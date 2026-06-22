# Invocation Interceptor + Invocation Context

**Status**: design approved, pending plan
**Date**: 2026-06-22
**Author**: Victor (with Claude)
**Targets**: Extension Patterns rubric (§10 of `Descrição do Segundo Trabalho Prático`)

---

## 1. Purpose

Wrap the existing `Dispatcher` with a chain of cross-cutting concerns — logging,
latency, retry, auth — without modifying either the dispatcher or the handlers
it routes to. Add a minimal `InvocationContext` so interceptors can carry
per-invocation state (trace id, start time) through the chain.

The shape matches the textbook Extension Pattern from POSA2: a chain of
`around` methods around a terminal handler. It is intentionally narrow — the
seed stays plain; interceptors are opt-in.

## 2. Scope

### In scope

- New SPI: `InvocationInterceptor` (`@FunctionalInterface`).
- New SPI: `InvocationChain` (single-method, allows `proceed()`).
- New value type: `InvocationContext` (record).
- New checked exception: `InvocationAbortedException` (extends `MiddlewareException`).
- New dispatcher wrapper: `InterceptingDispatcher` (same external contract as
  `Dispatcher.dispatch`).
- One built-in interceptor: `LoggingInterceptor` (logs `IN` and `OUT`).
- Test coverage: ~15 new tests across four new test classes.

### Out of scope (deferred)

- Caller host/port in `InvocationContext` — would require plumbing remote
  endpoint info from the server layer through `RequestHandler`. Deferred to
  Phase 5B at earliest.
- Annotation-driven interceptors (`@WithInterceptors(...)`).
- Mutable interceptor registration (`addInterceptor` after startup).
- Marshaller, Client-Dependent Instance, Passivation, JMeter, Knee/Usable
  Capacity — separate phases.

## 3. Design

### 3.1 New types

| Type | Kind | Package |
|---|---|---|
| `InvocationContext` | `record` (immutable) | `com.victor.middleware.invoker` |
| `InvocationInterceptor` | interface, `@FunctionalInterface` | same |
| `InvocationChain` | interface, single method | same |
| `InvocationAbortedException` | checked exception, extends `MiddlewareException` | `com.victor.middleware.exceptions` |
| `InterceptingDispatcher` | final class | `com.victor.middleware.invoker` |
| `LoggingInterceptor` | final class, implements `InvocationInterceptor` | same |

### 3.2 `InvocationContext` shape

```java
public record InvocationContext(
        String traceId,   // UUID v4 string
        long startNanos,  // System.nanoTime() at dispatch entry
        Command command,
        List<String> args) {

    public static InvocationContext forMessage(Message m) { ... }
    public long elapsedNanos() { return System.nanoTime() - startNanos; }
}
```

The factory `forMessage` generates the trace id and stamps `startNanos`.
`args` is a defensive copy of `m.args()` (consistent with `Message`'s own
defensive-copy contract).

### 3.3 `InvocationInterceptor` shape

```java
@FunctionalInterface
public interface InvocationInterceptor {
    void around(InvocationContext ctx, InvocationChain chain)
            throws InvocationAbortedException;
}
```

Single `around` method (not pre/post). The interceptor decides whether and
when to call `chain.proceed(ctx)`. To skip the rest of the chain (e.g. an auth
failure), the interceptor does *not* call `proceed` and either returns
normally (the wrapper sees no exception, treats it as a no-op chain) **or**
throws `InvocationAbortedException` with a wire-form error message.

The contract for "abort": always throw `InvocationAbortedException` so the
wrapper has a single, type-driven code path that converts it to
`"ERRO: <message>"`.

### 3.4 `InvocationChain` shape

```java
public interface InvocationChain {
    String proceed(InvocationContext ctx) throws InvocationAbortedException;
}
```

One private impl, `InvocationChainImpl`, constructed by `InterceptingDispatcher`:

```java
final class InvocationChainImpl implements InvocationChain {
    private final List<InvocationInterceptor> interceptors;
    private final int index;
    private final Function<Message, String> terminal;

    @Override
    public String proceed(InvocationContext ctx) throws InvocationAbortedException {
        if (index < interceptors.size()) {
            InvocationInterceptor next = interceptors.get(index);
            InvocationChain child = new InvocationChainImpl(
                    interceptors, index + 1, terminal);
            next.around(ctx, child);
            return null;  // wrapper awaits explicit return from outer around
        }
        return terminal.apply(new Message(ctx.command(), ctx.args()));
    }
}
```

