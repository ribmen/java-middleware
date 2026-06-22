package com.victor.middleware.invoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.victor.middleware.exceptions.InvocationAbortedException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

class LoggingInterceptorTest {

    private PrintStream originalOut;
    private ByteArrayOutputStream buffer;

    @BeforeEach
    void redirectStdout() {
        originalOut = System.out;
        buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(originalOut);
    }

    private String captured() {
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** Test-only InvocationChain stub that records calls and returns a fixed result. */
    private static final class StubChain implements InvocationChain {
        int proceedCalls = 0;
        final String resultToReturn;
        final InvocationAbortedException toThrow;

        StubChain(String resultToReturn) {
            this(resultToReturn, null);
        }

        StubChain(String resultToReturn, InvocationAbortedException toThrow) {
            this.resultToReturn = resultToReturn;
            this.toThrow = toThrow;
        }

        @Override
        public String proceed(InvocationContext ctx) throws InvocationAbortedException {
            proceedCalls++;
            if (toThrow != null) {
                throw toThrow;
            }
            return resultToReturn;
        }
    }

    @Test
    void preLogsCommandAndArgsInExpectedFormat() throws InvocationAbortedException {
        LoggingInterceptor interceptor = new LoggingInterceptor();
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        StubChain chain = new StubChain("wrote:k|v");

        interceptor.around(ctx, chain);

        String out = captured();
        assertTrue(out.contains("[INVOKER] IN"),
                "expected IN log line, got: " + out);
        assertTrue(out.contains("trace=" + ctx.traceId()),
                "expected IN line to include traceId, got: " + out);
        assertTrue(out.contains("cmd=WRITE"),
                "expected IN line to include cmd=WRITE, got: " + out);
        assertTrue(out.contains("args=[k, v]"),
                "expected IN line to include args=[k, v], got: " + out);
    }

    @Test
    void postLogsResultAndElapsedMs() throws InvocationAbortedException {
        LoggingInterceptor interceptor = new LoggingInterceptor();
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        StubChain chain = new StubChain("read:k");

        String result = interceptor.around(ctx, chain);

        assertEquals("read:k", result, "interceptor must pass through the chain's result");
        String out = captured();
        assertTrue(out.contains("[INVOKER] OUT"),
                "expected OUT log line, got: " + out);
        assertTrue(out.contains("trace=" + ctx.traceId()),
                "expected OUT line to include traceId, got: " + out);
        assertTrue(out.contains("result=read:k"),
                "expected OUT line to include result=read:k, got: " + out);
        assertTrue(out.contains("elapsed_ms="),
                "expected OUT line to include elapsed_ms=, got: " + out);
    }

    @Test
    void abortedInvocationLogsAbortAndRethrows() {
        LoggingInterceptor interceptor = new LoggingInterceptor();
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        StubChain chain = new StubChain(null, new InvocationAbortedException("nope"));

        InvocationAbortedException ex = assertThrows(InvocationAbortedException.class,
                () -> interceptor.around(ctx, chain));
        assertEquals("nope", ex.getMessage());

        String out = captured();
        assertTrue(out.contains("[INVOKER] OUT"),
                "expected OUT log line even on abort, got: " + out);
        assertTrue(out.contains("ABORTED"),
                "expected ABORTED marker in OUT line, got: " + out);
        assertTrue(out.contains("msg=nope"),
                "expected msg=nope in OUT line, got: " + out);
    }

    @Test
    void chainIsAlwaysCalledExactlyOnceOnHappyPath() throws InvocationAbortedException {
        LoggingInterceptor interceptor = new LoggingInterceptor();
        Message m = new Message(Command.WRITE, List.of("k", "v"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        StubChain chain = new StubChain("ok");

        interceptor.around(ctx, chain);

        assertEquals(1, chain.proceedCalls, "logging interceptor must call proceed exactly once");
    }

    @Test
    void inLogAppearsBeforeOutLog() throws InvocationAbortedException {
        LoggingInterceptor interceptor = new LoggingInterceptor();
        Message m = new Message(Command.READ, List.of("k"));
        InvocationContext ctx = InvocationContext.forMessage(m);
        StubChain chain = new StubChain("read:k");

        interceptor.around(ctx, chain);

        String out = captured();
        int inIdx = out.indexOf("[INVOKER] IN");
        int outIdx = out.indexOf("[INVOKER] OUT");
        assertTrue(inIdx >= 0 && outIdx >= 0,
                "both IN and OUT lines must be present, got: " + out);
        assertTrue(inIdx < outIdx,
                "IN must appear before OUT, got IN@" + inIdx + " OUT@" + outIdx);
    }
}
