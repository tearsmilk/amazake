package core.amazake.core.modules.groups;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Чистая логика групп: наследование прав (устойчивое к циклам), выбор основной группы по весу и проверка прав с
 * поддержкой подстановочных знаков ({@code *} и {@code a.b.*}). Методы ничего не знают о сервере и работают с
 * обычными {@code Map}/{@code Set}, поэтому легко покрываются юнит-тестами.
 */
public final class GroupLogic {

    private GroupLogic() {
    }

    /** Приводит имя группы или права к канонному виду (нижний регистр, без пробелов по краям). */
    public static String norm(final String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Эффективные права одной группы: её собственные права плюс права всех предков (транзитивно). Обход устойчив
     * к циклам в графе наследования — каждая группа посещается не более одного раза.
     *
     * @param group       имя группы
     * @param directPerms собственные права каждой группы
     * @param parents     прямые родители каждой группы
     */
    public static Set<String> effectivePerms(final String group,
                                             final Map<String, Set<String>> directPerms,
                                             final Map<String, Set<String>> parents) {
        final Set<String> out = new HashSet<>();
        final Set<String> visited = new HashSet<>();
        final Deque<String> stack = new ArrayDeque<>();
        stack.push(group);
        while (!stack.isEmpty()) {
            final String g = stack.pop();
            if (!visited.add(g)) {
                continue;
            }
            final Set<String> own = directPerms.get(g);
            if (own != null) {
                out.addAll(own);
            }
            final Set<String> par = parents.get(g);
            if (par != null) {
                for (final String p : par) {
                    if (!visited.contains(p)) {
                        stack.push(p);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Полный набор прав игрока: объединение эффективных прав всех его групп (плюс группа по умолчанию) и его личных
     * прав.
     */
    public static Set<String> playerEffective(final Collection<String> memberOf,
                                              final Set<String> playerPerms,
                                              final String defaultGroup,
                                              final Map<String, Set<String>> directPerms,
                                              final Map<String, Set<String>> parents) {
        final Set<String> groups = new HashSet<>();
        if (memberOf != null) {
            groups.addAll(memberOf);
        }
        if (defaultGroup != null && !defaultGroup.isEmpty()) {
            groups.add(defaultGroup);
        }
        final Set<String> out = new HashSet<>();
        for (final String g : groups) {
            out.addAll(effectivePerms(g, directPerms, parents));
        }
        if (playerPerms != null) {
            out.addAll(playerPerms);
        }
        return out;
    }

    /**
     * Основная группа игрока: из его групп (и группы по умолчанию) — та, у которой наибольший вес. При равном весе
     * берётся лексикографически меньшее имя, чтобы результат был детерминированным.
     */
    public static String primaryGroup(final Collection<String> memberOf,
                                       final Map<String, Integer> weights,
                                       final String defaultGroup) {
        String best = defaultGroup;
        int bestWeight = best == null ? Integer.MIN_VALUE : weights.getOrDefault(best, 0);
        if (memberOf != null) {
            for (final String g : memberOf) {
                final Integer w = weights.get(g);
                if (w == null) {
                    continue;
                }
                if (best == null || w > bestWeight || (w == bestWeight && g.compareTo(best) < 0)) {
                    best = g;
                    bestWeight = w;
                }
            }
        }
        return best;
    }

    /**
     * Проверка права по набору. Поддерживает {@code *} (все права), точное совпадение и префиксные подстановки вида
     * {@code a.b.*} (совпадает с {@code a.b.c}). Все элементы набора и проверяемый узел считаются уже приведёнными к
     * нижнему регистру.
     */
    public static boolean matches(final Set<String> perms, final String node) {
        if (perms == null || node == null) {
            return false;
        }
        if (perms.contains("*")) {
            return true;
        }
        final String n = norm(node);
        if (perms.contains(n)) {
            return true;
        }
        for (final String p : perms) {
            if (p.endsWith(".*")) {
                final String prefix = p.substring(0, p.length() - 1); // "a.b.*" -> "a.b."
                if (n.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }
}
