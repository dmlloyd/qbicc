package cc.quarkus.qcc.type.definition.classfile;

import java.nio.ByteBuffer;

import cc.quarkus.qcc.graph.BasicBlock;
import cc.quarkus.qcc.graph.ClassType;
import cc.quarkus.qcc.graph.GraphFactory;
import cc.quarkus.qcc.graph.NodeHandle;
import cc.quarkus.qcc.graph.ParameterValue;
import cc.quarkus.qcc.graph.Type;
import cc.quarkus.qcc.graph.schedule.Schedule;
import cc.quarkus.qcc.interpreter.JavaVM;
import cc.quarkus.qcc.type.definition.DefinedTypeDefinition;
import cc.quarkus.qcc.type.definition.MethodBody;
import cc.quarkus.qcc.type.definition.MethodHandle;
import cc.quarkus.qcc.type.definition.ResolutionFailedException;
import cc.quarkus.qcc.type.definition.element.MethodElement;

/**
 *
 */
final class ExactMethodHandleImpl extends AbstractBufferBacked implements MethodHandle {
    private final ClassFileImpl classFile;
    private final int modifiers;
    private final int index;
    private final DefinedTypeDefinition enclosing;
    private final ByteBuffer byteCode;
    private final int maxStack;
    private final int maxLocals;
    private volatile MethodBody resolved;

    ExactMethodHandleImpl(final ClassFileImpl classFile, final int modifiers, final int index, final ByteBuffer codeAttr, final DefinedTypeDefinition enclosing) {
        super(codeAttr);
        this.classFile = classFile;
        this.modifiers = modifiers;
        this.index = index;
        this.enclosing = enclosing;
        int save = codeAttr.position();
        maxStack = codeAttr.getShort() & 0xffff;
        maxLocals = codeAttr.getShort() & 0xffff;
        int codeLen = codeAttr.getInt();
        int codeOffs = codeAttr.position();
        int lim = codeAttr.limit();
        codeAttr.limit(codeOffs + codeLen);
        byteCode = codeAttr.slice();
        codeAttr.limit(lim);
        codeAttr.position(codeOffs + codeLen);
        int exTableLen = codeAttr.getShort() & 0xffff;
        int exTableOffs = codeAttr.position();
        codeAttr.position(exTableOffs + (exTableLen << 3));
        int attrCnt = codeAttr.getShort() & 0xffff;
        codeAttr.position(save);
    }

    public int getModifiers() {
        return modifiers;
    }

    public int getParameterCount() {
        return classFile.resolveMethod(index, enclosing).getParameterCount();
    }

    public MethodBody getResolvedMethodBody() throws ResolutionFailedException {
        MethodBody resolved = this.resolved;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            resolved = this.resolved;
            if (resolved != null) {
                return resolved;
            }
            DefinedMethodBody dmb = new DefinedMethodBody(classFile, modifiers, index, getBackingBuffer().duplicate());
            VerifiedMethodBody vmb;
            MethodElement methodElement = classFile.resolveMethod(index, enclosing);
            int paramCount = methodElement.getParameterCount();
            if (classFile.compareVersion(50, 0) >= 0) {
                if (dmb.getStackMapTableOffs() != - 1) {
                    // verify by type checking
                    vmb = new TypeCheckedVerifiedMethodBody(dmb, methodElement);
                } else {
                    vmb = new SimpleMethodBody(dmb, methodElement);
                }
            } else {
                throw new UnsupportedOperationException("todo");
            }
            GraphFactory gf = JavaVM.requireCurrent().createGraphFactory();
            MethodParser methodParser = new MethodParser(vmb, gf);
            ParameterValue[] parameters = new ParameterValue[paramCount];
            int j = 0;
            if ((modifiers & ClassFile.ACC_STATIC) == 0) {
                // instance method or constructor
                ClassType type = enclosing.verify().getClassType();
                methodParser.setLocal(j++, gf.receiver(type));
                assert ! type.isClass2Type();
            }
            for (int i = 0; i < paramCount; i ++) {
                Type type = methodElement.getParameter(i).getType();
                methodParser.setLocal(j, parameters[i] = gf.parameter(type, i));
                j++;
                if (type.isClass2Type()) {
                    j++;
                }
            }
            NodeHandle entryBlockHandle = new NodeHandle();
            methodParser.processNewBlock(byteCode, entryBlockHandle);
            BasicBlock entryBlock = NodeHandle.getTargetOf(entryBlockHandle);
            Schedule schedule = Schedule.forMethod(entryBlock);
            return this.resolved = new ResolvedMethodBody(parameters, entryBlock, schedule);
        }
    }
}
