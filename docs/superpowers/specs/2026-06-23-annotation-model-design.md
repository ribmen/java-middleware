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
- **The wire envelope shape changes when the annotation model is in use:** `args` becomes a JSON object `{key:value, …}` rather than a JSON array `[…]`. The pipe codec (`MessageParser`) and the array-shape JSON path (legacy `RequestHandler` SPI) continue to work unchanged.

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

### Marker interface

```java
public interface AnnotatedHandler {}   // empty marker
```

Intentionally empty. Demarcates "this object is safe to register with `AnnotatedDispatcher`" and is the only thing the registration type-check enforces (no instanceof check on the dispatcher — any `Object` can be registered; the marker is documentary).

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
        // Asserts: see Section 5.
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
    public Message dispatchTyped(Message msg) { ... } // never throws — see Section 5
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

Manual smoke step: `mvn exec:java -Dexec.mainClass=com.victor.demo.AnnotatedWorkerDemo -Dexec.args="8001"`, then `curl`/`nc` JSON envelopes.

### Wire envelope change (touching `marshaller/`)

To support named binding, `MessageEnvelope.args` is widened from `List<String>` to `Object`. `JsonMarshaller` is updated:

- On **unmarshal**, after the existing `verb` pre-validation pass, accept `args` as either a JSON array (existing path) or a JSON object. The array path produces `Message.args = List<String>` (unchanged). The object path produces `Message.args = List<String>` where the list is the *list of all values in the object in declaration order* — but this is **not** what the annotation model wants. The annotation model needs `Map<String,String>` semantics for named binding.

**Refined design (added after Section 3 review):** the JSON object's `args` are surfaced to the dispatcher via a side-channel rather than crammed into `Message.args`. Specifically:

- `MessageEnvelope` gets a second field `Object argMap` (or a sibling `Map<String,Object> argMap`). It is `null` for array-shape envelopes, populated for object-shape envelopes.
- `JsonMarshaller.unmarshal` reads both shapes. For array-shape it returns `Message(args = List<String>)` as today. For object-shape it returns `Message(args = List<String>)` (a *placeholder* — e.g. empty list) and exposes the map through a new `JsonMarshaller.lastArgMap()` thread-local-free getter — **or**, cleaner, through a package-private return wrapper.

**Final design (after iterating against Section 3 review feedback):** introduce a `UnmarshalledRequest` record in the `marshaller` package that bundles `(Message envelope, Map<String,Object> argMap)`. `JsonMarshaller.unmarshal` returns `UnmarshalledRequest`. The legacy `Marshaller` SPI stays returning `Message` (it gains a default method), and `JsonMarshaller` overrides it to throw `UnsupportedOperationException` (callers who need the map use `JsonMarshaller` directly). `MarshalledServer` and `MarshalledClient` are updated to consume the new return shape.

**Decision: take the simpler path.** `JsonMarshaller.unmarshal` continues to return `Message` with `args = List<String>` (the **values** of the JSON object in declaration order, for object-shape envelopes). The annotation dispatcher reads named values from `Message.args` by *index* (declaring `@Param("key")` and `@Param("value")` matches the order in which Jackson sees the object fields).

This avoids API surface changes, keeps `Marshaller` SPI intact, and the demo class demonstrates that the JSON object arrives in declaration order. **Drawback: positional binding instead of name-based binding, which is exactly what `@Param` was supposed to fix.**

**Reopening Decision 3 (arg binding) at spec-write time:** The original brainstorm locked `@Param("name")` as named binding. Reconciling that with the wire-envelope reality requires a richer return type. After this consideration, the spec **re-affirms named binding** with the following mechanism:

- `JsonMarshaller.unmarshal` continues to return `Message` with `args = List<String>`. **For object-shape envelopes, `args` contains the *keys* (in declaration order), not the values.** The dispatcher reads by key from the JSON object by re-parsing the raw wire — *no*, that's worse.
- **Final, committed design:** add a third method to the `Marshaller` SPI: `default Map<String, String> unmarshalArgs(String wire)` returning an empty map by default. `JsonMarshaller` overrides it to return the object-shape keys→values map, or an empty map for array-shape. `AnnotatedDispatcher` takes the `Marshaller` interface but casts to `JsonMarshaller` if it needs the map — **no, that defeats the SPI**.

**Committed final-final design:** this spec goes with **positional binding** for v1. `@Param("key")` becomes a **documentation annotation** in v1 — its value is validated for uniqueness and required-ness, but the dispatcher binds *positionally* into `Message.args`. This keeps the SPI surface minimal and the wire envelope unchanged. The named-binding behavior is deferred to a v2 (out of scope for this spec, possibly Phase 5E).

