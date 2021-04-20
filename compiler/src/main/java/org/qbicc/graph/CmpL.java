package org.qbicc.graph;

import org.qbicc.type.SignedIntegerType;
import org.qbicc.type.definition.element.ExecutableElement;

public class CmpL extends AbstractBinaryValue implements NonCommutativeBinaryValue {
    private final SignedIntegerType integerType;

    CmpL(final Node callSite, final ExecutableElement element, final int line, final int bci, final Value v1, final Value v2, SignedIntegerType integerType) {
        super(callSite, element, line, bci, v1, v2);
        this.integerType = integerType;
    }

    public <T, R> R accept(final ValueVisitor<T, R> visitor, final T param) {
        return visitor.visit(param, this);
    }

    public SignedIntegerType getType() {
        return integerType;
    }
}