package core.amazake.core.modules.groups;

import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;

/** Own permissions/groups system with inheritance, prefixes and weights. (skeleton) */
public final class GroupsModule implements Module {
    @Override public String id() { return "groups"; }
    @Override public String description() { return "свои группы и права: наследование, префиксы, веса"; }
    @Override public void onEnable(final ModuleContext context) throws Exception { }
    @Override public void onDisable() throws Exception { }
}
