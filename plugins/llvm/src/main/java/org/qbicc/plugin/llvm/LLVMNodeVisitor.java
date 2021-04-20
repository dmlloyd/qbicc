package org.qbicc.plugin.llvm;

import static org.qbicc.machine.llvm.Types.*;
import static org.qbicc.machine.llvm.Values.NULL;
import static org.qbicc.machine.llvm.Values.ZERO;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.qbicc.context.CompilationContext;
import org.qbicc.context.Location;
import org.qbicc.graph.Action;
import org.qbicc.graph.Add;
import org.qbicc.graph.AddressOf;
import org.qbicc.graph.And;
import org.qbicc.graph.BasicBlock;
import org.qbicc.graph.BitCast;
import org.qbicc.graph.BlockEntry;
import org.qbicc.graph.Cmp;
import org.qbicc.graph.CmpG;
import org.qbicc.graph.CmpL;
import org.qbicc.graph.Convert;
import org.qbicc.graph.Div;
import org.qbicc.graph.ElementOf;
import org.qbicc.graph.Extend;
import org.qbicc.graph.ExtractElement;
import org.qbicc.graph.ExtractMember;
import org.qbicc.graph.Fence;
import org.qbicc.graph.FunctionCall;
import org.qbicc.graph.GlobalVariable;
import org.qbicc.graph.Goto;
import org.qbicc.graph.If;
import org.qbicc.graph.IsEq;
import org.qbicc.graph.IsGe;
import org.qbicc.graph.IsGt;
import org.qbicc.graph.IsLe;
import org.qbicc.graph.IsLt;
import org.qbicc.graph.IsNe;
import org.qbicc.graph.Load;
import org.qbicc.graph.MemberOf;
import org.qbicc.graph.MemoryAtomicityMode;
import org.qbicc.graph.Mod;
import org.qbicc.graph.Multiply;
import org.qbicc.graph.CheckCast;
import org.qbicc.graph.Neg;
import org.qbicc.graph.Node;
import org.qbicc.graph.NodeVisitor;
import org.qbicc.graph.Or;
import org.qbicc.graph.ParameterValue;
import org.qbicc.graph.PhiValue;
import org.qbicc.graph.PointerHandle;
import org.qbicc.graph.Return;
import org.qbicc.graph.Select;
import org.qbicc.graph.Shl;
import org.qbicc.graph.Shr;
import org.qbicc.graph.StackAllocation;
import org.qbicc.graph.Store;
import org.qbicc.graph.Sub;
import org.qbicc.graph.Switch;
import org.qbicc.graph.Terminator;
import org.qbicc.graph.Triable;
import org.qbicc.graph.TriableVisitor;
import org.qbicc.graph.Truncate;
import org.qbicc.graph.Try;
import org.qbicc.graph.Unreachable;
import org.qbicc.graph.Unschedulable;
import org.qbicc.graph.Value;
import org.qbicc.graph.ValueHandle;
import org.qbicc.graph.ValueReturn;
import org.qbicc.graph.Xor;
import org.qbicc.graph.literal.SymbolLiteral;
import org.qbicc.graph.schedule.Schedule;
import org.qbicc.machine.llvm.FastMathFlag;
import org.qbicc.machine.llvm.FloatCondition;
import org.qbicc.machine.llvm.FunctionDefinition;
import org.qbicc.machine.llvm.IntCondition;
import org.qbicc.machine.llvm.LLBasicBlock;
import org.qbicc.machine.llvm.LLBuilder;
import org.qbicc.machine.llvm.LLValue;
import org.qbicc.machine.llvm.Module;
import org.qbicc.machine.llvm.Values;
import org.qbicc.machine.llvm.debuginfo.DILocation;
import org.qbicc.machine.llvm.op.Call;
import org.qbicc.machine.llvm.op.GetElementPtr;
import org.qbicc.machine.llvm.op.OrderingConstraint;
import org.qbicc.machine.llvm.op.Phi;
import org.qbicc.object.Function;
import org.qbicc.plugin.unwind.UnwindHelper;
import org.qbicc.type.BooleanType;
import org.qbicc.type.CompoundType;
import org.qbicc.type.FloatType;
import org.qbicc.type.FunctionType;
import org.qbicc.type.IntegerType;
import org.qbicc.type.PointerType;
import org.qbicc.type.SignedIntegerType;
import org.qbicc.type.Type;
import org.qbicc.type.ValueType;
import org.qbicc.type.VoidType;
import org.qbicc.type.definition.MethodBody;
import org.qbicc.type.definition.element.GlobalVariableElement;

