package pro.gravit.launchermodules.modssite.mods;

import java.util.ArrayList;
import java.util.List;

/**
 * Запись о моде в модпаке профиля.
 * Ключевой момент: installedFileName хранит имя СКАЧАННОГО файла — при обновлении
 * имя у файла на Modrinth/CurseForge меняется, поэтому старый файл удаляем по имени,
 * а не вычисляем из идентификатора.
 */
public class ModEntry {
    /** Локальный идентификатор мода в модпаке (обычно slug). */
    public String id;
    /**
     * Тип контента: "mod" | "resourcepack" | "shader" (null/пусто — "mod",
     * так выглядят записи старых версий модуля). Определяет целевую папку
     * профиля (mods / resourcepacks / shaderpacks) и фильтры поиска.
     */
    public String kind;
    /** Источник: "modrinth" | "curseforge". */
    public String source;
    /** ID/слаг проекта в источнике. */
    public String projectId;
    /** Человекочитаемое имя. */
    public String name;
    public String iconUrl;
    public String description;
    /** Опциональный мод (админ может включать/выключать). */
    public boolean optional;
    /** Включён ли в сборку (для опциональных). */
    public boolean enabled = true;
    /** Канал обновлений: release | beta | alpha. */
    public String channel = "release";
    /** Зависимости: локальные id других модов этого модпака. */
    public List<String> dependencies = new ArrayList<>();
    /** Конфликты: локальные id. */
    public List<String> conflicts = new ArrayList<>();
    /**
     * Могильники связей, вычищенных администратором на сайте: автоподтяжка
     * из API источников (linkDependencies) больше НЕ возвращает их. Формат
     * записей: "dep:&lt;id&gt;" (зависимость от мода &lt;id&gt;) и "conf:&lt;id&gt;"
     * (конфликт с модом &lt;id&gt;). Если админ заново ставит связь в настройках —
     * могильник снимается.
     */
    public List<String> linkTombstones = new ArrayList<>();
    /**
     * Могильники групп: имена групп, из которых мод УДАЛЁН администратором
     * на сайте (кнопкой «— Без группы —»). Автоподхват групп из профиля
     * (adoptProfileOptionals) больше НЕ возвращает мод в такую группу — иначе
     * файл мода, ещё привязанный в групповой записи, снова «усыновлял» бы его
     * сразу после удаления. Заново выбранная на сайте группа снимает могильник.
     */
    public List<String> groupTombstones = new ArrayList<>();
    /**
     * Могильник «обязательности»: админ осознанно сделал запись ОБЯЗАТЕЛЬНОЙ
     * в настройках на сайте (снял галочку «Опциональный»). Автоподхват
     * опциональности из профиля (adoptProfileOptionals) больше НЕ возвращает
     * такую запись в опциональные — иначе привязка файла в updateOptional
     * (оставшаяся до согласования или сделанная вручную) откатывала бы выбор
     * админа при каждом открытии вкладки. Галочка «Опциональный» на сайте
     * могильник снимает.
     */
    public boolean requiredTombstone;
    /** Заметка администратора (показывается на сайте). */
    public String note;

    // ---- Установленное состояние (сохраняется!) ----
    public String installedFileName;
    public String installedVersionId;
    public String installedVersionNumber;
    public String installedSha1;
    public long installedAt;
    /** modId из метаданных установленного jar (fabric.mod.json/mods.toml/mcmod.info). */
    public String installedModId;
    /** Человекочитаемое имя мода из метаданных jar. */
    public String installedModName;
    /** Прежние имена установленного файла (для перепривязки updateOptional при обновлениях). */
    public List<String> previousFileNames = new ArrayList<>();
    /** Имя созданной записи updateOptional профиля (фиксируется один раз при создании). */
    public String profileOptionalName;
    /**
     * Группа опциональных модов: несколько опциональных модов с ОДИНАКОВЫМ
     * optionalGroup образуют группу с общим переключателем — на сайте и
     * ОДНОЙ записью updateOptional в профиле (одна строка чекбокса включает
     * все файлы группы). Заполняется автоматически при подхвате групповых
     * записей профиля (одна запись с несколькими файлами) или вручную
     * в настройках мода на сайте.
     */
    public String optionalGroup;

    /** Нормализованный тип контента ("mod" для null/пустого — записи старых версий). */
    public String kindOrMod() {
        return Kinds.normalize(kind);
    }

    /** Запомнить прежнее имя файла (вызывается перед сменой/удалением installedFileName). */
    public void pushPreviousFileName(String name) {
        if (name == null || name.isBlank()) return;
        if (previousFileNames == null) previousFileNames = new ArrayList<>();
        previousFileNames.removeIf(name::equals);
        previousFileNames.add(name);
        while (previousFileNames.size() > 8) {
            previousFileNames.remove(0);
        }
    }

    /** Имя записи updateOptional, под которым мод управляется в профиле. */
    public String optionalName() {
        return profileOptionalName != null && !profileOptionalName.isBlank() ? profileOptionalName : id;
    }

    // ---- Могильники связей (см. linkTombstones) ----

    /** Есть ли могильник: kind "dep" | "conf", targetId — id второго мода. */
    public boolean hasLinkTombstone(String kind, String targetId) {
        return linkTombstones != null && linkTombstones.contains(kind + ":" + targetId);
    }

    /** Поставить могильник (kind "dep" | "conf"). */
    public void addLinkTombstone(String kind, String targetId) {
        if (targetId == null || targetId.isBlank()) return;
        if (linkTombstones == null) linkTombstones = new ArrayList<>();
        String key = kind + ":" + targetId;
        if (!linkTombstones.contains(key)) linkTombstones.add(key);
    }

    /** Снять могильник (связь заново поставлена администратором). */
    public void removeLinkTombstone(String kind, String targetId) {
        if (linkTombstones == null) return;
        linkTombstones.remove(kind + ":" + targetId);
    }

    // ---- Могильники групп (см. groupTombstones) ----

    /** Заблокирована ли автоподтяжка мода в группу с таким именем. */
    public boolean hasGroupTombstone(String groupName) {
        return groupName != null && groupTombstones != null && groupTombstones.contains(groupName);
    }

    /** Поставить могильник группы (мод удалён из неё администратором). */
    public void addGroupTombstone(String groupName) {
        if (groupName == null || groupName.isBlank()) return;
        if (groupTombstones == null) groupTombstones = new ArrayList<>();
        if (!groupTombstones.contains(groupName)) groupTombstones.add(groupName);
    }

    /** Снять могильник группы (мод заново в неё объединён). */
    public void removeGroupTombstone(String groupName) {
        if (groupTombstones == null) return;
        groupTombstones.remove(groupName);
    }

    // ---- Кэш последней проверки обновлений ----
    public String latestVersionId;
    public String latestVersionNumber;
    public String latestFileName;
    public String latestSha1;
    public String latestUrl;
    public long latestDatePublished;
    public long checkedAt;
    /** Версия, обновление до которой отложено кнопкой «Отложить»: пока она
     *  последняя у источника — обновлением не считается. Новая версия снимает отсрочку. */
    public String skippedVersionId;

    public boolean isUpdateAvailable() {
        if (latestVersionId == null) return false;
        if (latestVersionId.equals(skippedVersionId)) return false;
        if (installedVersionId == null) return enabled;
        return !latestVersionId.equals(installedVersionId);
    }

    /** Отсрочка активна (последняя версия именно та, что отложена). */
    public boolean isPostponed() {
        return latestVersionId != null && latestVersionId.equals(skippedVersionId);
    }
}
