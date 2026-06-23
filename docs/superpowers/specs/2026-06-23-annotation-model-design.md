# Annotation model design (Phase 5D)

**Date:** 2026-06-23
**Status:** Proposed — awaiting user review.
**Scope:** Signature-preserving component model: `@MethodMapping` + `@Param` annotations, an `AnnotatedDispatcher`, and an end-to-end demo wired through the existing `MarshalledServer` JSON envelope.

---

## 1. Motivation and rubric position

The PDF rubric (per `docs/PDF-CHECKLIST.md` §Sumário) gives 2.0 weight to "Modelo de Componentes". The current model is the basic `Command` enum + pipe codec — that satisfies the basic half. The signature-preserving half (`@Param` / `@MethodMapping` on a business class) is the only unclosed cell in the 2.0-weight row. Closing it moves the rubric estimate from ~7.5/10.0 to ~9.5/10.0 (the only remaining gap being the optional `Lifecycle: Client-Dependent Instance + Passivation` row, which the user explicitly cancelled).

This spec does not migrate the production `Gateway` / `WorkerComponent` to the new model. The new SPI sits **alongside** the legacy `Dispatcher` / `RequestHandler`, by user direction. Production semantics are unchanged.

## 2. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ kvstore/src/main/java/com/victor/demo/                     │
│   AnnotatedWorkerDemo   (handler implementation)            │
└─────────────┬───────────────────────────────────────────────┘
              │ register(this)
              ▼
┌─────────────────────────────────────────────────────────────┐
│ middleware-victor/.../invoker/annotated/                    │
│   AnnotatedDispatcher                                       │
│     ├─ Map<Command, BoundMethod>                            │
│     ├─ Marshaller marshaller                                │
│     └─ AnnotationScanner.scan(Object)   ◄── one-shot        │
│                                                              │
│   @MethodMapping(Command)                                   │
│   @Param("name")                                            │
└─────────────┬───────────────────────────────────────────────┘
              │ dispatchTyped(message)
              ▼
┌─────────────────────────────────────────────────────────────┐
│ middleware-victor/.../marshaller/                           │
│   MarshalledServer.startTyped(port, TypedRequestHandler)    │
│   JsonMarshaller (JSON envelope)                            │
└─────────────┬───────────────────────────────────────────────┘
              │ TCP / HTTP / UDP
              ▼
         wire (JSON envelope)
```

Key seams:
- **`AnnotatedDispatcher` is server-side only.** Clients continue to use `MarshalledClient` from Phase 5B.
- **`Marshaller` is injected into `AnnotatedDispatcher`** so handler return types stay domain types (POJOs, `String`, etc.). Handlers don't construct `Message` values — that boilerplate stays inside the dispatcher.
- **The wire envelope shape is unchanged.** The JSON envelope is the same array-shape `{"verb":"…","args":[…],"body":{}}` that Phase 5B produced. The annotation model changes how the dispatcher *consumes* `Message.args` (positionally, see §3), not how `Message.args` is shaped on the wire.

## 3. Components in detail

### Annotations (new package `com.victor.middleware.invoker.annotated`)

```java
@Retention(RUNTIME)
@Target(METHOD)
public @interface MethodMapping {
    Command value();
}

@Retention(RUNTIME)
@Target(PARAMETER)
public @interface Param {
    String value();
}
```

`@Param` is validated for presence and uniqueness in v1 (see §5), and is also the binding documentation for readers — see the **Wire envelope — positional binding for v1** subsection below for why it isn't *name-keyed* at runtime.

### Marker interface

```java
public interface AnnotatedHandler {}   // empty marker
```

Intentionally empty. Demarcates "this object is safe to register with `AnnotatedDispatcher`". The dispatcher does **not** instanceof-check the marker — any `Object` can be registered; the marker is documentary.

### Internal records (package-private)

```java
record ParamBinding(String name, int parameterIndex) {}

record BoundMethod(Method method, List<ParamBinding> paramBindings, Object target) {}
```

### `AnnotationScanner` (package-private)

```java
final class AnnotationScanner {
    private AnnotationScanner() {}

