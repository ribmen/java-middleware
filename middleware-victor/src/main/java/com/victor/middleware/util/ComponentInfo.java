package com.victor.middleware.util;

import java.net.InetAddress;

/**
 * Carries the {@code (address, port, lastHeartbeat)} triple used by the
 * service registry. Moved here from {@code kvstore} so the future Gateway
 * (Phase 2) can compile against middleware symbols.
 *
 * <p>Phase 1 keeps the original shape: address and port are immutable, the
 * heartbeat timestamp is mutable and refreshed by
 * {@link #setLastHeartbeat()}. The {@link #getId()} helper added in Phase 1
 * gives callers a stable {@code "host:port"} string key.</p>
 */
public class ComponentInfo {
    private final InetAddress address;
    private final int port;
    private long lastHeartbeat;

    public ComponentInfo(InetAddress address, int port) {
        this.address = address;
        this.port = port;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public void setLastHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    /** @return the canonical {@code "host:port"} identifier for this component. */
    public String getId() {
        return address.getHostAddress() + ":" + port;
    }

    @Override
    public String toString() {
        return "Component http://" + address + ":" + port;
    }
}