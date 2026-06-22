package com.victor.middleware.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable representation of a parsed request: a {@link Command} plus a
 * list of arguments. Phase 1 keeps the wire format as a {@code String} for
 * backwards compatibility with the existing
 * {@link com.victor.middleware.spi.RequestHandler} SPI — {@code Message} is
 * a parallel convenience used by the codec and (in Phase 4) by tests.
 *
 * <p>{@code Message} is a {@code record}, so it is immutable and gets
 * {@code equals}/{@code hashCode}/{@code toString} for free.</p>
 */
public record Message(Command command, List<String> args) {

    /** Compact constructor that defensively copies the args list. */
    public Message {
        args = args == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(args));
    }

    /** Convenience factory mirroring the wire form {@code "COMMAND|arg1|arg2"}. */
    public static Message of(String command, String... args) {
        Command cmd = Command.fromString(command);
        List<String> list = new ArrayList<>();
        for (String a : args) {
            if (a != null) {
                list.add(a);
            }
        }
        return new Message(cmd, list);
    }

    /** @return the first argument, or {@code null} if absent. */
    public String firstArg() {
        return args.isEmpty() ? null : args.get(0);
    }

    /** @return the second argument, or {@code null} if absent. */
    public String secondArg() {
        return args.size() < 2 ? null : args.get(1);
    }

    /**
     * Serialize back to wire form: {@code "COMMAND|arg1|arg2"}. Empty arg
     * list yields {@code "COMMAND"}.
     */
    public String toWireForm() {
        StringBuilder sb = new StringBuilder();
        sb.append(command.wireForm());
        for (String a : args) {
            sb.append('|').append(a);
        }
        return sb.toString();
    }
}