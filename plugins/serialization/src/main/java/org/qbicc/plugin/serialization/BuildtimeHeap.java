package org.qbicc.plugin.serialization;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.smallrye.common.constraint.Assert;
import org.qbicc.context.AttachmentKey;
import org.qbicc.context.CompilationContext;
import org.qbicc.graph.literal.Literal;
import org.qbicc.graph.literal.LiteralFactory;
import org.qbicc.object.Data;
import org.qbicc.object.Linkage;
import org.qbicc.object.Section;
import org.qbicc.plugin.coreclasses.CoreClasses;
import org.qbicc.plugin.layout.Layout;
import org.qbicc.type.ArrayType;
import org.qbicc.type.CompoundType;
import org.qbicc.type.Primitive;
import org.qbicc.type.TypeSystem;
import org.qbicc.type.ValueType;
import org.qbicc.type.WordType;
import org.qbicc.type.definition.LoadedTypeDefinition;
import org.qbicc.type.definition.element.ExecutableElement;
import org.qbicc.type.definition.element.FieldElement;
import org.qbicc.type.definition.element.GlobalVariableElement;
import org.qbicc.type.descriptor.BaseTypeDescriptor;

public class BuildtimeHeap {
    private static final AttachmentKey<BuildtimeHeap> KEY = new AttachmentKey<>();
    private static final String prefix = "qbicc_initial_heap_obj_";

    private final CompilationContext ctxt;
    private final Layout layout;
    /** For lazy definition of native array types for literals */
    private final HashMap<String, CompoundType> arrayTypes = new HashMap<>();
    /** For interning string literals */
    private final HashMap<String, Data>  stringLiterals = new HashMap<>();
    /** For interning java.lang.Class instances */
    private final HashMap<LoadedTypeDefinition, Data> classObjects = new HashMap<>();
    /** For interning objects */
    private final IdentityHashMap<Object, Data> objects = new IdentityHashMap<>();
    /** The initial heap */
    private final Section heapSection;
    /** The global array of java.lang.Class instances that is part of the serialized heap */
    private GlobalVariableElement classArrayGlobal;

    private int literalCounter = 0;

    private BuildtimeHeap(CompilationContext ctxt) {
        this.ctxt = ctxt;
        this.layout = Layout.get(ctxt);

        LoadedTypeDefinition ih = ctxt.getBootstrapClassContext().findDefinedType("org/qbicc/runtime/main/InitialHeap").load();
        this.heapSection = ctxt.getOrAddProgramModule(ih).getOrAddSection(ctxt.IMPLICIT_SECTION_NAME); // TODO: use ctxt.INITIAL_HEAP_SECTION_NAME
    }

    public static BuildtimeHeap get(CompilationContext ctxt) {
        BuildtimeHeap heap = ctxt.getAttachment(KEY);
        if (heap == null) {
            heap = new BuildtimeHeap(ctxt);
            BuildtimeHeap appearing = ctxt.putAttachmentIfAbsent(KEY, heap);
            if (appearing != null) {
                heap = appearing;
            }
        }
        return heap;
    }

    void setClassArrayGlobal(GlobalVariableElement g) {
        this.classArrayGlobal = g;
    }

    public GlobalVariableElement getAndRegisterGlobalClassArray(ExecutableElement originalElement) {
        Assert.assertNotNull(classArrayGlobal);
        if (!classArrayGlobal.getEnclosingType().equals(originalElement.getEnclosingType())) {
            Section section = ctxt.getImplicitSection(originalElement.getEnclosingType());
            section.declareData(null, classArrayGlobal.getName(), classArrayGlobal.getType());
        }
        return classArrayGlobal;
    }

    public synchronized Data serializeStringLiteral(String value) {
        // String literals are interned via equals, not ==
        if (stringLiterals.containsKey(value)) {
            return stringLiterals.get(value);
        }
        LoadedTypeDefinition jls = ctxt.getBootstrapClassContext().findDefinedType("java/lang/String").load();
        Data sl = serializeObject(jls, value);
        stringLiterals.put(value, sl);
        return sl;
    }

    public Data serializeClassObject(Primitive primitive) {
        LoadedTypeDefinition jlc = ctxt.getBootstrapClassContext().findDefinedType("java/lang/Class").load();
        Literal classLiteral = createClassObjectLiteral(jlc, primitive.getName(), primitive.getType());
        return defineData(nextLiteralName(), classLiteral);
    }

