package com.victor.middleware.marshaller;

import com.victor.middleware.spi.Marshaller;

/**
 * Single switchboard for {@link Marshaller} instances. Mirrors the shape
 * of {@link com.victor.middleware.factory.CommunicationFactory}: a
 * utility class with static accessors, no instances.
 */
public final class MarshallerFactory {

    private static final Marshaller JSON = new JsonMarshaller();

    private MarshallerFactory() {
        // utility class — not meant to be instantiated
    }

    /** @return the singleton JSON {@link Marshaller}. */
    public static Marshaller json() {
        return JSON;
    }
}
