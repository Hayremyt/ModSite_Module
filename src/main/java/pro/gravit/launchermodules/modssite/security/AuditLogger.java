package pro.gravit.launchermodules.modssite.security;

import pro.gravit.launchermodules.modssite.Utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Журнал аудита в формате JSONL: время, действие, IP, аккаунт, результат, детали.
 * Ротация по размеру (audit.log.jsonl -> audit.log.jsonl.1 ...).
 */
public class AuditLogger {
    private final Path file;
    private final int maxBytes;
    private final int keepFiles;
    private volatile boolean enabled;
    private final Object lock = new Object();

    public AuditLogger(Path dir, String fileName, boolean enabled, int maxFileMb, int keepFiles) {
        this.file = dir.resolve(fileName);
        this.enabled = enabled;
        this.maxBytes = Math.max(1, maxFileMb) * 1024 * 1024;
        this.keepFiles = Math.max(1, keepFiles);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void log(String action, String ip, String login, String result, Map<String, Object> details) {
        if (!enabled) return;
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("ts", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX").format(new Date()));
        entry.put("action", action);
        entry.put("ip", ip == null ? "" : ip);
        entry.put("user", login == null ? "" : login);
        entry.put("result", result == null ? "" : result);
        if (details != null && !details.isEmpty()) entry.put("details", details);
        String line = Utils.GSON_COMPACT.toJson(entry, Map.class);
        synchronized (lock) {
            try {
                rotateIfNeeded();
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write(line);
                    writer.write('\n');
                }
            } catch (IOException e) {
                org.apache.logging.log4j.LogManager.getLogger().error("Audit write failed", e);
            }
        }
    }

    public void log(String action, String ip, String login, String result) {
        log(action, ip, login, result, null);
    }

    private void rotateIfNeeded() throws IOException {
        if (!Files.exists(file) || Files.size(file) < maxBytes) return;
        for (int i = keepFiles - 1; i >= 1; i--) {
            Path from = file.resolveSibling(file.getFileName() + "." + i);
            Path to = file.resolveSibling(file.getFileName() + "." + (i + 1));
            if (Files.exists(from)) {
                Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, file.resolveSibling(file.getFileName() + ".1"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Постраничное чтение последних строк (для панели на сайте). */
    public synchronized List<String> readLast(int offsetFromEnd, int limit) {
        List<String> lines = new ArrayList<>();
        synchronized (lock) {
            if (!Files.exists(file)) return lines;
            try {
                List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
                int total = all.size();
                int from = Math.max(0, total - offsetFromEnd - limit);
                int to = Math.max(0, total - offsetFromEnd);
                for (int i = to - 1; i >= from; i--) {
                    if (!all.get(i).isBlank()) lines.add(all.get(i));
                }
            } catch (IOException e) {
                // ignore
            }
        }
        return lines;
    }

    public Path getFile() {
        return file;
    }
}
