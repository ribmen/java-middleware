package com.victor.middleware.invoker;

import java.util.EnumMap;
import java.util.function.Function;

import com.victor.middleware.protocol.Command;
import com.victor.middleware.protocol.Message;

/**
 * Minimal in-process dispatcher: maps each {@link Command} to a handler
 * function, then routes {@link Message}s by command.
 *
 * <p>The dispatcher speaks only the typed contract: every handler is
 * {@code Function<Message, Message>} and the dispatcher returns
 * {@link Message} directly. Serialization to/from the wire is the
 * responsibility of the {@code Marshaller} / {@code MarshalledServer}
 * decorator layers — there is no string round-trip inside the dispatcher
 * any more.</p>
 *
 * <p>Contract:</p>
 * <ul>
 *     <li>Unknown / unregistered commands return a typed error
 *         {@code Message(UNKNOWN, ["ERRO: ..."])} so callers can react
 *         uniformly without parsing wire form.</li>
 *     <li>Handler exceptions are caught and converted to the same
 *         {@code UNKNOWN}-command error shape — a thrown
 *         {@code RuntimeException} never becomes a silent connection
 *         drop.</li>
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

    private final EnumMap<Command, Function<Message, Message>> handlers = new EnumMap<>(Command.class);

    /** Bind a handler to a command. Overwrites any previously-registered handler. */
    public void register(Command command, Function<Message, Message> handler) {
        handlers.put(command, handler);
    }

    /** @return whether a handler is registered for the given command. */
    public boolean hasHandler(Command command) {
        return handlers.containsKey(command);
    }

    /**
     * Route a typed message to its handler and return the typed response.
     * On any failure (missing handler, {@code UNKNOWN} command, handler
     * exception) returns a {@link Message} with command {@code UNKNOWN}
     * and a single arg starting with {@code "ERRO:"}.
     */
    public Message dispatch(Message msg) {
        if (msg == null || msg.command() == Command.UNKNOWN) {
            return errorMessage("Comando desconhecido.");
        }
        Function<Message, Message> handler = handlers.get(msg.command());
        if (handler == null) {
            return errorMessage("Sem handler para " + msg.command().wireForm() + ".");
        }
        try {
            Message response = handler.apply(msg);
            return response == null
                    ? errorMessage("Handler retornou null.")
                    : response;
        } catch (RuntimeException e) {
            return errorMessage("Handler lançou "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * Alias for {@link #dispatch(Message)} kept for source compatibility
     * with the original typed entry point pinned by
     * {@code DispatcherTest#dispatchTyped}.
     */
    public Message dispatchTyped(Message msg) {
        return dispatch(msg);
    }

    private static Message errorMessage(String detail) {
        return new Message(Command.UNKNOWN, java.util.List.of("ERRO: " + detail));
    }
}
