package com.victor.middleware.marshaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class MessageEnvelopeTest {

    @Test
    void jacksonCanRoundTripAnEnvelope() throws Exception {
        MessageEnvelope original = new MessageEnvelope();
        original.verb = "WRITE";
        original.args = List.of("k", "v");
        original.body = java.util.Map.of();

        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(original);
        assertNotNull(json);
        assertTrue(json.contains("\"verb\":\"WRITE\""), "json: " + json);
        assertTrue(json.contains("\"args\":[\"k\",\"v\"]"), "json: " + json);

        MessageEnvelope roundTripped = mapper.readValue(json, MessageEnvelope.class);
        assertEquals("WRITE", roundTripped.verb);
        assertEquals(List.of("k", "v"), roundTripped.args);
    }
}
