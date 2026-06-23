# PDF Checklist — `Descrição do Segundo Trabalho Prático`

Mapping each PDF requirement to the concrete file/line evidence in the repo.
Last refreshed: 2026-06-22, after Phase 5B (Marshaller Basic Remoting Pattern).

---

## Requisito 1 — Trabalho individual

No evidence in code; project metadata is the relevant signal.

- Repo is a single-tree Maven workspace (`kvstore/`, `middleware-victor/`) — no
  collaborator metadata suggesting multi-author work.

**Status**: out of scope for automated verification.

---

## Requisito 2 — Modelo de Componente por anotações + invocação HTTP

### Anotação como modelo de componente

The middleware exposes a component model where methods on a business class
become invokable remotely. The two annotation styles mentioned in the PDF
(JSONObject-based, and signature-preserving `@MethodMapping` + `@Param`) map
onto:

- **`invoker/Dispatcher.java`** — central dispatch on a `RequestHandler`,
  picks the registered handler for the wire command, threads arity checks.
  Pinning test: `DispatcherTest.java` (6/6 green).
- **`protocol/Command.java`** + **`Message.java`** — wire envelope that the
  request handler parses before dispatch.
- **`protocol/MessageParser.java`** — round-trip codec (13/13 tests green).

The more sophisticated model — signature-preserving with explicit `@Param`
mapping — is *not* yet implemented; the current contract is
`COMMAND|arg1|arg2|...` only. **Deferred to Phase 5** (per user "Phase 4 only"
direction). Phase 5 deliverable: a `Param` annotation + reflection-based
binding on the dispatcher.

### Invocação HTTP

- **`server/HttpServer.java`** — accepts `POST /COMMAND HTTP/1.1` over raw
  `ServerSocket`. Hand-rolled framing, no framework. Phase 1 port; Phase 2
  preserves behavior (the layering-violation in `resolveStatusCode` is
  pinned by `HttpServerTest.handlerPayloadWithGatewayDownMarkerMapsToHttp503`
  rather than fixed).
- **`client/HttpClient.java`** — sends `POST /COMMAND` and reads `Content-Length`
  body. 5/5 tests green against a real `ServerSocket` double.

**Status**: HTTP invocation ✅. Annotation model: basic ✅, signature-preserving
deferred (Phase 5).

---

## Requisito 3 — Padrões Remoting Patterns

### Broker → `kvstore/Gateway.java`

- **`kvstore/src/main/java/com/victor/Gateway.java`** — central dispatcher
  accepting client requests on a single port, round-robin across registered
  workers. Phase 3 refactor delegates registry state to
  `middleware-victor/registry/GatewayRegistry`.

### Basic Remoting Patterns

| Pattern | Evidence |
|---|---|
| Server Request Handler | `spi/RequestHandler` — `@FunctionalInterface String handle(String)`. |
| Invoker | `invoker/Dispatcher.java` — central dispatch table keyed by `Command`. 6 unit tests. |
| Marshaller | `marshaller/JsonMarshaller` — Jackson-backed codec producing the envelope `{"verb":"…","args":[…],"body":{}}`. Wired in as the decorator layer via `MarshalledServer` / `MarshalledClient`. Error path uses `MarshalException` (statusCode 400/500). The pipe codec in `MessageParser` stays in production as the Layer-1 transport codec; both codecs round-trip the same `Message(Command, args)` value type. | ✅ |
| Remote Object | `kvstore/business/KVStore.java` — the actual remote object, invoked through `WorkerComponent.getRequestHandler`. |
| Remoting Error | `exceptions/MiddlewareException` + `ConnectionException` + `ProtocolException` + `NoAvailableNodeException`. Each carries an `origin` tag (`"HTTP"` / `"TCP"` / `"UDP"` / `"PARSER"`) for caller diagnostics. |

### Identification Patterns

| Pattern | Evidence |
|---|---|
| Lookup | `registry/GatewayRegistry` — workers register/unregister by `(host, port)`. |
| Object Id | `protocol/Message.command` + `args` tuple — every request is keyed by an enum + arg list. |
| Absolute Object Reference | `util/ComponentInfo.java` — `(InetAddress, port, lastHeartbeat)` triple; `getId()` yields `"host:port"`. |

### Lifecycle Management (2 de 3)

| Pattern | Evidence |
|---|---|
| Static Instance | `WorkerComponent` holds a single `KVStore` for its lifetime — one instance per process. |
| Per-Request Instance | `HttpServer.handleClient` constructs a fresh request scope per accepted socket (handler runs once, returns, socket closes). |
| Client-Dependent Instance | **Not wired.** Each `HttpClient`/`TcpClient`/`UdpClient` is a transient one-shot — no per-client state preserved across calls. Phase 5 candidate. |

### Lifecycle Management (2 de 4)

