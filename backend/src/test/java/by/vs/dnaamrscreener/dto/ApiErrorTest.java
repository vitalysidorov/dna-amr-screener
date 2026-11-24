package by.vs.dnaamrscreener.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiErrorTest {

    @Test
    void of_sets_fields_and_timestamp() {
        ApiError err = ApiError.of(409, "Conflict", "busy", "/api/v1/analyze");

        assertEquals(409, err.status());
        assertEquals("Conflict", err.error());
        assertEquals("busy", err.message());
        assertEquals("/api/v1/analyze", err.path());
        assertNotNull(err.timestamp());
    }
}
