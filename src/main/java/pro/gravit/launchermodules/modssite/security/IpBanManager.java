package pro.gravit.launchermodules.modssite.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.Utils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Защита от подбора пароля:
 *  - учёт неудачных попыток по IP с нарастающими сроками бана (banSchedule);
 *  - уровень нарушений не сбрасывается после снятия бана: повторное нарушение — срок длиннее;
 *  - whitelist — IP/CIDR, которые никогда не блокируются;
 *  - blacklist — IP/CIDR, которые всегда отклоняются;
 *  - блокировка по логину (accountLock) независимо от IP.
 * Состояние сохраняется в ipfilter.json (переживает рестарт).
 */
public class IpBanManager {
    private static final Logger logger = LogManager.getLogger(IpBanManager.class);

    public static class IpRecord {
        public int attempts;
        public long windowStart;
        public int offenseLevel;
        public long bannedUntil;
        public long lastFail;
        public long totalFails;
    }

    public static class AccountRecord {
        public int fails;
        public long windowStart;
        public int offenseLevel;
        public long lockedUntil;
        public long lastFail;
    }

    public static class State {
        public List<String> whitelist = new ArrayList<>();
        public List<String> blacklist = new ArrayList<>();
        public Map<String, IpRecord> ips = new ConcurrentHashMap<>();
        public Map<String, AccountRecord> accounts = new ConcurrentHashMap<>();
    }

    public static class CheckResult {
        public enum Type {ALLOW, BANNED, BLACKLISTED}

        public final Type type;
        public final long untilEpochMs;

        public CheckResult(Type type, long untilEpochMs) {
            this.type = type;
            this.untilEpochMs = untilEpochMs;
        }

        public boolean allowed() {
            return type == Type.ALLOW;
        }
    }

    private final Path stateFile;
    private final Object saveLock = new Object();
    private volatile State state = new State();
    private volatile ModsSiteConfig config;

    /**
     * Троттлинг записи состояния после неудачных попыток входа: каждая попытка
     * раньше перезаписывала ВЕСЬ ipfilter.json — при пределе 50 000 записей
     * это ~МБ на каждую попытку, и флуд логинами превращался в disk-DoS.
     * Записи не чаще раза в секунду; отложенное состояние досохраняется
     * плановым tick() (раз в 5 минут).
     */
    private static final long SAVE_THROTTLE_MS = 1000;
    private volatile long lastThrottledSaveAt;
    private volatile boolean dirty;

    /**
     * Верхний предел числа отслеживаемых IP/логинов. Защита от переполнения
     * памяти и ipfilter.json при доверии к X-Forwarded-For: атакующий может
     * подавать каждый запрос с новым выдуманным IP/логином — без предела
     * карта (и сохраняемый файл) росли бы бесконечно. Сверх предела новые
     * записи не создаются (уже отслеживаемые продолжают работать: баны,
     * уровни, разбан). Чистка устаревших — tick() (раз в 5 минут).
     */
    private static final int MAX_TRACKED_ENTRIES = 50_000;

    public IpBanManager(Path stateFile, ModsSiteConfig config) {
        this.stateFile = stateFile;
        this.config = config;
    }

    public void setConfig(ModsSiteConfig cfg) {
        this.config = cfg;
    }

    public void load() {
        if (java.nio.file.Files.exists(stateFile)) {
            try {
                State loaded = Utils.readJson(stateFile, State.class);
                if (loaded != null) {
                    if (loaded.ips == null) loaded.ips = new ConcurrentHashMap<>();
                    if (loaded.accounts == null) loaded.accounts = new ConcurrentHashMap<>();
                    if (loaded.whitelist == null) loaded.whitelist = new ArrayList<>();
                    if (loaded.blacklist == null) loaded.blacklist = new ArrayList<>();
                    state = loaded;
                }
            } catch (Exception e) {
                logger.error("Cannot read {}, using empty state", stateFile, e);
            }
        }
        // Списки из конфига имеют приоритет и добавляются к сохранённым
        mergeList(state.whitelist, config.whitelist);
        mergeList(state.blacklist, config.blacklist);
        save();
    }

