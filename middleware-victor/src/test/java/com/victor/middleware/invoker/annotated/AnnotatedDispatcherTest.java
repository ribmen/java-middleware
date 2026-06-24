package com.victor.middleware.invoker.annotated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.Marshaller;

/**
 * Behavior spec for {@link AnnotatedDispatcher#dispatchTyped(Message)}:
 * pins every dispatch-time error path from spec §5.
 */
class AnnotatedDispatcherTest {

    /** Happy path: bound method is invoked with positional args, returns OK. */
    @Test
    void dispatchInvokesBoundMethodAndReturnsOk() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new StubMarshaller());
        d.register(new EchoHandler());
        Message out = d.dispatchTyped(new Message(Command.WRITE, List.of("k1", "v1")));
        assertEquals(Command.OK, out.command());
        assertEquals(List.of("k1=v1"), out.args());
    }

    /** Unbound command returns an "ERRO: no handler" error. */
    @Test
    void unboundCommandReturnsErrorMessage() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new StubMarshaller());
        d.register(new EchoHandler());
        Message out = d.dispatchTyped(new Message(Command.READ, List.of("k1")));
        assertEquals(Command.UNKNOWN, out.command());
        assertTrue(out.args().get(0).startsWith("ERRO:"));
        assertTrue(out.args().get(0).contains("no handler"));
    }

    /** Extra args in {@code Message.args} beyond the method's arity are ignored. */
    @Test
    void extraArgsAreIgnored() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new StubMarshaller());
        d.register(new EchoHandler());
        Message out = d.dispatchTyped(new Message(Command.WRITE, List.of("k", "v", "extra")));
        assertEquals(Command.OK, out.command());
        assertEquals(List.of("k=v"), out.args());
    }

    /** A {@link MiddlewareException} from the handler is caught and surfaced. */
    @Test
    void middlewareExceptionFromHandlerIsSurfaced() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new StubMarshaller());
        d.register(new ThrowingHandler());
        Message out = d.dispatchTyped(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(Command.UNKNOWN, out.command());
        assertTrue(out.args().get(0).contains("mw-failure"));
    }

    /** A non-{@code MiddlewareException} from the handler is caught and wrapped with class+message. */
    @Test
    void runtimeExceptionFromHandlerIsWrapped() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new StubMarshaller());
        d.register(new NpeHandler());
        Message out = d.dispatchTyped(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(Command.UNKNOWN, out.command());
        assertTrue(out.args().get(0).contains("NullPointerException"));
    }

    /** A {@link MarshalException} from the marshaller is caught and surfaced with MARSHALLER origin. */
    @Test
    void marshallerExceptionIsSurfacedWithMarshallerOrigin() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new ExplodingMarshaller());
        d.register(new EchoHandler());
        Message out = d.dispatchTyped(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals(Command.UNKNOWN, out.command());
        assertTrue(out.args().get(0).contains("marshal boom"));
    }

    /** {@code hasHandler} reflects the current binding table. */
    @Test
    void hasHandlerReflectsBindingTable() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new StubMarshaller());
        assertFalse(d.hasHandler(Command.WRITE));
        d.register(new EchoHandler());
        assertTrue(d.hasHandler(Command.WRITE));
        assertFalse(d.hasHandler(Command.READ));
    }

    /** Missing-arg path: the handler receives {@code null} and surfaces a downstream NPE-wrapped error. */
    @Test
    void missingArgSurfacesHandlerNpe() {
        AnnotatedDispatcher d = new AnnotatedDispatcher(new StubMarshaller());
        d.register(new NpeHandler());
        Message out = d.dispatchTyped(new Message(Command.WRITE, List.of())); // empty args
        assertEquals(Command.UNKNOWN, out.command());
        assertTrue(out.args().get(0).contains("NullPointerException"));
    }

    // --- Fixtures -----------------------------------------------------------

    static class EchoHandler {
        @MethodMapping(Command.WRITE)
        public String write(@Param("key") String key, @Param("value") String value) {
            return key + "=" + value;
        }
    }

    static class ThrowingHandler {
        @MethodMapping(Command.WRITE)
        public String write(@Param("key") String key, @Param("value") String value)
                throws MiddlewareException {
            throw new TestMiddlewareException("mw-failure");
        }
    }

    static class NpeHandler {
        @MethodMapping(Command.WRITE)
        public String write(@Param("key") String key, @Param("value") String value) {
            // Trigger an NPE deterministically.
            String s = null;
            return s.length() + value;
        }
    }

    static class StubMarshaller implements Marshaller {
        @Override
        public String marshal(Message message) throws MarshalException { return ""; }
        @Override
        public Message unmarshal(String wire) throws MarshalException {
            return new Message(Command.UNKNOWN, List.of());
        }
    }

    static class ExplodingMarshaller implements Marshaller {
        @Override
        public String marshal(Message message) throws MarshalException {
            // MarshalException(message, statusCode) — message first, code second.
            throw new MarshalException("marshal boom", 500);
        }
        @Override
        public Message unmarshal(String wire) throws MarshalException {
            return new Message(Command.UNKNOWN, List.of());
        }
    }

    static class TestMiddlewareException extends MiddlewareException {
        private static final long serialVersionUID = 1L;
        TestMiddlewareException(String message) { super("TEST", message); }
    }
}