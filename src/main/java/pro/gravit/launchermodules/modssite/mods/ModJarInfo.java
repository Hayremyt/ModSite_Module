package pro.gravit.launchermodules.modssite.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Чтение идентификатора и человекочитаемого имени мода прямо из jar-файла.
 *
 * Поддерживаемые форматы метаданных (в порядке приоритета):
 *  - fabric.mod.json   (Fabric):        {"id": "...", "name": "..."}
 *  - quilt.mod.json    (Quilt):         {"quilt_loader": {"id": "...", "metadata": {"name": "..."}}}
 *  - META-INF/mods.toml (Forge 1.13+/NeoForge): modId = "..." / displayName = "..."
 *  - mcmod.info        (Forge <=1.12):  [{"modid": "...", "name": "..."}]
 *
 * Любая ошибка (файл отсутствует, не zip, битый json) -> null; метод никогда
 * не бросает исключений и не влияет на основной поток установки.
 */
public final class ModJarInfo {

    /** Идентификатор мода (modId) и его название из метаданных jar. */
    public static final class ModMeta {
        public final String id;
        public final String name;

        public ModMeta(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final Pattern TOML_MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern TOML_DISPLAY_NAME = Pattern.compile("(?m)^\\s*displayName\\s*=\\s*[\"']([^\"']+)[\"']");

    private ModJarInfo() {
    }

    /** Прочитать метаданные мода из jar; null, если прочитать не удалось. */
    public static ModMeta read(Path jar) {
        if (jar == null) return null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ModMeta meta = readFabric(zip);
            if (meta != null) return meta;
            meta = readQuilt(zip);
            if (meta != null) return meta;
            meta = readForgeToml(zip);
            if (meta != null) return meta;
            return readLegacyMcmod(zip);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static ModMeta readFabric(ZipFile zip) throws IOException {
        String text = readEntry(zip, "fabric.mod.json");
        if (text == null) return null;
        JsonObject root = parseObject(text);
        if (root == null) return null;
        return new ModMeta(optString(root, "id"), optString(root, "name"));
    }

    private static ModMeta readQuilt(ZipFile zip) throws IOException {
        String text = readEntry(zip, "quilt.mod.json");
        if (text == null) return null;
        JsonObject root = parseObject(text);
        if (root == null) return null;
        JsonObject loader = optObject(root, "quilt_loader");
        if (loader == null) return null;
        String id = optString(loader, "id");
        String name = null;
        JsonObject metadata = optObject(loader, "metadata");
        if (metadata != null) {
            name = optString(metadata, "name");
        }
        return new ModMeta(id, name);
    }

    private static ModMeta readForgeToml(ZipFile zip) throws IOException {
        String text = readEntry(zip, "META-INF/mods.toml");
        if (text == null) {
            // NeoForge 1.20.5+ тоже использует META-INF/neoforge.mods.toml
            text = readEntry(zip, "META-INF/neoforge.mods.toml");
        }
        if (text == null) return null;
        Matcher id = TOML_MOD_ID.matcher(text);
        if (!id.find()) return null;
        Matcher name = TOML_DISPLAY_NAME.matcher(text);
        return new ModMeta(id.group(1).trim(), name.find() ? name.group(1).trim() : null);
    }

    private static ModMeta readLegacyMcmod(ZipFile zip) throws IOException {
        String text = readEntry(zip, "mcmod.info");
        if (text == null) return null;
        // Иногда файл начинается с BOM или комментариев — вырезаем до первой '['
        int start = text.indexOf('[');
        if (start < 0) return null;
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(text.substring(start));
        } catch (RuntimeException e) {
            return null;
        }
        if (!parsed.isJsonArray()) return null;
        JsonArray array = parsed.getAsJsonArray();
        if (array.isEmpty() || !array.get(0).isJsonObject()) return null;
        JsonObject root = array.get(0).getAsJsonObject();
        return new ModMeta(optString(root, "modid"), optString(root, "name"));
    }

    private static String readEntry(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null || entry.isDirectory()) return null;
        byte[] buffer = new byte[(int) Math.min(entry.getSize(), 4L * 1024 * 1024)];
        int filled = 0;
        try (var input = zip.getInputStream(entry)) {
            if (input == null) return null;
            while (filled < buffer.length) {
                int read = input.read(buffer, filled, buffer.length - filled);
                if (read < 0) break;
                filled += read;
            }
        }
        return new String(buffer, 0, filled, StandardCharsets.UTF_8);
    }

    private static JsonObject parseObject(String text) {
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        if (value == null || value.isJsonNull()) return null;
        String s = value.getAsString();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static JsonObject optObject(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /**
     * Привести произвольную строку к безопасному идентификатору записи
     * updateOptional: [a-z0-9._-], без крайних точек/дефисов, не длиннее 64.
     */
    public static String sanitizeId(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('-');
            }
        }
        String result = sb.toString();
        // Схлопнуть повторы дефисов, срезать крайние разделители
        result = result.replaceAll("-{2,}", "-");
        result = stripEdges(result);
        if (result.length() > 64) {
            result = stripEdges(result.substring(0, 64));
        }
        return result.isEmpty() ? null : result;
    }

    private static String stripEdges(String s) {
        int start = 0, end = s.length();
        while (start < end && (s.charAt(start) == '-' || s.charAt(start) == '.')) start++;
        while (end > start && (s.charAt(end - 1) == '-' || s.charAt(end - 1) == '.')) end--;
        return s.substring(start, end);
    }
}
