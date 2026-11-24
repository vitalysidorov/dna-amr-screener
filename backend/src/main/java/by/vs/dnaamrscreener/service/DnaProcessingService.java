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
    private final KmerPrefixService kmerPrefixService;
    
    @Value("${spring.application.session-ttl-seconds:3600}")
    private long sessionTtlSeconds;
    @Value("${screener.lsh.max-reads-per-gene}")
    private int maxReadsPerGene;
    @Value("${screener.number-of-candidates}")
    private long numberOfCandidates;

    @Value("${screener.hit-criteria.strict.primary.min-identity}")
    private double strictPrimaryMinIdentity;
    @Value("${screener.hit-criteria.strict.primary.min-breadth-1x}")
    private double strictPrimaryMinBreadth1x;
    @Value("${screener.hit-criteria.strict.primary.min-breadth-2x}")
    private double strictPrimaryMinBreadth2x;
    @Value("${screener.hit-criteria.strict.primary.min-mean-depth}")
    private double strictPrimaryMinMeanDepth;

    @Value("${screener.hit-criteria.strict.secondary.min-identity}")
    private double strictSecondaryMinIdentity;
    @Value("${screener.hit-criteria.strict.secondary.min-breadth-1x}")
    private double strictSecondaryMinBreadth1x;
    @Value("${screener.hit-criteria.strict.secondary.min-breadth-2x}")
    private double strictSecondaryMinBreadth2x;
    @Value("${screener.hit-criteria.strict.secondary.min-mean-depth}")
    private double strictSecondaryMinMeanDepth;

    @Value("${screener.hit-criteria.relaxed.primary.min-identity}")
    private double relaxedPrimaryMinIdentity;
    @Value("${screener.hit-criteria.relaxed.primary.min-breadth-1x}")
    private double relaxedPrimaryMinBreadth1x;
    @Value("${screener.hit-criteria.relaxed.primary.min-breadth-2x}")
    private double relaxedPrimaryMinBreadth2x;
    @Value("${screener.hit-criteria.relaxed.primary.min-mean-depth}")
    private double relaxedPrimaryMinMeanDepth;

    @Value("${screener.hit-criteria.relaxed.secondary.min-identity}")
    private double relaxedSecondaryMinIdentity;
    @Value("${screener.hit-criteria.relaxed.secondary.min-breadth-1x}")
    private double relaxedSecondaryMinBreadth1x;
    @Value("${screener.hit-criteria.relaxed.secondary.min-breadth-2x}")
    private double relaxedSecondaryMinBreadth2x;
    @Value("${screener.hit-criteria.relaxed.secondary.min-mean-depth}")
    private double relaxedSecondaryMinMeanDepth;

    public DnaProcessingService(
            @Qualifier("geneRedisTemplate") StringRedisTemplate geneRedis,
            @Qualifier("sessionRedisTemplate") StringRedisTemplate sessionRedis,
            DbVersionService versionService, KmerPrefixService kmerPrefixService) {
        
        this.geneRedis = geneRedis;
        this.sessionRedis = sessionRedis;
        this.versionService = versionService;
        this.kmerPrefixService = kmerPrefixService;
    }

    public enum DetectionProfile {
        STRICT,
        RELAXED
    }

    public void queryLshAndIncrementDynamic(String readSequence, String sessionId, String kmerPrefix, int kmerSize,
            int numHashes, int bands, int rowsPerBand) {

        long[] signature = new long[numHashes];
        DNAHasherLib.INSTANCE.compute_minhash_jna(readSequence, kmerSize, numHashes, signature);

        final String versionPrefix = versionService.activeDataPrefix(kmerPrefix);
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
            bucketKeys.add(versionPrefix + kmerPrefix + "lsh:b:" + band + ":h:" + bandHash);
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

    public List<ReportResult> verifyCandidatesFromRedis(String sessionId, DetectionProfile profile, String kmerPrefix) {
        List<ReportResult> report = new ArrayList<>();
        Set<String> touchedGenes = new HashSet<>();
        String redisZsetKey = hitsKey(sessionId);
        final String prefix = versionService.activeDataPrefix(kmerPrefix);

        try {
            Set<ZSetOperations.TypedTuple<String>> candidates =
                    sessionRedis.opsForZSet().reverseRangeWithScores(redisZsetKey, 0, numberOfCandidates);

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

    public void seedReferenceGeneWithPrefix(String versionPrefix, String kmerPrefix, String geneId, String sequence, String antibiotic,
                                            Integer kmer, Integer hashes, Integer bands, Integer rows) {

        String metaKey = versionPrefix + "gene:meta:" + geneId;

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

                String bucketKey = versionPrefix + kmerPrefix + "lsh:b:" + band + ":h:" + bandHash;
                connection.setCommands().sAdd(bucketKey.getBytes(), geneId.getBytes());
            }
            return null;
        });
    }

    private boolean isStrictHit(double id, double b1x, double b2x, double meanDepth) {
        if (id >= strictPrimaryMinIdentity && b1x >= strictPrimaryMinBreadth1x &&
                (b2x >= strictPrimaryMinBreadth2x || meanDepth >= strictPrimaryMinMeanDepth)) {
            return true;
        }
        return id >= strictSecondaryMinIdentity && b1x >= strictSecondaryMinBreadth1x
                && (b2x >= strictSecondaryMinBreadth2x || meanDepth >= strictSecondaryMinMeanDepth);
    }

    private boolean isRelaxedHit(double id, double b1x, double b2x, double meanDepth) {
        if (id >= relaxedPrimaryMinIdentity && b1x >= relaxedPrimaryMinBreadth1x &&
                (b2x >= relaxedPrimaryMinBreadth2x || meanDepth >= relaxedPrimaryMinMeanDepth)) {
            return true;
        }
        return id >= relaxedSecondaryMinIdentity && b1x >= relaxedSecondaryMinBreadth1x
                && (b2x >= relaxedSecondaryMinBreadth2x || meanDepth >= relaxedSecondaryMinMeanDepth);
    }

    private static String hitsKey(String sessionId) {
        return "analysis:{" + sessionId + "}:hits";
    }

    private static String readsKey(String sessionId, String geneId) {
        return "analysis:{" + sessionId + "}:reads:" + geneId;
    }
}