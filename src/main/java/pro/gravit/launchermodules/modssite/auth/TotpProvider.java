package pro.gravit.launchermodules.modssite.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP (RFC 6238): коды из приложения-аутентификатора (Google Authenticator и т.п.).
 * HMAC-SHA1, 6 цифр, 30 секунд, допуск ±1 шаг.
 */
public final class TotpProvider {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private TotpProvider() {
    }

    /** Сгенерировать новый base32-секрет (160 бит). */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    public static boolean verify(String code, String base32Secret) {
        if (code == null || base32Secret == null) return false;
        code = code.replace(" ", "").trim();
        if (!code.matches("\\d{6}")) return false;
        byte[] key = base32Decode(base32Secret);
        if (key == null) return false;
        long counter = System.currentTimeMillis() / 1000L / 30L;
        // Допуск ±1 шаг (±30 секунд)
        for (long c = counter - 1; c <= counter + 1; c++) {
            if (totp(key, c).equals(code)) return true;
        }
        return false;
    }

    private static String totp(byte[] key, long counter) {
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xFF);
            counter >>>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(data);
            int offset = digest[digest.length - 1] & 0x0F;
            int binary = ((digest[offset] & 0x7F) << 24)
                    | ((digest[offset + 1] & 0xFF) << 16)
                    | ((digest[offset + 2] & 0xFF) << 8)
                    | (digest[offset + 3] & 0xFF);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** otpauth:// URI для импорта в приложение-аутентификатор. */
    public static String otpauthUri(String secret, String login, String issuer) {
        return "otpauth://totp/" + urlEncode(issuer + ":" + login)
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=6&period=30";
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ---------------- Base32 ----------------

    public static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32_ALPHABET[(buffer >> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32_ALPHABET[(buffer << (5 - bits)) & 0x1F]);
        }
        return out.toString();
    }

    public static byte[] base32Decode(String s) {
        s = s.toUpperCase().replaceAll("[^A-Z2-7]", "");
        int outLen = s.length() * 5 / 8;
        byte[] out = new byte[outLen];
        int buffer = 0, bits = 0, index = 0;
        for (char c : s.toCharArray()) {
            int value = indexOf(c);
            if (value < 0) return null;
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out[index++] = (byte) ((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
                if (index >= outLen) break;
            }
        }
        return out;
    }

    private static int indexOf(char c) {
        for (int i = 0; i < BASE32_ALPHABET.length; i++) {
            if (BASE32_ALPHABET[i] == c) return i;
        }
        return -1;
    }

    public static void main(String[] args) { // smoketest
        String secret = generateSecret();
        byte[] decoded = base32Decode(secret);
        System.out.println(secret + " roundtrip=" + Arrays.equals(decoded, base32Decode(base32Encode(decoded))));
    }
}
