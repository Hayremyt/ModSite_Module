package pro.gravit.launchermodules.modssite.auth;

import pro.gravit.launchermodules.modssite.Utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пометки «наблюдатель» (viewer) для админов — ОВЕРРАЙД поверх источников
 * (локальный конфиг и БД лаунчера). Хранится в отдельном файле
 * (config/ModsSite/admin-roles.json): сама запись админа в конфиге или БД
 * НЕ изменяется, пометка снимается одним кликом в панели Безопасность -> Роли.
 * В файле только логины (в нижнем регистре) — без паролей и секретов.
 */
public class AdminRoleOverrides {
    public static class State {
        public List<String> viewers = new ArrayList<>();
    }

    private final Path file;
    private final Object saveLock = new Object();
    private final Set<String> viewers = ConcurrentHashMap.newKeySet();

    public AdminRoleOverrides(Path file) {
        this.file = file;
    }

    public void load() {
        viewers.clear();
        if (Files.exists(file)) {
            try {
                State loaded = Utils.readJson(file, State.class);
                if (loaded != null && loaded.viewers != null) {
                    for (String login : loaded.viewers) {
                        String normalized = normalize(login);
                        if (normalized != null && !normalized.isEmpty()) {
                            viewers.add(normalized);
                        }
                    }
                }
            } catch (Exception e) {
                org.apache.logging.log4j.LogManager.getLogger()
                        .error("Cannot read admin role overrides ({})", file, e);
            }
        }
    }

    private void save() {
        synchronized (saveLock) {
            State state = new State();
            state.viewers = viewers();
            try {
                Utils.writeJsonAtomic(file, state);
            } catch (IOException e) {
                org.apache.logging.log4j.LogManager.getLogger()
                        .error("Cannot save admin role overrides ({})", file, e);
            }
        }
    }

    /** Логин помечен наблюдателем (действует поверх роли источника)? */
    public boolean isForcedViewer(String login) {
        String normalized = normalize(login);
        return normalized != null && !normalized.isEmpty() && viewers.contains(normalized);
    }

    /** Поставить/снять пометку «наблюдатель». @return true — состав изменился. */
    public boolean setForcedViewer(String login, boolean viewer) {
        String normalized = normalize(login);
        if (normalized == null || normalized.isEmpty()) return false;
        boolean changed = viewer ? viewers.add(normalized) : viewers.remove(normalized);
        if (changed) save();
        return changed;
    }

    /** Все помеченные логины (отсортированные, для интерфейса). */
    public List<String> viewers() {
        List<String> list = new ArrayList<>(viewers);
        list.sort(String::compareTo);
        return list;
    }

    /** Канонический вид логина: trim + нижний регистр (сравнение без учёта регистра). */
    public static String normalize(String login) {
        return login == null ? null : login.trim().toLowerCase(Locale.ROOT);
    }
}