    private static void mergeList(List<String> target, List<String> source) {
        if (source == null) return;
        for (String s : source) {
            String v = s.trim();
            if (!v.isEmpty() && !target.contains(v)) target.add(v);
        }
    }

    public void save() {
        synchronized (saveLock) {
            try {
                Utils.writeJsonAtomic(stateFile, state);
            } catch (IOException e) {
                logger.error("Cannot save {}", stateFile, e);
            }
        }
    }

    /** Сохранение с троттлингом (горячий путь учёта неудачных попыток). */
    private void saveThrottled() {
        long now = System.currentTimeMillis();
        if (now - lastThrottledSaveAt < SAVE_THROTTLE_MS) {
            dirty = true;
            return;
        }
        lastThrottledSaveAt = now;
        dirty = false;
        save();
    }

    // ======================= Проверки =======================

    /** Проверить доступ IP. */
    public CheckResult checkIp(String ip) {
        if (isListed(state.blacklist, ip)) {
            return new CheckResult(CheckResult.Type.BLACKLISTED, 0);
        }
        if (!config.security.enabled || isListed(state.whitelist, ip)) {
            return new CheckResult(CheckResult.Type.ALLOW, 0);
        }
        IpRecord record = state.ips.get(ip);
        if (record != null && record.bannedUntil > System.currentTimeMillis()) {
            return new CheckResult(CheckResult.Type.BANNED, record.bannedUntil);
        }
        return new CheckResult(CheckResult.Type.ALLOW, 0);
    }

    /** Проверить блокировку логина. 0 — не заблокирован, иначе epoch ms до снятия. */
    public long accountLockUntil(String login) {
        if (login == null || !config.security.accountLock.enabled) return 0;
        AccountRecord record = state.accounts.get(login.toLowerCase());
        if (record == null) return 0;
        return Math.max(0, record.lockedUntil - System.currentTimeMillis()) > 0 ? record.lockedUntil : 0;
    }

    // ======================= События входа =======================

    /**
     * Зафиксировать неудачную попытку входа. Возвращает срок бана IP, если он наступил (0 — нет).
     */
    public long onLoginFailed(String ip, String login) {
        long now = System.currentTimeMillis();
        long banMs = 0;
        if (config.security.enabled && !isListed(state.whitelist, ip)) {
            IpRecord record = state.ips.get(ip);
            // Предел: новые IP не отслеживаем (но учёт логина ниже продолжается),
            // пока устаревшие не вычистит tick()
            if (record == null && state.ips.size() < MAX_TRACKED_ENTRIES) {
                record = state.ips.computeIfAbsent(ip, k -> new IpRecord());
            }
            if (record != null) {
                if (now - record.windowStart > windowMs()) {
                    record.windowStart = now;
                    record.attempts = 0;
                }
                record.attempts++;
                record.lastFail = now;
                record.totalFails++;
                if (record.bannedUntil > now) {
                    // Уже в бане: не продлеваем, просто игнорируем
                } else if (record.attempts >= config.security.maxAttempts) {
                    long duration = scheduleMs(config.security.banScheduleMinutes, record.offenseLevel);
                    record.offenseLevel++;
                    record.bannedUntil = now + duration;
                    record.attempts = 0;
                    record.windowStart = now;
                    banMs = record.bannedUntil;
                    logger.warn("IP {} banned for {} minutes (offense level {})",
                            ip, duration / 60000L, record.offenseLevel);
                }
            }
        }
        if (login != null && config.security.accountLock.enabled) {
            String key = login.toLowerCase();
            AccountRecord record = state.accounts.get(key);
            // Предел — как для IP выше
            if (record == null && state.accounts.size() < MAX_TRACKED_ENTRIES) {
                record = state.accounts.computeIfAbsent(key, k -> new AccountRecord());
            }
            if (record != null) {
                if (now - record.windowStart > accountWindowMs()) {
                    record.windowStart = now;
                    record.fails = 0;
                }
                record.fails++;
                record.lastFail = now;
                if (record.lockedUntil <= now && record.fails >= config.security.accountLock.maxFails) {
                    long duration = scheduleMs(config.security.accountLock.lockScheduleMinutes, record.offenseLevel);
                    record.offenseLevel++;
                    record.lockedUntil = now + duration;
                    record.fails = 0;
                    logger.warn("Account '{}' locked for {} minutes (offense level {})",
                            login, duration / 60000L, record.offenseLevel);
                }
            }
        }
        saveThrottled();
        return banMs;
    }

