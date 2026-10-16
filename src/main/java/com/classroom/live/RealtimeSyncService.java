package com.classroom.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class RealtimeSyncService implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SupabaseClient client;
    private final UUID classroomId;
    private final Consumer<JsonNode> changeListener;
    private final Runnable reconnectedListener;
    private final Consumer<String> errorListener;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "supabase-realtime");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong references = new AtomicLong();
    private volatile WebSocket socket;
    private volatile boolean closed;
    private volatile boolean joined;
    private volatile String joinRef;
    private volatile String joinedAccessToken;
    private boolean reconnectScheduled;
    private int reconnectAttempt;

    public RealtimeSyncService(
            SupabaseClient client,
            UUID classroomId,
            Consumer<JsonNode> changeListener,
            Runnable reconnectedListener,
            Consumer<String> errorListener) {
        this.client = client;
        this.classroomId = classroomId;
        this.changeListener = changeListener;
        this.reconnectedListener = reconnectedListener;
        this.errorListener = errorListener;
    }

    public void start() {
        connect();
        scheduler.scheduleAtFixedRate(this::heartbeat, 25, 25, TimeUnit.SECONDS);
    }

    private void connect() {
        if (closed) {
            return;
        }
        try {
            String wsUrl = client.projectUrl().replaceFirst("^https://", "wss://")
                .replaceFirst("^http://", "ws://")
                + "/realtime/v1/websocket?apikey="
                + encode(client.publishableKey()) + "&vsn=1.0.0";
            http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(wsUrl), new Listener())
                .whenComplete((connected, error) -> {
                    if (closed) {
                        if (connected != null) {
                            closeSocket(connected);
                        }
                    } else if (error != null) {
                        scheduleReconnect("Realtime connection failed: " + error.getMessage());
                    } else {
                        socket = connected;
                    }
                });
        } catch (Exception exception) {
            scheduleReconnect("Could not start Realtime connection: " + exception.getMessage());
        }
    }

    private void join(WebSocket webSocket) {
        try {
            ObjectNode config = JSON.createObjectNode();
            config.putObject("broadcast").put("ack", false).put("self", false);
            config.putObject("presence").put("enabled", false).put("key", "");
            config.put("private", false);
            ArrayNode changes = config.putArray("postgres_changes");
            addFilter(changes, "classroom_files");
            addFilter(changes, "classroom_folders");
            ObjectNode payload = JSON.createObjectNode()
                .set("config", config);
            joinedAccessToken = client.accessToken();
            payload.put("access_token", joinedAccessToken);
            ObjectNode message = JSON.createObjectNode()
                .put("topic", topic())
                .put("event", "phx_join")
                .set("payload", payload);
            joinRef = Long.toString(references.incrementAndGet());
            message.put("ref", joinRef);
            message.put("join_ref", joinRef);
            webSocket.sendText(message.toString(), true)
                .exceptionally(error -> {
                    scheduleReconnect("Realtime subscription send failed: "
                        + error.getMessage());
                    return null;
                });
        } catch (Exception exception) {
            closeSocket(webSocket);
            scheduleReconnect("Could not join Realtime subscription: " + exception.getMessage());
        }
    }

    private void addFilter(ArrayNode changes, String table) {
        changes.addObject()
            .put("event", "*")
            .put("schema", "public")
            .put("table", table)
            .put("filter", "classroom_id=eq." + classroomId);
    }

    private void heartbeat() {
        WebSocket current = socket;
        if (closed || current == null || !joined) {
            return;
        }
        try {
            String refreshedToken = client.accessToken();
            if (!refreshedToken.equals(joinedAccessToken)) {
                ObjectNode tokenMessage = accessTokenMessage(
                    topic(),
                    refreshedToken,
                    Long.toString(references.incrementAndGet()),
                    joinRef
                );
                current.sendText(tokenMessage.toString(), true)
                    .exceptionally(error -> {
                        scheduleReconnect("Realtime token update failed: "
                            + error.getMessage());
                        return null;
                    });
                joinedAccessToken = refreshedToken;
            }
        } catch (Exception exception) {
            closeSocket(current);
            scheduleReconnect("Realtime authentication refresh failed: "
                + exception.getMessage());
            return;
        }
        ObjectNode message = heartbeatMessage(
            Long.toString(references.incrementAndGet()));
        current.sendText(message.toString(), true)
            .exceptionally(error -> {
                closeSocket(current);
                scheduleReconnect("Realtime heartbeat failed: " + error.getMessage());
                return null;
            });
    }

    private void receive(JsonNode message) {
        String event = message.path("event").asText();
        if ("phx_reply".equals(event)) {
            String status = message.path("payload").path("status").asText();
            if (joinRef != null && joinRef.equals(message.path("ref").asText())) {
                if ("ok".equals(status)) {
                    joined = true;
                    reconnectAttempt = 0;
                    reconnectedListener.run();
                } else {
                    scheduleReconnect("Realtime subscription was rejected: "
                        + message.path("payload").path("response").toString());
                }
            } else if ("error".equals(status)) {
                scheduleReconnect("Realtime request failed: "
                    + message.path("payload").path("response").toString());
            }
        } else if ("postgres_changes".equals(event)) {
            JsonNode data = message.path("payload").path("data");
            if (!data.isMissingNode()) {
                changeListener.accept(data);
            }
        } else if ("system".equals(event)
                && "error".equals(message.path("payload").path("status").asText())) {
            scheduleReconnect("Realtime server error: "
                + message.path("payload").path("message").asText("unknown error"));
        }
    }

    private synchronized void scheduleReconnect(String reason) {
        if (closed || reconnectScheduled) {
            return;
        }
        joined = false;
        joinRef = null;
        joinedAccessToken = null;
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            closeSocket(current);
        }
        long delay = Math.min(30, 1L << Math.min(reconnectAttempt++, 5));
        reconnectScheduled = true;
        errorListener.accept(reason + " Retrying in " + delay + "s.");
        scheduler.schedule(() -> {
            synchronized (RealtimeSyncService.this) {
                reconnectScheduled = false;
            }
            connect();
        }, delay, TimeUnit.SECONDS);
    }

    private String topic() {
        return "realtime:feric-classroom-" + classroomId;
    }

    static ObjectNode heartbeatMessage(String ref) {
        ObjectNode message = JSON.createObjectNode()
            .put("topic", "phoenix")
            .put("event", "heartbeat")
            .put("ref", ref);
        message.putObject("payload");
        return message;
    }

    static ObjectNode accessTokenMessage(
            String topic, String accessToken, String ref, String joinRef) {
        ObjectNode message = JSON.createObjectNode()
            .put("topic", topic)
            .put("event", "access_token")
            .put("ref", ref)
            .put("join_ref", joinRef);
        message.putObject("payload").put("access_token", accessToken);
        return message;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private void closeSocket(WebSocket webSocket) {
        closeSocket(webSocket, "Reconnecting");
    }

    private void closeSocket(WebSocket webSocket, String reason) {
        try {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, reason)
                .exceptionally(error -> null);
        } catch (IllegalStateException exception) {
            errorListener.accept("Realtime socket close was already in progress: "
                + exception.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        joined = false;
        joinRef = null;
        joinedAccessToken = null;
        scheduler.shutdownNow();
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            closeSocket(current, "Leaving classroom");
        }
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder partialMessage = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            if (closed) {
                closeSocket(webSocket);
                return;
            }
            socket = webSocket;
            webSocket.request(1);
            join(webSocket);
        }

        @Override
        public CompletableFuture<?> onText(
                WebSocket webSocket, CharSequence data, boolean last) {
            if (socket != webSocket) {
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            partialMessage.append(data);
            if (last) {
                String message = partialMessage.toString();
                partialMessage.setLength(0);
                try {
                    receive(JSON.readTree(message));
                } catch (Exception exception) {
                    scheduleReconnect("Could not process Realtime message: "
                        + exception.getMessage());
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<?> onClose(
                WebSocket webSocket, int statusCode, String reason) {
            if (socket == webSocket) {
                joined = false;
                scheduleReconnect(
                    "Realtime connection closed (" + statusCode + "): " + reason);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (socket == webSocket) {
                joined = false;
                scheduleReconnect("Realtime socket error: " + error.getMessage());
            }
        }
    }
}
