package pro.gravit.launchermodules.modssite.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limit по IP — скользящее окно. Два независимых лимита:
 *  api   — на все запросы API;
 *  login — на попытки входа (строже).
 */
public class RateLimiter {
    private static class Bucket {
        final Deque<Long> timestamps = new ArrayDeque<>();
    }

    /**
     * Предел числа бакетов на карту. При доверии к X-Forwarded-For атакующий
     * может подавать запросы с каждым разом новым «IP» — без предела карта
     * росла бы до следующего sweep (до 5 минут). Сверх предела: сначала
     * внеплановая чистка пустых бакетов, если не помогло — запрос проходит,
     * но не учитывается (fail-open: ограничитель частоты — сам защита,
     * из-за него жертвовать трафиком нельзя).
     */
    private static final int MAX_BUCKETS = 100_000;

    private final Map<String, Bucket> apiBuckets = new ConcurrentHashMap<>();
    private final Map<String, Bucket> loginBuckets = new ConcurrentHashMap<>();

    public boolean checkApi(String ip, int perMinute) {
        return check(apiBuckets, ip, perMinute);
    }

    public boolean checkLogin(String ip, int perMinute) {
        return check(loginBuckets, ip, perMinute);
    }

    private boolean check(Map<String, Bucket> buckets, String ip, int perMinute) {
        long now = System.currentTimeMillis();
        long windowMs = 60_000L;
        Bucket bucket = buckets.get(ip);
        if (bucket == null) {
            if (buckets.size() >= MAX_BUCKETS) {
                sweep(buckets, now);
                if (buckets.size() >= MAX_BUCKETS) {
                    return true; // fail-open, см. MAX_BUCKETS
                }
            }
            bucket = buckets.computeIfAbsent(ip, k -> new Bucket());
        }
        synchronized (bucket) {
            while (!bucket.timestamps.isEmpty() && now - bucket.timestamps.peekFirst() > windowMs) {
                bucket.timestamps.pollFirst();
            }
            if (bucket.timestamps.size() >= perMinute) {
                return false;
            }
            bucket.timestamps.addLast(now);
            return true;
        }
    }

    public void sweep() {
        long now = System.currentTimeMillis();
        sweep(apiBuckets, now);
        sweep(loginBuckets, now);
    }

    private void sweep(Map<String, Bucket> buckets, long now) {
        buckets.entrySet().removeIf(e -> {
            Bucket b = e.getValue();
            synchronized (b) {
                b.timestamps.removeIf(t -> now - t > 60_000L);
                return b.timestamps.isEmpty();
            }
        });
    }
}
