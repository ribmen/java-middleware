package com.victor.middleware.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable representation of a request: a {@link Command} plus a list of
 * arguments. {@code Message} is the typed value type that flows through the
 * dispatcher, the marshaller, and every server/client boundary — it is the
 * "in-memory" form of a request, never a serialized wire form.
 *
 * <p>Serialization is delegated to {@code marshaller.JsonMarshaller}: the
 * codec that turns a {@code Message} into the JSON envelope on the wire is
 * the only codec the system uses. There is no {@code toWireForm} on
 * {@code Message} because every cross-JVM boundary already speaks JSON.</p>
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

    /** @return the first argument, or {@code null} if absent. */
    public String firstArg() {
        return args.isEmpty() ? null : args.get(0);
    }

    /** @return the second argument, or {@code null} if absent. */
    public String secondArg() {
        return args.size() < 2 ? null : args.get(1);
    }
}
