package by.vs.dnaamrscreener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KmerPrefixServiceTest {

    @Mock
    private StringRedisTemplate geneRedis;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private KmerPrefixService service;

    @BeforeEach
    void setUp() {
        lenient().when(geneRedis.opsForSet()).thenReturn(setOps);
        lenient().when(geneRedis.opsForHash()).thenReturn(hashOps);
        service = new KmerPrefixService(geneRedis);
    }

    @Test
    void builtin_indexes_always_exist() {
        assertTrue(service.isIndexExists("k21"));
        assertTrue(service.isIndexExists("K17"));
        verify(setOps, never()).isMember(eq(KmerPrefixService.INDEXES_KEY), eq("k21"));
    }

    @Test
    void custom_index_checked_in_redis() {
        when(setOps.isMember(KmerPrefixService.INDEXES_KEY, "k31")).thenReturn(true);
        assertTrue(service.isIndexExists("k31"));
        when(setOps.isMember(KmerPrefixService.INDEXES_KEY, "k99")).thenReturn(false);
        assertFalse(service.isIndexExists("k99"));
    }

    @Test
    void addNewIndex_writes_to_redis_set() {
        assertEquals("k31", service.addNewIndex("K31"));
        verify(setOps).add(KmerPrefixService.INDEXES_KEY, "k31");
    }

    @Test
    void addNewIndex_with_params_writes_hash() {
        assertEquals("k31", service.addNewIndex("k31", 31, 100, 20, 5));
        verify(setOps).add(KmerPrefixService.INDEXES_KEY, "k31");
        verify(hashOps).putAll(eq(KmerPrefixService.PARAMS_KEY_PREFIX + "k31"), argThat(m ->
                "31".equals(m.get("kmer-size"))
                        && "100".equals(m.get("num-hashes"))
                        && "20".equals(m.get("bands"))
                        && "5".equals(m.get("rows-per-band"))
        ));
    }

    @Test
    void deleteIndex_protects_builtins() {
        assertFalse(service.deleteIndex("k21"));
        assertFalse(service.deleteIndex("k17"));
        verify(setOps, never()).remove(anyString(), any());
    }

    @Test
    void deleteIndex_removes_custom_from_redis() {
        when(setOps.remove(KmerPrefixService.INDEXES_KEY, "k31")).thenReturn(1L);
        assertTrue(service.deleteIndex("k31"));
        verify(geneRedis).delete(KmerPrefixService.PARAMS_KEY_PREFIX + "k31");
    }

    @Test
    void requirePrefix_rejects_blank_and_separators() {
        assertThrows(IllegalArgumentException.class, () -> service.requirePrefix(null));
        assertThrows(IllegalArgumentException.class, () -> service.requirePrefix(" "));
        assertThrows(IllegalArgumentException.class, () -> service.requirePrefix("k21:extra"));
        assertThrows(IllegalArgumentException.class, () -> service.requirePrefix("k 21"));
        service.requirePrefix("k21");
    }

    @Test
    void listIndexes_merges_redis_with_builtins() {
        when(setOps.members(KmerPrefixService.INDEXES_KEY)).thenReturn(Set.of("k31", "k21"));
        Set<String> indexes = service.listIndexes();
        assertTrue(indexes.contains("k21"));
        assertTrue(indexes.contains("k17"));
        assertTrue(indexes.contains("k31"));
    }

    @Test
    void getIndexParams_reads_hash() {
        when(hashOps.entries(KmerPrefixService.PARAMS_KEY_PREFIX + "k31"))
                .thenReturn(Map.of("kmer-size", "31", "num-hashes", "100"));
        Map<String, String> params = service.getIndexParams("k31");
        assertEquals("31", params.get("kmer-size"));
        assertEquals("100", params.get("num-hashes"));
    }
}
