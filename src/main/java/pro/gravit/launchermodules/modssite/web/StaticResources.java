package pro.gravit.launchermodules.modssite.web;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import pro.gravit.utils.helper.IOHelper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Раздача статических файлов веб-интерфейса.
 * Источники по приоритету:
 *  1. каталог конфигурации (config/ModsSite/web) — редактируется администратором;
 *  2. встроенные ресурсы jar — источник восстановления (читаются напрямую
 *     из файла jar, минуя classloader; см. {@link #bundledBytes}).
 * Доступен только белый список имён — directory traversal исключён.
 */
public final class StaticResources {
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "application/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "woff2", "font/woff2");
    private static final String ALLOWED_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789._-";

    private static final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    /**
     * Внешний каталог веб-файлов (обычно config/ModsSite/web).
     * Файлы из него раздаются с приоритетом над ресурсами jar: так администратор
     * может заменить/добавить статику без пересборки модуля, а также вылечить
     * jar, собранный без ресурсов. Перечитывается на каждый запрос — правки
     * применяются сразу, кэшировать незачем (файлы маленькие, диск локальный).
     */
    private static volatile Path externalDir;

    private StaticResources() {
    }

    /** Включить раздачу из внешнего каталога (null — отключить). */
    public static void setExternalDir(Path dir) {
        externalDir = dir;
    }

    /** Проверка имени файла (без путей, без спецсимволов). */
    public static boolean isAllowedName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        if (name.startsWith(".") || name.contains("/")) return false;
        String lower = name.toLowerCase();
        for (char c : lower.toCharArray()) {
            if (ALLOWED_CHARS.indexOf(c) < 0) return false;
        }
        return CONTENT_TYPES.containsKey(extension(lower));
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    public static DefaultFullHttpResponse load(String name) {
        if (!isAllowedName(name)) {
            return null;
        }
        byte[] bytes = readExternal(name);
        if (bytes == null) {
            bytes = bundledBytes(name);
        }
        if (bytes == null) {
            return null;
        }
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPES.get(extension(name.toLowerCase())));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
        return response;
    }

    private static byte[] readResource(String name) {
        try (InputStream input = StaticResources.class.getResourceAsStream("/web/" + name)) {
            if (input == null) return null;
            return input.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Прочитать файл из внешнего каталога; null — файла нет или каталог не задан.
     * Имя уже проверено isAllowedName (без путей и спецсимволов), нормализация —
     * дополнительная защита от обхода каталога.
     */
    private static byte[] readExternal(String name) {
        Path dir = externalDir;
        if (dir == null) return null;
        try {
            Path file = dir.resolve(name).normalize();
            if (!file.startsWith(dir) || !Files.isRegularFile(file)) return null;
            byte[] bytes = Files.readAllBytes(file);
            return bytes.length > 0 ? bytes : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ======================= Встроенные ресурсы jar =======================

    private static volatile Path codeSourceJar;
    private static volatile boolean codeSourceResolved = false;
    private static final Map<String, byte[]> jarDirectCache = new ConcurrentHashMap<>();

    /**
     * Все доступные встроенные файлы web/* из jar (отсутствующие не включаются).
     * Используется для синхронизации каталога конфигурации при старте.
     */
    public static Map<String, byte[]> bundledAll() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String name : WebResourcesManager.UI_FILES) {
            byte[] bytes = bundledBytes(name);
            if (bytes != null) result.put(name, bytes);
        }
        return result;
    }

    /**
     * Прочитать встроенный web-файл из jar модуля.
     * <p>Основной способ — ПРЯМОЕ чтение файла jar (ZipFile по код-сорсу класса):
     * не зависит от причуд classloader'ов в разных сборках LaunchServer
     * (известны случаи, когда getResourceAsStream модуля не видит ресурсы jar,
     * хотя классы из него грузятся). Если классы загружаются не из jar
     * (запуск из каталога классов, IDE) — откат к getResourceAsStream.
     * Результат кэшируется.
     */
    public static byte[] bundledBytes(String name) {
        if (!isAllowedName(name)) return null;
        resolveCodeSource();
        Path jar = codeSourceJar;
        if (jar != null) {
            byte[] direct = jarDirectCache.computeIfAbsent(name, n -> readFromJarFile(jar, "web/" + n));
            if (direct != null) return direct;
        }
        return cache.computeIfAbsent(name, StaticResources::readFromClassLoader);
    }

    private static void resolveCodeSource() {
        if (codeSourceResolved) return;
        synchronized (StaticResources.class) {
            if (codeSourceResolved) return;
            try {
                Path location = java.nio.file.Paths.get(StaticResources.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI());
                codeSourceJar = Files.isRegularFile(location) ? location : null;
            } catch (Throwable t) {
                codeSourceJar = null; // код-сорс не файл (каталог классов/динамическая генерация)
            }
            codeSourceResolved = true;
        }
    }

    /** Прямое чтение записи из jar-файла, минуя classloader. */
    private static byte[] readFromJarFile(Path jar, String entryName) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(entryName);
            if (entry == null || entry.isDirectory()) return null;
            byte[] bytes = zip.getInputStream(entry).readAllBytes();
            return bytes.length > 0 ? bytes : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Резервный способ — как обычный ресурс через classloader. */
    private static byte[] readFromClassLoader(String name) {
        try (InputStream input = StaticResources.class.getResourceAsStream("/web/" + name)) {
            if (input == null) return null;
            return input.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean send(ChannelHandlerContext ctx, String name) {
        DefaultFullHttpResponse response = load(name);
        if (response == null) {
            return false;
        }
        HttpVersion version = response.protocolVersion();
        ctx.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        return true;
    }

    public static byte[] readAll(String name) {
        try (InputStream input = StaticResources.class.getResourceAsStream("/web/" + name)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static io.netty.buffer.ByteBuf empty() {
        return Unpooled.wrappedBuffer(IOHelper.encode(""));
    }
}
