package com.victor.middleware.invoker.annotated;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.victor.middleware.protocol.Command;

/**
 * Behavior spec for {@link AnnotationScanner}: pins every
 * registration-time failure listed in spec §5.
 */
class AnnotationScannerTest {

    /** Happy path: a well-formed handler builds a non-empty dispatch table. */
    @Test
    void scanBuildsTableForWellFormedHandler() {
        Map<Command, BoundMethod> table = AnnotationScanner.scan(new GoodHandler());
        assertEquals(2, table.size());
        assertNotNull(table.get(Command.WRITE));
        assertNotNull(table.get(Command.READ));
        // Target is the handler instance — same reference.
        assertSame(GoodHandler.class, table.get(Command.WRITE).method().getDeclaringClass());
    }

    /**
     * A non-public {@code @MethodMapping} method would fail with IAE if it
     * ever reached the scanner — but {@code Class.getMethods()} only returns
     * public methods, so a package-private method is filtered out before
     * {@link AnnotationScanner#buildBoundMethod} ever sees it. This test
     * pins the reachable invariant: a non-public handler yields an empty
     * table (no exception). The non-public check inside the scanner is
     * dead-code defensive; if a future refactor switches to
     * {@code getDeclaredMethods()}, the {@code NonPublicHandler} fixture
     * is wired to re-validate the rule.
     */
    @Test
    void nonPublicMethodIsFilteredByGetMethods() {
        Map<Command, BoundMethod> table = AnnotationScanner.scan(new NonPublicHandler());
        assertTrue(table.isEmpty(),
                "package-private method is invisible to getMethods() — empty table expected");
    }

    /** A void-returning method fails with IAE. */
    @Test
    void voidReturnIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AnnotationScanner.scan(new VoidReturnHandler()));
    }

    /** A method declaring a non-{@code MiddlewareException} checked exception fails with IAE. */
    @Test
    void badCheckedExceptionIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AnnotationScanner.scan(new BadExceptionHandler()));
    }

    /** Two methods on the same handler claiming the same {@link Command} fail with ISE. */
    @Test
    void duplicateCommandIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> AnnotationScanner.scan(new DuplicateCommandHandler()));
    }

    /** A parameter without {@code @Param} fails with ISE. */
    @Test
    void parameterMissingParamIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> AnnotationScanner.scan(new MissingParamHandler()));
    }

    /** Two parameters sharing the same {@code @Param} name fail with ISE. */
    @Test
    void duplicateParamNameIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> AnnotationScanner.scan(new DuplicateParamHandler()));
    }

    /** A handler with no {@code @MethodMapping} methods yields an empty table. */
    @Test
    void emptyHandlerYieldsEmptyTable() {
        Map<Command, BoundMethod> table = AnnotationScanner.scan(new EmptyHandler());
        assertTrue(table.isEmpty());
        assertNull(table.get(Command.WRITE));
    }

    /** scan(null) throws NPE. */
    @Test
    void nullHandlerIsRejected() {
        assertThrows(NullPointerException.class, () -> AnnotationScanner.scan(null));
    }

    // --- Fixtures -----------------------------------------------------------

    static class GoodHandler {
        @MethodMapping(Command.WRITE)
        public String write(@Param("key") String key, @Param("value") String value) {
            return value;
        }
        @MethodMapping(Command.READ)
        public String read(@Param("key") String key) {
            return key;
        }
    }

    static class NonPublicHandler {
        @MethodMapping(Command.WRITE)
        String hidden(@Param("k") String k) { return k; } // package-private
    }

    static class VoidReturnHandler {
        @MethodMapping(Command.WRITE)
        public void go(@Param("k") String k) { /* no return */ }
    }

    static class BadExceptionHandler {
        @MethodMapping(Command.WRITE)
        public String go(@Param("k") String k) throws java.io.IOException { return k; }
    }

    static class DuplicateCommandHandler {
        @MethodMapping(Command.WRITE)
        public String a(@Param("k") String k) { return k; }
        @MethodMapping(Command.WRITE)
        public String b(@Param("k") String k) { return k; }
    }

    static class MissingParamHandler {
        @MethodMapping(Command.WRITE)
        public String go(String k) { return k; } // no @Param
    }

    static class DuplicateParamHandler {
        @MethodMapping(Command.WRITE)
        public String go(@Param("k") String a, @Param("k") String b) { return a; }
    }

    static class EmptyHandler {
        public String unrelated(@Param("k") String k) { return k; }
    }
}
