package com.victor.middleware.protocol;

/**
 * Names the wire-level commands the kvstore uses today. Typing the command
 * vocabulary protects the codec against typos that previously only surfaced
 * at runtime.
 *
 * <p>The protocol is intentionally flat: a single uppercase verb followed by
 * pipe-delimited arguments. The list below mirrors exactly what
 * {@code kvstore/Gateway.java} and {@code kvstore/WorkerComponent.java} send
 * over the wire.</p>
 */
public enum Command {
    WRITE,
    READ,
    REGISTER,
    HEARTBEAT,
    UNKNOWN;

    /**
     * Parse a wire command (case-insensitive). Unknown values map to
     * {@link #UNKNOWN} rather than throwing — the parser is forgiving so that
     * one unknown command does not crash the receiver.
     */
    public static Command fromString(String s) {
        if (s == null) {
            return UNKNOWN;
        }
        try {
            return Command.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    /** @return the canonical uppercase wire form of this command. */
    public String wireForm() {
        return name();
    }
}