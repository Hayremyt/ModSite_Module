package pro.gravit.launchermodules.modssite;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;

/**
 * Уровень вывода логов модуля в консоль сервера (config: logging.consoleLevel).
 * <p>
 * Уровень применяется ко ВСЕМ логгерам модуля сразу: классы пишут через
 * LogManager.getLogger(НашКласс.class), а имена логгеров повторяют полные
 * имена классов — то есть все они дети одного корневого логгера пакета
 * "pro.gravit.launchermodules.modssite". Уровень родителя наследуется,
 * поэтому один setLevel меняет вывод всего модуля (включая будущие
 * классы) и не затрагивает ни лаунчер, ни другие модули.
 * <p>
 * Уровни (как в настройках сайта):
 * <ul>
 *   <li>"off"   — ничего;</li>
 *   <li>"error" — только ошибки;</li>
 *   <li>"warn"  — ошибки и предупреждения (по умолчанию);</li>
 *   <li>"all"   — весь вывод, включая info/debug.</li>
 * </ul>
 */
public final class ModsSiteLogLevels {
    public static final String OFF = "off";
    public static final String ERROR = "error";
    public static final String WARN = "warn";
    public static final String ALL = "all";

    /** Значение по умолчанию (и запасное для некорректных значений). */
    public static final String DEFAULT = WARN;

    /** Корневой логгер пакета модуля — уровень применяется ко всем классам модуля. */
    private static final String MODULE_LOGGER = "pro.gravit.launchermodules.modssite";

    private ModsSiteLogLevels() {
    }

    /** Корректное имя уровня? */
    public static boolean isValid(String level) {
        return OFF.equals(level) || ERROR.equals(level) || WARN.equals(level) || ALL.equals(level);
    }

    /** Привести к одному из четырёх уровней (мусор -> "warn"). */
    public static String normalize(String level) {
        if (level != null) {
            String v = level.trim().toLowerCase(java.util.Locale.ROOT);
            if (isValid(v)) return v;
        }
        return DEFAULT;
    }

    /** Применить уровень к логгерам модуля (некорректное значение — "warn"). */
    public static void apply(String level) {
        String normalized = normalize(level);
        try {
            Configurator.setLevel(MODULE_LOGGER, toLog4jLevel(normalized));
        } catch (Throwable ignored) {
            // log4j-core недоступен или контекст не найден — вывод остаётся как есть
        }
    }

    private static Level toLog4jLevel(String level) {
        return switch (level) {
            case OFF -> Level.OFF;
            case ERROR -> Level.ERROR;
            case ALL -> Level.DEBUG; // весь вывод модуля: ниже debug модуль не пишет
            default -> Level.WARN;
        };
    }
}
