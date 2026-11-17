package pro.gravit.launchermodules.modssite.mods;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import pro.gravit.launcher.base.Launcher;
import pro.gravit.launcher.base.profiles.ClientProfile;
import pro.gravit.launcher.base.profiles.optional.OptionalDepend;
import pro.gravit.launcher.base.profiles.optional.OptionalFile;
import pro.gravit.launcher.base.profiles.optional.actions.OptionalAction;
import pro.gravit.launcher.base.profiles.optional.actions.OptionalActionFile;
import pro.gravit.launchermodules.modssite.ModsSiteConfig;
import pro.gravit.launchserver.LaunchServer;
import pro.gravit.launchserver.auth.profiles.LocalProfilesProvider;
import pro.gravit.launchserver.auth.profiles.ProfilesProvider;
import pro.gravit.utils.helper.IOHelper;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Синхронизация опциональных модов модпака с updateOptional профиля —
 * ванильной системой «Настройки» лаунчера (выбор модов на ИГРОКА).
 *
 * Зачем это нужно: в GravitLauncher 5.7.x OptionalActionFile.files
 * использует ТОЧНЫЕ пути без глобов (HashedDir.tryFindRecursive сравнивает
 * сегменты имени буквально), поэтому при обновлении мода со сменой имени
 * файла (sodium-0.5.8.jar -> sodium-0.6.0.jar) привязка в профиле протухает:
 * клиент перестаёт исключать файл у выключившего мод игрока. Этот класс
 * переписывает привязку в тот же момент, когда обновляется installedFileName,
 * — до rehash, т.е. клиенты никогда не видят рассогласованного состояния.
 *
 * Как выбирается имя новой записи, если мода ещё нет в конфиге профиля:
 *   1. идентификатор с сервиса, откуда качали (slug Modrinth/CurseForge = id записи);
 *   2. если сервис не дал slug (id синтетический вида curseforge-12345) — modId
 *      из метаданных скачанного jar (fabric.mod.json / mods.toml / mcmod.info);
 *   3. крайние случаи — синтетический id, имя из jar или имя файла.
 * Имя фиксируется в ModEntry.profileOptionalName и больше не меняется:
 * выбор игрока хранится на клиенте по имени записи, поэтому стабильность
 * имени важнее его «красоты».
 *
 * Правила владения: запись updateOptional считается управляемой модулем,
 * если её name совпадает с profileOptionalName/id мода из модпака.
 * Ручные записи админа с другими именами не переименовываются, но
 * ПОДХВАТЫВАЮТСЯ по привязке к нашему файлу (в т.ч. к прежним именам файла
 * после обновлений): привязка в них перепривязывается на актуальный путь,
 * при удалении мода — его пути вычищаются, имя и прочие настройки админа
 * сохраняются (чтобы не терялись выборы игроков).
 */
public class ProfileOptionalSync {
    private static final Logger logger = LogManager.getLogger(ProfileOptionalSync.class);

    private final LaunchServer server;
    /** Конфиг не final: mods reload подменяет его живым значением. */
    private volatile ModsSiteConfig config;

    public ProfileOptionalSync(LaunchServer server, ModsSiteConfig config) {
        this.server = server;
        this.config = config;
    }

    /** mods reload: подменить конфиг (syncProfileOptional и т.п.). */
    public void updateConfig(ModsSiteConfig newConfig) {
        if (newConfig == null) return;
        this.config = newConfig;
    }

    public boolean enabled() {
        return config != null && config.mods != null && config.mods.syncProfileOptional;
    }

    /**
     * Относительный путь файла внутри профиля, например "mods/sodium-1.jar".
     * Именно такой ключ используется в OptionalActionFile.files.
     * Тип контента записи определяет поддиректорию (mods/resourcepacks/shaderpacks).
     */
    public static String relativeFilePath(ModsSiteConfig config, Modpack modpack, String kind, String fileName) {
        String subdir = Kinds.subdir(config, modpack, kind);
        String name = UpdateService.sanitizeFileName(fileName);
        return subdir.isEmpty() ? name : subdir + "/" + name;
    }

    /** Путь для файла записи — с учётом типа контента записи. */
    public static String relativeFilePath(ModsSiteConfig config, Modpack modpack, ModEntry entry) {
        return relativeFilePath(config, modpack, entry == null ? Kinds.MOD : entry.kindOrMod(),
                entry == null ? null : entry.installedFileName);
    }

    /**
     * Полное согласование updateOptional профиля с модпаком:
     * для каждого установленного включённого опционального мода —
     * найти (по имени или по привязке к файлу) либо создать запись,
     * перепривязать её к актуальному имени файла, проставить зависимости;
     * записи/пути модов, переставших быть кандидатами, — убрать.
     * Идемпотентно: профиль сохраняется только при изменениях.
     *
     * Вызывается после установок/удалений, до rehash и save модпака.
     *
     * @return true, если профиль был изменён
     */
    public boolean reconcile(ClientProfile profile, Modpack modpack) {
        if (!enabled() || profile == null || modpack == null) return false;
        try {
            // Проход 0: подхват опциональности из профиля — моды, чьи файлы
            // привязаны в updateOptional (в т.ч. ручными записями админа),
            // становятся опциональными (сценарий: профиль/модпак импортированы
            // раньше, чем в профиле настроили updateOptional)
            adoptProfileOptionals(profile, modpack);
            Set<OptionalFile> optionals = profile.getOptional();
            // Кандидаты: опциональные + включённые + с установленным файлом
            List<ModEntry> candidates = new ArrayList<>();
            for (ModEntry entry : modpack.mods) {
                if (entry != null && entry.optional && entry.enabled
                        && entry.installedFileName != null && !entry.installedFileName.isBlank()) {
                    candidates.add(entry);
                }
            }
            boolean changed = false;
            // Проход 1: найти/создать записи и перепривязать их к файлам
            Map<String, UpsertResult> resolved = new LinkedHashMap<>();
            for (ModEntry entry : candidates) {
                UpsertResult result = upsert(optionals, modpack, entry);
                resolved.put(entry.id, result);
                if (result.changed) changed = true;
            }
            // Проход 2: зависимости/конфликты — только в своих записях,
            // ссылки — на фактические имена записей целей (цель может быть
            // подхваченной ручной записью админа с другим именем).
            // Групповая запись одна на несколько модов — её зависимости
            // это ОБЪЕДИНЕНИЕ зависимостей всех членов группы.
            Map<OptionalFile, List<String>> unionDeps = new LinkedHashMap<>();
            Map<OptionalFile, List<String>> unionConflicts = new LinkedHashMap<>();
            for (ModEntry entry : candidates) {
                UpsertResult result = resolved.get(entry.id);
                if (result == null || !result.owned) continue;
                boolean grouped = entry.optionalGroup != null && !entry.optionalGroup.isBlank();
                if (grouped) {
                    unionDeps.computeIfAbsent(result.file, k -> new ArrayList<>())
                            .addAll(entry.dependencies);
                    unionConflicts.computeIfAbsent(result.file, k -> new ArrayList<>())
                            .addAll(entry.conflicts);
                } else if (updateRelations(result.file, entry, resolved)) {
                    changed = true;
                }
            }
            for (Map.Entry<OptionalFile, List<String>> e : unionDeps.entrySet()) {
                List<String> conflicts = unionConflicts.getOrDefault(e.getKey(), new ArrayList<>());
                if (updateRelationsLists(e.getKey(), e.getValue(), conflicts, resolved)) changed = true;
            }
            // Проход 3: моды, выпавшие из кандидатов (выключены/не установлены/
            // стали обязательными) — убрать их записи и вычистить их пути
            // из подхваченных ручных записей
            for (ModEntry entry : modpack.mods) {
                if (entry == null || resolved.containsKey(entry.id)) continue;
                if (!isEverManaged(optionals, modpack, entry)) continue;
                if (removeEntryInternal(optionals, modpack, entry)) changed = true;
            }
            if (changed) {
                saveProfile(profile);
            }
            return changed;
        } catch (Throwable t) {
            logger.warn("[ModsSite] updateOptional sync failed for '{}'",
                    profile.getDir() == null ? "?" : profile.getDir(), t);
            return false;
        }
    }

