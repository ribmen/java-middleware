package com.victor.middleware.marshaller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.Marshaller;
import com.victor.middleware.spi.RequestHandler;
import com.victor.middleware.spi.TypedRequestHandler;

class MarshalledServerTest {

    @Test
    void marshalledRequestReachesHandlerAsMessageAndResponseGoesBackAsJson() throws Exception {
        AtomicReference<Message> captured = new AtomicReference<>();
        TypedRequestHandler typed = req -> {
            captured.set(req);
            return new Message(Command.OK, List.of(req.firstArg(), "written"));
        };
        Marshaller marshaller = new JsonMarshaller();

        // A tiny in-process raw server that records calls and lets the
        // test synchronously feed a wire-form request through the
        // marshaller path without binding a socket.
        StubRawServer raw = new StubRawServer();

        MarshalledServer server = new MarshalledServer(raw, marshaller);
        server.startTyped(0, typed);

        String wireRequest = marshaller.marshal(
                new Message(Command.WRITE, List.of("smoke-key", "smoke-value")));
        String wireResponse = raw.lastHandled(wireRequest);

        assertEquals(Command.WRITE, captured.get().command());
        assertEquals(List.of("smoke-key", "smoke-value"), captured.get().args());
        assertEquals(
                "{\"verb\":\"OK\",\"args\":[\"smoke-key\",\"written\"],\"body\":{}}",
                wireResponse);
        server.stop();
    }

    @Test
    void malformedRequestReturns400ErrorEnvelope() throws Exception {
        Marshaller marshaller = new JsonMarshaller();
        StubRawServer raw = new StubRawServer();
        MarshalledServer server = new MarshalledServer(raw, marshaller);
        server.startTyped(0, req -> new Message(Command.OK, List.of("never-called")));

        String response = raw.lastHandled("{not valid json");
        assertTrue(response.contains("\"error\""),
                "expected error envelope, got: " + response);
        assertTrue(response.contains("\"code\":400"),
                "expected code 400 in envelope, got: " + response);
        server.stop();
    }

    /**
     * A handler exception is caught by the decorator and converted into a
     * typed-error JSON envelope. The client therefore always sees
     * well-formed JSON, never a half-written response or a thrown
     * {@link MiddlewareException} propagating out of the adapter.
     */
    @Test
    void handlerMiddlewareExceptionEnvelopedAsError() throws Exception {
        Marshaller marshaller = new JsonMarshaller();
        StubRawServer raw = new StubRawServer();
        MarshalledServer server = new MarshalledServer(raw, marshaller);
        server.startTyped(0, req -> {
            throw new com.victor.middleware.exceptions.ConnectionException("HTTP", "down");
        });

        String response = raw.lastHandled(
                marshaller.marshal(new Message(Command.WRITE, List.of("k", "v"))));
        assertTrue(response.contains("\"error\""),
                "expected error envelope from caught exception, got: " + response);
        assertTrue(response.contains("\"code\":500"),
                "expected code 500 in envelope, got: " + response);
        server.stop();
    }

    @Test
    void implementsComponentServerContract() {
        Marshaller marshaller = new JsonMarshaller();
        StubRawServer raw = new StubRawServer();
        MarshalledServer server = new MarshalledServer(raw, marshaller);
        assertTrue(server instanceof ComponentServer);
    }

    @Test
    void stopDelegatesToInnerServer() {
        Marshaller marshaller = new JsonMarshaller();
        StubRawServer raw = new StubRawServer();
        MarshalledServer server = new MarshalledServer(raw, marshaller);
        server.stop();
        assertEquals(1, raw.stopCalls);
    }

    // -- Helpers ---------------------------------------------------------------

    /**
     * Minimal stand-in for a real ComponentServer: records calls and
     * lets tests synchronously feed a wire-form request through the
     * marshaller path without binding a socket. The "handler" given to
     * {@link #start} is invoked on each call to {@link #lastHandled}.
     */
    private static final class StubRawServer implements ComponentServer {
        RequestHandler handler;
        int stopCalls = 0;

        @Override
        public void start(int port, RequestHandler handler) {
            this.handler = handler;
        }

        @Override
        public void stop() {
            stopCalls++;
        }

        String lastHandled(String wireRequest) throws MiddlewareException {
            return handler.handle(wireRequest);
        }
    }
}