package by.vs.dnaamrscreener.service;

import by.vs.dnaamrscreener.support.RedisContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIf("by.vs.dnaamrscreener.support.RedisContainerSupport#dockerAvailable")
class DbVersionServiceIT {

    private static final String PREFIX = "k21";

    @Container
    static GenericContainer<?> redis = RedisContainerSupport.createRedisContainer();

    private StringRedisTemplate geneRedis;
    private DbVersionService service;

    @BeforeEach
    void setUp() {
        geneRedis = RedisContainerSupport.createTemplate(redis);
        geneRedis.getConnectionFactory().getConnection().serverCommands().flushAll();
        service = new DbVersionService(geneRedis, new KmerPrefixService(geneRedis));
    }

    @Test
    void defaults_to_blue_and_persists_switch() {
        assertEquals(DbVersionService.BLUE, service.getActiveSlot(PREFIX));
        assertEquals("k21:blue:", service.activeDataPrefix(PREFIX));

        String next = service.switchActive(PREFIX);
        assertEquals(DbVersionService.GREEN, next);

        DbVersionService fresh = new DbVersionService(geneRedis, new KmerPrefixService(geneRedis));
        assertEquals(DbVersionService.GREEN, fresh.getActiveSlot(PREFIX));
        assertEquals(DbVersionService.BLUE, fresh.getInactiveSlot(PREFIX));
    }

    @Test
    void setActiveSlot_roundtrip() {
        service.setActiveSlot(PREFIX, DbVersionService.GREEN);
        assertEquals("k21:green:", service.activeDataPrefix(PREFIX));
        assertEquals("k21:blue:", service.inactiveDataPrefix(PREFIX));
    }

    @Test
    void clearSlot_removes_keys_with_prefix() {
        service.setActiveSlot(PREFIX, DbVersionService.BLUE);
        geneRedis.opsForValue().set("k21:blue:gene:meta:test1", "x");
        geneRedis.opsForValue().set("k21:blue:k21lsh:b:0:h:1", "y");
        geneRedis.opsForValue().set("k21:green:gene:meta:keep", "z");

        long deleted = service.clearSlot(PREFIX, DbVersionService.BLUE);
        assertTrue(deleted >= 2);
        assertNull(geneRedis.opsForValue().get("k21:blue:gene:meta:test1"));
        assertEquals("z", geneRedis.opsForValue().get("k21:green:gene:meta:keep"));
    }

    @Test
    void status_reports_key_counts_per_slot() {
        service.setActiveSlot(PREFIX, DbVersionService.BLUE);
        geneRedis.opsForValue().set("k21:blue:a", "1");
        geneRedis.opsForValue().set("k21:blue:b", "2");
        geneRedis.opsForValue().set("k21:green:c", "3");

        DbVersionService.DbStatus status = service.status(PREFIX);
        assertEquals(PREFIX, status.prefix());
        assertEquals(DbVersionService.BLUE, status.activeSlot());
        assertEquals(2L, status.blueKeyCount());
        assertEquals(1L, status.greenKeyCount());
    }
}
