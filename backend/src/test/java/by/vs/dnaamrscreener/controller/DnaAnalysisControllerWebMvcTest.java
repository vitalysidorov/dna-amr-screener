package by.vs.dnaamrscreener.controller;

import by.vs.dnaamrscreener.dto.ReportResult;
import by.vs.dnaamrscreener.exception.GlobalExceptionHandler;
import by.vs.dnaamrscreener.service.DnaProcessingService;
import by.vs.dnaamrscreener.service.KmerPrefixService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@TestPropertySource(properties = {
        "spring.application.analyze-parallelism=4",
        "spring.application.batch-size=16",
        "spring.application.seed-parallelism=4",
        "screener.lsh.k21.kmer-size=21",
        "screener.lsh.k21.num-hashes=100",
        "screener.lsh.k21.bands=25",
        "screener.lsh.k21.rows-per-band=4",
        "screener.lsh.k17.kmer-size=17",
        "screener.lsh.k17.num-hashes=100",
        "screener.lsh.k17.bands=20",
        "screener.lsh.k17.rows-per-band=5",
        "screener.lsh.min-avg-phred=10.0"
})
@WebMvcTest(controllers = DnaAnalysisController.class)
@Import(GlobalExceptionHandler.class)
class DnaAnalysisControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DnaProcessingService processingService;

    @MockBean
    private KmerPrefixService kmerPrefixService;

    @MockBean(name = "sessionRedisTemplate")
    private StringRedisTemplate sessionRedis;

    @Test
    void analyze_finalize_returns_report_json() throws Exception {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(sessionRedis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        when(processingService.verifyCandidatesFromRedis(
                eq("sess-1"),
                eq(DnaProcessingService.DetectionProfile.STRICT),
                eq("k21")))
                .thenReturn(List.of(new ReportResult("blaNDM-1", "carbapenems", 98.5, "tier=strict")));

        String seq = "ACGTACGTACGTACGTACGTACGTACGTACGT";
        String qual = "I".repeat(seq.length());
        String fastq = "@read1\n" + seq + "\n+\n" + qual + "\n";

        mockMvc.perform(post("/api/v1/analyze")
                        .param("sessionId", "sess-1")
                        .param("kmerPrefix", "k21")
                        .param("profile", "strict")
                        .param("finalize", "true")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(fastq.getBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].geneId").value("blaNDM-1"))
                .andExpect(jsonPath("$[0].matchPercentage").value(98.5));
    }

    @Test
    void analyze_conflict_when_file_lock_held() throws Exception {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(sessionRedis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        String seq = "ACGTACGTACGTACGTACGTACGTACGTACGT";
        String qual = "I".repeat(seq.length());
        String fastq = "@r\n" + seq + "\n+\n" + qual + "\n";

        mockMvc.perform(post("/api/v1/analyze")
                        .param("sessionId", "sess-2")
                        .param("finalize", "true")
                        .header("X-File-Hash", "abc123")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(fastq.getBytes()))
                .andExpect(status().isConflict());
    }

    @Test
    void analyze_accumulate_without_finalize() throws Exception {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(sessionRedis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        String seq = "ACGTACGTACGTACGTACGTACGTACGTACGT";
        String qual = "I".repeat(seq.length());
        String fastq = "@read1\n" + seq + "\n+\n" + qual + "\n";

        mockMvc.perform(post("/api/v1/analyze")
                        .param("sessionId", "sess-3")
                        .param("kmerPrefix", "k21")
                        .param("finalize", "false")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(fastq.getBytes()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("accumulated"))
                .andExpect(jsonPath("$.sessionId").value("sess-3"));

        verify(processingService, never()).verifyCandidatesFromRedis(anyString(), any(), anyString());
    }
}
