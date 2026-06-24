package com.victor.middleware.invoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

/**
 * Behavior spec for {@link Dispatcher}.
 *
 * <p>The dispatcher is the smallest seam between a typed {@link Message}
 * and the handler that knows how to answer it. Handlers are typed end
 * to end: {@code Function<Message, Message>}. There is no string
 * round-trip inside the dispatcher any more — the marshaller is the
 * only layer that touches wire form.</p>
 */
class DispatcherTest {

    /** Registers a handler and dispatches a matching message — most basic path. */
    @Test
    void dispatchesMessageToHandlerForItsCommand() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> new Message(Command.OK,
                List.of("wrote:" + String.join("|", msg.args()))));

        Message msg = new Message(Command.WRITE, List.of("key", "value"));
        Message response = d.dispatch(msg);

        assertEquals(Command.OK, response.command());
        assertEquals(List.of("wrote:key|value"), response.args());
    }

    /** Unregistered command → typed UNKNOWN with "ERRO:" prefix, never a silent null or NPE. */
    @Test
    void returnsErrorForUnregisteredCommand() {
        Dispatcher d = new Dispatcher();
        Message msg = new Message(Command.READ, List.of("key"));
        Message response = d.dispatch(msg);

        assertSame(Command.UNKNOWN, response.command());
        assertTrue(response.args().get(0).startsWith("ERRO:"),
                "expected typed error, got: " + response);
    }

    /** UNKNOWN should never silently dispatch to a real handler. */
    @Test
    void unknownCommandAlwaysReturnsError() {
        Dispatcher d = new Dispatcher();
        d.register(Command.READ, msg -> new Message(Command.OK, List.of("should-not-run")));
        Message response = d.dispatch(new Message(Command.UNKNOWN, List.of()));

        assertSame(Command.UNKNOWN, response.command());
        assertTrue(response.args().get(0).startsWith("ERRO:"));
    }

    /** Handler throws → dispatcher surfaces the message, doesn't lose it. */
    @Test
    void handlerExceptionsAreSurfacedAsErrors() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> { throw new RuntimeException("boom"); });
        Message response = d.dispatch(new Message(Command.WRITE, List.of("k", "v")));

        assertSame(Command.UNKNOWN, response.command());
        String arg = response.args().get(0);
        assertTrue(arg.startsWith("ERRO:"),
                "expected error wrapping the cause, got: " + arg);
        assertTrue(arg.contains("boom"),
                "expected the cause message to be preserved, got: " + arg);
    }

    /** Multiple commands registered independently — round-trip per command. */
    @Test
    void multipleCommandsAreDispatchedIndependently() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> new Message(Command.OK, List.of("W:" + msg.firstArg())));
        d.register(Command.READ,  msg -> new Message(Command.OK, List.of("R:" + msg.firstArg())));

        assertEquals(new Message(Command.OK, List.of("W:k")), d.dispatch(new Message(Command.WRITE, List.of("k"))));
        assertEquals(new Message(Command.OK, List.of("R:k")), d.dispatch(new Message(Command.READ,  List.of("k"))));
    }

    /** Re-registering overwrites, never duplicates or throws. */
    @Test
    void registerOverwritesExistingHandler() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> new Message(Command.OK, List.of("first")));
        d.register(Command.WRITE, msg -> new Message(Command.OK, List.of("second")));

        assertEquals(new Message(Command.OK, List.of("second")),
                d.dispatch(new Message(Command.WRITE, List.of())));
    }

    /**
     * dispatchTyped is now an alias for dispatch — the handler returns
     * a {@link Message} directly, so there is nothing to round-trip.
     * The result must preserve the verb and args exactly as the
     * handler produced them.
     */
    @Test
    void dispatchTypedReturnsHandlerMessageUnchanged() {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> new Message(Command.OK, List.of(msg.firstArg())));

        Message msg = new Message(Command.WRITE, List.of("key", "value"));
        Message typed = d.dispatchTyped(msg);

        assertEquals(Command.OK, typed.command());
        assertEquals(List.of("key"), typed.args());
    }
}