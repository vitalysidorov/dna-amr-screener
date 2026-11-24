package by.vs.dnaamrscreener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DbVersionServiceTest {

    private static final String PREFIX = "k21";
    private static final String ACTIVE_KEY = "db:k21:active";

    @Mock
    private StringRedisTemplate geneRedis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private DbVersionService service;

    @BeforeEach
    void setUp() {
        lenient().when(geneRedis.opsForValue()).thenReturn(valueOps);
        KmerPrefixService kmerPrefixService = new KmerPrefixService(geneRedis);
        service = new DbVersionService(geneRedis, kmerPrefixService);
    }

    @Test
    void defaults_to_blue_when_redis_empty() {
        when(valueOps.get(ACTIVE_KEY)).thenReturn(null);
        assertEquals(DbVersionService.BLUE, service.getActiveSlot(PREFIX));
        assertEquals("k21:blue:", service.activeDataPrefix(PREFIX));
        assertEquals(DbVersionService.GREEN, service.getInactiveSlot(PREFIX));
        assertEquals("k21:green:", service.inactiveDataPrefix(PREFIX));
    }

    @Test
    void uses_green_when_set_in_redis() {
        when(valueOps.get(ACTIVE_KEY)).thenReturn(DbVersionService.GREEN);
        assertEquals(DbVersionService.GREEN, service.getActiveSlot(PREFIX));
        assertEquals(DbVersionService.BLUE, service.getInactiveSlot(PREFIX));
    }

    @Test
    void invalid_redis_value_falls_back_to_blue() {
        when(valueOps.get(ACTIVE_KEY)).thenReturn("yellow");
        assertEquals(DbVersionService.BLUE, service.getActiveSlot(PREFIX));
    }

    @Test
    void switchActive_flips_slot() {
        when(valueOps.get(ACTIVE_KEY)).thenReturn(DbVersionService.BLUE);
        service.getActiveSlot(PREFIX);

        String next = service.switchActive(PREFIX);
        assertEquals(DbVersionService.GREEN, next);
        verify(valueOps).set(ACTIVE_KEY, DbVersionService.GREEN);
        assertEquals(DbVersionService.GREEN, service.getActiveSlot(PREFIX));
    }

    @Test
    void setActiveSlot_rejects_invalid() {
        assertThrows(IllegalArgumentException.class, () -> service.setActiveSlot(PREFIX, "red"));
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    void setActiveSlot_accepts_blue_green() {
        service.setActiveSlot(PREFIX, DbVersionService.GREEN);
        verify(valueOps).set(ACTIVE_KEY, DbVersionService.GREEN);
        assertEquals(DbVersionService.GREEN, service.getActiveSlot(PREFIX));
    }

    @Test
    void caches_active_slot_without_extra_redis_reads() {
        when(valueOps.get(ACTIVE_KEY)).thenReturn(DbVersionService.BLUE);
        service.getActiveSlot(PREFIX);
        service.getActiveSlot(PREFIX);
        service.activeDataPrefix(PREFIX);
        verify(valueOps, times(1)).get(ACTIVE_KEY);
    }

    @Test
    void blank_prefix_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.getActiveSlot(" "));
        assertThrows(IllegalArgumentException.class, () -> service.getActiveSlot(null));
    }

    @Test
    void independent_cache_per_prefix() {
        when(valueOps.get("db:k21:active")).thenReturn(DbVersionService.BLUE);
        when(valueOps.get("db:k17:active")).thenReturn(DbVersionService.GREEN);

        assertEquals(DbVersionService.BLUE, service.getActiveSlot("k21"));
        assertEquals(DbVersionService.GREEN, service.getActiveSlot("k17"));
        assertEquals("k21:blue:", service.activeDataPrefix("k21"));
        assertEquals("k17:green:", service.activeDataPrefix("k17"));
    }
}
