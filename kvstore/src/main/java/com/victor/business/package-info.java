/**
 * The {@code kvstore} business domain.
 *
 * <p>This is the only piece of the system that knows what a
 * key/value store actually is:</p>
 * <ul>
 *     <li>{@link com.victor.business.KVStore} — a thread-safe CRUD
 *         map with optional version checks. Pure logic: no sockets,
 *         no threads, no exceptions outside its own domain.</li>
 *     <li>{@link com.victor.business.VersionedValue} — the value
 *         side of the optimistic-concurrency contract. {@link
 *         com.victor.business.KVStore#put(String, com.victor.business.VersionedValue,
 *         long) put(...)} rejects writes whose expected version does
 *         not match.</li>
 * </ul>
 *
 * <p>Because nothing in this package depends on the transport layer,
 * the domain is unit-testable without spinning up sockets — the
 * existing {@code kvstore} integration test exercises
 * {@link com.victor.business.KVStore} end-to-end through the
 * {@code kvstore} module's {@link com.victor.WorkerComponent}, and
 * the business package itself can be exercised in isolation if
 * needed.</p>
 */
package com.victor.business;