    /**
     * Убрать следы мода из updateOptional: свою запись — целиком,
     * из подхваченной ручной записи — только привязки к его файлам.
     * Не делает ничего, если следов нет.
     */
    public boolean removeEntry(ClientProfile profile, Modpack modpack, ModEntry entry) {
        if (!enabled() || profile == null || modpack == null || entry == null) return false;
        try {
            boolean changed = removeEntryInternal(profile.getOptional(), modpack, entry);
            if (changed) {
                saveProfile(profile);
            }
            return changed;
        } catch (Throwable t) {
            logger.warn("[ModsSite] Cannot remove updateOptional entry of '{}'", entry.id, t);
            return false;
        }
    }

    /**
     * Имена файлов (сегмент после последнего '/'), на которые указывают
     * файловые привязки updateOptional профиля — включая ручные записи админа.
     * Используется, чтобы не считать такие файлы «лишними» и при импорте
     * восстанавливать признак опциональности.
     */
    public static Set<String> optionalBoundFileNames(ClientProfile profile) {
        Set<String> names = new LinkedHashSet<>();
        if (profile == null) return names;
        try {
            for (OptionalFile file : profile.getOptional()) {
                if (file == null || file.actions == null) continue;
                for (OptionalAction action : file.actions) {
                    if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
                    for (String path : actionFile.files.keySet()) {
                        addFileNameSegment(path, names);
                    }
                }
            }
        } catch (Throwable ignored) {
            // битый профиль не должен ломать ни поиск «лишних» файлов, ни импорт
        }
        return names;
    }

    // ======================= Прямое чтение profile.json =======================

    /** Возможные имена массива опциональных модов в profile.json.
     *  Ванильный формат 5.7.x — updateOptional; прочие — на случай профилей,
     *  собранных конвертерами/руками по другим гайдам (optionalMods и т.п.). */
    private static final String[] RAW_OPTIONAL_KEYS = {
            "updateOptional", "optionalMods", "optional", "optionals", "update_optional"
    };
    /** Поля записи, в которых конвертеры/гайды держат список файлов. */
    private static final String[] RAW_FILE_KEYS = {"list", "files", "paths"};

    /** Что удалось вытащить из profile.json напрямую (минуя ClientProfile). */
    public static final class RawOptionals {
        /** Путь к profile.json (null — не смогли определить). */
        public Path path;
        /** Файл существует и прочитан. */
        public boolean read;
        /** Причина неудачи чтения (для диагностики). */
        public String readError;
        /** Число записей во всех распознанных массивах. */
        public int entries;
        /** Имена записей (name/id). */
        public final Set<String> entryNames = new LinkedHashSet<>();
        /** Имена файлов (последний сегмент пути) из всех привязок. */
        public final Set<String> boundFileNames = new LinkedHashSet<>();
        /** Полные пути всех привязок (как записаны). */
        public final Set<String> boundPaths = new LinkedHashSet<>();
        /** Найденные формы записей — для диагностики (например
         *  «updateOptional.actions[type=file].files», «optionalMods[].list»). */
        public final Set<String> formats = new LinkedHashSet<>();
    }

    /** Свежевыгруженные опциональные моды прямо из profile.json на диске.
     *  Зачем: ClientProfile разбирает ТОЛЬКО ванильную форму
     *  updateOptional[].actions[type=file].files — если профиль собран
     *  конвертером или по другому гайду (optionalMods[].list и т.п.), лаунчер
     *  эти записи молча игнорирует, а мод не может увидеть привязки. Читаем
     *  сырой JSON и собираем привязки/имена из любых разумных форм. */
    public RawOptionals rawOptionals(ClientProfile profile) {
        RawOptionals result = new RawOptionals();
        result.path = profileConfigPath(profile);
        if (result.path == null) return result;
        if (!Files.isRegularFile(result.path)) return result;
        result.read = true;
        try (Reader reader = Files.newBufferedReader(result.path, StandardCharsets.UTF_8)) {
            readRawRoot(JsonParser.parseReader(reader), result);
        } catch (Throwable t) {
            result.read = false;
            result.readError = String.valueOf(t.getMessage());
            logger.debug("[ModsSite] Cannot raw-read {} — {}",
                    result.path, result.readError);
        }
        return result;
    }

    /** Разбор корня profile.json: собирает записи/привязки из любых известных форм
     *  (отделён от чтения файла для тестируемости). */
    public static void readRawRoot(JsonElement rootElement, RawOptionals out) {
        if (rootElement == null || !rootElement.isJsonObject()) return;
        JsonObject root = rootElement.getAsJsonObject();
        for (String key : RAW_OPTIONAL_KEYS) {
            JsonElement array = root.get(key);
            if (array == null || !array.isJsonArray()) continue;
            for (JsonElement element : array.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                readRawEntry(key, element.getAsJsonObject(), out);
            }
        }
    }

