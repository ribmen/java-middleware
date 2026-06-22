package com.victor.middleware.invoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.InvocationAbortedException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

class InterceptingDispatcherTest {

    /** Build a Dispatcher pre-bound to a single WRITE handler that returns a fixed value. */
    private static Dispatcher dispatcherStoringLastMessage(String returnValue, AtomicReference<Message> seen) {
        Dispatcher d = new Dispatcher();
        d.register(Command.WRITE, msg -> {
            seen.set(msg);
            return returnValue;
        });
        return d;
    }

    @Test
    void passesThroughToInnerDispatcher() throws InvocationAbortedException {
        AtomicReference<Message> seen = new AtomicReference<>();
        Dispatcher inner = dispatcherStoringLastMessage("wrote:k|v", seen);

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of());
        Message msg = new Message(Command.WRITE, List.of("k", "v"));
        String result = wrapper.dispatch(msg);

        assertEquals("wrote:k|v", result);
        assertEquals(msg, seen.get(), "inner dispatcher must have been called");
    }

    @Test
    void invokesInterceptorsInOrder() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.READ, m -> "read:" + m.firstArg());
        List<String> log = new ArrayList<>();

        InvocationInterceptor first = (c, ch) -> {
            log.add("first:pre");
            String r = ch.proceed(c);
            log.add("first:post:" + r);
            return r;
        };
        InvocationInterceptor second = (c, ch) -> {
            log.add("second:pre");
            String r = ch.proceed(c);
            log.add("second:post:" + r);
            return r;
        };

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of(first, second));
        String result = wrapper.dispatch(new Message(Command.READ, List.of("k")));

        assertEquals("read:k", result);
        assertEquals(
                List.of("first:pre", "second:pre", "second:post:read:k", "first:post:read:k"),
                log);
    }

    @Test
    void abortedInvocationReturnsWireFormError() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.WRITE, m -> "should-not-happen");

        InvocationInterceptor aborter = (c, ch) -> {
            throw new InvocationAbortedException("auth failed");
        };

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of(aborter));
        String result = wrapper.dispatch(new Message(Command.WRITE, List.of("k", "v")));

        assertEquals("ERRO: auth failed", result);
    }

    @Test
    void runtimeExceptionInInterceptorSurfacedAsError() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.WRITE, m -> "should-not-happen");

        InvocationInterceptor exploder = (c, ch) -> {
            throw new IllegalStateException("boom");
        };

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of(exploder));
        String result = wrapper.dispatch(new Message(Command.WRITE, List.of("k", "v")));

        assertTrue(result.startsWith("ERRO:"),
                "runtime exception must surface as a wire-form error, got: " + result);
        assertTrue(result.contains("IllegalStateException"),
                "error must name the exception type, got: " + result);
        assertTrue(result.contains("boom"),
                "error must include the exception message, got: " + result);
    }

    @Test
    void emptyInterceptorListBehavesLikeBareDispatcher() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.READ, m -> "read:" + m.firstArg());

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of());
        String result = wrapper.dispatch(new Message(Command.READ, List.of("foo")));

        assertEquals("read:foo", result);
    }

    @Test
    void dispatcherPropagatesInnerUnknownCommandError() throws InvocationAbortedException {
        // The wrapper should NOT swallow the inner dispatcher's wire-form error for unknown commands.
        Dispatcher inner = new Dispatcher(); // no handlers
        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, List.of());
        String result = wrapper.dispatch(new Message(Command.READ, List.of("foo")));
        assertTrue(result.startsWith("ERRO:"), "got: " + result);
    }

    @Test
    void constructorRejectsNullInner() {
        assertThrows(NullPointerException.class,
                () -> new InterceptingDispatcher(null, List.of()));
    }

    @Test
    void constructorRejectsNullInterceptors() {
        Dispatcher inner = new Dispatcher();
        assertThrows(NullPointerException.class,
                () -> new InterceptingDispatcher(inner, null));
    }

    @Test
    void constructorTakesImmutableSnapshotOfInterceptors() throws InvocationAbortedException {
        Dispatcher inner = new Dispatcher();
        inner.register(Command.WRITE, m -> "ok");
        List<InvocationInterceptor> source = new ArrayList<>();
        InvocationInterceptor a = (c, ch) -> {
            ch.proceed(c);
            return "A";
        };
        source.add(a);

        InterceptingDispatcher wrapper = new InterceptingDispatcher(inner, source);
        // Mutate source after construction; the wrapper must keep its snapshot.
        source.add((c, ch) -> {
            ch.proceed(c);
            return "B";
        });

        String result = wrapper.dispatch(new Message(Command.WRITE, List.of("k", "v")));
        assertEquals("A", result, "wrapper must only invoke interceptors in its original snapshot");
    }
}
