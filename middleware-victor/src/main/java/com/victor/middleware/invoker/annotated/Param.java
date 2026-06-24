package com.victor.middleware.invoker.annotated;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names a parameter on a {@link MethodMapping}-annotated method.
 *
 * <p>In v1 the value is <b>documentary</b> — the dispatcher binds
 * <b>positionally</b> from {@code Message.args()}. Two parameters
 * on the same method may not share the same {@code @Param} name;
 * the scan fails otherwise.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Param {
    /** A human-readable name for this parameter. */
    String value();
}