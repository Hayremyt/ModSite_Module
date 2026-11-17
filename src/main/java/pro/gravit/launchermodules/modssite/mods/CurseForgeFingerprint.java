package pro.gravit.launchermodules.modssite.mods;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Отпечаток файла CurseForge: murmur2 (32 бита, seed 1) по содержимому файла
 * с фирменной модификацией CurseForge — из входа УДАЛЯЮТСЯ пробельные символы
 * (tab 9, LF 10, CR 13, пробел 32) перед хэшированием.
 *
 * Референс — реализация packwiz (curseforge/murmur2/hash.go, «CF modification:
 * strips whitespace characters») поверх канонического MurmurHash2 из SMHasher;
 * именно так считаются fingerprints в /v1/fingerprints/{gameId}.
 */
public final class CurseForgeFingerprint {

    private CurseForgeFingerprint() {
    }

    /** Отпечаток файла (десятичное число без знака); -1 при ошибке чтения. */
    public static long of(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            return of(input.readAllBytes());
        } catch (IOException e) {
            return -1;
        }
    }

    /** Отпечаток байтов (десятичное число без знака). */
    public static long of(byte[] data) {
        return murmur2(stripWhitespace(data), 1) & 0xFFFFFFFFL;
    }

    private static byte[] stripWhitespace(byte[] data) {
        int kept = 0;
        for (byte b : data) {
            if (!isWhitespace(b)) kept++;
        }
        if (kept == data.length) return data;
        byte[] result = new byte[kept];
        int index = 0;
        for (byte b : data) {
            if (!isWhitespace(b)) {
                result[index++] = b;
            }
        }
        return result;
    }

    private static boolean isWhitespace(byte b) {
        return b == 9 || b == 10 || b == 13 || b == 32;
    }

    /** Канонический MurmurHash2 (32 бита), совместим с SMHasher/go-murmur. */
    static int murmur2(byte[] data, int seed) {
        int length = data.length;
        final int m = 0x5bd1e995;
        final int r = 24;
        int h = seed ^ length;
        int chunks = length >> 2;
        for (int i = 0; i < chunks; i++) {
            int base = i << 2;
            int k = (data[base] & 0xFF)
                    | ((data[base + 1] & 0xFF) << 8)
                    | ((data[base + 2] & 0xFF) << 16)
                    | ((data[base + 3] & 0xFF) << 24);
            k *= m;
            k ^= k >>> r;
            k *= m;
            h *= m;
            h ^= k;
        }
        int tail = chunks << 2;
        int remainder = length & 3;
        // Хвост читается как switch с fallthrough в референсе:
        // case 3: xor data[2]<<16; case 2: xor data[1]<<8; case 1: xor data[0]; h *= m
        if (remainder == 3) {
            h ^= (data[tail + 2] & 0xFF) << 16;
        }
        if (remainder >= 2) {
            h ^= (data[tail + 1] & 0xFF) << 8;
        }
        if (remainder >= 1) {
            h ^= (data[tail] & 0xFF);
            h *= m;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h;
    }
}
