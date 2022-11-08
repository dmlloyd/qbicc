package org.qbicc.plugin.native_;

import org.qbicc.graph.BasicBlockBuilder;
import org.qbicc.graph.DelegatingBasicBlockBuilder;
import org.qbicc.graph.PointerValue;
import org.qbicc.type.CompoundType;
import org.qbicc.type.ValueType;
import org.qbicc.type.definition.element.FieldElement;

public class StructMemberAccessBasicBlockBuilder extends DelegatingBasicBlockBuilder {
    public StructMemberAccessBasicBlockBuilder(FactoryContext context, BasicBlockBuilder delegate) {
        super(delegate);
    }

    public PointerValue instanceFieldOf(PointerValue instance, FieldElement field) {
        ValueType valueType = instance.getPointeeType();
        if (valueType instanceof CompoundType) {
            return memberOf(instance, ((CompoundType) valueType).getMember(field.getName()));
        }
        return super.instanceFieldOf(instance, field);
    }
}
