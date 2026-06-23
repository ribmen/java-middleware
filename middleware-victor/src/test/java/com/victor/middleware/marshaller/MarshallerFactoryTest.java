package com.victor.middleware.marshaller;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.victor.middleware.spi.Marshaller;

class MarshallerFactoryTest {

    @Test
    void jsonReturnsAWorkingMarshaller() {
        Marshaller m = MarshallerFactory.json();
        assertNotNull(m);
        assertTrue(m instanceof JsonMarshaller);
    }

    @Test
    void jsonAlwaysReturnsSameInstance() {
        Marshaller first = MarshallerFactory.json();
        Marshaller second = MarshallerFactory.json();
        assertSame(first, second);
    }
}
