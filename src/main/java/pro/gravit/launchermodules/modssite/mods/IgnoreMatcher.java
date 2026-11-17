package pro.gravit.launchermodules.modssite.mods;

import pro.gravit.launchermodules.modssite.ModsSiteConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Шаблоны игнорирования файлов/папок в папках контента (mods/resourcepacks/
 * shaderpacks) — синтаксис как в .gitignore.
 *
 * <p>Зачем: в папке модов живут служебные файлы и папки других инструментов
 * (например, скрытая папка {@code .index} от Sinytra Connector или кэши
 * загрузчиков), которые не являются контентом модпака. Модуль не должен
 * тратить на них силы: не показывать «лишними», не читать метаданные,
 * не хэшировать и не слать запросы в Modrinth/CurseForge при импорте.
 *
 * <p>Поддерживаемый синтаксис (подмножество gitignore):
 * <ul>
 *   <li>один шаблон — одна строка; пустые строки и комментарии ({@code #})
 *       игнорируются;</li>
 *   <li>{@code *} — любое число символов внутри одного сегмента пути,
 *       {@code ?} — один символ, {@code [abc]} / {@code [a-z]} — класс
 *       символов;</li>
 *   <li>{@code **} — любое число сегментов пути (например,
 *       {@code build/**} — всё внутри папки build);</li>
 *   <li>шаблон БЕЗ слэша сравнивается с именем файла/папки на ЛЮБОМ
 *       уровне ({@code .index} сработает и в корне, и в подпапке);</li>
 *   <li>шаблон СО слэшом (кроме завершающего) привязан к корню папки
 *       контента ({@code /dummy.jar} — только файл dummy.jar в корне);</li>
 *   <li>завершающий {@code /} — только папки ({@code .index/});</li>
 *   <li>ведущий {@code !} — исключение из игнора (последнее подходящее
 *       правило выигрывает, как в gitignore).</li>
 * </ul>
 *
 * <p>Сопоставление регистрозависимо на Linux и регистронезависимо на
 * Windows/Mac (как реальный git) — для простоты везде без учёта регистра:
 * в путях контента модпаков имена почти всегда ASCII.
 */
public final class IgnoreMatcher {

    /** Пустой матчер (ничего не игнорирует) — переиспользуется, чтобы не аллоцировать. */
    private static final IgnoreMatcher EMPTY = new IgnoreMatcher(List.of());

    private final List<Rule> rules;

    private IgnoreMatcher(List<Rule> rules) {
        this.rules = rules;
    }

    /** Матчер из списка шаблонов (как в конфиге); null/пустой список — пустой матчер. */
    public static IgnoreMatcher of(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return EMPTY;
        List<Rule> rules = new ArrayList<>();
        for (String raw : patterns) {
            Rule rule = Rule.parse(raw);
            if (rule != null) rules.add(rule);
        }
        if (rules.isEmpty()) return EMPTY;
        return new IgnoreMatcher(rules);
    }

    /** Матчер для типа контента (mod/resourcepack/shader) по настройкам модуля. */
    public static IgnoreMatcher forKind(ModsSiteConfig config, String kind) {
        if (config == null || config.mods == null) return EMPTY;
        return of(Kinds.ignorePatterns(config, kind));
    }

    /** Игнорирует ли матчер хоть что-нибудь. */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * Подпадает ли путь под шаблоны. Путь — относительный от папки контента,
     * с разделителем {@code /} (для верхнего уровня — просто имя файла/папки).
     *
     * @param path      относительный путь (не null)
     * @param directory true, если это папка (для правил «только папки»)
     */
    public boolean matches(String path, boolean directory) {
        if (rules.isEmpty() || path == null || path.isBlank()) return false;
        String normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return false;
        boolean ignored = false;
        // последнее подходящее правило выигрывает (семантика gitignore)
        for (Rule rule : rules) {
            if (rule.matches(normalized, directory)) {
                ignored = !rule.negative;
            }
        }
        return ignored;
    }

    /** Удобство: подхват файла/папки верхнего уровня по одному имени. */
    public boolean matchesName(String name, boolean directory) {
        return matches(name, directory);
    }

    // ======================= Правило =======================

    private static final class Rule {
        final Pattern pattern;
        final boolean negative;   // ведущий '!'
        final boolean dirOnly;    // завершающий '/'
        final boolean matchBase;  // без '/' в шаблоне — сравнение имени на любом уровне

        Rule(Pattern pattern, boolean negative, boolean dirOnly, boolean matchBase) {
            this.pattern = pattern;
            this.negative = negative;
            this.dirOnly = dirOnly;
            this.matchBase = matchBase;
        }

        boolean matches(String path, boolean directory) {
            if (dirOnly && !directory) return false;
            if (matchBase) {
                // сравнить каждый сегмент пути с шаблоном (кроме последнего,
                // если это файл: gitignore сравнивает и имена папок по пути)
                int start = 0;
                int slash = path.indexOf('/');
                while (start <= path.length()) {
                    int end = slash < 0 ? path.length() : slash;
                    if (pattern.matcher(path.substring(start, end)).matches()) {
                        return true;
                    }
                    if (slash < 0) break;
                    start = slash + 1;
                    slash = path.indexOf('/', start);
                }
                return false;
            }
            return pattern.matcher(path).matches();
        }

        /** Разобрать строку шаблона; null — пустая строка/комментарий. */
        static Rule parse(String raw) {
            if (raw == null) return null;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) return null;
            boolean negative = false;
            if (line.startsWith("!")) {
                negative = true;
                line = line.substring(1).strip();
                if (line.isEmpty()) return null;
            }
            boolean dirOnly = false;
            if (line.endsWith("/")) {
                dirOnly = true;
                while (line.endsWith("/")) line = line.substring(0, line.length() - 1);
                if (line.isEmpty()) return null;
            }
            boolean anchored = false;
            if (line.startsWith("/")) {
                anchored = true;
                while (line.startsWith("/")) line = line.substring(1);
                if (line.isEmpty()) return null;
            }
            boolean containsSlash = line.indexOf('/') >= 0;
            boolean matchBase = !anchored && !containsSlash;
            String regex = buildRegex(line);
            if (regex == null) return null;
            return new Rule(Pattern.compile(regex, Pattern.CASE_INSENSITIVE),
                    negative, dirOnly, matchBase);
        }

        /** Шаблон gitignore → regex (полный путь, сегменты разделены '/'). */
        static String buildRegex(String pattern) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            int length = pattern.length();
            while (i < length) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    if (i + 1 < length && pattern.charAt(i + 1) == '*') {
                        // "**" — любое число сегментов
                        if (i + 2 < length && pattern.charAt(i + 2) == '/') {
                            // "**/" — ноль или больше сегментов с завершающим слэшем
                            // (a/**/b покрывает и a/b)
                            sb.append("(?:[^/]+/)*");
                            i += 3;
                        } else {
                            sb.append(".*");
                            i += 2;
                        }
                    } else {
                        // "*" — всё, кроме '/'
                        sb.append("[^/]*");
                        i++;
                    }
                } else if (c == '?') {
                    sb.append("[^/]");
                    i++;
                } else if (c == '[') {
                    // класс символов — перенести как есть до ']'
                    int close = pattern.indexOf(']', i + 1);
                    if (close < 0) return null; // незакрытый класс — игнорируем правило
                    String inner = pattern.substring(i + 1, close);
                    if (inner.startsWith("!")) {
                        // в regex негативный класс — '^'
                        inner = "^" + inner.substring(1);
                    }
                    sb.append('[').append(inner).append(']');
                    i = close + 1;
                } else if (isRegexMeta(c)) {
                    sb.append('\\').append(c);
                    i++;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString();
        }

        /** Символ, требующий экранирования в regex вне классов символов. */
        static boolean isRegexMeta(char c) {
            return "\\.[]{}()+-^$|".indexOf(c) >= 0;
        }
    }
}