final class LLVMNodeVisitor implements NodeVisitor<Void, LLValue, Void, Void, GetElementPtr> {
    final CompilationContext ctxt;
    final Module module;
    final LLVMModuleDebugInfo debugInfo;
    final LLValue topSubprogram;
    final LLVMModuleNodeVisitor moduleVisitor;
    final Schedule schedule;
    final Function functionObj;
    final FunctionDefinition func;
    final BasicBlock entryBlock;
    final Set<Action> visitedActions = new HashSet<>();
    final Map<Value, LLValue> mappedValues = new HashMap<>();
    final Map<BasicBlock, LLBasicBlock> mappedBlocks = new HashMap<>();
    final Map<BasicBlock, LLBasicBlock> mappedCatchBlocks = new HashMap<>();
    final MethodBody methodBody;
    final TriableVisitor<Try, Void> triableVisitor = new Triables();
    final LLBuilder builder;
    final Map<Node, LLValue> inlineLocations = new HashMap<>();

    private boolean personalityAdded;

    LLVMNodeVisitor(final CompilationContext ctxt, final Module module, final LLVMModuleDebugInfo debugInfo, final LLValue topSubprogram, final LLVMModuleNodeVisitor moduleVisitor, final Schedule schedule, final Function functionObj, final FunctionDefinition func) {
        this.ctxt = ctxt;
        this.module = module;
        this.debugInfo = debugInfo;
        this.topSubprogram = topSubprogram;
        this.moduleVisitor = moduleVisitor;
        this.schedule = schedule;
        this.functionObj = functionObj;
        this.func = func;
        this.methodBody = functionObj.getBody();
        entryBlock = methodBody.getEntryBlock();
        builder = LLBuilder.newBuilder(func.getRootBlock());
        personalityAdded = false;
    }

    // begin

    public void execute() {
        FunctionType funcType = functionObj.getType();
        int cnt = methodBody.getParameterCount();
        if (cnt != funcType.getParameterCount()) {
            throw new IllegalStateException("Mismatch between method body and function type parameter counts");
        }
        for (int i = 0; i < cnt; i ++) {
            ParameterValue value = functionObj.getBody().getParameterValue(i);
            ValueType      type = value.getType();
            org.qbicc.machine.llvm.Function.Parameter param = func.param(map(type)).name(value.getLabel() + value.getIndex());
            if(type instanceof IntegerType && ((IntegerType)type).getMinBits() < 32) {
                if(type instanceof SignedIntegerType) {
                    param.signExt();
                } else {
                    param.zeroExt();
                }
            } else if(type instanceof BooleanType) {
                param.zeroExt();
            }
            mappedValues.put(value, param.asValue());
        }
        ValueType retType = funcType.getReturnType();
        func.returns(map(retType));
        if(retType instanceof IntegerType && ((IntegerType)retType).getMinBits() < 32) {
            if(retType instanceof SignedIntegerType) {
                func.signExt();
            } else {
                func.zeroExt();
            }
        } else if(retType instanceof BooleanType) {
            func.zeroExt();
        }
        map(entryBlock);
    }

    // actions

    public Void visit(final Void param, final BlockEntry node) {
        // no operation
        return null;
    }

    public Void visit(final Void param, final Store node) {
        map(node.getDependency());
        ValueHandle valueHandle = node.getValueHandle();
        LLValue ptr;
        if (valueHandle instanceof PointerHandle) {
            // plain pointer; no GEP needed
            ptr = map(((PointerHandle) valueHandle).getPointerValue());
        } else {
            ptr = valueHandle.accept(this, null).asLocal();
        }
        org.qbicc.machine.llvm.op.Store storeInsn = builder.store(map(node.getValue().getType().getPointer()), map(node.getValue()), map(node.getValue().getType()), ptr);
        storeInsn.align(valueHandle.getValueType().getAlign());
        if (node.getMode() == MemoryAtomicityMode.SEQUENTIALLY_CONSISTENT) {
            storeInsn.atomic(OrderingConstraint.seq_cst);
        }
        return null;
    }

