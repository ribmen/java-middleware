package com.victor.middleware.registry;

import java.net.InetAddress;

/**
 * Mutable record of a single node tracked by the {@link GatewayRegistry}.
 *
 * <p>Adapts the reference project's {@code br.imd.ufrn.application.models.ServiceRecord}
 * (which uses a nullable {@code Boolean status} field) by:</p>
 * <ul>
 *     <li>Replacing {@code Boolean status} with the typed
 *         {@link NodeStatus} enum.</li>
 *     <li>Adding a {@link #lastHeartbeatNanos()} counterpart to
 *         {@link #lastHeartbeatMillis()} so future code can use
 *         monotonic clocks if needed.</li>
 * </ul>
 *
 * <p>Instances are not thread-safe on their own; the
 * {@link GatewayRegistry} protects all mutations through the underlying
 * {@link java.util.concurrent.ConcurrentHashMap} and a per-record
 * {@code synchronized} block on the heartbeat update path.</p>
 */
public class ServiceRecord {

    private final InetAddress address;
    private final int port;
    private volatile NodeStatus status;
    private volatile long lastHeartbeatMillis;

    public ServiceRecord(InetAddress address, int port) {
        this.address = address;
        this.port = port;
        this.status = NodeStatus.HEALTHY;
        this.lastHeartbeatMillis = System.currentTimeMillis();
    }

    /**
     * Atomically marks this record as freshly heartbeated. Sets status back
     * to {@link NodeStatus#HEALTHY} and bumps the timestamp. Thread-safe via
     * {@code volatile} fields — no external lock required.
     */
    public void refreshHeartbeat() {
        this.status = NodeStatus.HEALTHY;
        this.lastHeartbeatMillis = System.currentTimeMillis();
    }

    /**
     * Mark this record as having missed at least one heartbeat sweep. The
     * record remains in the registry but is excluded from
     * {@code GatewayRegistry.findHealthy()}.
     */
    public void markStale() {
        this.status = NodeStatus.STALE;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public NodeStatus status() {
        return status;
    }

    public long lastHeartbeatMillis() {
        return lastHeartbeatMillis;
    }

    /** @return the canonical {@code "host:port"} identifier for this record. */
    public String getId() {
        return address.getHostAddress() + ":" + port;
    }

    @Override
    public String toString() {
        return "ServiceRecord{" + getId() + ", " + status + "}";
    }
}