**Important**: the wrapper's `dispatch` does not rely on the return value of
`proceed`; it relies on the outermost interceptor returning the result via
its own `around` implementation. The standard pattern:

```java
LoggingInterceptor.around(ctx, chain) {
    System.out.println("IN " + ctx.traceId());
    String result = chain.proceed(ctx);     // returns the terminal result
    System.out.println("OUT " + result);
    // returning void; the wrapper records the result via the chain
}
```

To make this work cleanly, `InvocationChain.proceed` *returns* the terminal
result string, and `around` returns `void`. The interceptor is responsible
for either returning the result or throwing `InvocationAbortedException`.
`InterceptingDispatcher.dispatch` does:

```java
String result;
try {
    chain.proceed(ctx);
    result = lastSeen.get();    // populated by the terminal call
} catch (InvocationAbortedException e) {
    result = "ERRO: " + e.getMessage();
}
return result;
```

**Refinement** (cleaner than `lastSeen.get()`): make `around` return
`String`, the same shape as `dispatch`. `proceed` returns the terminal
handler's `String`. Interceptors transform or wrap the value. `void around`
is replaced by `String around` for symmetry with the existing `Dispatcher`
contract.

```java
@FunctionalInterface
public interface InvocationInterceptor {
    String around(InvocationContext ctx, InvocationChain chain)
            throws InvocationAbortedException;
}

@FunctionalInterface
public interface InvocationChain {
    String proceed(InvocationContext ctx) throws InvocationAbortedException;
}
```

The terminal call (`proceed` on an exhausted chain) returns
`terminal.apply(message)` directly. Interceptors on the way up see the
result string and can transform/log/wrap it. This is the standard
interceptor pattern (matches Spring's `HandlerInterceptor.postHandle` shape
in spirit).

### 3.5 `InvocationAbortedException` shape

```java
public class InvocationAbortedException extends MiddlewareException {
    public InvocationAbortedException(String message) {
        super("INVOKER", message);
    }
    public InvocationAbortedException(String message, Throwable cause) {
        super("INVOKER", message, cause);
    }
}
```

`origin = "INVOKER"`. Checked, so aborting interceptors must declare it.
This is consistent with the rest of the middleware exception tree (every
checked exception has an `origin` tag).

### 3.6 `InterceptingDispatcher` shape

```java
public final class InterceptingDispatcher {
    private final Dispatcher inner;
    private final List<InvocationInterceptor> interceptors;  // unmodifiable

    public InterceptingDispatcher(
            Dispatcher inner, List<InvocationInterceptor> interceptors) {
        this.inner = Objects.requireNonNull(inner);
        this.interceptors = List.copyOf(Objects.requireNonNull(interceptors));
    }

    public String dispatch(Message msg) {
        InvocationContext ctx = InvocationContext.forMessage(msg);
        InvocationChain terminalChain = new InvocationChainImpl(
                interceptors, 0, inner::dispatch);
        try {
            return terminalChain.proceed(ctx);
        } catch (InvocationAbortedException e) {
            return "ERRO: " + e.getMessage();
        } catch (RuntimeException e) {
            return "ERRO: Interceptor lançou "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}
```

Notes:
- `interceptors` is `List.copyOf(...)` — immutable, defensive copy, `null`
  arguments rejected.
- The wrapper has the same external contract as `Dispatcher.dispatch`
  (`Message -> String`). Callers (servers, tests, future integration code)
  can swap a `Dispatcher` for an `InterceptingDispatcher` with no signature
  change at the call site *if* they go through a shared interface — but for
  now we do not introduce one. The `Dispatcher` itself stays untouched.

### 3.7 `LoggingInterceptor` shape

```java
public final class LoggingInterceptor implements InvocationInterceptor {
    @Override
    public String around(InvocationContext ctx, InvocationChain chain)
            throws InvocationAbortedException {
        System.out.println("[INVOKER] IN  trace=" + ctx.traceId()
                + " cmd=" + ctx.command().wireForm()
                + " args=" + ctx.args());
        try {
            String result = chain.proceed(ctx);
            System.out.println("[INVOKER] OUT trace=" + ctx.traceId()
                    + " elapsed_ms=" + (ctx.elapsedNanos() / 1_000_000)
                    + " result=" + result);
            return result;
        } catch (InvocationAbortedException e) {
            System.out.println("[INVOKER] OUT trace=" + ctx.traceId()
                    + " ABORTED msg=" + e.getMessage());
            throw e;
        }
    }
}
```