| Pattern | Evidence |
|---|---|
| Lazy Acquisition | `TcpClient`/`HttpClient`/`UdpClient` open a fresh `Socket`/`DatagramSocket` per `send()` call and close on EOF — no pre-warmed connection pool. |
| Pooling | `HttpServer.THREAD_POOL_SIZE = 50`, `TcpServer.THREAD_POOL_SIZE = 500`, `UdpServer.THREAD_POOL_SIZE = 500` — `Executors.newFixedThreadPool` per server. Worker pool of executors. |
| Leasing | `util/ComponentInfo.lastHeartbeat` + `GatewayRegistry` heartbeat sweep — entries past the lease window are evicted. |
| Passivation | **Not implemented.** Workers are stateless request handlers; there's no object graph to passivate. Phase 5 candidate if we add complex object state. |

### Extension Patterns

| Pattern | Evidence | Notes |
|---|---|---|
| Invocation Interceptor | `invoker/InvocationInterceptor` (`@FunctionalInterface` `String around(InvocationContext, InvocationChain)`) + `invoker/InterceptingDispatcher` (wraps `Dispatcher` with an ordered chain) + built-in `invoker/LoggingInterceptor`. Chain runs in pre-order via `InvocationChainImpl`; aborts signal via `InvocationAbortedException` (checked). 27 dedicated tests across `InvocationChainTest` (9), `InterceptingDispatcherTest` (9), `LoggingInterceptorTest` (5), `InvocationContextTest` (4). | ✅ |
| Invocation Context | `invoker/InvocationContext` — immutable value type carrying `traceId` (UUID), `command`, `args`, `startNanos`, derived `elapsedNanos`. Built once per `InterceptingDispatcher.dispatch` via `InvocationContext.forMessage` and shared across every interceptor in the chain. | ✅ |
| Protocol Plug-In | `protocol/CommunicationType` enum (`TCP`, `HTTP`, `UDP`) + `factory/CommunicationFactory` swap implementations. **Two protocols supported**: HTTP and TCP (or HTTP and UDP, or TCP and UDP — pick any 2). Currently three are supported. | ✅ |

---

## Requisito 4 — Testes de Carga com JMeter

