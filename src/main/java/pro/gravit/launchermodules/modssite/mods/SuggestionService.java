package pro.gravit.launchermodules.modssite.mods;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchermodules.modssite.Utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Система предложений: наблюдатель находит проект в поиске и предлагает его
 * в сборку; администратор во вкладке «Предложения» скачивает его в модпак
 * или отклоняет одной кнопкой.
 *
 * <p>Хранение — config/ModsSite/suggestions.json, только ОЖИДАЮЩИЕ решения
 * записи. Решение (одобрено/отклонено) удаляет запись из очереди: отклонённое
 * можно предложить снова (спам отсекается лимитом и баном предложения),
 * одобренное уже лежит в модпаке. Персональные ограничения — в конфиге
 * (mods.suggestions.maxPerUser, mods.suggestions.bannedUsers).
 */
public class SuggestionService {
    private static final Logger logger = LogManager.getLogger(SuggestionService.class);

    /** Предел числа ожидающих записей во всей очереди (страховка от раздувания). */
    private static final int MAX_PENDING_TOTAL = 1000;
    /** Предел длины строковых полей записи. */
    private static final int MAX_FIELD = 300;

    /** Одно предложение (ожидает решения администратора). */
    public static class Suggestion {
        /** Уникальный id (uuid). */
        public String id;
        /** Логин предложившего (lowercase). */
        public String login;
        /** Тип контента: mod | resourcepack | shader. */
        public String kind;
        /** Источник проекта: modrinth | curseforge. */
        public String source;
        /** ID проекта в источнике. */
        public String projectId;
        /** Слаг (для читаемости и дедупликации). */
        public String slug;
        /** Название проекта. */
        public String title;
        /** Иконка (для списка админа). */
        public String iconUrl;
        /** Профиль, в чью сборку предложено. */
        public String profileDir;
        /** Заголовок профиля на момент предложения (диагностика). */
        public String profileTitle;
        /** Время создания (epoch ms). */
        public long createdAt;
    }

    /** Состояние файла. */
    public static class State {
        public List<Suggestion> suggestions = new ArrayList<>();
    }

    private final Path file;
    private final Object lock = new Object();
    private State state = new State();

    public SuggestionService(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                State loaded = Utils.readJson(file, State.class);
                if (loaded != null && loaded.suggestions != null) {
                    state = loaded;
                }
            }
        } catch (Exception e) {
            logger.error("[ModsSite] Cannot read suggestions file — starting empty", e);
            state = new State();
        }
    }

    private void saveLocked() {
        try {
            Utils.writeJsonAtomic(file, state);
        } catch (IOException e) {
            logger.error("[ModsSite] Cannot save suggestions", e);
        }
    }

    /** Все ожидающие предложения (свежие сверху). */
    public List<Suggestion> pending() {
        synchronized (lock) {
            List<Suggestion> copy = new ArrayList<>(state.suggestions);
            copy.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
            return copy;
        }
    }

    /** Ожидающие предложения конкретного пользователя (свежие сверху). */
    public List<Suggestion> pendingOf(String login) {
        String lower = normalizeLogin(login);
        List<Suggestion> result = new ArrayList<>();
        synchronized (lock) {
            for (Suggestion suggestion : state.suggestions) {
                if (suggestion != null && lower.equals(suggestion.login)) {
                    result.add(suggestion);
                }
            }
        }
        result.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return result;
    }

    /** Число ожидающих предложений пользователя. */
    public int pendingCount(String login) {
        return pendingOf(login).size();
    }

    /** Есть ли уже ожидающее предложение ЭТОГО проекта в ЭТОТ профиль (от кого угодно). */
    public boolean isDuplicate(String profileDir, String source, String projectId) {
        if (profileDir == null || source == null || projectId == null) return false;
        String profile = profileDir.trim();
        String src = source.trim();
        String project = projectId.trim();
        synchronized (lock) {
            for (Suggestion suggestion : state.suggestions) {
                if (suggestion != null && profile.equals(suggestion.profileDir)
                        && src.equals(suggestion.source) && project.equals(suggestion.projectId)) {
                    return true;
                }
            }
        }
        return false;
    }

    public Suggestion find(String id) {
        if (id == null || id.isBlank()) return null;
        synchronized (lock) {
            for (Suggestion suggestion : state.suggestions) {
                if (suggestion != null && id.equals(suggestion.id)) {
                    return suggestion;
                }
            }
        }
        return null;
    }

    /** Создать предложение. Атомарно идемпотентно: если точно такое же
     *  (профиль+источник+проект) уже ожидает — возвращается существующее. */
    public Suggestion create(String login, String kind, String source, String projectId,
                             String slug, String title, String iconUrl,
                             String profileDir, String profileTitle) {
        Suggestion suggestion = new Suggestion();
        suggestion.id = UUID.randomUUID().toString();
        suggestion.login = normalizeLogin(login);
        suggestion.kind = Kinds.normalize(kind);
        suggestion.source = cap(source == null ? null : source.trim());
        suggestion.projectId = cap(projectId == null ? null : projectId.trim());
        suggestion.slug = cap(slug);
        suggestion.title = cap(title);
        suggestion.iconUrl = iconUrl;
        suggestion.profileDir = cap(profileDir == null ? null : profileDir.trim());
        suggestion.profileTitle = cap(profileTitle);
        suggestion.createdAt = System.currentTimeMillis();
        synchronized (lock) {
            // Проверка дубля и добавление — ОДНА атомарная операция:
            // два одинаковых предложения в гонке не создадут двух записей
            for (Suggestion existing : state.suggestions) {
                if (existing != null && suggestion.profileDir != null
                        && suggestion.profileDir.equals(existing.profileDir)
                        && suggestion.source != null && suggestion.source.equals(existing.source)
                        && suggestion.projectId != null && suggestion.projectId.equals(existing.projectId)) {
                    return existing;
                }
            }
            state.suggestions.add(suggestion);
            // страховка от раздувания: самые старые вылетают за пределы лимита
            while (state.suggestions.size() > MAX_PENDING_TOTAL) {
                Suggestion oldest = null;
                for (Suggestion candidate : state.suggestions) {
                    if (candidate != null && (oldest == null || candidate.createdAt < oldest.createdAt)) {
                        oldest = candidate;
                    }
                }
                if (oldest == null) break;
                state.suggestions.remove(oldest);
            }
            saveLocked();
        }
        return suggestion;
    }

    /** Убрать из очереди (решение принято или отмена). Возвращает удалённую запись. */
    public Suggestion remove(String id) {
        if (id == null || id.isBlank()) return null;
        synchronized (lock) {
            for (Suggestion suggestion : state.suggestions) {
                if (suggestion != null && id.equals(suggestion.id)) {
                    state.suggestions.remove(suggestion);
                    saveLocked();
                    return suggestion;
                }
            }
        }
        return null;
    }

    public static String normalizeLogin(String login) {
        return login == null ? "" : login.trim().toLowerCase(Locale.ROOT);
    }

    private static String cap(String value) {
        if (value == null) return null;
        return value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) : value;
    }
}