    /** Walk handler's @MethodMapping methods, build Map<Command, BoundMethod>. */
    static Map<Command, BoundMethod> scan(Object handler) {
        // Asserts: see §5.
    }
}
```

Runs once at `register(...)` time. All registration-time validation lives here.

### `AnnotatedDispatcher` (public)

```java
public final class AnnotatedDispatcher {
    private final Map<Command, BoundMethod> bindings = new EnumMap<>(Command.class);
    private final Marshaller marshaller;

    public AnnotatedDispatcher(Marshaller marshaller) { ... }

    public void register(Object handler) { ... }     // throws on validation failure
    public boolean hasHandler(Command c) { ... }
    public Message dispatchTyped(Message msg) { ... } // never throws — see §5
}
```

`dispatchTyped(Message)` is the typed entry point — same shape as `Dispatcher.dispatchTyped`. Wire form (`String dispatch(String)`) is not exposed; the annotation model is strictly typed.

### Demo class (new, in `kvstore/`)

```java
package com.victor.demo;

public class AnnotatedWorkerDemo implements AnnotatedHandler {
    private final KVStore store;
    private final MarshalledServer server;
    private final AnnotatedDispatcher dispatcher;

    public AnnotatedWorkerDemo(int port) {
        this.store = new KVStore(new HashMap<>());
        this.dispatcher = new AnnotatedDispatcher(MarshallerFactory.json());
        this.dispatcher.register(this);
        this.server = new MarshalledServer(
            CommunicationFactory.createServer(CommunicationType.TCP),
            MarshallerFactory.json());
    }

    @MethodMapping(Command.WRITE)
    public String write(@Param("key") String key, @Param("value") String value) {
        return store.write(key, value);
    }

    @MethodMapping(Command.READ)
    public String read(@Param("key") String key) {
        return store.read(key).toString();
    }

    public void start() throws Exception {
        server.startTyped(port, dispatcher::dispatchTyped);
    }

    public void stop() { server.stop(); }

