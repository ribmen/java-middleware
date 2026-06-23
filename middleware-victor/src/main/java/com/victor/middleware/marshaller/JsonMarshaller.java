package com.victor.middleware.marshaller;

import java.util.List;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.Marshaller;

/**
 * Jackson-backed {@link Marshaller}. Round-trips {@link Message} values
 * through the envelope shape {@code {"verb":"…","args":[…],"body":{}}}.
 *
 * <p>Defensive invariants:</p>
 * <ul>
 *     <li>{@code marshal(null)} throws {@link IllegalArgumentException}
 *         (programmer error, not a wire failure).</li>
 *     <li>{@code unmarshal(null)} and {@code unmarshal("")} return
 *         {@code Message(UNKNOWN, [])} — same forgiveness as the pipe codec.</li>
 *     <li>Malformed JSON, missing {@code verb}, or non-string {@code verb}
 *         throw {@link MarshalException} with status 400.</li>
 *     <li>Encoder failures throw {@link MarshalException} with status 500.</li>
 * </ul>
 */
public class JsonMarshaller implements Marshaller {

    private static final int STATUS_BAD_REQUEST = 400;
    private static final int STATUS_INTERNAL_ERROR = 500;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonFactory factory = mapper.getFactory();

    @Override
    public String marshal(Message message) throws MarshalException {
        if (message == null) {
            throw new IllegalArgumentException("message must not be null");
        }
        MessageEnvelope env = new MessageEnvelope();
        env.verb = message.command().wireForm();
        env.args = message.args();
        env.body = java.util.Map.of();
        try {
            return mapper.writeValueAsString(env);
        } catch (JsonProcessingException e) {
            throw new MarshalException(
                    "failed to serialize response: " + e.getOriginalMessage(),
                    STATUS_INTERNAL_ERROR, e);
        }
    }

    @Override
    public Message unmarshal(String wire) throws MarshalException {
        if (wire == null || wire.isEmpty()) {
            return new Message(Command.UNKNOWN, List.of());
        }
        try (JsonParser parser = factory.createParser(wire)) {
            // Pre-validate: locate "verb" field token and ensure it's a string.
            // Jackson's default ObjectMapper would otherwise coerce 42 -> "42"
            // when binding into a String field, hiding this protocol violation.
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw new MarshalException("malformed JSON: not an object",
                        STATUS_BAD_REQUEST);
            }
            boolean verbSeen = false;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String fieldName = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("verb".equals(fieldName)) {
                    verbSeen = true;
                    if (value != JsonToken.VALUE_STRING) {
                        throw new MarshalException("verb must be a string",
                                STATUS_BAD_REQUEST);
                    }
                }
                // Skip value: scalars are consumed by nextToken(); objects/arrays
                // would need skipChildren(), but our envelope only contains
                // scalar values plus the args array which ObjectMapper handles.
            }
            if (!verbSeen) {
                throw new MarshalException("missing verb", STATUS_BAD_REQUEST);
            }
        } catch (IOException e) {
            throw new MarshalException(
                    "malformed JSON: " + e.getMessage(),
                    STATUS_BAD_REQUEST, e);
        }
        MessageEnvelope env;
        try {
            env = mapper.readValue(wire, MessageEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new MarshalException(
                    "malformed JSON: " + e.getOriginalMessage(),
                    STATUS_BAD_REQUEST, e);
        }
        if (env == null) {
            return new Message(Command.UNKNOWN, List.of());
        }
        Command cmd = Command.fromString(env.verb);
        List<String> args = env.args == null ? List.of() : env.args;
        return new Message(cmd, args);
    }
}
