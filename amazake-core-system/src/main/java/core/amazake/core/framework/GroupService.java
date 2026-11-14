package core.amazake.core.framework;

import java.util.UUID;

/**
 * Permissions and group information, published by the {@code groups} module into {@link CoreServices}. Other
 * modules (chat, tab) read it when present; if the groups module is disabled, {@link CoreServices#get} returns
 * empty and consumers fall back to defaults. All methods are safe to call from any thread and must not block.
 */
public interface GroupService {

    /** The player's primary group id (the highest-weight group they are in), or the default group. */
    String primaryGroup(UUID player);

    /** The display prefix for the player (MiniMessage, may be empty), resolved from their groups by weight. */
    String prefix(UUID player);

    /** The display suffix for the player (MiniMessage, may be empty). */
    String suffix(UUID player);

    /** The player's highest group weight (bigger = higher rank); 0 if unknown. */
    int weight(UUID player);

    /** Whether the player has a permission, taking groups and inheritance into account. */
    boolean has(UUID player, String permission);
}
