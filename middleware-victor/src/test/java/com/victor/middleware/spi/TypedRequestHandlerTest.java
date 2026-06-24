package com.victor.middleware.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.marshaller.JsonMarshaller;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.Marshaller;

/**
 * Spec for the typed {@link TypedRequestHandler} SPI. After the pipe
 * codec removal there is no string-in/string-out bridge in production
 * — these tests document the bridges that used to live between
 * {@code TypedRequestHandler} and {@code RequestHandler} via the pipe
 * codec, and verify that the typed SPI is self-sufficient.
 */
class TypedRequestHandlerTest {

    @Test
    void typedHandlerCanBeImplementedAsLambda() throws Exception {
        TypedRequestHandler h = req ->
                new Message(Command.OK, List.of(req.firstArg(), "written"));
        Message out = h.handle(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(new Message(Command.OK, List.of("k", "written")), out);
    }

    /**
     * Typed handler can be adapted to the legacy string SPI by running
     * it through the JSON marshaller. The bridge is test-only — in
     * production we never marshal and immediately unmarshal.
     */
    @Test
    void typedHandlerCanBeAdaptedToLegacyStringHandler() throws Exception {
        TypedRequestHandler typed = req ->
                new Message(Command.OK, List.of(req.firstArg()));
        Marshaller marshaller = new JsonMarshaller();
        RequestHandler legacy = wire -> {
            Message req = marshaller.unmarshal(wire);
            Message resp = typed.handle(req);
            return marshaller.marshal(resp);
        };

        String wireRequest = marshaller.marshal(
                new Message(Command.WRITE, List.of("k", "v")));
        String wire = legacy.handle(wireRequest);

        // Parse the JSON back to verify the envelope shape.
        Message parsed = marshaller.unmarshal(wire);
        assertEquals(Command.OK, parsed.command());
        assertEquals(List.of("k"), parsed.args());
    }

    /**
     * Legacy string handler can be adapted to the typed SPI via the
     * marshaller. The bridge lets callers that still own a
     * {@link RequestHandler} integrate with the typed dispatcher chain.
     */
    @Test
    void legacyStringHandlerCanBeAdaptedToTypedHandler() throws Exception {
        Marshaller marshaller = new JsonMarshaller();
        RequestHandler legacy = wire -> {
            // Echo-style: prefix with OK envelope.
            Message req = marshaller.unmarshal(wire);
            return marshaller.marshal(new Message(Command.OK, List.of(req.command().wireForm())));
        };
        TypedRequestHandler typed = req -> marshaller.unmarshal(
                legacy.handle(marshaller.marshal(req)));

        Message out = typed.handle(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(Command.OK, out.command());
        assertEquals(List.of("WRITE"), out.args());
    }

    @Test
    void typedHandlerMayThrowMiddlewareException() {
        TypedRequestHandler h = req -> { throw new MiddlewareException("nope"); };
        MiddlewareException ex = assertThrows(MiddlewareException.class,
                () -> h.handle(new Message(Command.WRITE, List.of("k"))));
        assertEquals("nope", ex.getMessage());
    }
}