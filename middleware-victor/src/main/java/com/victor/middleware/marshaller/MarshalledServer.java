package com.victor.middleware.marshaller;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.ComponentServer;
import com.victor.middleware.spi.Marshaller;
import com.victor.middleware.spi.RequestHandler;
import com.victor.middleware.spi.TypedRequestHandler;

/**
 * Server-side decorator: wraps a raw {@link ComponentServer} (HTTP/TCP/UDP)
 * and translates each request from JSON envelope to a typed {@link Message},
 * then translates the typed response back to a JSON envelope on the wire.
 *
 * <p>The only public entry point is {@link #startTyped(int, TypedRequestHandler)}.
 * Callers hand a {@code Message → Message} handler to the decorator and
 * the decorator handles JSON encoding at both ends. There is no longer
 * a string-in/string-out overload: the production API is typed end to
 * end, and the wire form is exclusively the JSON envelope.</p>
 *
 * <p>Composition with Phase 5A:</p>
 * <pre>
 *   MarshalledServer → raw HttpServer → InterceptingDispatcher → handler
 * </pre>
 * <p>The dispatcher and the interceptor chain are the responsibility of the
 * caller — this decorator only knows about JSON ↔ typed translation.</p>
 *
 * <p>Error path: a malformed JSON request throws {@link MarshalException}
 * (status 400). This decorator catches it and writes an error envelope
 * {@code {"error":"…","code":400}} instead of letting the raw server
 * surface the stack trace.</p>
 *
 * <p>Implementation note: the raw {@link ComponentServer} SPI still speaks
 * {@link RequestHandler} (string-in/string-out) for backward compatibility
 * with the three protocol servers. This decorator bridges from the typed
 * handler to that string SPI by passing the JSON envelope as the wire
 * string — the raw handler's only job is to forward it back unchanged
 * (see the adapter below).</p>
 */
public class MarshalledServer implements ComponentServer {

    private final ComponentServer inner;
    private final Marshaller marshaller;

    public MarshalledServer(ComponentServer inner, Marshaller marshaller) {
        this.inner = inner;
        this.marshaller = marshaller;
    }

    /**
     * Bind on {@code port} and route each incoming JSON envelope to the
     * {@link TypedRequestHandler}, marshalling the typed response back to
     * JSON.
     *
     * <p>The handler's return value is encoded via the supplied
     * {@link Marshaller}; a {@link MarshalException} on the response side
     * becomes a wire envelope of the form {@code {"error":"…","code":…}}
     * so the client always receives well-formed JSON.</p>
     */
    public void startTyped(int port, TypedRequestHandler handler) throws MiddlewareException {
        // Bridge: the raw server SPI still wants a RequestHandler (string → string).
        // We hand it a JSON envelope string and expect it to forward that string
        // back unchanged — the typed handler runs entirely on the decorator side.
        RequestHandler adapter = (rawWire) -> {
            Message request;
            try {
                request = marshaller.unmarshal(rawWire);
            } catch (MarshalException me) {
                return errorEnvelope(me.getMessage(), me.statusCode());
            }
            Message response;
            try {
                response = handler.handle(request);
            } catch (MiddlewareException me) {
                return errorEnvelope(me.getMessage(), 500);
            } catch (RuntimeException re) {
                return errorEnvelope(re.getClass().getSimpleName() + ": "
                        + re.getMessage(), 500);
            }
            try {
                return marshaller.marshal(response);
            } catch (MarshalException me) {
                return errorEnvelope(me.getMessage(), me.statusCode());
            }
        };
        inner.start(port, adapter);
    }

    /**
     * Legacy {@link ComponentServer} SPI kept only so the decorator can
     * still be assigned to a {@code ComponentServer} variable. Production
     * callers must use {@link #startTyped(int, TypedRequestHandler)}; this
     * method throws on call because there is no meaningful way to start
     * the decorator without a typed handler.
     */
    @Override
    @Deprecated
    public void start(int port, RequestHandler handler) {
        throw new UnsupportedOperationException(
                "MarshalledServer.start(int, RequestHandler) is removed. "
                        + "Use startTyped(int, TypedRequestHandler) — the JSON "
                        + "envelope is the only wire shape the decorator speaks.");
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
