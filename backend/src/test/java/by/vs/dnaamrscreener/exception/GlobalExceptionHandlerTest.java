package by.vs.dnaamrscreener.exception;

import by.vs.dnaamrscreener.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private HttpServletRequest request;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        when(request.getRequestURI()).thenReturn("/api/v1/analyze");
    }

    @Test
    void illegalArgument_returns_400() {
        ResponseEntity<ApiError> resp = handler.handleBadRequest(
                new IllegalArgumentException("bad slot"), request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals(400, resp.getBody().status());
        assertEquals("bad slot", resp.getBody().message());
        assertEquals("/api/v1/analyze", resp.getBody().path());
    }

    @Test
    void missingParam_returns_400() {
        ResponseEntity<ApiError> resp = handler.handleMissingParam(
                new MissingServletRequestParameterException("sessionId", "String"), request);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(resp.getBody().message().contains("sessionId"));
    }

    @Test
    void fileAlreadyProcessing_returns_409() {
        ResponseEntity<ApiError> resp = handler.handleConflict(
                new FileAlreadyProcessingException("файл уже обрабатывается"), request);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
        assertEquals(409, resp.getBody().status());
    }

    @Test
    void redisDown_returns_503() {
        ResponseEntity<ApiError> resp = handler.handleRedisDown(
                new RedisConnectionFailureException("connection refused"), request);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals(503, resp.getBody().status());
    }

    @Test
    void generic_returns_500() {
        ResponseEntity<ApiError> resp = handler.handleGeneral(
                new RuntimeException("boom"), request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, resp.getStatusCode());
        assertEquals("internal server error", resp.getBody().message());
    }
}
