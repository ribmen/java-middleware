/**
 * Single switchboard that maps a
 * {@link com.victor.middleware.protocol.CommunicationType} to the right
 * {@link com.victor.middleware.spi.ComponentServer} or
 * {@link com.victor.middleware.spi.ComponentClient}.
 *
 * <p>The factory is the only place in the module that names the
 * concrete {@code HttpServer}/{@code HttpClient} etc. classes.
 * Everywhere else, code is written against the SPI. That keeps the
 * swap surface to one line:</p>
 *
 * <pre>{@code
 * ComponentServer server = CommunicationFactory.createServer(CommunicationType.TCP);
 * server.start(port, handler);
 * }</pre>
 *
 * <p>Adding a new transport (e.g. WebSocket) is a three-step change:
 * add a value to {@link com.victor.middleware.protocol.CommunicationType},
 * add a branch here, and provide the new client/server pair. Nothing
 * else in the module has to be touched.</p>
 */
package com.victor.middleware.factory;
