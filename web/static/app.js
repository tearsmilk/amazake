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
  const MAX_TIME_MS = 1e9;        // ~11.5 days: anything later in a report is treated as garbage
  const MAX_INTERVAL_MS = 3600000; // sample interval above one hour is not plausible
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

  // Core layout roles (system.coreRoles): the colour of the thread group each role runs.
  const CORE_ROLES = {
    tick: { short: 'тик', name: 'Тик (главный поток)', color: 'var(--s1)', groups: ['main'] },
    chunks: { short: 'чанки', name: 'Чанки', color: 'var(--s2)', groups: ['chunk-workers', 'chunk-io'] },
    network: { short: 'сеть', name: 'Сеть (Netty)', color: 'var(--s3)', groups: ['netty'] },
    gc: { short: 'GC', name: 'GC и JIT', color: 'var(--s4)', groups: ['gc', 'jit'] },
    background: { short: 'фон', name: 'Фоновые потоки', color: 'var(--s5)', groups: null }, // every other group
    // Kept empty for the main thread to move to (amazake.yml cpu.reserve); no thread group runs there.
    reserve: { short: 'резерв', name: 'Резерв (пустое ядро для переезда тика)', color: 'var(--s6)', groups: [] },
    shared: { short: 'общее', name: 'Без роли', color: REST_COLOR, groups: [] },
  };
  const ROLE_ORDER = ['tick', 'chunks', 'network', 'gc', 'background', 'reserve', 'shared'];
  /** Most migrations[] entries the viewer reads. */
  const MAX_MIGRATIONS = 200;

  /** The roles of a coreRoles entry: "chunks+gc" -> ['chunks', 'gc']; [] for "shared". */
  function roleParts(v) {
    if (typeof v !== 'string' || v === 'shared') return [];
    return v.split('+').filter((p) => p !== 'shared' && Object.prototype.hasOwnProperty.call(CORE_ROLES, p));
  }

  /** Canonical coreRoles entry: known roles in ROLE_ORDER joined with "+", or "shared". */
  function normRole(v) {
    const parts = roleParts(v);
    const out = ROLE_ORDER.filter((id) => parts.indexOf(id) >= 0);
    return out.length ? out.join('+') : 'shared';
  }

  /** Label and colour of a coreRoles entry; a core with several roles gets «чанки+GC» and its first role's colour. */
  function roleInfo(v) {
    if (Object.prototype.hasOwnProperty.call(CORE_ROLES, v)) return CORE_ROLES[v];
    const parts = roleParts(v);
    if (!parts.length) return CORE_ROLES.shared;
    return {
      short: parts.map((p) => CORE_ROLES[p].short).join('+'),
      name: parts.map((p) => CORE_ROLES[p].name).join(' + ') + ' (общее ядро)',
      color: CORE_ROLES[parts[0]].color,
      groups: null,
    };
  }

  const ENV_NAMES = { NORMAL: 'Обычный', NETHER: 'Незер', THE_END: 'Энд', CUSTOM: 'Свой' };

  const SEVERITY = {
    critical: { label: 'Критично', rank: 0 },
    serious: { label: 'Серьёзно', rank: 1 },
    warning: { label: 'Внимание', rank: 2 },
    info: { label: 'Заметка', rank: 3 },
    good: { label: 'Норма', rank: 4 },
  };

  const app = document.getElementById('app');
  const metaBar = document.getElementById('meta');
  const actionsBar = document.getElementById('actions');
  const tabsBar = document.getElementById('tabs');
  const toast = document.getElementById('toast');
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

  let gradSeq = 0;

  /** Vertical fade for area fills; colour can be a CSS var, so it goes through style. */
  function gradientFill(root, color, top) {
    let defs = root.querySelector('defs');
    if (!defs) { defs = svg('defs'); root.insertBefore(defs, root.firstChild); }
    const id = 'amz-grad-' + (++gradSeq);
    const g = svg('linearGradient', { id: id, x1: 0, x2: 0, y1: 0, y2: 1 });
    const s0 = svg('stop', { offset: '0%' });
    s0.style.stopColor = color; s0.style.stopOpacity = String(top);
    const s1 = svg('stop', { offset: '100%' });
    s1.style.stopColor = color; s1.style.stopOpacity = '0';
    g.append(s0, s1);
    defs.appendChild(g);
    return 'url(#' + id + ')';
  }

  function isObj(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); }
  function num(v, d) { return typeof v === 'number' && isFinite(v) ? v : (d === undefined ? 0 : d); }
  function numOrNull(v) { return typeof v === 'number' && isFinite(v) ? v : null; }
  // Report strings are cut to a sane length: a multi-megabyte name without spaces
  // would make the browser's line breaking stall the tab for minutes.
  const STR_MAX = 512;
  const ARG_MAX = 4096;
  function cut(s, max) { return s.length > max ? s.slice(0, max - 1) + '…' : s; }
  function str(v, d, max) {
    if (typeof v === 'string') return cut(v, max || STR_MAX);
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
    // Absurd magnitudes (only in broken reports) would print hundreds of digits.
    if (Math.abs(x) >= 1e15) return x.toExponential(2).replace('.', ',');
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
    // Times are only trusted inside a plausible range: absurd values (1e12, 1e25)
    // would make the time axes useless and can stall tick generation.
    let interval = num(raw.intervalMs, 1000);
    if (!(interval > 0) || interval > MAX_INTERVAL_MS) interval = 1000;
    const rawDuration = num(raw.durationMs, -1);
    const durationOk = rawDuration >= 0 && rawDuration <= MAX_TIME_MS;
    const tLimit = Math.min(MAX_TIME_MS, 4 * Math.max(n * interval, durationOk ? rawDuration : 0) + 10 * interval);

    const t = [];
    for (let i = 0; i < n; i++) {
      const prev = i > 0 ? t[i - 1] : 0;
      let v = num(samples[i].t, NaN);
      if (!(v >= 0 && v <= tLimit)) v = i > 0 ? prev + interval : interval;
      if (i > 0 && v <= prev) v = prev + interval; // keep time strictly increasing
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

    const durationMs = durationOk && (rawDuration > 0 || !n) ? rawDuration : (n ? t[n - 1] : 0);
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
      .map((s) => ({ t: clamp(num(s.t, 0), 0, MAX_TIME_MS), ms: Math.max(0, num(s.ms, 0)) }))
      .sort((a, b) => b.ms - a.ms);

    // migrations[] (optional): moves of the main thread to a reserve core. Bad entries are dropped.
    const coreIdOk = (v) => typeof v === 'number' && Number.isInteger(v) && v >= 0 && v < 65536;
    const migrations = arr(raw.migrations).filter(isObj)
      .filter((m) => coreIdOk(m.from) && coreIdOk(m.to) && m.from !== m.to && num(m.t, -1) >= 0 && num(m.t, -1) <= MAX_TIME_MS)
      .slice(0, MAX_MIGRATIONS)
      .map((m) => {
        const f = numOrNull(m.foreignLoad);
        return { t: num(m.t, 0), from: m.from, to: m.to, foreignLoad: f === null ? null : clamp(f, 0, 1) };
      })
      .sort((a, b) => a.t - b.t);

    let start = null;
    if (typeof raw.createdAt === 'string') {
      const d = new Date(raw.createdAt);
      if (!isNaN(d.getTime())) start = d;
    }

    const perCore = system.perCore === true && coresFromSamples > 0;
    // One entry per reported core: a role, several joined with "+" ("chunks+gc"), or "shared";
    // unknown names are dropped, a list without any role is ignored.
    let coreRoles = null;
    if (perCore && Array.isArray(system.coreRoles) && system.coreRoles.length) {
      coreRoles = [];
      for (let k = 0; k < coresFromSamples; k++) coreRoles.push(normRole(system.coreRoles[k]));
      if (!coreRoles.some((r) => r !== 'shared')) coreRoles = null;
    }
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
        jvmArgs: arr(server.jvmArgs).map((a) => str(a, '', ARG_MAX)).filter((a) => a !== ''),
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
        coreRoles: coreRoles,
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
      migrations: migrations,
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
    if (!isFinite(t0) || !isFinite(t1) || !isFinite(span)) return [];
    const steps = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200].map((s) => s * 1000);
    let step = 0;
    for (const s of steps) if (span / s <= maxTicks) { step = s; break; }
    // Longer than the table covers: grow the step with the span (whole hours).
    if (!step) step = Math.max(7200000, Math.ceil(niceStep(span / maxTicks) / 3600000) * 3600000);
    const out = [];
    const first = Math.ceil(t0 / step) * step;
    // Index-based loop: never stalls even if v + step === v, and is hard-capped.
    for (let k = 0; k <= maxTicks * 2; k++) {
      const v = first + k * step;
      if (v > t1 + 1e-6) break;
      out.push(v);
    }
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
    let yMin = 0;
    let ticks;
    if (o.yMin > 0 && o.yMin < yMax) {
      // Axis that does not start at zero (TPS): ticks from a rounded floor up to yMax.
      const step = niceStep((yMax - o.yMin) / Math.max(2, Math.floor(H / 48)));
      yMin = Math.floor(o.yMin / step + 1e-9) * step;
      ticks = [];
      for (let k = 0; k < 50; k++) {
        const v = Math.round((yMin + k * step) / step) * step;
        if (v > yMax + 1e-9) break;
        ticks.push(v);
      }
    } else {
      ticks = niceTicks(yMax / scale, Math.max(2, Math.floor(H / 48))).map((v) => v * scale);
      if (o.fixedMax) ticks = ticks.filter((v) => v <= o.fixedMax + 1e-9);
      else yMax = ticks[ticks.length - 1];
    }
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
    const Y = (v) => mt + ph - clamp((v - yMin) / (yMax - yMin), 0, 1.02) * ph;

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
        if (d && s.area && n > 1 && s.values.every((v) => v !== null)) {
          const a = svg('path', { d: d + 'L' + X(n - 1).toFixed(1) + ',' + (mt + ph) + 'L' + X(0).toFixed(1) + ',' + (mt + ph) + 'Z', class: 'area-fill' });
          a.style.fill = gradientFill(root, s.color, 0.32);
          marks.insertBefore(a, marks.firstChild);
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
      if (!(r.y > 0) || r.y > yMax || r.y < yMin) continue;
      const y = Y(r.y);
      const line = svg('line', { x1: ml, x2: ml + pw, y1: y, y2: y, 'stroke-dasharray': '6 4', 'stroke-width': r.strong ? 1.5 : 1 });
      line.style.stroke = r.strong ? 'var(--ref-strong)' : 'var(--ref)';
      root.appendChild(line);
      if (r.label) {
        // Label on a surface-coloured pill so it stays legible over filled areas.
        const tw = r.label.length * 6.2 + 12;
        const below = y - 18 < mt;
        const by = below ? y + 3 : y - 18;
        root.appendChild(svg('rect', { x: ml + pw - tw, y: by, width: tw, height: 15, rx: 4, class: 'ref-bg' }));
        root.appendChild(svg('text', { x: ml + pw - 6, y: by + 11, 'text-anchor': 'end', class: 'ref-label', text: r.label }));
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
      const roles = R.system.coreRoles;
      const cellH = clamp(Math.floor(288 / rows), 3, 14);
      // With a core layout every row gets a role chip (and a colour strip, also on rows without a label).
      const compact = W < 560;
      const numW = compact ? 22 : 52;
      const chipW = roles && roles.some((v) => v.indexOf('+') >= 0) ? 66 : roles && roles.indexOf('reserve') >= 0 ? 46 : 40;
      const ml = roles ? numW + chipW + 18 : 58, mr = 8, mt = 4, mb = 26;
      const pw = Math.max(40, W - ml - mr);
      const H = mt + rows * cellH + mb;
      // Long reports have far more samples than pixels: bin samples into at most
      // ~pw/2 columns (max load per core in the bin) and draw one <path> per colour
      // class, merging horizontal runs, so the DOM stays small (≤ 11 paths).
      const cols = Math.max(1, Math.min(n, Math.floor(pw / 2)));
      const binStart = (c) => Math.floor(c * n / cols);
      const cw = pw / cols;
      const cellAt = (c, r) => {
        let v = null;
        for (let i = binStart(c), e = binStart(c + 1); i < e; i++) {
          const s = R.cores[i];
          const x = s && r < s.length ? s[r] : null;
          if (x !== null && (v === null || x > v)) v = x;
        }
        return v;
      };
      const root = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, width: W, height: H, role: 'img', 'aria-label': 'Загрузка ядер по времени' });
      const g = svg('g', { 'shape-rendering': 'crispEdges' });
      root.appendChild(g);
      const paths = new Array(11).fill('');
      const hCell = Math.max(1, cellH - (cellH > 5 ? 1 : 0));
      for (let r = 0; r < rows; r++) {
        const y = mt + r * cellH;
        let runCls = -1, runStart = 0;
        for (let c = 0; c <= cols; c++) {
          let k = -1;
          if (c < cols) {
            const v = cellAt(c, r);
            k = v === null ? 10 : Math.min(9, Math.floor(v * 10));
          }
          if (k !== runCls) {
            if (runCls >= 0) {
              const x0 = ml + runStart * cw;
              const w = (c - runStart) * cw + (c === cols ? 0 : 0.6);
              paths[runCls] += 'M' + x0.toFixed(2) + ' ' + y + 'h' + w.toFixed(2) + 'v' + hCell + 'h' + (-w).toFixed(2) + 'z';
            }
            runCls = k; runStart = c;
          }
        }
      }
      for (let k = 0; k < 11; k++) {
        if (paths[k]) g.appendChild(svg('path', { d: paths[k], class: k === 10 ? 'hm0' : 'hm' + k }));
      }
      const every = Math.max(1, Math.ceil(13 / cellH));
      if (roles) {
        const strips = {};
        for (let r = 0; r < rows; r++) {
          const role = roles[r] || 'shared';
          strips[role] = (strips[role] || '') + 'M' + (ml - 6) + ' ' + (mt + r * cellH) + 'h3v' + hCell + 'h-3z';
        }
        for (const role of Object.keys(strips)) {
          const p = svg('path', { d: strips[role], class: 'role-strip' });
          p.style.fill = roleInfo(role).color;
          root.appendChild(p);
        }
      }
      for (let r = 0; r < rows; r += every) {
        const cy = mt + r * cellH + cellH / 2;
        if (!roles) {
          root.appendChild(svg('text', { x: ml - 8, y: cy + 4, 'text-anchor': 'end', text: 'ядро ' + r }));
          continue;
        }
        const role = roles[r] || 'shared';
        root.appendChild(svg('text', { x: numW, y: cy + 4, 'text-anchor': 'end', text: compact ? String(r) : 'ядро ' + r }));
        const chipH = Math.min(13, Math.max(9, cellH * every - 2));
        const bg = svg('rect', { x: numW + 6, y: cy - chipH / 2, width: chipW, height: chipH, rx: chipH / 2, class: 'role-chip-bg' + (role === 'shared' ? ' shared' : '') });
        bg.style.fill = roleInfo(role).color;
        root.appendChild(bg);
        root.appendChild(svg('text', { x: numW + 6 + chipW / 2, y: cy + 3.5, 'text-anchor': 'middle', class: 'role-chip-text', text: roleInfo(role).short }));
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
        const col = clamp(Math.floor((pt.x - ml) / cw), 0, cols - 1);
        const r = clamp(Math.floor((pt.y - mt) / cellH), 0, rows - 1);
        outline.setAttribute('x', ml + col * cw); outline.setAttribute('y', mt + r * cellH);
        outline.setAttribute('width', Math.max(2, cw)); outline.setAttribute('height', cellH);
        outline.setAttribute('visibility', 'visible');
        const i0 = binStart(col), i1 = Math.max(i0, binStart(col + 1) - 1);
        const v = cellAt(col, r);
        let busy = null;
        for (let i = i0; i <= i1; i++) {
          const c = R.cores[i];
          if (c) { const b = c.reduce((a, x) => a + x, 0); if (busy === null || b > busy) busy = b; }
        }
        const binned = i1 > i0;
        const when = binned ? timeLabel(R, R.t[i0]) + ' — ' + fmtClock(R.t[i1]) : timeLabel(R, R.t[i0]);
        showTip(evt, 'Ядро ' + r + (roles ? ' (' + roleInfo(roles[r] || 'shared').short + ')' : '') + ' · ' + when, [
          { name: binned ? 'Загрузка ядра (максимум)' : 'Загрузка ядра', value: v === null ? 'нет данных' : fmtPct(v) },
          { name: binned ? 'Все ядра (максимум)' : 'Все ядра в этот момент', value: busy === null ? '—' : fmtCores(busy) },
        ]);
      };
      hit.addEventListener('pointermove', onMove);
      hit.addEventListener('pointerdown', onMove);
      hit.addEventListener('pointerleave', () => { outline.setAttribute('visibility', 'hidden'); hideTip(); });
      // Moves of the main thread: a dashed line at the time, a hollow dot on the old core, a filled one on the new.
      for (const m of R.migrations) {
        if (!(d1 > d0) || m.t < d0 || m.t > d1 + R.interval) continue;
        const x = ml + clamp((m.t - d0) / (d1 - d0), 0, 1) * pw;
        const mg = svg('g', { class: 'mig' });
        mg.appendChild(svg('line', { x1: x, x2: x, y1: mt, y2: mt + rows * cellH, class: 'mig-line' }));
        const dotR = clamp(cellH / 2, 2.5, 5);
        if (m.from < rows) mg.appendChild(svg('circle', { cx: x, cy: mt + m.from * cellH + cellH / 2, r: dotR, class: 'mig-from' }));
        if (m.to < rows) mg.appendChild(svg('circle', { cx: x, cy: mt + m.to * cellH + cellH / 2, r: dotR, class: 'mig-to' }));
        const mhit = svg('rect', { x: x - 6, y: mt, width: 12, height: rows * cellH, class: 'mig-hit' });
        const onMig = (evt) => {
          outline.setAttribute('visibility', 'hidden');
          showTip(evt, 'Переезд главного потока · ' + timeLabel(R, m.t), migrationRows(m));
        };
        mhit.addEventListener('pointermove', onMig);
        mhit.addEventListener('pointerdown', onMig);
        mhit.addEventListener('pointerleave', hideTip);
        mg.appendChild(mhit);
        root.appendChild(mg);
      }
      box.appendChild(root);
    });
  }

  /** Tooltip rows of one migrations[] entry. */
  function migrationRows(m) {
    return [
      { name: 'Откуда', value: 'ядро ' + m.from + ' (стало резервом)' },
      { name: 'Куда', value: 'ядро ' + m.to },
      { name: 'Чужая нагрузка на старом ядре', value: m.foreignLoad === null ? '—' : fmtPct(m.foreignLoad) },
    ];
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

  function sparkline(R, values, name, color) {
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
      const a = svg('path', { d: d + 'L' + X(n - 1).toFixed(1) + ',' + (H - 1) + 'L' + X(0).toFixed(1) + ',' + (H - 1) + 'Z', class: 'spark-area' });
      if (color) a.style.fill = color;
      root.appendChild(a);
    }
    if (d) {
      const l = svg('path', { d: d, class: 'spark-line' });
      if (color) l.style.stroke = color;
      root.appendChild(l);
    }
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
    // A column may have a short header for phones (c.short), shown below 480 px instead of c.label.
    const th = (c) => (c.short
      ? el('th', { class: c.cls || null, scope: 'col', title: c.label }, [el('span', { class: 'th-full', text: c.label }), el('span', { class: 'th-short', text: c.short })])
      : el('th', { class: c.cls || null, scope: 'col', text: c.label }));
    const thead = el('thead', null, el('tr', null, columns.map(th)));
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
            (R.system.coreRoles && roleParts(R.system.coreRoles[topCore.i]).indexOf('tick') >= 0
              ? 'Это ядро тика из раскладки ядер: на нём работает главный поток, так и задумано. '
              : 'Обычно так выглядит главный поток сервера: он однопоточный и грузит одно ядро, пока остальные свободны. ') +
            'Если при этом есть лаги, сервер упирается в производительность одного ядра; помогает процессор с более высокой частотой, а не больше ядер.');
        }
      }
      layoutFindings(R, add);
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
  // Core layout (system.coreRoles)
  // ---------------------------------------------------------------------

  /** "0–3, 8, 10–11" for a sorted list of core indexes. */
  function fmtCoreList(idx) {
    const parts = [];
    for (let i = 0; i < idx.length;) {
      let j = i;
      while (j + 1 < idx.length && idx[j + 1] === idx[j] + 1) j++;
      parts.push(j > i ? idx[i] + '–' + idx[j] : String(idx[i]));
      i = j + 1;
    }
    return parts.join(', ');
  }

  function coresLabel(idx) {
    return (idx.length === 1 ? 'ядро ' : 'ядра ') + fmtCoreList(idx);
  }

  /** Small coloured role label («тик», «чанки», ...). */
  function roleChip(role) {
    const info = roleInfo(role);
    const c = el('span', { class: 'role-chip' + (role === 'shared' ? ' shared' : ''), title: info.name, text: info.short });
    c.style.setProperty('--role', info.color);
    return c;
  }

  /** Whether thread group {@code group} runs on the cores of {@code role}. */
  function roleHasGroup(role, group) {
    const gs = CORE_ROLES[role].groups;
    if (gs) return gs.indexOf(group) >= 0;
    for (const id of ROLE_ORDER) {
      const other = CORE_ROLES[id].groups;
      if (other && other.indexOf(group) >= 0) return false;
    }
    return true;
  }

  /**
   * Per role present in the layout: its cores, their mean load (whole machine, 0..1 per core),
   * the cores they were busy in total, and how many cores the role's own thread groups used.
   */
  function layoutStats(R) {
    const roles = R.system.coreRoles;
    if (!roles) return null;
    const avg = coreAverages(R);
    const out = [];
    for (const id of ROLE_ORDER) {
      const cores = [];
      // A core with several roles ("chunks+gc") counts for each of them.
      for (let k = 0; k < roles.length; k++) {
        if (id === 'shared' ? roles[k] === 'shared' : roleParts(roles[k]).indexOf(id) >= 0) cores.push(k);
      }
      if (!cores.length) continue;
      const loads = cores.map((k) => avg[k] || 0);
      const busy = sum(loads);
      let threadCores = null;
      if (id !== 'shared' && id !== 'reserve') {
        threadCores = 0;
        for (const g of R.groups) if (roleHasGroup(id, g.id)) threadCores += g.avgCores;
      }
      out.push({ id: id, cores: cores, avgLoad: busy / cores.length, busyCores: busy, peakLoad: maxOf(loads) || 0, threadCores: threadCores });
    }
    return out;
  }

  function layoutFindings(R, add) {
    const stats = layoutStats(R);
    if (!stats) return;
    const by = {};
    for (const r of stats) by[r.id] = r;
    const tick = by.tick, chunks = by.chunks;
    const main = R.group('main');
    const tickName = tick && tick.cores.length > 1 ? 'Ядра тика' : 'Ядро тика';
    if (tick && tick.avgLoad >= 0.9 && chunks && chunks.avgLoad < 0.3) {
      add('warning', 'Раскладка ядер: ' + tickName.toLowerCase() + ' загружено на ' + fmtPct(tick.avgLoad) + ', а ядра чанков почти простаивают (' + fmtPct(chunks.avgLoad) + ')',
        'Главный поток один, и раскладка не может отдать ему соседние ядра: свободные ядра чанков тикам не помогут. ' +
        'Ускоряет тики только уменьшение работы внутри тика (simulation-distance, лимиты мобов и сущностей, фермы, синхронные задачи плагинов) или процессор с более высокой частотой. ' +
        'Если чанки и дальше простаивают, часть их ядер (' + fmtCoreList(chunks.cores) + ') можно отдать фоновым задачам: cpu.layout.chunks и cpu.layout.background в amazake.yml, затем /amazake reload.');
    }
    if (R.migrations.length) {
      const moves = R.migrations.length;
      const last = R.migrations[moves - 1];
      add('info', 'Главный поток переезжал на резервное ядро: ' + moves + ' ' + plural(moves, ['раз', 'раза', 'раз']),
        'Ядро тика было занято чужой работой (другие процессы машины, прерывания, steal у VPS), и сервер перенёс главный поток на пустое резервное ядро (amazake.yml, cpu.reserve). ' +
        'Последний переезд: ядро ' + last.from + ' → ' + last.to + ' в ' + timeLabel(R, last.t) + (last.foreignLoad === null ? '' : ', чужая нагрузка ' + fmtPct(last.foreignLoad)) + '. ' +
        'Отметки переездов — на тепловой карте во вкладке «Ядра». Роли ядер в отчёте — на конец замера, поэтому средние по ролям смешивают время до и после переезда. ' +
        'Переезд убирает помеху, но не делает тяжёлый тик легче; если переезды частые, найдите, что ещё работает на машине (top, htop).');
    }
    if (tick && main && !R.migrations.length) {
      const extra = tick.busyCores - main.avgCores;
      if (extra >= 0.2) {
        add(extra >= 0.4 ? 'warning' : 'info', 'На ядре тика работает не только главный поток: ещё ' + fmtCores(extra),
          tickName + ' (' + fmtCoreList(tick.cores) + ') отдано главному потоку и загружено в среднем на ' + fmtCores(tick.busyCores) + ', а сам главный поток занимал ' + fmtCores(main.avgCores) + '. ' +
          'Остальное — чужая нагрузка: раскладка закрепляет только потоки сервера, а другие процессы машины (Discord-бот, база данных, бэкап) система может запускать на этом же ядре и отнимать время у тиков. ' +
          'Проверьте /amazake cores на сервере и top или htop на машине (столбец P — ядро процесса). Посторонний процесс можно перенести на ядра фона командой taskset -a -p -c <ядра> <pid>.');
      }
    }
    for (const id of ['chunks', 'network', 'gc', 'background']) {
      const r = by[id];
      if (!r || r.avgLoad < 0.85) continue;
      add(r.avgLoad >= 0.95 ? 'serious' : 'warning',
        'Ядрам роли «' + CORE_ROLES[id].short + '» тесно: загружены в среднем на ' + fmtPct(r.avgLoad),
        'Под роль «' + CORE_ROLES[id].name + '» отдано ' + r.cores.length + ' ' + coresWord(r.cores.length) + ' (' + fmtCoreList(r.cores) + '), её потоки заняли ' + fmtCores(r.threadCores) + '. ' +
        'Пока эти ядра заняты, потоки роли ждут очереди, даже если другие ядра свободны: раскладка не пускает их туда. ' +
        'Дайте роли больше ядер в amazake.yml (cpu.layout.' + id + ') и выполните /amazake reload, или верните там "auto".');
    }
  }

  // ---------------------------------------------------------------------
  // Icons (stroke icons on a 24×24 grid, built with createElementNS)
  // ---------------------------------------------------------------------

  const ICONS = {
    link: ['M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.5 1.5', 'M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.5-1.5'],
    download: ['M12 3v12', 'M7 10l5 5 5-5', 'M4 20h16'],
    check: ['M5 12.5l4.5 4.5L19 7.5'],
    good: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M8 12.5l2.7 2.7L16.5 9.5'],
    warning: ['M10.3 3.9L2.4 17.6A2 2 0 0 0 4.1 20.6h15.8a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z', 'M12 9v4.5', 'M12 17.2v.1'],
    serious: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7.5v5.5', 'M12 16.4v.1'],
    critical: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M9 9l6 6', 'M15 9l-6 6'],
    info: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 11v5.5', 'M12 7.6v.1'],
    chevron: ['M9 6l6 6-6 6'],
    search: ['M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z', 'M20 20l-4-4'],
    upload: ['M12 16V4', 'M7 9l5-5 5 5', 'M4 20h16'],
    file: ['M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z', 'M14 3v5h5'],
    arrow: ['M5 12h14', 'M13 6l6 6-6 6'],
    back: ['M19 12H5', 'M11 6l-6 6 6 6'],
    clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z', 'M12 7v5l3 2'],
    users: ['M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M2 21v-1a6 6 0 0 1 6-6h2a6 6 0 0 1 6 6v1', 'M16 3.1a4 4 0 0 1 0 7.8', 'M22 21v-1a6 6 0 0 0-4-5.6'],
    calendar: ['M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z', 'M3 10h18', 'M8 3v4', 'M16 3v4'],
    server: ['M4 4h16v6H4z', 'M4 14h16v6H4z', 'M8 7h.01', 'M8 17h.01'],
    sun: ['M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z', 'M12 2v2', 'M12 20v2', 'M4.9 4.9l1.4 1.4', 'M17.7 17.7l1.4 1.4', 'M2 12h2', 'M20 12h2', 'M4.9 19.1l1.4-1.4', 'M17.7 6.3l1.4-1.4'],
    moon: ['M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z'],
  };

  function icon(name, cls) {
    const root = svg('svg', { viewBox: '0 0 24 24', width: 16, height: 16, 'aria-hidden': 'true', focusable: 'false', class: 'icon' + (cls ? ' ' + cls : '') });
    for (const d of ICONS[name] || []) root.appendChild(svg('path', { d: d }));
    return root;
  }

  // ---------------------------------------------------------------------
  // Status colours for widgets
  // ---------------------------------------------------------------------

  const STATUS = {
    good: { label: 'Норма', icon: 'good' },
    warn: { label: 'Внимание', icon: 'warning' },
    bad: { label: 'Плохо', icon: 'critical' },
  };

  /** lower-is-better: value <= a → good, <= b → warn, else bad. */
  function statusLow(v, a, b) { return v === null || v === undefined || !isFinite(v) ? null : v <= a ? 'good' : v <= b ? 'warn' : 'bad'; }
  /** higher-is-better: value >= a → good, >= b → warn, else bad. */
  function statusHigh(v, a, b) { return v === null || v === undefined || !isFinite(v) ? null : v >= a ? 'good' : v >= b ? 'warn' : 'bad'; }

  function statusVar(st) { return st === 'good' ? 'var(--good)' : st === 'warn' ? 'var(--warning)' : st === 'bad' ? 'var(--critical)' : 'var(--accent)'; }

  // ---------------------------------------------------------------------
  // Mini sparkline for the stat widgets (scales with CSS, no redraw needed)
  // ---------------------------------------------------------------------

  const MINI_POINTS = 240;

  function miniSpark(R, values, opts) {
    const box = el('div', { class: 'w-spark' });
    const n = values ? values.length : 0;
    if (!n || values.every((v) => v === null)) return box;
    const W = 240, H = 44;
    // Long reports: average into at most MINI_POINTS buckets.
    const b = Math.min(n, MINI_POINTS);
    const pts = new Array(b);
    for (let k = 0; k < b; k++) {
      const i0 = Math.floor(k * n / b), i1 = Math.max(i0 + 1, Math.floor((k + 1) * n / b));
      let s = 0, c = 0;
      for (let i = i0; i < i1; i++) if (values[i] !== null) { s += values[i]; c++; }
      pts[k] = c ? s / c : null;
    }
    let lo = opts.min !== undefined ? opts.min : Math.min(0, minOf(pts) || 0);
    let hi = opts.max !== undefined ? opts.max : (maxOf(pts) || 0);
    if (opts.max === undefined) hi = hi * 1.1;
    if (!(hi > lo)) hi = lo + 1;
    const X = (k) => (b > 1 ? k / (b - 1) * W : W / 2);
    const Y = (v) => 3 + (1 - clamp((v - lo) / (hi - lo), 0, 1)) * (H - 6);
    const root = svg('svg', { viewBox: '0 0 ' + W + ' ' + H, preserveAspectRatio: 'none', role: 'img', 'aria-label': opts.label || '' });
    let d = '', open = false, first = -1, last = -1;
    for (let k = 0; k < b; k++) {
      if (pts[k] === null) { open = false; continue; }
      if (first < 0) first = k;
      last = k;
      d += (open ? 'L' : 'M') + X(k).toFixed(1) + ',' + Y(pts[k]).toFixed(1);
      open = true;
    }
    if (b === 1) d = 'M0,' + Y(pts[0]).toFixed(1) + 'L' + W + ',' + Y(pts[0]).toFixed(1);
    const flat = (maxOf(pts) || 0) === (minOf(pts) || 0);
    if (!opts.noArea && !flat && pts.every((v) => v !== null) && b > 1) {
      const a = svg('path', { d: d + 'L' + X(last).toFixed(1) + ',' + H + 'L' + X(first).toFixed(1) + ',' + H + 'Z', class: 'w-spark-area' });
      a.style.fill = gradientFill(root, opts.color, 0.35);
      root.appendChild(a);
    }
    if (opts.ref !== undefined && opts.ref > lo && opts.ref < hi) {
      root.appendChild(svg('line', { x1: 0, x2: W, y1: Y(opts.ref), y2: Y(opts.ref), class: 'w-spark-ref', 'vector-effect': 'non-scaling-stroke' }));
    }
    const line = svg('path', { d: d, class: 'w-spark-line', 'vector-effect': 'non-scaling-stroke' });
    line.style.stroke = opts.color;
    root.appendChild(line);
    const cross = svg('line', { y1: 0, y2: H, class: 'w-spark-cross', 'vector-effect': 'non-scaling-stroke', visibility: 'hidden' });
    root.appendChild(cross);
    const onMove = (evt) => {
      const r = root.getBoundingClientRect();
      const f = clamp((evt.clientX - r.left) / (r.width || 1), 0, 1);
      const i = Math.round(f * (n - 1));
      const x = n > 1 ? i / (n - 1) * W : W / 2;
      cross.setAttribute('x1', x); cross.setAttribute('x2', x);
      cross.setAttribute('visibility', 'visible');
      showTip(evt, timeLabel(R, R.t[i] !== undefined ? R.t[i] : 0), [{ color: opts.color, line: true, name: opts.label || '', value: values[i] === null ? 'нет данных' : opts.fmt(values[i]) }]);
    };
    root.addEventListener('pointermove', onMove);
    root.addEventListener('pointerdown', onMove);
    root.addEventListener('pointerleave', () => { cross.setAttribute('visibility', 'hidden'); hideTip(); });
    box.appendChild(root);
    return box;
  }

  function widget(R, o) {
    const st = o.status || null;
    const w = el('div', { class: 'widget' + (st ? ' st-' + st : ' st-none') + (o.key ? ' key' : '') });
    const head = el('div', { class: 'w-head' }, [el('span', { class: 'w-label', text: o.label })]);
    if (st) {
      head.appendChild(el('span', { class: 'w-status' }, [icon(STATUS[st].icon), STATUS[st].label]));
    } else if (o.key) {
      head.appendChild(el('span', { class: 'w-status' }, 'ключевой'));
    }
    w.appendChild(head);
    const value = el('div', { class: 'w-value' }, [el('span', { class: 'w-num', text: o.value })]);
    if (o.unit) value.appendChild(el('span', { class: 'w-unit', text: o.unit }));
    w.appendChild(value);
    w.appendChild(el('div', { class: 'w-sub', text: o.sub || ' ' }));
    if (o.bar !== undefined && o.bar !== null) {
      const m = el('div', { class: 'w-bar' });
      const f = el('span');
      f.style.width = clamp(o.bar * 100, 0, 100).toFixed(1) + '%';
      f.style.background = statusVar(st);
      m.appendChild(f);
      w.appendChild(m);
    }
    if (o.spark) w.appendChild(miniSpark(R, o.spark.values, Object.assign({ color: statusVar(st) }, o.spark)));
    return w;
  }

  // ---------------------------------------------------------------------
  // Cards, notices, small parts
  // ---------------------------------------------------------------------

  function card(opts, body) {
    const c = el('section', { class: 'card' + (opts.cls ? ' ' + opts.cls : ''), id: opts.id || null });
    if (opts.title || opts.right) {
      const head = el('div', { class: 'card-head' });
      const left = el('div', { class: 'card-titles' }, [
        opts.title ? el('h2', { text: opts.title }) : null,
        opts.desc ? el('p', { class: 'card-desc', text: opts.desc }) : null,
      ]);
      head.appendChild(left);
      if (opts.right) head.appendChild(el('div', { class: 'card-tools' }, opts.right));
      c.appendChild(head);
    }
    append(c, body);
    return c;
  }

  function noticeEl(kind, title, text) {
    return el('div', { class: 'notice ' + kind, role: kind === 'error' ? 'alert' : null }, [
      icon(kind === 'error' ? 'critical' : kind === 'warn' ? 'warning' : 'info', 'notice-icon'),
      el('div', null, [
        el('p', { class: 'notice-title', text: title }),
        text ? el('p', { text: text }) : null,
      ]),
    ]);
  }

  /** Summary chip: small label, big (status-coloured) value, optional unit and sub-line. */
  function chip(label, value, o) {
    o = o || {};
    const st = o.status || null;
    const v = el('div', { class: 'sc-value' }, [el('span', { class: 'sc-num', text: value })]);
    if (o.unit) v.appendChild(el('span', { class: 'sc-unit', text: o.unit }));
    return el('div', { class: 'stat-chip' + (st ? ' has-st st-' + st : '') }, [
      el('span', { class: 'sc-label' }, label),
      v,
      o.sub ? el('span', { class: 'sc-sub', text: o.sub }) : null,
    ]);
  }

  function kv(rows) {
    const dl = el('dl', { class: 'kv' });
    for (const r of rows) {
      dl.appendChild(el('dt', { text: r[0] }));
      dl.appendChild(el('dd', { class: r[2] ? 'mono' : null, text: r[1] === null || r[1] === undefined || r[1] === '' ? '—' : r[1] }));
    }
    return dl;
  }

  function moreButton(text, onClick) {
    const b = el('button', { class: 'btn btn-ghost btn-more', type: 'button', text: text });
    b.addEventListener('click', onClick);
    return b;
  }

  function reportSectionFailure(where, err) {
    if (window.console && console.warn) console.warn('amazake viewer: section failed', err);
    const note = noticeEl('error', 'Не удалось показать этот раздел', 'Возможно, в отчёте не хватает данных. Остальные разделы доступны на других вкладках.');
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

  function stackedGroupSeries(R) {
    const colored = R.groups.filter((g) => Object.prototype.hasOwnProperty.call(GROUP_COLORS, g.id) && g.series);
    const rest = R.groups.filter((g) => colored.indexOf(g) < 0 && g.series);
    const order = Object.keys(GROUP_COLORS);
    colored.sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
    const series = colored.map((g) => ({ name: groupName(g.id), values: g.series, color: groupColor(g.id), id: g.id }));
    if (rest.length) {
      const vals = new Array(R.n).fill(0);
      for (const g of rest) for (let i = 0; i < R.n; i++) vals[i] += g.series[i] || 0;
      series.push({ name: 'Остальное', values: vals, color: REST_COLOR, id: '_rest' });
    }
    return { series: series, rest: rest };
  }

  // ---------------------------------------------------------------------
  // Tab: Обзор
  // ---------------------------------------------------------------------

  function tabOverview(R) {
    const C = R.system.logicalCores;
    const grid = el('div', { class: 'widgets' });

    // TPS
    const tpsAvg = mean(R.tps), tpsMin = minOf(R.tps);
    grid.appendChild(widget(R, {
      label: 'TPS', value: fmtNum(tpsAvg, 2), status: statusHigh(tpsAvg, 19.5, 17),
      sub: tpsAvg === null ? 'нет данных' : 'минимум ' + fmtNum(tpsMin, 1) + ' · норма 20',
      spark: { values: R.tps, min: 0, max: 20, label: 'TPS', fmt: (v) => fmtNum(v, 2) },
    }));

    // MSPT
    const msptAvg = mean(R.msptAvg), msptMax = maxOf(R.msptMax);
    grid.appendChild(widget(R, {
      label: 'MSPT', value: fmtNum(msptAvg, 1), unit: msptAvg === null ? '' : 'мс', status: statusLow(msptAvg, 40, 50),
      sub: msptAvg === null ? 'нет данных' : 'максимум ' + fmtMs(msptMax) + ' · предел 50 мс',
      spark: { values: R.msptAvg, min: 0, ref: 50, label: 'Средний тик', fmt: fmtMs },
    }));

    // Main thread
    const main = R.group('main');
    grid.appendChild(widget(R, {
      key: true,
      label: 'Главный поток', value: main ? fmtNum(main.avgCores * 100, 0) : '—', unit: main ? '%' : '',
      status: main ? statusLow(main.avgCores, 0.7, 0.9) : null,
      sub: main ? 'одного ядра · пик ' + fmtPct(main.peakCores) : 'нет данных о Server thread',
      spark: main && main.series ? { values: main.series, min: 0, max: Math.max(1, (maxOf(main.series) || 0)), ref: 1, label: 'Главный поток', fmt: (v) => fmtPct(v) + ' ядра' } : null,
    }));

    // Server CPU
    const procAvg = mean(R.processCpu), procPeak = maxOf(R.processCpu);
    grid.appendChild(widget(R, {
      label: 'CPU сервера', value: procAvg === null ? '—' : fmtNum(procAvg * 100, 0), unit: procAvg === null ? '' : '%',
      sub: procAvg === null ? 'нет данных' : '≈ ' + fmtNum(procAvg * C, 1) + ' из ' + C + ' ' + coresWord(C) + ' · пик ' + fmtPct(procPeak),
      spark: { values: R.processCpu, min: 0, label: 'CPU сервера', fmt: (v) => fmtPct(v) + ' · ' + fmtCores(v * C) },
    }));

    // Machine CPU
    const sysAvg = mean(R.systemCpu), sysPeak = maxOf(R.systemCpu);
    grid.appendChild(widget(R, {
      label: 'CPU машины', value: sysAvg === null ? '—' : fmtNum(sysAvg * 100, 0), unit: sysAvg === null ? '' : '%',
      status: statusLow(sysAvg, 0.7, 0.85),
      sub: sysAvg === null ? 'нет данных' : 'пик ' + fmtPct(sysPeak) + ' · ' + C + ' ' + plural(C, ['логическое ядро', 'логических ядра', 'логических ядер']),
      spark: { values: R.systemCpu, min: 0, max: 1, label: 'CPU машины', fmt: (v) => fmtPct(v) },
    }));

    // Heap
    const heapMax = R.system.heapMaxBytes, heapPeak = maxOf(R.heapUsed), heapAvg = mean(R.heapUsed);
    const heapFrac = heapMax && heapPeak !== null ? heapPeak / heapMax : null;
    grid.appendChild(widget(R, {
      label: 'Память (куча)', value: heapPeak === null ? '—' : fmtBytes(heapPeak), unit: heapMax ? '/ ' + fmtBytes(heapMax) : '',
      status: statusLow(heapFrac, 0.75, 0.9),
      sub: heapPeak === null ? 'нет данных' : 'пик' + (heapFrac !== null ? ' ' + fmtPct(heapFrac) + ' кучи' : '') + ' · в среднем ' + fmtBytes(heapAvg),
      bar: heapFrac,
      spark: { values: R.heapUsed, min: 0, max: heapMax || undefined, label: 'Занято', fmt: fmtBytes },
    }));

    // GC
    const gcTotal = sum(R.gcTimeMs), gcRuns = sum(R.gcCount);
    const gcKnown = R.gcTimeMs.some((v) => v !== null);
    const gcShare = gcKnown && R.durationMs > 0 ? gcTotal / R.durationMs : null;
    const perMin = gcKnown && R.durationMs > 0 ? gcTotal / (R.durationMs / 60000) : null;
    grid.appendChild(widget(R, {
      label: 'Сборка мусора', value: fmtNum(perMin, 0), unit: perMin === null ? '' : 'мс/мин',
      status: statusLow(gcShare, 0.03, 0.1),
      sub: gcKnown ? fmtNum(gcRuns) + ' ' + plural(gcRuns, ['сборка', 'сборки', 'сборок']) + ' · ' + fmtPct(gcShare, 1) + ' времени' : 'нет данных',
      spark: { values: R.gcTimeMs, min: 0, label: 'Время GC', fmt: fmtMs },
    }));

    // Players / entities / chunks
    const lastOf = (a) => { for (let i = a.length - 1; i >= 0; i--) if (a[i] !== null) return a[i]; return null; };
    let players = lastOf(R.players);
    if (players === null) players = R.server.onlinePlayers;
    let entities = lastOf(R.entities), chunks = lastOf(R.loadedChunks);
    if (entities === null && R.server.worlds.length) entities = sum(R.server.worlds.map((w) => w.entities));
    if (chunks === null && R.server.worlds.length) chunks = sum(R.server.worlds.map((w) => w.loadedChunks));
    grid.appendChild(widget(R, {
      label: 'Игроки', value: fmtNum(players), unit: R.server.maxPlayers !== null && players !== null ? '/ ' + fmtNum(R.server.maxPlayers) : '',
      sub: 'сущности ' + fmtNum(entities) + ' · чанки ' + fmtNum(chunks),
      spark: { values: R.players, min: 0, noArea: true, label: 'Игроки', fmt: (v) => fmtNum(v) },
    }));

    // Findings
    const list = el('ul', { class: 'findings' });
    for (const f of current.findings) {
      const li = el('li', { class: 'finding sev-' + f.severity });
      // Phones show two lines of each finding; this button (hidden on wide screens) opens the rest.
      const more = el('button', { class: 'finding-more', type: 'button', 'aria-expanded': 'false', text: 'Подробнее' });
      more.addEventListener('click', () => {
        const open = li.classList.toggle('open');
        more.setAttribute('aria-expanded', String(open));
        more.textContent = open ? 'Свернуть' : 'Подробнее';
      });
      append(li, [
        el('span', { class: 'finding-icon' }, icon(f.severity === 'info' ? 'info' : f.severity)),
        el('div', { class: 'finding-body' }, [
          el('div', { class: 'finding-top' }, [
            el('p', { class: 'finding-title', text: f.title }),
            el('span', { class: 'badge sev-' + f.severity, text: SEVERITY[f.severity].label }),
          ]),
          el('p', { class: 'finding-text', text: f.text }),
          more,
        ]),
      ]);
      list.appendChild(li);
    }
    const findCard = card({ title: 'Выводы', desc: 'Автоматические подсказки по этому замеру, самые важные сверху. Это правила, а не приговор: сверяйтесь с графиками на вкладках.' }, list);

    // Top groups
    const total = sum(R.groups.map((g) => g.avgCores));
    const top = el('div', { class: 'toplist' });
    for (const g of R.groups.slice(0, 7)) {
      const share = total > 0 ? g.avgCores / total : 0;
      const bar = el('div', { class: 'share-bar' });
      const fill = el('span');
      fill.style.width = clamp(share * 100, 0, 100).toFixed(1) + '%';
      fill.style.background = groupColor(g.id);
      bar.appendChild(fill);
      top.appendChild(el('div', { class: 'toplist-row' }, [
        el('div', { class: 'toplist-name' }, [swatch(g.id), el('span', { text: groupName(g.id) })]),
        el('span', { class: 'toplist-val mono', text: fmtNum(g.avgCores, 2) }),
        bar,
      ]));
    }
    const goThreads = el('button', { class: 'btn btn-ghost btn-small', type: 'button' }, ['Все потоки', icon('arrow')]);
    goThreads.addEventListener('click', () => setTab('threads', true));
    const topCard = card({ title: 'Кто ест процессор', desc: 'Среднее число занятых ядер по группам потоков.', cls: 'side' },
      R.groups.length ? [top, el('div', { class: 'card-foot' }, goThreads)] : el('p', { class: 'empty', text: 'В отчёте нет данных о группах потоков.' }));

    const layoutCard = coreLayoutCard(R);
    if (!layoutCard) return [grid, el('div', { class: 'overview-split' }, [findCard, topCard])];
    topCard.classList.remove('side');
    return [grid, el('div', { class: 'overview-split' }, [findCard, el('div', { class: 'side side-stack' }, [topCard, layoutCard])])];
  }

  /** Overview card «Раскладка ядер»: each role, its cores and how busy they were. Null without a layout. */
  function coreLayoutCard(R) {
    const stats = layoutStats(R);
    if (!stats) return null;
    const list = el('div', { class: 'toplist' });
    for (const r of stats) {
      const bar = el('div', { class: 'share-bar' });
      const fill = el('span');
      fill.style.width = clamp(r.avgLoad * 100, 0, 100).toFixed(1) + '%';
      fill.style.background = CORE_ROLES[r.id].color;
      bar.appendChild(fill);
      list.appendChild(el('div', { class: 'toplist-row' }, [
        el('div', { class: 'toplist-name' }, [roleChip(r.id), el('span', { class: 'mono', text: coresLabel(r.cores) })]),
        el('span', { class: 'toplist-val mono', text: fmtPct(r.avgLoad) }),
        bar,
      ]));
    }
    const go = el('button', { class: 'btn btn-ghost btn-small', type: 'button' }, ['Ядра', icon('arrow')]);
    go.addEventListener('click', () => setTab('cores', true));
    return card({ title: 'Раскладка ядер', desc: 'У каждого ядра своя работа (amazake.yml, cpu.layout). Справа — средняя загрузка ядер роли.' },
      [list, el('div', { class: 'card-foot' }, go)]);
  }

  function swatch(id) {
    const sw = el('span', { class: 'swatch' });
    sw.style.background = groupColor(id);
    return sw;
  }

  // ---------------------------------------------------------------------
  // Tab: Ядра
  // ---------------------------------------------------------------------

  const CORES_COMPACT = 32;
  const CORES_TOP = 16;

  /** Chips above the heatmap: which cores each role has. */
  function roleLegend(R) {
    const box = el('div', { class: 'role-legend' });
    for (const r of layoutStats(R) || []) {
      box.appendChild(el('span', { class: 'role-legend-item' }, [roleChip(r.id), el('span', { class: 'mono', text: fmtCoreList(r.cores) })]));
    }
    return box;
  }

  /** «Раскладка ядер» on the cores tab: per role, its cores, their load and its own threads' load. */
  function layoutTableCard(R) {
    const stats = layoutStats(R);
    if (!stats) return null;
    const columns = [
      { label: 'Роль', render: (r) => el('span', { class: 'role-cell' }, [roleChip(r.id), el('span', { class: 'role-name', text: CORE_ROLES[r.id].name })]) },
      { label: 'Ядра', cls: 'mono nowrap', render: (r) => fmtCoreList(r.cores) },
      { label: 'Загрузка ядер', short: 'Загрузка', cls: 'num', render: (r) => {
        const m = el('div', { class: 'meter' });
        const fill = el('span');
        fill.style.width = clamp(r.avgLoad * 100, 0, 100).toFixed(1) + '%';
        fill.style.background = r.avgLoad >= 0.9 ? statusVar('bad') : r.avgLoad >= 0.7 ? statusVar('warn') : CORE_ROLES[r.id].color;
        m.appendChild(fill);
        return el('span', { class: 'role-load' }, [m, el('span', { class: 'mono', text: fmtPct(r.avgLoad) })]);
      } },
      { label: 'Свои потоки', short: 'Потоки', cls: 'num mono', render: (r) => (r.threadCores === null ? '—'
        : el('span', null, [el('span', { class: 'th-full', text: fmtCores(r.threadCores) }), el('span', { class: 'th-short', text: fmtCoresShort(r.threadCores) })])) },
    ];
    const t = table(columns, stats);
    t.wrap.classList.add('layout-table');
    return card({
      title: 'Раскладка ядер',
      desc: 'Сервер закрепил группы потоков за своими ядрами (amazake.yml, cpu.layout). «Загрузка ядер» — средняя загрузка этих ядер всеми процессами машины, ' +
        '«Свои потоки» — сколько ядер в среднем заняли потоки этой роли. Если ядра загружены заметно больше, чем свои потоки, на них работает кто-то ещё.',
    }, t.wrap);
  }

  /** «Переезды главного потока»: one row per migrations[] entry; null when there are none. */
  function migrationsCard(R) {
    if (!R.migrations.length) return null;
    const columns = [
      { label: 'Когда', cls: 'mono nowrap', render: (m) => timeLabel(R, m.t) },
      { label: 'Откуда', cls: 'mono', render: (m) => 'ядро ' + m.from },
      { label: 'Куда', cls: 'mono', render: (m) => 'ядро ' + m.to },
      { label: 'Чужая нагрузка', short: 'Чужая', cls: 'num mono', render: (m) => (m.foreignLoad === null ? '—' : fmtPct(m.foreignLoad)) },
    ];
    const t = table(columns, R.migrations);
    return card({
      title: 'Переезды главного потока',
      desc: 'Ядро тика было занято не главным потоком (другие процессы, прерывания, steal) две проверки подряд, и сервер перенёс главный поток на пустое резервное ядро; старое ядро стало резервом. ' +
        'На тепловой карте переезд — пунктир: кружок-контур на старом ядре, закрашенный кружок на новом.',
    }, t.wrap);
  }

  function tabCores(R) {
    const C = R.system.logicalCores;
    const out = [];
    if (R.system.perCore && R.n) {
      const avg = current.coreAvg || (current.coreAvg = coreAverages(R));
      out.push(card({
        title: 'Загрузка ядер',
        desc: 'Строка — логическое ядро, столбец — интервал замера (в длинных замерах соседние интервалы объединяются, клетка показывает максимум). Чем ярче, тем сильнее загружено ядро всеми процессами машины.',
        right: heatLegend(),
      }, [R.system.coreRoles ? roleLegend(R) : null, heatmap(R)]));
      const layout = R.system.coreRoles ? layoutTableCard(R) : null;
      if (layout) out.push(layout);
      const moves = migrationsCard(R);
      if (moves) out.push(moves);
      const roles = R.system.coreRoles;
      const combined = roles && roles.some((v) => v.indexOf('+') >= 0);
      const bars = el('div', { class: 'core-bars' + (roles ? ' has-roles' : '') + (combined ? ' combined-roles' : '') });
      const coreBar = (k) => {
        const v = avg[k];
        const st = v >= 0.9 ? 'bad' : v >= 0.7 ? 'warn' : null;
        const m = el('div', { class: 'meter' });
        const fill = el('span');
        fill.style.width = clamp(v * 100, 0, 100).toFixed(1) + '%';
        fill.style.background = st ? statusVar(st) : 'var(--accent)';
        m.appendChild(fill);
        return el('div', { class: 'core-bar' }, [
          el('span', { class: 'name mono', text: 'ядро ' + k }), roles ? roleChip(roles[k] || 'shared') : null, m, el('span', { class: 'val mono', text: fmtPct(v) }),
        ]);
      };
      const fillBars = (idx) => {
        bars.textContent = '';
        const frag = document.createDocumentFragment();
        for (const k of idx) frag.appendChild(coreBar(k));
        bars.appendChild(frag);
      };
      const all = avg.map((_, k) => k);
      const med = medianOf(avg);
      const body = [bars];
      let desc = 'Медиана ' + fmtPct(med) + ', самое загруженное ' + fmtPct(maxOf(avg)) + '. Жёлтым — от 70 %, красным — от 90 %.';
      if (avg.length > CORES_COMPACT) {
        // Big machines: the busiest cores first, the full list on demand.
        const busiest = all.slice().sort((a, b) => avg[b] - avg[a]).slice(0, CORES_TOP);
        fillBars(busiest);
        desc += ' Показаны ' + CORES_TOP + ' самых загруженных из ' + avg.length + '.';
        const foot = el('div', { class: 'card-foot' });
        foot.appendChild(moreButton('Показать все ' + avg.length + ' ' + coresWord(avg.length), () => { fillBars(all); foot.remove(); }));
        body.push(foot);
      } else {
        fillBars(all);
      }
      out.push(card({ title: 'Средняя загрузка каждого ядра', desc: desc }, body));
    } else {
      out.push(noticeEl('info', 'Загрузка по отдельным ядрам недоступна', R.n ? 'Сервер собирает её только на Linux (/proc/stat). Ниже — общая загрузка машины.' : 'В отчёте нет замеров.'));
    }
    const proc = R.processCpu.map((v) => (v === null ? null : v * C));
    const other = R.systemCpu.map((v, i) => (v === null || R.processCpu[i] === null ? null : Math.max(0, v - R.processCpu[i]) * C));
    out.push(card({
      title: 'Сервер и остальные процессы',
      desc: 'Сколько ядер в каждый момент занимал сервер Minecraft и сколько — всё остальное на машине (например, Discord-бот или база данных).',
    }, [
      legend([
        { name: 'Сервер Minecraft', color: 'var(--s1)' },
        { name: 'Другие процессы', color: REST_COLOR },
        { name: 'Все ядра машины (' + C + ')', dashed: true },
      ]),
      tsChart(R, () => ({
        stacked: true,
        height: 240,
        series: [
          { name: 'Сервер Minecraft', values: proc, color: 'var(--s1)' },
          { name: 'Другие процессы', values: other, color: REST_COLOR },
        ],
        refs: [{ y: C, label: 'все ядра: ' + C }],
        fixedMax: C,
        yFmt: (v) => fmtNum(v, v % 1 ? 1 : 0),
        valFmt: fmtCores,
        ariaLabel: 'Загрузка машины: сервер и другие процессы',
      })),
    ]));
    return out;
  }

  // ---------------------------------------------------------------------
  // Tab: Потоки (stacked chart + expandable group tree)
  // ---------------------------------------------------------------------

  function tabThreads(R) {
    const C = R.system.logicalCores;
    const out = [];
    const st = stackedGroupSeries(R);

    let fullScale = false;
    const seg = el('div', { class: 'seg', role: 'group', 'aria-label': 'Масштаб графика' });
    const bFit = el('button', { type: 'button', 'aria-pressed': 'true', text: 'По данным' });
    const bAll = el('button', { type: 'button', 'aria-pressed': 'false', text: 'Все ядра (' + C + ')' });
    seg.append(bFit, bAll);
    const chart = tsChart(R, () => ({
      stacked: true,
      height: 280,
      series: st.series,
      fixedMax: fullScale ? C : 0,
      refs: [
        { y: 1, label: '1 ядро', strong: false },
        { y: C, label: 'все ядра: ' + C, strong: true },
      ],
      yFmt: (v) => fmtNum(v, v % 1 ? 1 : 0),
      valFmt: fmtCores,
      ariaLabel: 'Загрузка процессора по группам потоков',
    }));
    const legendItems = () => {
      const items = st.series.map((s) => ({ name: s.id === '_rest' ? 'Остальное: ' + st.rest.map((g) => groupName(g.id)).join(', ') : s.name, color: s.color }));
      items.push({ name: fullScale ? 'Пунктир: 1 ядро и все ядра (' + C + ')' : 'Пунктир: 1 ядро (все ' + C + ' — в режиме «Все ядра»)', dashed: true });
      return items;
    };
    let legendEl = legend(legendItems());
    const setScale = (all) => {
      fullScale = all;
      bFit.setAttribute('aria-pressed', String(!all));
      bAll.setAttribute('aria-pressed', String(all));
      const next = legend(legendItems());
      legendEl.replaceWith(next);
      legendEl = next;
      chart._redraw();
    };
    bFit.addEventListener('click', () => setScale(false));
    bAll.addEventListener('click', () => setScale(true));

    if (R.groups.length) {
      out.push(card({
        title: 'Кто ест процессор',
        desc: 'Потоки сервера, сгруппированные по назначению. Высота — сколько ядер группа занимала в этот момент; 1,0 = одно ядро полностью.',
        right: seg,
      }, st.series.length ? [legendEl, chart] : el('p', { class: 'chart-note', text: 'У групп нет рядов по времени, показан только список ниже.' })));
    }
    out.push(threadTree(R));
    return out;
  }

  const TREE_FIRST = 50;
  const TREE_STEP = 100;

  function threadTree(R) {
    const C = R.system.logicalCores;
    // Groups from the report plus any group that only appears on threads.
    const groups = R.groups.map((g) => ({ g: g, threads: [] }));
    const byId = new Map(groups.map((x) => [x.g.id, x]));
    for (const t of R.threads) {
      let x = byId.get(t.group);
      if (!x) {
        x = { g: { id: t.group, threads: 0, cpuMs: 0, avgCores: 0, peakCores: 0, series: null, synthetic: true }, threads: [] };
        byId.set(t.group, x);
        groups.push(x);
      }
      x.threads.push(t);
      if (x.g.synthetic) { x.g.threads++; x.g.cpuMs += t.cpuMs; x.g.avgCores += t.avgCores; }
    }
    groups.sort((a, b) => b.g.avgCores - a.g.avgCores);
    const total = sum(groups.map((x) => x.g.avgCores));
    const expanded = new Set();

    const input = el('input', { class: 'input', type: 'search', placeholder: 'Поиск потока или группы…', 'aria-label': 'Фильтр потоков', autocomplete: 'off', spellcheck: 'false' });
    const searchBox = el('label', { class: 'search' }, [icon('search'), input]);
    const count = el('span', { class: 'count' });
    const list = el('div', { class: 'tree', role: 'list' });

    const header = el('div', { class: 'tree-row tree-header', 'aria-hidden': 'true' }, [
      el('span', { class: 'tc-name', text: 'Группа / поток' }),
      el('span', { class: 'tc-share', text: 'Доля CPU сервера' }),
      el('span', { class: 'tc-num', text: 'Среднее' }),
      el('span', { class: 'tc-extra' }, [
        el('span', { class: 'tc-num', text: 'Пик' }),
        el('span', { class: 'tc-num', text: 'Потоков' }),
        el('span', { class: 'tc-num', text: 'CPU-время' }),
        el('span', { class: 'tc-spark', text: 'По времени' }),
      ]),
    ]);

    // Peak, thread count, CPU time and sparkline. On wide screens these are grid
    // columns (display: contents); on phones they become a labelled second line.
    function extraCells(peak, nThreads, cpuMs, series, name, color) {
      return el('span', { class: 'tc-extra' }, [
        el('span', { class: 'tc-num mono', 'data-l': 'пик', text: peak === null ? '—' : fmtNum(peak, 2) }),
        el('span', { class: 'tc-num mono' + (nThreads === null ? ' na' : ''), 'data-l': 'потоков', text: nThreads === null ? '—' : fmtNum(nThreads) }),
        el('span', { class: 'tc-num mono', 'data-l': 'CPU', title: fmtNum(cpuMs) + ' мс CPU', text: fmtNum(cpuMs / 1000, 1) + NBSP + 'с' }),
        el('span', { class: 'tc-spark' }, series ? sparkline(R, series, name, color) : el('span', { class: 'muted', text: '—' })),
      ]);
    }

    function shareCell(v, color) {
      const share = total > 0 ? v / total : 0;
      const bar = el('div', { class: 'share-bar' });
      const fill = el('span');
      fill.style.width = clamp(share * 100, 0, 100).toFixed(2) + '%';
      fill.style.background = color;
      bar.appendChild(fill);
      return el('span', { class: 'tc-share' }, [bar, el('span', { class: 'share-pct mono', text: total > 0 ? fmtNum(share * 100, share * 100 < 9.95 ? 1 : 0) + ' %' : '—' })]);
    }

    function threadRow(t) {
      const peak = t.series ? maxOf(t.series) : null;
      return el('div', { class: 'tree-row thread-row', role: 'listitem' }, [
        el('span', { class: 'tc-name' }, [el('span', { class: 'thread-name mono', text: t.name, title: t.name })]),
        shareCell(t.avgCores, groupColor(t.group)),
        el('span', { class: 'tc-num mono', 'data-l': 'среднее', text: fmtNum(t.avgCores, 2) }),
        extraCells(peak, null, t.cpuMs, t.series, t.name, groupColor(t.group)),
      ]);
    }

    function groupBlock(x, threads, open) {
      const g = x.g;
      const block = el('div', { class: 'tree-group' + (open ? ' open' : ''), role: 'listitem' });
      const btn = el('button', { class: 'tree-row group-row', type: 'button', 'aria-expanded': String(open) }, [
        el('span', { class: 'tc-name' }, [icon('chevron', 'chev'), swatch(g.id), el('span', { class: 'group-label', text: groupName(g.id) })]),
        shareCell(g.avgCores, groupColor(g.id)),
        el('span', { class: 'tc-num mono', 'data-l': 'среднее', text: fmtNum(g.avgCores, 2) }),
        extraCells(g.synthetic ? null : g.peakCores, g.threads, g.cpuMs, g.series, groupName(g.id), groupColor(g.id)),
      ]);
      block.appendChild(btn);
      const body = el('div', { class: 'tree-children' });
      let shown = 0;
      const fill = (k) => {
        const more = body.querySelector('.tree-more');
        if (more) more.remove();
        const end = Math.min(threads.length, shown + k);
        const frag = document.createDocumentFragment();
        for (let i = shown; i < end; i++) frag.appendChild(threadRow(threads[i]));
        body.appendChild(frag);
        shown = end;
        if (shown < threads.length) {
          const rest = threads.length - shown;
          body.appendChild(el('div', { class: 'tree-more' }, moreButton('Показать ещё ' + Math.min(TREE_STEP, rest) + ' из ' + rest, () => fill(TREE_STEP))));
        }
      };
      const build = () => {
        if (body.childNodes.length) return;
        body.appendChild(el('p', { class: 'tree-meta' }, [
          fmtPct(g.avgCores / C, 1) + ' всей машины · ' + (total > 0 ? fmtPct(g.avgCores / total, 0) : '—') + ' нагрузки сервера · CPU-время ' + fmtNum(g.cpuMs / 1000, 1) + ' с',
          g.peakCores && !g.synthetic ? ' · пик ' + fmtCores(g.peakCores) : '',
        ]));
        if (!threads.length) body.appendChild(el('p', { class: 'tree-meta muted', text: 'Потоки этой группы в отчёт не попали.' }));
        fill(TREE_FIRST);
      };
      if (open) { build(); block.appendChild(body); }
      btn.addEventListener('click', () => {
        const now = !block.classList.contains('open');
        block.classList.toggle('open', now);
        btn.setAttribute('aria-expanded', String(now));
        if (now) { expanded.add(g.id); build(); block.appendChild(body); } else { expanded.delete(g.id); body.remove(); }
      });
      return block;
    }

    const apply = () => {
      const q = input.value.trim().toLowerCase();
      list.textContent = '';
      list.appendChild(header);
      let nThreads = 0, nGroups = 0;
      for (const x of groups) {
        const gMatch = q && (x.g.id.toLowerCase().includes(q) || groupName(x.g.id).toLowerCase().includes(q));
        const threads = !q || gMatch ? x.threads : x.threads.filter((t) => t.name.toLowerCase().includes(q));
        if (q && !gMatch && !threads.length) continue;
        nGroups++;
        nThreads += threads.length;
        list.appendChild(groupBlock(x, threads, q ? (!gMatch || expanded.has(x.g.id)) : expanded.has(x.g.id)));
      }
      if (!nGroups) list.appendChild(el('p', { class: 'empty', text: 'Ничего не найдено.' }));
      count.textContent = q
        ? 'найдено: ' + nThreads + ' ' + plural(nThreads, ['поток', 'потока', 'потоков']) + ' в ' + nGroups + ' ' + plural(nGroups, ['группе', 'группах', 'группах'])
        : R.threads.length + ' ' + plural(R.threads.length, ['поток', 'потока', 'потоков']) + ' в ' + groups.length + ' ' + plural(groups.length, ['группе', 'группах', 'группах']);
    };
    let timer = 0;
    input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(apply, 120); });
    if (groups.length) { expanded.add(groups[0].g.id); apply(); }

    const src = R.system.perThreadSource === 'jmx'
      ? 'Источник: JMX — только Java-потоки, внутренние потоки JVM (GC, JIT) не видны.'
      : R.system.perThreadSource === 'proc' ? 'Источник: /proc — все потоки процесса, включая GC и JIT; Linux обрезает имена до 15 символов.' : '';
    return card({
      title: 'Группы и потоки',
      desc: 'Нажмите на группу, чтобы увидеть её потоки. Полоса — доля в общей нагрузке сервера; «Среднее» и «Пик» — в ядрах. График по времени есть у 24 самых занятых потоков. ' + src,
      right: groups.length ? [searchBox] : null,
    }, groups.length ? [el('div', { class: 'tree-tools' }, count), list] : el('p', { class: 'empty', text: 'В отчёте нет данных о потоках.' }));
  }

  // ---------------------------------------------------------------------
  // Tab: Тики
  // ---------------------------------------------------------------------

  const SPIKES_FIRST = 20;

  function tabTicks(R) {
    const tpsAvg = mean(R.tps), msptAvg = mean(R.msptAvg), msptMax = maxOf(R.msptMax);
    const tpsMin = minOf(R.tps);
    const nSpikes = R.lagSpikes.length;
    const worstSpike = nSpikes ? R.lagSpikes[0].ms : null;
    // Same formatters and thresholds as the «Обзор» widgets.
    const chips = el('div', { class: 'stat-chips' }, [
      chip('TPS в среднем', fmtNum(tpsAvg, 2), { status: statusHigh(tpsAvg, 19.5, 17), sub: 'норма 20' }),
      chip('TPS минимум', fmtNum(tpsMin, 1), { status: statusHigh(tpsMin, 19.5, 17) }),
      chip('MSPT в среднем', fmtNum(msptAvg, 1), { unit: msptAvg === null ? '' : 'мс', status: statusLow(msptAvg, 40, 50), sub: 'предел 50 мс' }),
      chip('Самый долгий тик', fmtNum(msptMax, 0), { unit: msptMax === null ? '' : 'мс', status: statusLow(msptMax, 50, 100) }),
      chip('Лаг-спайков', fmtNum(nSpikes), { status: nSpikes === 0 ? 'good' : statusLow(worstSpike, 100, 150) === 'bad' || nSpikes > 10 ? 'bad' : 'warn', sub: nSpikes ? 'тиков дольше 50 мс' : 'тиков > 50 мс не было' }),
    ]);
    // The axis follows the data (a dip from 20 to 17,6 must be visible), capped just above 20.
    const tpsLow = minOf(R.tps);
    const tpsMax = Math.max(20.5, Math.ceil((maxOf(R.tps) || 20) * 2) / 2);
    const tpsCard = card({ title: 'TPS', desc: 'Тиков в секунду, норма — 20. Ось начинается не с нуля, чтобы были видны просадки.' }, [
      legend([
        { name: 'TPS', color: 'var(--s1)', line: true },
        { name: '19,5 — граница нормы', dashed: true },
      ]),
      tsChart(R, () => ({
        height: 220,
        series: [{ name: 'TPS', values: R.tps, color: 'var(--s1)' }],
        fixedMax: tpsMax,
        yMin: tpsLow === null ? 0 : Math.max(0, Math.min(18, Math.floor(tpsLow) - 1)),
        refs: [{ y: 19.5, label: '19,5' }],
        yFmt: (v) => fmtNum(v, v % 1 ? 1 : 0),
        valFmt: (v) => fmtNum(v, 2),
        ariaLabel: 'TPS по времени',
      })),
    ]);
    const msptCard = card({ title: 'MSPT', desc: 'Длительность тика, мс. Дольше 50 мс — сервер не успевает.' }, [
      legend([
        { name: 'Средний тик', color: 'var(--s1)', line: true },
        { name: 'Самый долгий тик', color: 'var(--s2)', line: true },
        { name: '50 мс', dashed: true, color: 'var(--ref-strong)' },
      ]),
      tsChart(R, () => ({
        height: 220,
        series: [
          { name: 'Средний тик', values: R.msptAvg, color: 'var(--s1)', area: true },
          { name: 'Самый долгий тик', values: R.msptMax, color: 'var(--s2)', width: 1.5 },
        ],
        refs: [{ y: 50, label: '50 мс', strong: true, include: true }],
        yFmt: (v) => fmtNum(v, 0),
        valFmt: fmtMs,
        ariaLabel: 'MSPT по времени',
      })),
    ]);

    const main = R.group('main');
    const mainCard = (!main || !main.series)
      ? card({ title: 'Главный поток' }, el('p', { class: 'empty', text: 'В отчёте нет данных о главном потоке (Server thread).' }))
      : card({ title: 'Главный поток', desc: 'Все тики мира (мобы, редстоун, блоки, игроки) считаются в одном потоке «Server thread». Он не может занять больше одного ядра.' }, [
        legend([{ name: 'Главный поток', color: 'var(--s1)', line: true }, { name: 'Предел: 1 ядро', dashed: true, color: 'var(--ref-strong)' }]),
        tsChart(R, () => ({
          height: 220,
          series: [{ name: 'Главный поток', values: main.series, color: 'var(--s1)', area: true }],
          refs: [{ y: 1, label: 'предел: 1 ядро', strong: true, include: true }],
          minMax: 1.1,
          yFmt: (v) => fmtNum(v, 2),
          valFmt: fmtCores,
          ariaLabel: 'Загрузка главного потока',
        })),
        el('p', { class: 'chart-note', text: 'Среднее ' + fmtCores(main.avgCores) + ', пик ' + fmtCores(main.peakCores) + '. Если линия прижата к пределу, главный поток не успевает и сервер начинает лагать.' }),
      ]);

    const spikes = R.lagSpikes;
    const maxMs = spikes.length ? spikes[0].ms : 1;
    let spikesBody;
    if (!spikes.length) spikesBody = el('p', { class: 'empty good-empty' }, [icon('good'), 'Тиков дольше 50 мс не было.']);
    else {
      const columns = [
        { label: '№', cls: 'num', render: (s) => String(s._k) },
        { label: 'Когда', cls: 'mono', render: (s) => timeLabel(R, s.t) },
        { label: 'Длительность', cls: 'num', render: (s) => el('span', { class: 'ms-val ' + (s.ms >= 100 ? 'bad' : 'warn'), text: fmtMs(s.ms) }) },
        { label: 'От самого долгого', cls: 'meter-cell', render: (s) => spikeMeter(s.ms / maxMs, s.ms) },
        { label: 'GC в этот интервал', cls: 'num opt', render: (s) => (R.n ? fmtMs(R.gcTimeMs[sampleIndexAt(R, s.t)]) : '—') },
      ];
      const rows = spikes.map((s, k) => ({ t: s.t, ms: s.ms, _k: k + 1 }));
      const t = table(columns, rows.slice(0, SPIKES_FIRST));
      spikesBody = [t.wrap];
      if (rows.length > SPIKES_FIRST) {
        const foot = el('div', { class: 'card-foot' });
        let shown = SPIKES_FIRST;
        const btn = moreButton('Показать все ' + rows.length, () => {
          shown = rows.length;
          fillBody(t.tbody, columns, rows.slice(0, shown));
          foot.remove();
        });
        foot.appendChild(btn);
        spikesBody.push(foot);
      }
    }
    const spikesCard = card({ id: 'spikes', title: 'Лаг-спайки', desc: spikes.length ? 'Тики дольше 50 мс, самые долгие сверху (' + spikes.length + '). Жёлтым — до 100 мс, красным — от 100 мс.' : null }, spikesBody);
    return [chips, el('div', { class: 'grid-2' }, [tpsCard, msptCard]), mainCard, spikesCard];
  }

  function spikeMeter(frac, ms) {
    const m = el('div', { class: 'meter' });
    const f = el('span');
    f.style.width = clamp(frac * 100, 0, 100).toFixed(1) + '%';
    // Every spike is over 50 ms: amber up to 100 ms, red from 100 ms. Never the accent.
    f.style.background = ms >= 100 ? 'var(--critical)' : 'var(--warning)';
    m.appendChild(f);
    return m;
  }

  // ---------------------------------------------------------------------
  // Tab: Память
  // ---------------------------------------------------------------------

  function tabMemory(R) {
    const heapMax = R.system.heapMaxBytes;
    const gcTotal = sum(R.gcTimeMs), runs = sum(R.gcCount);
    const heapPeak = maxOf(R.heapUsed);
    const heapFrac = heapMax && heapPeak !== null ? heapPeak / heapMax : null;
    const gcKnown = R.gcTimeMs.some((v) => v !== null);
    const gcShare = gcKnown && R.durationMs > 0 ? gcTotal / R.durationMs : null;
    const chips = el('div', { class: 'stat-chips' }, [
      chip(['Максимум кучи ', el('span', { class: 'nt', text: '(-Xmx)' })], fmtBytes(heapMax)),
      chip('Занято в пике', fmtBytes(heapPeak), { status: statusLow(heapFrac, 0.75, 0.9), sub: heapFrac !== null ? fmtPct(heapFrac) + ' от максимума' : null }),
      chip('Занято в среднем', fmtBytes(mean(R.heapUsed))),
      chip('Сборок мусора', fmtNum(gcKnown || R.gcCount.some((v) => v !== null) ? runs : null)),
      chip('Время GC', fmtMs(gcKnown ? gcTotal : null), { status: statusLow(gcShare, 0.03, 0.1), sub: gcShare !== null ? fmtPct(gcShare, 1) + ' времени' : null }),
      chip('Средняя пауза', runs > 0 ? fmtMs(gcTotal / runs) : '—'),
    ]);
    const unit = Math.max(heapMax || 0, maxOf(R.heapUsed) || 0) >= 2 * 1024 ** 3 ? 1024 ** 3 : 1024 ** 2;
    const unitName = unit === 1024 ** 3 ? 'ГБ' : 'МБ';
    const heapCard = card({ title: 'Память (куча)', desc: 'Занятая куча после каждого интервала и максимум (-Xmx).' }, [
      legend([{ name: 'Занято', color: 'var(--s1)', line: true }, { name: 'Максимум', dashed: true }]),
      tsChart(R, () => ({
        height: 240,
        series: [{ name: 'Занято', values: R.heapUsed, color: 'var(--s1)', area: true }],
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
    const gcCard = card({ title: 'Время сборки мусора', desc: 'Паузы сборщика мусора за каждый интервал, мс.' }, [
      tsChart(R, () => ({
        height: 200,
        series: [{ name: 'Время GC', values: R.gcTimeMs, color: 'var(--s4)', kind: 'bar' }],
        minMax: 10,
        yFmt: (v) => fmtNum(v, 0),
        tipRows: (i) => [
          { color: 'var(--s4)', name: 'Время GC', value: fmtMs(R.gcTimeMs[i]) },
          { name: 'Сборок', value: R.gcCount[i] === null ? '—' : fmtNum(R.gcCount[i]) },
        ],
        ariaLabel: 'Время сборки мусора по интервалам',
      })),
      el('p', { class: 'chart-note', text: 'Всего ' + fmtMs(gcTotal) + ' за ' + fmtDuration(R.durationMs) + ' (' + fmtPct(R.durationMs > 0 ? gcTotal / R.durationMs : 0, 1) + ' времени).' }),
    ]);
    const cntCard = card({ title: 'Число сборок', desc: 'Сколько раз сборщик мусора запускался за интервал (все сборщики).' }, [
      tsChart(R, () => ({
        height: 200,
        series: [{ name: 'Сборок', values: R.gcCount, color: 'var(--s7)', kind: 'bar' }],
        minMax: 2,
        yFmt: (v) => fmtNum(v, v % 1 ? 1 : 0),
        valFmt: (v) => fmtNum(v),
        ariaLabel: 'Число сборок мусора по интервалам',
      })),
      el('p', { class: 'chart-note', text: 'Всего ' + fmtNum(runs) + ' ' + plural(runs, ['сборка', 'сборки', 'сборок']) + '.' }),
    ]);
    return [chips, heapCard, el('div', { class: 'grid-2' }, [gcCard, cntCard])];
  }

  // ---------------------------------------------------------------------
  // Tab: Сервер
  // ---------------------------------------------------------------------

  function envName(w) {
    return (Object.prototype.hasOwnProperty.call(ENV_NAMES, w.environment) ? ENV_NAMES[w.environment] : w.environment) || '—';
  }

  function tabServer(R) {
    const s = R.server, sys = R.system;
    const players = s.onlinePlayers === null ? '—' : fmtNum(s.onlinePlayers) + (s.maxPlayers !== null ? ' из ' + fmtNum(s.maxPlayers) : '');
    const sysCard = card({ title: 'Система' }, kv([
      ['ОС', sys.os],
      ['Архитектура', sys.arch, true],
      ['Процессор', sys.cpuModel || 'неизвестно'],
      ['Логических ядер', fmtNum(sys.logicalCores)],
      ['ОЗУ машины', sys.totalMemoryBytes ? fmtBytes(sys.totalMemoryBytes) : 'неизвестно'],
      ['Куча (-Xmx)', fmtBytes(sys.heapMaxBytes)],
      ['Java', [s.javaVersion, s.javaVendor].filter(Boolean).join(', ')],
      ['Нагрузка по ядрам', sys.perCore ? 'есть (/proc/stat)' : 'нет'],
      ['Источник по потокам', sys.perThreadSource === 'proc' ? '/proc (все потоки)' : sys.perThreadSource === 'jmx' ? 'JMX (только Java-потоки)' : sys.perThreadSource],
    ]));
    const srvCard = card({ title: 'Сервер' }, kv([
      ['Платформа', s.brand],
      ['Сборка', s.version, true],
      ['Minecraft', s.minecraftVersion, true],
      ['Игроки', players],
      ['Начало замера', R.start ? fmtDateTime(R.start) : 'неизвестно'],
      ['Длительность', fmtDuration(R.durationMs) + ' · ' + R.n + ' ' + plural(R.n, ['замер', 'замера', 'замеров'])],
      ['Интервал', fmtMs(R.interval)],
      ['Пул чанков', s.pools.chunkWorkers === null ? '—' : fmtNum(s.pools.chunkWorkers) + ' ' + plural(s.pools.chunkWorkers, ['поток', 'потока', 'потоков'])],
      ['Пул диска чанков', s.pools.chunkIo === null ? '—' : fmtNum(s.pools.chunkIo) + ' ' + plural(s.pools.chunkIo, ['поток', 'потока', 'потоков'])],
      ['Сеть (Netty)', s.pools.netty === null ? '—' : fmtNum(s.pools.netty) + ' ' + plural(s.pools.netty, ['поток', 'потока', 'потоков'])],
    ]));
    const args = s.jvmArgs;
    const jvmCard = card({ id: 'jvm', cls: 'jvm-card' }, el('details', { class: 'jvm' }, [
      el('summary', null, [icon('chevron', 'chev'), 'Аргументы JVM', el('span', { class: 'count-pill', text: String(args.length) })]),
      args.length ? el('ul', { class: 'jvm-list' }, args.map((a) => el('li', { text: a }))) : el('p', { class: 'empty', text: 'Аргументы не переданы.' }),
    ]));

    const worlds = s.worlds;
    const wCard = card({ id: 'worlds', title: 'Миры', desc: 'Состояние на конец замера.' }, [
      worlds.length ? table([
        { label: 'Мир', cls: 'name nowrap', render: (w) => [el('span', { class: 'mono', text: w.name }), el('span', { class: 'td-sub', text: envName(w) })] },
        { label: 'Тип', cls: 'wrap opt', render: (w) => el('span', { class: 'env env-' + (ENV_NAMES[w.environment] ? w.environment.toLowerCase() : 'custom'), text: envName(w) }) },
        { label: 'Чанки', cls: 'num', render: (w) => fmtNum(w.loadedChunks) },
        { label: 'Сущности', cls: 'num', render: (w) => fmtNum(w.entities) },
        { label: 'Блок-сущности', cls: 'num', render: (w) => fmtNum(w.tileEntities) },
        { label: 'Игроки', cls: 'num', render: (w) => fmtNum(w.players) },
      ], worlds).wrap : el('p', { class: 'empty', text: 'Список миров не передан.' }),
    ]);
    const plugins = s.plugins.slice().sort((a, b) => a.name.localeCompare(b.name, 'ru'));
    const enabled = plugins.filter((p) => p.enabled).length;
    const pCard = card({ id: 'plugins', title: 'Плагины', desc: plugins.length ? plugins.length + ' ' + plural(plugins.length, ['плагин', 'плагина', 'плагинов']) + ', включено ' + enabled + '.' : null }, [
      plugins.length ? table([
        { label: 'Плагин', cls: 'name', render: (p) => p.name },
        { label: 'Версия', cls: 'wrap mono', render: (p) => p.version || '—' },
        { label: 'Статус', render: (p) => el('span', { class: 'pill ' + (p.enabled ? 'pill-on' : 'pill-off'), text: p.enabled ? 'включён' : 'выключен' }) },
      ], plugins).wrap : el('p', { class: 'empty', text: 'Плагинов нет или список не передан.' }),
    ]);
    return [el('div', { class: 'grid-2' }, [srvCard, sysCard]), jvmCard, wCard, pCard];
  }

  // ---------------------------------------------------------------------
  // Shell: top bar, tabs, routing
  // ---------------------------------------------------------------------

  const TABS = [
    { id: 'overview', label: 'Обзор', render: tabOverview },
    { id: 'cores', label: 'Ядра', render: tabCores },
    { id: 'threads', label: 'Потоки', render: tabThreads },
    { id: 'ticks', label: 'Тики', render: tabTicks },
    { id: 'memory', label: 'Память', render: tabMemory },
    { id: 'server', label: 'Сервер', render: tabServer },
  ];
  // Anchors of the old single-page viewer, so old links still land somewhere sensible.
  const OLD_ANCHORS = { findings: 'overview', groups: 'threads', main: 'ticks', spikes: 'ticks', worlds: 'server', plugins: 'server', jvm: 'server' };

  /** The loaded report: {key, R, raw, rawText, fileName, shareUrl, findings, tab, panel}. */
  let current = null;

  function tabFromHash() {
    const h = (location.hash || '').slice(1);
    if (TABS.some((t) => t.id === h)) return h;
    if (Object.prototype.hasOwnProperty.call(OLD_ANCHORS, h)) return OLD_ANCHORS[h];
    return null;
  }

  function resetView() {
    charts = [];
    hideTip();
    app.textContent = '';
    window.scrollTo(0, 0);
  }

  function clearShell() {
    metaBar.textContent = '';
    metaBar.hidden = true;
    actionsBar.textContent = '';
    actionsBar.hidden = true;
    tabsBar.hidden = true;
    tabsBar.firstElementChild.textContent = '';
    document.body.classList.remove('has-report');
    syncHeadHeight();
  }

  function showToast(text) {
    toast.textContent = '';
    toast.appendChild(icon('check'));
    toast.appendChild(document.createTextNode(text));
    toast.hidden = false;
    clearTimeout(showToast.timer);
    showToast.timer = setTimeout(() => { toast.hidden = true; }, 2200);
  }

  async function copyText(text) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch (e) { /* fall back below */ }
    const ta = el('textarea', { class: 'offscreen', readonly: true, 'aria-hidden': 'true' });
    ta.value = text;
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    ta.remove();
    return ok;
  }

  function metaItem(iconName, text, cls) {
    return el('span', { class: 'meta-item' + (cls ? ' ' + cls : '') }, [icon(iconName), el('span', { text: text })]);
  }

  function buildShell(R) {
    const s = R.server;
    metaBar.textContent = '';
    const title = (s.brand + ' ' + s.version).trim();
    metaBar.appendChild(metaItem('server', title + (s.minecraftVersion && s.version.indexOf(s.minecraftVersion) < 0 ? ' · ' + s.minecraftVersion : ''), 'meta-server'));
    if (R.start) {
      // Full date on wide screens, compact "11.11 16:30" on phones.
      const pad2 = (v) => String(v).padStart(2, '0');
      const d = R.start;
      const short = pad2(d.getDate()) + '.' + pad2(d.getMonth() + 1) + ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
      metaBar.appendChild(el('span', { class: 'meta-item', title: fmtDateTime(d) }, [
        icon('calendar'),
        el('span', { class: 'when-long', text: d.toLocaleString('ru-RU', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' }) }),
        el('span', { class: 'when-short', text: short }),
      ]));
    }
    metaBar.appendChild(metaItem('clock', fmtDuration(R.durationMs)));
    if (s.onlinePlayers !== null) metaBar.appendChild(metaItem('users', fmtNum(s.onlinePlayers) + (s.maxPlayers !== null ? '/' + fmtNum(s.maxPlayers) : '')));
    metaBar.hidden = false;

    actionsBar.textContent = '';
    if (current.shareUrl) {
      const copy = el('button', { class: 'btn btn-small', type: 'button', title: 'Скопировать ссылку на отчёт', 'aria-label': 'Скопировать ссылку' }, [icon('link'), el('span', { class: 'btn-text', text: 'Ссылка' })]);
      copy.addEventListener('click', async () => {
        const url = current.shareUrl + '#' + current.tab;
        if (await copyText(url)) showToast('Ссылка скопирована');
        else window.prompt('Скопируйте ссылку:', url);
      });
      actionsBar.appendChild(copy);
    }
    if (current.rawText) {
      const dl = el('button', { class: 'btn btn-small btn-primary', type: 'button', 'aria-label': 'Скачать JSON' }, [icon('download'), el('span', { class: 'btn-text', text: 'Скачать JSON' })]);
      dl.addEventListener('click', () => {
        const url = URL.createObjectURL(new Blob([current.rawText], { type: 'application/json' }));
        const a = el('a', { href: url, download: current.fileName || 'amazake-profile.json' });
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      });
      actionsBar.appendChild(dl);
    }
    actionsBar.hidden = !actionsBar.childNodes.length;

    const inner = tabsBar.firstElementChild;
    inner.textContent = '';
    for (const t of TABS) {
      const b = el('button', { class: 'tab', type: 'button', role: 'tab', id: 'tab-' + t.id, 'aria-controls': 'panel', 'aria-selected': 'false', tabindex: '-1', text: t.label });
      b.addEventListener('click', () => setTab(t.id, true));
      b.addEventListener('keydown', (e) => {
        const i = TABS.indexOf(t);
        let j = -1;
        if (e.key === 'ArrowRight') j = (i + 1) % TABS.length;
        else if (e.key === 'ArrowLeft') j = (i - 1 + TABS.length) % TABS.length;
        else if (e.key === 'Home') j = 0;
        else if (e.key === 'End') j = TABS.length - 1;
        if (j < 0) return;
        e.preventDefault();
        setTab(TABS[j].id, true);
        document.getElementById('tab-' + TABS[j].id).focus();
      });
      inner.appendChild(b);
    }
    tabsBar.hidden = false;
    document.body.classList.add('has-report');
    syncHeadHeight();
    updateFades();
  }

  function setTab(id, updateUrl) {
    if (!current) return;
    if (!TABS.some((t) => t.id === id)) id = 'overview';
    const same = current.tab === id && current.panel.childNodes.length;
    current.tab = id;
    if (updateUrl && location.hash !== '#' + id) {
      history.replaceState(null, '', location.pathname + location.search + '#' + id);
    }
    for (const t of TABS) {
      const b = document.getElementById('tab-' + t.id);
      if (!b) continue;
      const on = t.id === id;
      b.setAttribute('aria-selected', String(on));
      b.tabIndex = on ? 0 : -1;
      if (on && b.scrollIntoView && tabsBar.firstElementChild.scrollWidth > tabsBar.firstElementChild.clientWidth) {
        b.scrollIntoView({ block: 'nearest', inline: 'nearest' });
      }
    }
    if (same) return;
    const tab = TABS.find((t) => t.id === id);
    charts = [];
    hideTip();
    const panel = current.panel;
    panel.setAttribute('aria-labelledby', 'tab-' + id);
    panel.textContent = '';
    for (const node of safeSection(tab.render, current.R)) panel.appendChild(node);
    drawAllCharts(true);
    if (window.scrollY > 0) window.scrollTo(0, 0);
  }

  function renderReport(raw, rawText, fileName, key, shareUrl) {
    resetView();
    clearShell();
    current = null;
    if (!isObj(raw)) {
      showError('Это не отчёт профайлера', 'Файл не содержит JSON-объект отчёта Amazake.');
      return;
    }
    const looksLikeReport = raw.format === 'amazake-profile' || Array.isArray(raw.samples) || isObj(raw.server);
    if (!looksLikeReport) {
      showError('Это не отчёт профайлера', 'В JSON нет полей отчёта Amazake (format "amazake-profile"). Возможно, выбран не тот файл.');
      return;
    }
    const R = normalize(raw);
    let findings;
    try { findings = computeFindings(R); } catch (e) {
      if (window.console && console.warn) console.warn('amazake viewer: findings failed', e);
      findings = [{ severity: 'info', title: 'Не удалось рассчитать выводы', text: 'В отчёте не хватает данных для автоматических подсказок. Графики на вкладках доступны.' }];
    }
    current = { key: key, R: R, raw: raw, rawText: rawText, fileName: fileName, shareUrl: shareUrl, findings: findings, tab: null, panel: null };
    document.title = (R.server.brand + ' ' + R.server.version).trim() + ' — Amazake profiler';
    buildShell(R);

    if (raw.format !== 'amazake-profile') {
      app.appendChild(noticeEl('warn', 'Формат не распознан', 'Поле format не равно "amazake-profile". Показываем то, что удалось прочитать.'));
    } else if (raw.version !== 1) {
      app.appendChild(noticeEl('warn', 'Другая версия формата', 'Отчёт версии ' + str(raw.version, '?') + ', а просмотрщик знает версию 1. Часть данных может отображаться неверно.'));
    }
    current.panel = el('div', { id: 'panel', class: 'panel', role: 'tabpanel', tabindex: '-1' });
    app.appendChild(current.panel);
    app.appendChild(el('p', { class: 'footer-note', text: 'Отчёт обработан в вашем браузере. Формат amazake-profile, версия ' + str(raw.version, '?') + '.' }));
    setTab(tabFromHash() || 'overview', true);
  }

  function showError(title, text, opts) {
    resetView();
    clearShell();
    current = null;
    document.title = 'Amazake profiler';
    const actions = el('div', { class: 'error-actions' });
    // Retry is the expected action when there is one; otherwise going home is.
    const canRetry = !!(opts && opts.retry);
    if (canRetry) {
      const retry = el('button', { class: 'btn btn-primary', type: 'button', text: 'Повторить' });
      retry.addEventListener('click', opts.retry);
      actions.appendChild(retry);
    }
    actions.appendChild(el('a', { class: 'btn' + (canRetry ? '' : ' btn-primary'), href: '/' }, [icon('back'), 'На главную']));
    app.appendChild(el('div', { class: 'center-card error-card', role: 'alert' }, [
      el('div', { class: 'error-icon' }, icon(opts && opts.kind === 'notfound' ? 'search' : 'warning')),
      el('h1', { text: title }),
      el('p', { class: 'lead', text: text }),
      actions,
    ]));
  }

  function showLoading(text) {
    resetView();
    clearShell();
    current = null;
    const sk = el('div', { class: 'skeleton', 'aria-busy': 'true', 'aria-live': 'polite' }, [
      el('p', { class: 'loading-text' }, [el('span', { class: 'spinner', 'aria-hidden': 'true' }), text]),
    ]);
    const grid = el('div', { class: 'widgets' });
    for (let i = 0; i < 8; i++) grid.appendChild(el('div', { class: 'widget sk-block' }));
    sk.appendChild(grid);
    sk.appendChild(el('div', { class: 'card sk-block sk-tall' }));
    app.appendChild(sk);
  }

  function showStart(message) {
    resetView();
    clearShell();
    current = null;
    document.title = 'Amazake profiler';

    const status = el('div', { class: 'start-status', 'aria-live': 'polite' });
    if (message) status.appendChild(noticeEl('error', message.title, message.text));

    const fileInput = el('input', { type: 'file', accept: '.json,.gz,application/json', hidden: true });
    const pick = el('button', { class: 'btn btn-primary', type: 'button' }, [icon('file'), 'Выбрать файл']);
    const zone = el('div', { class: 'dropzone', tabindex: '0', role: 'button', 'aria-label': 'Открыть файл отчёта' }, [
      el('div', { class: 'dz-icon' }, icon('upload')),
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

    const linkInput = el('input', { class: 'input', type: 'text', placeholder: 'Ссылка или id отчёта', 'aria-label': 'Ссылка или id отчёта', autocomplete: 'off', spellcheck: 'false' });
    const go = el('button', { class: 'btn', type: 'submit' }, ['Открыть', icon('arrow')]);
    const form = el('form', { class: 'start-row' }, [el('label', { class: 'search link-field' }, [icon('link'), linkInput]), go]);
    const formHint = el('p', { class: 'field-hint' }, ['Например, ', el('span', { class: 'mono', text: 'https://…/r/aB3xY9kQ2m' }), ' или просто ', el('span', { class: 'mono', text: 'aB3xY9kQ2m' })]);
    form.addEventListener('submit', (e) => {
      e.preventDefault();
      const id = parseReportRef(linkInput.value);
      status.textContent = '';
      if (!id) {
        status.appendChild(noticeEl('error', 'Не понимаю ссылку', 'Вставьте ссылку вида https://сайт/r/aB3xY9kQ2m или только id отчёта (латинские буквы и цифры).'));
        return;
      }
      // A copied link carries the tab (…/r/id#memory): keep it.
      const hm = /#([A-Za-z]+)\s*$/.exec(linkInput.value);
      const h = hm && (TABS.some((t) => t.id === hm[1]) || Object.prototype.hasOwnProperty.call(OLD_ANCHORS, hm[1])) ? '#' + hm[1] : '';
      location.assign('/r/' + id + h);
    });

    const sample = el('button', { class: 'btn btn-ghost', type: 'button' }, ['Открыть пример', icon('arrow')]);
    sample.addEventListener('click', () => {
      history.pushState(null, '', '/?sample');
      loadSample();
    });

    const steps = el('ol', { class: 'steps' }, [
      el('li', null, [el('span', { class: 'step-n', text: '1' }), el('div', null, [el('b', null, el('code', { text: '/amazake profile' })), el('span', { text: 'на сервере — замер нагрузки за 30–60 секунд' })])]),
      el('li', null, [el('span', { class: 'step-n', text: '2' }), el('div', null, [el('b', { text: 'Ссылка на отчёт' }), el('span', { text: 'сервер пришлёт её в чат или консоль' })])]),
      el('li', null, [el('span', { class: 'step-n', text: '3' }), el('div', null, [el('b', { text: 'Разбор здесь' }), el('span', { text: 'TPS, ядра, потоки, память и подсказки' })])]),
    ]);

    app.appendChild(el('div', { class: 'start' }, [
      el('div', { class: 'hero' }, [
        el('img', { class: 'hero-logo', src: '/favicon.svg?v=41b24776', alt: '', width: '56', height: '56' }),
        el('h1', null, ['Amazake ', el('span', { class: 'accent-text', text: 'profiler' })]),
        el('p', { class: 'lead', text: 'Показывает, чем занят процессор сервера: главный поток, чанки, сеть, сборщик мусора, плагины — и что с этим делать.' }),
      ]),
      steps,
      el('div', { class: 'center-card' }, [
        status,
        zone,
        el('div', { class: 'start-sep', text: 'или откройте по ссылке' }),
        form,
        formHint,
        el('div', { class: 'start-sample' }, [el('span', { class: 'muted', text: 'Нет отчёта под рукой?' }), sample]),
      ]),
      el('p', { class: 'start-help' }, [
        'Если загрузка на сайт выключена, файл отчёта лежит в папке сервера — его можно открыть здесь. Отчёты на сайте хранятся 30 дней.',
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
    history.pushState(null, '', '/?file');
    renderReport(raw, text, file.name, 'file', null);
  }

  async function fetchReport(url, notFoundText) {
    let res;
    try {
      res = await fetch(url, { headers: { Accept: 'application/json' }, cache: 'no-store' });
    } catch (e) {
      return { error: ['Нет связи с сайтом', 'Не удалось загрузить отчёт. Проверьте подключение к интернету и попробуйте ещё раз.'], retry: true };
    }
    if (!res.ok) {
      try { await res.text(); } catch (e) { /* only drains the error body */ }
    }
    if (res.status === 404) return { error: ['Отчёт не найден или устарел (хранится 30 дней)', notFoundText], notFound: true };
    if (!res.ok) return { error: ['Сайт ответил ошибкой ' + res.status, 'Попробуйте обновить страницу чуть позже.'], retry: true };
    let text;
    try {
      text = await res.text();
    } catch (e) {
      return { error: ['Загрузка прервалась', 'Соединение оборвалось во время загрузки отчёта.'], retry: true };
    }
    try {
      return { raw: parseJsonText(text), text: text };
    } catch (e) {
      return { error: ['Отчёт повреждён', 'Сайт вернул данные, которые не являются JSON.'] };
    }
  }

  let loadSeq = 0;

  async function loadRemote(id) {
    const seq = ++loadSeq;
    showLoading('Загружаем отчёт…');
    const r = await fetchReport('/api/reports/' + encodeURIComponent(id),
      'Отчёта с id «' + id + '» нет: ссылка неверная или срок хранения истёк. Снимите новый профиль командой /amazake profile.');
    if (seq !== loadSeq) return;
    if (r.error) { showError(r.error[0], r.error[1], { kind: r.notFound ? 'notfound' : null, retry: r.retry ? () => loadRemote(id) : null }); return; }
    renderReport(r.raw, r.text, 'amazake-' + id + '.json', 'r:' + id, location.origin + '/r/' + id);
  }

  async function loadSample() {
    const seq = ++loadSeq;
    showLoading('Загружаем пример…');
    const r = await fetchReport('/sample-report.json', 'Файл примера не найден на сайте.');
    if (seq !== loadSeq) return;
    if (r.error) { showError(r.error[0], r.error[1], { retry: r.retry ? loadSample : null }); return; }
    renderReport(r.raw, r.text, 'amazake-sample.json', 'sample', location.origin + '/?sample');
  }

  function route() {
    hideTip();
    const path = location.pathname;
    const tab = tabFromHash();
    const m = /^\/r\/([^/]*)\/?$/.exec(path);
    let key = null;
    if (m) {
      let id = '';
      try { id = decodeURIComponent(m[1]); } catch (e) { id = ''; }
      if (!ID_RE.test(id)) {
        loadSeq++;
        showError('Неверная ссылка', 'Id отчёта состоит только из латинских букв и цифр. Проверьте ссылку.', { kind: 'notfound' });
        return;
      }
      key = 'r:' + id;
      if (current && current.key === key) { setTab(tab || current.tab, false); return; }
      loadRemote(id);
      return;
    }
    if (location.search === '?sample') {
      if (current && current.key === 'sample') { setTab(tab || current.tab, false); return; }
      loadSample();
      return;
    }
    if (location.search === '?file' && current && current.key === 'file') { setTab(tab || current.tab, false); return; }
    loadSeq++;
    showStart();
  }

  // Theme: dark for everyone by default; light only when the viewer picks it.
  const THEME_KEY = 'amazake-theme';
  const themeBtn = document.getElementById('theme-toggle');
  function storedTheme() {
    try { return localStorage.getItem(THEME_KEY) === 'light' ? 'light' : 'dark'; } catch (e) { return 'dark'; }
  }
  function applyTheme(theme) {
    if (theme === 'light') document.documentElement.setAttribute('data-theme', 'light');
    else document.documentElement.removeAttribute('data-theme');
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', theme === 'light' ? '#ffffff' : '#0d1117');
    const cs = document.querySelector('meta[name="color-scheme"]');
    if (cs) cs.setAttribute('content', theme);
    if (themeBtn) {
      const label = theme === 'light' ? 'Тёмная тема' : 'Светлая тема';
      themeBtn.textContent = '';
      themeBtn.appendChild(icon(theme === 'light' ? 'moon' : 'sun'));
      themeBtn.setAttribute('aria-label', label);
      themeBtn.title = label;
    }
  }
  applyTheme(storedTheme());
  if (themeBtn) {
    themeBtn.addEventListener('click', () => {
      const next = document.documentElement.getAttribute('data-theme') === 'light' ? 'dark' : 'light';
      try { localStorage.setItem(THEME_KEY, next); } catch (e) { /* not saved, still applied */ }
      applyTheme(next);
      hideTip();
    });
  }

  // Edge fades on horizontal scrollers (tab strip, meta chips) so it is visible they scroll.
  const fadeEls = [tabsBar.firstElementChild, metaBar];
  function updateFades() {
    for (const e of fadeEls) {
      const max = e.scrollWidth - e.clientWidth;
      e.classList.toggle('fade-l', max > 1 && e.scrollLeft > 1);
      e.classList.toggle('fade-r', max > 1 && e.scrollLeft < max - 1);
    }
  }
  for (const e of fadeEls) e.addEventListener('scroll', updateFades, { passive: true });
  window.addEventListener('resize', updateFades);

  // Header height drives the sticky offsets below it (side card on «Обзор»).
  const shellHead = document.querySelector('.shell-head');
  function syncHeadHeight() {
    if (shellHead) document.documentElement.style.setProperty('--head-h', shellHead.offsetHeight + 'px');
  }
  window.addEventListener('resize', syncHeadHeight);

  window.addEventListener('popstate', route);
  window.addEventListener('hashchange', () => {
    const tab = tabFromHash();
    if (current && tab && tab !== current.tab) setTab(tab, false);
  });
  route();
  syncHeadHeight();
})();
