package ink.hoka.amazake.systems.tab;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.scoreboard.Team;

/**
 * What every viewer needs to know about one player to show them in its own scoreboard: the team (sort key) and the
 * name tag. Immutable; written only by that player's own task into the shared map and read by everyone's tasks.
 * Equality is used to skip unchanged entries, so all fields are value types.
 */
record SharedEntry(UUID id, String name, String team, Component prefix, Component suffix, NamedTextColor color,
                   Team.OptionStatus collision, Team.OptionStatus visibility) {

    static Team.OptionStatus collision(final String value) {
        return switch (value) {
            case "never" -> Team.OptionStatus.NEVER;
            case "push-other-teams" -> Team.OptionStatus.FOR_OTHER_TEAMS;
            case "push-own-team" -> Team.OptionStatus.FOR_OWN_TEAM;
            default -> Team.OptionStatus.ALWAYS;
        };
    }

    static Team.OptionStatus visibility(final String value) {
        return switch (value) {
            case "never" -> Team.OptionStatus.NEVER;
            case "hide-for-other-teams" -> Team.OptionStatus.FOR_OTHER_TEAMS;
            case "hide-for-own-team" -> Team.OptionStatus.FOR_OWN_TEAM;
            default -> Team.OptionStatus.ALWAYS;
        };
    }
}