**Status: NOT DONE.** No `.jmx` files exist in the repo. Required for the
presentation day per the PDF ("todos os testes devem estar preparados e
configurados antecipadamente").

Suggested next phase (Phase 5+) — *not* part of Phase 4 per user direction:

1. Export a `HelloServer` demo from `middleware-victor` that exposes a single
   `kvstore` operation (e.g. `READ`) over HTTP. Entry point documented in
   `docs/HELLO-SERVER.md`.
2. Add a `jmeter/` directory at repo root with one `.jmx` plan:
   - Thread Group ramping 1 → 50 → 200 threads over 60s
   - HTTP Request sampler → `POST /WRITE HTTP/1.1` with a JSON body
   - Summary Report listener
3. Capture latency at 1/10/50/100/200/400 threads → Knee/Usable Capacity.

---

## Requisito 5 — Knee Capacity e Usable Capacity

**Status: NOT DONE.** Depends on Requisito 4 (need JMeter results first).

**Definition recap** (from "Art of Capacity Planning", cited by UFRN/IMD
literature):
- **Knee capacity**: latency starts climbing faster than throughput —
  the bend in the latency-vs-load curve.
- **Usable capacity**: max throughput at acceptable latency SLO
  (commonly 2× or 3× baseline latency).

Output target: a `docs/capacity.md` with two plots or CSV tables.

---

## Sumário — Pontuação estimada

| Critério | Peso | Implementação | Lacuna |
|---|---|---|---|
| Modelo de Componentes | 2.0 | Básico (Command enum + Message record) | Falta modelo com `@Param` / assinatura preservada |
| Basic Remoting Patterns | 2.0 | 5/5 ✅ (Server Request Handler, Invoker, Marshaller, Remote Object, Remoting Error) | — |
| Identification Patterns | 1.0 | 3/3 ✅ | — |
| Lifecycle Management | 2.0 | 4/8 (Static ✅, Per-Request ✅, Pooling ✅, Leasing ✅) | Client-Dependent Instance + Passivation pendentes |
| Extension Patterns | 3.0 | 3/3 (Invocation Interceptor ✅, Invocation Context ✅, Protocol Plug-In ✅) | — |
| Testes JMeter | req. #4 | Não iniciado | — |
| Knee/Usable Capacity | req. #5 | Não iniciado | depende de JMeter |

**Para nota máxima**: faltam Client-Dependent Instance, Passivation,
JMeter + capacity. Estimativa de cobertura atual: ~7.5 / 10.0 na rubrica
individual (Extension Patterns 3/3; Basic Remoting Patterns agora 5/5).

---

## Done (Phase 5A)

- **Invocation Interceptor** SPI + chain: `invoker/InvocationInterceptor`
  (`@FunctionalInterface`), `invoker/InvocationChain` + package-private
  `InvocationChainImpl` (pre-order recursion via `index`-cursor +
  `List.copyOf` snapshot), `invoker/InterceptingDispatcher` (wraps any
  `Dispatcher` while preserving the `String dispatch(Message)` contract).
- **Abort signal**: `exceptions/InvocationAbortedException` — checked, extends
  `MiddlewareException` with `origin="INVOKER"`; intercept signal abort by
  throwing it; `InterceptingDispatcher` translates to wire-form
  `"ERRO: <message>"` so the response is always a well-formed `Message` on
  the wire.
- **Built-in interceptor**: `invoker/LoggingInterceptor` prints paired IN/OUT
  lines keyed by `traceId` so log aggregators can correlate an IN with its
  OUT/ABORT.
- **Invocation Context**: `invoker/InvocationContext` — immutable value
  type carrying `traceId` (UUID), `command`, `args`, `startNanos`,
  derived `elapsedNanos`; shared across every interceptor in one chain.
- **Test coverage**: 27 dedicated tests across `InvocationChainTest` (9),
  `InterceptingDispatcherTest` (9), `LoggingInterceptorTest` (5),
  `InvocationContextTest` (4). Total `middleware-victor` suite now
  62/62 green; `kvstore` suite unchanged (1/1).
- **Documentation**: `invoker/package-info.java` extended with an
  "Interceptor chain (Phase 5A)" section that names the two contracts pinned
  by tests and points future readers at each new class.
- **Untouched on purpose**: `Dispatcher.java` (Phase 1 design is sealed —
  the wrapper adds the chain, doesn't reshape the dispatcher) and
  `kvstore/` (no domain change).

## Done (Phase 5B)

- **Marshaller SPI**: `spi/Marshaller` — protocol-agnostic `marshal(Message)` / `unmarshal(String)` contract.
- **JSON implementation**: `marshaller/JsonMarshaller` + `marshaller/MarshallerFactory.json()` singleton. Defensive: empty/null input → `Message(UNKNOWN, [])`; malformed JSON / missing verb → `MarshalException(400)`; encoder failures → `MarshalException(500)`.
- **Envelope DTO**: package-private `marshaller/MessageEnvelope` — Jackson-friendly `{verb, args, body}` record.
- **Exception**: `exceptions/MarshalException` — extends `MiddlewareException`, carries `int statusCode` (400/500), `origin = "MARSHALLER"`.
- **Typed SPI**: `spi/TypedRequestHandler` — `Message handle(Message) throws MiddlewareException`. Legacy `spi/RequestHandler` is now `@Deprecated` with a Javadoc pointer.
- **Server decorator**: `marshaller/MarshalledServer` — wraps any `ComponentServer`, JSON-unmarshals requests, JSON-marshals responses, catches `MarshalException` and writes `{"error":"…","code":N}` envelopes.
- **Client decorator**: `marshaller/MarshalledClient` — wraps any `ComponentClient`, JSON-marshals outgoing, JSON-unmarshals incoming; catches `MarshalException` on response and returns `Message(UNKNOWN, [err])`.
- **Dispatcher typed entry**: `invoker/Dispatcher.dispatchTyped(Message)` — typed counterpart to `dispatch(Message)` so the Marshaller path skips a parse/encode round trip.
- **HTTP seam**: `server/HttpServer.statusFrom(MiddlewareException)` — maps `MarshalException` to its `statusCode`; non-marshal exceptions return -1 (fallback to existing `resolveStatusCode`).
- **Test coverage**: 32 dedicated tests across `MarshalExceptionTest` (3), `MessageEnvelopeTest` (1), `MarshallerSpiTest` (1), `JsonMarshallerTest` (12), `MarshallerFactoryTest` (2), `TypedRequestHandlerTest` (4), `MarshalledServerTest` (5), `MarshalledClientTest` (4), `DispatcherTest` (+1 dispatchTyped), `HttpServerTest` (+2 statusFrom). Total `middleware-victor` suite now 97/97 green; `kvstore` suite now 2/2 green (original pipe-codec test + `marshallerDecoratorRoundTripsOnTcpTransport`).
- **Preservation invariant**: the 64 prior `middleware-victor` tests stay untouched (62 closed at end of Phase 5A + 2 `HttpServerTest.statusFrom` cases added in Phase 5B Task 11). The Marshaller decorator is exercised by its own dedicated test; production semantics of `MessageParser` and `Dispatcher` are unchanged.
- **Documentation**: `marshaller/package-info.java` describes the decorator architecture; `invoker/package-info.java` extended with a "Marshaller decorator (Phase 5B)" section.

## Done (Phase 4)

- JUnit 5 migration complete em ambos módulos (`middleware-victor`: 35/35,
  `kvstore`: 1/1).
- `package-info.java` per package (próximo item do plano).