    public static void main(String[] args) throws Exception {
        new AnnotatedWorkerDemo(Integer.parseInt(args[0])).start();
    }
}
```

The class-level Javadoc on `AnnotatedWorkerDemo` will explicitly state: *"In v1, `@Param` is positional — `key` binds to `Message.args().get(0)`, `value` to `Message.args().get(1)`. Send the JSON envelope as `{"verb":"WRITE","args":["k1","v1"],"body":{}}`."*

Manual smoke step: `mvn exec:java -Dexec.mainClass=com.victor.demo.AnnotatedWorkerDemo -Dexec.args="8001"`, then `curl`/`nc` JSON envelopes.

### Wire envelope — positional binding for v1

**Decision: positional binding for v1, named binding deferred to v2 (see §8).**

The original brainstorm locked `@Param("name")` as *named* binding. The wire envelope's `args` is currently `List<String>` (positional), and broadening it to support named values cleanly requires either (a) widening `MessageEnvelope.args` to `Object` and teaching `JsonMarshaller` to surface a `Map<String,String>` through a richer return type, or (b) adding a third method to the `Marshaller` SPI. Either change is a blast-radius expansion that the user-cancelled "no production migration" goal of this phase rules out.

**v1 design:** `MessageEnvelope` and `JsonMarshaller` are **untouched**. The annotation dispatcher binds **positionally** — `@Param("key") String key` reads `Message.args().get(0)`, `@Param("value") String value` reads `Message.args().get(1)`. The `@Param` annotation's value is **validated for uniqueness and presence** (so two parameters can't claim the same name) but is otherwise documentary.

The JSON envelope on the wire remains `{"verb":"WRITE","args":["k1","v1"],"body":{}}` (array shape), same as Phase 5B. The annotation model changes how the dispatcher *consumes* `Message.args`, not how `Message.args` is shaped.

Rejected alternatives (recorded for the v2 spec): widen `MessageEnvelope.args` to `Object`; introduce an `UnmarshalledRequest` record bundling `Message` and `Map<String,String>`; add a default `Marshaller.unmarshalArgs(String)` method. The named-binding behavior is deferred to a v2 (out of scope for this spec).

## 4. Data flow

Inbound (server → dispatcher), for an array-shape envelope `{"verb":"WRITE","args":["k1","v1"],"body":{}}`:

1. `MarshalledServer` (existing Phase 5B) receives the raw JSON on its socket. It calls `marshaller.unmarshal(rawJson)` → returns `Message(Command.WRITE, ["k1","v1"])` (unchanged from Phase 5B).
2. `MarshalledServer.startTyped(port, dispatcher::dispatchTyped)` calls `dispatcher.dispatchTyped(message)`.
3. `AnnotatedDispatcher.dispatchTyped(message)`:
   - Look up `BoundMethod` in `Map<Command, BoundMethod>` for `message.command()`.
   - For each `ParamBinding` in declaration order, take `message.args().get(binding.parameterIndex())`.
   - Call `boundMethod.method().invoke(boundMethod.target(), arg0, arg1, …)`.
   - Take the return value (`Object`) and stringify it (`String.valueOf(returnValue)`), then pass to `marshaller.marshal(new Message(Command.OK, [stringResult]))`. This produces a `String` that `MarshalledServer` will write back as the JSON response body.
   - On any failure inside the chain → build `Message(Command.UNKNOWN, ["ERRO: …"])` and return it. `MarshalledServer` marshals that to JSON normally.

Registration:

`AnnotationScanner.scan(Object handler)` runs once at `register(...)` time. It walks `handler.getClass().getMethods()`, collects every method annotated `@MethodMapping(Command.X)`, asserts the validation rules in §5, and returns `Map<Command, BoundMethod>`.

Outbound (client → caller): not in scope. The annotation model lives server-side.

## 5. Error handling

### Registration-time failures — fail fast at `register(...)`, throw unchecked

| Failure | Exception | Message |
|---|---|---|
| `@MethodMapping` method not `public` | `IllegalArgumentException` | `"@MethodMapping method must be public: <method>"` |
| `@MethodMapping` method returns `void` | `IllegalArgumentException` | `"@MethodMapping method must not return void: <method>"` |
| `@MethodMapping` method declares a checked exception that's not a `MiddlewareException` subtype | `IllegalArgumentException` | `"@MethodMapping method may only throw MiddlewareException subtypes: <method> declares <X>"` |
| Two methods on the same object annotated with `@MethodMapping(Command.X)` | `IllegalStateException` | `"duplicate @MethodMapping for command X on <class>: <m1>, <m2>"` |
| Parameter has no `@Param` annotation | `IllegalStateException` | `"parameter <idx> of <method> has no @Param"` |
| Two parameters on the same method share the same `@Param("name")` | `IllegalStateException` | `"duplicate @Param('name') on <method>"` |
| `register(null)` | `NullPointerException` | `"handler must not be null"` |

### Dispatch-time failures — `dispatchTyped` never throws, returns `Message(UNKNOWN, …)`

| Failure | Wire form returned | Origin tag |
|---|---|---|
| `Command` has no binding | `Message(UNKNOWN, ["ERRO: no handler for command X"])` | `"INVOKER"` |
| `InvocationTargetException` → `MiddlewareException` | `Message(UNKNOWN, ["ERRO: <message>"])` | inherited `origin` |
| `InvocationTargetException` → other `Throwable` | `Message(UNKNOWN, ["ERRO: <ExceptionClassName>: <message>"])` | `"INVOKER"` |
| Reflection `IllegalAccessException` / `NoSuchMethodException` (impossible post-registration, caught defensively) | `Message(UNKNOWN, ["ERRO: dispatcher internal: <message>"])` | `"INVOKER"` |
| Marshaller throws `MarshalException` serializing the return value | `Message(UNKNOWN, ["ERRO: marshaller: <message>"])` | `"MARSHALLER"` |

Extra args in the inbound JSON (more elements in `Message.args` than the method's arity) are **ignored** — permissive on read, strict on bind. Missing args trigger the `InvocationTargetException` → `IllegalArgumentException` path (the handler receives `null` for the missing slot, and the first NPE-like call inside the handler throws; the dispatcher maps that to an `"ERRO: …"` response).

**HTTP 200 on `ERRO:` responses is a known gap.** `MarshalledServer` returns a `Message(UNKNOWN, …)` as a successful response, so HTTP 200 is what the client sees. Closing this would require changing `dispatchTyped`'s return type, which Phase 5B deliberated and rejected. The gap is accepted and called out in `MarshalledServer`'s Javadoc (one-line comment, not a code change in this phase).

## 6. Testing

Three layers, all JUnit 5, suite goes **97/97 + 2/2 → 123/123 + 3/3** (26 new tests across 7 new test files).

### Layer 1 — annotation + scanner unit tests (in `invoker/annotated/`, 4 test files)

- `MethodMappingTest` (1 test) — annotation presence round-trip.
- `ParamTest` (1 test) — annotation presence round-trip.
- `AnnotationScannerTest` (8 tests) — pin every registration-time failure: happy path, non-public, void return, bad checked exception, duplicate command, missing `@Param`, duplicate `@Param`, no `@MethodMapping` (empty result).
- `BoundMethodTest` (3 tests) — record accessors, `paramBindings()` declaration order, value equality.

### Layer 2 — dispatcher unit tests (`invoker/annotated/`, 2 test files)

`AnnotatedDispatcherTest` (8 tests):

1. Happy-path `dispatch` returns `Message(OK, [marshalledResult])` (using a stub `Marshaller`).
2. Unbound `Command` returns the "no handler" error.
3. Extra args in `Message.args` (longer list than the method's arity) are ignored.
4. Handler-thrown `MiddlewareException` is caught and the error `Message` is returned with the original `origin` preserved.
5. Handler-thrown unchecked `RuntimeException` is caught and the error `Message` includes the class name + message.
6. `MarshalException` from the marshaller is caught and a `Marshaller`-origin error is returned.
7. `hasHandler(Command.X)` returns `true` for bound commands, `false` for unbound.
8. Reflection `IllegalAccessException` (forced via a test-only `Method.setAccessible(false)` post-registration) returns an "internal" error.

`AnnotatedDispatcherRegistrationTest` (4 tests):

1. `register(Object)` populates the map.
2. `register(Object)` re-registering the same object replaces the binding.
3. Two different objects bound to the same `Command` → second `register` throws.
4. `register(null)` throws `NullPointerException`.

### Layer 3 — end-to-end test (in `kvstore/src/test/java/com/victor/integration/`, 1 test file)

`AnnotatedDispatcherEndToEndTest` (1 test) — sibling to `Phase3EndToEndTest`. Spins up `MarshalledServer` (TCP) wrapped around `AnnotatedDispatcher` registered with a small `KVStore`-backed test fixture, sends a JSON envelope over a raw `Socket`, asserts the JSON response shape, sends a malformed envelope (missing arg), asserts the `ERRO:` response.

### Test file count

7 new test files total: 4 in Layer 1, 2 in Layer 2, 1 in Layer 3. Test count: 1+1+8+3 + 8+4 + 1 = **26 tests**.

### What we are *not* testing

- Type coercion (only `String` params in v1; the spec explicitly defers this).
- Async return types.
- The `MarshalledServer.startTyped` overload is exercised by the e2e test, not in isolation.

### Demo verification (manual, not in test suite)

`mvn exec:java -Dexec.mainClass=com.victor.demo.AnnotatedWorkerDemo -Dexec.args="8001"`, then `curl`/`nc` JSON envelopes. Per `feedback-bash-background-jvm-sigterm.md`, manual smoke step, not Bash-driven.

## 7. Files touched / created

### New files (8 source + 7 test = 15 total)

Source:
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/MethodMapping.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/Param.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/AnnotatedHandler.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/AnnotatedDispatcher.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/BoundMethod.java` (package-private)
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/ParamBinding.java` (package-private)
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/AnnotationScanner.java` (package-private)
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/package-info.java`
- `kvstore/src/main/java/com/victor/demo/AnnotatedWorkerDemo.java`

Test:
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/MethodMappingTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/ParamTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/AnnotationScannerTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/BoundMethodTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/AnnotatedDispatcherTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/AnnotatedDispatcherRegistrationTest.java`
- `kvstore/src/test/java/com/victor/integration/AnnotatedDispatcherEndToEndTest.java`

