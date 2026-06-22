package com.victor.middleware.invoker;

import java.util.EnumMap;
import java.util.function.Function;

import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

/**
 * Minimal in-process dispatcher: maps each {@link Command} to a handler
 * function, then routes {@link Message}s by command. This is the seed of
 * the future annotation-driven invoker — today the map is passed in by
 * hand; tomorrow the same class can back handlers discovered via
 * {@code @Handler(WRITE)} reflection.
 *
 * <p>Contract:</p>
 * <ul>
 *     <li>Unknown / unregistered commands return a wire-form error string
 *         (the dispatcher speaks the same protocol as the gateway, so
 *         errors flow back to the client unchanged).</li>
 *     <li>Handler exceptions are caught and converted to the same
 *         wire-form error shape — a thrown {@code RuntimeException} never
 *         becomes a silent connection drop.</li>
 *     <li>Re-registering a command overwrites the prior handler.</li>
 * </ul>
 *
 * <p>Thread-safety: backed by {@link EnumMap}, not synchronized. Callers
 * are expected to finish registration before exposing the dispatcher to
 * the request loop. This matches how request handlers are wired in the
 * gateway and workers today (all done in {@code start()} before
 * {@code server.start(...)}).</p>
 */
public class Dispatcher {

    private final EnumMap<Command, Function<Message, String>> handlers = new EnumMap<>(Command.class);

    /** Bind a handler to a command. Overwrites any previously-registered handler. */
    public void register(Command command, Function<Message, String> handler) {
        handlers.put(command, handler);
    }

    /** @return whether a handler is registered for the given command. */
    public boolean hasHandler(Command command) {
        return handlers.containsKey(command);
    }

    /**
     * Route a message to its handler. On any failure (missing handler,
     * {@code UNKNOWN} command, handler exception) returns a wire-form
     * error string starting with {@code "ERRO:"}.
     */
    public String dispatch(Message msg) {
        if (msg == null || msg.command() == Command.UNKNOWN) {
            return "ERRO: Comando desconhecido.";
        }
        Function<Message, String> handler = handlers.get(msg.command());
        if (handler == null) {
            return "ERRO: Sem handler para " + msg.command().wireForm() + ".";
        }
        try {
            return handler.apply(msg);
        } catch (RuntimeException e) {
            return "ERRO: Handler lançou " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}