package com.victor.middleware.invoker.annotated;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Internal record: the dispatchable shape of a single annotated method.
 *
 * <p>One {@code BoundMethod} is produced per {@link MethodMapping}-annotated
 * method on a registered handler. The {@link AnnotatedDispatcher} looks
 * up the binding by {@link com.victor.middleware.protocol.Command} and
 * invokes {@code method.invoke(target, args[0], args[1], ...)} where each
 * {@code args[i]} is sourced from {@code Message.args().get(paramBindings.get(i).parameterIndex())}.</p>
 *
 * @param method the reflected method
 * @param paramBindings ordered list of parameter bindings (declaration order)
 * @param target the handler instance to invoke against
 */
record BoundMethod(Method method, List<ParamBinding> paramBindings, Object target) {
}