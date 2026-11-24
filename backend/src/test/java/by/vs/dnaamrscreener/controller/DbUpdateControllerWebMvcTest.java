package by.vs.dnaamrscreener.controller;

import by.vs.dnaamrscreener.exception.GlobalExceptionHandler;
import by.vs.dnaamrscreener.service.DbVersionService;
import by.vs.dnaamrscreener.service.DnaProcessingService;
import by.vs.dnaamrscreener.service.GeneParserService;
import by.vs.dnaamrscreener.service.KmerPrefixService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@WebMvcTest(controllers = DbUpdateController.class)
@Import(GlobalExceptionHandler.class)
class DbUpdateControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DbVersionService versionService;

    @MockBean
    private DnaProcessingService processingService;

    @MockBean
    private GeneParserService geneParserService;

    @MockBean
    private KmerPrefixService kmerPrefixService;

    @MockBean(name = "sessionRedisTemplate")
    private StringRedisTemplate sessionRedis;

    @Test
    void status_returns_ok() throws Exception {
        when(kmerPrefixService.isIndexExists("k21")).thenReturn(true);
        when(versionService.status("k21")).thenReturn(
                new DbVersionService.DbStatus("k21", "blue", 10L, 0L));

        mockMvc.perform(get("/api/card/status").param("kmerPrefix", "k21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefix").value("k21"))
                .andExpect(jsonPath("$.activeSlot").value("blue"))
                .andExpect(jsonPath("$.blueKeyCount").value(10));
    }

    @Test
    void status_rejects_unknown_prefix() throws Exception {
        when(kmerPrefixService.isIndexExists("k99")).thenReturn(false);

        mockMvc.perform(get("/api/card/status").param("kmerPrefix", "k99"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void switch_endpoint_delegates() throws Exception {
        when(kmerPrefixService.isIndexExists("k21")).thenReturn(true);
        when(versionService.getActiveSlot("k21")).thenReturn(DbVersionService.BLUE);
        when(versionService.switchActive("k21")).thenReturn(DbVersionService.GREEN);

        mockMvc.perform(post("/api/card/switch").param("kmerPrefix", "k21"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousActive").value("blue"))
                .andExpect(jsonPath("$.currentActive").value("green"));
    }

    @Test
    void switch_rejects_unknown_prefix() throws Exception {
        when(kmerPrefixService.isIndexExists(anyString())).thenReturn(false);

        mockMvc.perform(post("/api/card/switch").param("kmerPrefix", "unknown"))
                .andExpect(status().isBadRequest());
    }
}