Log shape: `[INVOKER] IN  trace=<uuid> cmd=<CMD> args=[...]`,
`[INVOKER] OUT trace=<uuid> elapsed_ms=<n> result=<string>`,
`[INVOKER] OUT trace=<uuid> ABORTED msg=<string>`.

## 4. Sequence (one WRITE call, one LoggingInterceptor)

```
Caller → InterceptingDispatcher.dispatch(msg)
  → ctx = InvocationContext.forMessage(msg)         // traceId stamped, startNanos recorded
  → chain = InvocationChainImpl([Logging], inner::dispatch)
  → chain.proceed(ctx)
      → LoggingInterceptor.around(ctx, childChain)
          [IN] System.out: "[INVOKER] IN  trace=... cmd=WRITE args=[k,v]"
          childChain.proceed(ctx)
            → childChain.index == 1 == interceptors.size() → terminal
            → inner.dispatch(msg) → "wrote:k|v"
          [OUT] System.out: "[INVOKER] OUT trace=... elapsed_ms=0 result=wrote:k|v"
          return "wrote:k|v"
  ← "wrote:k|v"
```

If a second interceptor were present (e.g. an AuthInterceptor stub):
```
chain.proceed(ctx)
  → AuthInterceptor.around(ctx, childChain)
      [if fails] throw new InvocationAbortedException("auth failed")
  ← InterceptingDispatcher.dispatch catches InvocationAbortedException
  ← returns "ERRO: auth failed"
```

## 5. Test plan (TDD, 15 tests)

### 5.1 `InvocationContextTest` (4 tests)

| Test | What it pins |
|---|---|
| `forMessageStampsStartNanosNonZero` | Timestamp recorded at construction. |
| `elapsedNanosIncreasesAfterSleep` | `elapsedNanos()` returns a non-zero positive value after a brief sleep. |
| `traceIdIsUniqueAcrossCalls` | Two `forMessage` calls produce different trace ids. |
| `traceIdHasUuidShape` | Matches `UUID.toString()` regex. |

### 5.2 `InvocationChainTest` (4 tests)

A tiny test-only constructor on `InvocationChainImpl` (package-private) or a
small `InvocationChain` factory exposed via a `static` method on
`InterceptingDispatcher` for tests. Either way, the chain is constructable
without spinning up a `Dispatcher`.

| Test | What it pins |
|---|---|
| `exhaustedChainCallsTerminalHandler` | Empty interceptor list → terminal invoked, result returned. |
| `nonEmptyChainCallsInterceptorsInOrder` | Two interceptors; record call order; verify pre-order. |
| `interceptorCanShortCircuitByNotCallingProceed` | `around` returns its own value without calling `proceed`; terminal never invoked; the chain returns the interceptor's own returned string. |
| `interceptorCanWrapResultFromProceed` | `around` calls `proceed`, transforms the string, returns transformed. |

### 5.3 `InterceptingDispatcherTest` (5 tests)

| Test | What it pins |
|---|---|
| `passesThroughToInnerDispatcher` | Bare dispatcher behavior preserved with empty interceptor list. |
| `invokesInterceptorsInOrder` | Two `RecordingInterceptor`s; verify call order. |
| `abortedInvocationReturnsWireFormError` | Interceptor throws `InvocationAbortedException("nope")`; result is `"ERRO: nope"`. |
| `runtimeExceptionInInterceptorSurfacedAsError` | Interceptor throws `RuntimeException`; result is `"ERRO: ..."`. |
| `interceptorCanMutateContextArgsAndSeeUpdatedValueDownstream` | First interceptor appends to `ctx.args()` (via new `withArgs` helper or by replacing args in a context wrapper — for the seed, mutation goes through a fresh context; deferred) |

**Defer the last test** — `InvocationContext` is a record (immutable), so
mutation would require an `InvocationContext.withArgs(...)` helper that
returns a new record. Out of scope for the seed. Replace with:

| Test | What it pins |
|---|---|
| `emptyInterceptorListBehavesLikeBareDispatcher` | Same dispatch result for the same `Message` when no interceptors are present. |