    /** Одна запись опционального мода из сырого JSON: имя + привязки из любых форм. */
    private static void readRawEntry(String key, JsonObject entry, RawOptionals out) {
        out.entries++;
        String name = rawString(entry, "name");
        if (name == null) name = rawString(entry, "id");
        if (name != null && !name.isBlank()) out.entryNames.add(name.trim());
        // 1. Ванильная форма: actions[type=file].files — объект или массив
        JsonElement actions = entry.get("actions");
        if (actions != null && actions.isJsonArray()) {
            for (JsonElement actionElement : actions.getAsJsonArray()) {
                if (!actionElement.isJsonObject()) continue;
                JsonObject action = actionElement.getAsJsonObject();
                String type = rawString(action, "type");
                JsonElement files = action.get("files");
                if (files == null) continue;
                if (type != null && !"file".equals(type)) continue;
                if (collectRawPaths(files, out)) {
                    out.formats.add(key + ".actions[type=file].files");
                }
            }
        }
        // 2. Альтернативные/легаси формы: list / files / paths прямо в записи
        for (String fileKey : RAW_FILE_KEYS) {
            JsonElement files = entry.get(fileKey);
            if (files == null || files.isJsonNull()) continue;
            if (collectRawPaths(files, out)) {
                out.formats.add(key + "[]." + fileKey);
            }
        }
    }

    /** Собрать пути файлов из элемента (массив строк / объект-ключи / одна строка).
     *  @return true, если добавлен хотя бы один новый путь */
    private static boolean collectRawPaths(JsonElement files, RawOptionals out) {
        int before = out.boundPaths.size();
        if (files.isJsonPrimitive()) {
            addRawPath(files.getAsString(), out);
        } else if (files.isJsonArray()) {
            for (JsonElement e : files.getAsJsonArray()) {
                if (e.isJsonPrimitive()) addRawPath(e.getAsString(), out);
            }
        } else if (files.isJsonObject()) {
            for (String path : files.getAsJsonObject().keySet()) {
                addRawPath(path, out);
            }
        }
        return out.boundPaths.size() > before;
    }

    private static void addRawPath(String path, RawOptionals out) {
        if (path == null || path.isBlank()) return;
        path = path.trim().replace('\\', '/');
        if (path.isEmpty() || ".".equals(path) || "..".equals(path)) return;
        out.boundPaths.add(path);
        Set<String> segment = new LinkedHashSet<>();
        addFileNameSegment(path, segment);
        out.boundFileNames.addAll(segment);
    }

    /** Последний сегмент пути (имя файла) — в набор. */
    private static void addFileNameSegment(String path, Set<String> out) {
        if (path == null || path.isBlank()) return;
        String name = path.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        if (!name.isBlank()) out.add(name);
    }