    public Void visit(final Void param, final Fence node) {
        map(node.getDependency());
        MemoryAtomicityMode mode = node.getAtomicityMode();
        switch (mode) {
            case ACQUIRE:
                builder.fence(OrderingConstraint.acquire);
                break;
            case RELEASE:
                builder.fence(OrderingConstraint.release);
                break;
            case ACQUIRE_RELEASE:
                builder.fence(OrderingConstraint.acq_rel);
                break;
            case SEQUENTIALLY_CONSISTENT:
                builder.fence(OrderingConstraint.seq_cst);
                break;
        }
        return null;
    }

    // terminators

    public Void visit(final Void param, final Goto node) {
        map(node.getDependency());
        builder.br(map(node.getResumeTarget()));
        return null;
    }

    public Void visit(final Void param, final If node) {
        map(node.getDependency());
        builder.br(map(node.getCondition()), map(node.getTrueBranch()), map(node.getFalseBranch()));
        return null;
    }

    public Void visit(final Void param, final Return node) {
        map(node.getDependency());
        builder.ret();
        return null;
    }

    public Void visit(final Void param, final Unreachable node) {
        map(node.getDependency());
        builder.unreachable();
        return null;
    }


    public Void visit(final Void param, final Switch node) {
        map(node.getDependency());
        org.qbicc.machine.llvm.op.Switch switchInst = builder.switch_(i32, map(node.getSwitchValue()), map(node.getDefaultTarget()));

        for (int i = 0; i < node.getNumberOfValues(); i++)
            switchInst.case_(Values.intConstant(node.getValueForIndex(i)), map(node.getTargetForIndex(i)));

        return null;
    }

    public Void visit(final Void param, final Try node) {
        node.getDelegateOperation().accept(triableVisitor, node);
        return null;
    }

    public Void visit(final Void param, final ValueReturn node) {
        map(node.getDependency());
        builder.ret(map(node.getReturnValue().getType()), map(node.getReturnValue()));
        return null;
    }

    // values

    boolean isFloating(Type type) {
        return type instanceof FloatType;
    }

    boolean isSigned(Type type) {
        return type instanceof SignedIntegerType;
    }

    boolean isPointer(Type type) {
        return type instanceof PointerType;
    }

    public LLValue visit(final Void param, final Add node) {
        ValueType type = node.getType();
        LLValue inputType = map(type);
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return isFloating(type) ?
               builder.fadd(inputType, llvmLeft, llvmRight).asLocal() :
               builder.add(inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final AddressOf node) {
        return node.getValueHandle().accept(this, null).asLocal();
    }

    public LLValue visit(final Void param, final And node) {
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return builder.and(map(node.getType()), llvmLeft, llvmRight).asLocal();
    }

    @Override
    public LLValue visit(Void param, Cmp node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());

        IntCondition lessThanCondition = isSigned(left.getType()) ? IntCondition.slt : IntCondition.ult;

        LLValue booleanType = map(ctxt.getTypeSystem().getBooleanType());
        LLValue integerType = map(ctxt.getTypeSystem().getSignedInteger32Type());
        return builder.select(
            booleanType,
            builder.icmp(lessThanCondition, inputType, llvmLeft, llvmRight).asLocal(),
            integerType,
            map(ctxt.getLiteralFactory().literalOf(-1)),
            builder.select(
                booleanType,
                builder.icmp(IntCondition.eq, inputType, llvmLeft, llvmRight).asLocal(),
                integerType,
                map(ctxt.getLiteralFactory().literalOf(0)),
                map(ctxt.getLiteralFactory().literalOf(1))
            ).asLocal()
        ).asLocal();
    }

    @Override
    public LLValue visit(Void param, CmpG node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());

