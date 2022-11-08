package org.qbicc.graph;

import org.qbicc.graph.atomic.AccessMode;
import org.qbicc.type.InvokableType;
import org.qbicc.type.PointerType;
import org.qbicc.type.ValueType;

/**
 * A handle expression for some thing which is addressable at run time (i.e. can be read from and/or written to).
 */
public interface PointerValue extends Value {
    /**
     * Get the type that a pointer to the referred value would have.
     *
     * @return the referred pointer type
     */
    PointerType getType();

    /**
     * Get the type that the referred value would have. Equivalent to {@code getPointerType().getPointeeType()}.
     *
     * @return the referred value type
     */
    default ValueType getPointeeType() {
        return getType().getPointeeType();
    }

    /**
     * Get the return type of this value handle. If it is not a pointer to something invokable, an exception is thrown.
     *
     * @return the return type
     */
    default ValueType getReturnType() {
        return getType().getPointeeType(InvokableType.class).getReturnType();
    }

    /**
     * Determine whether this handle is writable.
     *
     * @return {@code true} if the handle is writable, {@code false} otherwise
     */
    default boolean isWritable() {
        return true;
    }

    /**
     * Determine whether this handle is readable.
     *
     * @return {@code true} if the handle is readable, {@code false} otherwise
     */
    default boolean isReadable() {
        return true;
    }

    /**
     * Determine whether this handle refers to a function or method that definitely does not throw any exception,
     * not even {@link StackOverflowError} or {@link OutOfMemoryError}.
     *
     * @return {@code true} if the handle referee can definitely never throw, {@code false} otherwise
     */
    default boolean isNoThrow() {
        return false;
    }

    /**
     * Determine whether this handle refers to a function or method that definitely never returns.
     *
     * @return {@code true} if the handle referee can definitely never return, {@code false} otherwise
     */
    default boolean isNoReturn() {
        return false;
    }

    /**
     * Determine whether this handle refers to a function or method that definitely has no side-effects.
     *
     * @return {@code true} if the handle referee definitely has no side-effects, {@code false} otherwise
     */
    default boolean isNoSideEffect() {
        return false;
    }

    /**
     * Determine whether the value handle is always in the same location.
     *
     * @return {@code true} if the handle is always in the same location, or {@code false} otherwise
     */
    boolean isConstantLocation();

    /**
     * Determine whether the value handle eventually refers to a value which is constant.
     *
     * @return {@code true} if the handle is always in the same location, or {@code false} otherwise
     */
    boolean isValueConstant();

    /**
     * Determine whether the value handle refers to a function or method that should be constant-folded.
     *
     * @return {@code true} to constant-fold the call result, or {@code false} otherwise
     */
    default boolean isFold() {
        return false;
    }

    /**
     * Get the detected access mode for this handle.
     *
     * @return the detected access mode for this handle (must not be {@code null})
     */
    AccessMode getDetectedMode();

    default <T, R> R accept(ValueVisitor<T, R> visitor, T param) {
        return accept((PointerValueVisitor<T, R>) visitor, param);
    }

    default <T> long accept(ValueVisitorLong<T> visitor, T param) {
        return accept((PointerValueVisitorLong<T>) visitor, param);
    }

    <T, R> R accept(PointerValueVisitor<T, R> visitor, T param);

    <T> long accept(PointerValueVisitorLong<T> visitor, T param);
}
