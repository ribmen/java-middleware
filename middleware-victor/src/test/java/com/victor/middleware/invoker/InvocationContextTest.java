package com.victor.middleware.invoker;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

class InvocationContextTest {

    @Test
    void forMessageStampsStartNanosNonZero() {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        assertTrue(ctx.startNanos() > 0L,
                "startNanos should be a positive nanoTime value, got " + ctx.startNanos());
    }

    @Test
    void elapsedNanosIncreasesAfterSleep() throws InterruptedException {
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        Thread.sleep(5); // ~5ms — generous on slow CI
        long elapsed = ctx.elapsedNanos();
        assertTrue(elapsed > 0L,
                "elapsedNanos should be positive after a 5ms sleep, got " + elapsed);
    }

    @Test
    void traceIdIsUniqueAcrossCalls() {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext a = InvocationContext.forMessage(m);
        InvocationContext b = InvocationContext.forMessage(m);
        assertNotEquals(a.traceId(), b.traceId(),
                "two forMessage calls must produce different trace ids");
    }

    @Test
    void traceIdHasUuidShape() {
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        assertNotNull(ctx.traceId());
        // UUID.toString() form: 8-4-4-4-12 hex digits separated by '-'
        assertTrue(Pattern.matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                        ctx.traceId()),
                "traceId '" + ctx.traceId() + "' does not match UUID shape");
    }
}