package core.amazake.core.modules.essentials;

import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;

/** Essentials: warps, homes, teleport requests, gamemode shortcuts, /invsee, /ec. (skeleton) */
public final class EssentialsModule implements Module {
    @Override public String id() { return "essentials"; }
    @Override public String description() { return "варпы, дома, запросы на телепорт, /gm, /invsee, /ec"; }
    @Override public void onEnable(final ModuleContext context) throws Exception { }
    @Override public void onDisable() throws Exception { }
}
