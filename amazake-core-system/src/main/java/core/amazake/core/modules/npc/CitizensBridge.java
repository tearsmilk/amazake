package core.amazake.core.modules.npc;

import java.lang.reflect.Method;
import org.bukkit.command.CommandSender;

/**
 * A thin reflective bridge to the optional Citizens plugin. Citizens is NOT on the compile classpath and must not be
 * imported directly (ProGuard must not see {@code net.citizensnpcs.*}); every call goes through cached
 * {@link Class}/{@link Method} handles looked up once in {@link #create()}.
 *
 * <p>The bridge never throws from its operations: {@link #setSkin} returns {@code false} and the caller reports a
 * Russian error. Only {@link #create()} throws (when the API shape is missing), so the module can degrade at enable
 * time.</p>
 */
final class CitizensBridge {

    private final Method getNpcRegistry;    // CitizensAPI#getNPCRegistry() -> NPCRegistry
    private final Method getNpcSelector;     // CitizensAPI#getNPCSelector() -> NPCSelector
    private final Method registryGetById;    // NPCRegistry#getById(int) -> NPC
    private final Method selectorGetSelected; // NPCSelector#getSelected(CommandSender) -> NPC
    private final Method npcGetOrAddTrait;    // NPC#getOrAddTrait(Class) -> Trait
    private final Method npcGetId;            // NPC#getId() -> int
    private final Method npcGetName;          // NPC#getName() -> String
    private final Class<?> skinTraitClass;    // net.citizensnpcs.trait.SkinTrait
    private final Method setSkinName1;        // SkinTrait#setSkinName(String)
    private final Method setSkinName2;        // SkinTrait#setSkinName(String, boolean) (may be null)

    private CitizensBridge(final Method getNpcRegistry, final Method getNpcSelector, final Method registryGetById,
                           final Method selectorGetSelected, final Method npcGetOrAddTrait, final Method npcGetId,
                           final Method npcGetName, final Class<?> skinTraitClass, final Method setSkinName1,
                           final Method setSkinName2) {
        this.getNpcRegistry = getNpcRegistry;
        this.getNpcSelector = getNpcSelector;
        this.registryGetById = registryGetById;
        this.selectorGetSelected = selectorGetSelected;
        this.npcGetOrAddTrait = npcGetOrAddTrait;
        this.npcGetId = npcGetId;
        this.npcGetName = npcGetName;
        this.skinTraitClass = skinTraitClass;
        this.setSkinName1 = setSkinName1;
        this.setSkinName2 = setSkinName2;
    }

    /**
     * Looks up the Citizens API reflectively, caching every handle. Throws if the expected shape is absent, so the
     * module can log a stacktrace and stay idle rather than register a broken command.
     */
    static CitizensBridge create() throws ReflectiveOperationException {
        final Class<?> apiClass = Class.forName("net.citizensnpcs.api.CitizensAPI");
        final Class<?> registryClass = Class.forName("net.citizensnpcs.api.npc.NPCRegistry");
        final Class<?> selectorClass = Class.forName("net.citizensnpcs.api.npc.NPCSelector");
        final Class<?> npcClass = Class.forName("net.citizensnpcs.api.npc.NPC");
        final Class<?> traitClass = Class.forName("net.citizensnpcs.api.trait.Trait");
        final Class<?> skinTraitClass = Class.forName("net.citizensnpcs.trait.SkinTrait");

        final Method getNpcRegistry = apiClass.getMethod("getNPCRegistry");
        final Method getNpcSelector = apiClass.getMethod("getNPCSelector");
        final Method registryGetById = registryClass.getMethod("getById", int.class);
        final Method selectorGetSelected = selectorClass.getMethod("getSelected", CommandSender.class);
        final Method npcGetOrAddTrait = npcClass.getMethod("getOrAddTrait", Class.class);
        final Method npcGetId = npcClass.getMethod("getId");
        final Method npcGetName = npcClass.getMethod("getName");

        // setSkinName(String) is required; setSkinName(String, boolean) is a bonus when present.
        final Method setSkinName1 = skinTraitClass.getMethod("setSkinName", String.class);
        Method setSkinName2;
        try {
            setSkinName2 = skinTraitClass.getMethod("setSkinName", String.class, boolean.class);
        } catch (final NoSuchMethodException ignored) {
            setSkinName2 = null;
        }

        // Sanity: the trait class must actually be a Trait so getOrAddTrait accepts it.
        if (!traitClass.isAssignableFrom(skinTraitClass)) {
            throw new NoSuchMethodException("SkinTrait не является Trait — API Citizens изменился");
        }

        return new CitizensBridge(getNpcRegistry, getNpcSelector, registryGetById, selectorGetSelected,
            npcGetOrAddTrait, npcGetId, npcGetName, skinTraitClass, setSkinName1, setSkinName2);
    }

    /** The NPC the sender currently has selected, or {@code null} if none/unavailable. */
    Object selectedNpc(final CommandSender sender) {
        try {
            final Object selector = this.getNpcSelector.invoke(null);
            if (selector == null) {
                return null;
            }
            return this.selectorGetSelected.invoke(selector, sender);
        } catch (final ReflectiveOperationException e) {
            return null;
        }
    }

    /** The NPC with the given numeric id, or {@code null} if none/unavailable. */
    Object npcById(final int id) {
        try {
            final Object registry = this.getNpcRegistry.invoke(null);
            if (registry == null) {
                return null;
            }
            return this.registryGetById.invoke(registry, id);
        } catch (final ReflectiveOperationException e) {
            return null;
        }
    }

    /** A short label "{@code #id name}" for messages; defensive if the NPC object misbehaves. */
    String describe(final Object npc) {
        try {
            final Object id = this.npcGetId.invoke(npc);
            final Object name = this.npcGetName.invoke(npc);
            return "#" + id + " " + name;
        } catch (final ReflectiveOperationException e) {
            return "NPC";
        }
    }

    /**
     * Sets {@code npc}'s skin to {@code skin} (a player name or raw value). Must run on the region that owns the NPC
     * (the module uses the global region scheduler). Returns {@code false} on any reflective failure.
     */
    boolean setSkin(final Object npc, final String skin) {
        try {
            final Object trait = this.npcGetOrAddTrait.invoke(npc, this.skinTraitClass);
            if (trait == null) {
                return false;
            }
            if (this.setSkinName2 != null) {
                // The boolean is "force update" — refetch the skin from Mojang immediately.
                this.setSkinName2.invoke(trait, skin, true);
            } else {
                this.setSkinName1.invoke(trait, skin);
            }
            return true;
        } catch (final ReflectiveOperationException e) {
            return false;
        }
    }
}
