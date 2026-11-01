package by.vs.dnaamrscreener.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DbVersionService {

    public static final String ACTIVE_KEY = "db:active";
    public static final String BLUE = "blue";
    public static final String GREEN = "green";

    private final StringRedisTemplate geneRedis;

    private final AtomicReference<String> activeSlotCache = new AtomicReference<>();

    public DbVersionService(@Qualifier("geneRedisTemplate") StringRedisTemplate geneRedis) {
        this.geneRedis = geneRedis;
    }

    public String getActiveSlot() {
        String cached = activeSlotCache.get();
        if (cached != null) {
            return cached;
        }
        return refreshActiveSlot();
    }

    public synchronized String refreshActiveSlot() {
        String active = geneRedis.opsForValue().get(ACTIVE_KEY);
        if (active == null || (!BLUE.equals(active) && !GREEN.equals(active))) {
            active = BLUE;
        }
        activeSlotCache.set(active);
        return active;
    }

    public String getInactiveSlot() {
        return BLUE.equals(getActiveSlot()) ? GREEN : BLUE;
    }

    public String switchActive() {
        String newActive = getInactiveSlot();
        geneRedis.opsForValue().set(ACTIVE_KEY, newActive);
        activeSlotCache.set(newActive);
        return newActive;
    }

    public void setActiveSlot(String slot) {
        if (!BLUE.equals(slot) && !GREEN.equals(slot)) {
            throw new IllegalArgumentException("slot must be 'blue' or 'green'");
        }
        geneRedis.opsForValue().set(ACTIVE_KEY, slot);
        activeSlotCache.set(slot);
    }

    public String activePrefix() {
        return getActiveSlot() + ":";
    }

    public String inactivePrefix() {
        return getInactiveSlot() + ":";
    }

    public long clearSlot(String slot) {
        if (!BLUE.equals(slot) && !GREEN.equals(slot)) {
            throw new IllegalArgumentException("slot must be 'blue' or 'green'");
        }

        long deleted = 0;
        ScanOptions options = ScanOptions.scanOptions()
                .match(slot + ":*")
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

    public DbStatus status() {
        String active = getActiveSlot();
        long blueKeys = countKeys(BLUE);
        long greenKeys = countKeys(GREEN);
        return new DbStatus(active, blueKeys, greenKeys);
    }

    private long countKeys(String slot) {
        long count = 0;
        ScanOptions options = ScanOptions.scanOptions()
                .match(slot + ":*")
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

    public record DbStatus(String activeSlot, long blueKeyCount, long greenKeyCount) {}
}