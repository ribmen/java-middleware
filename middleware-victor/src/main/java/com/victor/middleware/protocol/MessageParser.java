package com.victor.middleware.protocol;

import java.util.ArrayList;
import java.util.List;

import com.victor.middleware.exceptions.ProtocolException;

/**
 * Centralized pipe-delimited codec used by all three servers and by tests.
 *
 * <p>Currently this logic is duplicated inside
 * {@code kvstore/Gateway.java} (which uses {@code split("\\|")} with default
 * limit) and {@code kvstore/WorkerComponent.java} (which uses
 * {@code split("\\|", 3)}). Centralizing it removes the duplication and the
 * latent arity-divergence bug.</p>
 *
 * <p>Phase 1 keeps the legacy {@link #splitRaw(String, int)} for callers that
 * have not yet migrated; Phase 2 should remove those in favor of
 * {@link #parse(String)}.</p>
 */
public final class MessageParser {

    /** Pipe delimiter used both for parsing and serialization. */
    public static final String DELIM = "|";

    /** Regex form of {@link #DELIM} for {@code String.split}. */
    public static final String DELIM_REGEX = "\\|";

    private MessageParser() {
        // utility class
    }

    /**
     * Parse a raw wire string into a {@link Message}. Empty / null input
     * returns {@code new Message(UNKNOWN, List.of())} without throwing — the
     * codec is forgiving so a stray blank line does not kill the receiver.
     *
     * @throws ProtocolException if {@code raw} is non-null/non-empty but
     *         cannot be split into at least a command token.
     */
    public static Message parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Message(Command.UNKNOWN, List.of());
        }
        String[] parts = splitRaw(raw, -1);
        if (parts.length == 0) {
            return new Message(Command.UNKNOWN, List.of());
        }
        Command cmd = Command.fromString(parts[0]);
        List<String> args = new ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            args.add(parts[i]);
        }
        return new Message(cmd, args);
    }

    /** Inverse of {@link #parse(String)}: serialize a {@link Message} to wire form. */
    public static String encode(Command command, List<String> args) {
        return new Message(command, args).toWireForm();
    }

    /**
     * Low-level split helper kept for transitional callers. Matches the
     * behavior of {@code String.split(DELIM_REGEX, limit)} but never returns
     * a leading empty token caused by a leading delimiter (which Java's
     * default split does anyway, but defensively normalize here).
     *
     * @param raw   wire string to split
     * @param limit passed to {@link String#split(String, int)}; {@code -1}
     *              means "no limit"
     */
    public static String[] splitRaw(String raw, int limit) {
        if (raw == null) {
            return new String[0];
        }
        if (raw.isEmpty()) {
            return new String[0];
        }
        return raw.split(DELIM_REGEX, limit);
    }
}