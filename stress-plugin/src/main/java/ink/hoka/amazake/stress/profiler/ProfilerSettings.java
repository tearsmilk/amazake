package ink.hoka.amazake.stress.profiler;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** The {@code profiler} section of config.yml, read on the main thread when a measurement starts. */
record ProfilerSettings(String uploadUrl, String uploadToken, int intervalMs, int maxSeconds) {

    static final String DEFAULT_UPLOAD_URL = "https://mc-ru.org";
    static final int MIN_INTERVAL_MS = 250;
    static final int MAX_INTERVAL_MS = 10_000;

    static ProfilerSettings read(final FileConfiguration config) {
        final ConfigurationSection section = config.getConfigurationSection("profiler");
        if (section == null) {
            return new ProfilerSettings(DEFAULT_UPLOAD_URL, "", 1000, 7200);
        }
        final String url = section.getString("upload-url", DEFAULT_UPLOAD_URL);
        final String token = section.getString("upload-token", "");
        final int interval = Math.max(MIN_INTERVAL_MS, Math.min(MAX_INTERVAL_MS, section.getInt("interval-ms", 1000)));
        final int max = Math.max(10, section.getInt("max-seconds", 7200));
        return new ProfilerSettings(url == null ? "" : url.trim(), token == null ? "" : token.trim(), interval, max);
    }
}
