package com.victor.demo;

import java.util.HashMap;
import java.util.List;

import com.victor.business.KVStore;
import com.victor.business.VersionedValue;
import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.factory.CommunicationFactory;
import com.victor.middleware.invoker.annotated.AnnotatedDispatcher;
import com.victor.middleware.invoker.annotated.AnnotatedHandler;
import com.victor.middleware.invoker.annotated.MethodMapping;
import com.victor.middleware.invoker.annotated.Param;
import com.victor.middleware.marshaller.JsonMarshaller;
import com.victor.middleware.marshaller.MarshalledServer;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.CommunicationType;
import com.victor.middleware.spi.TypedRequestHandler;

/**
 * Demo entry point that wires the {@link AnnotatedDispatcher} +
 * {@link MarshalledServer#startTyped(int, TypedRequestHandler)} typed
 * SPI end-to-end. Stand-in for a future "annotation-only" worker that
 * drops the {@code getRequestHandler} boilerplate of
 * {@code WorkerComponent}.
 *
 * <p>Run with:</p>
 * <pre>
 *   mvn -pl kvstore exec:java \
 *        -Dexec.mainClass=com.victor.demo.AnnotatedWorkerDemo \
 *        -Dexec.args="8001 HTTP"
 * </pre>
 *
 * <p>Send a JSON envelope (the same shape the production gateway emits):</p>
 * <pre>
 *   curl -X POST http://localhost:8001/WRITE \
 *        -H 'Content-Type: application/json' \
 *        -d '{"verb":"WRITE","args":["alpha","beta"],"body":{}}'
 * </pre>
 * <p>Expected response:</p>
 * <pre>
 *   {"verb":"OK","args":["Written key: alpha, value: beta, version: 1"],"body":{}}
 * </pre>
 *
 * <p>This file is the missing link in Phase 5D: it proves that the
 * typed dispatcher chain (annotation scan → dispatchTyped →
 * startTyped → JSON envelope) composes without the gateway or the
 * {@code MessageParser} pipe codec.</p>
 */
public class AnnotatedWorkerDemo {

    /**
     * Annotation-only handler: methods declared with {@code @MethodMapping}
     * become remote-invocable. {@link AnnotatedHandler} is a documentary
     * marker (the dispatcher does not {@code instanceof}-check it).
     */
    public static final class AnnotationOnlyWorker implements AnnotatedHandler {

        private final KVStore store;

        public AnnotationOnlyWorker() {
            this.store = new KVStore(new HashMap<>());
        }

        /**
         * Wire-handler for {@link Command#WRITE}. The two {@link Param}
         * names are documentary in v1 — binding is positional from
         * {@code Message.args()}.
         */
        @MethodMapping(Command.WRITE)
        public String write(
                @Param("key") String key,
                @Param("value") String value) {
            return store.write(key, value);
        }

        /**
         * Wire-handler for {@link Command#READ}. Returns the value
         * portion (not the full {@link VersionedValue#toString()}) so
         * the demo response is short. Throws
         * {@link com.victor.middleware.exceptions.MiddlewareException}
         * for missing keys; the dispatcher wraps it as a typed
         * {@code Message(UNKNOWN, …)} response.
         */
        @MethodMapping(Command.READ)
        public String read(@Param("key") String key) throws MiddlewareException {
            VersionedValue v = store.read(key);
            return v.getValue();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("uso: AnnotatedWorkerDemo <port> <HTTP|TCP|UDP>");
            System.exit(2);
        }
        int port = Integer.parseInt(args[0]);
        CommunicationType type = CommunicationType.valueOf(args[1].toUpperCase());

        // 1) Build the annotation dispatcher and register the worker.
        AnnotatedDispatcher dispatcher = new AnnotatedDispatcher(new JsonMarshaller());
        AnnotationOnlyWorker worker = new AnnotationOnlyWorker();
        dispatcher.register(worker);

        // 2) Adapt it to TypedRequestHandler so the decorator can call it.
        TypedRequestHandler handler = dispatcher::dispatchTyped;

        // 3) Wrap a raw server in MarshalledServer and start in typed mode.
        MarshalledServer server = new MarshalledServer(
                CommunicationFactory.createServer(type),
                new JsonMarshaller());
        server.startTyped(port, handler);

        System.out.printf("[DEMO] AnnotatedWorkerDemo escutando em %s:%d%n", type, port);
        System.out.println("[DEMO] Comandos registrados: " + List.of(Command.WRITE, Command.READ));
        System.out.println("[DEMO] Body esperado: {\"verb\":\"WRITE\",\"args\":[\"k\",\"v\"],\"body\":{}}");
    }
}