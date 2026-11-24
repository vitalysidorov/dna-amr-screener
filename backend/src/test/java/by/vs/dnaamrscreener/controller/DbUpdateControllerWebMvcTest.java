package by.vs.dnaamrscreener.controller;

import by.vs.dnaamrscreener.exception.GlobalExceptionHandler;
import by.vs.dnaamrscreener.service.DbVersionService;
import by.vs.dnaamrscreener.service.DnaProcessingService;
import by.vs.dnaamrscreener.service.GeneParserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@TestPropertySource(properties = {
        "spring.application.analyze-parallelism=4",
        "spring.application.batch-size=16",
        "spring.application.seed-parallelism=4",
        "screener.lsh.kmer-size=15",
        "screener.lsh.num-hashes=100",
        "screener.lsh.bands=20",
        "screener.lsh.rows-per-band=5",
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

    @MockBean(name = "sessionRedisTemplate")
    private StringRedisTemplate sessionRedis;

    @Test
    void status_returns_ok() throws Exception {
        when(versionService.status()).thenReturn(
                new DbVersionService.DbStatus("blue", 10L, 0L));

        mockMvc.perform(get("/api/card/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeSlot").value("blue"))
                .andExpect(jsonPath("$.blueKeyCount").value(10));
    }

    @Test
    void switch_endpoint_delegates() throws Exception {
        when(versionService.getActiveSlot()).thenReturn(DbVersionService.BLUE);
        when(versionService.switchActive()).thenReturn(DbVersionService.GREEN);

        mockMvc.perform(post("/api/card/switch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousActive").value("blue"))
                .andExpect(jsonPath("$.currentActive").value("green"));
    }
}
