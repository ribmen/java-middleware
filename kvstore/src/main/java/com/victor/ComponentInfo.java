package com.victor;

import java.net.InetAddress;

public class ComponentInfo {
    private final InetAddress address;
    private final int port;
    private final ComponentType type;
    private long lastHeartbeat;

    public ComponentInfo(InetAddress address, int port, ComponentType type) {
        this.address = address;
        this.port = port;
        this.type = type;
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPort() {
        return port;
    }

    public ComponentType getType() {
        return type;
    }

    public void setLastHeartbeat() {
        this.lastHeartbeat = System.currentTimeMillis();
    }

    public long getLastHeartbeat() {
        return lastHeartbeat;
    }

    @Override
    public String toString() {
        return "Component http://" + address + ":" + port + " do tipo " + type;
    }

}
