package com.victor.middleware.exceptions;

/**
 * Thrown by an {@link com.victor.middleware.invoker.InvocationInterceptor} to
 * abort the current invocation. The {@code InterceptingDispatcher} catches
 * this and converts it to a wire-form error string ({@code "ERRO: <message>"}).
 *
 * <p>This is the single, type-driven abort signal for the interceptor chain.
 * Interceptors should not return a special string to mean "abort"; they
 * throw this exception so the wrapper has a single code path.</p>
 */
public class InvocationAbortedException extends MiddlewareException {
    private static final long serialVersionUID = 1L;

    public InvocationAbortedException(String message) {
        super("INVOKER", message);
    }

    public InvocationAbortedException(String message, Throwable cause) {
        super("INVOKER", message, cause);
    }
}
