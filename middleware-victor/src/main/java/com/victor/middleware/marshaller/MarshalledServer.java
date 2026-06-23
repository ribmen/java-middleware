package com.victor.middleware.marshaller;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.protocol.MessageParser;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.Marshaller;
import com.victor.middleware.spi.RequestHandler;

/**
 * Server-side decorator: wraps a raw {@link ComponentServer} (HTTP/TCP/UDP)
 * and translates each request from JSON envelope to {@link Message}, then
 * translates the handler's wire-form response back to JSON envelope.
 *
 * <p>Composition with Phase 5A:</p>
 * <pre>
 *   MarshalledServer → raw HttpServer → InterceptingDispatcher → handler
 * </pre>
 * <p>The dispatcher and the interceptor chain are the responsibility of the
 * caller — this decorator only knows about JSON ↔ wire-form translation.</p>
 *
 * <p>Error path: a malformed JSON request throws {@link MarshalException}
 * (status 400). This decorator catches it and writes an error envelope
 * {@code {"error":"…","code":400}} instead of letting the raw server
 * surface the stack trace.</p>
 */
public class MarshalledServer implements ComponentServer {

    private final ComponentServer inner;
    private final Marshaller marshaller;

    public MarshalledServer(ComponentServer inner, Marshaller marshaller) {
        this.inner = inner;
        this.marshaller = marshaller;
    }

    @Override
    public void start(int port, RequestHandler handler) throws MiddlewareException {
        RequestHandler adapted = (rawWire) -> {
            Message request;
            try {
                request = marshaller.unmarshal(rawWire);
            } catch (MarshalException me) {
                return errorEnvelope(me.getMessage(), me.statusCode());
            }
            String wireResponse = handler.handle(request.toWireForm());
            try {
                Message response = MessageParser.parse(wireResponse);
                return marshaller.marshal(response);
            } catch (MarshalException me) {
                return errorEnvelope(me.getMessage(), me.statusCode());
            }
        };
        inner.start(port, adapted);
    }

    @Override
    public void stop() {
        inner.stop();
    }

    private static String errorEnvelope(String message, int code) {
        // Lightweight, no Jackson dependency for the error path so the
        // server always recovers gracefully even if the marshaller
        // itself is broken.
        String escaped = message.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"error\":\"" + escaped + "\",\"code\":" + code + "}";
    }
}
