package by.vs.dnaamrscreener.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DbVersionService {

    public static final String BLUE = "blue";
    public static final String GREEN = "green";

    private final StringRedisTemplate geneRedis;
    private final KmerPrefixService kmerPrefixService;
    private final ConcurrentHashMap<String, String> activeCache = new ConcurrentHashMap<>();

    public DbVersionService(@Qualifier("geneRedisTemplate") StringRedisTemplate geneRedis, KmerPrefixService kmerPrefixService) {
        this.geneRedis = geneRedis;
        this.kmerPrefixService = kmerPrefixService;
    }

    private static String activeKey(String prefix) {
        return "db:" + prefix + ":active";
    }

    public String getActiveSlot(String prefix) {
        kmerPrefixService.requirePrefix(prefix);
        String cached = activeCache.get(prefix);
        if (cached != null) return cached;
        return refreshActiveSlot(prefix);
    }

    public String refreshActiveSlot(String prefix) {
        kmerPrefixService.requirePrefix(prefix);
        String active = geneRedis.opsForValue().get(activeKey(prefix));
        if (!BLUE.equals(active) && !GREEN.equals(active)) {
            active = BLUE;
        }
        activeCache.put(prefix, active);
        return active;
    }

    public String getInactiveSlot(String prefix) {
        return BLUE.equals(getActiveSlot(prefix)) ? GREEN : BLUE;
    }

    public String switchActive(String prefix) {
        kmerPrefixService.requirePrefix(prefix);
        String next = getInactiveSlot(prefix);
        geneRedis.opsForValue().set(activeKey(prefix), next);
        activeCache.put(prefix, next);
        return next;
    }

    public void setActiveSlot(String prefix, String slot) {
        kmerPrefixService.requirePrefix(prefix);
        if (!BLUE.equals(slot) && !GREEN.equals(slot)) {
            throw new IllegalArgumentException("slot must be blue|green");
        }
        geneRedis.opsForValue().set(activeKey(prefix), slot);
        activeCache.put(prefix, slot);
    }

    public String activeDataPrefix(String prefix) {
        return prefix + ":" + getActiveSlot(prefix) + ":";
    }

    public String inactiveDataPrefix(String prefix) {
        return prefix + ":" + getInactiveSlot(prefix) + ":";
    }

    public long clearSlot(String prefix, String slot) {
        kmerPrefixService.requirePrefix(prefix);
        if (!BLUE.equals(slot) && !GREEN.equals(slot)) {
            throw new IllegalArgumentException("slot must be blue|green");
        }
        long deleted = 0;
        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + ":" + slot + ":*")
                .count(1000)
                .build();
        try (Cursor<String> cursor = geneRedis.scan(options)) {
            List<String> batch = new ArrayList<>(500);
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 500) {
                    deleted += geneRedis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                deleted += geneRedis.delete(batch);
            }
        }
        return deleted;
    }

    public DbStatus status(String prefix) {
        kmerPrefixService.requirePrefix(prefix);
        return new DbStatus(
                prefix,
                getActiveSlot(prefix),
                countKeys(prefix, BLUE),
                countKeys(prefix, GREEN)
        );
    }

    private long countKeys(String prefix, String slot) {
        long count = 0;
        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + ":" + slot + ":*")
                .count(1000)
                .build();
        try (Cursor<String> cursor = geneRedis.scan(options)) {
            while (cursor.hasNext()) {
                cursor.next();
                count++;
            }
        }
        return count;
    }

    public record DbStatus(String prefix, String activeSlot, long blueKeyCount, long greenKeyCount) {}
}