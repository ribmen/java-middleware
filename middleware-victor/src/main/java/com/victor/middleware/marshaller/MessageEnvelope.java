package com.victor.middleware.marshaller;

import java.util.List;
import java.util.Map;

/**
 * Jackson DTO matching the wire envelope
 * {@code {"verb":"WRITE","args":["k","v"],"body":{}}}.
 *
 * <p>Package-private on purpose: only {@link JsonMarshaller} reads and
 * writes this shape. The rest of the codebase talks in
 * {@link com.victor.middleware.protocol.Message} values.</p>
 */
class MessageEnvelope {

    public String verb;
    public List<String> args;
    public Map<String, Object> body;

    public MessageEnvelope() {
        // Jackson requires a no-arg constructor.
    }
}