    private static String rawString(JsonObject object, String member) {
        JsonElement value = object.get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** Путь к profile.json на диске (как его видит LocalProfilesProvider). */
    private Path profileConfigPath(ClientProfile profile) {
        if (server == null || server.config == null || profile == null || profile.getDir() == null) {
            return null;
        }
        try {
            if (server.config.profilesProvider instanceof LocalProfilesProvider local) {
                // Точный путь живого профиля (совпадение объекта)
                for (ProfilesProvider.UncompletedProfile p : local.getProfiles(null)) {
                    if (p instanceof LocalProfilesProvider.LocalProfile lp && lp.getProfile() == profile) {
                        return lp.getConfigPath();
                    }
                }
                // Не нашли живой объект (профиль пересобран в памяти) — путь по правилам провайдера
                Path dir = Path.of(local.profilesDir);
                if (!dir.isAbsolute()) dir = server.dir.resolve(dir);
                return dir.resolve(profile.getDir() + ".json");
            }
        } catch (Throwable ignored) {
            // сторонний провайдер — пробуем стандартное место, читать будем безопасно
        }
        return server.dir.resolve("profiles").resolve(profile.getDir() + ".json");
    }

    /** Сводный снимок опциональных модов профиля: разобранный ClientProfile
     *  + сырой profile.json (привязки и имена из всех форм). Для сопоставления
     *  с модами модпака, поиска «лишних» файлов и импорта. */
    public static final class OptionalsView {
        /** Привязки к именам файлов (парсинг + сырой JSON). */
        public final Set<String> boundFileNames = new LinkedHashSet<>();
        /** Имена записей (парсинг + сырой JSON). */
        public final Set<String> entryNames = new LinkedHashSet<>();
        /** Число записей в разобранном updateOptional. */
        public int parsedEntries;
        /** Сырой разбор profile.json (может быть не прочитан). */
        public RawOptionals raw;
    }

    public OptionalsView view(ClientProfile profile) {
        OptionalsView view = new OptionalsView();
        if (profile == null) return view;
        try {
            for (OptionalFile file : profile.getOptional()) {
                if (file == null) continue;
                view.parsedEntries++;
                if (file.name != null && !file.name.isBlank()) view.entryNames.add(file.name.trim());
                if (file.actions == null) continue;
                for (OptionalAction action : file.actions) {
                    if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
                    for (String path : actionFile.files.keySet()) {
                        addFileNameSegment(path, view.boundFileNames);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        RawOptionals raw = rawOptionals(profile);
        view.raw = raw;
        view.boundFileNames.addAll(raw.boundFileNames);
        view.entryNames.addAll(raw.entryNames);
        return view;
    }

    /** Все известные привязки к файлам (парсинг + сырой JSON) — для «лишних» файлов. */
    public Set<String> allBoundFileNames(ClientProfile profile) {
        return view(profile).boundFileNames;
    }

    // ======================= Внутреннее =======================

    /**
     * Подхват опциональности из профиля: моды, чьи установленные файлы
     * (текущее или прежние имена) привязаны в updateOptional профиля —
     * в том числе РУЧНЫМИ записями администратора — помечаются
     * опциональными и включёнными (файл лежит на сервере и должен входить
     * в состав; выключить сможет сам админ).
     *
     * <p>Сценарий: готовый модпак и профиль импортировали на сервер,
     * updateOptional в профиле настраивали руками ПОСЛЕ импорта модов
     * в модпак — раньше признак опциональности проставлялся только в момент
     * импорта, и такие моды навсегда оставались «обязательными».
     *
     * <p>Синхронизация односторонняя: «файл не привязан в профиле» НЕ делает
     * мод обязательным — опциональность могла быть включена галочкой на сайте
     * без привязки в профиле.
     *
     * @return true, если модпак изменился (вызывающий код должен его сохранить)
     */
    public boolean adoptProfileOptionals(ClientProfile profile, Modpack modpack) {
        if (profile == null || modpack == null || modpack.mods.isEmpty()) return false;
        OptionalsView view = view(profile);
        if (view.boundFileNames.isEmpty() && view.entryNames.isEmpty()) return false;
        Set<String> boundLower = lowerSet(view.boundFileNames);
        Set<String> namesLower = lowerSet(view.entryNames);
        boolean changed = false;
        for (ModEntry entry : modpack.mods) {
            if (entry == null || entry.optional) continue;
            // Могильник «обязательности»: админ снял галочку «Опциональный»
            // на сайте — автоподхват не возвращает опциональность, иначе
            // ещё не вычищенная привязка файла в updateOptional откатывала бы
            // выбор админа при каждом согласовании. Галочка «Опциональный»
            // могильник снимает.
            if (entry.requiredTombstone) {
                // Могильник — это СОСТОЯНИЕ, а не событие: он повторно
                // проверяется при каждом согласовании (открытие страницы модов,
                // установка/удаление, «Применить состав»). Лог на каждый такой
                // запуск — даже debug — захлёбывал журнал; состояние и так видно
                // на сайте (значок «обязательный»), в лог не пишем ничего.
                continue;
            }
            String reason = null;
            if (matchesBoundFile(entry, view.boundFileNames, boundLower)) {
                reason = "файл привязан в updateOptional профиля";
            } else if (matchesEntryName(entry, namesLower)) {
                reason = "имя записи updateOptional совпало с модом ("
                        + (entry.installedModId != null ? entry.installedModId : entry.id) + ")";
            }
            if (reason != null) {
                entry.optional = true;
                entry.enabled = true;
                changed = true;
                logger.info("[ModsSite] Mod '{}' is optional: {}", entry.id, reason);
            }
        }
        // Группы: одна запись профиля с НЕСКОЛЬКИМИ файлами связывает моды в
        // группу с общим переключателем (пример: запись «Iris» с двумя файлами
        // iris-neoforge и iris-flywheel-compat). Члены группы получают имя
        // группы в optionalGroup; reconcile удержит их в ОДНОЙ записи.
        Set<OptionalFile> optionals = profile.getOptional();
        for (OptionalFile record : optionals) {
            if (record == null || record.name == null || record.name.isBlank()) continue;
            Set<String> recordPaths = recordBoundPaths(record);
            if (recordPaths.size() < 2) continue;
            List<ModEntry> members = new ArrayList<>();
            for (ModEntry entry : modpack.mods) {
                if (entry == null || !entry.optional) continue;
                for (String path : knownRelativePaths(modpack, entry)) {
                    if (recordPaths.contains(lowerPath(path))) {
                        members.add(entry);
                        break;
                    }
                }
            }
            if (members.size() < 2) continue;
            for (ModEntry member : members) {
                if (member.optionalGroup == null || member.optionalGroup.isBlank()) {
                    // могильник: мод удалён из этой группы админом на сайте —
                    // автоподхват не возвращает его (файл ещё привязан в
                    // групповой записи, но связь разорвана намеренно)
                    if (member.hasGroupTombstone(record.name)) {
                        // Состояние-не-событие (см. requiredTombstone выше): в лог не пишем
                        continue;
                    }
                    member.optionalGroup = record.name;
                    changed = true;
                    logger.info("[ModsSite] Mod '{}' joined optional group '{}' ({} file(s) in one profile entry)",
                            member.id, record.name, recordPaths.size());
                }
            }
        }
        return changed;
    }

    /** Все файловые пути одной записи updateOptional (без учёта регистра). */
    private static Set<String> recordBoundPaths(OptionalFile record) {
        Set<String> paths = new LinkedHashSet<>();
        if (record.actions == null) return paths;
        for (OptionalAction action : record.actions) {
            if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
            for (String path : actionFile.files.keySet()) {
                if (path != null && !path.isBlank()) paths.add(lowerPath(path));
            }
        }
        return paths;
    }

    // ======================= Зависимости/конфликты из профиля =======================

    /**
     * Подхватить зависимости и конфликты ИЗ профиля — поля dependenciesFile /
     * conflictFile записей updateOptional (то, что настроил админ или конвертер
     * модпака; в примере ниже запись «Sodium» зависит от записи «Iris»):
     * <pre>
     * { "name": "Sodium", ..., "dependenciesFile": [{"name": "Iris"}],
     *   "conflictFile": [{"name": "Optifine"}] }
     * </pre>
     * Раньше модуль только ПИСАЛ эти поля в профиль, но не читал их обратно —
     * на сайте связи оставались пустыми.
     *
     * <p>Разрешение ссылок: имя записи-цели -> все моды модпака, покрытые той
     * записью (по файловым привязкам или по совпадению имени записи с
     * id/slug/modId мода); если записи с таким именем нет, ссылка трактуется
     * как id мода модпака напрямую.
     *
     * <p>Неудаляюще: список мода (зависимости или конфликты) заполняется,
     * только если он ПУСТ — первичное заполнение; дальше списками управляет
     * админ на сайте (чтобы вычищенную на сайте связь профиль не возвращал).
     * Конфликты проставляются в обе стороны, как и везде в модуле.
     *
     * @return true, если модпак изменился (вызывающий код должен его сохранить)
     */
    public boolean adoptProfileRelations(ClientProfile profile, Modpack modpack) {
        if (!enabled() || profile == null || modpack == null || modpack.mods.isEmpty()) return false;
        Set<OptionalFile> optionals = profile.getOptional();
        if (optionals == null || optionals.isEmpty()) return false;
        boolean changed = false;
        for (OptionalFile record : optionals) {
            if (record == null || record.name == null || record.name.isBlank()) continue;
            List<ModEntry> sources = coveredEntries(record, modpack);
            if (sources.isEmpty()) continue;
            // Зависимости: ссылка на записи-цели — только в прямую сторону
            List<ModEntry> depTargets = relationTargets(record.dependenciesFile, optionals, modpack);
            if (!depTargets.isEmpty()) {
                for (ModEntry source : sources) {
                    if (source.dependencies == null) source.dependencies = new ArrayList<>();
                    if (!source.dependencies.isEmpty()) continue; // только первичное заполнение
                    for (ModEntry target : depTargets) {
                        if (target != source && !source.dependencies.contains(target.id)) {
                            source.dependencies.add(target.id);
                            changed = true;
                            logger.info("[ModsSite] Dependency adopted from profile: '{}' depends on '{}'",
                                    source.id, target.id);
                        }
                    }
                }
            }
            // Конфликты: в обе стороны (у цели тоже должен стоять конфликт)
            List<ModEntry> conflictTargets = relationTargets(record.conflictFile, optionals, modpack);
            if (!conflictTargets.isEmpty()) {
                Map<ModEntry, List<ModEntry>> reverse = new LinkedHashMap<>();
                for (ModEntry source : sources) {
                    if (source.conflicts == null) source.conflicts = new ArrayList<>();
                    if (source.conflicts.isEmpty()) {
                        for (ModEntry target : conflictTargets) {
                            if (target != source && !source.conflicts.contains(target.id)) {
                                source.conflicts.add(target.id);
                                changed = true;
                                logger.info("[ModsSite] Conflict adopted from profile: '{}' <-> '{}'",
                                        source.id, target.id);
                            }
                        }
                    }
                    for (ModEntry target : conflictTargets) {
                        if (target != source) {
                            reverse.computeIfAbsent(target, k -> new ArrayList<>()).add(source);
                        }
                    }
                }
                for (Map.Entry<ModEntry, List<ModEntry>> e : reverse.entrySet()) {
                    ModEntry target = e.getKey();
                    if (target.conflicts == null) target.conflicts = new ArrayList<>();
                    if (!target.conflicts.isEmpty()) continue; // только первичное заполнение
                    for (ModEntry source : e.getValue()) {
                        if (!target.conflicts.contains(source.id)) {
                            target.conflicts.add(source.id);
                            changed = true;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** Моды модпака, покрытые записью: по файловым привязкам записи ИЛИ по
     *  совпадению имени записи с идентификаторами мода (id/slug/modId). */
    private List<ModEntry> coveredEntries(OptionalFile record, Modpack modpack) {
        List<ModEntry> result = new ArrayList<>();
        if (record == null) return result;
        Set<String> recordPaths = recordBoundPaths(record);
        Set<String> nameLower = new LinkedHashSet<>();
        if (record.name != null && !record.name.isBlank()) {
            nameLower.add(record.name.trim().toLowerCase(Locale.ROOT));
        }
        for (ModEntry entry : modpack.mods) {
            if (entry == null) continue;
            boolean hit = false;
            if (!recordPaths.isEmpty()) {
                for (String path : knownRelativePaths(modpack, entry)) {
                    if (recordPaths.contains(lowerPath(path))) {
                        hit = true;
                        break;
                    }
                }
            }
            if (!hit) hit = matchesEntryName(entry, nameLower);
            if (hit) result.add(entry);
        }
        return result;
    }

    /** Цели ссылок записи (dependenciesFile/conflictFile): моды записей-целей;
     *  ссылка, для которой записи с таким именем нет, трактуется как id мода
     *  модпака напрямую. */
    private List<ModEntry> relationTargets(OptionalDepend[] refs, Set<OptionalFile> optionals,
                                           Modpack modpack) {
        List<ModEntry> result = new ArrayList<>();
        if (refs == null) return result;
        for (OptionalDepend ref : refs) {
            if (ref == null || ref.name == null || ref.name.isBlank()) continue;
            OptionalFile targetRecord = findByName(optionals, ref.name);
            if (targetRecord == null) {
                // без учёта регистра: конвертеры/руки пишут имена как попало
                for (OptionalFile candidate : optionals) {
                    if (candidate != null && candidate.name != null
                            && candidate.name.equalsIgnoreCase(ref.name)) {
                        targetRecord = candidate;
                        break;
                    }
                }
            }
            List<ModEntry> targets = targetRecord != null
                    ? coveredEntries(targetRecord, modpack) : List.of();
            if (!targets.isEmpty()) {
                for (ModEntry target : targets) {
                    if (!result.contains(target)) result.add(target);
                }
            } else {
                ModEntry direct = modpack.find(ref.name.trim());
                if (direct != null && !result.contains(direct)) result.add(direct);
            }
        }
        return result;
    }

    /** Совпадает ли один из идентификаторов мода с именем записи updateOptional
     *  (id/slug, зафиксированное имя записи, modId из jar) — без учёта регистра. */
    public static boolean matchesEntryName(ModEntry entry, Set<String> namesLower) {
        if (namesLower.isEmpty()) return false;
        return namesLower.contains(lower(entry.id))
                || namesLower.contains(lower(entry.profileOptionalName))
                || namesLower.contains(lower(entry.installedModId));
    }

    private static String lower(String s) {
        return s == null ? null : s.trim().toLowerCase(Locale.ROOT);
    }

    private static Set<String> lowerSet(Set<String> values) {
        Set<String> result = new LinkedHashSet<>(values.size());
        for (String value : values) {
            String lower = lower(value);
            if (lower != null && !lower.isEmpty()) result.add(lower);
        }
        return result;
    }

    /** Привязан ли файл мода (текущий или прежние имена) в updateOptional. */
    private static boolean matchesBoundFile(ModEntry entry, Set<String> bound, Set<String> boundLower) {
        if (entry.installedFileName != null && isBoundName(entry.installedFileName, bound, boundLower)) {
            return true;
        }
        if (entry.previousFileNames != null) {
            for (String previous : entry.previousFileNames) {
                if (previous != null && !previous.isBlank() && isBoundName(previous, bound, boundLower)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isBoundName(String name, Set<String> bound, Set<String> boundLower) {
        return bound.contains(name) || boundLower.contains(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Результат обработки одного мода: запись, было ли изменение, наша ли запись. */
    private static final class UpsertResult {
        final OptionalFile file;
        final boolean changed;
        final boolean owned;

        UpsertResult(OptionalFile file, boolean changed, boolean owned) {
            this.file = file;
            this.changed = changed;
            this.owned = owned;
        }
    }

    private static OptionalFile findByName(Set<OptionalFile> optionals, String name) {
        if (name == null) return null;
        for (OptionalFile file : optionals) {
            if (file != null && name.equals(file.name)) return file;
        }
        return null;
    }

    /** Все пути (текущий и прежние имена файла), по которым мод мог быть привязан. */
    private Set<String> knownRelativePaths(Modpack modpack, ModEntry entry) {
        Set<String> paths = new LinkedHashSet<>();
        addKnownPath(modpack, entry, entry.installedFileName, paths);
        if (entry.previousFileNames != null) {
            for (String name : entry.previousFileNames) {
                addKnownPath(modpack, entry, name, paths);
            }
        }
        return paths;
    }

    private void addKnownPath(Modpack modpack, ModEntry entry, String fileName, Set<String> out) {
        if (fileName == null || fileName.isBlank()) return;
        out.add(relativeFilePath(config, modpack, entry.kindOrMod(), fileName));
    }

    /** Запись, чья файловая привязка содержит один из известных путей мода
     *  (сравнение путей — без учёта регистра: файловые системы Windows его не
     *  различают, и админ мог написать имя файла в другом регистре). */
    private static OptionalFile findByPaths(Set<OptionalFile> optionals, Set<String> knownPaths) {
        if (knownPaths.isEmpty()) return null;
        Set<String> lower = lowerPaths(knownPaths);
        for (OptionalFile file : optionals) {
            if (file == null || file.actions == null) continue;
            for (OptionalAction action : file.actions) {
                if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
                for (String path : actionFile.files.keySet()) {
                    if (lower.contains(lowerPath(path))) return file;
                }
            }
        }
        return null;
    }

    /** Содержит ли запись привязку к одному из путей (без учёта регистра). */
    private static boolean hasAnyPath(OptionalFile file, Set<String> knownPaths) {
        if (file == null || file.actions == null || knownPaths.isEmpty()) return false;
        Set<String> lower = lowerPaths(knownPaths);
        for (OptionalAction action : file.actions) {
            if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
            for (String path : actionFile.files.keySet()) {
                if (lower.contains(lowerPath(path))) return true;
            }
        }
        return false;
    }

    private static String lowerPath(String path) {
        return path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
    }

    private static Set<String> lowerPaths(Set<String> paths) {
        Set<String> result = new LinkedHashSet<>(paths.size());
        for (String path : paths) {
            result.add(lowerPath(path));
        }
        return result;
    }

    /**
     * Имел ли мод когда-либо модуличную запись (для чистки при выпадении из
     * кандидатов): запись создана модулем, мод опциональный (мог быть выключен)
     * либо осталась запись старой версии модуля с именем = id, привязанная к нам.
     */
    private boolean isEverManaged(Set<OptionalFile> optionals, Modpack modpack, ModEntry entry) {
        if (entry.profileOptionalName != null) return true;
        if (entry.optional) return true;
        OptionalFile byName = findByName(optionals, entry.optionalName());
        return byName != null && hasAnyPath(byName, knownRelativePaths(modpack, entry));
    }

    /** Найти или создать запись; перепривязать к актуальному файлу.
     *  Групповой мод (optionalGroup задан) удерживается в ОДНОЙ записи группы:
     *  сначала ищется запись с именем группы, перепривязка — аддитивная
     *  (чужие привязки записи не трогаются, свои устаревшие — вычищаются). */
    private UpsertResult upsert(Set<OptionalFile> optionals, Modpack modpack, ModEntry entry) {
        String relPath = relativeFilePath(config, modpack, entry.kindOrMod(), entry.installedFileName);
        Set<String> knownPaths = knownRelativePaths(modpack, entry);
        boolean grouped = entry.optionalGroup != null && !entry.optionalGroup.isBlank();
        OptionalFile file = null;
        boolean byOwnName = false;
        boolean owned = false;
        boolean changed = false;
        if (!grouped) {
            // 0a. Зафиксированное имя указывает на ГРУППОВУЮ запись другого мода
            //     (мод выведен из группы на сайте, имя в конфиге устарело,
            //     либо группу переименовали): имя больше не наше
            if (entry.profileOptionalName != null) {
                OptionalFile stale = findByName(optionals, entry.profileOptionalName);
                if (stale != null && isGroupRecordOfOthers(stale, modpack, entry)) {
                    entry.profileOptionalName = null;
                    changed = true;
                }
            }
        }
        // 0. Группа: запись с именем группы (общий переключатель)
        if (grouped) {
            file = findByName(optionals, entry.optionalGroup);
            // Групповой мод держится ТОЛЬКО в записи группы: если её ещё нет —
            // она будет создана ниже, а прежние одиночные привязки мода
            // погашены (миграция в группу). Поиск по собственному имени и по
            // привязкам пропускаем, иначе мод застрял бы в одиночной записи.
        } else {
            // 1. Своя запись по зафиксированному имени
            file = findByName(optionals, entry.optionalName());
            byOwnName = file != null;
            if (file != null) {
                // Запись, привязывающая файлы ДРУГОГО мода, — разделяемая:
                // управляем только своим путём, иначе rebindOwned вычистил бы чужие
                owned = !bindsOtherEntry(modpack, file, entry);
            } else {
                // 2. Ручная запись админа, привязанная к нашему файлу, — подхват
                //    (не своя: имя и настройки админа не трогаем).
                //    ГРУППОВАЯ запись ДРУГИХ модов — не подхват: мод выведен
                //    из группы (или в неё не вступал), привязываться к ней нельзя,
                //    иначе выход из группы тут же откатывался бы автоподхватом
                file = findByPaths(optionals, knownPaths);
                if (file != null && isGroupRecordOfOthers(file, modpack, entry)) {
                    // Состояние-не-событие (могильник группы): в лог не пишем
                    file = null;
                }
                owned = false;
            }
        }
        if (file == null) {
            // 3. Мода нет в конфиге профиля: создать запись. Для группового мода
            //    имя записи = имя группы, а прежние одиночные привязки гасятся
            file = new OptionalFile();
            file.name = grouped
                    ? uniqueGroupName(optionals, entry.optionalGroup)
                    : resolveName(optionals, modpack, entry);
            entry.profileOptionalName = file.name;
            file.info = describe(entry);
            file.mark = config.mods.defaultOptionalEnabled; // состояние по умолчанию у игроков
            file.visible = true;
            file.actions = new ArrayList<>();
            optionals.add(file);
            owned = true;
            changed = true;
            // Новая запись: гасим прежние привязки мода в других записях —
            // одиночные при миграции в группу, групповые при выходе из неё
            // (у выведенного мода файл оставался в записи группы)
            changed |= cleanModPathsElsewhere(optionals, modpack, entry, file);
        } else {
            if (byOwnName && owned && entry.profileOptionalName == null) {
                entry.profileOptionalName = file.name;
            }
            if (grouped && entry.profileOptionalName == null) {
                entry.profileOptionalName = file.name; // имя групповой записи — общее
            }
        }
        if (owned && !grouped) {
            // Полное управление: ручные move-привязки (значение != "") сохраняем,
            // исключение перепривязываем к актуальному пути
            changed |= rebindOwned(file, relPath);
        } else {
            // Групповая/подхваченная/разделяемая запись: имя и настройки не
            // трогаем, перепривязываем только пути нашего файла
            changed |= rebindAdopted(file, knownPaths, relPath);
        }
        // Устаревшие привязки мода в ДРУГИХ записях: переключатель группы
        // управляет ВСЕМИ файлами своей записи, поэтому путь мода должен
        // жить только в его актуальной записи (выход из группы, переход
        // в другую, прежние имена файла после обновлений)
        changed |= cleanModPathsElsewhere(optionals, modpack, entry, file);
        return new UpsertResult(file, changed, owned);
    }

    /** Групповая ли это запись ДРУГИХ модов: её имя совпадает с optionalGroup
     *  другого мода модпака (сам мод в неё не входит). */
    private static boolean isGroupRecordOfOthers(OptionalFile record, Modpack modpack, ModEntry self) {
        if (record == null || record.name == null || record.name.isBlank()) return false;
        for (ModEntry other : modpack.mods) {
            if (other == null || other == self) continue;
            if (record.name.equals(other.optionalGroup)) return true;
        }
        return false;
    }

    /** Вычистить привязки мода из ВСЕХ записей, кроме указанной (выход из
     *  групповой записи / миграция в неё): одиночная запись мода гасится, если
     *  в ней не осталось файловых привязок и других действий (jvmArgs и т.п.).
     *  @return true, если что-то изменилось */
    private boolean cleanModPathsElsewhere(Set<OptionalFile> optionals, Modpack modpack,
                                        ModEntry entry, OptionalFile keep) {
        Set<String> knownPaths = knownRelativePaths(modpack, entry);
        if (knownPaths.isEmpty()) return false;
        Set<String> lowerKnown = lowerPaths(knownPaths);
        List<OptionalFile> toDrop = new ArrayList<>();
        boolean changed = false;
        for (OptionalFile other : optionals) {
            if (other == null || other == keep || other.actions == null) continue;
            boolean touched = false;
            for (OptionalAction action : other.actions) {
                if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
                if (actionFile.files.isEmpty()) continue;
                Map<String, String> copy = new LinkedHashMap<>(actionFile.files);
                if (copy.keySet().removeIf(path -> lowerKnown.contains(lowerPath(path)))) {
                    actionFile.files = copy; // копия: files может быть immutable
                    touched = true;
                }
            }
            if (!touched) continue;
            changed = true;
            boolean hasFiles = false;
            boolean hasOtherActions = false;
            for (OptionalAction action : other.actions) {
                if (action instanceof OptionalActionFile actionFile) {
                    if (actionFile.files != null && !actionFile.files.isEmpty()) hasFiles = true;
                } else {
                    hasOtherActions = true;
                }
            }
            if (!hasFiles && !hasOtherActions) toDrop.add(other);
        }
        if (!toDrop.isEmpty()) {
            optionals.removeAll(toDrop);
            changed = true;
        }
        return changed;
    }

    /** Имя группы, уникальное среди записей (коллизии маловероятны — запись
     *  с таким именем уже искалась выше, но на всякий случай). */
    private static String uniqueGroupName(Set<OptionalFile> optionals, String base) {
        String name = base;
        for (int i = 2; findByName(optionals, name) != null; i++) {
            name = base + "-" + i;
        }
        return name;
    }

    /** Привязывает ли запись файлы ДРУГОГО мода модпака (признак разделяемой
     *  записи: группа опциональных или ручная запись на несколько файлов). */
    private boolean bindsOtherEntry(Modpack modpack, OptionalFile file, ModEntry self) {
        for (ModEntry other : modpack.mods) {
            if (other == null || other == self || other.installedFileName == null
                    || other.installedFileName.isBlank()) continue;
            for (String path : knownRelativePaths(modpack, other)) {
                if (hasAnyPath(file, java.util.Set.of(path))) return true;
            }
        }
        return false;
    }

    /**
     * Перепривязка своей записи: запись полностью управляется модулем —
     * ручные move-привязки (значение != "") сохраняем, исключения ("")
     * заменяем единственным актуальным путём (устаревшие вычищаются,
     * в т.ч. оставшиеся от старых версий модуля без истории имён).
     */
    private static boolean rebindOwned(OptionalFile file, String relPath) {
        if (file.actions == null) file.actions = new ArrayList<>();
        OptionalActionFile actionFile = null;
        for (OptionalAction action : file.actions) {
            if (action instanceof OptionalActionFile found) {
                actionFile = found;
                break;
            }
        }
        if (actionFile == null) {
            actionFile = new OptionalActionFile(new LinkedHashMap<>());
            file.actions.add(actionFile);
        }
        Map<String, String> desired = new LinkedHashMap<>();
        if (actionFile.files != null) {
            for (Map.Entry<String, String> e : actionFile.files.entrySet()) {
                String key = e.getKey();
                String value = e.getValue() == null ? "" : e.getValue();
                if (!value.isEmpty()) {
                    desired.put(key, value);                 // move/remap админа — не трогаем
                }
            }
        }
        desired.put(relPath, "");
        if (!desired.equals(actionFile.files)) {
            actionFile.files = desired;
            return true;
        }
        return false;
    }

    /** Перепривязка подхваченной ручной записи: убрать известные пути мода
     *  (сравнение без учёта регистра), добавить актуальный. */
    private static boolean rebindAdopted(OptionalFile file, Set<String> knownPaths, String relPath) {
        if (file.actions == null) file.actions = new ArrayList<>();
        String lowerRel = lowerPath(relPath);
        Set<String> lowerKnown = lowerPaths(knownPaths);
        // Уже привязано и переименований не было?
        boolean hasCurrent = false;
        boolean hasStale = false;
        for (OptionalAction action : file.actions) {
            if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
            for (String path : actionFile.files.keySet()) {
                String lower = lowerPath(path);
                if (!lowerKnown.contains(lower)) continue;
                if (lower.equals(lowerRel)) hasCurrent = true;
                else hasStale = true;
            }
        }
        if (hasCurrent && !hasStale) return false;
        OptionalActionFile target = null;
        for (OptionalAction action : file.actions) {
            if (!(action instanceof OptionalActionFile actionFile)) continue;
            if (target == null) target = actionFile;
            if (actionFile.files != null && !actionFile.files.isEmpty()) {
                // копия вместо удаления по месту: files может быть immutable
                // (профиль, собранный в памяти, а не прочитанный с диска)
                Map<String, String> copy = new LinkedHashMap<>(actionFile.files);
                copy.keySet().removeIf(path -> lowerKnown.contains(lowerPath(path)));
                actionFile.files = copy;
            }
        }
        if (target == null) {
            target = new OptionalActionFile(new LinkedHashMap<>());
            file.actions.add(target);
        }
        if (target.files == null) target.files = new LinkedHashMap<>();
        target.files.put(relPath, "");
        return true;
    }

    /**
     * Имя для новой записи: приоритет — стабильный идентификатор с сервиса
     * (slug), затем modId из jar, затем синтетический id/имя файла.
     * Дедуплицируется против существующих записей и других модов.
     */
    private String resolveName(Set<OptionalFile> optionals, Modpack modpack, ModEntry entry) {
        String base = null;
        String how;
        boolean syntheticId = entry.id != null && entry.source != null && entry.projectId != null
                && entry.id.equals(entry.source + "-" + entry.projectId);
        if (entry.id != null && !entry.id.isBlank() && !syntheticId) {
            base = entry.id;
            how = "service slug";
        } else {
            String jarId = ModJarInfo.sanitizeId(entry.installedModId);
            if (jarId != null) {
                base = jarId;
                how = "jar modId";
            } else if (entry.id != null && !entry.id.isBlank()) {
                base = entry.id;
                how = "id";
            } else {
                String jarName = ModJarInfo.sanitizeId(entry.installedModName);
                if (jarName != null) {
                    base = jarName;
                    how = "jar name";
                } else if (entry.installedFileName != null) {
                    String stem = entry.installedFileName;
                    int dot = stem.lastIndexOf('.');
                    if (dot > 0) stem = stem.substring(0, dot);
                    base = ModJarInfo.sanitizeId(stem);
                    how = "file name";
                } else {
                    base = "mod";
                    how = "fallback";
                }
            }
        }
        String candidate = base;
        for (int i = 2; nameTaken(optionals, modpack, entry, candidate); i++) {
            candidate = base + "-" + i;
        }
        logger.info("[ModsSite] updateOptional entry '{}' created for mod '{}' (name from {})",
                candidate, entry.id, how);
        return candidate;
    }

    private static boolean nameTaken(Set<OptionalFile> optionals, Modpack modpack, ModEntry self, String name) {
        for (OptionalFile file : optionals) {
            if (file != null && name.equals(file.name)) return true;
        }
        for (ModEntry other : modpack.mods) {
            if (other != null && other != self && name.equals(other.optionalName())) return true;
        }
        return false;
    }

    /** Человекочитаемое описание (устанавливается только при создании записи). */
    private static String describe(ModEntry entry) {
        String s = entry.note != null && !entry.note.isBlank() ? entry.note : entry.description;
        if (s == null || s.isBlank()) s = entry.name;          // имя с сервиса
        if (s == null || s.isBlank()) s = entry.installedModName; // имя из jar
        if (s == null || s.isBlank()) s = entry.id;
        if (s == null) s = "";
        s = s.replace('\n', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** Зависимости/конфликты своей записи: ссылки на фактические имена записей целей. */
    private boolean updateRelations(OptionalFile file, ModEntry entry, Map<String, UpsertResult> resolved) {
        return updateRelationsLists(file, entry.dependencies, entry.conflicts, resolved);
    }

    /** Записать в entry зависимости/конфликты по спискам id модов (для групповых
     *  записей список — объединение по всем членам группы). */
    private boolean updateRelationsLists(OptionalFile file, List<String> dependencyIds,
                                         List<String> conflictIds, Map<String, UpsertResult> resolved) {
        String selfName = file.name;
        List<String> depNames = relationNames(dependencyIds, resolved, selfName);
        List<String> conflictNames = relationNames(conflictIds, resolved, selfName);
        OptionalDepend[] deps = depNames.isEmpty() ? null : buildDepends(depNames);
        OptionalDepend[] conflicts = conflictNames.isEmpty() ? null : buildDepends(conflictNames);
        boolean changed = false;
        if (!sameDepends(file.dependenciesFile, deps)) {
            file.dependenciesFile = deps;
            changed = true;
        }
        if (!sameDepends(file.conflictFile, conflicts)) {
            file.conflictFile = conflicts;
            changed = true;
        }
        return changed;
    }

    /** Имена записей целей по id модов; пропускаем отсутствующих и себя. */
    private static List<String> relationNames(List<String> ids, Map<String, UpsertResult> resolved, String selfName) {
        List<String> names = new ArrayList<>();
        if (ids == null) return names;
        for (String id : ids) {
            if (id == null) continue;
            UpsertResult target = resolved.get(id);
            if (target == null || target.file == null) continue; // у цели нет своей записи
            if (selfName.equals(target.file.name)) continue;
            if (!names.contains(target.file.name)) names.add(target.file.name);
        }
        return names;
    }

    /** Массив ссылок по именам. */
    private static OptionalDepend[] buildDepends(List<String> names) {
        List<OptionalDepend> result = new ArrayList<>();
        for (String name : names) {
            OptionalDepend depend = new OptionalDepend();
            depend.name = name;
            result.add(depend);
        }
        return result.isEmpty() ? null : result.toArray(new OptionalDepend[0]);
    }

    /** Сравнение массивов OptionalDepend по именам (equals у них нет). */
    private static boolean sameDepends(OptionalDepend[] a, OptionalDepend[] b) {
        if (Objects.equals(a, b)) return true;
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            String an = a[i] == null ? null : a[i].name;
            String bn = b[i] == null ? null : b[i].name;
            if (!Objects.equals(an, bn)) return false;
        }
        return true;
    }

    /** Убрать следы мода: свою запись целиком, из чужой — только его пути. */
    private boolean removeEntryInternal(Set<OptionalFile> optionals, Modpack modpack, ModEntry entry) {
        // 1. Своя запись — ТОЛЬКО если она не разделяемая (группа опциональных
        //    с несколькими файлами остаётся: удаляется лишь путь этого мода)
        OptionalFile owned = findByName(optionals, entry.optionalName());
        if (owned != null && !bindsOtherEntry(modpack, owned, entry)) {
            optionals.remove(owned);
            return true;
        }
        // 2. Чужая запись с привязкой к нашему файлу: вычистить наши пути;
        //    полностью убрать её, если файловых привязок не осталось и
        //    других действий (jvmArgs и т.п.) нет — иначе останется мёртвый чекбокс
        Set<String> knownPaths = knownRelativePaths(modpack, entry);
        if (knownPaths.isEmpty()) return false;
        Set<String> lowerKnown = lowerPaths(knownPaths);
        boolean changed = false;
        List<OptionalFile> toDrop = new ArrayList<>();
        for (OptionalFile file : optionals) {
            if (file == null) continue;
            boolean touched = false;
            if (file.actions != null) {
                for (OptionalAction action : file.actions) {
                    if (!(action instanceof OptionalActionFile actionFile) || actionFile.files == null) continue;
                    if (actionFile.files.isEmpty()) continue;
                    Map<String, String> copy = new LinkedHashMap<>(actionFile.files);
                    if (copy.keySet().removeIf(path -> lowerKnown.contains(lowerPath(path)))) {
                        actionFile.files = copy; // копия: files может быть immutable
                        touched = true;
                    }
                }
            }
            if (!touched) continue;
            changed = true;
            boolean hasFiles = false;
            boolean hasOtherActions = false;
            if (file.actions != null) {
                for (OptionalAction action : file.actions) {
                    if (action instanceof OptionalActionFile actionFile) {
                        if (actionFile.files != null && !actionFile.files.isEmpty()) hasFiles = true;
                    } else {
                        hasOtherActions = true;
                    }
                }
            }
            if (!hasFiles && !hasOtherActions) {
                toDrop.add(file);
            }
        }
        if (!toDrop.isEmpty()) {
            optionals.removeAll(toDrop);
        }
        return changed;
    }

    /**
     * Сохранить profile.json тем же способом, что и LocalProfilesProvider
     * (configGson с адаптерами OptionalAction), но атомарно.
     * При стороннем провайдере профилей или вне LaunchServer — правка
     * только в памяти (сервер отдаёт живые объекты, диск не трогаем).
     */
    private void saveProfile(ClientProfile profile) throws IOException {
        try {
            profile.updateOptionalGraph();
        } catch (Throwable t) {
            logger.debug("[ModsSite] updateOptionalGraph failed (ignored)", t);
        }
        if (server == null || server.config == null
                || !(server.config.profilesProvider instanceof LocalProfilesProvider local)) {
            return;
        }
        if (Launcher.gsonManager == null || Launcher.gsonManager.configGson == null) {
            return;
        }
        Path path = null;
        for (ProfilesProvider.UncompletedProfile p : local.getProfiles(null)) {
            if (p instanceof LocalProfilesProvider.LocalProfile lp && lp.getProfile() == profile) {
                path = lp.getConfigPath();
                break;
            }
        }
        if (path == null) return;
        OptionalAction.registerProviders(); // идемпотентно; нужен адаптер "type":"file"
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (Writer writer = IOHelper.newWriter(tmp)) {
            Launcher.gsonManager.configGson.toJson(profile, writer);
        }
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        logger.info("[ModsSite] Profile '{}' updated (updateOptional synced)", profile.getDir());
    }
}
