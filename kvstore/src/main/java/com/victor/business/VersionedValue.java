package com.victor.business;

public class VersionedValue {
    private String value;
    private int version;
    private long timestamp;

    public VersionedValue(String value, int version) {
        this(value, version, System.currentTimeMillis());
    }

    public VersionedValue(String value, int version, long timestamp) {
        this.value = value;
        this.version = version;
        this.timestamp = timestamp;
    }

    public String getValue() {
        return value;
    }

    public int getVersion() {
        return version;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return value + " | Version: " + version + " | Timestamp: " + timestamp;
    }
}
