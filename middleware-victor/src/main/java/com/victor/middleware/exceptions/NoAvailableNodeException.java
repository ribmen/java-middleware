package com.victor.middleware.exceptions;

/**
 * Raised by the future Gateway registry (Phase 2) when round-robin selection
 * finds zero healthy workers. Defined in Phase 1 so callers and the
 * {@link com.victor.middleware.factory.CommunicationFactory} already speak in
 * middleware vocabulary.
 */
public class NoAvailableNodeException extends MiddlewareException {

    private static final long serialVersionUID = 1L;

    public NoAvailableNodeException(String message) {
        super("GATEWAY", message);
    }
}