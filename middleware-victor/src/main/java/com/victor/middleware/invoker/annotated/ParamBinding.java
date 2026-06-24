package com.victor.middleware.invoker.annotated;

/**
 * Internal record: associates a method parameter's {@link Param} name
 * with its declaration-order index. The {@code parameterIndex} is the
 * <b>positional</b> binding site — {@code args().get(parameterIndex)}
 * is what gets passed to {@link java.lang.reflect.Method#invoke} for
 * this parameter.
 *
 * @param name the {@code @Param("name")} value (documentary in v1)
 * @param parameterIndex 0-based index in the method's parameter list
 */
record ParamBinding(String name, int parameterIndex) {
}