package com.victor.middleware.invoker.annotated;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.victor.middleware.protocol.Command;

/**
 * Marks a method as the handler for a single {@link Command}.
 *
 * <p>Method requirements (enforced at {@code AnnotatedDispatcher.register}
 * time by {@code AnnotationScanner}):</p>
 * <ul>
 *     <li>must be {@code public};</li>
 *     <li>must not return {@code void};</li>
 *     <li>may only declare {@code MiddlewareException} subtypes as
 *         checked exceptions (other checked exceptions fail the scan).</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MethodMapping {
    /** The wire-level command this method handles. */
    Command value();
}