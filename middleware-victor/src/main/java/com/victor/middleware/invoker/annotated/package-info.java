/**
 * Annotation-driven component model: a business object declares its
 * invocable methods with {@link MethodMapping} and its parameters with
 * {@link Param}; {@link AnnotationScanner} reflects the object into a
 * {@code Command → BoundMethod} table at registration time, and
 * {@link AnnotatedDispatcher} routes incoming {@link com.victor.middleware.protocol.Message}
 * values to those methods.
 *
 * <p>This package sits alongside the legacy {@code invoker.Dispatcher}
 * (Phase 1). It does not replace it; the legacy pipe-codec dispatcher
 * remains in production. The annotation model targets the
 * signature-preserving half of the PDF "Modelo de Componentes" rubric
 * cell.</p>
 *
 * <p><b>Wire binding is positional in v1.</b> {@link Param}'s value is
 * validated for presence and uniqueness but does not name-key on the
 * wire: {@code args().get(0)} binds to the first {@code @Param} in
 * declaration order, {@code args().get(1)} to the second, and so on.
 * Named binding is deferred to v2.</p>
 */
package com.victor.middleware.invoker.annotated;