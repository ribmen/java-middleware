/**
 * Wire envelope and codec for the middleware transport.
 *
 * <p>The package has three responsibilities:</p>
 * <ol>
 *     <li>Name the protocols the transport can speak
 *         ({@link com.victor.middleware.protocol.CommunicationType}: HTTP,
 *         TCP, UDP).</li>
 *     <li>Name the verbs that travel on the wire
 *         ({@link com.victor.middleware.protocol.Command}: WRITE, READ,
 *         REGISTER, HEARTBEAT, UNKNOWN). Typing the verbs catches a
 *         whole class of typos that would otherwise surface as
 *         {@code String.equals} mismatches at runtime.</li>
 *     <li>Encode/decode the canonical pipe-delimited form
 *         ({@code COMMAND|arg1|arg2|...}) via
 *         {@link com.victor.middleware.protocol.MessageParser}.</li>
 * </ol>
 *
 * <p>The wire format is plain UTF-8 strings joined by {@code |} — no
 * JSON, no XML. That choice keeps every server under two hundred lines
 * of code and matches the "Sockets" constraint of the assignment.</p>
 */
package com.victor.middleware.protocol;
