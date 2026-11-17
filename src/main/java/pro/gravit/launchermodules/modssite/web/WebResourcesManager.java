package pro.gravit.launchermodules.modssite.web;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Синхронизация веб-файлов интерфейса с каталогом конфигурации
 * ({@code config/ModsSite/web}).
 *
 * <p>Файлы интерфейса живут на диске, а не в jar: администратор правит стили
 * и разметку напрямую, изменения применяются сразу (перезапуск не нужен).
 * Jar служит источником восстановления.
 *
 * <p>Правила синхронизации (при каждом старте модуля):
 * <ul>
 *   <li>файла нет на диске — записывается из jar (в т.ч. восстановление
 *       после удаления администратором);</li>
 *   <li>файл на диске не менялся с прошлой записи (хэш совпадает с маркером) —
 *       перезаписывается версией из jar: после обновления модуля интерфейс
 *       обновляется автоматически;</li>
 *   <li>файл изменён администратором — изменения сохраняются, файл не трогается;</li>
 *   <li>файла нет ни в jar, ни на диске — попадает в {@code missingEverywhere},
 *       модуль пишет ERROR (интерфейс будет неполным).</li>
 * </ul>
 *
 * <p>Состояние хранится в маркере {@value #MARKER_FILE}: версия модуля и хэши
 * записанных файлов. Маркер не раздаётся как статика (имя начинается с точки).
 * Принудительная перезапись всего — {@link #forceRestore} (команда
 * {@code mods web restore}).
 */
public final class WebResourcesManager {
    /** Имена файлов интерфейса, синхронизируемых с каталогом конфигурации. */
    public static final List<String> UI_FILES =
            List.of("index.html", "style.css", "app.js", "i18n.js", "favicon.svg");
    public static final String MARKER_FILE = ".web-resources.json";

    private static final Gson gson = new Gson();

    /** Маркер: версия модуля, записавшего файлы, и их хэши. */
    private static final class Marker {
        @SerializedName("version")
        String version;
        @SerializedName("hashes")
        Map<String, String> hashes = new LinkedHashMap<>();
    }

    /** Результат синхронизации — для лога при старте и команды mods web. */
    public static final class Report {
        public final Path dir;
        public final String markerVersion;   // версия до синхронизации (null — маркера не было)
        public final String currentVersion;
        public final List<String> extracted = new ArrayList<>();
        public final List<String> updated = new ArrayList<>();
        public final List<String> kept = new ArrayList<>();
        public final List<String> missingInBundle = new ArrayList<>();
        public final List<String> missingEverywhere = new ArrayList<>();

        Report(Path dir, String markerVersion, String currentVersion) {
            this.dir = dir;
            this.markerVersion = markerVersion;
            this.currentVersion = currentVersion;
        }

        public boolean hasProblems() {
            return !missingEverywhere.isEmpty();
        }
    }

    private WebResourcesManager() {
    }

    /**
     * Согласовать каталог конфигурации с содержимым jar.
     * Идемпотентно; безопасно для правок администратора (см. правила в шапке).
     */
    public static Report sync(Path dir, String currentVersion, Map<String, byte[]> bundle) {
        return syncInternal(dir, currentVersion, bundle, false);
    }

    /**
     * Принудительно перезаписать ВСЕ файлы из jar (правки администратора будут
     * потеряны). Вызывается командой {@code mods web restore}.
     */
    public static Report forceRestore(Path dir, String currentVersion, Map<String, byte[]> bundle) {
        return syncInternal(dir, currentVersion, bundle, true);
    }

    private static Report syncInternal(Path dir, String currentVersion,
                                       Map<String, byte[]> bundle, boolean force) {
        Report report = new Report(dir, readMarkerVersion(dir), currentVersion);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // Каталог недоступен — раздача продолжится из jar; сообщим вызывающему коду
            report.missingEverywhere.addAll(bundle.isEmpty() ? UI_FILES : filter(bundle));
            return report;
        }
        Marker prev = readMarker(dir);
        Marker next = new Marker();
        next.version = currentVersion;
        for (String name : UI_FILES) {
            byte[] bundled = bundle.get(name);
            Path file = dir.resolve(name);
            if (bundled == null || bundled.length == 0) {
                report.missingInBundle.add(name);
                if (!Files.isRegularFile(file)) {
                    report.missingEverywhere.add(name);
                }
                continue;
            }
            String bundledHash = sha256(bundled);
            next.hashes.put(name, bundledHash);
            boolean exists = Files.isRegularFile(file);
            if (force || !exists) {
                if (writeFile(file, bundled)) {
                    report.extracted.add(name);
                } else {
                    report.missingEverywhere.add(name);
                }
                continue;
            }
            String diskHash = hashFile(file);
            // «Не менялся админом» = на диске ровно то, что модуль записывал в
            // прошлый раз (хэш диска совпадает с маркером). Тогда можно смело
            // перезаписать новой версией из jar. Если админ правил — не трогаем.
            boolean unmodifiedByAdmin = prev != null
                    && diskHash != null
                    && diskHash.equals(prev.hashes.get(name));
            if (unmodifiedByAdmin) {
                if (!bundledHash.equals(diskHash) && writeFile(file, bundled)) {
                    report.updated.add(name);
                }
            } else {
                // Файл изменён администратором (или маркера нет) — не трогаем
                report.kept.add(name);
            }
        }
        writeMarker(dir, next);
        return report;
    }

    private static List<String> filter(Map<String, byte[]> bundle) {
        List<String> result = new ArrayList<>();
        for (String name : UI_FILES) {
            byte[] bytes = bundle.get(name);
            if (bytes == null || bytes.length == 0) result.add(name);
        }
        return result;
    }

    private static Marker readMarker(Path dir) {
        Path marker = dir.resolve(MARKER_FILE);
        if (!Files.isRegularFile(marker)) return null;
        try {
            String text = Files.readString(marker, StandardCharsets.UTF_8);
            Marker parsed = gson.fromJson(text, Marker.class);
            return parsed != null ? parsed : null;
        } catch (IOException | RuntimeException e) {
            return null; // битый маркер = как отсутствующий: права администратора уважаем
        }
    }

    private static String readMarkerVersion(Path dir) {
        Marker marker = readMarker(dir);
        return marker == null ? null : marker.version;
    }

    private static void writeMarker(Path dir, Marker marker) {
        try {
            Files.writeString(dir.resolve(MARKER_FILE),
                    gson.toJson(marker), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // Без маркера синхронизация станет консервативнее (не трогаем файлы)
        }
    }

    private static boolean writeFile(Path file, byte[] bytes) {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            try {
                Files.write(file, bytes); // ATOMIC_MOVE может не поддерживаться — обычная запись
                return true;
            } catch (IOException e2) {
                return false;
            }
        }
    }

    private static String hashFile(Path file) {
        try {
            return sha256(Files.readAllBytes(file));
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest(data)) {
                sb.append(Character.forDigit((b >> 4) & 15, 16));
                sb.append(Character.forDigit(b & 15, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(data.length); // SHA-256 есть в любом JDK; недостижимо
        }
    }
}
