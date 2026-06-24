package com.victor.middleware.invoker.annotated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;

/**
 * Behavior spec for {@link MethodMapping}: the annotation survives
 * {@code RetentionPolicy.RUNTIME} and carries the right {@link Command}.
 */
class MethodMappingTest {

    /** A round-trip reads the annotation and recovers the {@link Command}. */
    @Test
    void annotationCarriesCommandAndIsRuntimeRetained() throws NoSuchMethodException {
        Method m = Fixture.class.getDeclaredMethod("handle");
        MethodMapping mm = m.getAnnotation(MethodMapping.class);
        assertNotNull(mm, "@MethodMapping must be present on Fixture#handle");
        assertEquals(Command.WRITE, mm.value());
    }

    static class Fixture {
        @MethodMapping(Command.WRITE)
        public String handle() { return ""; }
    }
}
