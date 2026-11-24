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

    @Container
    static GenericContainer<?> redis = RedisContainerSupport.createRedisContainer();

    private StringRedisTemplate geneRedis;
    private DbVersionService service;

    @BeforeEach
    void setUp() {
        geneRedis = RedisContainerSupport.createTemplate(redis);
        geneRedis.getConnectionFactory().getConnection().serverCommands().flushAll();
        service = new DbVersionService(geneRedis);
    }

    @Test
    void defaults_to_blue_and_persists_switch() {
        assertEquals(DbVersionService.BLUE, service.getActiveSlot());
        assertEquals("blue:", service.activePrefix());

        String next = service.switchActive();
        assertEquals(DbVersionService.GREEN, next);

        // new instance should read from Redis, not only local cache
        DbVersionService fresh = new DbVersionService(geneRedis);
        assertEquals(DbVersionService.GREEN, fresh.getActiveSlot());
        assertEquals(DbVersionService.BLUE, fresh.getInactiveSlot());
    }

    @Test
    void setActiveSlot_roundtrip() {
        service.setActiveSlot(DbVersionService.GREEN);
        assertEquals("green:", service.activePrefix());
        assertEquals("blue:", service.inactivePrefix());
    }

    @Test
    void clearSlot_removes_keys_with_prefix() {
        service.setActiveSlot(DbVersionService.BLUE);
        geneRedis.opsForValue().set("blue:gene:meta:test1", "x");
        geneRedis.opsForValue().set("blue:lsh:b:0:h:1", "y");
        geneRedis.opsForValue().set("green:gene:meta:keep", "z");

        long deleted = service.clearSlot(DbVersionService.BLUE);
        assertTrue(deleted >= 2);
        assertNull(geneRedis.opsForValue().get("blue:gene:meta:test1"));
        assertEquals("z", geneRedis.opsForValue().get("green:gene:meta:keep"));
    }
}
