package com.victor.middleware.invoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

/**
 * Behavior spec for {@link Dispatcher}.
 *
 * <p>The dispatcher is the smallest seam between a parsed {@link Message}
 * and the handler that knows how to answer it. Today the handler map is
 * passed in directly; tomorrow the same class can back an annotation-driven
 * invoker (handlers declared via {@code @Handler(WRITE)}).</p>
 */
class DispatcherTest {

    /** Registers a handler and dispatches a matching message — most basic path. */
    @Test
    void dispatchesMessageToHandlerForItsCommand() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> "wrote:" + String.join("|", msg.args()));

        Message msg = new Message(Command.WRITE, List.of("key", "value"));
        assertEquals("wrote:key|value", d.dispatch(msg));
    }

    /** Unregistered command → wire-form error, never a silent null or NPE. */
    @Test
    void returnsErrorForUnregisteredCommand() {
        Dispatcher d = new Dispatcher();
        Message msg = new Message(Command.READ, List.of("key"));
        String result = d.dispatch(msg);
        assertTrue(result.startsWith("ERRO:"), "expected wire-form error, got: " + result);
    }

    /** UNKNOWN should never silently dispatch to a real handler. */
    @Test
    void unknownCommandAlwaysReturnsError() {
        Dispatcher d = new Dispatcher();
        d.register(Command.READ, msg -> "should-not-run");
        String result = d.dispatch(new Message(Command.UNKNOWN, List.of()));
        assertTrue(result.startsWith("ERRO:"));
    }

    /** Handler throws → dispatcher surfaces the message, doesn't lose it. */
    @Test
    void handlerExceptionsAreSurfacedAsErrors() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> { throw new RuntimeException("boom"); });
        String result = d.dispatch(new Message(Command.WRITE, List.of("k", "v")));
        assertTrue(result.startsWith("ERRO:") && result.contains("boom"),
                "expected error wrapping the cause, got: " + result);
    }

    /** Multiple commands registered independently — round-trip per command. */
    @Test
    void multipleCommandsAreDispatchedIndependently() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> "W:" + msg.args().get(0));
        d.register(Command.READ,  msg -> "R:" + msg.args().get(0));

        assertEquals("W:k", d.dispatch(new Message(Command.WRITE, List.of("k"))));
        assertEquals("R:k", d.dispatch(new Message(Command.READ,  List.of("k"))));
    }

    /** Re-registering overwrites, never duplicates or throws. */
    @Test
    void registerOverwritesExistingHandler() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> "first");
        d.register(Command.WRITE, msg -> "second");
        assertEquals("second", d.dispatch(new Message(Command.WRITE, List.of())));
    }

    /**
     * dispatchTyped(Message) routes through the same handler map and
     * re-parses the wire-form output back into a {@link Message}. For
     * well-formed wire-form responses (verb-prefixed), the round-trip
     * preserves the verb and args.
     */
    @Test
    void dispatchTypedRoundTripsWellFormedWireResponse() {
        Dispatcher d = new Dispatcher();
        // Handler returns a well-formed pipe-delimited wire-form response.
        d.register(Command.WRITE, msg -> "OK|" + msg.args().get(0));

        Message msg = new Message(Command.WRITE, List.of("key", "value"));
        Message typed = d.dispatchTyped(msg);
        assertEquals(Command.OK, typed.command());
        assertEquals(List.of("key"), typed.args());
    }
}
