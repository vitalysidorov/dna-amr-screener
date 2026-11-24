package by.vs.dnaamrscreener.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;


@Service
public class KmerPrefixService {

    public static final String INDEXES_KEY = "meta:kmer:indexes";
    public static final String PARAMS_KEY_PREFIX = "meta:kmer:params:";

    public static final String DEFAULT_K17 = "k17";
    public static final String DEFAULT_K21 = "k21";

    private static final Set<String> BUILTIN = Set.of(DEFAULT_K17, DEFAULT_K21);

    private final StringRedisTemplate geneRedis;

    public KmerPrefixService(@Qualifier("geneRedisTemplate") StringRedisTemplate geneRedis) {
        this.geneRedis = geneRedis;
    }

    @PostConstruct
    void ensureBuiltinIndexes() {
        geneRedis.opsForSet().add(INDEXES_KEY, DEFAULT_K17, DEFAULT_K21);
    }

    public String addNewIndex(String index) {
        requirePrefix(index);
        String indexLower = index.toLowerCase();
        geneRedis.opsForSet().add(INDEXES_KEY, indexLower);
        return indexLower;
    }


    public String addNewIndex(String index, int kmerSize, int numHashes, int bands, int rowsPerBand) {
        String indexLower = addNewIndex(index);
        Map<String, String> params = new HashMap<>(4);
        params.put("kmer-size", String.valueOf(kmerSize));
        params.put("num-hashes", String.valueOf(numHashes));
        params.put("bands", String.valueOf(bands));
        params.put("rows-per-band", String.valueOf(rowsPerBand));
        geneRedis.opsForHash().putAll(paramsKey(indexLower), params);
        return indexLower;
    }

    public boolean deleteIndex(String index) {
        if (index == null || index.isBlank()) {
            return false;
        }
        String indexLower = index.toLowerCase();
        if (BUILTIN.contains(indexLower)) {
            return false;
        }
        Long removed = geneRedis.opsForSet().remove(INDEXES_KEY, indexLower);
        geneRedis.delete(paramsKey(indexLower));
        return removed != null && removed > 0;
    }

    public void requirePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        if (prefix.contains(":") || prefix.contains(" ")) {
            throw new IllegalArgumentException("prefix must not contain ':' or space");
        }
    }

    public boolean isIndexExists(String index) {
        if (index == null || index.isBlank()) {
            return false;
        }
        String indexLower = index.toLowerCase();
        if (BUILTIN.contains(indexLower)) {
            return true;
        }
        Boolean member = geneRedis.opsForSet().isMember(INDEXES_KEY, indexLower);
        return Boolean.TRUE.equals(member);
    }

    public Set<String> listIndexes() {
        Set<String> members = geneRedis.opsForSet().members(INDEXES_KEY);
        if (members == null || members.isEmpty()) {
            return Set.copyOf(BUILTIN);
        }
        Set<String> result = new HashSet<>(members);
        result.addAll(BUILTIN);
        return Collections.unmodifiableSet(result);
    }

    public Map<String, String> getIndexParams(String index) {
        requirePrefix(index);
        Map<Object, Object> raw = geneRedis.opsForHash().entries(paramsKey(index.toLowerCase()));
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>(raw.size());
        raw.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
        return out;
    }

    private static String paramsKey(String indexLower) {
        return PARAMS_KEY_PREFIX + indexLower;
    }
}
