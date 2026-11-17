package pro.gravit.launchermodules.modssite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpRequest;
import pro.gravit.utils.helper.IOHelper;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class Utils {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final Gson GSON_COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Utils() {
    }

    /** Gson для конфигов модуля (совместим с configGson LaunchServer). */
    public static <T> T readJson(Path file, Class<T> clazz) throws IOException {
        try (BufferedReader reader = IOHelper.newReader(file)) {
            return GSON.fromJson(reader, clazz);
        }
    }

    public static void writeJsonAtomic(Path file, Object value) throws IOException {
        IOHelper.createParentDirs(file);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter writer = IOHelper.newWriter(tmp)) {
            GSON.toJson(value, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Экранирование HTML для встраивания текста в страницы. */
    public static String htmlEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> {
                    if (c < 32 && c != '\n' && c != '\t' && c != '\r') sb.append(' ');
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Реальный IP клиента: context.ip (проставляется NettyIpForwardHandler при
     * включённом netty.ipForwarding), иначе адрес сокета.
     */
    public static String clientIp(Channel channel, String contextIp) {
        if (contextIp != null && !contextIp.isEmpty()) {
            // X-Forwarded-For может содержать цепочку: берём первый (клиентский)
            int comma = contextIp.indexOf(',');
            String ip = (comma > 0 ? contextIp.substring(0, comma) : contextIp).trim();
            if (!ip.isEmpty()) return ip;
        }
        SocketAddress address = channel.remoteAddress();
        if (address instanceof InetSocketAddress isa) {
            return isa.getAddress() == null ? String.valueOf(address) : isa.getAddress().getHostAddress();
        }
        return String.valueOf(address);
    }

    /** Проверка, что строка похожа на IP/CIDR. */
    public static boolean isValidIpOrCidr(String s) {
        if (s == null || s.isEmpty() || s.length() > 50) return false;
        if (!s.matches("[0-9a-fA-F.:\\-/]+")) return false;
        return true;
    }

    public static boolean equalsConstantTime(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(utf8(a), utf8(b));
    }
}
