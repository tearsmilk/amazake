package core.amazake.core.modules.chat;

import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;

/** Chat: global/local, formats, cooldown, group prefixes. (skeleton) */
public final class ChatModule implements Module {
    @Override public String id() { return "chat"; }
    @Override public String description() { return "чат: глобал/локал, оформление, задержки, префиксы групп"; }
    @Override public void onEnable(final ModuleContext context) throws Exception { }
    @Override public void onDisable() throws Exception { }
}
