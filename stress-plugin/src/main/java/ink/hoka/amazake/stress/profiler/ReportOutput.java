package ink.hoka.amazake.stress.profiler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * Turns the report into JSON and gets it to the owner: uploaded to {@code profiler.upload-url} when set
 * (POST /api/reports, gzip, optional X-Amazake-Token), otherwise or on any failure saved to
 * {@code plugins/AmazakeStress/profiles/}. Runs on the profiler thread, never on the main thread.
 * Same API and behaviour as Amazake's built-in profiler (docs/profiler-format.md, "Upload API").
 */
final class ReportOutput {

    /** The site refuses reports larger than this, uncompressed (413). */
    static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Logger logger;
    private final Messages messages;
    private final Path folder;
    private final String userAgent;

    ReportOutput(final Logger logger, final Messages messages, final Path folder, final String pluginVersion) {
        this.logger = logger;
        this.messages = messages;
        this.folder = folder;
        this.userAgent = "AmazakeStress/" + pluginVersion;
    }

    /**
     * @param localOnly true when the plugin or server is shutting down: the report is only saved to profiles/,
     *                  because the JVM may exit (or the plugin's classes unload) before a slow upload finishes
     * @return the link or the file path, for /stress status
     */
    @Nullable String deliver(final Report report, final ProfilerSettings settings, final CommandSender sender, final boolean localOnly) {
        final byte[] json = GSON.toJson(report).getBytes(StandardCharsets.UTF_8);
        final String site = settings.uploadUrl();
        String failure = null;
        if (localOnly) {
            if (!site.isBlank()) {
                failure = "плагин/сервер выключается, отправка могла не успеть";
            }
        } else if (!site.isBlank()) {
            if (json.length > MAX_UPLOAD_BYTES) {
                failure = "отчёт больше 8 МБ, сайт его не примет (уменьшите время замера или увеличьте profiler.interval-ms)";
            } else {
                this.messages.info(sender, "Замер закончен, отправляю отчёт на " + site + " …");
                final String[] link = new String[1];
                failure = this.upload(json, site, settings.uploadToken(), sender, link);
                if (failure == null) {
                    return link[0];
                }
            }
        }
        return this.save(json, report.server().brand(), sender, failure);
    }

    /** Null when uploaded and the link was sent, else why it failed (in Russian). */
    private @Nullable String upload(final byte[] json, final String site, final String token, final CommandSender sender,
                                    final String[] linkOut) {
        final URI endpoint;
        try {
            String base = site.trim();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            endpoint = URI.create(base + "/api/reports");
            if (endpoint.getScheme() == null || endpoint.getHost() == null
                || !(endpoint.getScheme().equalsIgnoreCase("http") || endpoint.getScheme().equalsIgnoreCase("https"))) {
                return "неверный адрес profiler.upload-url: " + site;
            }
        } catch (final IllegalArgumentException e) {
            return "неверный адрес profiler.upload-url: " + site;
        }

        final HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Content-Encoding", "gzip")
            .header("User-Agent", this.userAgent)
            .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(json)));
        if (token != null && !token.isBlank()) {
            request.header("X-Amazake-Token", token);
        }

        final HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "отправку прервали";
        } catch (final IOException | RuntimeException e) {
            this.logger.warning("Profiler upload to " + endpoint + " failed: " + e);
            return "сайт недоступен (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")";
        }

        return switch (response.statusCode()) {
            case 201 -> this.linkFrom(response.body(), sender, linkOut);
            case 400 -> "сайт не принял отчёт (400: не тот формат; возможно, сайт старее плагина)";
            case 401 -> "сайт отказал в доступе (401): проверьте profiler.upload-token в config.yml";
            case 413 -> "отчёт слишком большой для сайта (413)";
            case 429 -> "слишком много отчётов подряд (429), попробуйте позже";
            default -> "сайт ответил кодом " + response.statusCode();
        };
    }

    private @Nullable String linkFrom(final String body, final CommandSender sender, final String[] linkOut) {
        try {
            final JsonObject answer = JsonParser.parseString(body).getAsJsonObject();
            final JsonElement url = answer.get("url");
            if (url == null || !url.isJsonPrimitive() || url.getAsString().isBlank()) {
                return "сайт принял отчёт, но не прислал ссылку";
            }
            linkOut[0] = url.getAsString();
            this.messages.link(sender, "Отчёт готов:", url.getAsString());
            return null;
        } catch (final RuntimeException e) {
            return "сайт принял отчёт, но ответ не удалось прочитать";
        }
    }

    private @Nullable String save(final byte[] json, final String brand, final CommandSender sender, final @Nullable String uploadFailure) {
        final String safeBrand = brand.replaceAll("[^A-Za-z0-9._-]", "_");
        final Path file = this.folder.resolve("amazake-profile-" + safeBrand + "-" + LocalDateTime.now().format(FILE_TIME) + ".json");
        try {
            Files.createDirectories(this.folder);
            Files.write(file, json);
        } catch (final IOException | RuntimeException e) {
            this.logger.log(Level.SEVERE, "Could not save profiler report to " + file, e);
            this.messages.error(sender, "Не удалось сохранить отчёт в " + file + ": " + e.getMessage());
            return null;
        }
        if (uploadFailure != null) {
            this.messages.error(sender, "Отчёт не отправлен: " + uploadFailure + ".");
        }
        final String path = file.toAbsolutePath().normalize().toString();
        this.messages.result(sender, "Отчёт сохранён: " + path
            + ". Его можно перетащить на страницу сайта профайлера, чтобы посмотреть.");
        return path;
    }

    private static byte[] gzip(final byte[] data) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        } catch (final IOException e) {
            throw new IllegalStateException(e); // cannot happen with an in-memory stream
        }
        return out.toByteArray();
    }
}