### 5.4 `LoggingInterceptorTest` (3 tests)

| Test | What it pins |
|---|---|
| `preLogsCommandAndArgsInExpectedFormat` | Capture `System.out`, run interceptor that calls `chain.proceed`, assert log contains `IN  trace=` and `cmd=WRITE`. |
| `postLogsResultAndElapsedMs` | Same setup, assert log contains `OUT trace=` and `result=`. |
| `abortedInvocationLogsAbortAndRethrows` | Stub chain that throws `InvocationAbortedException`, assert log contains `ABORTED` and the exception propagates. |

### 5.5 Untouched

- `DispatcherTest.java` — must keep passing unchanged. Verified by `mvn test`
  after the refactor lands.

## 6. File changes

```
middleware-victor/src/main/java/com/victor/middleware/invoker/
  InvocationContext.java          (NEW, record)
  InvocationInterceptor.java      (NEW, interface)
  InvocationChain.java            (NEW, interface + InvocationChainImpl package-private)
  InvocationAbortedException.java (NEW — but in exceptions package; see below)
  InterceptingDispatcher.java     (NEW, final class)
  LoggingInterceptor.java         (NEW, final class)
  Dispatcher.java                 (UNCHANGED)
  package-info.java               (UPDATED — mention new types and the chain)

middleware-victor/src/main/java/com/victor/middleware/exceptions/
  InvocationAbortedException.java (NEW)
  package-info.java               (UNCHANGED — already mentions new types generically)

middleware-victor/src/test/java/com/victor/middleware/invoker/
  InvocationContextTest.java      (NEW)
  InvocationChainTest.java        (NEW)
  InterceptingDispatcherTest.java (NEW)
  LoggingInterceptorTest.java     (NEW)
  DispatcherTest.java             (UNCHANGED)
```

No changes to `kvstore/`, `protocol/`, `spi/`, `server/`, `client/`,
`factory/`, `registry/`, `util/`, or `Dispatcher.java`. The new exception
extends `MiddlewareException`, so no existing exception-handling code breaks.

## 7. Decisions recorded

1. **`String around(...)` not `void around(...)`.** Symmetric with the
   `Dispatcher.dispatch(Message) → String` contract and matches the textbook
   pattern. Interceptors transform or wrap the result.
2. **Abort via `InvocationAbortedException`, not via return value.** Keeps the
   "happy path" return value shape clean (`String`) and gives the wrapper a
   single, type-driven code path.
3. **Constructor-only interceptor registration.** No mutable API surface.
   `interceptors` is an immutable defensive copy via `List.copyOf`.
4. **Built-in: `LoggingInterceptor` only.** Other interceptors (Latency,
   Auth, TraceId) ship later. The seed proves the shape works; one demo
   interceptor is enough for Phase 5A.
5. **`InvocationContext` is per-invocation only.** No caller host/port.
   Plumbing that through `RequestHandler` is Phase 5B at earliest.
6. **`InvocationContext` is immutable (record).** No `withArgs` helper in
   this seed; interceptors that need to mutate context create a new
   `InvocationContext` via the canonical constructor (visible to tests, not
   common in user code).
7. **`InvocationChainImpl` is package-private.** Exposed for tests in the
   same package; not part of the public SPI surface.
8. **No interface unification of `Dispatcher` and `InterceptingDispatcher`.**
   Both expose `String dispatch(Message)`, but a common interface is YAGNI
   for the seed. Add one when a third dispatcher shape appears.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Interceptor exception handling overlaps `Dispatcher.dispatch`'s handler-exception handling. | Both layers wrap with the same `"ERRO: ..."` shape. Tests pin the exact format. |
| Recursive `chain.proceed` if an interceptor calls it twice. | Not prevented at runtime — but tests cover the normal flow. Document "call at most once" in `InvocationChain` Javadoc. |
| Test logging produces noisy output during `mvn test`. | Tests that capture `System.out` use a small helper that redirects to a `ByteArrayOutputStream` and restores in `@AfterEach`. |

## 9. Acceptance criteria

- All 15 new tests pass.
- All 35 existing `middleware-victor` tests still pass.
- `kvstore`'s 1 test still passes.
- `package-info.java` in `invoker/` mentions the new types.
- `docs/PDF-CHECKLIST.md` updated: "Invocation Interceptor ✅" row added in
  Extension Patterns.
- Commit message references this spec.