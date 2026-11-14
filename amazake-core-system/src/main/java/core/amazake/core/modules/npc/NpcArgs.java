package core.amazake.core.modules.npc;

import java.util.Locale;

/**
 * Pure parsing of the {@code /npccore} arguments — no Bukkit, no Citizens, so it is unit-testable.
 *
 * <p>Grammar:</p>
 * <ul>
 *   <li>{@code skin <player>} — set the selected NPC's skin to {@code <player>}'s skin;</li>
 *   <li>{@code skin id <npcId> <player>} — same, for the NPC with the given numeric id;</li>
 *   <li>{@code skinurl <value>} — set the skin from a raw name/value.</li>
 * </ul>
 */
final class NpcArgs {

    /** What the sender asked for. */
    enum Action {
        /** Skin of the currently selected NPC. */
        SKIN_SELECTED,
        /** Skin of the NPC with {@link #npcId()}. */
        SKIN_BY_ID,
        /** Skin from a raw value on the selected NPC. */
        SKIN_URL
    }

    private final Action action;
    private final Integer npcId;
    private final String value;

    private NpcArgs(final Action action, final Integer npcId, final String value) {
        this.action = action;
        this.npcId = npcId;
        this.value = value;
    }

    Action action() {
        return this.action;
    }

    /** The NPC numeric id for {@link Action#SKIN_BY_ID}, otherwise {@code null}. */
    Integer npcId() {
        return this.npcId;
    }

    /** The skin name (a player name) or the raw skin value. */
    String value() {
        return this.value;
    }

    /** The outcome of parsing: either {@link #args()} is set, or {@link #error()} holds a Russian message. */
    record Result(NpcArgs args, String error) {
        static Result ok(final NpcArgs args) {
            return new Result(args, null);
        }

        static Result err(final String error) {
            return new Result(null, error);
        }

        boolean isOk() {
            return this.args != null;
        }
    }

    private static final String USAGE =
        "Использование: /npccore skin <игрок> | /npccore skin id <id> <игрок> | /npccore skinurl <значение>";

    /** Parses the raw command arguments. Never throws. */
    static Result parse(final String[] args) {
        if (args == null || args.length == 0) {
            return Result.err(USAGE);
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "skin": {
                if (args.length >= 2 && args[1].equalsIgnoreCase("id")) {
                    if (args.length < 4) {
                        return Result.err("Использование: /npccore skin id <id> <игрок>");
                    }
                    final Integer id = parseId(args[2]);
                    if (id == null) {
                        return Result.err("Номер NPC должен быть целым числом, а не «" + args[2] + "».");
                    }
                    final String player = args[3];
                    if (player.isBlank()) {
                        return Result.err("Укажите имя игрока, чей скин поставить.");
                    }
                    return Result.ok(new NpcArgs(Action.SKIN_BY_ID, id, player));
                }
                if (args.length < 2 || args[1].isBlank()) {
                    return Result.err("Использование: /npccore skin <игрок>");
                }
                return Result.ok(new NpcArgs(Action.SKIN_SELECTED, null, args[1]));
            }
            case "skinurl": {
                if (args.length < 2 || args[1].isBlank()) {
                    return Result.err("Использование: /npccore skinurl <значение>");
                }
                return Result.ok(new NpcArgs(Action.SKIN_URL, null, args[1]));
            }
            default:
                return Result.err("Неизвестная подкоманда «" + args[0] + "». " + USAGE);
        }
    }

    private static Integer parseId(final String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
