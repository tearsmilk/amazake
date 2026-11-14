package core.amazake.core.modules.groups;

import java.util.Set;

/**
 * Неизменяемый снимок одной группы в кэше. Меняется целиком (не по полям), чтобы чтение из других потоков было
 * безопасным: задача подменяет запись в {@code ConcurrentHashMap}, а не правит её.
 *
 * @param name    канонное имя (нижний регистр)
 * @param weight  вес (больше — выше ранг)
 * @param prefix  префикс в MiniMessage (может быть пустым)
 * @param suffix  суффикс в MiniMessage (может быть пустым)
 * @param perms   собственные права группы (неизменяемый набор, в нижнем регистре)
 * @param parents прямые родители (неизменяемый набор имён в нижнем регистре)
 */
public record GroupData(String name, int weight, String prefix, String suffix,
                        Set<String> perms, Set<String> parents) {

    public GroupData {
        perms = Set.copyOf(perms);
        parents = Set.copyOf(parents);
    }

    public GroupData withWeight(final int newWeight) {
        return new GroupData(this.name, newWeight, this.prefix, this.suffix, this.perms, this.parents);
    }

    public GroupData withPrefix(final String newPrefix) {
        return new GroupData(this.name, this.weight, newPrefix, this.suffix, this.perms, this.parents);
    }

    public GroupData withSuffix(final String newSuffix) {
        return new GroupData(this.name, this.weight, this.prefix, newSuffix, this.perms, this.parents);
    }

    public GroupData withPerms(final Set<String> newPerms) {
        return new GroupData(this.name, this.weight, this.prefix, this.suffix, newPerms, this.parents);
    }

    public GroupData withParents(final Set<String> newParents) {
        return new GroupData(this.name, this.weight, this.prefix, this.suffix, this.perms, newParents);
    }
}
