/*
 * Amazake profiler viewer. Plain JS, no dependencies, no build step.
 * Report format: docs/profiler-format.md (format "amazake-profile", version 1).
 *
 * Security: every string inside a report is untrusted. The DOM is built only with
 * createElement / createElementNS / textContent; never innerHTML.
 */
'use strict';

(function () {
  const SVGNS = 'http://www.w3.org/2000/svg';
  const MAX_FILE_BYTES = 64 * 1024 * 1024;
  const ID_RE = /^[A-Za-z0-9]{1,64}$/;

  const GROUP_NAMES = {
    'main': 'Главный поток (тики)',
    'chunk-workers': 'Чанки (генерация/загрузка/свет)',
    'chunk-io': 'Чанки (диск)',
    'netty': 'Сеть',
    'gc': 'Сборщик мусора',
    'jit': 'JIT-компилятор',
    'plugins-async': 'Плагины (асинхронно)',
    'chat': 'Чат',
    'paper-async': 'Paper (прочее асинхронное)',
    'spark': 'spark',
    'amazake': 'Amazake',
    'other': 'Прочее',
  };

  // Colour follows the group id, never its rank. Groups without a slot fold into "rest".
  const GROUP_COLORS = {
    'main': 'var(--s1)',
    'chunk-workers': 'var(--s2)',
    'netty': 'var(--s3)',
    'gc': 'var(--s4)',
    'plugins-async': 'var(--s5)',
    'jit': 'var(--s6)',
    'chunk-io': 'var(--s7)',
  };
  const REST_COLOR = 'var(--s-rest)';

  const ENV_NAMES = { NORMAL: 'Обычный', NETHER: 'Незер', THE_END: 'Энд', CUSTOM: 'Свой' };

  const SEVERITY = {
    critical: { label: 'Критично', rank: 0 },
    serious: { label: 'Серьёзно', rank: 1 },
    warning: { label: 'Внимание', rank: 2 },
    info: { label: 'Заметка', rank: 3 },
    good: { label: 'Норма', rank: 4 },
  };

  const app = document.getElementById('app');
  const topnav = document.getElementById('topnav');
  const tip = document.getElementById('tooltip');

  /** Chart redraw callbacks for the current view (re-run on resize). */
  let charts = [];

  // ---------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------

  function el(tag, props, children) {
    const node = document.createElement(tag);
    if (props) {
      for (const key of Object.keys(props)) {
        const v = props[key];
        if (v === undefined || v === null || v === false) continue;
        if (key === 'class') node.className = v;
        else if (key === 'text') node.textContent = String(v);
        else if (key === 'href') node.href = v;
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), v);
        else node.setAttribute(key, v === true ? '' : String(v));
      }
    }
    append(node, children);
    return node;
  }

  function append(node, children) {
    if (children === undefined || children === null) return node;
    if (!Array.isArray(children)) children = [children];
    for (const c of children) {
      if (c === undefined || c === null || c === false) continue;
      node.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
    }
    return node;
  }

  function svg(tag, attrs) {
    const node = document.createElementNS(SVGNS, tag);
    if (attrs) {
      for (const key of Object.keys(attrs)) {
        const v = attrs[key];
        if (v === undefined || v === null) continue;
        if (key === 'text') node.textContent = String(v);
        else node.setAttribute(key, String(v));
      }
    }
    return node;
  }

  function isObj(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }
  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : (d === undefined ? 0 : d); }
  function numOrNull(v) { return typeof v === 'number' && isFinite(v) ? v : null; }
  function str(v, d) {
    if (typeof v === 'string') return v;
    if (typeof v === 'number' || typeof v === 'boolean') return String(v);
    return d === undefined ? '' : d;
  }
  function arr(v) { return Array.isArray(v) ? v : []; }

  function sum(a) { let s = 0; for (const v of a) if (v !== null) s += v; return s; }
  function mean(a) {
    let s = 0, k = 0;
    for (const v of a) if (v !== null && v !== undefined) { s += v; k++; }
    return k ? s / k : null;
  }
  function maxOf(a) {
    let m = null;
    for (const v of a) if (v !== null && v !== undefined && (m === null || v > m)) m = v;
    return m;
  }
  function minOf(a) {
    let m = null;
    for (const v of a) if (v !== null && v !== undefined && (m === null || v < m)) m = v;
    return m;
  }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  // ---------------------------------------------------------------------
  // Formatting (Russian)
  // ---------------------------------------------------------------------

  const NBSP = ' ';

  function fmtNum(x, digits) {
    if (x === null || x === undefined || !isFinite(x)) return '—';
    return x.toLocaleString('ru-RU', { minimumFractionDigits: digits || 0, maximumFractionDigits: digits || 0 });
  }

  function plural(n, forms) {
    const a = Math.abs(n) % 100, b = a % 10;
    if (a > 10 && a < 20) return forms[2];
    if (b > 1 && b < 5) return forms[1];
    if (b === 1) return forms[0];
    return forms[2];
  }

  function fmtCores(x) {
    if (x === null || x === undefined) return '—';
    return fmtNum(x, 2) + NBSP + 'ядра';
  }

  function fmtCoresShort(x) { return fmtNum(x, 2); }

  function fmtPct(frac, digits) {
    if (frac === null || frac === undefined || !isFinite(frac)) return '—';
    return fmtNum(frac * 100, digits || 0) + NBSP + '%';
  }

  function fmtBytes(b) {
    if (b === null || b === undefined || !isFinite(b)) return '—';
    const units = ['Б', 'КБ', 'МБ', 'ГБ', 'ТБ'];
    let u = 0, v = b;
    while (Math.abs(v) >= 1024 && u < units.length - 1) { v /= 1024; u++; }
    return fmtNum(v, u >= 3 ? 1 : 0) + NBSP + units[u];
  }

  function fmtMs(ms) {
    if (ms === null || ms === undefined || !isFinite(ms)) return '—';
    return fmtNum(ms, ms < 10 && ms % 1 ? 1 : 0) + NBSP + 'мс';
  }

  function fmtDuration(ms) {
    if (!isFinite(ms) || ms < 0) return '—';
    const s = Math.round(ms / 1000);
    if (s < 60) return s + NBSP + 'с';
    const m = Math.floor(s / 60), rs = s % 60;
    if (m < 60) return m + NBSP + 'мин' + (rs ? ' ' + rs + NBSP + 'с' : '');
    const h = Math.floor(m / 60), rm = m % 60;
    return h + NBSP + 'ч' + (rm ? ' ' + rm + NBSP + 'мин' : '');
  }

  /** Offset since the start as m:ss. */
  function fmtClock(ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    const m = Math.floor(s / 60), rs = s % 60;
    if (m >= 60) return Math.floor(m / 60) + ':' + String(m % 60).padStart(2, '0') + ':' + String(rs).padStart(2, '0');
    return m + ':' + String(rs).padStart(2, '0');
  }

  function fmtDateTime(d) {
    return d.toLocaleString('ru-RU', { year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }

  function timeLabel(R, t) {
    let s = fmtClock(t);
    if (R.start) {
      const d = new Date(R.start.getTime() + t);
      s += ' · ' + d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }
    return s;
  }

  // ---------------------------------------------------------------------
  // Report normalisation: never trust the shape of the input
  // ---------------------------------------------------------------------

  function normalize(raw) {
    const server = isObj(raw.server) ? raw.server : {};
    const system = isObj(raw.system) ? raw.system : {};
    const samples = arr(raw.samples).filter(isObj);
    const n = samples.length;
    let interval = num(raw.intervalMs, 1000);
    if (interval <= 0) interval = 1000;

    const t = [];
    for (let i = 0; i < n; i++) {
      let v = num(samples[i].t, (i + 1) * interval);
      if (i > 0 && v <= t[i - 1]) v = t[i - 1] + interval; // keep time strictly increasing
      t.push(v);
    }

    const field = (name) => samples.map((s) => numOrNull(s[name]));

    let coresFromSamples = 0;
    for (const s of samples) if (Array.isArray(s.cores)) coresFromSamples = Math.max(coresFromSamples, s.cores.length);
    let logicalCores = Math.round(num(system.logicalCores, 0));
    if (logicalCores <= 0) logicalCores = coresFromSamples || 1;

    const fitSeries = (s) => {
      const a = arr(s).map((v) => numOrNull(v));
      const out = new Array(n);
      for (let i = 0; i < n; i++) out[i] = i < a.length ? a[i] : null;
      return out;
    };

    const groups = arr(raw.groups).filter(isObj).map((g) => {
      const series = Array.isArray(g.series) ? fitSeries(g.series) : null;
      const avg = num(g.avgCores, NaN);
      return {
        id: str(g.group, 'other') || 'other',
        threads: Math.max(0, Math.round(num(g.threads, 0))),
        cpuMs: num(g.cpuMs, 0),
        avgCores: isFinite(avg) ? avg : (series ? (mean(series) || 0) : 0),
        peakCores: num(g.peakCores, series ? (maxOf(series) || 0) : 0),
        series: series,
      };
    }).sort((a, b) => b.avgCores - a.avgCores);

    const durationMs = num(raw.durationMs, n ? t[n - 1] : 0);
    const threads = arr(raw.threads).filter(isObj).map((th) => {
      const cpuMs = num(th.cpuMs, 0);
      return {
        name: str(th.name, '?'),
        group: str(th.group, 'other') || 'other',
        cpuMs: cpuMs,
        avgCores: num(th.avgCores, durationMs > 0 ? cpuMs / durationMs : 0),
        series: Array.isArray(th.series) ? fitSeries(th.series) : null,
      };
    }).sort((a, b) => b.avgCores - a.avgCores);

    const lagSpikes = arr(raw.lagSpikes).filter(isObj)
      .map((s) => ({ t: num(s.t, 0), ms: num(s.ms, 0) }))
      .sort((a, b) => b.ms - a.ms);

    let start = null;
    if (typeof raw.createdAt === 'string') {
      const d = new Date(raw.createdAt);
      if (!isNaN(d.getTime())) start = d;
    }

    const perCore = system.perCore === true && coresFromSamples > 0;
    const pools = isObj(server.threadPools) ? server.threadPools : {};

    return {
      raw: raw,
      n: n,
      t: t,
      interval: interval,
      durationMs: durationMs,
      start: start,
      server: {
        brand: str(server.brand, 'Сервер'),
        version: str(server.version, ''),
        minecraftVersion: str(server.minecraftVersion, ''),
        javaVersion: str(server.javaVersion, ''),
        javaVendor: str(server.javaVendor, ''),
        jvmArgs: arr(server.jvmArgs).map((a) => str(a, '')).filter((a) => a !== ''),
        onlinePlayers: numOrNull(server.onlinePlayers),
        maxPlayers: numOrNull(server.maxPlayers),
        plugins: arr(server.plugins).filter(isObj).map((p) => ({
          name: str(p.name, '?'), version: str(p.version, ''), enabled: p.enabled !== false,
        })),
        worlds: arr(server.worlds).filter(isObj).map((w) => ({
          name: str(w.name, '?'), environment: str(w.environment, ''),
          loadedChunks: numOrNull(w.loadedChunks), entities: numOrNull(w.entities),
          tileEntities: numOrNull(w.tileEntities), players: numOrNull(w.players),
        })),
        pools: {
          chunkWorkers: numOrNull(pools.chunkWorkers),
          chunkIo: numOrNull(pools.chunkIo),
          netty: numOrNull(pools.netty),
        },
      },
      system: {
        os: str(system.os, ''),
        arch: str(system.arch, ''),
        logicalCores: logicalCores,
        cpuModel: str(system.cpuModel, ''),
        totalMemoryBytes: numOrNull(system.totalMemoryBytes),
        heapMaxBytes: numOrNull(system.heapMaxBytes),
        perCore: perCore,
        perThreadSource: str(system.perThreadSource, ''),
      },
      cores: samples.map((s) => (Array.isArray(s.cores) ? s.cores.map((v) => clamp(num(v, 0), 0, 1)) : null)),
      coreCount: coresFromSamples,
      systemCpu: field('systemCpu'),
      processCpu: field('processCpu'),
      tps: field('tps'),
      msptAvg: field('msptAvg'),
      msptMax: field('msptMax'),
      heapUsed: field('heapUsedBytes'),
      gcCount: field('gcCount'),
      gcTimeMs: field('gcTimeMs'),
      players: field('players'),
      entities: field('entities'),
      loadedChunks: field('loadedChunks'),
      groups: groups,
      threads: threads,
      lagSpikes: lagSpikes,
      group: function (id) { return groups.find((g) => g.id === id) || null; },
    };
  }

  /** Sample index that contains offset t (first sample whose end is at or after t). */
  function sampleIndexAt(R, t) {
    for (let i = 0; i < R.n; i++) if (R.t[i] >= t) return i;
    return R.n - 1;
  }

  // ---------------------------------------------------------------------
  // Tooltip
  // ---------------------------------------------------------------------

  function showTip(evt, title, rows) {
    tip.textContent = '';
    tip.appendChild(el('div', { class: 'tt-title', text: title }));
    for (const r of rows) {
      const row = el('div', { class: 'tt-row' });
      if (r.color) {
        const sw = el('span', { class: 'swatch' + (r.line ? ' line' : '') });
        sw.style.background = r.color;
        row.appendChild(sw);
      }
      row.appendChild(el('span', { class: 'tt-name', text: r.name }));
      row.appendChild(el('span', { class: 'tt-val', text: r.value }));
      tip.appendChild(row);
    }
    tip.hidden = false;
    const pad = 14;
    const w = tip.offsetWidth, h = tip.offsetHeight;
    let x = evt.clientX + pad, y = evt.clientY + pad;
    if (x + w > window.innerWidth - 8) x = evt.clientX - pad - w;
    if (y + h > window.innerHeight - 8) y = evt.clientY - pad - h;
    tip.style.left = Math.max(8, x) + 'px';
    tip.style.top = Math.max(8, y) + 'px';
  }

  function hideTip() { tip.hidden = true; }

  window.addEventListener('scroll', hideTip, { passive: true });

  /** Pointer position in SVG user units. */
  function svgPoint(svgEl, evt, W, H) {
    const r = svgEl.getBoundingClientRect();
    return {
      x: (evt.clientX - r.left) * (W / (r.width || W)),
      y: (evt.clientY - r.top) * (H / (r.height || H)),
    };
  }

  // ---------------------------------------------------------------------
  // Chart plumbing
  // ---------------------------------------------------------------------

  function chartBox(draw, extraClass) {
    const box = el('div', { class: 'chart' + (extraClass ? ' ' + extraClass : '') });
    const entry = {
      box: box,
      lastW: -1,
      run: function (force) {
        const W = Math.max(240, Math.floor(box.clientWidth || 600));
        if (!force && W === entry.lastW) return;
        entry.lastW = W;
        box.textContent = '';
        draw(box, W);
      },
    };
    box._redraw = () => entry.run(true);
    charts.push(entry);
    return box;
  }

  function drawAllCharts(force) {
    for (const c of charts) {
      if (!c.box.isConnected) continue;
      try { c.run(force); } catch (e) { reportSectionFailure(c.box, e); }
    }
  }

  let resizeTimer = 0;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(() => { hideTip(); drawAllCharts(false); }, 120);
  });

  function niceStep(raw) {
    if (!(raw > 0)) return 1;
    const p = Math.pow(10, Math.floor(Math.log10(raw)));
    const f = raw / p;
    const m = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
    return m * p;
  }

  function niceTicks(max, count) {
    if (!(max > 0)) max = 1;
    const step = niceStep(max / Math.max(1, count));
    const top = Math.ceil(max / step - 1e-9) * step;
    const ticks = [];
    for (let v = 0; v <= top + step * 1e-6; v += step) ticks.push(Math.round(v / step) * step);
    return ticks;
  }

  function timeTicks(t0, t1, pixels) {
    const span = Math.max(1, t1 - t0);
    const maxTicks = Math.max(2, Math.floor(pixels / 64));
    const steps = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200].map((s) => s * 1000);
    let step = steps[steps.length - 1];
    for (const s of steps) if (span / s <= maxTicks) { step = s; break; }
    const out = [];
    for (let v = Math.ceil(t0 / step) * step; v <= t1 + 1e-6; v += step) out.push(v);
    return out;
  }

  function binaryNearest(xs, x) {
    let lo = 0, hi = xs.length - 1;
    while (hi - lo > 1) {
      const mid = (lo + hi) >> 1;
      if (xs[mid] < x) lo = mid; else hi = mid;
    }
    return Math.abs(xs[lo] - x) <= Math.abs(xs[hi] - x) ? lo : hi;
  }

  /**
   * Time-series chart. opts:
   *   series: [{name, values, color, kind: 'line'|'bar', dashed, width}]
   *   stacked: bool (areas, values stacked in order: first at the bottom)
   *   height, yFmt(v), valFmt(v), refs: [{y, label, strong, include}], fixedMax, minMax,
   *   tipRows(i) -> rows (optional), tipTotal: bool
   */
  function tsChart(R, opts) {
    return chartBox((box, W) => drawTs(box, W, R, opts()));
  }

  function drawTs(box, W, R, o) {
    const n = R.n;
    if (!n) {
      box.appendChild(el('p', { class: 'empty', text: 'В отчёте нет замеров — графику нечего показать.' }));
      return;
    }
    const H = o.height || 220;
    const series = o.series.filter((s) => s && s.values);
    const isBar = series.some((s) => s.kind === 'bar');

    // stacking
    const lower = [], upper = [];
    if (o.stacked) {
      const acc = new Array(n).fill(0);
      for (let k = 0; k < series.length; k++) {
        lower.push(acc.slice());
        for (let i = 0; i < n; i++) acc[i] += Math.max(0, series[k].values[i] || 0);
        upper.push(acc.slice());
      }
    }
    let dataMax = 0;
    if (o.stacked) dataMax = upper.length ? (maxOf(upper[upper.length - 1]) || 0) : 0;
    else for (const s of series) dataMax = Math.max(dataMax, maxOf(s.values) || 0);

    let yMax;
    if (o.fixedMax) yMax = o.fixedMax;
    else {
      yMax = Math.max(dataMax * 1.08, o.minMax || 0);
      for (const r of o.refs || []) if (r.include) yMax = Math.max(yMax, r.y * 1.06);
      if (!(yMax > 0)) yMax = 1;
    }
    const scale = o.yScale || 1;
    let ticks = niceTicks(yMax / scale, Math.max(2, Math.floor(H / 48))).map((v) => v * scale);
    if (o.fixedMax) ticks = ticks.filter((v) => v <= o.fixedMax + 1e-9);
    else yMax = ticks[ticks.length - 1];
    const yFmt = o.yFmt || ((v) => fmtNum(v, 1));
    const yLabels = ticks.map(yFmt);
    const ml = Math.max(...yLabels.map((s) => s.length)) * 6.6 + 12;
    const mr = 12, mt = 12, mb = 26;
    const pw = Math.max(40, W - ml - mr), ph = H - mt - mb;

    const t0 = R.t[0], t1 = R.t[n - 1];
    const span = t1 - t0;
    const pad = isBar ? (n > 1 ? span / (n - 1) / 2 : R.interval / 2) : 0;
    const d0 = n > 1 ? t0 - pad : t0 - R.interval / 2;
    const d1 = n > 1 ? t1 + pad : t0 + R.interval / 2;
    const X = (i) => ml + (R.t[i] - d0) / (d1 - d0) * pw;
    const XT = (t) => ml + (t - d0) / (d1 - d0) * pw;
    const Y = (v) => mt + ph - clamp(v / yMax, 0, 1.02) * ph;

    const root = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, role: 'img' });
    if (o.ariaLabel) root.setAttribute('aria-label', o.ariaLabel);

    // grid + y labels
    for (let k = 0; k < ticks.length; k++) {
      const y = Y(ticks[k]);
      root.appendChild(svg('line', { x1: ml, x2: ml + pw, y1: y, y2: y, class: k === 0 ? 'axisline' : 'gridline' }));
      root.appendChild(svg('text', { x: ml - 8, y: y + 4, 'text-anchor': 'end', text: yLabels[k] }));
    }
    // x labels
    for (const tt of timeTicks(d0, d1, pw)) {
      const x = XT(tt);
      if (x < ml - 1 || x > ml + pw + 1) continue;
      root.appendChild(svg('line', { x1: x, x2: x, y1: mt + ph, y2: mt + ph + 4, class: 'axisline' }));
      root.appendChild(svg('text', { x: x, y: H - 6, 'text-anchor': 'middle', text: fmtClock(tt) }));
    }

    const marks = svg('g');
    root.appendChild(marks);

    if (o.stacked) {
      for (let k = 0; k < series.length; k++) {
        const s = series[k];
        if (n === 1) {
          const x = X(0);
          const r = svg('rect', { x: x - 10, width: 20, y: Y(upper[k][0]), height: Math.max(0, Y(lower[k][0]) - Y(upper[k][0])) });
          r.style.fill = s.color;
          marks.appendChild(r);
          continue;
        }
        let d = '';
        for (let i = 0; i < n; i++) d += (i ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(upper[k][i]).toFixed(1);
        for (let i = n - 1; i >= 0; i--) d += 'L' + X(i).toFixed(1) + ',' + Y(lower[k][i]).toFixed(1);
        const p = svg('path', { d: d + 'Z', 'stroke-width': 1, 'stroke-linejoin': 'round' });
        p.style.fill = s.color;
        p.style.stroke = 'var(--surface)';
        marks.appendChild(p);
      }
    } else {
      for (const s of series) {
        if (s.kind === 'bar') {
          const bw = n > 1 ? Math.max(1, pw / n - 2) : 24;
          for (let i = 0; i < n; i++) {
            const v = s.values[i];
            if (v === null || v <= 0) continue;
            const y = Y(v);
            const r = svg('rect', { x: X(i) - bw / 2, y: y, width: bw, height: Math.max(1, mt + ph - y), rx: Math.min(2, bw / 2) });
            r.style.fill = s.color;
            marks.appendChild(r);
          }
          continue;
        }
        let d = '', open = false;
        for (let i = 0; i < n; i++) {
          const v = s.values[i];
          if (v === null) { open = false; continue; }
          const isolated = (i === 0 || s.values[i - 1] === null) && (i === n - 1 || s.values[i + 1] === null);
          if (isolated) {
            const c = svg('circle', { cx: X(i), cy: Y(v), r: 3 });
            c.style.fill = s.color;
            marks.appendChild(c);
          }
          d += (open ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(v).toFixed(1);
          open = true;
        }
        if (d) {
          const p = svg('path', { d: d, fill: 'none', 'stroke-width': s.width || 2, 'stroke-linejoin': 'round', 'stroke-linecap': 'round' });
          p.style.stroke = s.color;
          if (s.dashed) p.setAttribute('stroke-dasharray', '5 4');
          marks.appendChild(p);
        }
      }
    }

    // reference lines
    for (const r of o.refs || []) {
      if (!(r.y > 0) || r.y > yMax) continue;
      const y = Y(r.y);
      const line = svg('line', { x1: ml, x2: ml + pw, y1: y, y2: y, 'stroke-dasharray': '6 4', 'stroke-width': r.strong ? 1.5 : 1 });
      line.style.stroke = r.strong ? 'var(--ref-strong)' : 'var(--ref)';
      root.appendChild(line);
      if (r.label) {
        const label = svg('text', { x: ml + pw - 4, y: y - 5, 'text-anchor': 'end', class: 'ref-label', text: r.label });
        root.appendChild(label);
      }
    }

    // hover layer
    const cross = svg('line', { y1: mt, y2: mt + ph, class: 'crosshair', visibility: 'hidden' });
    root.appendChild(cross);
    const dots = series.filter((s) => s.kind !== 'bar').map((s) => {
      const c = svg('circle', { r: 4, visibility: 'hidden', 'stroke-width': 2 });
      c.style.fill = s.color;
      c.style.stroke = 'var(--surface)';
      c.style.pointerEvents = 'none';
      root.appendChild(c);
      return c;
    });
    const hit = svg('rect', { x: ml, y: mt, width: pw, height: ph, class: 'hit' });
    root.appendChild(hit);
    const xs = R.t.map((_, i) => X(i));
    const valFmt = o.valFmt || yFmt;

    const onMove = (evt) => {
      const pt = svgPoint(root, evt, W, H);
      const i = n === 1 ? 0 : binaryNearest(xs, pt.x);
      const x = xs[i];
      cross.setAttribute('x1', x); cross.setAttribute('x2', x);
      cross.setAttribute('visibility', 'visible');
      let k = 0;
      series.forEach((s, si) => {
        if (s.kind === 'bar') return;
        const dot = dots[k++];
        const v = o.stacked ? upper[si][i] : s.values[i];
        if (v === null || v === undefined) { dot.setAttribute('visibility', 'hidden'); return; }
        dot.setAttribute('cx', x); dot.setAttribute('cy', Y(v));
        dot.setAttribute('visibility', 'visible');
      });
      let rows;
      if (o.tipRows) rows = o.tipRows(i);
      else {
        rows = series.map((s) => ({ color: s.color, name: s.name, value: s.values[i] === null ? 'нет данных' : valFmt(s.values[i]), line: s.kind !== 'bar' && !o.stacked }));
        if (o.stacked) {
          rows.reverse();
          if (o.tipTotal !== false) rows.push({ name: 'Всего', value: valFmt(upper.length ? upper[upper.length - 1][i] : 0) });
        }
      }
      showTip(evt, timeLabel(R, R.t[i]), rows);
    };
    const onLeave = () => {
      cross.setAttribute('visibility', 'hidden');
      for (const d of dots) d.setAttribute('visibility', 'hidden');
      hideTip();
    };
    hit.addEventListener('pointermove', onMove);
    hit.addEventListener('pointerdown', onMove);
    hit.addEventListener('pointerleave', onLeave);

    box.appendChild(root);
  }

  function legend(items) {
    const ul = el('ul', { class: 'legend' });
    for (const it of items) {
      const sw = el('span', { class: 'swatch' + (it.line ? ' line' : '') + (it.dashed ? ' dashed' : '') });
      if (!it.dashed) sw.style.background = it.color;
      else if (it.color) sw.style.borderTopColor = it.color;
      ul.appendChild(el('li', null, [sw, it.name]));
    }
    return ul;
  }

  // ---------------------------------------------------------------------
  // Heatmap of per-core load
  // ---------------------------------------------------------------------

  function heatmap(R) {
    return chartBox((box, W) => {
      const n = R.n, rows = R.coreCount;
      const cellH = clamp(Math.floor(288 / rows), 3, 14);
      const ml = 58, mr = 8, mt = 4, mb = 26;
      const pw = Math.max(40, W - ml - mr);
      const H = mt + rows * cellH + mb;
      const cw = pw / n;
      const root = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, role: 'img', 'aria-label': 'Загрузка ядер по времени' });
      const g = svg('g', { 'shape-rendering': 'crispEdges' });
      root.appendChild(g);
      for (let i = 0; i < n; i++) {
        const c = R.cores[i];
        for (let r = 0; r < rows; r++) {
          const v = c && r < c.length ? c[r] : null;
          const cls = v === null ? 'hm0' : 'hm' + Math.min(9, Math.floor(v * 10));
          g.appendChild(svg('rect', {
            x: (ml + i * cw).toFixed(2), y: mt + r * cellH,
            width: (cw + 0.6).toFixed(2), height: Math.max(1, cellH - (cellH > 5 ? 1 : 0)), class: cls,
          }));
        }
      }
      const every = Math.max(1, Math.ceil(13 / cellH));
      for (let r = 0; r < rows; r += every) {
        root.appendChild(svg('text', { x: ml - 8, y: mt + r * cellH + cellH / 2 + 4, 'text-anchor': 'end', text: 'ядро ' + r }));
      }
      const d0 = R.t[0] - R.interval, d1 = R.t[n - 1];
      for (const tt of timeTicks(d0, d1, pw)) {
        const x = ml + (tt - d0) / (d1 - d0) * pw;
        if (x < ml - 1 || x > ml + pw + 1) continue;
        root.appendChild(svg('line', { x1: x, x2: x, y1: mt + rows * cellH, y2: mt + rows * cellH + 4, class: 'axisline' }));
        root.appendChild(svg('text', { x: x, y: H - 6, 'text-anchor': 'middle', text: fmtClock(tt) }));
      }
      const outline = svg('rect', { fill: 'none', 'stroke-width': 2, visibility: 'hidden' });
      outline.style.stroke = 'var(--ink)';
      outline.style.pointerEvents = 'none';
      root.appendChild(outline);
      const hit = svg('rect', { x: ml, y: mt, width: pw, height: rows * cellH, class: 'hit' });
      root.appendChild(hit);
      const onMove = (evt) => {
        const pt = svgPoint(root, evt, W, H);
        const i = clamp(Math.floor((pt.x - ml) / cw), 0, n - 1);
        const r = clamp(Math.floor((pt.y - mt) / cellH), 0, rows - 1);
        outline.setAttribute('x', ml + i * cw); outline.setAttribute('y', mt + r * cellH);
        outline.setAttribute('width', Math.max(2, cw)); outline.setAttribute('height', cellH);
        outline.setAttribute('visibility', 'visible');
        const c = R.cores[i];
        const v = c && r < c.length ? c[r] : null;
        const busy = c ? c.reduce((a, b) => a + b, 0) : null;
        showTip(evt, 'Ядро ' + r + ' · ' + timeLabel(R, R.t[i]), [
          { name: 'Загрузка ядра', value: v === null ? 'нет данных' : fmtPct(v) },
          { name: 'Все ядра в этот момент', value: busy === null ? '—' : fmtCores(busy) },
        ]);
      };
      hit.addEventListener('pointermove', onMove);
      hit.addEventListener('pointerdown', onMove);
      hit.addEventListener('pointerleave', () => { outline.setAttribute('visibility', 'hidden'); hideTip(); });
      box.appendChild(root);
    });
  }

  function heatLegend() {
    const ramp = el('span', { class: 'hm-legend-ramp' });
    for (let k = 0; k < 10; k++) {
      const s = el('span');
      s.style.background = 'var(--hm' + k + ')';
      ramp.appendChild(s);
    }
    return el('div', { class: 'hm-legend' }, ['0 %', ramp, '100 % загрузки ядра']);
  }

  // ---------------------------------------------------------------------
  // Sparkline (threads table)
  // ---------------------------------------------------------------------

  function sparkline(R, values, name) {
    const W = 120, H = 26;
    const n = values.length;
    const box = el('span', { class: 'spark' });
    const top = Math.max(0.05, maxOf(values) || 0);
    const root = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, role: 'img', 'aria-label': 'Нагрузка потока по времени' });
    const X = (i) => (n > 1 ? 1 + i / (n - 1) * (W - 2) : W / 2);
    const Y = (v) => H - 2 - clamp(v / top, 0, 1) * (H - 4);
    let d = '', open = false;
    for (let i = 0; i < n; i++) {
      const v = values[i];
      if (v === null) { open = false; continue; }
      d += (open ? 'L' : 'M') + X(i).toFixed(1) + ',' + Y(v).toFixed(1);
      open = true;
    }
    if (n > 1 && d && values.every((v) => v !== null)) {
      root.appendChild(svg('path', { d: d + 'L' + X(n - 1).toFixed(1) + ',' + (H - 1) + 'L' + X(0).toFixed(1) + ',' + (H - 1) + 'Z', class: 'spark-area' }));
    }
    if (d) root.appendChild(svg('path', { d: d, class: 'spark-line' }));
    const cross = svg('line', { y1: 0, y2: H, class: 'crosshair', visibility: 'hidden' });
    cross.style.stroke = 'var(--ink-2)';
    root.appendChild(cross);
    const hit = svg('rect', { x: 0, y: 0, width: W, height: H, fill: 'transparent' });
    root.appendChild(hit);
    const onMove = (evt) => {
      if (!n) return;
      const pt = svgPoint(root, evt, W, H);
      const i = n > 1 ? clamp(Math.round((pt.x - 1) / (W - 2) * (n - 1)), 0, n - 1) : 0;
      cross.setAttribute('x1', X(i)); cross.setAttribute('x2', X(i));
      cross.setAttribute('visibility', 'visible');
      showTip(evt, name, [{ name: timeLabel(R, R.t[i] !== undefined ? R.t[i] : 0), value: values[i] === null ? 'нет данных' : fmtCores(values[i]) }]);
    };
    hit.addEventListener('pointermove', onMove);
    hit.addEventListener('pointerdown', onMove);
    hit.addEventListener('pointerleave', () => { cross.setAttribute('visibility', 'hidden'); hideTip(); });
    box.appendChild(root);
    return box;
  }

  // ---------------------------------------------------------------------
  // Tables
  // ---------------------------------------------------------------------

  function table(columns, rows) {
    const thead = el('thead', null, el('tr', null, columns.map((c) => el('th', { class: c.cls || null, scope: 'col', text: c.label }))));
    const tbody = el('tbody');
    fillBody(tbody, columns, rows);
    return { wrap: el('div', { class: 'table-wrap' }, el('table', null, [thead, tbody])), tbody: tbody };
  }

  function fillBody(tbody, columns, rows) {
    tbody.textContent = '';
    for (const row of rows) {
      tbody.appendChild(el('tr', null, columns.map((c) => {
        const v = c.render(row);
        return el('td', { class: c.cls || null }, v === null || v === undefined ? '—' : v);
      })));
    }
  }

  function groupName(id) {
    return Object.prototype.hasOwnProperty.call(GROUP_NAMES, id) ? GROUP_NAMES[id] : id;
  }

  function groupColor(id) {
    return Object.prototype.hasOwnProperty.call(GROUP_COLORS, id) ? GROUP_COLORS[id] : REST_COLOR;
  }

  function groupCell(id) {
    const sw = el('span', { class: 'swatch' });
    sw.style.background = groupColor(id);
    return el('span', { class: 'group-cell' }, [sw, groupName(id)]);
  }

  function meter(frac, hot) {
    const m = el('div', { class: 'meter' + (hot ? ' hot' : '') });
    const fill = el('span');
    fill.style.width = clamp(frac * 100, 0, 100).toFixed(1) + '%';
    m.appendChild(fill);
    return m;
  }

  // ---------------------------------------------------------------------
  // Findings (rule-based recommendations)
  // ---------------------------------------------------------------------

  function coresWord(n) { return plural(n, ['ядро', 'ядра', 'ядер']); }

  function computeFindings(R) {
    const out = [];
    const add = (severity, title, text) => out.push({ severity: severity, title: title, text: text });
    const C = R.system.logicalCores;
    const n = R.n;

    if (!n) {
      add('serious', 'В отчёте нет замеров', 'Замер прервался или файл повреждён. Запустите /amazake profile ещё раз и дождитесь окончания.');
      return out;
    }
    if (n < 5) add('info', 'Замер очень короткий: ' + n + ' ' + plural(n, ['замер', 'замера', 'замеров']),
      'Выводы по нескольким секундам ненадёжны. Для точной картины снимайте профиль хотя бы 30–60 секунд, лучше в момент, когда игроки жалуются на лаги.');

    const sysAvg = mean(R.systemCpu);
    const procAvg = mean(R.processCpu);
    const sysCores = sysAvg === null ? null : sysAvg * C;
    const procCores = procAvg === null ? null : procAvg * C;

    // main thread
    const main = R.group('main');
    if (main) {
      const idle = sysCores === null ? null : Math.max(0, C - sysCores);
      const idleN = idle === null ? 0 : Math.floor(idle);
      if (main.avgCores >= 0.8) {
        const sev = main.avgCores >= 0.9 ? 'serious' : 'warning';
        const base = 'Главный поток занят в среднем на ' + fmtCores(main.avgCores) + ' (пик ' + fmtCoresShort(main.peakCores) + '). ' +
          'Тики Minecraft считаются в одном потоке, поэтому он не может использовать больше одного ядра. ';
        if (idle === null) {
          add(sev, 'Главный поток упирается в одно ядро',
            base + 'Уменьшайте работу внутри тика: simulation-distance, лимиты мобов и сущностей, фермы и редстоун, тяжёлые синхронные задачи плагинов. ' +
            'Что именно тратит время в тике, покажет spark (/spark profiler).');
        } else if (idleN >= 1) {
          add(sev, 'Главный поток упирается в одно ядро, а ' + idleN + ' ' + coresWord(idleN) + ' простаивают',
            base + 'Добавление ядер или более мощный тариф с тем же процессором не помогут. Помогает уменьшение работы внутри тика: ' +
            'simulation-distance, лимиты мобов и сущностей, большие фермы и редстоун, тяжёлые синхронные задачи плагинов. ' +
            'Что именно тратит время в тике, покажет spark (/spark profiler). Если есть выбор, берите процессор с более высокой частотой.');
        } else {
          add(sev, 'Главный поток почти всё время занят, и свободных ядер на машине тоже нет',
            base + 'При этом остальные ядра тоже загружены. Сначала разгрузите машину (см. выводы о других процессах и потоках), затем уменьшайте работу внутри тика.');
        }
      } else if (main.peakCores >= 0.95) {
        add('info', 'Главный поток иногда упирается в ядро',
          'В среднем занят на ' + fmtCores(main.avgCores) + ', но в отдельные секунды доходит до ' + fmtCoresShort(main.peakCores) + '. ' +
          'Посмотрите график «Главный поток», чтобы найти эти моменты и сравнить их с лаг-спайками.');
      }
    }

    // TPS
    const tpsAvg = mean(R.tps), tpsMin = minOf(R.tps);
    if (tpsAvg !== null) {
      if (tpsAvg < 19.5 || tpsMin < 18) {
        const sev = tpsAvg < 15 ? 'critical' : (tpsAvg < 18 || tpsMin < 15) ? 'serious' : 'warning';
        add(sev, 'TPS проседает: в среднем ' + fmtNum(tpsAvg, 2) + ', минимум ' + fmtNum(tpsMin, 1),
          'Норма — 20 TPS. Ниже 20 игровое время идёт медленнее: мобы, механизмы и фермы работают с замедлением, игроки видят задержки. ' +
          'Причина почти всегда — долгие тики главного потока, смотрите MSPT и лаг-спайки.');
      } else {
        add('good', 'TPS в норме: в среднем ' + fmtNum(tpsAvg, 2) + ', минимум ' + fmtNum(tpsMin, 1), 'Сервер успевал выполнять 20 тиков в секунду.');
      }
    }

    // MSPT spikes
    const msptPeak = maxOf(R.msptMax);
    const spikeCount = R.lagSpikes.length || R.msptMax.filter((v) => v !== null && v > 50).length;
    if (spikeCount > 0 || (msptPeak !== null && msptPeak > 50)) {
      const peak = Math.max(msptPeak || 0, R.lagSpikes.length ? R.lagSpikes[0].ms : 0);
      const sev = peak >= 500 ? 'critical' : (peak >= 150 || spikeCount >= Math.max(10, n * 0.2)) ? 'serious' : 'warning';
      let gcHits = 0;
      for (const s of R.lagSpikes) {
        const i = sampleIndexAt(R, s.t);
        if ((R.gcTimeMs[i] || 0) >= 50) gcHits++;
      }
      let text = 'Тик дольше 50 мс игроки замечают как подлагивание (откат блоков, рывки мобов). ';
      if (gcHits) text += gcHits + ' из ' + R.lagSpikes.length + ' совпадают по времени с долгими паузами сборщика мусора (50 мс и больше за секунду) — см. раздел «Память». ';
      text += 'Сравните время спайков с графиками групп: всплеск «Чанков» указывает на генерацию или загрузку мира, всплеск «Плагинов» — на тяжёлую задачу плагина.';
      add(sev, 'Лаг-спайки: ' + spikeCount + ' ' + plural(spikeCount, ['тик', 'тика', 'тиков']) + ' дольше 50 мс, самый долгий ' + fmtMs(peak), text);
    }
    const msptAvg = mean(R.msptAvg);
    if (msptAvg !== null && msptAvg >= 40) {
      add(msptAvg >= 50 ? 'serious' : 'warning', 'Средняя длительность тика ' + fmtMs(msptAvg) + ' — запас до 50 мс почти исчерпан',
        'Пока тик укладывается в 50 мс, TPS держится на 20, но любой рост нагрузки (новые игроки, фермы, загрузка чанков) сразу превратится в лаги.');
    }

    // GC
    const gcTotal = sum(R.gcTimeMs);
    const gcShare = R.durationMs > 0 ? gcTotal / R.durationMs : 0;
    const gcGroup = R.group('gc');
    const gcCores = gcGroup ? gcGroup.avgCores : 0;
    if (gcShare >= 0.03 || gcCores >= 0.5) {
      const sev = gcShare >= 0.1 || gcCores >= 1.5 ? 'serious' : 'warning';
      const runs = sum(R.gcCount);
      add(sev, 'Сборщик мусора работает много: ' + fmtPct(gcShare, 1) + ' времени замера',
        'За замер ' + fmtNum(runs) + ' ' + plural(runs, ['сборка', 'сборки', 'сборок']) + ', суммарно ' + fmtMs(gcTotal) +
        ' (в среднем ' + fmtMs(R.durationMs > 0 ? gcTotal / (R.durationMs / 1000) : 0) + ' в секунду); потоки GC используют в среднем ' + fmtCores(gcCores) + '. ' +
        'Частые и долгие сборки — признак нехватки памяти или того, что что-то создаёт очень много объектов (плагины, сущности, загрузка чанков). ' +
        'Проверьте размер -Xmx и флаги Aikar для G1.');
    }

    // heap
    const heapMax = R.system.heapMaxBytes;
    const heapPeak = maxOf(R.heapUsed);
    if (heapMax && heapPeak !== null) {
      const frac = heapPeak / heapMax;
      if (frac > 0.85) {
        add(frac > 0.95 ? 'serious' : 'warning', 'Память почти закончилась: занято до ' + fmtPct(frac) + ' кучи',
          'Пик ' + fmtBytes(heapPeak) + ' из ' + fmtBytes(heapMax) + ' (-Xmx). Когда куча заполнена, сборщик мусора запускается чаще и паузы растут, ' +
          'в худшем случае сервер падает с OutOfMemoryError. Увеличьте -Xmx (если на VPS есть свободная память) или найдите, что её занимает.');
      }
    }

    // chunk workers
    const cw = R.group('chunk-workers');
    if (cw) {
      const pool = R.server.pools.chunkWorkers || cw.threads || 1;
      if (cw.avgCores >= 0.8 * pool || cw.peakCores >= 0.9 * pool) {
        const always = cw.avgCores >= 0.8 * pool;
        add(always ? 'serious' : 'warning',
          (always ? 'Пул чанков работает на пределе: ' : 'Пул чанков в пиках загружен полностью: ') +
          fmtCoresShort(always ? cw.avgCores : cw.peakCores) + ' из ' + pool + ' ' + plural(pool, ['потока', 'потоков', 'потоков']),
          'Генерация, загрузка и освещение чанков не успевают: игроки видят недогруженный мир, а главный поток может ждать чанки. ' +
          'Заранее прогенерируйте мир (например, плагином Chunky), уменьшите view-distance, ограничьте быстрые полёты на элитрах по новым землям.');
      }
    }

    // netty
    const net = R.group('netty');
    if (net) {
      const pool = R.server.pools.netty || net.threads || 1;
      if (net.avgCores >= 1.0 || net.avgCores >= 0.6 * pool) {
        add(net.avgCores >= 0.9 * pool ? 'serious' : 'warning', 'Сеть заметно нагружает процессор: ' + fmtCores(net.avgCores),
          'Потоки Netty заняты сжатием, шифрованием и отправкой пакетов. Возможные причины: много игроков, слишком низкий network-compression-threshold, ' +
          'атака ботов или флуд пакетами. Проверьте network-compression-threshold в server.properties (256 и выше снижает нагрузку) и защиту от ботов.');
      }
    }

    // plugins async
    const pa = R.group('plugins-async');
    if (pa && pa.avgCores >= 0.25) {
      const top = R.threads.filter((t) => t.group === 'plugins-async').slice(0, 3);
      const names = top.map((t) => '«' + t.name + '» — ' + fmtCores(t.avgCores)).join('; ');
      add(pa.avgCores >= 1.5 ? 'serious' : pa.avgCores >= 0.5 ? 'warning' : 'info',
        'Асинхронные задачи плагинов используют ' + fmtCores(pa.avgCores),
        (names ? 'Больше всего: ' + names + '. ' : '') +
        'Имя плагина обычно стоит в конце имени потока Craft Scheduler Thread. Эти задачи не тормозят тики напрямую, но отнимают процессор у остальных. ' +
        'Проверьте настройки этих плагинов: частоту сохранений и синхронизаций, логирование, работу с базой данных.');
    }

    // JIT
    const jit = R.group('jit');
    if (jit && jit.avgCores >= 0.5) {
      add('info', 'JIT-компилятор активно работает: ' + fmtCores(jit.avgCores),
        'Так бывает в первые минуты после запуска, пока Java компилирует горячий код. Если сервер запущен давно, это необычно, но обычно безвредно.');
    }

    // whole machine and other processes
    if (sysCores !== null && procCores !== null) {
      const other = R.systemCpu.map((s, i) => (s === null || R.processCpu[i] === null ? null : Math.max(0, s - R.processCpu[i]) * C));
      const otherAvg = mean(other) || 0;
      const otherPeak = maxOf(other) || 0;
      const machineFrac = sysCores / C;
      if (otherAvg >= 1.0 || otherAvg / C >= 0.15) {
        add(otherAvg / C >= 0.35 || machineFrac >= 0.85 ? 'serious' : 'warning',
          'Другие процессы на машине тоже нагружают процессор: в среднем ' + fmtCores(otherAvg) + ' (пик ' + fmtCoresShort(otherPeak) + ')',
          'Это нагрузка не от сервера Minecraft: например, Discord-бот, база данных, бэкапы или другой сервер на той же VPS. ' +
          'Сам сервер в это время использует ' + fmtCores(procCores) + '. Они конкурируют за процессор, а на VPS ядра ещё и делятся с соседями по хосту. ' +
          'Посмотрите, что это за процессы, командой top или htop на VPS (сортировка по CPU) и сравните моменты пиков с графиком «Сервер и остальные процессы».');
      } else if (otherPeak >= 2) {
        add('info', 'Другие процессы иногда нагружают машину: пик ' + fmtCores(otherPeak),
          'В среднем посторонняя нагрузка небольшая (' + fmtCores(otherAvg) + '), но в отдельные моменты что-то ещё на машине (например, Discord-бот или бэкап) заметно занимает процессор.');
      }
      if (machineFrac >= 0.85) {
        add('critical', 'Процессор машины загружен почти полностью: ' + fmtPct(machineFrac),
          'Свободных ядер практически нет, все потоки ждут очереди. Разгрузите машину или перейдите на тариф с большим числом ядер.');
      }
    }

    // per-core imbalance
    if (R.system.perCore) {
      const avg = coreAverages(R);
      if (avg.length >= 2) {
        const sorted = avg.map((v, i) => ({ v: v, i: i })).sort((a, b) => b.v - a.v);
        const med = medianOf(avg);
        const topCore = sorted[0];
        if (topCore.v >= 0.6 && topCore.v - med >= 0.35) {
          add(topCore.v >= 0.9 ? 'warning' : 'info',
            'Нагрузка по ядрам неравномерная: ядро ' + topCore.i + ' занято на ' + fmtPct(topCore.v) + ', медиана ' + fmtPct(med),
            'Обычно так выглядит главный поток сервера: он однопоточный и грузит одно ядро, пока остальные свободны. ' +
            'Если при этом есть лаги, сервер упирается в производительность одного ядра; помогает процессор с более высокой частотой, а не больше ядер.');
        }
      }
    }

    if (!out.some((f) => SEVERITY[f.severity].rank <= SEVERITY.warning.rank)) {
      add('good', 'Серьёзных проблем не найдено', 'По этому замеру сервер справляется с нагрузкой. Если лаги бывают в другое время, снимите профиль именно в такой момент.');
    }
    out.sort((a, b) => SEVERITY[a.severity].rank - SEVERITY[b.severity].rank);
    return out;
  }

  function coreAverages(R) {
    const sums = [], counts = [];
    for (const c of R.cores) {
      if (!c) continue;
      for (let k = 0; k < c.length; k++) {
        sums[k] = (sums[k] || 0) + c[k];
        counts[k] = (counts[k] || 0) + 1;
      }
    }
    const out = [];
    for (let k = 0; k < sums.length; k++) out.push(counts[k] ? sums[k] / counts[k] : 0);
    return out;
  }

  function medianOf(a) {
    if (!a.length) return 0;
    const s = a.slice().sort((x, y) => x - y);
    const m = s.length >> 1;
    return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
  }

  // ---------------------------------------------------------------------
  // Report sections
  // ---------------------------------------------------------------------

  function card(id, title, desc, body) {
    return el('section', { class: 'card', id: id || null }, [
      el('h2', { text: title }),
      desc ? el('p', { class: 'card-desc', text: desc }) : null,
    ].concat(body));
  }

  function fact(label, value) {
    return el('div', { class: 'fact' }, [el('div', { class: 'fact-label', text: label }), el('div', { class: 'fact-value', text: value || '—' })]);
  }

  function sectionHeader(R) {
    const s = R.server, sys = R.system;
    const title = (s.brand + ' ' + s.version).trim();
    const head = el('div', { class: 'report-head' }, [
      el('div', null, [
        el('h1', { text: title || 'Отчёт профайлера' }),
        el('p', { class: 'sub', text: 'Отчёт профайлера' + (R.start ? ' от ' + fmtDateTime(R.start) : '') }),
      ]),
    ]);
    const players = s.onlinePlayers === null ? '—' : fmtNum(s.onlinePlayers) + (s.maxPlayers !== null ? ' из ' + fmtNum(s.maxPlayers) : '');
    const java = [s.javaVersion, s.javaVendor].filter(Boolean).join(', ');
    const os = [sys.os, sys.arch].filter(Boolean).join(', ');
    const heap = sys.heapMaxBytes === null ? '—' :
      fmtBytes(sys.heapMaxBytes) + (sys.totalMemoryBytes ? ' (ОЗУ ' + fmtBytes(sys.totalMemoryBytes) + ')' : '');
    const facts = el('div', { class: 'facts' }, [
      fact('Сервер', title),
      fact('Minecraft', s.minecraftVersion),
      fact('Начало замера', R.start ? fmtDateTime(R.start) : 'неизвестно'),
      fact('Длительность', fmtDuration(R.durationMs) + ' · ' + R.n + ' ' + plural(R.n, ['замер', 'замера', 'замеров'])),
      fact('Игроки', players),
      fact('Ядра', fmtNum(sys.logicalCores) + ' ' + plural(sys.logicalCores, ['логическое', 'логических', 'логических'])),
      fact('Процессор', sys.cpuModel || 'неизвестно'),
      fact('Java', java),
      fact('ОС', os),
      fact('Куча (максимум)', heap),
    ]);
    return [head, facts];
  }

  function sectionFindings(R) {
    const list = el('ul', { class: 'findings' });
    for (const f of computeFindings(R)) {
      list.appendChild(el('li', { class: 'finding sev-' + f.severity }, [
        el('span', { class: 'badge sev-' + f.severity, text: SEVERITY[f.severity].label }),
        el('p', { class: 'finding-title', text: f.title }),
        el('p', { class: 'finding-text', text: f.text }),
      ]));
    }
    return card('findings', 'Выводы', 'Автоматические подсказки по этому замеру. Это правила, а не приговор: сверяйтесь с графиками ниже.', [list]);
  }

  function sectionCores(R) {
    const C = R.system.logicalCores;
    const body = [];
    if (R.system.perCore && R.n) {
      body.push(heatmap(R), heatLegend());
      const avg = coreAverages(R);
      const bars = el('div', { class: 'core-bars' });
      avg.forEach((v, k) => {
        bars.appendChild(el('div', { class: 'core-bar' }, [
          el('span', { class: 'name', text: 'ядро ' + k }), meter(v, v >= 0.7), el('span', { class: 'val', text: fmtPct(v) }),
        ]));
      });
      body.push(el('h3', { text: 'Средняя загрузка каждого ядра' }), bars);
    } else {
      body.push(el('p', { class: 'notice', text: R.n ? 'Загрузка по отдельным ядрам недоступна: сервер собирает её только на Linux (/proc/stat).' : 'В отчёте нет замеров.' }));
    }

    body.push(el('h3', { text: 'Сервер и остальные процессы' }));
    body.push(el('p', { class: 'card-desc', text: 'Сколько ядер в каждую секунду занимал сервер Minecraft и сколько — всё остальное на машине (например, Discord-бот или база данных).' }));
    const proc = R.processCpu.map((v) => (v === null ? null : v * C));
    const other = R.systemCpu.map((v, i) => (v === null || R.processCpu[i] === null ? null : Math.max(0, v - R.processCpu[i]) * C));
    body.push(legend([
      { name: 'Сервер Minecraft', color: 'var(--s1)' },
      { name: 'Другие процессы', color: REST_COLOR },
      { name: 'Все ядра машины (' + C + ')', dashed: true },
    ]));
    body.push(tsChart(R, () => ({
      stacked: true,
      height: 200,
      series: [
        { name: 'Сервер Minecraft', values: proc, color: 'var(--s1)' },
        { name: 'Другие процессы', values: other, color: REST_COLOR },
      ],
      refs: [{ y: C, label: 'все ядра: ' + C }],
      fixedMax: C,
      yFmt: (v) => fmtNum(v, v % 1 ? 1 : 0),
      valFmt: fmtCores,
      ariaLabel: 'Загрузка машины: сервер и другие процессы',
    })));
    return card('cores', 'Ядра процессора', 'Каждая строка — логическое ядро, каждый столбец — секунда замера. Чем ярче клетка, тем сильнее загружено ядро (всеми процессами машины).', body);
  }

  function sectionGroups(R) {
    const C = R.system.logicalCores;
    const colored = R.groups.filter((g) => Object.prototype.hasOwnProperty.call(GROUP_COLORS, g.id) && g.series);
    const rest = R.groups.filter((g) => colored.indexOf(g) < 0 && g.series);
    // stack order: the fixed order of GROUP_COLORS, so a colour sits in the same place in every report
    const order = Object.keys(GROUP_COLORS);
    colored.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
    const series = colored.map((g) => ({ name: groupName(g.id), values: g.series, color: groupColor(g.id), id: g.id }));
    if (rest.length) {
      const vals = new Array(R.n).fill(0);
      for (const g of rest) for (let i = 0; i < R.n; i++) vals[i] += g.series[i] || 0;
      series.push({ name: 'Остальное', values: vals, color: REST_COLOR, id: '_rest' });
    }

    let fullScale = false;
    const seg = el('div', { class: 'seg', role: 'group', 'aria-label': 'Масштаб графика' });
    const bFit = el('button', { type: 'button', 'aria-pressed': 'true', text: 'По данным' });
    const bAll = el('button', { type: 'button', 'aria-pressed': 'false', text: 'Все ядра (' + C + ')' });
    seg.append(bFit, bAll);

    const chart = tsChart(R, () => ({
      stacked: true,
      height: 260,
      series: series,
      fixedMax: fullScale ? C : 0,
      refs: [
        { y: 1, label: '1 ядро', strong: false },
        { y: C, label: 'все ядра: ' + C, strong: true },
      ],
      yFmt: (v) => fmtNum(v, v % 1 ? 1 : 0),
      valFmt: fmtCores,
      ariaLabel: 'Загрузка процессора по группам потоков',
    }));
    const setScale = (all) => {
      fullScale = all;
      bFit.setAttribute('aria-pressed', String(!all));
      bAll.setAttribute('aria-pressed', String(all));
      chart._redraw();
    };
    bFit.addEventListener('click', () => setScale(false));
    bAll.addEventListener('click', () => setScale(true));

    const legendItems = series.map((s) => ({ name: s.id === '_rest' ? 'Остальное: ' + rest.map((g) => groupName(g.id)).join(', ') : s.name, color: s.color }));
    legendItems.push({ name: 'Пороги: 1 ядро и все ядра', dashed: true });

    const totalAvg = sum(R.groups.map((g) => g.avgCores));
    const t = table([
      { label: 'Группа', render: (g) => groupCell(g.id) },
      { label: 'Потоков', cls: 'num opt', render: (g) => fmtNum(g.threads) },
      { label: 'Среднее, ядер', cls: 'num', render: (g) => fmtNum(g.avgCores, 2) },
      { label: 'Пик, ядер', cls: 'num', render: (g) => fmtNum(g.peakCores, 2) },
      { label: '% машины', cls: 'num opt', render: (g) => fmtPct(g.avgCores / C, 1) },
      { label: '% сервера', cls: 'num opt', render: (g) => (totalAvg > 0 ? fmtPct(g.avgCores / totalAvg, 0) : '—') },
    ], R.groups);

    const head = el('div', { class: 'card-head' }, [
      el('div', null, [
        el('h2', { text: 'Кто ест процессор' }),
        el('p', { class: 'card-desc', text: 'Потоки сервера, сгруппированные по назначению. Высота — сколько ядер группа занимала в эту секунду. 1,0 = одно ядро полностью.' }),
      ]),
      seg,
    ]);
    const body = [head];
    if (!R.groups.length) body.push(el('p', { class: 'empty', text: 'В отчёте нет данных о группах потоков.' }));
    else {
      body.push(legend(legendItems), chart);
      if (!series.length) body.push(el('p', { class: 'chart-note', text: 'У групп нет поминутных рядов, показана только таблица.' }));
      body.push(el('h3', { text: 'Группы потоков' }), t.wrap);
    }
    return el('section', { class: 'card', id: 'groups' }, body);
  }

  function sectionMain(R) {
    const main = R.group('main');
    if (!main || !main.series) {
      return card('main', 'Главный поток', null, [el('p', { class: 'empty', text: 'В отчёте нет данных о главном потоке (Server thread).' })]);
    }
    const body = [
      legend([{ name: 'Главный поток', color: 'var(--s1)', line: true }, { name: 'Предел: 1 ядро', dashed: true, color: 'var(--ref-strong)' }]),
      tsChart(R, () => ({
        height: 200,
        series: [{ name: 'Главный поток', values: main.series, color: 'var(--s1)' }],
        refs: [{ y: 1, label: 'предел: 1 ядро', strong: true, include: true }],
        minMax: 1.1,
        yFmt: (v) => fmtNum(v, 2),
        valFmt: fmtCores,
        ariaLabel: 'Загрузка главного потока',
      })),
      el('p', { class: 'chart-note', text: 'Среднее ' + fmtCores(main.avgCores) + ', пик ' + fmtCores(main.peakCores) + '. Если линия прижата к пределу, главный поток не успевает и сервер начинает лагать.' }),
    ];
    return card('main', 'Главный поток', 'Все тики мира (мобы, редстоун, блоки, игроки) считаются в одном потоке «Server thread». Он не может занять больше одного ядра.', body);
  }

  function sectionTicks(R) {
    const tpsCard = card(null, 'TPS', 'Тиков в секунду, норма — 20.', [
      tsChart(R, () => ({
        height: 200,
        series: [{ name: 'TPS', values: R.tps, color: 'var(--s1)' }],
        fixedMax: 20,
        yFmt: (v) => fmtNum(v, 0),
        valFmt: (v) => fmtNum(v, 2),
        ariaLabel: 'TPS по времени',
      })),
    ]);
    const msptCard = card(null, 'MSPT', 'Длительность тика, мс. Больше 50 мс — сервер не успевает.', [
      legend([
        { name: 'Средний тик', color: 'var(--s1)', line: true },
        { name: 'Самый долгий тик', color: 'var(--s2)', line: true },
        { name: '50 мс', dashed: true, color: 'var(--ref-strong)' },
      ]),
      tsChart(R, () => ({
        height: 200,
        series: [
          { name: 'Средний тик', values: R.msptAvg, color: 'var(--s1)' },
          { name: 'Самый долгий тик', values: R.msptMax, color: 'var(--s2)' },
        ],
        refs: [{ y: 50, label: '50 мс', strong: true, include: true }],
        yFmt: (v) => fmtNum(v, 0),
        valFmt: fmtMs,
        ariaLabel: 'MSPT по времени',
      })),
    ]);

    const spikes = R.lagSpikes.slice(0, 20);
    const maxMs = spikes.length ? spikes[0].ms : 1;
    let spikesBody;
    if (!spikes.length) spikesBody = el('p', { class: 'empty', text: 'Тиков дольше 50 мс не было.' });
    else {
      spikesBody = table([
        { label: '№', cls: 'num', render: (s) => String(spikes.indexOf(s) + 1) },
        { label: 'Когда', render: (s) => timeLabel(R, s.t) },
        { label: 'Длительность', cls: 'num', render: (s) => fmtMs(s.ms) },
        { label: '', cls: 'meter-cell', render: (s) => meter(s.ms / maxMs, s.ms >= 100) },
        { label: 'GC в эту секунду', cls: 'num opt', render: (s) => (R.n ? fmtMs(R.gcTimeMs[sampleIndexAt(R, s.t)]) : '—') },
      ], spikes).wrap;
    }
    const total = R.lagSpikes.length;
    const spikesCard = card('spikes', 'Лаг-спайки', total
      ? 'Тики дольше 50 мс, самые долгие сверху' + (total > 20 ? ' (показаны 20 из ' + total + ')' : '') + '.'
      : null, [spikesBody]);
    return [el('div', { class: 'grid-2', id: 'ticks' }, [tpsCard, msptCard]), spikesCard];
  }

  function sectionMemory(R) {
    const heapMax = R.system.heapMaxBytes;
    const unit = Math.max(heapMax || 0, maxOf(R.heapUsed) || 0) >= 2 * 1024 ** 3 ? 1024 ** 3 : 1024 ** 2;
    const unitName = unit === 1024 ** 3 ? 'ГБ' : 'МБ';
    const heapCard = card(null, 'Память (куча)', 'Занятая куча после каждой секунды и максимум (-Xmx).', [
      legend([{ name: 'Занято', color: 'var(--s1)', line: true }, { name: 'Максимум', dashed: true }]),
      tsChart(R, () => ({
        height: 200,
        series: [{ name: 'Занято', values: R.heapUsed, color: 'var(--s1)' }],
        refs: heapMax ? [{ y: heapMax, label: 'максимум ' + fmtBytes(heapMax), include: true }] : [],
        yScale: unit,
        yFmt: (v) => fmtNum(v / unit, (v / unit) % 1 ? 1 : 0) + ' ' + unitName,
        tipRows: (i) => {
          const rows = [{ color: 'var(--s1)', line: true, name: 'Занято', value: fmtBytes(R.heapUsed[i]) }];
          if (heapMax && R.heapUsed[i] !== null) rows.push({ name: 'От максимума', value: fmtPct(R.heapUsed[i] / heapMax) });
          return rows;
        },
        ariaLabel: 'Занятая память по времени',
      })),
    ]);
    const gcTotal = sum(R.gcTimeMs);
    const gcCard = card(null, 'Сборка мусора', 'Время пауз сборщика мусора за каждую секунду, мс.', [
      tsChart(R, () => ({
        height: 200,
        series: [{ name: 'Время GC', values: R.gcTimeMs, color: 'var(--s4)', kind: 'bar' }],
        minMax: 10,
        yFmt: (v) => fmtNum(v, 0),
        tipRows: (i) => [
          { color: 'var(--s4)', name: 'Время GC', value: fmtMs(R.gcTimeMs[i]) },
          { name: 'Сборок', value: R.gcCount[i] === null ? '—' : fmtNum(R.gcCount[i]) },
        ],
        ariaLabel: 'Время сборки мусора по секундам',
      })),
      el('p', { class: 'chart-note', text: 'Всего ' + fmtMs(gcTotal) + ' за ' + fmtDuration(R.durationMs) + ' (' + fmtPct(R.durationMs > 0 ? gcTotal / R.durationMs : 0, 1) + ' времени).' }),
    ]);
    return el('div', { class: 'grid-2', id: 'memory' }, [heapCard, gcCard]);
  }

  function sectionThreads(R) {
    const LIMIT = 200;
    const input = el('input', { class: 'input', type: 'search', placeholder: 'Фильтр по имени или группе…', 'aria-label': 'Фильтр потоков' });
    const count = el('span', { class: 'count' });
    const columns = [
      { label: 'Поток', cls: 'name', render: (t) => [t.name, el('span', { class: 'td-sub', text: groupName(t.group) })] },
      { label: 'Группа', cls: 'opt', render: (t) => groupCell(t.group) },
      { label: 'Ядер', cls: 'num', render: (t) => fmtNum(t.avgCores, 3) },
      { label: 'CPU, мс', cls: 'num opt', render: (t) => fmtNum(t.cpuMs) },
      { label: 'По времени', render: (t) => (t.series ? sparkline(R, t.series, t.name) : el('span', { class: 'muted', text: '—' })) },
    ];
    const t = table(columns, []);
    const apply = () => {
      const q = input.value.trim().toLowerCase();
      const list = q ? R.threads.filter((th) => th.name.toLowerCase().includes(q) || th.group.toLowerCase().includes(q) || groupName(th.group).toLowerCase().includes(q)) : R.threads;
      fillBody(t.tbody, columns, list.slice(0, LIMIT));
      count.textContent = list.length > LIMIT
        ? 'показаны ' + LIMIT + ' из ' + list.length + ', уточните фильтр'
        : list.length + ' ' + plural(list.length, ['поток', 'потока', 'потоков']);
    };
    input.addEventListener('input', apply);
    apply();
    const src = R.system.perThreadSource === 'jmx'
      ? 'Источник: JMX — только Java-потоки, внутренние потоки JVM (GC, JIT) не видны.'
      : R.system.perThreadSource === 'proc' ? 'Источник: /proc — все потоки процесса, включая GC и JIT. Linux обрезает имена до 15 символов.' : '';
    const body = R.threads.length
      ? [el('div', { class: 'table-tools' }, [input, count]), t.wrap]
      : [el('p', { class: 'empty', text: 'В отчёте нет списка потоков.' })];
    return card('threads', 'Потоки', 'Все потоки за время замера, самые нагруженные сверху. График — для 24 самых занятых. ' + src, body);
  }

  function sectionPluginsWorlds(R) {
    const plugins = R.server.plugins.slice().sort((a, b) => a.name.localeCompare(b.name, 'ru'));
    const pCard = card('plugins', 'Плагины', plugins.length ? plugins.length + ' ' + plural(plugins.length, ['плагин', 'плагина', 'плагинов']) : null, [
      plugins.length ? table([
        { label: 'Плагин', cls: 'name', render: (p) => p.name },
        { label: 'Версия', render: (p) => p.version || '—' },
        { label: 'Статус', render: (p) => el('span', { class: p.enabled ? 'status-on' : 'status-off', text: p.enabled ? 'включён' : 'выключен' }) },
      ], plugins).wrap : el('p', { class: 'empty', text: 'Плагинов нет или список не передан.' }),
    ]);
    const worlds = R.server.worlds;
    const wCard = card('worlds', 'Миры', 'Состояние на конец замера.', [
      worlds.length ? table([
        { label: 'Мир', cls: 'name', render: (w) => w.name },
        { label: 'Тип', render: (w) => ENV_NAMES[w.environment] || w.environment || '—' },
        { label: 'Чанки', cls: 'num', render: (w) => fmtNum(w.loadedChunks) },
        { label: 'Сущности', cls: 'num', render: (w) => fmtNum(w.entities) },
        { label: 'Блок-сущности', cls: 'num', render: (w) => fmtNum(w.tileEntities) },
        { label: 'Игроки', cls: 'num', render: (w) => fmtNum(w.players) },
      ], worlds).wrap : el('p', { class: 'empty', text: 'Список миров не передан.' }),
    ]);
    const args = R.server.jvmArgs;
    const jvm = el('section', { class: 'card', id: 'jvm' }, [
      el('details', { class: 'jvm' }, [
        el('summary', { text: 'Аргументы JVM (' + args.length + ')' }),
        args.length ? el('ul', { class: 'jvm-list' }, args.map((a) => el('li', { text: a }))) : el('p', { class: 'empty', text: 'Аргументы не переданы.' }),
      ]),
    ]);
    return [wCard, pCard, jvm];
  }

  function toc() {
    const links = [
      ['findings', 'Выводы'], ['cores', 'Ядра'], ['groups', 'Кто ест процессор'], ['main', 'Главный поток'],
      ['ticks', 'TPS и MSPT'], ['spikes', 'Лаг-спайки'], ['memory', 'Память'], ['threads', 'Потоки'], ['worlds', 'Миры и плагины'],
    ];
    return el('nav', { class: 'toc', 'aria-label': 'Разделы отчёта' }, links.map((l) => {
      const a = el('a', { href: '#' + l[0], text: l[1] });
      a.addEventListener('click', (e) => {
        const target = document.getElementById(l[0]);
        if (target) { e.preventDefault(); target.scrollIntoView({ behavior: 'smooth', block: 'start' }); }
      });
      return a;
    }));
  }

  function reportSectionFailure(where, err) {
    if (window.console && console.warn) console.warn('amazake viewer: section failed', err);
    const note = el('div', { class: 'notice error' }, [
      el('p', { class: 'notice-title', text: 'Не удалось показать этот раздел' }),
      el('p', { text: 'Возможно, в отчёте не хватает данных. Остальные разделы показаны.' }),
    ]);
    if (where && where.replaceWith) where.replaceWith(note);
    return note;
  }

  function safeSection(fn, R) {
    try {
      const out = fn(R);
      return Array.isArray(out) ? out : [out];
    } catch (e) {
      return [reportSectionFailure(null, e)];
    }
  }

  // ---------------------------------------------------------------------
  // Views
  // ---------------------------------------------------------------------

  function resetView() {
    charts = [];
    hideTip();
    app.textContent = '';
    window.scrollTo(0, 0);
  }

  function setNav(rawText, fileName) {
    topnav.textContent = '';
    topnav.hidden = false;
    topnav.appendChild(el('a', { class: 'btn btn-small', href: '/', text: 'Другой отчёт' }));
    if (rawText) {
      const btn = el('button', { class: 'btn btn-small', type: 'button', text: 'Скачать JSON' });
      btn.addEventListener('click', () => {
        const url = URL.createObjectURL(new Blob([rawText], { type: 'application/json' }));
        const a = el('a', { href: url, download: fileName || 'amazake-profile.json' });
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      });
      topnav.appendChild(btn);
    }
  }

  function renderReport(raw, rawText, fileName) {
    resetView();
    if (!isObj(raw)) {
      showError('Это не отчёт профайлера', 'Файл не содержит JSON-объект отчёта Amazake.', true);
      return;
    }
    const looksLikeReport = raw.format === 'amazake-profile' || Array.isArray(raw.samples) || isObj(raw.server);
    if (!looksLikeReport) {
      showError('Это не отчёт профайлера', 'В JSON нет полей отчёта Amazake (format "amazake-profile"). Возможно, выбран не тот файл.', true);
      return;
    }
    setNav(rawText, fileName);
    if (raw.format !== 'amazake-profile') {
      app.appendChild(noticeEl('warn', 'Формат не распознан', 'Поле format не равно "amazake-profile". Показываем то, что удалось прочитать.'));
    } else if (raw.version !== 1) {
      app.appendChild(noticeEl('warn', 'Другая версия формата', 'Отчёт версии ' + str(raw.version, '?') + ', а просмотрщик знает версию 1. Часть данных может отображаться неверно.'));
    }

    const R = normalize(raw);
    document.title = (R.server.brand + ' ' + R.server.version).trim() + ' — Amazake Профайлер';

    const parts = [];
    parts.push(...safeSection(sectionHeader, R));
    parts.push(toc());
    parts.push(...safeSection(sectionFindings, R));
    parts.push(...safeSection(sectionCores, R));
    parts.push(...safeSection(sectionGroups, R));
    parts.push(...safeSection(sectionMain, R));
    parts.push(...safeSection(sectionTicks, R));
    parts.push(...safeSection(sectionMemory, R));
    parts.push(...safeSection(sectionThreads, R));
    parts.push(...safeSection(sectionPluginsWorlds, R));
    parts.push(el('p', { class: 'footer-note', text: 'Отчёт обработан в вашем браузере. Формат amazake-profile, версия ' + str(raw.version, '?') + '.' }));
    for (const p of parts) app.appendChild(p);
    drawAllCharts(true);
    if (location.hash && location.hash.length > 1) {
      const target = document.getElementById(location.hash.slice(1));
      if (target) target.scrollIntoView();
    }
  }

  function noticeEl(kind, title, text) {
    return el('div', { class: 'notice ' + kind, role: kind === 'error' ? 'alert' : null }, [
      el('p', { class: 'notice-title', text: title }),
      text ? el('p', { text: text }) : null,
    ]);
  }

  function showError(title, text, withBack) {
    resetView();
    topnav.hidden = true;
    app.appendChild(el('div', { class: 'start' }, [
      noticeEl('error', title, text),
      withBack ? el('a', { class: 'btn', href: '/', text: 'На главную' }) : null,
    ]));
  }

  function showLoading(text) {
    resetView();
    app.appendChild(el('p', { class: 'loading', text: text }));
  }

  function showStart(message) {
    resetView();
    topnav.hidden = true;
    topnav.textContent = '';
    document.title = 'Amazake Профайлер';

    const status = el('div', { 'aria-live': 'polite' });
    if (message) status.appendChild(noticeEl('error', message.title, message.text));

    const fileInput = el('input', { type: 'file', accept: '.json,.gz,application/json', hidden: true });
    const pick = el('button', { class: 'btn btn-primary', type: 'button', text: 'Выбрать файл' });
    const zone = el('div', { class: 'dropzone', tabindex: '0', role: 'button', 'aria-label': 'Открыть файл отчёта' }, [
      el('p', { class: 'dropzone-title', text: 'Перетащите сюда файл отчёта .json' }),
      el('p', { class: 'dropzone-hint', text: 'Файл не загружается на сервер: он открывается прямо в браузере.' }),
      pick,
      fileInput,
    ]);
    const openPicker = () => fileInput.click();
    pick.addEventListener('click', (e) => { e.stopPropagation(); openPicker(); });
    zone.addEventListener('click', openPicker);
    zone.addEventListener('keydown', (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openPicker(); } });
    fileInput.addEventListener('change', () => { if (fileInput.files && fileInput.files[0]) openFile(fileInput.files[0], status); });
    zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('drag'); });
    zone.addEventListener('dragleave', () => zone.classList.remove('drag'));
    zone.addEventListener('drop', (e) => {
      e.preventDefault();
      zone.classList.remove('drag');
      const f = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
      if (f) openFile(f, status);
    });

    const linkInput = el('input', { class: 'input', type: 'text', placeholder: 'Ссылка на отчёт (…/r/aB3xY9kQ2m) или его id', 'aria-label': 'Ссылка или id отчёта', autocomplete: 'off', spellcheck: 'false' });
    const go = el('button', { class: 'btn', type: 'submit', text: 'Открыть' });
    const form = el('form', { class: 'start-row' }, [linkInput, go]);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const id = parseReportRef(linkInput.value);
      status.textContent = '';
      if (!id) {
        status.appendChild(noticeEl('error', 'Не понимаю ссылку', 'Вставьте ссылку вида https://сайт/r/aB3xY9kQ2m или только id отчёта (латинские буквы и цифры).'));
        return;
      }
      location.assign('/r/' + id);
    });

    const sample = el('button', { class: 'btn', type: 'button', text: 'Открыть пример' });
    sample.addEventListener('click', () => {
      history.pushState(null, '', '/?sample');
      loadSample();
    });

    app.appendChild(el('div', { class: 'start' }, [
      el('h1', { text: 'Отчёты профайлера Amazake' }),
      el('p', { class: 'lead', text: 'Здесь видно, чем занят процессор сервера: главный поток, чанки, сеть, сборщик мусора, плагины — и что с этим делать.' }),
      status,
      zone,
      form,
      el('div', { class: 'start-sep', text: 'или' }),
      el('div', { class: 'start-sample' }, sample),
      el('p', { class: 'start-help' }, [
        'Отчёт создаётся на сервере командой ', el('code', { text: '/amazake profile' }),
        '. Если загрузка на сайт включена, сервер пришлёт ссылку; иначе файл отчёта лежит в папке сервера — его можно открыть здесь.',
      ]),
    ]));
  }

  function parseReportRef(text) {
    const s = String(text || '').trim();
    if (!s) return null;
    const m = /\/r\/([A-Za-z0-9]+)(?:[/?#].*)?$/.exec(s);
    if (m && ID_RE.test(m[1])) return m[1];
    if (ID_RE.test(s)) return s;
    return null;
  }

  function parseJsonText(text) {
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    return JSON.parse(text);
  }

  async function readFileText(file) {
    const buf = await file.arrayBuffer();
    const bytes = new Uint8Array(buf);
    if (bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b) {
      if (typeof DecompressionStream !== 'function') throw new Error('gzip');
      const stream = new Blob([buf]).stream().pipeThrough(new DecompressionStream('gzip'));
      return await new Response(stream).text();
    }
    return new TextDecoder('utf-8').decode(bytes);
  }

  async function openFile(file, status) {
    status.textContent = '';
    if (file.size > MAX_FILE_BYTES) {
      status.appendChild(noticeEl('error', 'Файл слишком большой', 'Отчёт не может весить больше 64 МБ. Похоже, это не отчёт профайлера.'));
      return;
    }
    let text;
    try {
      text = await readFileText(file);
    } catch (e) {
      status.appendChild(noticeEl('error', 'Не удалось прочитать файл', e && e.message === 'gzip'
        ? 'Файл сжат gzip, а этот браузер не умеет его распаковывать. Распакуйте файл и откройте .json.'
        : 'Браузер не смог прочитать файл.'));
      return;
    }
    let raw;
    try {
      raw = parseJsonText(text);
    } catch (e) {
      status.appendChild(noticeEl('error', 'Файл не является JSON', 'Откройте файл отчёта, который создала команда /amazake profile (обычно с расширением .json).'));
      return;
    }
    history.pushState(null, '', '/#file');
    renderReport(raw, text, file.name);
  }

  async function fetchReport(url, notFoundText) {
    let res;
    try {
      res = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store' });
    } catch (e) {
      return { error: ['Нет связи с сайтом', 'Не удалось загрузить отчёт. Проверьте подключение к интернету и обновите страницу.'] };
    }
    if (!res.ok) {
      try { await res.text(); } catch (e) { /* only drains the error body */ }
    }
    if (res.status === 404) return { error: ['Отчёт не найден', notFoundText] };
    if (!res.ok) return { error: ['Сайт ответил ошибкой ' + res.status, 'Попробуйте обновить страницу чуть позже.'] };
    let text;
    try {
      text = await res.text();
    } catch (e) {
      return { error: ['Загрузка прервалась', 'Соединение оборвалось во время загрузки отчёта. Обновите страницу.'] };
    }
    try {
      return { raw: parseJsonText(text), text: text };
    } catch (e) {
      return { error: ['Отчёт повреждён', 'Сайт вернул данные, которые не являются JSON.'] };
    }
  }

  async function loadRemote(id) {
    showLoading('Загружаем отчёт…');
    const r = await fetchReport('/api/reports/' + encodeURIComponent(id),
      'Отчёта с id «' + id + '» нет: ссылка неверная или срок хранения отчёта истёк. Снимите новый профиль командой /amazake profile.');
    if (r.error) { showError(r.error[0], r.error[1], true); return; }
    renderReport(r.raw, r.text, 'amazake-' + id + '.json');
  }

  async function loadSample() {
    showLoading('Загружаем пример…');
    const r = await fetchReport('/sample-report.json', 'Файл примера не найден на сайте.');
    if (r.error) { showError(r.error[0], r.error[1], true); return; }
    renderReport(r.raw, r.text, 'amazake-sample.json');
  }

  function route() {
    const path = location.pathname;
    const m = /^\/r\/([^/]*)\/?$/.exec(path);
    if (m) {
      let id = '';
      try { id = decodeURIComponent(m[1]); } catch (e) { id = ''; }
      if (!ID_RE.test(id)) {
        showError('Неверная ссылка', 'Id отчёта состоит только из латинских букв и цифр. Проверьте ссылку.', true);
        return;
      }
      loadRemote(id);
      return;
    }
    if (location.search === '?sample') { loadSample(); return; }
    showStart();
  }

  window.addEventListener('popstate', route);
  route();
})();
