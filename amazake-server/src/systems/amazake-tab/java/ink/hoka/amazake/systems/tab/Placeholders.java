package ink.hoka.amazake.systems.tab;

import java.util.Locale;
import java.util.function.Function;

/**
 * {@code %name%} placeholder replacement. Pure: no Bukkit types, any thread.
 *
 * <p>A placeholder is {@code %} + {@code [A-Za-z0-9_]+} + {@code %}. The resolver gets the name in lower case and
 * returns the value, or null to leave the placeholder untouched (so a later pass, e.g. PlaceholderAPI, can handle
 * it). A lone {@code %} (as in "50%") is kept as is.</p>
 */
public final class Placeholders {

    private Placeholders() {
    }

    public static String apply(final String template, final Function<String, String> resolver) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        int start = template.indexOf('%');
        if (start < 0) {
            return template;
        }
        final StringBuilder out = new StringBuilder(template.length() + 16);
        int copied = 0;
        while (start >= 0) {
            int end = start + 1;
            while (end < template.length() && isNameChar(template.charAt(end))) {
                end++;
            }
            if (end < template.length() && end > start + 1 && template.charAt(end) == '%') {
                final String name = template.substring(start + 1, end).toLowerCase(Locale.ROOT);
                final String value = resolver.apply(name);
                if (value != null) {
                    out.append(template, copied, start).append(value);
                    copied = end + 1;
                    start = template.indexOf('%', end + 1);
                    continue;
                }
                // unknown: keep it and continue after its closing '%' so it is not reused as an opening one
                start = template.indexOf('%', end + 1);
                continue;
            }
            // not a placeholder: this '%' is literal, the next one may open a placeholder
            start = template.indexOf('%', start + 1);
        }
        out.append(template, copied, template.length());
        return out.toString();
    }

    /** True if the text still contains something that looks like a placeholder. */
    public static boolean hasPlaceholder(final String text) {
        final int first = text.indexOf('%');
        return first >= 0 && text.indexOf('%', first + 1) > first + 1;
    }

    /** Fixed-point number with a dot, independent of the server locale ("19.97"). */
    public static String decimal(final double value, final int digits) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0";
        }
        return String.format(Locale.ROOT, "%." + digits + "f", value);
    }

    private static boolean isNameChar(final char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }
}
