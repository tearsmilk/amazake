package core.amazake.core.modules.holograms;

import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;

/** Holograms on armor stands or text/item displays. (skeleton) */
public final class HologramsModule implements Module {
    @Override public String id() { return "holograms"; }
    @Override public String description() { return "голограммы на armor stand или item/text display"; }
    @Override public void onEnable(final ModuleContext context) throws Exception { }
    @Override public void onDisable() throws Exception { }
}