### Modified files (2)

- `middleware-victor/src/main/java/com/victor/middleware/marshaller/MarshalledServer.java` — add `startTyped(int port, TypedRequestHandler handler)` overload. **Does not change** the existing `start(int, RequestHandler)` path; the 35 legacy tests stay green. Constructor: `MarshalledServer(ComponentServer inner, Marshaller marshaller) implements ComponentServer` (existing, unchanged). New method delegates to the existing `start` after wrapping the typed handler in a `RequestHandler` adapter that parses the typed `Message` back to wire form for the legacy `ComponentServer.start` signature — **or** adds a parallel typed path on `ComponentServer`. **Decision: add a parallel `startTyped` to `ComponentServer`** (and all three implementations: `HttpServer`, `TcpServer`, `UdpServer`). This is a wider blast radius than ideal but keeps the typed path clean. The 35 legacy tests on the wire-form path stay untouched.

  *Self-correction:* re-evaluating — `ComponentServer` is a sealed-style interface used everywhere. Adding a method breaks the 3 implementations. **Simpler decision:** keep `ComponentServer` unchanged, add `MarshalledServer.startTyped(int, TypedRequestHandler)` that *re-marshals* the typed `Message` back to wire form via `Message.toWireForm()` and feeds it through the existing `start(int, RequestHandler)` path. The wire-form conversion is cheap (string concat) and preserves the legacy contract. **Committing to this approach.** The `startTyped` method is the only new public surface on `MarshalledServer`.

