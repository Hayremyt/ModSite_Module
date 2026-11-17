package pro.gravit.launchermodules.modssite.auth;

import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.Utils;
import pro.gravit.utils.helper.SecurityHelper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сессии сайта: токен в HttpOnly-cookie (клиенту), в файле — только SHA-256 хэш токена.
 * У каждой сессии свой CSRF-токен для state-changing запросов.
 */
public class SessionManager {
    public static class Session {
        public String tokenHash;
        public String login;
        public String source;   // "local" | "launcher"
        public String role;     // "admin" | "viewer"
        public String csrf;
        public String ip;
        public long createdAt;
        public long lastSeen;
        public long expiresAt;
        public transient String cookieValue; // только в памяти, не сохраняется
    }

    public static class State {
        public List<Session> sessions = new ArrayList<>();
    }

    private final Path stateFile;
    private final Object saveLock = new Object();
    private final Map<String, Session> byToken = new ConcurrentHashMap<>();
    private volatile ModsSiteConfig config;

    public SessionManager(Path stateFile, ModsSiteConfig config) {
        this.stateFile = stateFile;
        this.config = config;
    }

    public void setConfig(ModsSiteConfig cfg) {
        this.config = cfg;
    }

    public void load() {
        byToken.clear();
        if (java.nio.file.Files.exists(stateFile)) {
            try {
                State loaded = Utils.readJson(stateFile, State.class);
                if (loaded != null && loaded.sessions != null) {
                    long now = System.currentTimeMillis();
                    for (Session s : loaded.sessions) {
                        if (s != null && s.tokenHash != null && s.expiresAt > now) {
                            byToken.put(s.tokenHash, s);
                        }
                    }
                }
            } catch (Exception e) {
                org.apache.logging.log4j.LogManager.getLogger().error("Cannot read sessions", e);
            }
        }
    }

    public void save() {
        synchronized (saveLock) {
            try {
                State state = new State();
                state.sessions = new ArrayList<>(byToken.values());
                Utils.writeJsonAtomic(stateFile, state);
            } catch (IOException e) {
                org.apache.logging.log4j.LogManager.getLogger().error("Cannot save sessions", e);
            }
        }
    }

    /**
     * Создать сессию. Исходный токен (для Set-Cookie) возвращается в tokenOut;
     * на диск он не попадает — только его SHA-256.
     */
    public Session create(String login, String source, String role, String ip, StringBuilder tokenOut) {
        String token = SecurityHelper.randomStringToken() + SecurityHelper.randomStringToken();
        Session session = new Session();
        session.cookieValue = token;
        session.tokenHash = sha256(token);
        session.login = login;
        session.source = source;
        session.role = role;
        session.ip = ip;
        session.csrf = SecurityHelper.randomStringToken();
        long now = System.currentTimeMillis();
        session.createdAt = now;
        session.lastSeen = now;
        session.expiresAt = now + Math.max(5, config.auth.sessionTtlMinutes) * 60_000L;
        int maxPerIp = config.auth.maxSessionsPerIp;
        if (maxPerIp > 0) {
            List<Session> sameIp = new ArrayList<>();
            byToken.values().forEach(s -> {
                if (ip != null && ip.equals(s.ip)) sameIp.add(s);
            });
            sameIp.sort((a, b) -> Long.compare(b.lastSeen, a.lastSeen));
            for (int i = maxPerIp - 1; i < sameIp.size(); i++) {
                byToken.remove(sameIp.get(i).tokenHash);
            }
        }
        byToken.put(session.tokenHash, session);
        save();
        tokenOut.append(token);
        return session;
    }

    public Session get(String token) {
        if (token == null || token.isEmpty()) return null;
        Session session = byToken.get(sha256(token));
        if (session == null) return null;
        if (session.expiresAt <= System.currentTimeMillis()) {
            byToken.remove(session.tokenHash);
            save();
            return null;
        }
        session.lastSeen = System.currentTimeMillis();
        return session;
    }

    public boolean delete(String token) {
        boolean removed = token != null && byToken.remove(sha256(token)) != null;
        if (removed) save();
        return removed;
    }

    public void deleteAllFor(String login) {
        boolean changed = byToken.values().removeIf(s ->
                login != null && login.toLowerCase(Locale.ROOT).equals(
                        s.login == null ? "" : s.login.toLowerCase(Locale.ROOT)));
        if (changed) save();
    }

    /** Удалить ВСЕ сессии (например, при выключении сайта командой mods site off). */
    public void clearAll() {
        boolean changed = !byToken.isEmpty();
        byToken.clear();
        if (changed) save();
    }

    public void purgeExpired() {
        long now = System.currentTimeMillis();
        boolean changed = byToken.values().removeIf(s -> s.expiresAt <= now);
        if (changed) save();
    }

    public List<Session> list() {
        return new ArrayList<>(byToken.values());
    }

    public int count() {
        return byToken.size();
    }

    private static String sha256(String s) {
        byte[] digest = SecurityHelper.digest(SecurityHelper.DigestAlgorithm.SHA256, s);
        return Base64.getEncoder().encodeToString(digest);
    }
}
