# Phase 5B — Marshaller Design Spec

**Date**: 2026-06-22
**Author**: brainstormed with the user
**Status**: design approved; pending implementation plan
**Predecessor**: Phase 5A — Invocation Interceptor (closed, 27 tests, 9 commits on main)
**Module**: `middleware-victor/` (new package `com.victor.middleware.marshaller`)
**Pattern reference**: `Middleware/middleware/src/main/java/br/imd/ufrn/Marshaller.java` (UFRN/IMD reference project)

---

## 1. Goal

Implement the **Marshaller** Basic Remoting Pattern (POSA2) for the
`middleware-victor` module. Convert in-memory `Message` objects to/from a
JSON wire format, wired in as a decorator layer that sits between the raw
transport (`HttpServer`/`TcpServer`/`UdpServer` and their clients) and the
typed application handler.

**Why this is the highest-value remaining gap**: per
`docs/PDF-CHECKLIST.md` §Sumário, the Marshaller is the largest single
unfilled Basic Remoting Pattern cell (2.0 rubric weight). Adding it moves
the estimated score from ~6.5/10.0 to ~7.5/10.0.

**Why now, after Phase 5A**: Phase 5A established the decorator composition
shape (`InterceptingDispatcher` wraps `Dispatcher`). The Marshaller uses the
same shape (`MarshalledServer` wraps `ComponentServer`). Building on a
pattern we already have keeps the architecture coherent.

---

## 2. Architecture

Three layers, composed by decoration:

```
┌──────────────────────────────────────────────────────────────┐
│  Layer 3: Application  (kvstore/.../Gateway, WorkerComponent)│
│  — depends only on Message + TypedRequestHandler             │
├──────────────────────────────────────────────────────────────┤
│  Layer 2: Marshaller decorator  (MarshalledServer/Client)    │
│  — speaks JSON envelope on the outside, Message inside       │
│  — wraps a raw ComponentServer/Client                        │
├──────────────────────────────────────────────────────────────┤
│  Layer 1: Raw transport  (HttpServer/Client, TcpServer/Client)│
│  — unchanged: byte stream, pipe codec, or HTTP framing       │
└──────────────────────────────────────────────────────────────┘
```

**Composition with Phase 5A** is a stack: `MarshalledServer` →
`Raw ComponentServer` (e.g. `HttpServer`) → handler table keyed by
`TypedRequestHandler`. The `InterceptingDispatcher` (when present) wraps the
handler table. Order: **JSON → dispatch table → interceptors → handler body**
on the way in; reverse on the way out.

**Two codecs, one `Message`**:
- `MessageParser` (pipe-delimited) — Layer-1 transport codec. Stays in
  production. The 13 `MessageParserTest` cases continue to pin it.
- `JsonMarshaller` (JSON envelope) — Layer-2 decorator codec. New.

Both codecs produce/consume the same `Message(Command, args)` value type.
The verb set is identical; only the on-wire bytes differ.

