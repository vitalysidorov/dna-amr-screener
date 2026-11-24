package by.vs.dnaamrscreener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class DbVersionServiceTest {

    @Mock
    private StringRedisTemplate geneRedis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private DbVersionService service;

    @BeforeEach
    void setUp() {
        // lenient: not every test hits opsForValue()
        lenient().when(geneRedis.opsForValue()).thenReturn(valueOps);
        service = new DbVersionService(geneRedis);
    }

    @Test
    void defaults_to_blue_when_redis_empty() {
        when(valueOps.get(DbVersionService.ACTIVE_KEY)).thenReturn(null);
        assertEquals(DbVersionService.BLUE, service.getActiveSlot());
        assertEquals("blue:", service.activePrefix());
        assertEquals(DbVersionService.GREEN, service.getInactiveSlot());
        assertEquals("green:", service.inactivePrefix());
    }

    @Test
    void uses_green_when_set_in_redis() {
        when(valueOps.get(DbVersionService.ACTIVE_KEY)).thenReturn(DbVersionService.GREEN);
        assertEquals(DbVersionService.GREEN, service.getActiveSlot());
        assertEquals(DbVersionService.BLUE, service.getInactiveSlot());
    }

    @Test
    void invalid_redis_value_falls_back_to_blue() {
        when(valueOps.get(DbVersionService.ACTIVE_KEY)).thenReturn("yellow");
        assertEquals(DbVersionService.BLUE, service.getActiveSlot());
    }

    @Test
    void switchActive_flips_slot() {
        when(valueOps.get(DbVersionService.ACTIVE_KEY)).thenReturn(DbVersionService.BLUE);
        // first call loads cache as blue
        service.getActiveSlot();

        String next = service.switchActive();
        assertEquals(DbVersionService.GREEN, next);
        verify(valueOps).set(DbVersionService.ACTIVE_KEY, DbVersionService.GREEN);
        assertEquals(DbVersionService.GREEN, service.getActiveSlot());
    }

    @Test
    void setActiveSlot_rejects_invalid() {
        assertThrows(IllegalArgumentException.class, () -> service.setActiveSlot("red"));
        verify(valueOps, never()).set(anyString(), anyString());
    }

    @Test
    void setActiveSlot_accepts_blue_green() {
        service.setActiveSlot(DbVersionService.GREEN);
        verify(valueOps).set(DbVersionService.ACTIVE_KEY, DbVersionService.GREEN);
        assertEquals(DbVersionService.GREEN, service.getActiveSlot());
    }

    @Test
    void caches_active_slot_without_extra_redis_reads() {
        when(valueOps.get(DbVersionService.ACTIVE_KEY)).thenReturn(DbVersionService.BLUE);
        service.getActiveSlot();
        service.getActiveSlot();
        service.activePrefix();
        // only first miss should hit Redis
        verify(valueOps, times(1)).get(DbVersionService.ACTIVE_KEY);
    }
}
