package cc.quarkus.qcc.type;

import java.util.List;
import java.util.Objects;

import cc.quarkus.qcc.type.definition.DefinedTypeDefinition;

/**
 * A type which represents a class (<em>not</em> a reference to a class).
 */
public final class ClassObjectType extends PhysicalObjectType {
    private final DefinedTypeDefinition definition;
    private final ClassObjectType superClassType;
    private final List<InterfaceObjectType> interfaces;

    ClassObjectType(final TypeSystem typeSystem, final boolean const_, final DefinedTypeDefinition definition, final ClassObjectType superClassType, final List<InterfaceObjectType> interfaces) {
        super(typeSystem, Objects.hash(definition), const_);
        this.definition = definition;
        this.superClassType = superClassType;
        this.interfaces = interfaces;
    }

    public ClassObjectType asConst() {
        return (ClassObjectType) super.asConst();
    }

    public DefinedTypeDefinition getDefinition() {
        return definition;
    }

    public boolean hasSuperClass() {
        return superClassType != null;
    }

    ClassObjectType constructConst() {
        return new ClassObjectType(typeSystem, true, definition, superClassType, interfaces);
    }

    public ClassObjectType getSuperClassType() {
        return superClassType;
    }

    public long getSize() {
        // todo: probe definition for layout size? or, maybe never report layout and rely on lowering to struct instead
        throw new IllegalStateException("Object layout has not yet taken place");
    }

    public boolean isSubtypeOf(final ObjectType other) {
        return this == other
            || other instanceof ClassObjectType && isSubtypeOf((ClassObjectType) other)
            || other instanceof InterfaceObjectType && isSubtypeOf((InterfaceObjectType) other);
    }

    public boolean isSubtypeOf(final ClassObjectType other) {
        return this == other
            || superClassType != null
            && superClassType.isSubtypeOf(other);
    }

    public boolean isSubtypeOf(final InterfaceObjectType other) {
        for (InterfaceObjectType interface_ : interfaces) {
            if (interface_.isSubtypeOf(other)) {
                return true;
            }
        }
        return false;
    }

    public StringBuilder toString(final StringBuilder b) {
        return super.toString(b).append("class").append('(').append(definition.getInternalName()).append(')');
    }

    public StringBuilder toFriendlyString(final StringBuilder b) {
        return b.append("class").append('.').append(definition.getInternalName().replace('/', '-'));
    }
}