- `docs/PDF-CHECKLIST.md` — flip "Modelo de Componentes" row from "Básico ✅, signature-preserving deferred" to "Básico ✅ + signature-preserving ✅". Update sumário estimate from ~7.5/10.0 to ~9.5/10.0.

### Untouched on purpose

- `Dispatcher.java` (Phase 1 design sealed — same precedent as Phase 5A).
- `kvstore/Gateway.java` and `kvstore/WorkerComponent.java` (production semantics unchanged; this phase adds an alternative, doesn't migrate).
- `MessageEnvelope.java` and `JsonMarshaller.java` (the wire envelope shape is unchanged; positional binding makes the existing array path sufficient).

## 8. Out of scope (deferred)

- **Type coercion** beyond `String` parameters. `@Param int count` would not compile-check today; we don't add coercion in v1.
- **Async return types** (`CompletableFuture<T>`).
- **Named binding** from the JSON object's keys — v1 uses positional binding; named binding requires a richer unmarshal return type and is deferred to a v2.
- **Migrating production `WorkerComponent`** to the annotation model. The new SPI is alongside; migration is a separate phase.
- **Lifecycle: Client-Dependent Instance + Passivation** — user cancelled 2026-06-23, not in scope.
- **JsonMarshaller hardening** (the "Task #73" debt) — out of band.

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Reflection errors at dispatch are confusing | Caught and translated to a wire-form `Message(UNKNOWN, ["ERRO: …"])`; pinned by `AnnotatedDispatcherTest` 4 and 8. |
| Handlers returning non-`String` types confuse the marshaller | `AnnotatedDispatcher` calls `String.valueOf(returnValue)` before constructing the success `Message`. Non-`String` returns are explicitly supported (the API type is `Object`) but the wire form is the string representation. Called out in the demo's Javadoc. |
| `@Param` looks like name-based binding to a reader, but is positional | Demo class Javadoc calls this out explicitly. The §3 "Wire envelope" subsection names the limitation. The v2 spec (out of scope) is the resolution. |
| `MarshalledServer.startTyped` re-marshals typed `Message` → wire form → typed `Message` | Cheap (one `toWireForm()` string concat). The legacy `start(int, RequestHandler)` path stays the canonical entry; `startTyped` is a convenience. The 35 legacy tests on the wire-form path stay green because the implementation of `start` is unchanged. |
| Cross-class duplicate `Command` binding is a runtime check, not a registration-time scan | Documented in §5; the registration tests pin the *behavior* (it throws) without committing to a particular step in `register`. |
