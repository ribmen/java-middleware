package com.victor.business;

import java.util.HashMap;

public class KVStore {
    private HashMap<String, VersionedValue> store;

    public KVStore(HashMap<String, VersionedValue> store) {
        this.store = store;
    }

    public HashMap<String, VersionedValue> getStore() {
        return store;
    }

    public String write(String key, String value) {
        int newVersion = 1;
        if (store.containsKey(key)) {
            newVersion = store.get(key).getVersion() + 1;
        }
        store.put(key, new VersionedValue(value, newVersion));
        System.out.println("Write key: " + key + ", value: " + value + ", version: " + newVersion);
        return "Written key: " + key + ", value: " + value + ", version: " + newVersion;
    }

    public VersionedValue read(String key) {
        if (store.containsKey(key)) {
            VersionedValue value = store.get(key);
            System.out.println("Read key: " + key + ", value: " + value.getValue() + ", version: " + value.getVersion());
            return value;
        } else {
            throw new IllegalArgumentException("Key not found: " + key);
        }
    }

    public VersionedValue getMostRecentValue(KVStore kvStore) {
        if (kvStore.getStore().isEmpty()) {
            throw new IllegalStateException("KVStore is empty");
        }
        VersionedValue mostRecent = null;
        for (VersionedValue value : kvStore.getStore().values()) { //optimize this algorithm later
            if (mostRecent == null || value.getVersion() > mostRecent.getVersion()) {
                mostRecent = value;
            }
        }
        return mostRecent;
    }
}
