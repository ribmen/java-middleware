package com.victor.middleware.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

class TypedRequestHandlerTest {

    @Test
    void typedHandlerCanBeImplementedAsLambda() throws Exception {
        TypedRequestHandler h = req ->
                new Message(Command.OK, List.of(req.args().get(0), "written"));
        Message out = h.handle(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(new Message(Command.OK, List.of("k", "written")), out);
    }

    @Test
    void typedHandlerCanBeAdaptedToLegacyStringHandler() throws Exception {
        TypedRequestHandler typed = req ->
                new Message(Command.OK, List.of(req.args().get(0)));
        RequestHandler legacy = adaptToString(typed);

        String wire = legacy.handle("WRITE|k|extra");
        assertEquals("OK|k", wire);
    }

    @Test
    void legacyStringHandlerCanBeAdaptedToTypedHandler() throws Exception {
        RequestHandler legacy = req -> "OK|" + req;
        TypedRequestHandler typed = adaptToTyped(legacy);

        Message out = typed.handle(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(Command.OK, out.command());
        assertEquals(List.of("WRITE", "k", "v"), out.args());
    }

    @Test
    void typedHandlerMayThrowMiddlewareException() {
        TypedRequestHandler h = req -> { throw new MiddlewareException("nope"); };
        try {
            h.handle(new Message(Command.WRITE, List.of("k")));
            org.junit.jupiter.api.Assertions.fail("expected MiddlewareException");
        } catch (MiddlewareException expected) {
            assertEquals("nope", expected.getMessage());
        }
    }

    /** Helpers — the bridges live here as test-only utilities. */

    private static RequestHandler adaptToString(TypedRequestHandler typed) {
        return wire -> {
            Message req = com.victor.middleware.protocol.MessageParser.parse(wire);
            Message resp = typed.handle(req);
            return resp.toWireForm();
        };
    }

    private static TypedRequestHandler adaptToTyped(RequestHandler legacy) {
        return req -> {
            String resp = legacy.handle(req.toWireForm());
            return com.victor.middleware.protocol.MessageParser.parse(resp);
        };
    }
}
