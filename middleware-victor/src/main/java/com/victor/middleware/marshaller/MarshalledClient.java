package com.victor.middleware.marshaller;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.ComponentClient;
import com.victor.middleware.spi.Marshaller;

/**
 * Client-side decorator: wraps a raw {@link ComponentClient} and converts
 * outgoing {@link Message}s to JSON envelopes, then converts incoming JSON
 * envelopes back to {@link Message}s on the return path.
 *
 * <p>Error path: a malformed response is converted to
 * {@code Message(UNKNOWN, [<error message>])} so callers see a uniform
 * typed surface — the same forgiveness shape that {@code Dispatcher}
 * already returns for unregistered commands.</p>
 *
 * <p>Business exceptions raised by the inner client (connection refused,
 * timeout, etc.) propagate to the caller unchanged.</p>
 */
public class MarshalledClient implements ComponentClient {

    private final ComponentClient inner;
    private final Marshaller marshaller;

    public MarshalledClient(ComponentClient inner, Marshaller marshaller) {
        this.inner = inner;
        this.marshaller = marshaller;
    }

    /**
     * Typed entry point: marshal the request to a JSON envelope, send it,
     * then unmarshal the JSON response back into a {@link Message}. On a
     * malformed response, returns {@code Message(UNKNOWN, [<error>])} so
     * the caller sees a uniform typed surface.
     */
    public Message send(String host, int port, Message request) throws MiddlewareException {
        String wireRequest = marshaller.marshal(request);
        String wireResponse = inner.send(host, port, wireRequest);
        try {
            return marshaller.unmarshal(wireResponse);
        } catch (MarshalException me) {
            return new Message(Command.UNKNOWN, java.util.List.of(me.getMessage()));
        }
    }

    /**
     * SPI pass-through: forwards the raw wire-form string to the inner
     * client and returns its raw wire-form string response. Marshalling
     * is the caller's responsibility on this path.
     */
    @Override
    public String send(String host, int port, String request) throws MiddlewareException {
        return inner.send(host, port, request);
    }
}
