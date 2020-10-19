package cc.quarkus.qcc.graph;

import cc.quarkus.qcc.type.ArrayType;
import cc.quarkus.qcc.type.ValueType;

/**
 * A read of an array element.
 */
public final class ArrayElementRead extends AbstractValue implements ArrayElementOperation {
    private final Node dependency;
    private final Value instance;
    private final Value index;
    private final JavaAccessMode mode;

    ArrayElementRead(final Node dependency, final Value instance, final Value index, final JavaAccessMode mode) {
        this.dependency = dependency;
        this.instance = instance;
        this.index = index;
        this.mode = mode;
    }

    public JavaAccessMode getMode() {
        return mode;
    }

    public Value getInstance() {
        return instance;
    }

    public Value getIndex() {
        return index;
    }

    public ValueType getType() {
        return ((ArrayType)instance.getType()).getElementType();
    }

    public <T, R> R accept(final ValueVisitor<T, R> visitor, final T param) {
        return visitor.visit(param, this);
    }
}