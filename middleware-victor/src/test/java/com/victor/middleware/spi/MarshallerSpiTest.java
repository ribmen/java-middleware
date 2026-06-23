package com.victor.middleware.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

class MarshallerSpiTest {

    @Test
    void marshallerInterfaceExposesMarshalAndUnmarshal() throws Exception {
        Class<?> iface = Class.forName("com.victor.middleware.spi.Marshaller");
        assertNotNull(iface);
        Method marshal = iface.getMethod("marshal",
                Class.forName("com.victor.middleware.protocol.Message"));
        assertEquals(String.class, marshal.getReturnType());
        Method unmarshal = iface.getMethod("unmarshal", String.class);
        assertEquals(Class.forName("com.victor.middleware.protocol.Message"),
                unmarshal.getReturnType());
    }
}
