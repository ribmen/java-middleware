package com.victor.middleware.invoker.annotated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.Marshaller;
import com.victor.middleware.exceptions.MarshalException;

/**
 * Behavior spec for {@link AnnotatedDispatcher#register(Object)}: the
 * four registration-time contracts — populate, replace, cross-class
 * conflict, null guard.
 */
class AnnotatedDispatcherRegistrationTest {

    /** {@code register} populates the dispatch table. */
    @Test
    void registerPopulatesTable() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new NoopMarshaller());
        d.register(new EchoHandler());
        assertNotNull(d);
        assertEquals(Command.OK,
                d.dispatchTyped(new Message(Command.WRITE, List.of("k", "v"))).command());
    }

    /** Re-registering the same handler object overwrites the binding. */
    @Test
    void registerReplacingSameHandlerOverwritesSilently() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new NoopMarshaller());
        EchoHandler h = new EchoHandler();
        d.register(h);
        d.register(h);
        assertEquals(Command.OK,
                d.dispatchTyped(new Message(Command.WRITE, List.of("k", "v"))).command());
    }

    /** Two different handler instances bound to the same {@link Command} throws. */
    @Test
    void crossHandlerDuplicateCommandThrows() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new NoopMarshaller());
        d.register(new EchoHandler());
        assertThrows(IllegalStateException.class,
                () -> d.register(new OtherWriteHandler()));
    }

    /** {@code register(null)} throws {@link NullPointerException}. */
    @Test
    void registerNullThrows() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new NoopMarshaller());
        assertThrows(NullPointerException.class, () -> d.register(null));
    }

    // --- Fixtures -----------------------------------------------------------

    static class EchoHandler {
        @MethodMapping(Command.WRITE)
        public String write(@Param("key") String key, @Param("value") String value) {
            return key + "=" + value;
        }
    }

    static class OtherWriteHandler {
        @MethodMapping(Command.WRITE)
        public String write(@Param("k") String k, @Param("v") String v) { return v; }
    }

    static class NoopMarshaller implements Marshaller {
        @Override
        public String marshal(Message message) throws MarshalException { return ""; }
        @Override
        public Message unmarshal(String wire) throws MarshalException {
            return new Message(Command.UNKNOWN, List.of());
        }
    }
}