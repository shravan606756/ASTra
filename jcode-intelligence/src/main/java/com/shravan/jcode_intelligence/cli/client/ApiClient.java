package com.shravan.jcode_intelligence.cli.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Centralized HTTP client for communicating with the ASTra REST backend.
 * Uses java.net.http.HttpClient and Gson.
 */
public class ApiClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final Gson gson;
    private final Duration requestTimeout;
    private final Duration indexTimeout;

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.gson = new GsonBuilder().create();
        this.requestTimeout = Duration.ofSeconds(30);
        this.indexTimeout = Duration.ofMinutes(25);
    }

    /**
     * Checks if the backend is reachable via the Actuator health endpoint.
     *
     * @return true if the backend responds and its status is UP
     * @throws ApiException if an error occurs while communicating
     */
    public boolean health() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/health"))
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
                HealthResponse health = gson.fromJson(response.body(), HealthResponse.class);
                return "UP".equalsIgnoreCase(health.status);
            }
            return false;
        } catch (ConnectException e) {
            throw new ApiException("Backend is unavailable (Connection refused).", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException("Request timed out.", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException("Failed to communicate with backend: " + e.getMessage(), e);
        } catch (JsonSyntaxException e) {
             throw new ApiException("Failed to parse backend response.", e);
        }
    }

    /**
     * Lists all distinct indexed repository IDs.
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> listRepositories() {
        return get("/api/v1/repositories", java.util.List.class);
    }

    /**
     * Removes all indexed vectors for the given repository ID.
     * @return true if removed, false if not found.
     */
    public boolean removeRepository(String repositoryId) {
        return delete("/api/v1/repositories/" + repositoryId);
    }

    /**
     * Retrieves chunk counts and type breakdown for a repository.
     */
    @SuppressWarnings("unchecked")
    public com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO getRepositoryStats(String repositoryId) {
        return get("/api/v1/repositories/" + repositoryId + "/stats", com.shravan.jcode_intelligence.dto.response.RepositoryStatsDTO.class);
    }

    /**
     * Triggers backend indexing for a repository.
     */
    public com.shravan.jcode_intelligence.dto.response.IndexResponse index(com.shravan.jcode_intelligence.dto.request.IndexRequest request) {
        return post("/api/v1/index", request, com.shravan.jcode_intelligence.dto.response.IndexResponse.class, indexTimeout);
    }

    /**
     * Sends a chat query to the backend.
     */
    public com.shravan.jcode_intelligence.dto.response.ChatResponse ask(com.shravan.jcode_intelligence.dto.request.ChatRequest request) {
        return post("/api/v1/chat", request, com.shravan.jcode_intelligence.dto.response.ChatResponse.class);
    }

    /**
     * Reusable GET helper.
     */
    private <T> T get(String path, Class<T> responseType) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            handleErrorResponse(response);
            return gson.fromJson(response.body(), responseType);
        } catch (ConnectException e) {
            throw new ApiException("Backend is unavailable (Connection refused).", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException("Request timed out.", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException("Failed to communicate with backend: " + e.getMessage(), e);
        } catch (JsonSyntaxException e) {
            throw new ApiException("Failed to parse backend response.", e);
        }
    }

    /**
     * Reusable POST helper with default timeout.
     */
    private <T> T post(String path, Object body, Class<T> responseType) {
        return post(path, body, responseType, requestTimeout);
    }

    /**
     * Reusable POST helper with explicit timeout.
     */
    private <T> T post(String path, Object body, Class<T> responseType, Duration timeout) {
        try {
            String jsonBody = gson.toJson(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            handleErrorResponse(response);
            return gson.fromJson(response.body(), responseType);
        } catch (ConnectException e) {
            throw new ApiException("Backend is unavailable (Connection refused).", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException("Request timed out.", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException("Failed to communicate with backend: " + e.getMessage(), e);
        } catch (JsonSyntaxException e) {
            throw new ApiException("Failed to parse backend response.", e);
        }
    }

    /**
     * Reusable DELETE helper.
     */
    private boolean delete(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(requestTimeout)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return false;
            }
            handleErrorResponse(response);
            return true;
        } catch (ConnectException e) {
            throw new ApiException("Backend is unavailable (Connection refused).", e);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException("Request timed out.", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new ApiException("Failed to communicate with backend: " + e.getMessage(), e);
        }
    }

    private void handleErrorResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            throw new ApiException("Client Error (" + response.statusCode() + "): " + response.body(), response.statusCode());
        } else if (response.statusCode() >= 500) {
            throw new ApiException("Server Error (" + response.statusCode() + "): " + response.body(), response.statusCode());
        }
    }

    // Lightweight internal DTO for parsing Actuator /actuator/health response
    private static class HealthResponse {
        String status;
    }
}