    public synchronized Data serializeClassObject(LoadedTypeDefinition type) {
        if (classObjects.containsKey(type)) {
            return classObjects.get(type);
        }

        LoadedTypeDefinition jlc = ctxt.getBootstrapClassContext().findDefinedType("java/lang/Class").load();
        Data cl = serializeClassObjectImpl(jlc, type);
        classObjects.put(type, cl);
        return cl;
    }

    public synchronized Data serializeObject(Object obj) {
        if (objects.containsKey(obj)) {
            return objects.get(obj);
        }

        Class<?> cls = obj.getClass();
        // java.lang.Class instances are their own special thing; can't just reflectively write them.
        if (obj instanceof Class<?>) {
            LoadedTypeDefinition ltd = ctxt.getBootstrapClassContext().findDefinedType(cls.getName().replace('.', '/')).load();
            return serializeClassObject(ltd);
        }

        Data data;
        if (cls.isArray()) {
            if (obj instanceof byte[]) {
                data = serializeArray((byte[]) obj);
            } else if (obj instanceof boolean[]) {
                data = serializeArray((boolean[]) obj);
            } else if (obj instanceof char[]) {
                data =  serializeArray((char[]) obj);
            } else if (obj instanceof short[]) {
                data =  serializeArray((short[]) obj);
            } else if (obj instanceof int[]) {
                data = serializeArray((int[]) obj);
            } else if (obj instanceof float[]) {
                data = serializeArray((float[]) obj);
            } else if (obj instanceof long[]) {
                data = serializeArray((long[]) obj);
            } else if (obj instanceof double[]) {
                data = serializeArray((double[]) obj);
            } else {
                data = serializeArray((Object[]) obj);
            }
        } else {
            LoadedTypeDefinition ltd = ctxt.getBootstrapClassContext().findDefinedType(cls.getName().replace('.', '/')).load();
            data = serializeObject(ltd, obj);
        }

        objects.put(obj, data);
        return data;
    }

    private Literal createClassObjectLiteral(LoadedTypeDefinition jlc, String className, ValueType type) {
        LiteralFactory lf = ctxt.getLiteralFactory();
        Layout.LayoutInfo jlcLayout = layout.getInstanceLayoutInfo(jlc);
        CompoundType jlcType = jlcLayout.getCompoundType();
        HashMap<CompoundType.Member, Literal> memberMap = new HashMap<>();

        // First default to a zero initializer for all the instance fields
        for (CompoundType.Member m: jlcType.getMembers()) {
            memberMap.put(m, lf.zeroInitializerLiteralOfType(m.getType()));
        }

        // Object header
        memberMap.put(jlcLayout.getMember(layout.getObjectTypeIdField()), lf.literalOf(jlc.getTypeId()));

        // Next, initialize instance fields of the qbicc jlc type that we can/want to set at build time.
        CompoundType.Member name = jlcType.getMember("name");
        memberMap.put(name, dataToLiteral(lf, serializeStringLiteral(className), (WordType)name.getType()));

        CompoundType.Member id = jlcType.getMember("id");
        memberMap.put(id, lf.literalOfType(type));

        //
        // TODO: Figure out what information we need to support reflection and serialize it too (VMClass instance???)
        //

        return ctxt.getLiteralFactory().literalOf(jlcType, memberMap);
    }

    private Data serializeClassObjectImpl(LoadedTypeDefinition jlc, LoadedTypeDefinition type) {
        String externalName = type.getInternalName().replace('/', '.');
        if (externalName.startsWith("internal_array_")) {
            externalName = externalName.replaceFirst("internal_array_", "[");
        }
        Literal classLiteral = createClassObjectLiteral(jlc, externalName, type.getType());
        return defineData(nextLiteralName(), classLiteral);
    }

    private String nextLiteralName() {
        return prefix+(this.literalCounter++);
    }

    private Data defineData(String name, Literal value) {
        Data d = heapSection.addData(null,name, value);
        d.setLinkage(Linkage.EXTERNAL);
        d.setAddrspace(1);
        return d;
    }