**Action item for the spec reader:** the demo class uses `@Param("key")` and `@Param("value")` as documentation, and the JSON object is sent in key-then-value order. The dispatcher binds positionally. This is **a deliberate simplification** and is called out in the demo's class-level Javadoc.

## 4. Data flow

Inbound (server → dispatcher), for an object-shape envelope `{"verb":"WRITE","args":{"key":"k1","value":"v1"},"body":{}}`:

1. `MarshalledServer` (existing Phase 5B) receives the raw JSON on its socket. It calls `marshaller.unmarshal(rawJson)` → returns `Message(Command.WRITE, ["k1","v1"])` (values, not keys, in declaration order — see §3 final-final design).
2. `MarshalledServer` calls `dispatcher.dispatchTyped(message)`.
3. `AnnotatedDispatcher.dispatchTyped(message)`:
   - Look up `BoundMethod` in `Map<Command, BoundMethod>` for `message.command()`.
   - For each `ParamBinding` in declaration order, take `message.args().get(binding.parameterIndex())`.
   - Call `boundMethod.method().invoke(boundMethod.target(), arg0, arg1, …)`.
   - Take the return value (`Object`) and pass it to `marshaller.marshal(new Message(Command.OK, [returnValue.toString()]))` (or — better — to a new `marshaller.marshalObject(Object)` that returns the wire JSON directly). **Decision: use `marshaller.marshal(Message)` and stringify the return value into a single-element args list.** This avoids a new SPI method.
   - On any failure inside the chain → build `Message(Command.UNKNOWN, ["ERRO: …"])` and return it.

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
| Reflection `IllegalAccessException` / `NoSuchMethodException` (impossible post-registration, but caught defensively) | `Message(UNKNOWN, ["ERRO: dispatcher internal: <message>"])` | `"INVOKER"` |
| Marshaller throws `MarshalException` serializing the return value | `Message(UNKNOWN, ["ERRO: marshaller: <message>"])` | `"MARSHALLER"` |

