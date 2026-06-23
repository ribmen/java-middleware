package com.victor.middleware.marshaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.ComponentClient;
import com.victor.middleware.spi.Marshaller;

class MarshalledClientTest {

    @Test
    void sendMarshalsRequestAndUnmarshalsResponse() throws Exception {
        Marshaller marshaller = new JsonMarshaller();
        StubRawClient raw = new StubRawClient(req -> {
            try {
                return marshaller.marshal(new Message(Command.OK, List.of("k", "v")));
            } catch (MarshalException e) {
                throw new RuntimeException(e);
            }
        });

        MarshalledClient client = new MarshalledClient(raw, marshaller);
        Message resp = client.send("127.0.0.1", 9000,
                new Message(Command.WRITE, List.of("k", "v")));

        // outgoing wire was the JSON envelope
        assertEquals(
                "{\"verb\":\"WRITE\",\"args\":[\"k\",\"v\"],\"body\":{}}",
                raw.lastRequest);
        // response is parsed back into a Message
        assertEquals(new Message(Command.OK, List.of("k", "v")), resp);
    }

    @Test
    void malformedResponseReturnsUnknownWithErrorMessage() throws Exception {
        Marshaller marshaller = new JsonMarshaller();
        StubRawClient raw = new StubRawClient(req -> "{not valid json");

        MarshalledClient client = new MarshalledClient(raw, marshaller);
        Message resp = client.send("127.0.0.1", 9000,
                new Message(Command.WRITE, List.of("k", "v")));

        assertSame(Command.UNKNOWN, resp.command());
        assertTrue(resp.args().get(0).startsWith("malformed JSON"),
                "expected malformed-JSON prefix in error arg, got: " + resp.args());
    }

    @Test
    void businessExceptionPropagatesFromInnerClient() throws Exception {
        Marshaller marshaller = new JsonMarshaller();
        // ConnectionException is checked; wrap construction in a sneaky-throw
        // so the Function<String,String> lambda signature accepts it.
        com.victor.middleware.exceptions.ConnectionException toThrow =
                new com.victor.middleware.exceptions.ConnectionException("HTTP", "down");
        StubRawClient raw = new StubRawClient(req -> {
            throw sneakyThrow(toThrow);
        });

        MarshalledClient client = new MarshalledClient(raw, marshaller);
        try {
            client.send("127.0.0.1", 9000, new Message(Command.WRITE, List.of("k", "v")));
            org.junit.jupiter.api.Assertions.fail("expected ConnectionException");
        } catch (com.victor.middleware.exceptions.ConnectionException expected) {
            assertEquals("down", expected.getMessage());
        }
    }

    @Test
    void implementsComponentClientContract() throws Exception {
        Marshaller marshaller = new JsonMarshaller();
        StubRawClient raw = new StubRawClient(req -> "OK|");
        MarshalledClient client = new MarshalledClient(raw, marshaller);
        assertTrue(client instanceof ComponentClient);
    }

    // -- Helpers ---------------------------------------------------------------

    private static final class StubRawClient implements ComponentClient {
        final java.util.function.Function<String, String> behavior;
        String lastRequest;

        StubRawClient(java.util.function.Function<String, String> behavior) {
            this.behavior = behavior;
        }

        @Override
        public String send(String host, int port, String request) throws MiddlewareException {
            this.lastRequest = request;
            return behavior.apply(request);
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
