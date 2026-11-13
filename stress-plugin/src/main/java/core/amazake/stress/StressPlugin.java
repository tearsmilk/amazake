package core.amazake.stress;

import core.amazake.stress.profiler.StressProfiler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/** AmazakeStress entry point. Owns the module registry, the test world, the profiler and the active run. */
public final class StressPlugin extends JavaPlugin {

    private ModuleRegistry modules;
    private TestWorld testWorld;
    private TickMonitor tickMonitor;
    private ProfilerBridge profiler;
    private EntityTickBridge entityTicks;
    private @Nullable StageRunner run;
    private @Nullable StageRunner lastRun;
    private @Nullable Path lastResult;
    private boolean preparing;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.modules = ModuleRegistry.createDefault();
        this.testWorld = new TestWorld(this);
        this.tickMonitor = new TickMonitor();
        this.getServer().getPluginManager().registerEvents(this.tickMonitor, this);
        this.entityTicks = EntityTickBridge.load(this.getLogger());
        this.profiler = new StressProfiler(this);
        final StressCommand command = new StressCommand(this);
        final PluginCommand pluginCommand = this.getCommand("stress");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            this.getLogger().severe("Command 'stress' is missing from plugin.yml");
        }
        this.getLogger().info("AmazakeStress ready: modules " + this.modules.ids() + ", test world " + TestWorld.NAME);
    }

    @Override
    public void onDisable() {
        this.stopRun("плагин выключается");
        if (this.profiler != null) {
            try {
                this.profiler.shutdown();
            } catch (final Throwable t) {
                this.getLogger().log(Level.WARNING, "Profiler shutdown failed", t);
            }
        }
        this.getServer().getScheduler().cancelTasks(this);
    }

    // ---------------------------------------------------------------- accessors

    /** Re-reads config.yml from disk and returns a fresh snapshot. */
    public StressConfig loadStressConfig() {
        this.reloadConfig();
        return new StressConfig(this.getConfig());
    }

    public ModuleRegistry modules() {
        return this.modules;
    }

    public TestWorld testWorld() {
        return this.testWorld;
    }

    public TickMonitor tickMonitor() {
        return this.tickMonitor;
    }

    public ProfilerBridge profiler() {
        return this.profiler;
    }

    /** Amazake's entity tick counters (unavailable, all no-ops, on other servers). */
    public EntityTickBridge entityTicks() {
        return this.entityTicks;
    }

    /** Replaces the profiler implementation (e.g. from another plugin or a test). */
    public void setProfiler(ProfilerBridge profiler) {
        this.profiler = profiler;
    }

    /** The active run, or null. */
    public @Nullable StageRunner currentRun() {
        return this.run;
    }

    public @Nullable StageRunner lastRun() {
        return this.lastRun;
    }

    public @Nullable Path lastResult() {
        return this.lastResult;
    }

    /** True while stress_world is being prepared for a run that was requested. */
    public boolean isPreparing() {
        return this.preparing;
    }

    // ---------------------------------------------------------------- run control

    /**
     * Prepares stress_world and starts a run.
     *
     * @param mode {@code "all"} or a module id
     * @param profile true to run the built-in profiler for exactly the length of the run (as {@code /stress run});
     *                false to follow {@code profiler.auto-start-with-run}
     */
    public void startRun(CommandSender sender, String mode, int seconds, boolean profile) {
        if (this.run != null || this.preparing) {
            Msg.error(sender, "Прогон уже идёт. Сначала /stress stop");
            return;
        }
        final StressConfig config;
        try {
            config = this.loadStressConfig();
        } catch (final Throwable t) {
            this.getLogger().log(Level.SEVERE, "Bad config.yml", t);
            Msg.error(sender, "Ошибка в config.yml: " + t.getMessage());
            return;
        }
        if (!mode.equals("all") && this.modules.get(mode) == null) {
            Msg.error(sender, "Неизвестный модуль: " + mode);
            return;
        }
        final int total = seconds > 0 ? seconds : config.totalSeconds();
        this.preparing = true;
        Msg.info(sender, "Готовлю мир " + TestWorld.NAME + " (сид " + config.seed() + ")...");
        this.testWorld.prepare(config, world -> {
            this.preparing = false;
            if (!this.isEnabled()) {
                return;
            }
            final StageRunner runner = new StageRunner(this, config, world, mode, total, sender,
                profile || config.autoProfile());
            this.run = runner;
            runner.begin();
            Msg.broadcast("Прогон запущен: " + (mode.equals("all") ? "все модули" : "модуль " + mode)
                + ", " + total + " с (" + total * 20L + " тиков)" + (runner.isProfiled() ? ", с профайлером" : ""),
                NamedTextColor.GREEN, null);
        }, error -> {
            this.preparing = false;
            Msg.error(sender, "Не могу запустить: " + error);
        });
    }

    /** Stops the active run (if any). Returns false if nothing was running. */
    public boolean stopRun(String reason) {
        final StageRunner runner = this.run;
        if (runner == null) {
            return false;
        }
        runner.stop(reason, false);
        return true;
    }

    /** Called by {@link StageRunner#stop} exactly once. */
    void onRunFinished(StageRunner runner, boolean completed) {
        if (this.run == runner) {
            this.run = null;
        }
        this.lastRun = runner;
        final List<String> summary = StatusPrinter.summary(this, runner, completed, runner.endReason());
        Msg.broadcast("Прогон завершён" + (completed ? "" : " досрочно") + ": " + runner.endReason(),
            completed ? NamedTextColor.GREEN : NamedTextColor.YELLOW, null);
        for (final String line : summary) {
            this.getLogger().info(line);
        }
        if (runner.initiator() instanceof Player player && player.isOnline()) {
            for (final String line : summary) {
                Msg.plain(player, line, NamedTextColor.GRAY);
            }
        }
        if (runner.config().saveResults() && this.isEnabled()) {
            final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(runner.startedAtMillis()));
            final Path file = this.getDataFolder().toPath().resolve("results").resolve("run-" + stamp + "-" + runner.mode() + ".txt");
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, summary, StandardCharsets.UTF_8);
                this.lastResult = file;
                Msg.broadcast("Сводка сохранена: " + file, NamedTextColor.GRAY, null);
            } catch (final IOException e) {
                this.getLogger().log(Level.WARNING, "Cannot save results to " + file, e);
            }
        }
    }
}
