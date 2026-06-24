package com.victor.middleware.invoker.annotated;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.victor.middleware.exceptions.MiddlewareException;
import com.victor.middleware.protocol.Command;

/**
 * One-shot reflection pass that turns an annotated handler object into
 * a {@code Map<Command, BoundMethod>} keyed by wire command.
 *
 * <p>Called once per {@code AnnotatedDispatcher.register(handler)} call.
 * All registration-time validation lives here (see spec §5).</p>
 */
final class AnnotationScanner {

    private AnnotationScanner() {
        // utility class — not meant to be instantiated
    }

    /**
     * Walk the handler's {@code @MethodMapping} methods, validate the
     * registration rules, and return the dispatch table.
     *
     * @param handler the object to reflect (any non-null Object; the
     *                {@link AnnotatedHandler} marker is documentary)
     * @return a fresh map keyed by {@link Command}
     * @throws NullPointerException if {@code handler} is null
     * @throws IllegalArgumentException if a {@code @MethodMapping} method
     *         is non-public, returns void, or declares a checked exception
     *         that is not a {@link MiddlewareException} subtype
     * @throws IllegalStateException if two methods on the same handler
     *         share the same {@code @MethodMapping} command, if any
     *         parameter is missing {@code @Param}, or if any method has
     *         two parameters sharing the same {@code @Param} name
     */
    static Map<Command, BoundMethod> scan(Object handler) {
        if (handler == null) {
            throw new NullPointerException("handler must not be null");
        }
        Map<Command, BoundMethod> result = new EnumMap<>(Command.class);
        for (Method method : handler.getClass().getMethods()) {
            MethodMapping mapping = method.getAnnotation(MethodMapping.class);
            if (mapping == null) {
                continue;
            }
            BoundMethod bound = buildBoundMethod(handler, method);
            Command command = mapping.value();
            if (result.containsKey(command)) {
                throw new IllegalStateException(
                        "duplicate @MethodMapping for command " + command
                                + " on " + handler.getClass().getName()
                                + ": " + result.get(command).method().getName()
                                + ", " + method.getName());
            }
            result.put(command, bound);
        }
        return result;
    }

    private static BoundMethod buildBoundMethod(Object handler, Method method) {
        // Note: Class.getMethods() (used above) only returns public methods, so
        // this non-public check is dead code under the current entry point. It
        // stays as defensive coverage in case a future refactor switches to
        // getDeclaredMethods() — see AnnotationScannerTest#nonPublicMethodIsFilteredByGetMethods.
        if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    "@MethodMapping method must be public: " + method);
        }
        if (method.getReturnType() == void.class) {
            throw new IllegalArgumentException(
                    "@MethodMapping method must not return void: " + method);
        }
        for (Class<?> ex : method.getExceptionTypes()) {
            if (!MiddlewareException.class.isAssignableFrom(ex)) {
                throw new IllegalArgumentException(
                        "@MethodMapping method may only throw MiddlewareException"
                                + " subtypes: " + method + " declares " + ex.getName());
            }
        }
        Parameter[] params = method.getParameters();
        List<ParamBinding> bindings = new ArrayList<>(params.length);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < params.length; i++) {
            Param param = params[i].getAnnotation(Param.class);
            if (param == null) {
                throw new IllegalStateException(
                        "parameter " + i + " of " + method + " has no @Param");
            }
            if (!seen.add(param.value())) {
                throw new IllegalStateException(
                        "duplicate @Param('" + param.value() + "') on " + method);
            }
            bindings.add(new ParamBinding(param.value(), i));
        }
        return new BoundMethod(method, List.copyOf(bindings), handler);
    }
}
