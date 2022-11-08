package org.qbicc.plugin.coreclasses;

import org.qbicc.context.CompilationContext;
import org.qbicc.graph.BasicBlockBuilder;
import org.qbicc.graph.Value;
import org.qbicc.graph.PointerValue;
import org.qbicc.graph.literal.LiteralFactory;
import org.qbicc.type.definition.element.FieldElement;

import static org.qbicc.graph.atomic.AccessModes.SinglePlain;

/**
 * Functions to initialize the core classes fields of object instances,
 * which are the same regardless of where the memory is allocated (heap, stack) and GC implementation.
 */
public class BasicHeaderInitializer {

    public static void initializeObjectHeader(final CompilationContext ctxt, final BasicBlockBuilder bb, final PointerValue handle, final Value typeId) {
        initializeObjectHeader(ctxt, bb, CoreClasses.get(ctxt), handle, typeId);
    }

    public static void initializeArrayHeader(final CompilationContext ctxt, final BasicBlockBuilder bb, final PointerValue handle, final Value typeId, final Value size) {
        initializeArrayHeader(ctxt, bb, CoreClasses.get(ctxt), handle, typeId, size);
    }

    public static void initializeRefArrayHeader(CompilationContext ctxt, BasicBlockBuilder bb, final PointerValue handle, Value elemTypeId, Value dimensions, final Value size) {
        initializeRefArrayHeader(ctxt, bb, CoreClasses.get(ctxt), handle, elemTypeId, dimensions, size);
    }


    private static void initializeObjectHeader(final CompilationContext ctxt, final BasicBlockBuilder bb, final CoreClasses coreClasses, final PointerValue handle, final Value typeId) {
        bb.store(bb.instanceFieldOf(handle, coreClasses.getObjectTypeIdField()), typeId, SinglePlain);
    }

    private static void initializeArrayHeader(final CompilationContext ctxt, final BasicBlockBuilder bb, final CoreClasses coreClasses, final PointerValue handle, final Value typeId, final Value size) {
        initializeObjectHeader(ctxt, bb, coreClasses, handle, typeId);
        bb.store(bb.instanceFieldOf(handle, coreClasses.getArrayLengthField()), size, SinglePlain);
    }

    private static void initializeRefArrayHeader(final CompilationContext ctxt, final BasicBlockBuilder bb, final CoreClasses coreClasses, final PointerValue handle, Value elemTypeId, Value dimensions, final Value size) {
        LiteralFactory lf = ctxt.getLiteralFactory();
        initializeArrayHeader(ctxt, bb, coreClasses, handle, lf.literalOfType(coreClasses.getReferenceArrayTypeDefinition().load().getClassType()), size);
        FieldElement dimsField = coreClasses.getRefArrayDimensionsField();
        bb.store(bb.instanceFieldOf(handle, dimsField), dimensions, SinglePlain);
        bb.store(bb.instanceFieldOf(handle, coreClasses.getRefArrayElementTypeIdField()), elemTypeId, SinglePlain);
    }
}
