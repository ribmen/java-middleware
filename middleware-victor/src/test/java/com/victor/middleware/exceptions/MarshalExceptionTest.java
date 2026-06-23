package com.victor.middleware.exceptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarshalExceptionTest {

    @Test
    void carries400StatusCodeForMalformedInput() {
        MarshalException ex = new MarshalException("malformed JSON: bad token", 400);
        assertEquals("malformed JSON: bad token", ex.getMessage());
        assertEquals(400, ex.statusCode());
        assertEquals("MARSHALLER", ex.getOrigin());
        assertTrue(ex instanceof MiddlewareException);
    }

    @Test
    void carries500StatusCodeForEncoderFailure() {
        IllegalStateException cause = new IllegalStateException("boom");
        MarshalException ex = new MarshalException("failed to serialize", 500, cause);
        assertEquals(500, ex.statusCode());
        assertSame(cause, ex.getCause());
    }

    @Test
    void rejectsInvalidStatusCodesAtConstruction() {
        try {
            new MarshalException("bad code", 418);
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
