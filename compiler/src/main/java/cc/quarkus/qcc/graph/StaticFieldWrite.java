package cc.quarkus.qcc.graph;

import java.util.Objects;

import cc.quarkus.qcc.type.definition.element.FieldElement;

/**
 *
 */
public final class StaticFieldWrite extends AbstractNode implements FieldWrite, Action {
    private final Node dependency;
    private final FieldElement fieldElement;
    private final Value value;
    private final JavaAccessMode mode;

    StaticFieldWrite(final int line, final int bci, final Node dependency, final FieldElement fieldElement, final Value value, final JavaAccessMode mode) {
        super(line, bci);
        this.dependency = dependency;
        this.fieldElement = fieldElement;
        this.value = value;
        this.mode = mode;
    }

    public FieldElement getFieldElement() {
        return fieldElement;
    }

    public Value getWriteValue() {
        return value;
    }

    public JavaAccessMode getMode() {
        return mode;
    }

    public int getBasicDependencyCount() {
        return 1;
    }

    public Node getBasicDependency(final int index) throws IndexOutOfBoundsException {
        return index == 0 ? dependency : Util.throwIndexOutOfBounds(index);
    }

    public <T, R> R accept(final ActionVisitor<T, R> visitor, final T param) {
        return visitor.visit(param, this);
    }

    int calcHashCode() {
        return Objects.hash(dependency, fieldElement, value, mode);
    }

    public boolean equals(final Object other) {
        return other instanceof StaticFieldWrite && equals((StaticFieldWrite) other);
    }

    public boolean equals(final StaticFieldWrite other) {
        return this == other || other != null
            && dependency.equals(other.dependency)
            && fieldElement.equals(other.fieldElement)
            && value.equals(other.value)
            && mode.equals(other.mode);
    }
}