Extra args in the inbound JSON (extra keys beyond what's declared on the method) are **ignored** — permissive on read, strict on bind. Missing args trigger the `InvocationTargetException` → `IllegalArgumentException` path (the handler receives `null` for the missing slot, and the first NPE-like call inside the handler throws; the dispatcher maps that to an `"ERRO: …"` response).

**HTTP 200 on `ERRO:` responses is a known gap.** `MarshalledServer` returns a `Message(UNKNOWN, …)` as a successful response, so HTTP 200 is what the client sees. Closing this would require changing `dispatchTyped`'s return type, which Phase 5B deliberated and rejected. The gap is accepted and called out in `MarshalledServer`'s Javadoc (one-line comment, not a code change in this phase).

## 6. Testing

Three layers, all JUnit 5, suite goes **97/97 + 2/2 → 123/123 + 3/3** (26 new tests).

### Layer 1 — annotation + scanner unit tests (in `invoker/annotated/`)

- `@MethodMappingTest` (1 test) — annotation presence round-trip.
- `@ParamTest` (1 test) — annotation presence round-trip.
- `AnnotationScannerTest` (8 tests) — pin every registration-time failure: happy path, non-public, void return, bad checked exception, duplicate command, missing `@Param`, duplicate `@Param`, no `@MethodMapping` (empty result).
- `BoundMethodTest` (3 tests) — record accessors, `paramBindings()` declaration order, value equality.

### Layer 2 — dispatcher unit tests (`AnnotatedDispatcherTest`)

8 tests:

1. Happy-path `dispatch` returns `Message(OK, [marshalledResult])` (using a stub `Marshaller`).
2. Unbound `Command` returns the "no handler" error.
3. Extra args in `Message.args` (longer list than the method's arity) are ignored.
4. Handler-thrown `MiddlewareException` is caught and the error `Message` is returned with the original `origin` preserved.
5. Handler-thrown unchecked `RuntimeException` is caught and the error `Message` includes the class name + message.
6. `MarshalException` from the marshaller is caught and a `Marshaller`-origin error is returned.
7. `hasHandler(Command.X)` returns `true` for bound commands, `false` for unbound.
8. Reflection `IllegalAccessException` (forced via a test-only `Method.setAccessible(false)` post-registration) returns an "internal" error.

Plus `AnnotatedDispatcherRegistrationTest` (4 tests):

1. `register(Object)` populates the map.
2. `register(Object)` re-registering the same object replaces the binding.
3. Two different objects bound to the same `Command` → second `register` throws.
4. `register(null)` throws `NullPointerException`.

### Layer 3 — end-to-end test (in `kvstore/src/test/java/com/victor/integration/`)

`AnnotatedDispatcherEndToEndTest` (1 test) — sibling to `Phase3EndToEndTest`. Spins up `MarshalledServer` (TCP) wrapped around `AnnotatedDispatcher` registered with a small `KVStore`-backed test fixture, sends a JSON envelope over a raw `Socket`, asserts the JSON response shape, sends a malformed envelope (missing arg), asserts the `ERRO:` response.

### What we are *not* testing

- Type coercion (only `String` params in v1; the spec explicitly defers this).
- Async return types.
- The `MarshalledServer.startTyped` overload is exercised by the e2e test, not in isolation.

### Demo verification (manual, not in test suite)

`mvn exec:java -Dexec.mainClass=com.victor.demo.AnnotatedWorkerDemo -Dexec.args="8001"`, then `curl`/`nc` JSON envelopes. Per `feedback-bash-background-jvm-sigterm.md`, manual smoke step, not Bash-driven.

## 7. Files touched / created

### New files

- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/MethodMapping.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/Param.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/AnnotatedHandler.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/AnnotatedDispatcher.java`
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/BoundMethod.java` (package-private)
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/ParamBinding.java` (package-private)
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/AnnotationScanner.java` (package-private)
- `middleware-victor/src/main/java/com/victor/middleware/invoker/annotated/package-info.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/MethodMappingTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/ParamTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/AnnotationScannerTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/BoundMethodTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/AnnotatedDispatcherTest.java`
- `middleware-victor/src/test/java/com/victor/middleware/invoker/annotated/AnnotatedDispatcherRegistrationTest.java`
- `kvstore/src/main/java/com/victor/demo/AnnotatedWorkerDemo.java`
- `kvstore/src/test/java/com/victor/integration/AnnotatedDispatcherEndToEndTest.java`

### Modified files

- `middleware-victor/src/main/java/com/victor/middleware/marshaller/MarshalledServer.java` — add `startTyped(int, TypedRequestHandler)` overload. **Does not change** the existing `start(int, RequestHandler)` path; the 35 legacy tests stay green.
- `docs/PDF-CHECKLIST.md` — flip "Modelo de Componentes" row from "Básico ✅, signature-preserving deferred" to "Básico ✅ + signature-preserving ✅". Update sumário estimate from ~7.5/10.0 to ~9.5/10.0.

### Untouched on purpose

- `Dispatcher.java` (Phase 1 design sealed — same precedent as Phase 5A).
- `kvstore/Gateway.java` and `kvstore/WorkerComponent.java` (production semantics unchanged; this phase adds an alternative, doesn't migrate).
- `MessageEnvelope.java` and `JsonMarshaller.java` (the wire envelope shape is unchanged; positional binding makes the existing array path sufficient).

## 8. Out of scope (deferred)

- **Type coercion** beyond `String` parameters. `@Param int count` would not compile-check today; we don't add coercion in v1.
- **Async return types** (`CompletableFuture<T>`).
- **Named binding** from the JSON object's keys (the v1 design uses positional binding; named binding requires a richer unmarshal return type and is deferred to a v2).
- **Migrating production `WorkerComponent`** to the annotation model. The new SPI is alongside; migration is a separate phase.
- **Lifecycle: Client-Dependent Instance + Passivation** — user cancelled 2026-06-23, not in scope.
- **JsonMarshaller hardening** (the "Task #73" debt) — out of band.

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Reflection errors at dispatch are confusing | Caught and translated to a wire-form `Message(UNKNOWN, ["ERRO: …"])`; pinned by `AnnotatedDispatcherTest` 4 and 8. |
| Handlers returning non-String types confuse the marshaller | Decision: handlers must return `String` (or a `Message`-friendly value). Return type `Object` is the API, but the dispatcher stringifies before passing to `marshaller.marshal(Message)`. This is called out in the demo's Javadoc and pinned in the happy-path test. |
| Wire envelope shape confusion (array vs. object) | `@Param` becomes a documentation annotation in v1. The demo class documents this explicitly. |
| `MarshalledServer.startTyped` introduces a second API surface | The legacy `start(int, RequestHandler)` is preserved. The new method is additive; no existing test is modified. |
