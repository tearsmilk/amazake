package core.amazake.core.modules.npc;

import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;

/** NPCs via the Citizens plugin, with player skins. (skeleton) */
public final class NpcModule implements Module {
    @Override public String id() { return "npc"; }
    @Override public String description() { return "NPC через Citizens, скины игроков"; }
    @Override public void onEnable(final ModuleContext context) throws Exception { }
    @Override public void onDisable() throws Exception { }
}
