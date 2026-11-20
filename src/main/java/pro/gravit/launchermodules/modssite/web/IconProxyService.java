package pro.gravit.launchermodules.modssite.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.ModsSiteModule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Прокси картинок (иконки/превью проектов) Modrinth/CurseForge.
 *
 * Зачем: браузер грузил их напрямую с cdn.modrinth.com / media.forgecdn.net —
 * у пользователей с заблокированным доступом к этим CDN иконки не открывались,
 * хотя сам сайт работал (поиск идёт через сервер). Прокси отдаёт картинку
 * с домена сайта: GET /api/icon?url=... — сервер запрашивает её сам (с той же
 * точки, где работает поиск) и кэширует на диск.
 *
 * Защита:
 *  - эндпоинт требует сессию сайта (как остальной API) и лимитируется rate-limit'ом;
 *  - только http(s) и только домены из mods.iconProxyHosts (поддомены тоже);
 *  - редиректы разрешены, но финальный домен проверяется тем же списком;
 *  - отдаётся только image/*, размер ограничен, таймауты на соединение и ответ;
 *  - кэш на диске с TTL и мягким лимитом суммарного размера.
 */
public class IconProxyService {
    private static final Logger logger = LogManager.getLogger(IconProxyService.class);

    /** Лимит размера одной картинки (иконки проектов на порядок меньше). */
    private static final int MAX_BYTES = 5 * 1024 * 1024;
    /** TTL кэша на диске: иконки меняются редко, сутки — разумный компромисс. */
    private static final long TTL_MS = 24 * 3600_000L;
    /** Мягкий лимит суммарного размера кэша (обычно не достигается: чистка по TTL). */
    private static final long MAX_CACHE_BYTES = 256L * 1024 * 1024;
    /** До какого размера ужимать кэш при превышении мягкого лимита. */
    private static final long SWEEP_TARGET_BYTES = 128L * 1024 * 1024;

