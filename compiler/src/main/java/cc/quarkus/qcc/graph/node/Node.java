package cc.quarkus.qcc.graph.node;

import java.util.List;
import java.util.stream.Collectors;

import cc.quarkus.qcc.interpret.Context;
import cc.quarkus.qcc.type.TypeDescriptor;

public interface Node<V> {

    int getId();

    ControlNode<?> getControl();

    void setControl(ControlNode<?> control);

    void addSuccessor(Node<?> out);

    V getValue(Context context);

    Class<V> getType();
    TypeDescriptor<V> getTypeDescriptor();

    List<? extends Node<?>> getPredecessors();

    default List<? extends ControlNode<?>> getControlPredecessors() {
        return getPredecessors().stream()
                .filter(e-> e instanceof ControlNode<?> )
                .map(e->(ControlNode<?>)e)
                .collect(Collectors.toList());
    }

    List<? extends Node<?>> getSuccessors();

    default List<? extends ControlNode<?>> getControlSuccessors() {
        return getSuccessors().stream()
                .filter(e-> e instanceof ControlNode<?> )
                .map(e->(ControlNode<?>)e)
                .collect(Collectors.toList());
    }

    default String label() {
        return "node " + getId();
    }

}
