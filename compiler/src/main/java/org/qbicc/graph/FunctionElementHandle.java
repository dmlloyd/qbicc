package org.qbicc.graph;

import org.qbicc.type.definition.element.ExecutableElement;
import org.qbicc.type.definition.element.FunctionElement;

/**
 * A handle for a function.
 */
public final class FunctionElementHandle extends Executable {

    FunctionElementHandle(ExecutableElement element, int line, int bci, FunctionElement functionElement) {
        super(element, line, bci, functionElement);
    }

    @Override
    public FunctionElement getExecutable() {
        return (FunctionElement) super.getExecutable();
    }

    public boolean equals(final Executable other) {
        return other instanceof FunctionElementHandle && equals((FunctionElementHandle) other);
    }

    public boolean equals(final FunctionElementHandle other) {
        return super.equals(other);
    }

    @Override
    public <T, R> R accept(ValueHandleVisitor<T, R> visitor, T param) {
        return visitor.visit(param, this);
    }
}