        LLValue booleanType = map(ctxt.getTypeSystem().getBooleanType());
        LLValue integerType = map(ctxt.getTypeSystem().getSignedInteger32Type());
        return builder.select(
            booleanType,
            builder.fcmp(FloatCondition.ugt, inputType, llvmLeft, llvmRight).asLocal(),
            integerType,
            map(ctxt.getLiteralFactory().literalOf(1)),
            builder.select(
                booleanType,
                builder.fcmp(FloatCondition.ult, inputType, llvmLeft, llvmRight).withFlags(Set.of(FastMathFlag.nnan)).asLocal(),
                integerType,
                map(ctxt.getLiteralFactory().literalOf(-1)),
                map(ctxt.getLiteralFactory().literalOf(0))
            ).asLocal()
        ).asLocal();
    }

    @Override
    public LLValue visit(Void param, CmpL node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());

        LLValue booleanType = map(ctxt.getTypeSystem().getBooleanType());
        LLValue integerType = map(ctxt.getTypeSystem().getSignedInteger32Type());
        return builder.select(
            booleanType,
            builder.fcmp(FloatCondition.ult, inputType, llvmLeft, llvmRight).asLocal(),
            integerType,
            map(ctxt.getLiteralFactory().literalOf(-1)),
            builder.select(
                booleanType,
                builder.fcmp(FloatCondition.ugt, inputType, llvmLeft, llvmRight).withFlags(Set.of(FastMathFlag.nnan)).asLocal(),
                integerType,
                map(ctxt.getLiteralFactory().literalOf(1)),
                map(ctxt.getLiteralFactory().literalOf(0))
            ).asLocal()
        ).asLocal();
    }

    public LLValue visit(final Void param, final IsEq node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());
        return isFloating(node.getLeftInput().getType()) ?
            builder.fcmp(FloatCondition.oeq, inputType, llvmLeft, llvmRight).asLocal() :
            builder.icmp(IntCondition.eq, inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final IsNe node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());
        return isFloating(node.getLeftInput().getType()) ?
            builder.fcmp(FloatCondition.one, inputType, llvmLeft, llvmRight).asLocal() :
            builder.icmp(IntCondition.ne, inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final IsLt node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());
        ValueType valueType = node.getLeftInput().getType();
        return isFloating(valueType) ?
            builder.fcmp(FloatCondition.olt, inputType, llvmLeft, llvmRight).asLocal() :
            isSigned(valueType) ?
                builder.icmp(IntCondition.slt, inputType, llvmLeft, llvmRight).asLocal() :
                builder.icmp(IntCondition.ult, inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final IsLe node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());
        ValueType valueType = node.getLeftInput().getType();
        return isFloating(valueType) ?
            builder.fcmp(FloatCondition.ole, inputType, llvmLeft, llvmRight).asLocal() :
            isSigned(valueType) ?
                builder.icmp(IntCondition.sle, inputType, llvmLeft, llvmRight).asLocal() :
                builder.icmp(IntCondition.ule, inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final IsGt node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());
        ValueType valueType = node.getLeftInput().getType();
        return isFloating(valueType) ?
            builder.fcmp(FloatCondition.ogt, inputType, llvmLeft, llvmRight).asLocal() :
            isSigned(valueType) ?
                builder.icmp(IntCondition.sgt, inputType, llvmLeft, llvmRight).asLocal() :
                builder.icmp(IntCondition.ugt, inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final IsGe node) {
        Value left = node.getLeftInput();
        LLValue inputType = map(left.getType());
        LLValue llvmLeft = map(left);
        LLValue llvmRight = map(node.getRightInput());
        ValueType valueType = node.getLeftInput().getType();
        return isFloating(valueType) ?
            builder.fcmp(FloatCondition.oge, inputType, llvmLeft, llvmRight).asLocal() :
            isSigned(valueType) ?
                builder.icmp(IntCondition.sge, inputType, llvmLeft, llvmRight).asLocal() :
                builder.icmp(IntCondition.uge, inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Or node) {
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return builder.or(map(node.getType()), llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Xor node) {
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return builder.xor(map(node.getType()), llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Multiply node) {
        LLValue inputType = map(node.getType());
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return isFloating(node.getType()) ?
               builder.fmul(inputType, llvmLeft, llvmRight).asLocal() :
               builder.mul(inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Select node) {
        Value trueValue = node.getTrueValue();
        LLValue inputType = map(trueValue.getType());
        Value falseValue = node.getFalseValue();
        return builder.select(map(node.getCondition().getType()), map(node.getCondition()), inputType, map(trueValue), map(falseValue)).asLocal();
    }

    public LLValue visit(final Void param, final PhiValue node) {
        Phi phi = builder.phi(map(node.getType()));
        LLValue result = phi.asLocal();
        mappedValues.put(node, result);
        for (BasicBlock incomingBlock : node.getPinnedBlock().getIncoming()) {
            Terminator terminator = incomingBlock.getTerminator();
            Value v = node.getValueForInput(terminator);
            if (v != null) {
                // process dependencies
                phi.item(map(v), map(incomingBlock));
            }
        }
        return result;
    }

    public LLValue visit(final Void param, final Load node) {
        map(node.getDependency());
        ValueHandle valueHandle = node.getValueHandle();
        LLValue ptr;
        if (valueHandle instanceof PointerHandle) {
            // plain pointer; no GEP needed
            ptr = map(((PointerHandle) valueHandle).getPointerValue());
        } else {
            ptr = valueHandle.accept(this, null).asLocal();
        }
        org.qbicc.machine.llvm.op.Load loadInsn = builder.load(map(valueHandle.getValueType().getPointer()), map(valueHandle.getValueType()), ptr);
        loadInsn.align(node.getType().getAlign());
        if (node.getMode() == MemoryAtomicityMode.ACQUIRE) {
            loadInsn.atomic(OrderingConstraint.acquire);
        }
        return loadInsn.asLocal();
    }

    public LLValue visit(final Void param, final Neg node) {
        Type javaInputType = node.getInput().getType();
        LLValue inputType = map(javaInputType);
        LLValue llvmInput = map(node.getInput());
        return builder.fneg(inputType, llvmInput).asLocal();
    }

    public LLValue visit(final Void param, final Shr node) {
        LLValue inputType = map(node.getType());
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return isSigned(node.getType()) ?
               builder.ashr(inputType, llvmLeft, llvmRight).asLocal() :
               builder.lshr(inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Shl node) {
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return builder.shl(map(node.getType()), llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Sub node) {
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return isFloating(node.getLeftInput().getType())
            ? builder.fsub(map(node.getType()), llvmLeft, llvmRight).asLocal()
            : builder.sub(map(node.getType()), llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Div node) {
        LLValue inputType = map(node.getType());
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return isFloating(node.getType()) ?
               builder.fdiv(inputType, llvmLeft, llvmRight).asLocal() :
                    isSigned(node.getType()) ?
                      builder.sdiv(inputType, llvmLeft, llvmRight).asLocal() :
                      builder.udiv(inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final Mod node) {
        LLValue inputType = map(node.getType());
        LLValue llvmLeft = map(node.getLeftInput());
        LLValue llvmRight = map(node.getRightInput());
        return isFloating(node.getType()) ?
               builder.frem(inputType, llvmLeft, llvmRight).asLocal() :
                    isSigned(node.getType()) ?
                      builder.srem(inputType, llvmLeft, llvmRight).asLocal() :
                      builder.urem(inputType, llvmLeft, llvmRight).asLocal();
    }

    public LLValue visit(final Void param, final BitCast node) {
        ValueType javaInputType = node.getInput().getType();
        ValueType javaOutputType = node.getType();
        LLValue llvmInput = map(node.getInput());
        // skip bitcasts between same types
        if (LLVMModuleNodeVisitor.mapsToSameType(javaInputType, javaOutputType)) {
            return llvmInput;
        }
        LLValue inputType = map(javaInputType);
        LLValue outputType = map(javaOutputType);
        return builder.bitcast(inputType, llvmInput, outputType).asLocal();
    }

    public LLValue visit(final Void param, final Convert node) {
        Type javaInputType = node.getInput().getType();
        Type javaOutputType = node.getType();
        LLValue inputType = map(javaInputType);
        LLValue outputType = map(javaOutputType);
        LLValue llvmInput = map(node.getInput());
        if (inputType.equals(outputType)) {
            return llvmInput;
        }
        return isPointer(javaInputType) ?
                    builder.ptrtoint(inputType, llvmInput, outputType).asLocal() :
                    isPointer(javaOutputType) ?
                    builder.inttoptr(inputType, llvmInput, outputType).asLocal() :
                    isFloating(javaInputType) ?
                    isSigned(javaOutputType) ?
                    builder.fptosi(inputType, llvmInput, outputType).asLocal() :
                    builder.fptoui(inputType, llvmInput, outputType).asLocal() :
                    isSigned(javaInputType) ?
                    builder.sitofp(inputType, llvmInput, outputType).asLocal() :
                    builder.uitofp(inputType, llvmInput, outputType).asLocal();
    }

    public LLValue visit(final Void param, final Extend node) {
        Type javaInputType = node.getInput().getType();
        Type javaOutputType = node.getType();
        LLValue inputType = map(javaInputType);
        LLValue outputType = map(javaOutputType);
        LLValue llvmInput = map(node.getInput());
        return isFloating(javaInputType) ?
               builder.fpext(inputType, llvmInput, outputType).asLocal() :
                    isSigned(javaInputType) ?
                    builder.sext(inputType, llvmInput, outputType).asLocal() :
                    builder.zext(inputType, llvmInput, outputType).asLocal();
    }

    public LLValue visit(final Void param, final ExtractElement node) {
        LLValue arrayType = map(node.getArrayType());
        LLValue array = map(node.getArrayValue());
        LLValue index = map(node.getIndex());
        return builder.extractvalue(arrayType, array).arg(index).asLocal();
    }

    public LLValue visit(final Void param, final ExtractMember node) {
        LLValue compType = map(node.getCompoundType());
        LLValue comp = map(node.getCompoundValue());
        LLValue index = map(node.getCompoundType(), node.getMember());
        return builder.extractvalue(compType, comp).arg(index).asLocal();
    }

    public LLValue visit(final Void param, final CheckCast node) {
        return map(node.getInput());
    }

    public LLValue visit(final Void param, final Truncate node) {
        Type javaInputType = node.getInput().getType();
        Type javaOutputType = node.getType();
        LLValue inputType = map(javaInputType);
        LLValue outputType = map(javaOutputType);
        LLValue llvmInput = map(node.getInput());
        return isFloating(javaInputType) ?
               builder.ftrunc(inputType, llvmInput, outputType).asLocal() :
               builder.trunc(inputType, llvmInput, outputType).asLocal();
    }

    public LLValue visit(final Void param, final StackAllocation node) {
        LLValue pointeeType = map(node.getType().getPointeeType());
        LLValue countType = map(node.getCount().getType());
        LLValue count = map(node.getCount());
        LLValue alignment = map(node.getAlign());
        return builder.alloca(pointeeType).elements(countType, count).align(alignment).asLocal();
    }

    // calls

    public LLValue visit(final Void param, final FunctionCall node) {
        map(node.getDependency());
        FunctionType functionType = node.getFunctionType();
        List<Value> arguments = node.getArguments();
        LLValue llType = map(functionType);
        LLValue llTarget = map(node.getCallTarget());
        // two scans - once to populate the maps, and then once to emit the call in the right order
        for (int i = 0; i < arguments.size(); i++) {
            ValueType type = arguments.get(i).getType();
            map(type);
            map(arguments.get(i));
        }
        Call call = builder.call(llType, llTarget);
        for (int i = 0; i < arguments.size(); i++) {
            ValueType type = arguments.get(i).getType();
            Call.Argument arg = call.arg(map(type), map(arguments.get(i)));
            if (type instanceof IntegerType && ((IntegerType)type).getMinBits() < 32) {
                if(type instanceof SignedIntegerType) {
                    arg.signExt();
                } else {
                    arg.zeroExt();
                }
            } else if (type instanceof BooleanType) {
                arg.zeroExt();
            }
        }
        ValueType retType = functionType.getReturnType();
        if(retType instanceof IntegerType && ((IntegerType)retType).getMinBits() < 32) {
            if(retType instanceof SignedIntegerType) {
                call.signExt();
            } else {
                call.zeroExt();
            }
        } else if(retType instanceof BooleanType) {
            call.zeroExt();
        }
        return call.asLocal();
    }

    public LLValue visit(final Try try_, final FunctionCall node) {
        map(node.getDependency());
        FunctionType functionType = node.getFunctionType();
        List<Value> arguments = node.getArguments();
        LLValue llType = map(functionType);
        LLValue llTarget = map(node.getCallTarget());
        // two scans - once to populate the maps, and then once to emit the call in the right order
        for (int i = 0; i < arguments.size(); i++) {
            map(functionType.getParameterType(i));
            map(arguments.get(i));
        }
        Call call = builder.invoke(llType, llTarget, map(try_.getResumeTarget()), mapCatch(try_.getExceptionHandler()));
        for (int i = 0; i < arguments.size(); i++) {
            ValueType type = functionType.getParameterType(i);
            Call.Argument arg = call.arg(map(type), map(arguments.get(i)));
            if(type instanceof IntegerType && ((IntegerType)type).getMinBits() < 32) {
                if(type instanceof SignedIntegerType) {
                    arg.signExt();
                } else {
                    arg.zeroExt();
                }
            } else if (type instanceof BooleanType) {
                arg.zeroExt();
            }
        }
        ValueType retType = functionType.getReturnType();
        if(retType instanceof IntegerType && ((IntegerType)retType).getMinBits() < 32) {
            if(retType instanceof SignedIntegerType) {
                call.signExt();
            } else {
                call.zeroExt();
            }
        } else if(retType instanceof BooleanType) {
            call.zeroExt();
        }

        if (!personalityAdded) {
            Function personalityFunction = ctxt.getExactFunction(UnwindHelper.get(ctxt).getPersonalityMethod());
            SymbolLiteral literal = ctxt.getLiteralFactory().literalOfSymbol(personalityFunction.getLiteral().getName(), personalityFunction.getType().getPointer());
            // clang generates the personality argument like this (by casting the function to i8* using bitcast):
            //      define dso_local void @_Z7catchitv() #0 personality i8* bitcast (i32 (...)* @__gxx_personality_v0 to i8*) {
            // We can also generate it this way using following construct:
            //      func.personality(Values.bitcastConstant(map(literal), map(literal.getType()), ptrTo(i8)), ptrTo(i8));
            // but directly specifying the function works as well.
            func.personality(map(literal), map(literal.getType()));
            func.unwindTable();
            personalityAdded = true;
        }
        return call.asLocal();
    }

    // GEP

    @Override
    public GetElementPtr visit(Void param, ElementOf node) {
        ValueHandle nextHandle = node.getValueHandle();
        LLValue index = map(node.getIndex());
        LLValue indexType = map(node.getIndex().getType());
        if (nextHandle instanceof PointerHandle) {
            PointerHandle ptrHandle = (PointerHandle) nextHandle;
            // special case: element-of-pointer
            return gep(map(ptrHandle.getPointerValue()), ptrHandle.getPointerType(), ptrHandle).arg(false, indexType, index);
        }
        return nextHandle.accept(this, param).arg(false, indexType, index);
    }

    @Override
    public GetElementPtr visit(Void param, MemberOf node) {
        LLValue index = map(node.getStructType(), node.getMember());
        return node.getValueHandle().accept(this, param).arg(false, i32, index);
    }

    @Override
    public GetElementPtr visit(Void param, GlobalVariable node) {
        GlobalVariableElement gv = node.getVariableElement();
        return gep(Values.global(gv.getName()), node.getValueType().getPointer(), node).arg(false, i32, ZERO);
    }

    @Override
    public GetElementPtr visit(Void param, PointerHandle node) {
        return gep(map(node.getPointerValue()), node.getPointerType(), node).arg(false, i32, ZERO);
    }

    GetElementPtr gep(LLValue ptr, ValueType pointerType, ValueHandle handle) {
        ValueType valueType = handle.getValueType();
        if (valueType instanceof VoidType) {
            return builder.getelementptr(i8, map(pointerType), ptr);
        } else {
            return builder.getelementptr(map(valueType), map(pointerType), ptr);
        }
    }

    // unknown node catch-all methods

    public LLValue visitUnknown(final Void param, final Value node) {
        return node.accept(moduleVisitor, null);
    }

    public Void visitUnknown(final Void param, final Action node) {
        ctxt.error(functionObj.getOriginalElement(), node, "llvm: Unrecognized action %s", node.getClass());
        return null;
    }

    public Void visitUnknown(final Void param, final Terminator node) {
        ctxt.error(functionObj.getOriginalElement(), node, "llvm: Unrecognized terminator %s", node.getClass());
        return null;
    }

    public GetElementPtr visitUnknown(Void param, ValueHandle node) {
        throw new IllegalStateException("Unexpected handle " + node);
    }

    // mapping

    private DILocation createDbgLocation(final Node node) {
        LLValue inlinedAt = dbgInlinedCallSite(node.getCallSite());

        if (inlinedAt == null && node.getElement() != functionObj.getOriginalElement()) {
            ctxt.error(Location.builder().setNode(node).build(), "LLVM: Node is not part of the root function, but has no call site");
        }

        LLValue scope = (topSubprogram != null && inlinedAt == null)
                ? topSubprogram
                : debugInfo.getDebugInfoForFunction(node.getElement()).getScope(node.getBytecodeIndex());

        return module.diLocation(node.getSourceLine(), 0, scope, inlinedAt);
    }

    private LLValue dbgInlinedCallSite(final Node node) {
        if (node == null) {
            return null;
        }

        LLValue diLocation = inlineLocations.get(node);

        if (diLocation == null) {
            diLocation = createDbgLocation(node).distinct(true).asRef();
            inlineLocations.put(node, diLocation);
        }

        return diLocation;
    }

    private LLValue dbg(final Node node) {
        if (node.getElement() == null || debugInfo == null) {
            return null;
        }

        return createDbgLocation(node).asRef();
    }

    private LLBasicBlock map(BasicBlock block) {
        LLBasicBlock mapped = mappedBlocks.get(block);
        if (mapped != null) {
            return mapped;
        }
        mapped = func.createBlock();
        mappedBlocks.put(block, mapped);

        LLValue oldBuilderDebugLocation = builder.setDebugLocation(dbg(block.getTerminator()));
        LLBasicBlock oldBuilderBlock = builder.moveToBlock(mapped);

        block.getTerminator().accept(this, null);

        builder.setDebugLocation(oldBuilderDebugLocation);
        builder.moveToBlock(oldBuilderBlock);

        return mapped;
    }

    private LLBasicBlock mapCatch(BasicBlock block) {
        LLBasicBlock mapped = mappedCatchBlocks.get(block);
        if (mapped != null) {
            return mapped;
        }

        mapped = func.createBlock();

        // TODO Is it correct to use the call's debug info here?
        LLBasicBlock oldBuilderBlock = builder.moveToBlock(mapped);

        builder.landingpad(structType().member(ptrTo(i8)).member(i32)).catch_(ptrTo(i8), NULL);
        LLBasicBlock handler = map(block);
        builder.br(handler);

        builder.moveToBlock(oldBuilderBlock);
        return mapped;
    }

    private LLValue map(Type type) {
        return moduleVisitor.map(type);
    }

    private void map(Action action) {
        if (visitedActions.add(action)) {
            LLValue oldBuilderDebugLocation = builder.setDebugLocation(dbg(action));
            LLBasicBlock oldBuilderBlock = builder.moveToBlock(map(schedule.getBlockForNode(action)));

            action.accept(this, null);

            builder.setDebugLocation(oldBuilderDebugLocation);
            builder.moveToBlock(oldBuilderBlock);
        }
    }

    private LLValue map(Value value) {
        LLValue mapped = mappedValues.get(value);
        if (mapped != null) {
            return mapped;
        }

        if (value instanceof Unschedulable) {
            return value.accept(this, null);
        }

        LLBasicBlock oldBuilderBlock = builder.moveToBlock(map(schedule.getBlockForNode(value)));

        mapped = mappedValues.get(value);
        if (mapped == null) {
            LLValue oldBuilderDebugLocation = builder.setDebugLocation(dbg(value));
            mapped = value.accept(this, null);
            mappedValues.put(value, mapped);
            builder.setDebugLocation(oldBuilderDebugLocation);
        }

        builder.moveToBlock(oldBuilderBlock);
        return mapped;
    }

    private void map(Node unknown) {
        if (unknown instanceof Action) {
            map((Action) unknown);
        } else if (unknown instanceof Value) {
            map((Value) unknown);
        } else {
            throw new IllegalStateException();
        }
    }

    private LLValue map(CompoundType compoundType, CompoundType.Member member) {
        return moduleVisitor.map(compoundType, member);
    }

    class Triables implements TriableVisitor<Try, Void> {
        public Void visitUnknown(final Try param, final Triable node) {
            return LLVMNodeVisitor.this.visitUnknown(null, param);
        }

        public Void visit(final Try param, final FunctionCall node) {
            mappedValues.put(node, LLVMNodeVisitor.this.visit(param, node));
            return null;
        }
    }
}