**Critical preservation invariant**: the 63 existing tests (62 in
`middleware-victor` — including the 27 Phase 5A interceptor tests — and 1
in `kvstore`'s `Phase3EndToEndTest`) must remain green without
modification. They live at Layer 1 and never see the Marshaller. The
Marshaller is a *new* decorator wired in only by callers that opt in
(e.g. `HelloServer` demo, JMeter plans, the public gateway/worker
boundary in `kvstore/`).

---

## 3. Components

Eight new classes plus two minimal modifications:

### 3.1 New classes

| Class | Path | Purpose |
|---|---|---|
| `Marshaller` (interface) | `spi/Marshaller.java` | SPI: `String marshal(Message)`, `Message unmarshal(String)`. Protocol-agnostic contract. |
| `JsonMarshaller` | `marshaller/JsonMarshaller.java` | Jackson-backed impl of `Marshaller`. Uses private `MessageEnvelope` DTO. |
| `MessageEnvelope` | `marshaller/MessageEnvelope.java` | Package-private Jackson DTO `{verb, args, body}`. Round-trip record. |
| `MarshalException` | `exceptions/MarshalException.java` | Checked, extends `MiddlewareException`, carries `int statusCode` (400 / 500). `origin = "MARSHALLER"`. |
| `TypedRequestHandler` | `spi/TypedRequestHandler.java` | New SPI: `Message handle(Message request) throws MiddlewareException`. |
| `MarshalledServer` | `marshaller/MarshalledServer.java` | Decorator: `public MarshalledServer(ComponentServer inner, Marshaller m) implements ComponentServer`. `start(port, handler)` delegates to `inner.start(port, jsonHandler)` where `jsonHandler` is a lambda that wraps the user's `RequestHandler` to JSON-unmarshal-then-handle-then-marshal. |
| `MarshalledClient` | `marshaller/MarshalledClient.java` | Decorator: `public MarshalledClient(ComponentClient inner, Marshaller m) implements ComponentClient`. `send(host, port, request)` JSON-marshals `request` (as `Message`), delegates to `inner.send`, JSON-unmarshals the response. |
| `MarshallerFactory` | `marshaller/MarshallerFactory.java` | Single `static Marshaller json()` accessor — same shape as `CommunicationFactory`. |

### 3.2 Modified (minimal)

- `RequestHandler` → add `@Deprecated` Javadoc pointing at
  `TypedRequestHandler`. **Signature unchanged.** The 35 tests that depend
  on it stay untouched.
- `Dispatcher` → add overload `Message dispatchTyped(Message)` that
  delegates to the existing handler table. The existing
  `String dispatch(String)` stays for the 35 tests.
- `HttpServer` → add `int statusFrom(MiddlewareException)` that returns
  `e.statusCode()` if it's a `MarshalException`, else falls back to the
  existing string-inspection logic. Existing 5 tests stay green.
- `pom.xml` (middleware-victor) → add Jackson 2.17.x dependency.

### 3.3 Untouched

- All `server/*`, `client/*` — raw transports don't change.
- `protocol/MessageParser.java` — pipe codec stays in production.
- `protocol/Message.java`, `protocol/Command.java` — value types unchanged.
- `invoker/*` — interceptor chain composes with the new layer, doesn't
  move.
- `kvstore/`'s `KVStore`, `VersionedValue` — domain doesn't change.
  (`Gateway`, `BaseComponent`, `WorkerComponent` get minimal JSON-flavored
  updates per §5 below.)

---

## 4. Data Flow

### 4.1 Happy path — WRITE over HTTP

```
Client                               MarshalledServer       HttpServer         TypedHandler
  │                                       │                   │                    │
  │  POST /WRITE  body={"verb":"WRITE",   │                   │                    │
  │       "args":["k","v"],"body":{}}     │                   │                    │
  │ ─────────────────────────────────────►│                   │                    │
  │                                       │  raw String       │                    │
  │                                       │ ─────────────────►│                    │
  │                                       │                   │  parsed String     │
  │                                       │                   │ ──────────────────►│
  │                                       │                   │                    │ dispatch:
  │                                       │                   │                    │ WRITE handler
  │                                       │                   │  response String   │
  │                                       │                   │ ◄──────────────────│ "OK|k|v"
  │                                       │  unmarshal ◄─────│                    │
  │                                       │  → Message(OK, [k,v])                  │
  │                                       │  marshal → {"verb":"OK","args":["k","v"],"body":{}}
  │ ◄─────────────────────────────────────│                   │                    │
  │  HTTP/1.1 200  body={"verb":"OK",…}   │                   │                    │
```

### 4.2 Error path — malformed JSON body

```
Client                                 MarshalledServer
  │  POST /WRITE  body={not valid json
  │ ──────────────────────────────────────►│
  │                                        │ JsonMarshaller.unmarshal throws
  │                                        │   JsonProcessingException
  │                                        │ MarshalException wraps it, status=400
  │                                        │ catch → marshal error envelope:
  │                                        │   {"error":"malformed JSON","code":400}
  │ ◄──────────────────────────────────────│
  │  HTTP/1.1 400  body={"error":…,…}      │
```

### 4.3 Concrete byte shapes

| Direction | Bytes |
|---|---|
| Request | `{"verb":"WRITE","args":["smoke-key","smoke-value"],"body":{}}` |
| OK response | `{"verb":"OK","args":["smoke-key","smoke-value"],"body":{}}` |
| Error response | `{"error":"malformed JSON: Unexpected token","code":400}` |
| Unknown verb | `{"verb":"FOOBAR","args":["x"],"body":{}}` → handler returns `Message(UNKNOWN, [x])` → `{"verb":"UNKNOWN","args":["x"],"body":{}}` |

The `body` field is `{}` for now — it's there for the future `@Body`-annotated
parameters (mirrors `Middleware/Marshaller`), so we don't pay for it later.

---

## 5. Error Handling

Three error sites, three translation rules:

**1. `JsonMarshaller.unmarshal` fails (malformed JSON, wrong field types)**:
- Throws `MarshalException("malformed JSON: <detail>", 400, cause)` —
  checked, extends `MiddlewareException`, `origin = "MARSHALLER"`.
- `MarshalledServer` catches in its request-loop, marshals an error
  envelope `{"error": msg, "code": 400}`, writes that to the wire.
- `MarshalledClient.send()` catches, marshals the same error envelope on
  the *return* path — so callers see `Message(UNKNOWN, [errorMsg])` rather
  than an exception. Same shape as how `Dispatcher` returns wire-form
  errors instead of throwing.

**2. `JsonMarshaller.marshal` fails (handler returned an unserializable object)**:
- Throws `MarshalException("failed to serialize response: <detail>", 500, cause)`.
- `MarshalledServer` catches, writes `{"error": msg, "code": 500}`.
- `MarshalledClient.send()` catches, returns `Message(UNKNOWN, [errorMsg])`.

**3. Handler throws `MiddlewareException` (e.g. `ConnectionException`, `ProtocolException`)**:
- Passes through `MarshalledServer` untouched — not the Marshaller's job
  to translate business exceptions.
- The interceptor chain (Phase 5A) catches it at the dispatch layer and
  translates to `InvocationAbortedException` → wire-form `ERRO: ...`.
- `MarshalledClient.send()` only catches `MarshalException`; business
  exceptions propagate to the caller, preserving existing semantics.

### 5.1 Status code mapping (HTTP-only)

| `MarshalException` status | HTTP response code |
|---|---|
| 400 (malformed) | `400 Bad Request` |
| 500 (encoder error) | `500 Internal Server Error` |
| (any other thrown `MiddlewareException`) | existing `HttpServer.resolveStatusCode` rules |

### 5.2 Defensive invariants (pinned by tests)

- `Marshaller.unmarshal(null)` → `Message(UNKNOWN, [])` — same forgiveness
  as `MessageParser.parse`.
- `Marshaller.unmarshal("")` → `Message(UNKNOWN, [])`.
- `Marshaller.unmarshal(valid JSON, missing 'verb')` → `MarshalException("missing verb", 400)`.
- `Marshaller.unmarshal(valid JSON, verb=42 (non-string))` → `MarshalException("verb must be a string", 400)`.

---

## 6. Testing

### 6.1 Preserved (untouched)

- All 35 `middleware-victor` tests that don't touch JSON or the new SPI
- All 13 `MessageParserTest` cases (pipe codec stays in production for the
  Layer-1 raw transport)
- The 1 `kvstore/AppTest`
- The Phase 5A interceptor chain (27 tests across 4 files)
- The `InterceptingDispatcher` wrapper tests (9)

### 6.2 New test files

| File | Cases | What it pins |
|---|---|---|
| `JsonMarshallerTest` | ~12 | Round-trip `WRITE`/`READ`/`REGISTER`/`HEARTBEAT`/`UNKNOWN`; null/empty/malformed JSON; missing `verb` field; non-string verb; `body` as `Map<String,Object>` round-trip; non-serializable body throws `MarshalException` 500; `MarshalException` carries `statusCode=400` for malformed, `500` for encoder. |
| `TypedRequestHandlerTest` | ~4 | New SPI compiles; deprecated `RequestHandler` still works alongside; a `TypedRequestHandler` can be adapted to `RequestHandler` and vice versa (lambda bridge). |
| `MarshalledServerTest` | ~5 | End-to-end through `MarshalledServer` wrapping a real `LocalServerSocket`-based test double: request JSON → handler called with `Message` → response JSON written. Malformed request → error envelope. Handler exception → propagates unchanged. |
| `MarshalledClientTest` | ~4 | Round-trip through `MarshalledClient` wrapping `HttpClient`: outgoing JSON shape, incoming `Message` parse, malformed response returns `Message(UNKNOWN, [err])`. |

### 6.3 Modified tests

| File | Change |
|---|---|
| `MessageParserTest` | None — pipe codec still in production. |
| `DispatcherTest` | Add 1 case: `dispatchTyped(Message)` returns same as `dispatch(msg.toWireForm())` for the same handler. |
| `Phase3EndToEndTest` | Swap `"WRITE|smoke-key|smoke-value"` → `{"verb":"WRITE","args":["smoke-key","smoke-value"],"body":{}}`. Same expected response shape, JSON-flavored. |
| `HttpServerTest` | Add 2 cases: malformed JSON body → 400 with error envelope; `MarshalException(status=500)` from handler → 500 with error envelope. The existing 5 stay green. |

### 6.4 JMeter plans (`jmeter-tests/`)

- `tcp_write_test.jmx` → renamed `http_write_test.jmx`; body swapped to
  `{"verb":"WRITE","args":["Chave1","Value1"],"body":{}}`. CSV unchanged.
- `http_read_test.jmx` → same JSON swap.
- Optional new `tcp_pipe_legacy_test.jmx` to exercise the pipe codec
  end-to-end via JMeter (proves both codecs work on the same raw
  transport).

### 6.5 Total test count after Phase 5B

~63 + 25 new = **~88 tests green**, blast radius from the wire-format
change = **2 modified files** (`Phase3EndToEndTest`, the `.jmx` plans).

---

## 7. Verification Gate

Per project convention (`feedback-mandatory-verification-subagent.md`),
after implementation a `verification` subagent is dispatched with:
- Original user request: "implement Marshaller"
- All files changed (8 new + 4 modified)
- This design spec
- The ~87-test target

The verifier runs `mvn test` on both modules and confirms exact count.
The controller (this session) does not self-assign the verdict.

---

## 8. Documentation

- `docs/PDF-CHECKLIST.md` — Extension Patterns row in §3 flipped to ✅ for
  Marshaller; §Sumário bumped from `~6.5/10.0` to `~7.5/10.0`.
- `invoker/package-info.java` extended with a "Marshaller decorator
  (Phase 5B)" section paralleling the Phase 5A interceptor section.
- New `marshaller/package-info.java` covering the 8 new classes.
- New `docs/HELLO-SERVER.md` (deferred from Phase 4 checklist) wired to
  demonstrate `MarshalledServer` + `MarshalledClient` over HTTP for the
  JMeter plans.

---

## 9. Out of scope

- `@Body`-annotated handler parameters (the `body` field is reserved for
  it but never read yet).
- Marshaller plug-in SPI for non-JSON codecs (XML, protobuf). The
  `Marshaller` interface is open; only `JsonMarshaller` is implemented.
  Future codecs can plug in via `MarshallerFactory`.
- Replacing the pipe codec in `MessageParser`. The two codecs coexist.
- Removing or renaming `RequestHandler`. It stays, marked `@Deprecated`,
  for backward compatibility with the 35 existing tests.

---

## 10. Open questions

None at design time. All six clarifying questions answered by the user
during brainstorming:

1. Wire format → JSON
2. JSON library → Jackson
3. `RequestHandler` evolution → add `TypedRequestHandler`, deprecate old
4. Envelope shape → mirror reference `{verb, args, body}`
5. JMeter plans → update to JSON now
6. Composition approach → A (decorators)

---

## 11. Implementation order (preview, for the plan)

1. `pom.xml` — add Jackson dependency.
2. `exceptions/MarshalException.java` — base of the new error path.
3. `marshaller/MessageEnvelope.java` — Jackson DTO (no behavior).
4. `marshaller/JsonMarshaller.java` — first impl of `Marshaller`.
5. `spi/Marshaller.java` — SPI (after the impl so the shape is known).
6. `marshaller/MarshallerFactory.java` — single accessor.
7. `spi/TypedRequestHandler.java` — new SPI.
8. `invoker/Dispatcher.java` — add `dispatchTyped` overload.
9. `marshaller/MarshalledServer.java` — server decorator.
10. `marshaller/MarshalledClient.java` — client decorator.
11. `server/HttpServer.java` — add `statusFrom` helper.
12. `spi/RequestHandler.java` — `@Deprecated` annotation only.
13. `kvstore/.../Gateway.java`, `WorkerComponent.java`, `BaseComponent.java`
    — minimal JSON-flavored updates.
14. Test files (per §6).
15. JMeter `.jmx` updates.
16. Docs updates per §8.
17. Verification subagent dispatch.
