package org.qbicc.graph;

import java.util.Objects;

import org.qbicc.graph.atomic.WriteAccessMode;
import org.qbicc.type.definition.element.ExecutableElement;

/**
 *
 */
public class Store extends AbstractNode implements Action, OrderedNode {
    private final Node dependency;
    private final PointerValue handle;
    private final Value value;
    private final WriteAccessMode mode;

    Store(Node callSite, ExecutableElement element, int line, int bci, Node dependency, PointerValue handle, Value value, WriteAccessMode mode) {
        super(callSite, element, line, bci);
        this.dependency = dependency;
        this.handle = handle;
        this.value = value;
        this.mode = mode;
        if (! handle.isWritable()) {
            throw new IllegalArgumentException("Handle is not writable");
        }
    }

    @Override
    public Node getDependency() {
        return dependency;
    }

    public Value getValue() {
        return value;
    }

    public WriteAccessMode getAccessMode() {
        return mode;
    }

    int calcHashCode() {
        return Objects.hash(dependency, handle, mode);
    }

    @Override
    String getNodeName() {
        return "Store";
    }

    public boolean equals(final Object other) {
        return other instanceof Store && equals((Store) other);
    }

    @Override
    public StringBuilder toString(StringBuilder b) {
        super.toString(b);
        b.append('(');
        value.toString(b);
        b.append(',');
        b.append(mode);
        b.append(')');
        return b;
    }

    public boolean equals(final Store other) {
        return this == other || other != null && dependency.equals(other.dependency) && handle.equals(other.handle) && value.equals(other.value) && mode == other.mode;
    }

    @Override
    public boolean hasPointerValueDependency() {
        return true;
    }

    @Override
    public PointerValue getPointerValue() {
        return handle;
    }

    @Override
    public int getValueDependencyCount() {
        return 1;
    }

    @Override
    public Value getValueDependency(int index) throws IndexOutOfBoundsException {
        return index == 0 ? value : Util.throwIndexOutOfBounds(index);
    }

    @Override
    public <T, R> R accept(ActionVisitor<T, R> visitor, T param) {
        return visitor.visit(param, this);
    }
}
