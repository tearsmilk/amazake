package ink.hoka.amazake.profiler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ink.hoka.amazake.AmazakeConfig;
import io.papermc.paper.ServerBuildInfo;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
import java.util.logging.Level;
import java.util.zip.GZIPOutputStream;
import org.bukkit.command.CommandSender;

/**
 * Turns the report into JSON and gets it to the owner: uploaded to profiler.upload-url when set
 * (POST /api/reports, gzip), otherwise or on any failure saved to profiles/ in the server folder.
 * Runs on the profiler thread, never on the main thread.
 */
final class ReportOutput {

    /** The site refuses reports larger than this, uncompressed (413). */
    static final int MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ReportOutput() {
    }

    /**
     * @param serverStopping true when the server is shutting down: the report is only saved to profiles/,
     *                       because the JVM may exit before a slow upload finishes and the report would be lost
     */
    static void deliver(final Report report, final CommandSender sender, final boolean serverStopping) {
        final byte[] json = GSON.toJson(report).getBytes(StandardCharsets.UTF_8);
        final String site = AmazakeConfig.profilerUploadUrl;
        String failure = null;
        if (serverStopping) {
            if (site != null && !site.isBlank()) {
                failure = "сервер выключается, отправка могла не успеть";
            }
        } else if (site != null && !site.isBlank()) {
            if (json.length > MAX_UPLOAD_BYTES) {
                failure = "отчёт больше 8 МБ, сайт его не примет (уменьшите время замера или увеличьте profiler.interval-ms)";
            } else {
                Messages.info(sender, "Замер закончен, отправляю отчёт на сайт…");
                failure = upload(json, site, AmazakeConfig.profilerUploadToken, sender);
                if (failure == null) {
                    return;
                }
            }
        }
        save(json, sender, failure);
    }

    /** Null when uploaded and the link was sent, else why it failed (in Russian). */
    private static String upload(final byte[] json, final String site, final String token, final CommandSender sender) {
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
            .header("User-Agent", "Amazake/" + ServerBuildInfo.buildInfo().asString(ServerBuildInfo.StringRepresentation.VERSION_SIMPLE))
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
            Profiler.LOGGER.warning("Profiler upload to " + endpoint + " failed: " + e);
            return "сайт недоступен (" + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ")";
        }

        return switch (response.statusCode()) {
            case 201 -> linkFrom(response.body(), sender);
            case 400 -> "сайт не принял отчёт (400: не тот формат; возможно, сайт старее сервера)";
            case 401 -> "сайт отказал в доступе (401): проверьте profiler.upload-token в amazake.yml";
            case 413 -> "отчёт слишком большой для сайта (413)";
            case 429 -> "слишком много отчётов подряд (429), попробуйте позже";
            default -> "сайт ответил кодом " + response.statusCode();
        };
    }

    private static String linkFrom(final String body, final CommandSender sender) {
        try {
            final JsonObject answer = JsonParser.parseString(body).getAsJsonObject();
            final JsonElement url = answer.get("url");
            if (url == null || !url.isJsonPrimitive() || url.getAsString().isBlank()) {
                return "сайт принял отчёт, но не прислал ссылку";
            }
            Messages.link(sender, "Отчёт готов:", url.getAsString());
            return null;
        } catch (final RuntimeException e) {
            return "сайт принял отчёт, но ответ не удалось прочитать";
        }
    }

    private static void save(final byte[] json, final CommandSender sender, final String uploadFailure) {
        final File folder = new File(AmazakeConfig.file().getAbsoluteFile().getParentFile(), "profiles");
        final Path file = folder.toPath().resolve("amazake-profile-" + LocalDateTime.now().format(FILE_TIME) + ".json");
        try {
            Files.createDirectories(folder.toPath());
            Files.write(file, json);
        } catch (final IOException | RuntimeException e) {
            Profiler.LOGGER.log(Level.SEVERE, "Could not save profiler report to " + file, e);
            Messages.error(sender, "Не удалось сохранить отчёт в " + file + ": " + e.getMessage());
            return;
        }
        if (uploadFailure != null) {
            Messages.error(sender, "Отчёт не отправлен: " + uploadFailure + ".");
        }
        Messages.result(sender, "Отчёт сохранён: " + file.toAbsolutePath().normalize()
            + ". Его можно перетащить на страницу сайта профайлера, чтобы посмотреть.");
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