    private Data serializeObject(LoadedTypeDefinition concreteType, Object instance) {
        LiteralFactory lf = ctxt.getLiteralFactory();
        Layout.LayoutInfo objLayout = layout.getInstanceLayoutInfo(concreteType);
        CompoundType objType = objLayout.getCompoundType();
        HashMap<CompoundType.Member, Literal> memberMap = new HashMap<>();

        // Object header
        memberMap.put(objLayout.getMember(layout.getObjectTypeIdField()), lf.literalOf(concreteType.getTypeId()));

        // Instance fields
        Class<?> jClass = instance.getClass();
        LoadedTypeDefinition qClass = concreteType;
        while (qClass.hasSuperClass()) {
            for (Field jf : jClass.getDeclaredFields()) {
                FieldElement qf = qClass.findField(jf.getName());
                CompoundType.Member member = objLayout.getMember(qf);
                jf.setAccessible(true);
                if (qf != null && !qf.isStatic()) {
                    try {
                        if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.Z)) {
                            memberMap.put(member, lf.literalOf(jf.getBoolean(instance)));
                        } else if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.B)) {
                            memberMap.put(member, lf.literalOf(jf.getByte(instance)));
                        } else if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.S)) {
                            memberMap.put(member, lf.literalOf(jf.getShort(instance)));
                        } else if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.C)) {
                            memberMap.put(member, lf.literalOf(jf.getChar(instance)));
                        } else if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.I)) {
                            memberMap.put(member, lf.literalOf(jf.getInt(instance)));
                        } else if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.F)) {
                            memberMap.put(member, lf.literalOf(jf.getFloat(instance)));
                        } else if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.J)) {
                            memberMap.put(member, lf.literalOf(jf.getLong(instance)));
                        } else if (qf.getTypeDescriptor().equals(BaseTypeDescriptor.D)) {
                            memberMap.put(member, lf.literalOf(jf.getDouble(instance)));
                        } else {
                            Object fieldContents = jf.get(instance);
                            if (fieldContents == null) {
                                memberMap.put(member, lf.zeroInitializerLiteralOfType(member.getType()));
                            } else {
                                memberMap.put(member, dataToLiteral(lf, serializeObject(fieldContents), (WordType)member.getType()));
                            }
                        }
                    } catch (IllegalAccessException e) {
                        ctxt.error("Heap Serialization: denied access to field %s of %s", jf.getName(), jClass);
                    }
                }
            }
            qClass = qClass.getSuperClass();
            jClass = jClass.getSuperclass();
        }

        return defineData(nextLiteralName(), ctxt.getLiteralFactory().literalOf(objType, memberMap));
    }

    private Literal dataToLiteral(LiteralFactory lf, Data data, WordType toType) {
        return lf.bitcastLiteral(lf.literalOfSymbol(data.getName(), data.getType().getPointer().asCollected()), toType);
    }

    private CompoundType arrayLiteralType(FieldElement contents, int length) {
        LoadedTypeDefinition ltd = contents.getEnclosingType().load();
        String typeName = ltd.getInternalName() + "_"+length;
        CompoundType sizedArrayType = arrayTypes.get(typeName);
        if (sizedArrayType == null) {
            TypeSystem ts = ctxt.getTypeSystem();
            Layout.LayoutInfo objLayout = layout.getInstanceLayoutInfo(ltd);
            CompoundType arrayCT = objLayout.getCompoundType();

            CompoundType.Member contentMem = objLayout.getMember(contents);
            ArrayType sizedContentMem = ts.getArrayType(((ArrayType)contents.getType()).getElementType(), length);
            CompoundType.Member realContentMem = ts.getCompoundTypeMember(contentMem.getName(), sizedContentMem, contentMem.getOffset(), contentMem.getAlign());

            Supplier<List<CompoundType.Member>> thunk;
            if (contents.equals(CoreClasses.get(ctxt).getRefArrayContentField())) {
                thunk = () -> List.of(arrayCT.getMember(0), arrayCT.getMember(1), arrayCT.getMember(2), arrayCT.getMember(3), realContentMem);
            } else {
                thunk = () -> List.of(arrayCT.getMember(0), arrayCT.getMember(1), realContentMem);
            }

            sizedArrayType = ts.getCompoundType(CompoundType.Tag.STRUCT, typeName,arrayCT.getSize() + sizedContentMem.getSize(), arrayCT.getAlign(), thunk);
            arrayTypes.put(typeName, sizedArrayType);
        }
        return sizedArrayType;
    }

    private Literal javaPrimitiveArrayLiteral(FieldElement contentsField, int length, Literal data) {
        LiteralFactory lf = ctxt.getLiteralFactory();
        CompoundType literalCT = arrayLiteralType(contentsField, length);
        return lf.literalOf(literalCT, Map.of(
            literalCT.getMember(0), lf.literalOf(contentsField.getEnclosingType().load().getTypeId()),
            literalCT.getMember(1), lf.literalOf(length),
            literalCT.getMember(2), data
        ));
    }

    private Data serializeArray(byte[] array) {
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getByteArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getSignedInteger8Type(), array.length), array));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(boolean[] array) {
        List<Literal> elements = new ArrayList<>(array.length);
        for (boolean v: array) {
            elements.add(ctxt.getLiteralFactory().literalOf(v));
        }
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getBooleanArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getUnsignedInteger8Type(), array.length), elements));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(char[] array) {
        List<Literal> elements = new ArrayList<>(array.length);
        for (char v: array) {
            elements.add(ctxt.getLiteralFactory().literalOf(v));
        }
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getCharArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getUnsignedInteger16Type(), array.length), elements));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(short[] array) {
        List<Literal> elements = new ArrayList<>(array.length);
        for (short v: array) {
            elements.add(ctxt.getLiteralFactory().literalOf(v));
        }
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getShortArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getSignedInteger16Type(), array.length), elements));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(int[] array) {
        List<Literal> elements = new ArrayList<>(array.length);
        for (int v: array) {
            elements.add(ctxt.getLiteralFactory().literalOf(v));
        }
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getIntArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getSignedInteger32Type(), array.length), elements));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(float[] array) {
        List<Literal> elements = new ArrayList<>(array.length);
        for (float v: array) {
            elements.add(ctxt.getLiteralFactory().literalOf(v));
        }
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getFloatArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getFloat32Type(), array.length), elements));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(long[] array) {
        List<Literal> elements = new ArrayList<>(array.length);
        for (long v: array) {
            elements.add(ctxt.getLiteralFactory().literalOf(v));
        }
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getLongArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getSignedInteger64Type(), array.length), elements));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(double[] array) {
        List<Literal> elements = new ArrayList<>(array.length);
        for (double v: array) {
            elements.add(ctxt.getLiteralFactory().literalOf(v));
        }
        Literal al = javaPrimitiveArrayLiteral(CoreClasses.get(ctxt).getDoubleArrayContentField(), array.length,
            ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getFloat64Type(), array.length), elements));
        return defineData(nextLiteralName(), al);
    }

    private Data serializeArray(Object[] array) {
        LoadedTypeDefinition jlo = ctxt.getBootstrapClassContext().findDefinedType("java/lang/Object").load();
        Class<?> cls = array.getClass();
        int dimensions = 0;
        Class<?> leafElementClass = cls.getComponentType();
        while (leafElementClass.isArray()) {
            dimensions += 1;
            leafElementClass = leafElementClass.getComponentType();
        }
        LoadedTypeDefinition leafLTD = ctxt.getBootstrapClassContext().findDefinedType(leafElementClass.getName().replace('.', '/')).load();

        LiteralFactory lf = ctxt.getLiteralFactory();
        List<Literal> elements = new ArrayList<>(array.length);
        for (Object o: array) {
            Data elem = serializeObject(o);
            elements.add(lf.bitcastLiteral(lf.literalOfSymbol(elem.getName(), elem.getType().getPointer().asCollected()), jlo.getClassType().getReference()));
        }
        FieldElement contentsField = CoreClasses.get(ctxt).getRefArrayContentField();

        CompoundType literalCT = arrayLiteralType(contentsField, array.length);
        Literal al = lf.literalOf(literalCT, Map.of(
            literalCT.getMember(0), lf.literalOf(contentsField.getEnclosingType().load().getTypeId()),
            literalCT.getMember(1), lf.literalOf(array.length),
            literalCT.getMember(2), lf.literalOf(ctxt.getTypeSystem().getUnsignedInteger8Type(), dimensions),
            literalCT.getMember(3), lf.literalOf(leafLTD.getTypeId()),
            literalCT.getMember(4), ctxt.getLiteralFactory().literalOf(ctxt.getTypeSystem().getArrayType(jlo.getClassType().getReference(), array.length), elements)
        ));

        return defineData(nextLiteralName(), al);
    }
}