    private final ModsSiteModule module;
    private final Path cacheDir;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public IconProxyService(ModsSiteModule module, Path cacheDir) {
        this.module = module;
        this.cacheDir = cacheDir;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            logger.warn("[ModsSite] Cannot create icon cache dir {}", cacheDir, e);
        }
    }

    /** Включён ли прокси (mods.iconProxyEnabled, читается из живого конфига). */
    public boolean enabled() {
        ModsSiteConfig cfg = module.config;
        return cfg != null && cfg.mods != null && cfg.mods.iconProxyEnabled;
    }

    /** Домен разрешён? Точное совпадение или поддомен записи из конфига. */
    public boolean isAllowedHost(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return false;
        ModsSiteConfig cfg = module.config;
        if (cfg == null || cfg.mods == null || cfg.mods.iconProxyHosts == null) return false;
        for (String raw : cfg.mods.iconProxyHosts) {
            if (raw == null) continue;
            String allowed = raw.trim().toLowerCase(Locale.ROOT);
            if (allowed.isEmpty()) continue;
            if (h.equals(allowed) || h.endsWith("." + allowed)) return true;
        }
        return false;
    }

    public static class IconProxyException extends Exception {
        /** HTTP-статус источника, если ошибка — его ответ (0 — сетевой сбой/отказ). */
        public final int upstreamStatus;

        public IconProxyException(String message) {
            super(message);
            this.upstreamStatus = 0;
        }

        public IconProxyException(String message, int upstreamStatus) {
            super(message);
            this.upstreamStatus = upstreamStatus;
        }
    }

    /** Результат проксирования: тип содержимого и байты картинки. */
    public record Result(String contentType, byte[] body, boolean cached) {
    }

    private record Cached(String contentType, byte[] body) {
    }

    /** Скачать картинку через прокси (или отдать из дискового кэша). */
    public Result get(String rawUrl) throws IconProxyException, IOException {
        String url = rawUrl == null ? "" : rawUrl.trim();
        URI uri;
        try {
            uri = new URI(url);
        } catch (Exception e) {
            throw new IconProxyException("Invalid icon URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IconProxyException("Only http/https icon URLs are allowed");
        }
        String host = uri.getHost();
        if (host == null || !isAllowedHost(host)) {
            throw new IconProxyException("Icon host is not allowed: " + host);
        }
        String key = sha256Hex(url);
        Path bodyFile = cacheDir.resolve(key + ".img");
        Path metaFile = cacheDir.resolve(key + ".meta");

        Cached cached = readCache(metaFile, bodyFile);
        if (cached != null) {
            return new Result(cached.contentType(), cached.body(), true);
        }

        ModsSiteConfig cfg = module.config;
        String userAgent = cfg != null && cfg.mods != null && cfg.mods.modrinth != null
                && cfg.mods.modrinth.userAgent != null && !cfg.mods.modrinth.userAgent.isBlank()
                ? cfg.mods.modrinth.userAgent
                : "ModsSite_module/1.0";

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", userAgent)
                .header("Accept", "image/*,*/*;q=0.8")
                .GET().build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new IconProxyException("Icon host unreachable: " + host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IconProxyException("Icon fetch interrupted");
        }
        // Редирект мог увести на чужой домен — проверяем финальный адрес тем же списком
        URI finalUri = response.uri();
        if (finalUri != null && finalUri.getHost() != null && !isAllowedHost(finalUri.getHost())) {
            try (InputStream ignored = response.body()) {
            }
            throw new IconProxyException("Icon host is not allowed (after redirect): " + finalUri.getHost());
        }
        try (InputStream stream = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IconProxyException("Icon source returned HTTP " + status, status);
            }
            String contentType = parseContentType(response.headers().firstValue("content-type").orElse(""));
            if (!contentType.startsWith("image/")) {
                throw new IconProxyException("Icon URL is not an image (" + contentType + ")");
            }
            byte[] body = readLimited(stream);
            writeCache(metaFile, bodyFile, contentType, body);
            return new Result(contentType, body, false);
        }
    }

    private static String parseContentType(String raw) {
        String type = raw == null ? "" : raw;
        int semicolon = type.indexOf(';');
        if (semicolon > 0) type = type.substring(0, semicolon);
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] readLimited(InputStream stream) throws IOException, IconProxyException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(chunk)) > 0) {
            total += read;
            if (total > MAX_BYTES) {
                throw new IconProxyException("Icon too large (limit " + (MAX_BYTES / (1024 * 1024)) + " MB)");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private void writeCache(Path metaFile, Path bodyFile, String contentType, byte[] body) {
        try {
            Path tmp = bodyFile.resolveSibling(bodyFile.getFileName().toString() + ".tmp");
            Files.write(tmp, body);
            try {
                Files.move(tmp, bodyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(tmp, bodyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            JsonObject meta = new JsonObject();
            meta.addProperty("contentType", contentType);
            meta.addProperty("storedAt", System.currentTimeMillis());
            Path tmpMeta = metaFile.resolveSibling(metaFile.getFileName().toString() + ".tmp");
            Files.write(tmpMeta, meta.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmpMeta, metaFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.debug("[ModsSite] Icon cache write failed", e); // кэш необязателен
        }
    }

    private Cached readCache(Path metaFile, Path bodyFile) {
        try {
            if (!Files.isRegularFile(metaFile) || !Files.isRegularFile(bodyFile)) return null;
            JsonObject meta = JsonParser.parseString(Files.readString(metaFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            long storedAt = meta.has("storedAt") && !meta.get("storedAt").isJsonNull()
                    ? meta.get("storedAt").getAsLong() : 0;
            String contentType = meta.has("contentType") && !meta.get("contentType").isJsonNull()
                    ? meta.get("contentType").getAsString() : "";
            if (storedAt <= 0 || contentType == null || !contentType.startsWith("image/")) return null;
            if (System.currentTimeMillis() - storedAt > TTL_MS) return null;
            byte[] body = Files.readAllBytes(bodyFile);
            if (body.length == 0 || body.length > MAX_BYTES) return null;
            return new Cached(contentType, body);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Чистка кэша: просроченные записи и мусорные .tmp, затем мягкий лимит
     * суммарного размера. Вызывается при старте и в регулярном обслуживании.
     */
    public void sweep() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cacheDir)) {
            long now = System.currentTimeMillis();
            List<Path> imgs = new ArrayList<>();
            long total = 0;
            for (Path file : files) {
                String name = file.getFileName().toString();
                try {
                    if (name.endsWith(".tmp")) {
                        if (now - Files.getLastModifiedTime(file).toMillis() > 3600_000L) {
                            Files.deleteIfExists(file);
                        }
                    } else if (name.endsWith(".meta")) {
                        if (now - Files.getLastModifiedTime(file).toMillis() > TTL_MS + 3600_000L) {
                            Files.deleteIfExists(file);
                            Files.deleteIfExists(
                                    file.resolveSibling(name.substring(0, name.length() - 5) + ".img"));
                        }
                    } else if (name.endsWith(".img")) {
                        imgs.add(file);
                        total += Files.size(file);
                    }
                } catch (IOException ignored) {
                }
            }
            if (total > MAX_CACHE_BYTES) {
                imgs.sort(Comparator.comparingLong(this::lastModified));
                for (Path file : imgs) {
                    if (total <= SWEEP_TARGET_BYTES) break;
                    try {
                        long size = Files.size(file);
                        String base = file.getFileName().toString();
                        Files.deleteIfExists(file);
                        Files.deleteIfExists(
                                file.resolveSibling(base.substring(0, base.length() - 4) + ".meta"));
                        total -= size;
                    } catch (IOException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            logger.debug("[ModsSite] Icon cache sweep failed", t);
        }
    }

    private long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
