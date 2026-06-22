/**
 * In-process worker registry used by the gateway to discover and pick
 * workers.
 *
 * <p>The centerpiece is
 * {@link com.victor.middleware.registry.GatewayRegistry}, an
 * adaptation of the reference project's
 * {@code br.imd.ufrn.application.gateway.GatewayRegistry} with three
 * concrete improvements: the heartbeat schedule bug is fixed (named
 * constants instead of using the listening port as the period), the
 * background sweeper is a {@link java.util.concurrent.ScheduledExecutorService}
 * with a proper shutdown hook instead of an unbounded
 * {@code while(true)} loop, and an empty registry surfaces
 * {@link com.victor.middleware.exceptions.NoAvailableNodeException}
 * instead of forcing every caller to null-check.</p>
 *
 * <p>{@link com.victor.middleware.registry.NodeStatus} and
 * {@link com.victor.middleware.registry.ServiceRecord} carry the
 * per-entry state.</p>
 */
package com.victor.middleware.registry;
