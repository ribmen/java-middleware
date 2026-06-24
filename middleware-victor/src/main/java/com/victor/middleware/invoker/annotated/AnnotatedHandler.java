package com.victor.middleware.invoker.annotated;

/**
 * Empty marker interface for objects safe to register with
 * {@link AnnotatedDispatcher}.
 *
 * <p>Intentionally empty. The dispatcher does not {@code instanceof}-check
 * the marker — any {@code Object} may be registered. The marker is
 * documentary: it tells a reader "this class is intended to be registered
 * with the annotation dispatcher".</p>
 */
public interface AnnotatedHandler {
}