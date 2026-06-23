package com.victor.middleware.spi;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.protocol.Message;

/**
 * Strategy for converting between in-memory {@link Message} values and
 * a wire-format string. Implementations decide the on-the-wire bytes;
 * the {@link MarshalledServer} and {@link MarshalledClient} decorators
 * sit between a raw transport and a typed application handler.
 *
 * <p>Marshalling failures are reported as {@link MarshalException}
 * carrying a {@code statusCode} (400 for malformed input, 500 for
 * encoder failures) so the HTTP transport can translate them
 * directly.</p>
 */
public interface Marshaller {

    /** Convert a {@link Message} to its wire-format string. */
    String marshal(Message message) throws MarshalException;

    /**
     * Convert a wire-format string to a {@link Message}.
     * Implementations are forgiving on empty/null input and return
     * {@code Message(UNKNOWN, [])}; malformed structured input throws
     * {@link MarshalException} with status 400.
     */
    Message unmarshal(String wire) throws MarshalException;
}
