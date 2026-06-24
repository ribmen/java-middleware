package com.victor.middleware.invoker.annotated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;

/** Behavior spec for {@link BoundMethod}: record accessors and value equality. */
class BoundMethodTest {

    /** Accessors return what was passed in. */
    @Test
    void accessorsReturnConstructorArgs() throws NoSuchMethodException {
        Method m = Fixture.class.getDeclaredMethod("write", String.class, String.class);
        List<ParamBinding> bindings = List.of(
                new ParamBinding("key", 0),
                new ParamBinding("value", 1));
        Fixture target = new Fixture();
        BoundMethod bound = new BoundMethod(m, bindings, target);

        assertSame(m, bound.method());
        assertSame(bindings, bound.paramBindings());
        assertSame(target, bound.target());
    }

    /** {@code paramBindings()} preserves declaration order. */
    @Test
    void paramBindingsPreserveDeclarationOrder() throws NoSuchMethodException {
        Method m = Fixture.class.getDeclaredMethod("write", String.class, String.class);
        BoundMethod bound = new BoundMethod(m,
                List.of(new ParamBinding("key", 0), new ParamBinding("value", 1)),
                new Fixture());
        assertEquals(0, bound.paramBindings().get(0).parameterIndex());
        assertEquals(1, bound.paramBindings().get(1).parameterIndex());
        assertEquals("key", bound.paramBindings().get(0).name());
        assertEquals("value", bound.paramBindings().get(1).name());
    }

    /**
     * Record {@code equals} is field-based. Two {@code BoundMethod} values
     * with the same {@link Method} reference, the same {@code paramBindings}
     * list, and the same {@code target} reference compare equal. Different
     * {@code target} references (since {@code Fixture} doesn't override
     * {@code equals}) make the records unequal — pinning the field-by-field
     * contract rather than a notion of "same handler shape".
     */
    @Test
    void equalsIsFieldBased() throws NoSuchMethodException {
        Method m = Fixture.class.getDeclaredMethod("write", String.class, String.class);
        List<ParamBinding> bindings = List.of(new ParamBinding("k", 0));
        Fixture target = new Fixture();
        BoundMethod a = new BoundMethod(m, bindings, target);
        BoundMethod b = new BoundMethod(m, bindings, target);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        // Different paramBindings list → unequal.
        BoundMethod c = new BoundMethod(m,
                List.of(new ParamBinding("other", 0)),
                target);
        assertNotEquals(a, c);
    }

    static class Fixture {
        @MethodMapping(Command.WRITE)
        public String write(@Param("key") String key, @Param("value") String value) {
            return value;
        }
    }
}
