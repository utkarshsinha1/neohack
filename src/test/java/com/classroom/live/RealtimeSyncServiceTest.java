package com.classroom.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RealtimeSyncServiceTest {
    @Test
    void heartbeatUsesPhoenixTopicAndAnEmptyPayload() {
        ObjectNode message = RealtimeSyncService.heartbeatMessage("12");

        assertEquals("phoenix", message.path("topic").asText());
        assertEquals("heartbeat", message.path("event").asText());
        assertEquals("12", message.path("ref").asText());
        assertTrue(message.path("payload").isObject());
        assertTrue(message.path("payload").isEmpty());
    }

    @Test
    void accessTokenUpdateUsesTheDocumentedPayloadShape() {
        ObjectNode message = RealtimeSyncService.accessTokenMessage(
            "realtime:classroom", "new-token", "13", "9");

        assertEquals("access_token", message.path("event").asText());
        assertEquals("realtime:classroom", message.path("topic").asText());
        assertEquals("new-token", message.path("payload").path("access_token").asText());
        assertEquals("13", message.path("ref").asText());
        assertEquals("9", message.path("join_ref").asText());
        assertEquals(1, message.path("payload").size());
    }
}
