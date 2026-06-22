package com.victor.middleware.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Pins the {@link MessageParser} contract. The parser is the single
 * source of truth for the pipe-delimited wire format; every server
 * and client funnels through it (directly or via
 * {@link Message#toWireForm()}).
 *
 * <p>What we lock down here:</p>
 * <ul>
 *     <li>Empty / null input is forgiving — never throws, yields
 *         {@code Message(UNKNOWN, [])}.</li>
 *     <li>Command parsing is case-insensitive and forgiving of typos.</li>
 *     <li>{@link #encode} is the exact inverse of {@link #parse} for any
 *         non-empty arg list (round-trip equality).</li>
 *     <li>Arity is preserved — no silent truncation, no silent drop.</li>
 * </ul>
 */
class MessageParserTest {

    /** Null in → UNKNOWN with no args, never a NPE. */
    @Test
    void parseNullReturnsUnknownWithEmptyArgs() {
        Message m = MessageParser.parse(null);
        assertSame(Command.UNKNOWN, m.command());
        assertEquals(List.of(), m.args());
    }

    /** Empty string in → UNKNOWN with no args, never an exception. */
    @Test
    void parseEmptyStringReturnsUnknownWithEmptyArgs() {
        Message m = MessageParser.parse("");
        assertSame(Command.UNKNOWN, m.command());
        assertEquals(List.of(), m.args());
    }

    /** Standard happy path: command + two args preserved in order. */
    @Test
    void parseWriteKeyValuePreservesAllTokens() {
        Message m = MessageParser.parse("WRITE|smoke-key|smoke-value");
        assertSame(Command.WRITE, m.command());
        assertEquals(List.of("smoke-key", "smoke-value"), m.args());
    }

    /** Command token is matched case-insensitively. */
    @Test
    void parseIsCaseInsensitiveOnCommand() {
        assertSame(Command.WRITE, MessageParser.parse("write|k|v").command());
        assertSame(Command.READ, MessageParser.parse("Read|k").command());
        assertSame(Command.REGISTER, MessageParser.parse("register|ip|9000").command());
    }

    /** Unknown command verb maps to UNKNOWN, never throws. */
    @Test
    void parseUnknownCommandMapsToUnknownEnum() {
        Message m = MessageParser.parse("FOOBAR|k|v");
        assertSame(Command.UNKNOWN, m.command());
        // Args past the verb are preserved even when the verb is unknown.
        assertEquals(List.of("k", "v"), m.args());
    }

    /** One-arg form: READ|key, WRITE|key|value — arity is preserved, not truncated. */
    @Test
    void parseSingleArgCommandPreservesArity() {
        Message m = MessageParser.parse("READ|only-key");
        assertSame(Command.READ, m.command());
        assertEquals(1, m.args().size());
        assertEquals("only-key", m.args().get(0));
    }

    /** encode → "COMMAND|arg1|arg2" with no trailing delimiter. */
    @Test
    void encodeJoinsCommandAndArgsWithPipe() {
        assertEquals("WRITE|k|v", MessageParser.encode(Command.WRITE, List.of("k", "v")));
        assertEquals("READ|key",  MessageParser.encode(Command.READ,  List.of("key")));
    }

    /** encode with empty args yields just the command name. */
    @Test
    void encodeWithEmptyArgsYieldsBareCommand() {
        assertEquals("WRITE", MessageParser.encode(Command.WRITE, List.of()));
    }

    /**
     * Round-trip: for any non-empty arg list, parse(encode(c, args))
     * equals the original Message. This is the property the codec
     * promises — encode must be the exact inverse of parse.
     */
    @Test
    void roundTripEncodeThenParseYieldsEquivalentMessage() {
        Message original = new Message(Command.WRITE, List.of("alpha", "beta", "gamma"));
        Message roundTripped = MessageParser.parse(original.toWireForm());
        assertEquals(original.command(), roundTripped.command());
        assertEquals(original.args(),    roundTripped.args());
    }

    /** Message.toWireForm() round-trips through MessageParser.parse. */
    @Test
    void messageToWireFormRoundTripsThroughParse() {
        Message original = new Message(Command.READ, List.of("smoke-key"));
        String wire = original.toWireForm();
        assertNotNull(wire);
        assertEquals("READ|smoke-key", wire);

        Message parsed = MessageParser.parse(wire);
        assertEquals(original, parsed);
    }

    /** splitRaw returns what String.split would for the canonical inputs. */
    @Test
    void splitRawSplitsOnEveryPipe() {
        String[] parts = MessageParser.splitRaw("a|b|c", -1);
        assertEquals(3, parts.length);
        assertEquals("a", parts[0]);
        assertEquals("b", parts[1]);
        assertEquals("c", parts[2]);
    }

    /** splitRaw(null) returns an empty array, never a NPE. */
    @Test
    void splitRawNullReturnsEmptyArray() {
        String[] parts = MessageParser.splitRaw(null, -1);
        assertEquals(0, parts.length);
    }

    /** splitRaw with limit=3 keeps the tail as a single token (Java split semantics). */
    @Test
    void splitRawWithLimitKeepsTailAsSingleToken() {
        // limit=3 means: at most 3 tokens, but the third gets all the remainder.
        String[] parts = MessageParser.splitRaw("WRITE|a|b|c|d", 3);
        assertEquals(3, parts.length);
        assertEquals("WRITE", parts[0]);
        assertEquals("a", parts[1]);
        assertEquals("b|c|d", parts[2]);
    }
}
