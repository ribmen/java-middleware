package com.victor.middleware.registry;

/**
 * Liveness state of a registered service node as tracked by the
 * {@link GatewayRegistry}.
 *
 * <p>Adapts the reference project's {@code Boolean status} field into a
 * proper enum so that future states (e.g. {@code DRAINING},
 * {@code QUARANTINED}) can be added without changing the type of
 * {@link ServiceRecord#status()}.</p>
 */
public enum NodeStatus {

    /**
     * The node has sent a heartbeat within the registry's freshness window
     * and is eligible to receive traffic.
     */
    HEALTHY,

    /**
     * The node has missed one or more heartbeat sweeps. Still in the
     * registry for diagnostic purposes but excluded from round-robin
     * selection. The failure detector may transition it to {@link #STALE}
     * permanently or remove it.
     */
    STALE
}
