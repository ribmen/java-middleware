/**
 * Top-level package of the {@code kvstore} application.
 *
 * <p>After the transport extraction, this package contains only the
 * process entry points and lifecycle classes:</p>
 * <ul>
 *     <li>{@link com.victor.Gateway} — the broker process. Owns the
 *         {@link com.victor.middleware.registry.GatewayRegistry},
 *         accepts client requests on its single transport port, and
 *         round-robins them across the registered workers.</li>
 *     <li>{@link com.victor.BaseComponent} — common lifecycle for the
 *         gateway and workers: a {@code main} that reads CLI args,
 *         binds the configured transport, and starts the
 *         heartbeat/registration bookkeeping.</li>
 *     <li>{@link com.victor.WorkerComponent} — the remote object
 *         host. Each worker owns a {@link com.victor.business.KVStore}
 *         and exposes its operations via the transport SPI.</li>
 * </ul>
 *
 * <p>Everything wire-related lives in
 * {@code com.victor.middleware.*} (the extracted transport module).
 * This package is intentionally thin: it should hold nothing but the
 * domain wiring above the transport seam.</p>
 */
package com.victor;
