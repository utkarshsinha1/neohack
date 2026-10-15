package com.classroom.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class SupabaseClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SupabaseConfig config;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();
    private volatile Session session;

    public SupabaseClient(SupabaseConfig config) {
        this.config = Objects.requireNonNull(config);
    }

    public String projectUrl() {
        return config.projectUrl();
    }

    public String publishableKey() {
        return config.publishableKey();
    }

    public synchronized Session signIn(String email, char[] password) throws IOException {
        ObjectNode body = JSON.createObjectNode()
            .put("email", email)
            .put("password", new String(password));
        JsonNode response = sendAuth("/auth/v1/token?grant_type=password", body);
        session = sessionFrom(response);
        return session;
    }

    public synchronized JsonNode signUp(String email, char[] password) throws IOException {
        ObjectNode body = JSON.createObjectNode()
            .put("email", email)
            .put("password", new String(password));
        JsonNode response = sendAuth("/auth/v1/signup", body);
        if (response.hasNonNull("access_token")) {
            session = sessionFrom(response);
        }
        return response;
    }

    public synchronized void signOut() {
        session = null;
    }

    public UUID userId() throws IOException {
        return requireSession().userId();
    }

    public String userEmail() throws IOException {
        return requireSession().email();
    }

    public String accessToken() throws IOException {
        return requireSession().accessToken();
    }

    public JsonNode get(String restPath) throws IOException {
        return sendJson("GET", "/rest/v1/" + restPath, null, null);
    }

    public JsonNode post(String restPath, JsonNode body, String prefer) throws IOException {
        return sendJson("POST", "/rest/v1/" + restPath, body, prefer);
    }

    public JsonNode patch(String restPath, JsonNode body, String prefer) throws IOException {
        return sendJson("PATCH", "/rest/v1/" + restPath, body, prefer);
    }

    public void delete(String restPath) throws IOException {
        sendJson("DELETE", "/rest/v1/" + restPath, null, null);
    }

    public JsonNode rpc(String function, JsonNode body) throws IOException {
        return sendJson("POST", "/rest/v1/rpc/" + function, body, null);
    }

    public String encodeQuery(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private JsonNode sendAuth(String path, JsonNode body) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.projectUrl() + path))
            .timeout(Duration.ofSeconds(30))
            .header("apikey", config.publishableKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        return send(request);
    }

    private JsonNode sendJson(
            String method, String path, JsonNode body, String prefer) throws IOException {
        String token = requireSession().accessToken();
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                URI.create(config.projectUrl() + path))
            .timeout(Duration.ofSeconds(30))
            .header("apikey", config.publishableKey())
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json");
        if (prefer != null && !prefer.isBlank()) {
            builder.header("Prefer", prefer);
        }
        HttpRequest.BodyPublisher publisher = body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body.toString());
        builder.method(method, publisher);
        return send(builder.build());
    }

    private JsonNode send(HttpRequest request) throws IOException {
        try {
            HttpResponse<String> response = http.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String responseBody = response.body();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = responseBody;
                try {
                    JsonNode error = JSON.readTree(responseBody);
                    message = firstText(error, "msg", "message", "error_description",
                        "error", "details", "hint");
                } catch (Exception ignored) {
                    // Keep the response body when the server does not return JSON.
                }
                throw new RequestException(response.statusCode(), message);
            }
            if (responseBody == null || responseBody.isBlank()) {
                return JSON.nullNode();
            }
            return JSON.readTree(responseBody);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Supabase request was interrupted", exception);
        }
    }

    private synchronized Session requireSession() throws IOException {
        Session current = session;
        if (current == null) {
            throw new IOException("Sign in to Supabase before using classrooms");
        }
        if (current.expiresAt().isBefore(Instant.now().plusSeconds(30))) {
            if (current.refreshToken() == null || current.refreshToken().isBlank()) {
                session = null;
                throw new IOException("Supabase session expired; sign in again");
            }
            ObjectNode body = JSON.createObjectNode().put("refresh_token", current.refreshToken());
            JsonNode response = sendAuth("/auth/v1/token?grant_type=refresh_token", body);
            session = sessionFrom(response);
        }
        return session;
    }

    private static Session sessionFrom(JsonNode response) throws IOException {
        JsonNode user = response.path("user");
        if (!response.hasNonNull("access_token") || !user.hasNonNull("id")) {
            throw new IOException(
                "Authentication did not return a session. Confirm the account's email, "
                    + "then sign in."
            );
        }
        long seconds = response.path("expires_in").asLong(3600);
        return new Session(
            response.path("access_token").asText(),
            response.path("refresh_token").asText(null),
            UUID.fromString(user.path("id").asText()),
            user.path("email").asText(),
            Instant.now().plusSeconds(Math.max(seconds, 60))
        );
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return node.toString();
    }

    public static boolean isTransient(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof RequestException request) {
                return request.statusCode() >= 500;
            }
            cause = cause.getCause();
        }
        return failure instanceof IOException;
    }

    public static final class RequestException extends IOException {
        private final int statusCode;

        private RequestException(int statusCode, String message) {
            super("Supabase request failed (HTTP " + statusCode + "): " + message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public record Session(
            String accessToken, String refreshToken, UUID userId, String email,
            Instant expiresAt) {}
}
