package com.victor.middleware.protocol;

/**
 * Identifies which transport is being used. Trivial enum, kept as a stable
 * abstraction surface so the {@link com.victor.middleware.factory.CommunicationFactory}
 * can switch on it.
 */
public enum CommunicationType {
    TCP,
    HTTP,
    UDP
}