package core.amazake.stress.packets;

import core.amazake.stress.LoadModule;
import core.amazake.stress.Region;
import core.amazake.stress.Stage;
import core.amazake.stress.StressContext;
import core.amazake.stress.StressPlugin;
import io.papermc.paper.math.BlockPosition;
import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * Load module "packets": heavy but harmless outgoing network traffic.
 *
 * <p>Two parts:
 * <ul>
 *   <li><b>In-world</b> (always, also without players), inside this module's region of stress_world: a ceiling of
 *       real blocks toggled every tick (block update packets to everyone in view distance), armor stands whose
 *       name and glow change and which move every tick (entity metadata and movement packets), and particle
 *       bursts through {@link World#spawnParticle}.</li>
 *   <li><b>Per player</b> (only players standing in stress_world, the first {@code max-players} by name):
 *       particles, sounds, fake block changes ({@code sendBlockChange}/{@code sendMultiBlockChange}, client only),
 *       boss bars, action bar, and a private scoreboard with team and sidebar updates every tick.</li>
 * </ul>
 * With {@code teleport-players: true} every online player is moved once per run to the module's platform in
 * stress_world, so that the per-player part and the view-distance part are the same on both servers.
 * Players in other worlds are never sent anything, and no other world is touched.
 */
public final class PacketsModule implements LoadModule {

    private static final Particle[] PARTICLE_CANDIDATES = {
        Particle.FLAME, Particle.HAPPY_VILLAGER, Particle.END_ROD, Particle.CRIT, Particle.ENCHANT,
        Particle.CLOUD, Particle.COMPOSTER, Particle.WITCH, Particle.ELECTRIC_SPARK, Particle.WAX_ON
    };
    private static final Sound[] SOUNDS = {
        Sound.BLOCK_NOTE_BLOCK_HAT, Sound.BLOCK_NOTE_BLOCK_BIT, Sound.UI_BUTTON_CLICK,
        Sound.BLOCK_STONE_BUTTON_CLICK_ON, Sound.ENTITY_EXPERIENCE_ORB_PICKUP
    };
    private static final NamedTextColor[] COLORS = {
        NamedTextColor.RED, NamedTextColor.GOLD, NamedTextColor.YELLOW, NamedTextColor.GREEN,
        NamedTextColor.AQUA, NamedTextColor.BLUE, NamedTextColor.LIGHT_PURPLE, NamedTextColor.WHITE
    };
    private static final BossBar.Color[] BAR_COLORS = BossBar.Color.values();

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final StressPlugin plugin;
    private StressContext ctx;
    private World world;
    /** Layout and in-world part: never depends on how many players are online. */
    private Random random;
    /** Per-player part only, so players present or not never shift the in-world sequence. */
    private Random playerRandom;

    // settings
    private int maxPlayers;
    private boolean teleportPlayers;
    private int particlePacketsPerTick;
    private int particlesPerPacket;
    private int worldParticleBursts;
    private int worldParticlesPerBurst;
    private int soundsPerTick;
    private int fakeBlockChanges;
    private int singleBlockChanges;
    private int blockToggles;
    private int entityCount;
    private int entityUpdatesPerTick;
    private int movingEntities;
    private int bossBarCount;
    private boolean actionBar;
    private int teamCount;
    private int teamUpdatesPerTick;
    private int sidebarLines;
    private int buildPerTick;
    private int spawnPerTick;

    // layout
    private Region region;
    private Location anchor;
    private final List<int[]> boardCells = new ArrayList<>();
    private final List<int[]> fakeCells = new ArrayList<>();
    private BlockData boardA;
    private BlockData boardB;
    private BlockData[] fakeData;
    private Particle[] particles;

    // build state
    private boolean ready;
    private int floorCursor;
    private int boardBuildCursor;

    // runtime state
    private final List<ArmorStand> stands = new ArrayList<>();
    private final List<double[]> standHomes = new ArrayList<>();
    private final List<BossBar> bars = new ArrayList<>();
    private final Set<UUID> barViewers = new HashSet<>();
    private final Map<UUID, Scoreboard> previousBoards = new LinkedHashMap<>();
    private final Set<UUID> teleported = new HashSet<>();
    /** Where teleported players were before, to send them back on stop. */
    private final Map<UUID, Location> returnTo = new LinkedHashMap<>();
    private Scoreboard board;
    private Objective sidebar;
    private final List<Team> teams = new ArrayList<>();
    private int toggleCursor;
    private boolean toggleState;
    private int fakeCursor;
    private int entityCursor;
    private int teamCursor;
    private double intensity = 1.0;
    private boolean warnedNoPlayers;
    private boolean stopped;
    private int lastPlayers;

    // counters
    private long packetsSent;
    private long blockToggleCount;

    public PacketsModule(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "packets";
    }

    @Override
    public void start(StressContext ctx) {
        this.ctx = ctx;
        this.world = ctx.world();
        this.random = ctx.random();
        this.playerRandom = ctx.newRandom("players");
        final ConfigurationSection cfg = ctx.config();
        this.maxPlayers = Math.max(0, cfg.getInt("max-players", 2));
        this.teleportPlayers = cfg.getBoolean("teleport-players", true);
        this.particlePacketsPerTick = Math.max(0, cfg.getInt("particle-packets-per-tick", 200));
        this.particlesPerPacket = Math.max(1, cfg.getInt("particles-per-packet", 8));
        this.worldParticleBursts = Math.max(0, cfg.getInt("world-particle-bursts-per-tick", 20));
        this.worldParticlesPerBurst = Math.max(1, cfg.getInt("world-particles-per-burst", 50));
        this.soundsPerTick = Math.max(0, cfg.getInt("sounds-per-tick", 50));
        this.fakeBlockChanges = Math.max(0, cfg.getInt("block-changes-per-tick", 2000));
        this.singleBlockChanges = Math.max(0, cfg.getInt("single-block-changes-per-tick", 100));
        this.blockToggles = Math.max(0, cfg.getInt("world-block-toggles-per-tick", 256));
        this.entityCount = Math.max(0, cfg.getInt("moving-entities", 300));
        this.entityUpdatesPerTick = Math.max(0, cfg.getInt("entity-updates-per-tick", 150));
        this.movingEntities = Math.max(0, cfg.getInt("entity-moves-per-tick", 300));
        this.bossBarCount = Math.max(0, Math.min(16, cfg.getInt("boss-bars", 4)));
        this.actionBar = cfg.getBoolean("action-bar", true);
        this.teamCount = Math.max(0, Math.min(64, cfg.getInt("teams", 16)));
        this.teamUpdatesPerTick = Math.max(0, cfg.getInt("team-updates-per-tick", 20));
        this.sidebarLines = Math.max(0, Math.min(15, cfg.getInt("sidebar-lines", 15)));
        this.buildPerTick = Math.max(64, cfg.getInt("build-blocks-per-tick", 1024));
        this.spawnPerTick = Math.max(1, cfg.getInt("spawn-per-tick", 20));

        final List<Particle> usable = new ArrayList<>();
        for (final Particle particle : PARTICLE_CANDIDATES) {
            if (particle.getDataType() == Void.class) {
                usable.add(particle);
            }
        }
        this.particles = usable.toArray(new Particle[0]);

        // 4x4 chunks = 64x64 blocks: floor at baseY-1, players at baseY, fake blocks at baseY+3..+5, board at baseY+7.
        this.region = ctx.allocateRegion("packets", 4, 4);
        final int y0 = this.region.minY();
        this.anchor = new Location(this.world, this.region.centerX() + 0.5, y0, this.region.centerZ() + 0.5);
        for (int x = this.region.minX() + 8; x <= this.region.maxX() - 8; x++) {
            for (int z = this.region.minZ() + 8; z <= this.region.maxZ() - 8; z++) {
                this.boardCells.add(new int[]{x, y0 + 7, z});
            }
        }
        for (int y = y0 + 3; y <= y0 + 5; y++) {
            for (int x = this.region.minX() + 8; x <= this.region.maxX() - 8; x++) {
                for (int z = this.region.minZ() + 8; z <= this.region.maxZ() - 8; z++) {
                    this.fakeCells.add(new int[]{x, y, z});
                }
            }
        }
        this.boardA = ctx.blocks().data(Material.WHITE_CONCRETE);
        this.boardB = ctx.blocks().data(Material.BLACK_CONCRETE);
        this.fakeData = new BlockData[]{
            ctx.blocks().data(Material.GLASS), ctx.blocks().data(Material.RED_STAINED_GLASS),
            ctx.blocks().data(Material.LIME_STAINED_GLASS), ctx.blocks().data(Material.LIGHT_BLUE_STAINED_GLASS)
        };

        ctx.chunks().loadAndHold(this.region).thenRun(() -> {
            if (ctx.isActive() && !this.stopped) {
                this.ready = true;
            }
        });
        ctx.logger().info("[packets] platform at " + this.anchor.getBlockX() + "," + this.anchor.getBlockY() + ","
            + this.anchor.getBlockZ() + " in " + this.world.getName());
    }

    @Override
    public void onStageChange(Stage stage) {
        if (this.ctx != null) {
            this.intensity = this.ctx.intensity();
        }
    }

    private int scaled(int base) {
        return Math.max(0, (int) Math.floor(base * this.intensity));
    }

    @Override
    public void tick(long tick) {
        if (this.stopped || !this.ready) {
            return;
        }
        if (!this.buildStep()) {
            return; // still building the platform and board (block budget per tick)
        }
        this.spawnStep();
        if (this.teleportPlayers && tick % 20 == 0) {
            this.teleportNewPlayers();
        }
        final List<Player> players = this.selectPlayers();
        this.lastPlayers = players.size();
        if (players.isEmpty() && !this.warnedNoPlayers) {
            this.warnedNoPlayers = true;
            this.ctx.logger().info("[packets] no player in " + this.world.getName() + ": per-player packets need a player"
                + " (join and stand on the platform); doing the in-world part only");
        }
        this.worldPart(tick);
        if (!players.isEmpty()) {
            this.syncViewers(players);
            for (final Player player : players) {
                this.playerPart(player, tick);
            }
            this.sharedPart(tick);
        }
    }

    // ---------------------------------------------------------------- build (spread over ticks)

    /** Returns true once floor and board are complete. */
    private boolean buildStep() {
        int budget = this.buildPerTick;
        final int y0 = this.region.minY();
        final int sizeX = this.region.sizeX();
        final int floorTotal = sizeX * this.region.sizeZ();
        final BlockData floor = this.ctx.blocks().data(Material.SMOOTH_STONE);
        while (budget > 0 && this.floorCursor < floorTotal) {
            final int x = this.region.minX() + this.floorCursor % sizeX;
            final int z = this.region.minZ() + this.floorCursor / sizeX;
            this.ctx.blocks().set(x, y0 - 1, z, floor);
            this.floorCursor++;
            budget--;
        }
        while (budget > 0 && this.boardBuildCursor < this.boardCells.size()) {
            final int[] c = this.boardCells.get(this.boardBuildCursor++);
            this.ctx.blocks().set(c[0], c[1], c[2], this.boardA);
            budget--;
        }
        return this.floorCursor >= floorTotal && this.boardBuildCursor >= this.boardCells.size();
    }

    private void spawnStep() {
        final int target = this.entityCount;
        int budget = this.spawnPerTick;
        final int side = Math.max(1, (int) Math.ceil(Math.sqrt(Math.max(1, target))));
        final double spacing = Math.max(1.0, 44.0 / side);
        while (budget-- > 0 && this.stands.size() < target) {
            final int i = this.stands.size();
            final double x = this.region.minX() + 10 + (i % side) * spacing;
            final double z = this.region.minZ() + 10 + (i / side) * spacing;
            final double y = this.region.minY() + 1.0;
            final int index = i;
            final ArmorStand stand = this.ctx.spawn(new Location(this.world, x, y, z), ArmorStand.class, as -> {
                as.setGravity(false);
                as.setInvulnerable(true);
                as.setSilent(true);
                as.setPersistent(false);
                as.setMarker(true);
                as.setSmall(true);
                as.setBasePlate(false);
                as.setCustomNameVisible(true);
                as.customName(Component.text("stress #" + index, NamedTextColor.GRAY));
            });
            this.stands.add(stand);
            this.standHomes.add(new double[]{x, y, z, this.random.nextDouble() * Math.PI * 2});
        }
    }

    // ---------------------------------------------------------------- players

    private void teleportNewPlayers() {
        final List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.sort(Comparator.comparing(Player::getName));
        int slot = 0;
        for (final Player player : online) {
            if (this.teleported.contains(player.getUniqueId())) {
                continue;
            }
            this.teleported.add(player.getUniqueId());
            if (player.getWorld().equals(this.world)) {
                continue;
            }
            this.returnTo.put(player.getUniqueId(), player.getLocation());
            final Location target = this.anchor.clone().add((slot++ % 3) - 1, 0, 0);
            target.setYaw(0f);
            target.setPitch(-20f);
            player.teleport(target);
            player.sendMessage(Component.text("[AmazakeStress] Вы перенесены на площадку пакетного теста в "
                + this.world.getName() + ". Стойте здесь до конца прогона.", NamedTextColor.GOLD));
        }
    }

    /** Players in stress_world, first {@code max-players} by name (fixed order). */
    private List<Player> selectPlayers() {
        final List<Player> out = new ArrayList<>(this.world.getPlayers());
        out.sort(Comparator.comparing(Player::getName));
        return out.size() > this.maxPlayers ? new ArrayList<>(out.subList(0, this.maxPlayers)) : out;
    }

    private void syncViewers(List<Player> players) {
        if (this.board == null) {
            this.createScoreboard();
        }
        final Set<UUID> current = new HashSet<>();
        for (final Player player : players) {
            current.add(player.getUniqueId());
            if (this.barViewers.add(player.getUniqueId())) {
                for (final BossBar bar : this.bars) {
                    player.showBossBar(bar);
                }
            }
            if (!this.previousBoards.containsKey(player.getUniqueId())) {
                this.previousBoards.put(player.getUniqueId(), player.getScoreboard());
                player.setScoreboard(this.board);
            }
        }
        // Players that left the selection: hide bars, give their scoreboard back.
        for (final UUID id : new ArrayList<>(this.barViewers)) {
            if (!current.contains(id)) {
                this.barViewers.remove(id);
                this.restorePlayer(id);
            }
        }
    }

    private void createScoreboard() {
        this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        for (int i = 0; i < this.teamCount; i++) {
            final Team team = this.board.registerNewTeam("stress" + i);
            team.color(COLORS[i % COLORS.length]);
            for (int e = 0; e < 4; e++) {
                team.addEntry("st" + i + "_" + e);
            }
            this.teams.add(team);
        }
        if (this.sidebarLines > 0) {
            this.sidebar = this.board.registerNewObjective("stress", Criteria.DUMMY,
                Component.text("AmazakeStress", NamedTextColor.GOLD));
            this.sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);
        }
        for (int i = 0; i < this.bossBarCount; i++) {
            this.bars.add(BossBar.bossBar(Component.text("stress " + i), 0f, BAR_COLORS[i % BAR_COLORS.length],
                BossBar.Overlay.PROGRESS));
        }
    }

    private void restorePlayer(UUID id) {
        final Player player = Bukkit.getPlayer(id);
        final Scoreboard previous = this.previousBoards.remove(id);
        if (player == null || !player.isOnline()) {
            return;
        }
        for (final BossBar bar : this.bars) {
            player.hideBossBar(bar);
        }
        if (previous != null && player.getScoreboard() == this.board) {
            player.setScoreboard(previous);
        }
        this.restoreFakeBlocks(player);
    }

    private void playerPart(Player player, long tick) {
        final Location at = player.getLocation();
        final double px = at.getX();
        final double py = at.getY();
        final double pz = at.getZ();
        // particles: one packet per call
        final int particlePackets = this.scaled(this.particlePacketsPerTick);
        for (int i = 0; i < particlePackets && this.particles.length > 0; i++) {
            final Particle particle = this.particles[this.playerRandom.nextInt(this.particles.length)];
            player.spawnParticle(particle,
                px + this.playerRandom.nextDouble() * 12 - 6, py + this.playerRandom.nextDouble() * 4, pz + this.playerRandom.nextDouble() * 12 - 6,
                this.particlesPerPacket, 0.5, 0.5, 0.5, 0.0);
        }
        this.packetsSent += particlePackets;
        // sounds: quiet, seeded
        final int sounds = this.scaled(this.soundsPerTick);
        for (int i = 0; i < sounds; i++) {
            final Sound sound = SOUNDS[this.playerRandom.nextInt(SOUNDS.length)];
            final Location where = new Location(this.world, px + this.playerRandom.nextDouble() * 8 - 4, py, pz + this.playerRandom.nextDouble() * 8 - 4);
            player.playSound(where, sound, SoundCategory.AMBIENT, 0.05f, 0.5f + this.playerRandom.nextFloat(), this.playerRandom.nextLong());
        }
        this.packetsSent += sounds;
        // fake blocks (client only): one multi-block change per section
        final int fake = Math.min(this.fakeCells.size(), this.scaled(this.fakeBlockChanges));
        if (fake > 0) {
            final Map<BlockPosition, BlockData> changes = new HashMap<>(fake * 2);
            for (int i = 0; i < fake; i++) {
                final int[] c = this.fakeCells.get((this.fakeCursor + i) % this.fakeCells.size());
                changes.put(Position.block(c[0], c[1], c[2]), this.fakeData[(int) ((tick + i) & 3)]);
            }
            player.sendMultiBlockChange(changes);
            this.packetsSent += Math.max(1, fake / 256);
        }
        final int single = Math.min(this.fakeCells.size(), this.scaled(this.singleBlockChanges));
        for (int i = 0; i < single; i++) {
            final int[] c = this.fakeCells.get(this.playerRandom.nextInt(this.fakeCells.size()));
            player.sendBlockChange(new Location(this.world, c[0], c[1], c[2]), this.fakeData[this.playerRandom.nextInt(this.fakeData.length)]);
        }
        this.packetsSent += single;
        if (this.actionBar) {
            player.sendActionBar(Component.text("AmazakeStress · тик " + tick + " · пакеты", COLORS[(int) (tick % COLORS.length)]));
            this.packetsSent++;
        }
    }

    /** Boss bars and scoreboard: one update reaches every viewer. */
    private void sharedPart(long tick) {
        this.fakeCursor = (this.fakeCursor + Math.max(1, this.scaled(this.fakeBlockChanges))) % Math.max(1, this.fakeCells.size());
        for (int i = 0; i < this.bars.size(); i++) {
            final BossBar bar = this.bars.get(i);
            bar.progress((float) (((tick + i * 7L) % 100L) / 100.0));
            bar.name(Component.text("AmazakeStress " + i + " · " + tick));
            if (tick % 10 == i) {
                bar.color(BAR_COLORS[(int) ((tick / 10 + i) % BAR_COLORS.length)]);
            }
        }
        final int teamUpdates = this.teams.isEmpty() ? 0 : this.scaled(this.teamUpdatesPerTick);
        for (int i = 0; i < teamUpdates; i++) {
            final Team team = this.teams.get(this.teamCursor++ % this.teams.size());
            team.prefix(Component.text("[" + (tick % 1000) + "] ", COLORS[(int) ((tick + i) % COLORS.length)]));
        }
        if (this.sidebar != null) {
            for (int line = 0; line < this.sidebarLines; line++) {
                this.sidebar.getScore("line " + line).setScore((int) ((tick * (line + 1)) % 1000));
            }
        }
        this.packetsSent += this.bars.size() * 2L + teamUpdates + this.sidebarLines;
    }

    // ---------------------------------------------------------------- in-world part (no players needed)

    private void worldPart(long tick) {
        // real block toggles: block update packets to everyone within view distance
        final int toggles = Math.min(this.boardCells.size(), this.scaled(this.blockToggles));
        for (int i = 0; i < toggles; i++) {
            if (this.toggleCursor >= this.boardCells.size()) {
                this.toggleCursor = 0;
                this.toggleState = !this.toggleState;
            }
            final int[] c = this.boardCells.get(this.toggleCursor++);
            this.ctx.blocks().set(c[0], c[1], c[2], this.toggleState ? this.boardA : this.boardB);
        }
        this.blockToggleCount += toggles;
        // entity metadata: rename and glow toggle
        final int updates = this.stands.isEmpty() ? 0 : Math.min(this.stands.size(), this.scaled(this.entityUpdatesPerTick));
        for (int i = 0; i < updates; i++) {
            final int index = this.entityCursor++ % this.stands.size();
            final ArmorStand stand = this.stands.get(index);
            if (stand.isValid()) {
                stand.customName(Component.text("stress #" + index + " · " + tick, COLORS[(int) ((tick + index) % COLORS.length)]));
                stand.setGlowing(((tick + index) & 1) == 0);
            }
        }
        // entity movement: small circles around each home
        final int moves = Math.min(this.stands.size(), this.scaled(this.movingEntities));
        for (int i = 0; i < moves; i++) {
            final ArmorStand stand = this.stands.get(i);
            if (!stand.isValid()) {
                continue;
            }
            final double[] home = this.standHomes.get(i);
            final double angle = home[3] + tick * 0.15;
            stand.teleport(new Location(this.world, home[0] + Math.cos(angle) * 1.5, home[1], home[2] + Math.sin(angle) * 1.5,
                (float) (tick * 9 % 360), 0f));
        }
        // particle bursts broadcast by the world (to players within range)
        final int bursts = this.particles.length == 0 ? 0 : this.scaled(this.worldParticleBursts);
        for (int i = 0; i < bursts; i++) {
            final Particle particle = this.particles[this.random.nextInt(this.particles.length)];
            this.world.spawnParticle(particle,
                this.region.minX() + 8 + this.random.nextDouble() * 48, this.region.minY() + 1 + this.random.nextDouble() * 5,
                this.region.minZ() + 8 + this.random.nextDouble() * 48,
                this.worldParticlesPerBurst, 1.0, 1.0, 1.0, 0.0);
        }
    }

    private void restoreFakeBlocks(Player player) {
        if (!player.getWorld().equals(this.world) || this.fakeCells.isEmpty()) {
            return;
        }
        final Map<BlockPosition, BlockData> real = new HashMap<>(this.fakeCells.size() * 2);
        for (final int[] c : this.fakeCells) {
            real.put(Position.block(c[0], c[1], c[2]), this.world.getBlockAt(c[0], c[1], c[2]).getBlockData());
        }
        player.sendMultiBlockChange(real);
    }

    // ---------------------------------------------------------------- stop / status

    @Override
    public void stop() {
        this.stopped = true;
        for (final UUID id : new ArrayList<>(this.barViewers)) {
            this.restorePlayer(id);
        }
        this.barViewers.clear();
        for (final UUID id : new ArrayList<>(this.previousBoards.keySet())) {
            this.restorePlayer(id);
        }
        for (final Team team : this.teams) {
            try {
                team.unregister();
            } catch (final IllegalStateException ignored) {
                // already unregistered
            }
        }
        this.teams.clear();
        this.bars.clear();
        // Players this module moved into stress_world go back where they were (if they are still on the platform world).
        for (final Map.Entry<UUID, Location> entry : this.returnTo.entrySet()) {
            final Player player = Bukkit.getPlayer(entry.getKey());
            final Location back = entry.getValue();
            if (player != null && player.isOnline() && player.getWorld().equals(this.world)
                && back.getWorld() != null && Bukkit.getWorld(back.getWorld().getUID()) != null) {
                player.teleport(back);
            }
        }
        this.returnTo.clear();
        this.stands.clear(); // tracked entities are removed by the framework
        this.standHomes.clear();
    }

    @Override
    public String status() {
        if (this.ctx == null) {
            return "не запущен";
        }
        if (!this.ready || this.boardBuildCursor < this.boardCells.size()) {
            return "строю площадку (" + (this.floorCursor + this.boardBuildCursor) + " блоков)";
        }
        return "игроков " + this.lastPlayers + "/" + this.maxPlayers + ", пакетов игрокам ~" + this.packetsSent
            + ", переключений блоков " + this.blockToggleCount + ", стоек " + this.stands.size() + "/" + this.entityCount
            + (this.lastPlayers == 0 ? " (нет игроков в " + this.world.getName() + ": только нагрузка в мире)" : "");
    }
}
