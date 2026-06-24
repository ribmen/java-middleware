package com.victor.middleware.invoker.annotated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;

/** Behavior spec for {@link Param}: annotation is runtime-retained and reads its name. */
class ParamTest {

    @Test
    void annotationCarriesNameAndIsRuntimeRetained() throws NoSuchMethodException {
        Method m = Fixture.class.getDeclaredMethod("write", String.class, String.class);
        Parameter[] params = m.getParameters();
        assertEquals("key", params[0].getAnnotation(Param.class).value());
        assertEquals("value", params[1].getAnnotation(Param.class).value());
        assertNotNull(m.getAnnotation(MethodMapping.class));
    }

    static class Fixture {
        @MethodMapping(Command.WRITE)
        public String write(@Param("key") String key, @Param("value") String value) {
            return value;
        }
    }
}
