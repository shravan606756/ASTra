package com.shravan.jcode_intelligence.controller;

import com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO;
import com.shravan.jcode_intelligence.service.RepositoryManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RepositoryControllerTest {

    private RepositoryManagementService service;
    private RepositoryController controller;

    @BeforeEach
    void setUp() {
        service = mock(RepositoryManagementService.class);
        controller = new RepositoryController(service);
    }

    @Test
    @DisplayName("removeRepository rejects blank or null repository ID with IllegalArgumentException")
    void testRemoveRepository_rejectsBlankOrNullId() {
        assertThrows(IllegalArgumentException.class, () -> controller.removeRepository(null));
        assertThrows(IllegalArgumentException.class, () -> controller.removeRepository(""));
        assertThrows(IllegalArgumentException.class, () -> controller.removeRepository("   "));

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("removeRepository returns 204 when service succeeds")
    void testRemoveRepository_successReturns204() {
        when(service.removeRepository("test-repo")).thenReturn(true);

        ResponseEntity<Void> response = controller.removeRepository("test-repo");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(service).removeRepository("test-repo");
    }

    @Test
    @DisplayName("removeRepository returns 404 when service returns false")
    void testRemoveRepository_notFoundReturns404() {
        when(service.removeRepository("missing-repo")).thenReturn(false);

        ResponseEntity<Void> response = controller.removeRepository("missing-repo");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(service).removeRepository("missing-repo");
    }

    @Test
    @DisplayName("getRepositoryStats rejects blank or null repository ID")
    void testGetRepositoryStats_rejectsBlankOrNullId() {
        assertThrows(IllegalArgumentException.class, () -> controller.getRepositoryStats(null));
        assertThrows(IllegalArgumentException.class, () -> controller.getRepositoryStats(""));
        assertThrows(IllegalArgumentException.class, () -> controller.getRepositoryStats("   "));

        verifyNoInteractions(service);
    }
}