    /** Успешный вход: сбрасываем счётчики попыток (уровень нарушений сохраняется). */
    public void onLoginSuccess(String ip, String login) {
        IpRecord record = state.ips.get(ip);
        if (record != null) {
            record.attempts = 0;
            record.windowStart = System.currentTimeMillis();
        }
        if (login != null) {
            AccountRecord account = state.accounts.remove(login.toLowerCase());
            if (account != null && account.lockedUntil > System.currentTimeMillis()) {
                // Не разблокируем принудительно залоченный аккаунт успешным входом (его и не было)
                state.accounts.put(login.toLowerCase(), account);
            }
        }
        save();
    }

    // ======================= Периодическая чистка =======================

    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, IpRecord> e : state.ips.entrySet()) {
            IpRecord r = e.getValue();
            boolean banExpired = r.bannedUntil > 0 && r.bannedUntil <= now;
            boolean stale = now - Math.max(r.lastFail, r.bannedUntil) > 30L * 24 * 3600 * 1000; // 30 дней без событий
            if (banExpired && config.security.levelDecayHours > 0) {
                long decayMs = config.security.levelDecayHours * 3600_000L;
                if (now - r.bannedUntil > decayMs) {
                    r.offenseLevel = 0; // Долгое «чистое» время — уровень сброшен
                }
            }
            if (stale && r.offenseLevel == 0) {
                state.ips.remove(e.getKey());
                changed = true;
            }
        }
        for (Map.Entry<String, AccountRecord> e : state.accounts.entrySet()) {
            AccountRecord r = e.getValue();
            boolean stale = now - Math.max(r.lastFail, r.lockedUntil) > 30L * 24 * 3600 * 1000;
            if (stale && (r.lockedUntil <= now)) {
                state.accounts.remove(e.getKey());
                changed = true;
            }
        }
        // Досохранить то, что отложил троттлинг (даже если сами записи не менялись)
        if (dirty) {
            dirty = false;
            changed = true;
        }
        if (changed) save();
    }

    // ======================= Управление списками =======================

    public boolean whitelist(String ip) {
        return modifyList(state.whitelist, ip, true);
    }

    public boolean blacklist(String ip) {
        return modifyList(state.blacklist, ip, true);
    }

    public boolean unwhitelist(String ip) {
        return modifyList(state.whitelist, ip, false);
    }

    public boolean unblacklist(String ip) {
        return modifyList(state.blacklist, ip, false);
    }

    public boolean unban(String ip) {
        IpRecord record = state.ips.get(ip);
        boolean result = false;
        if (record != null) {
            record.bannedUntil = 0;
            record.attempts = 0;
            result = true;
        }
        save();
        return result;
    }

    public boolean unlockAccount(String login) {
        if (login == null) return false;
        boolean result = state.accounts.remove(login.toLowerCase()) != null;
        save();
        return result;
    }

    public List<String> getWhitelist() {
        return new ArrayList<>(state.whitelist);
    }

    public List<String> getBlacklist() {
        return new ArrayList<>(state.blacklist);
    }

    /** Активные баны: ip -> [bannedUntil, offenseLevel, totalFails]. */
    public Map<String, long[]> getActiveBans() {
        Map<String, long[]> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        state.ips.forEach((ip, r) -> {
            if (r.bannedUntil > now) {
                result.put(ip, new long[]{r.bannedUntil, r.offenseLevel, r.totalFails});
            }
        });
        return result;
    }

    public Map<String, long[]> getActiveAccountLocks() {
        Map<String, long[]> result = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        state.accounts.forEach((login, r) -> {
            if (r.lockedUntil > now) {
                result.put(login, new long[]{r.lockedUntil, r.offenseLevel, r.fails});
            }
        });
        return result;
    }

    private boolean modifyList(List<String> list, String ip, boolean add) {
        String v = ip == null ? "" : ip.trim();
        if (!Utils.isValidIpOrCidr(v)) return false;
        if (add) {
            if (!list.contains(v)) list.add(v);
        } else {
            // Удаляем только из своего списка и соответствующего ему списка
            // конфига: unwhitelist не должен трогать blacklist (и наоборот) —
            // иначе IP, случайно оказавшийся в обоих конфиг-списках,
            // вычищался из ОБОИХ при удалении из одного
            boolean white = list == state.whitelist;
            list.removeIf(v::equals);
            (white ? config.whitelist : config.blacklist).removeIf(v::equals);
        }
        save();
        return true;
    }

    // ======================= Вспомогательные =======================

    private boolean isListed(List<String> list, String ip) {
        for (String entry : list) {
            if (matches(entry, ip)) return true;
        }
        return false;
    }

    /** Точное совпадение IP, CIDR-подсеть или префиксная запись. */
    public static boolean matches(String pattern, String ip) {
        pattern = pattern.trim();
        if (pattern.isEmpty()) return false;
        if (pattern.equals(ip)) return true;
        // IP-литералы сравниваем напрямую (getByName на НЕ-литерале —
        // например, значении из доверенного X-Forwarded-For — полез бы в DNS
        // и заблокировал поток обработки запросов на секунды)
        if (!isIpLiteral(ip)) return false;
        if (pattern.contains("/")) {
            try {
                String[] parts = pattern.split("/");
                if (!isIpLiteral(parts[0])) return false;
                byte[] patternBytes = InetAddress.getByName(parts[0]).getAddress();
                int prefix = Integer.parseInt(parts[1]);
                byte[] ipBytes = InetAddress.getByName(ip).getAddress();
                if (patternBytes.length != ipBytes.length) return false;
                int fullBytes = prefix / 8;
                int remBits = prefix % 8;
                for (int i = 0; i < fullBytes && i < patternBytes.length; i++) {
                    if (patternBytes[i] != ipBytes[i]) return false;
                }
                if (remBits > 0 && fullBytes < patternBytes.length) {
                    int mask = 0xFF << (8 - remBits);
                    if ((patternBytes[fullBytes] & mask) != (ipBytes[fullBytes] & mask)) return false;
                }
                return true;
            } catch (UnknownHostException | NumberFormatException | ArrayIndexOutOfBoundsException e) {
                return false;
            }
        }
        // Префиксная запись: "192.168." или "10.1."
        if (pattern.endsWith(".") && ip.startsWith(pattern)) return true;
        return false;
    }

    /**
     * Строка — литерал IP-адреса (IPv4 или IPv6, включая
     * IPv4-отображённый "::ffff:1.2.3.4")? Только для литералов вызывается
     * InetAddress.getByName — всё прочее (мусор из заголовков) без DNS
     * считается несовпадением.
     */
    static boolean isIpLiteral(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) return false;
        if (s.indexOf(':') < 0) {
            // IPv4: четыре десятичных группы
            String[] parts = s.split("\\.", -1);
            if (parts.length != 4) return false;
            for (String part : parts) {
                if (part.isEmpty() || part.length() > 3) return false;
                for (char c : part.toCharArray()) {
                    if (c < '0' || c > '9') return false;
                }
            }
            return true;
        }
        // IPv6: hex-группы, ':' и точки (IPv4-отображённые адреса)
        for (char c : s.toCharArray()) {
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex && c != ':' && c != '.') return false;
        }
        return true;
    }

    private long windowMs() {
        return Math.max(1, config.security.windowMinutes) * 60_000L;
    }

    private long accountWindowMs() {
        return Math.max(1, config.security.accountLock.windowMinutes) * 60_000L;
    }

    static long scheduleMs(List<Integer> schedule, int level) {
        if (schedule == null || schedule.isEmpty()) return 10 * 60_000L;
        int index = Math.min(Math.max(level, 0), schedule.size() - 1);
        return Math.max(1, schedule.get(index)) * 60_000L;
    }

    /** Валидация адреса (для теста). */
    static boolean isInetAddress(String s) {
        try {
            InetAddress.getByName(s);
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    public static InetSocketAddress anyAddress() {
        return new InetSocketAddress(0);
    }
}
