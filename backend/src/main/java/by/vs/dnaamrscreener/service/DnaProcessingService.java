package by.vs.dnaamrscreener.service;

import by.vs.dnaamrscreener.dto.ReportResult;
import by.vs.dnaamrscreener.jna.DNAHasherLib;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class DnaProcessingService {

    private final StringRedisTemplate geneRedis;
    private final StringRedisTemplate sessionRedis;
    private final DbVersionService versionService;
    
    @Value("${spring.application.session-ttl-seconds:3600}")
    private long sessionTtlSeconds;
    @Value("${screener.lsh.max-reads-per-gene}")
    private int maxReadsPerGene;

    public DnaProcessingService(
            @Qualifier("geneRedisTemplate") StringRedisTemplate geneRedis,
            @Qualifier("sessionRedisTemplate") StringRedisTemplate sessionRedis,
            DbVersionService versionService) {
        
        this.geneRedis = geneRedis;
        this.sessionRedis = sessionRedis;
        this.versionService = versionService;
    }

    public enum DetectionProfile {
        STRICT,
        RELAXED
    }

    public void queryLshAndIncrementDynamic(String readSequence, String sessionId, int kmerSize,
            int numHashes, int bands, int rowsPerBand) {

        long[] signature = new long[numHashes];
        DNAHasherLib.INSTANCE.compute_minhash_jna(readSequence, kmerSize, numHashes, signature);

        final String prefix = versionService.activePrefix();
        final String zsetKey = hitsKey(sessionId);

        List<String> bucketKeys = new ArrayList<>(bands);
        for (int band = 0; band < bands; band++) {
            int startIdx = band * rowsPerBand;
            if (startIdx + rowsPerBand > signature.length) {
                break;
            }
            long[] subArray = Arrays.copyOfRange(signature, startIdx, startIdx + rowsPerBand);
            long bandHash = DNAHasherLib.INSTANCE.XXH3_64bits_withSeed(
                    subArray, subArray.length, band);
            bucketKeys.add(prefix + "lsh:b:" + band + ":h:" + bandHash);
        }

        if (bucketKeys.isEmpty()) {
            return;
        }

        List<Object> membersList = geneRedis.executePipelined((RedisCallback<Object>) connection -> {
            for (String key : bucketKeys) {
                connection.setCommands().sMembers(key.getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });

        Map<String, Long> geneIncrements = new HashMap<>();
        for (Object raw : membersList) {
            if (!(raw instanceof Set<?> set) || set.isEmpty()) {
                continue;
            }
            for (Object geneObj : set) {
                geneIncrements.merge(String.valueOf(geneObj), 1L, Long::sum);
            }
        }

        if (geneIncrements.isEmpty()) {
            return;
        }

        final byte[] seqBytes = readSequence.getBytes(StandardCharsets.UTF_8);
        final byte[] zsetKeyBytes = zsetKey.getBytes(StandardCharsets.UTF_8);
        final int maxReads = maxReadsPerGene;

        sessionRedis.executePipelined((RedisCallback<Object>) connection -> {
            for (Map.Entry<String, Long> e : geneIncrements.entrySet()) {
                String geneId = e.getKey();
                byte[] member = geneId.getBytes(StandardCharsets.UTF_8);

                connection.zSetCommands().zIncrBy(
                        zsetKeyBytes,
                        e.getValue().doubleValue(),
                        member);

                byte[] listKey = readsKey(sessionId, geneId)
                        .getBytes(StandardCharsets.UTF_8);

                connection.listCommands().rPush(listKey, seqBytes);
                connection.listCommands().lTrim(listKey, -maxReads, -1);
                connection.keyCommands().expire(listKey, sessionTtlSeconds);
            }

            connection.keyCommands().expire(zsetKeyBytes, sessionTtlSeconds);
            return null;
        });
    }

    public List<ReportResult> verifyCandidatesFromRedis(String sessionId, DetectionProfile profile) {
        List<ReportResult> report = new ArrayList<>();
        Set<String> touchedGenes = new HashSet<>();
        String redisZsetKey = hitsKey(sessionId);
        final String prefix = versionService.activePrefix();

        try {
            Set<ZSetOperations.TypedTuple<String>> candidates =
                    sessionRedis.opsForZSet().reverseRangeWithScores(redisZsetKey, 0, 149);

            if (candidates == null || candidates.isEmpty()) {
                return report;
            }

            for (ZSetOperations.TypedTuple<String> candidate : candidates) {
                String geneId = candidate.getValue();
                if (geneId == null) {
                    continue;
                }
                touchedGenes.add(geneId);

                String refKey = prefix + "gene:meta:" + geneId;
                List<Object> meta = geneRedis.opsForHash()
                        .multiGet(refKey, List.of("sequence", "antibiotic", "length"));

                if (meta == null || meta.size() < 3
                        || meta.get(0) == null || meta.get(2) == null) {
                    continue;
                }

                String refSequence = (String) meta.get(0);
                String antibiotic = meta.get(1) != null
                        ? (String) meta.get(1)
                        : "unknown antibiotics";
                int geneLength = Integer.parseInt((String) meta.get(2));

                String listKey = readsKey(sessionId, geneId);
                List<String> reads = sessionRedis.opsForList().range(listKey, 0, -1);
                if (reads == null || reads.isEmpty()) {
                    continue;
                }

                String patientData = String.join("\n", reads);
                int[] depthArray = new int[geneLength];

                double avgIdentity = DNAHasherLib.INSTANCE.verify_exact_alignment_with_depth(
                        refSequence, patientData, depthArray, geneLength);

                if (avgIdentity <= 0.0) {
                    continue;
                }

                int covered1 = 0;
                int covered2 = 0;
                long totalDepth = 0;
                for (int d : depthArray) {
                    totalDepth += d;
                    if (d >= 1) covered1++;
                    if (d >= 2) covered2++;
                }

                double breadth1x = (double) covered1 / geneLength;
                double breadth2x = (double) covered2 / geneLength;
                double meanDepth = (double) totalDepth / geneLength;

                boolean accepted = switch (profile) {
                    case STRICT -> isStrictHit(avgIdentity, breadth1x, breadth2x, meanDepth);
                    case RELAXED -> isRelaxedHit(avgIdentity, breadth1x, breadth2x, meanDepth);
                };

                if (!accepted) {
                    continue;
                }

                String tier = profile == DetectionProfile.STRICT ? "strict" : "relaxed";
                String status = String.format(
                        "tier=%s, id=%.1f%%, b1x=%.1f%%, b2x=%.1f%%, depth=%.1f",
                        tier,
                        avgIdentity * 100,
                        breadth1x * 100,
                        breadth2x * 100,
                        meanDepth);

                report.add(new ReportResult(geneId, antibiotic, avgIdentity * 100, status));
            }
        } finally {
            cleanupSession(sessionId, touchedGenes);
        }

        return report;
    }

    private void cleanupSession(String sessionId, Set<String> geneIds) {
        String zsetKey = hitsKey(sessionId);

        if (geneIds == null || geneIds.isEmpty()) {
            Set<String> all = sessionRedis.opsForZSet().range(zsetKey, 0, -1);
            if (all != null) {
                geneIds = all;
            }
        }

        sessionRedis.delete(zsetKey);

        if (geneIds != null) {
            for (String geneId : geneIds) {
                sessionRedis.delete(readsKey(sessionId, geneId));
            }
        }
    }

    public void seedReferenceGeneWithPrefix(String prefix, String geneId, String sequence, String antibiotic,
                                            Integer kmer, Integer hashes, Integer bands, Integer rows) {

        String metaKey = prefix + "gene:meta:" + geneId;

        long[] signature = new long[hashes];
        DNAHasherLib.INSTANCE.compute_minhash_jna(sequence, kmer, hashes, signature);

        geneRedis.executePipelined((RedisCallback<Object>) connection -> {
            Map<byte[], byte[]> meta = new HashMap<>(4);
            meta.put("sequence".getBytes(), sequence.getBytes());
            meta.put("antibiotic".getBytes(), antibiotic.getBytes());
            meta.put("length".getBytes(), String.valueOf(sequence.length()).getBytes());
            connection.hashCommands().hMSet(metaKey.getBytes(), meta);

            for (int band = 0; band < bands; band++) {
                int startIdx = band * rows;
                int endIdx = startIdx + rows;
                if (endIdx > signature.length) break;

                long[] subArray = Arrays.copyOfRange(signature, startIdx, endIdx);
                long bandHash = DNAHasherLib.INSTANCE.XXH3_64bits_withSeed(
                        subArray, subArray.length, band);

                String bucketKey = prefix + "lsh:b:" + band + ":h:" + bandHash;
                connection.setCommands().sAdd(bucketKey.getBytes(), geneId.getBytes());
            }
            return null;
        });
    }

    private static boolean isStrictHit(double id, double b1x, double b2x, double meanDepth) {
        if (id >= 0.95 && b1x >= 0.80 && (b2x >= 0.40 || meanDepth >= 5.0)) {
            return true;
        }
        return id >= 0.90 && b1x >= 0.80 && (b2x >= 0.50 || meanDepth >= 8.0);
    }

    private static boolean isRelaxedHit(double id, double b1x, double b2x, double meanDepth) {
        if (id >= 0.90 && b1x >= 0.70 && (b2x >= 0.30 || meanDepth >= 3.0)) {
            return true;
        }
        return id >= 0.85 && b1x >= 0.75 && (b2x >= 0.40 || meanDepth >= 5.0);
    }

    private static String hitsKey(String sessionId) {
        return "analysis:{" + sessionId + "}:hits";
    }

    private static String readsKey(String sessionId, String geneId) {
        return "analysis:{" + sessionId + "}:reads:" + geneId;
    }
}