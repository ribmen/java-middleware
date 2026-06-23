/**
 * Marshaller decorator layer (Phase 5B).
 *
 * <p>Sits between a raw transport (HTTP/TCP/UDP) and a typed application
 * handler. The raw {@link com.victor.middleware.spi.ComponentServer} and
 * {@link com.victor.middleware.spi.ComponentClient} speak canonical pipe-
 * delimited wire form (see
 * {@link com.victor.middleware.protocol.MessageParser}); this package
 * translates that wire form to/from a JSON envelope via
 * {@link com.victor.middleware.spi.Marshaller}.</p>
 *
 * <p>Composition:</p>
 * <pre>
 *   MarshalledServer → HttpServer (raw) → InterceptingDispatcher → handler
 *   MarshalledClient → HttpClient (raw) → worker
 * </pre>
 *
 * <p>Classes:</p>
 * <ul>
 *     <li>{@link JsonMarshaller} — Jackson-backed implementation of the
 *         {@link com.victor.middleware.spi.Marshaller} SPI. Round-trips
 *         {@link com.victor.middleware.protocol.Message} values through
 *         the envelope shape {@code {"verb":"…","args":[…],"body":{}}}.</li>
 *     <li>{@link MarshallerFactory} — single accessor for the JSON
 *         {@link com.victor.middleware.spi.Marshaller} singleton.</li>
 *     <li>{@link MarshalledServer} — server decorator. Catches
 *         {@link com.victor.middleware.exceptions.MarshalException} on
 *         the request path and writes a JSON error envelope
 *         {@code {"error":"…","code":400}}.</li>
 *     <li>{@link MarshalledClient} — client decorator. Catches
 *         {@link com.victor.middleware.exceptions.MarshalException} on
 *         the response path and returns
 *         {@code Message(UNKNOWN, [errorMessage])} so callers see a
 *         uniform typed surface.</li>
 *     <li>{@link MessageEnvelope} — package-private Jackson DTO. Not
 *         part of the public API.</li>
 * </ul>
 */
package com.victor.middleware.marshaller;