package com.victor.middleware.invoker.annotated;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.victor.middleware.exceptions.MarshalException;
import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;
import com.victor.middleware.spi.Marshaller;

/**
 * Annotation-driven dispatcher: routes incoming {@link Message} values
 * to {@link MethodMapping}-annotated methods on registered handler
 * objects. Returns a typed {@link Message} (the same shape as
 * {@code invoker.Dispatcher.dispatchTyped}).
 *
 * <p>The {@link Marshaller} is injected so handler return values can be
 * encoded into a response {@code Message} without the handler doing
 * any envelope construction.</p>
 *
 * <p>Thread-safety: backed by an {@link EnumMap}, not synchronized. Same
 * contract as the legacy {@code invoker.Dispatcher}: callers are
 * expected to finish registration before exposing the dispatcher to
 * the request loop.</p>
 */
public final class AnnotatedDispatcher {

    private final Map<Command, BoundMethod> bindings = new EnumMap<>(Command.class);

    /**
     * Used on the success path to confirm the response {@link Message}
     * can be encoded to the wire form. The marshaller is held per spec
     * §2 ("Marshaller is injected into AnnotatedDispatcher") and §5
     * ("Marshaller throws MarshalException serializing the return
     * value" surfaces as a {@code "MARSHALLER"}-origin error).
     */
    private final Marshaller marshaller;

    /** Wire up a dispatcher with a {@link Marshaller} for response encoding. */
    public AnnotatedDispatcher(Marshaller marshaller) {
        this.marshaller = marshaller;
    }

    /**
     * Reflect the handler's {@code @MethodMapping} methods into the
     * dispatch table. Throws on validation failure — see
     * {@link AnnotationScanner#scan(Object)}.
     *
     * <p>If a {@link Command} is already bound to a method on a
     * <em>different</em> handler instance, this method throws. If the
     * same handler is re-registered, the binding is replaced silently
     * (mirrors {@code invoker.Dispatcher.register} overwriting).</p>
     */
    public void register(Object handler) {
        Map<Command, BoundMethod> scanned = AnnotationScanner.scan(handler);
        for (Map.Entry<Command, BoundMethod> e : scanned.entrySet()) {
            BoundMethod existing = bindings.get(e.getKey());
            if (existing != null && existing.target() != handler) {
                throw new IllegalStateException(
                        "command " + e.getKey() + " already bound on a different handler: "
                                + existing.target().getClass().getName());
            }
            bindings.put(e.getKey(), e.getValue());
        }
    }

    /** @return whether a binding exists for the given {@link Command}. */
    public boolean hasHandler(Command command) {
        return bindings.containsKey(command);
    }

    /**
     * Route the message to its bound method and return the response.
     * Never throws — see spec §5 dispatch-time failures.
     *
     * <p>Implementation: positional binding from {@code Message.args()}
     * (see spec §3 "Wire envelope — positional binding for v1"). The
     * return value is stringified via {@code String.valueOf} and
     * wrapped in {@code Message(OK, [stringified])}.</p>
     */
    public Message dispatchTyped(Message msg) {
        if (msg == null || msg.command() == Command.UNKNOWN) {
            return errorMessage("INVOKER", "comando desconhecido");
        }
        BoundMethod bound = bindings.get(msg.command());
        if (bound == null) {
            return errorMessage("INVOKER",
                    "no handler for command " + msg.command().wireForm());
        }
        try {
            Object[] args = new Object[bound.paramBindings().size()];
            List<String> wireArgs = msg.args();
            for (int i = 0; i < args.length; i++) {
                int srcIndex = bound.paramBindings().get(i).parameterIndex();
                args[i] = (srcIndex < wireArgs.size()) ? wireArgs.get(srcIndex) : null;
            }
            Object result = bound.method().invoke(bound.target(), args);
            String stringified = String.valueOf(result);
            Message response = new Message(Command.OK, List.of(stringified));
            try {
                // Validate the response can be encoded to the wire form.
                // The typed return type still wins — this is a side-effecting
                // canary that surfaces a MARSHALLER-origin error early.
                marshaller.marshal(response);
                return response;
            } catch (MarshalException me) {
                return errorMessage("MARSHALLER", me.getMessage());
            }
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof com.victor.middleware.exceptions.MiddlewareException me) {
                return new Message(Command.UNKNOWN, List.of("ERRO: " + me.getMessage()));
            }
            String className = cause == null ? "Throwable" : cause.getClass().getSimpleName();
            String message = cause == null ? "no cause" : cause.getMessage();
            return errorMessage("INVOKER", className + ": " + message);
        } catch (IllegalAccessException e) {
            // Defensive: Method.invoke's only checked exception after
            // getMethods() succeeded. Pinned by the spec §5 row that
            // calls this "impossible post-registration".
            return errorMessage("INVOKER", "dispatcher internal: " + e.getMessage());
        }
    }

    private static Message errorMessage(String origin, String detail) {
        return new Message(Command.UNKNOWN, List.of("ERRO: " + detail));
    }
}
