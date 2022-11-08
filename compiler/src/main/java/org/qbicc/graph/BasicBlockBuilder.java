package org.qbicc.graph;

import static org.qbicc.graph.atomic.AccessModes.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.smallrye.common.constraint.Assert;
import org.qbicc.context.ClassContext;
import org.qbicc.context.CompilationContext;
import org.qbicc.context.Locatable;
import org.qbicc.context.Location;
import org.qbicc.graph.atomic.GlobalAccessMode;
import org.qbicc.graph.atomic.ReadAccessMode;
import org.qbicc.graph.atomic.WriteAccessMode;
import org.qbicc.graph.literal.Literal;
import org.qbicc.graph.literal.LiteralFactory;
import org.qbicc.type.ArrayObjectType;
import org.qbicc.type.ClassObjectType;
import org.qbicc.type.CompoundType;
import org.qbicc.type.FunctionType;
import org.qbicc.type.InstanceMethodType;
import org.qbicc.type.InterfaceObjectType;
import org.qbicc.type.NullableType;
import org.qbicc.type.ObjectType;
import org.qbicc.type.PhysicalObjectType;
import org.qbicc.type.PrimitiveArrayObjectType;
import org.qbicc.type.ReferenceArrayObjectType;
import org.qbicc.type.StaticMethodType;
import org.qbicc.type.TypeSystem;
import org.qbicc.type.TypeType;
import org.qbicc.type.ValueType;
import org.qbicc.type.WordType;
import org.qbicc.type.definition.classfile.ClassFile;
import org.qbicc.type.definition.element.ConstructorElement;
import org.qbicc.type.definition.element.ExecutableElement;
import org.qbicc.type.definition.element.FieldElement;
import org.qbicc.type.definition.element.FunctionElement;
import org.qbicc.type.definition.element.GlobalVariableElement;
import org.qbicc.type.definition.element.InitializerElement;
import org.qbicc.type.definition.element.InstanceMethodElement;
import org.qbicc.type.definition.element.LocalVariableElement;
import org.qbicc.type.definition.element.MethodElement;
import org.qbicc.type.descriptor.ArrayTypeDescriptor;
import org.qbicc.type.descriptor.ClassTypeDescriptor;
import org.qbicc.type.descriptor.MethodDescriptor;
import org.qbicc.type.descriptor.TypeDescriptor;

/**
 * A program graph builder, which builds each basic block in succession and wires them together.
 */
public interface BasicBlockBuilder extends Locatable {
    // parameters

    /**
     * Add an input parameter to the given block.
     * Any control flow transfer to this block <em>must</em> provide an argument value for each parameter.
     *
     * @param owner the label of the block to which the parameter should belong (must not be {@code null})
     * @param slot the parameter name (must not be {@code null})
     * @param type the parameter type (must not be {@code null})
     * @param nullable {@code true} if the reference- or pointer-typed parameter can be {@code null}, or {@code false} otherwise
     * @return the parameter value (not {@code null})
     * @throws IllegalArgumentException if the parameter already has a different definition, or {@code type} is not nullable but {@code nullable} was set
     */
    BlockParameter addParam(BlockLabel owner, Slot slot, ValueType type, boolean nullable);

    /**
     * Add a nullable input parameter to the given block.
     * Any control flow transfer to this block <em>must</em> provide an argument value for each parameter.
     *
     * @param owner the label of the block to which the parameter should belong (must not be {@code null})
     * @param slot the parameter name (must not be {@code null})
     * @param type the parameter type (must not be {@code null})
     * @return the parameter value (not {@code null})
     * @throws IllegalArgumentException if the parameter already has a different definition
     */
    default BlockParameter addParam(BlockLabel owner, Slot slot, ValueType type) {
        return addParam(owner, slot, type, type instanceof NullableType);
    }

    /**
     * Get the pre-established block parameter for the given slot.
     *
     * @param owner the owning block label (must not be {@code null})
     * @param slot the slot (must not be {@code null})
     * @return the parameter value (not {@code null})
     * @throws NoSuchElementException if no parameter was established for the given slot
     */
    BlockParameter getParam(BlockLabel owner, Slot slot) throws NoSuchElementException;

    // context

    /**
     * Get the class context of the current element.
     *
     * @return the class context (not {@code null})
     */
    default ClassContext getCurrentClassContext() {
        return getCurrentElement().getEnclosingType().getContext();
    }

    /**
     * Get the current compilation context.
     *
     * @return the compilation context (not {@code null})
     */
    default CompilationContext getContext() {
        return getCurrentClassContext().getCompilationContext();
    }

    /**
     * Get the literal factory.
     *
     * @return the literal factory (not {@code null})
     */
    default LiteralFactory getLiteralFactory() {
        return getCurrentClassContext().getLiteralFactory();
    }

    /**
     * Get the type system.
     *
     * @return the type system (not {@code null})
     */
    default TypeSystem getTypeSystem() {
        return getCurrentClassContext().getTypeSystem();
    }

    /**
     * Get the first builder in this chain.
     *
     * @return the first builder ((must not be {@code null}))
     */
    BasicBlockBuilder getFirstBuilder();

    /**
     * Set the first builder in this chain.
     *
     * @param first the first builder (must not be {@code null})
     */
    void setFirstBuilder(BasicBlockBuilder first);

    /**
     * Get the element currently being built.
     *
     * @return the element currently being built
     */
    ExecutableElement getCurrentElement();

    /**
     * Get the root element currently being built.
     *
     * @return the root element currently being built
     */
    ExecutableElement getRootElement();

    /**
     * Set the element currently being built, returning the previously-set element.  Used for inlining.
     *
     * @param element the new current element
     * @return the previously set current element
     */
    ExecutableElement setCurrentElement(ExecutableElement element);

    /**
     * Get the currently set call site node.  Used for inlining.
     *
     * @return the currently set call site node
     */
    Node getCallSite();

    /**
     * Set the call site node.  Used for inlining.
     *
     * @param callSite the call site node
     * @return the previously set call site node
     */
    Node setCallSite(Node callSite);

    /**
     * Get a location for the element currently being built, suitable for passing to diagnostics.
     *
     * @return the location
     */
    Location getLocation();

    /**
     * Set the line number to use for subsequently built nodes.  Use {@code 0} for no line number.
     *
     * @param newLineNumber the line number
     * @return the previously set line number
     */
    int setLineNumber(int newLineNumber);

    /**
     * Set the bytecode index to use for subsequently built nodes.  Use {@code -1} for no bytecode index.
     *
     * @param newBytecodeIndex the bytecode index
     * @return the previously set bytecode index
     */
    int setBytecodeIndex(int newBytecodeIndex);

    /**
     * Get the current bytecode index.
     *
     * @return the current bytecode index
     */
    int getBytecodeIndex();

    /**
     * Indicate that all construction is complete.
     */
    void finish();

    /**
     * Get the first (entry) block of the subprogram.  If the first block has not yet been terminated, an exception
     * is thrown.
     *
     * @return the first (entry) block (not {@code null})
     * @throws IllegalStateException if the first block has not yet been terminated
     */
    BasicBlock getFirstBlock() throws IllegalStateException;

    BlockLabel getEntryLabel() throws IllegalStateException;

    // values

    /**
     * Get the empty {@code void} literal.
     *
     * @return the empty {@code void} literal (not {@code null})
     */
    default Literal emptyVoid() {
        return getLiteralFactory().zeroInitializerLiteralOfType(getTypeSystem().getVoidType());
    }

    Value offsetOfField(FieldElement fieldElement);

    // sub-value extraction

    Value extractElement(Value array, Value index);

    Value extractMember(Value compound, CompoundType.Member member);

    Value extractInstanceField(Value valueObj, TypeDescriptor owner, String name, TypeDescriptor type);

    Value extractInstanceField(Value valueObj, FieldElement field);

    Value insertElement(Value array, Value index, Value value);

    Value insertMember(Value compound, CompoundType.Member member, Value value);

    // debug

    Node declareDebugAddress(LocalVariableElement variable, Value address);

    Node setDebugValue(LocalVariableElement variable, Value value);

    // ternary

    Value select(Value condition, Value trueValue, Value falseValue);

    // binary

    Value add(Value v1, Value v2);

    Value multiply(Value v1, Value v2);

    Value and(Value v1, Value v2);

    Value or(Value v1, Value v2);

    Value xor(Value v1, Value v2); // also used for ones-complement

    Value isEq(Value v1, Value v2);

    Value isNe(Value v1, Value v2);

    Value shr(Value v1, Value v2);

    Value shl(Value v1, Value v2);

    Value sub(Value v1, Value v2); // also used for twos-complement

    Value divide(Value v1, Value v2);

    Value remainder(Value v1, Value v2);

    Value min(Value v1, Value v2);

    Value max(Value v1, Value v2);

    Value isLt(Value v1, Value v2);

    Value isGt(Value v1, Value v2);

    Value isLe(Value v1, Value v2);

    Value isGe(Value v1, Value v2);

    Value rol(Value v1, Value v2);

    Value ror(Value v1, Value v2);

    Value cmp(Value v1, Value v2);

    Value cmpG(Value v1, Value v2);

    Value cmpL(Value v1, Value v2);

    // unary

    Value notNull(Value v);

    Value negate(Value v); // neg is only needed for FP; ints should use 0-n

    Value complement(Value v);

    Value byteSwap(Value v);

    Value bitReverse(Value v);

    Value countLeadingZeros(Value v);

    Value countTrailingZeros(Value v);

    Value populationCount(Value v);

    /**
     * Get the {@link Class} object for the given type ID value, whose type must be a {@link TypeType} with
     * an upper bound which is a {@link ObjectType}.
     *
     * @param typeId the type ID value
     * @param dims the dimensions if type ID is for reference array, 0 otherwise
     * @return a non-nullable narrowed reference to the class object for the given type ID value
     */
    Value classOf(Value typeId, Value dims);

    default Value classOf(Value typeId) {
        return classOf(typeId, getLiteralFactory().literalOf(getTypeSystem().getUnsignedInteger8Type(), 0));
    }

    default Value classOf(ClassObjectType cot) {
        return classOf(getLiteralFactory().literalOfType(cot));
    }

    default Value classOf(InterfaceObjectType iot) {
        return classOf(getLiteralFactory().literalOfType(iot));
    }

    default Value classOf(PrimitiveArrayObjectType aot) {
        return classOf(getLiteralFactory().literalOfType(aot));
    }

    default Value classOf(ReferenceArrayObjectType aot) {
        return classOf(getLiteralFactory().literalOfType(aot.getLeafElementType()), getLiteralFactory().literalOf(aot.getDimensionCount()));
    }

    default Value classOf(ArrayObjectType aot) {
        return aot instanceof PrimitiveArrayObjectType pa ? classOf(pa) : classOf((ReferenceArrayObjectType) aot);
    }

    default Value classOf(PhysicalObjectType pot) {
        return pot instanceof ClassObjectType cot ? classOf(cot) : classOf((ArrayObjectType) pot);
    }

    default Value classOf(ObjectType ot) {
        return ot instanceof PhysicalObjectType pot ? classOf(pot) : classOf((InterfaceObjectType) ot);
    }

    Value truncate(Value value, WordType toType);

    Value extend(Value value, WordType toType);

    Value bitCast(Value value, WordType toType);

    Value valueConvert(Value value, WordType toType);

    Value instanceOf(Value input, ObjectType expectedType, int expectedDimensions);

    default Value instanceOf(Value input, ObjectType expectedType) {
        return instanceOf(input, expectedType, 0);
    }

    Value instanceOf(Value input, TypeDescriptor desc);

    Value checkcast(Value value, Value toType, Value toDimensions, CheckCast.CastType kind, ObjectType expectedType);

    Value checkcast(Value value, TypeDescriptor desc);

    /**
     * Wrap the pointer value with a member selection node, which can later be unwrapped.
     *
     * @param handle the pointer value to wrap (must not be {@code null})
     * @return the member selection node (not {@code null})
     * @see MemberSelector
     */
    Value selectMember(PointerValue handle);

    // pointer values

    Value interfaceMethodLookup(TypeDescriptor owner, String name, MethodDescriptor descriptor, Value instanceTypeId);

    Value interfaceMethodLookup(InstanceMethodElement lookupMethod, Value instanceTypeId);

    Value virtualMethodLookup(TypeDescriptor owner, String name, MethodDescriptor descriptor, Value instanceTypeId);

    Value virtualMethodLookup(InstanceMethodElement lookupMethod, Value instanceTypeId);

    /**
     * A handle to the current thread.  The handle's value type is always assignable to a reference to {@code java.lang.Thread}.
     * The handle is usually not writable, except in a (typically exported) function.
     *
     * @return the handle (not {@code null})
     */
    PointerValue currentThread();

    PointerValue lengthOf(PointerValue arrayHandle);

    PointerValue memberOf(PointerValue structHandle, CompoundType.Member member);

    PointerValue elementOf(PointerValue array, Value index);

    PointerValue unsafeHandle(PointerValue base, Value offset, ValueType outputType);

    PointerValue offsetPointer(Value pointer, Value offsetValue);

    /**
     * Convenience method to construct a pointer handle with a zero offset.
     * <b>Do not override this method.</b>
     *
     * @param pointer the pointer value (must not be {@code null})
     * @return the zero-offset pointer handle (must not be {@code null})
     */
    default PointerValue pointerValueOf(Value pointer) {
        return pointer instanceof PointerValue pv ? pv : offsetPointer(pointer, getCurrentElement().getEnclosingType().getContext().getLiteralFactory().literalOf(0));
    }

    PointerValue decodeReference(Value reference);

    PointerValue instanceFieldOf(PointerValue instance, FieldElement field);

    PointerValue instanceFieldOf(PointerValue instance, TypeDescriptor owner, String name, TypeDescriptor type);

    PointerValue staticField(FieldElement field);

    PointerValue staticField(TypeDescriptor owner, String name, TypeDescriptor type);

    PointerValue globalVariable(GlobalVariableElement variable);

    PointerValue localVariable(LocalVariableElement variable);

    PointerValue exactMethodOf(Value instance, MethodElement method, MethodDescriptor callSiteDescriptor, InstanceMethodType callSiteType);

    /**
     * Convenience method to construct a method handle whose descriptor and type match the element's descriptor and type.
     * <b>Do not override this method.</b>
     *
     * @param instance the object instance (must not be {@code null})
     * @param method the method element (must not be {@code null})
     * @return the value handle (not {@code null})
     */
    default PointerValue exactMethodOf(Value instance, MethodElement method) {
        return exactMethodOf(instance, method, method.getDescriptor(), (InstanceMethodType) method.getType());
    }

    PointerValue exactMethodOf(Value instance, TypeDescriptor owner, String name, MethodDescriptor descriptor);

    PointerValue virtualMethodOf(Value instance, MethodElement method, MethodDescriptor callSiteDescriptor, InstanceMethodType callSiteType);

    /**
     * Convenience method to construct a method handle whose descriptor and type match the element's descriptor and type.
     * <b>Do not override this method.</b>
     *
     * @param instance the object instance (must not be {@code null})
     * @param method the method element (must not be {@code null})
     * @return the value handle (not {@code null})
     */
    default PointerValue virtualMethodOf(Value instance, MethodElement method) {
        return virtualMethodOf(instance, method, method.getDescriptor(), (InstanceMethodType) method.getType());
    }

    PointerValue virtualMethodOf(Value instance, TypeDescriptor owner, String name, MethodDescriptor descriptor);

    PointerValue interfaceMethodOf(Value instance, MethodElement method, MethodDescriptor callSiteDescriptor, InstanceMethodType callSiteType);

    /**
     * Convenience method to construct a method handle whose descriptor and type match the element's descriptor and type.
     * <b>Do not override this method.</b>
     *
     * @param instance the object instance (must not be {@code null})
     * @param method the method element (must not be {@code null})
     * @return the value handle (not {@code null})
     */
    default PointerValue interfaceMethodOf(Value instance, MethodElement method) {
        return interfaceMethodOf(instance, method, method.getDescriptor(), (InstanceMethodType) method.getType());
    }

    PointerValue interfaceMethodOf(Value instance, TypeDescriptor owner, String name, MethodDescriptor descriptor);

    PointerValue staticMethod(MethodElement method, MethodDescriptor callSiteDescriptor, StaticMethodType callSiteType);

    /**
     * Convenience method to construct a method handle whose descriptor and type match the element's descriptor and type.
     * <b>Do not override this method.</b>
     *
     * @param method the method element (must not be {@code null})
     * @return the value handle (not {@code null})
     */
    default PointerValue staticMethod(MethodElement method) {
        return staticMethod(method, method.getDescriptor(), (StaticMethodType) method.getType());
    }

    PointerValue staticMethod(TypeDescriptor owner, String name, MethodDescriptor descriptor);

    PointerValue constructorOf(Value instance, ConstructorElement constructor, MethodDescriptor callSiteDescriptor, InstanceMethodType callSiteType);

    /**
     * Convenience method to construct a constructor handle whose descriptor and type match the element's descriptor and type.
     * <b>Do not override this method.</b>
     *
     * @param instance the object instance (must not be {@code null})
     * @param constructor the constructor element (must not be {@code null})
     * @return the value handle (not {@code null})
     */
    default PointerValue constructorOf(Value instance, ConstructorElement constructor) {
        return constructorOf(instance, constructor, constructor.getDescriptor(), constructor.getType());
    }

    PointerValue constructorOf(Value instance, TypeDescriptor owner, MethodDescriptor descriptor);

    PointerValue functionOf(FunctionElement function);

    PointerValue asm(String instruction, String constraints, Set<AsmHandle.Flag> flags, FunctionType type);

    // memory

    Value addressOf(PointerValue handle);

    /**
     * Get a value that is a reference to the given value handle. If the handle's type is not an allocated
     * object, an exception is thrown.
     *
     * @param handle the value handle (must not be {@code null})
     * @return the reference value (not {@code null})
     * @throws IllegalArgumentException if the value handle does not refer to something that can be referenced
     */
    Value encodeReference(PointerValue handle) throws IllegalArgumentException;

    Value stackAllocate(ValueType type, Value count, Value align);

    Value new_(ClassObjectType type, Value typeId, Value size, Value align);

    Value new_(ClassTypeDescriptor desc);

    Value newArray(PrimitiveArrayObjectType arrayType, Value size);

    Value newArray(ArrayTypeDescriptor desc, Value size);

    Value newReferenceArray(ReferenceArrayObjectType arrayType, Value elemTypeId, Value dimensions, Value size);

    Value multiNewArray(ArrayObjectType arrayType, List<Value> dimensions);

    Value multiNewArray(ArrayTypeDescriptor desc, List<Value> dimensions);

    default Value load(PointerValue handle) {
        return load(handle, SinglePlain);
    }

    Value load(PointerValue handle, ReadAccessMode mode);

    Value readModifyWrite(PointerValue target, ReadModifyWrite.Op op, Value update, ReadAccessMode readMode, WriteAccessMode writeMode);

    Value cmpAndSwap(PointerValue target, Value expect, Value update, ReadAccessMode readMode, WriteAccessMode writeMode, CmpAndSwap.Strength strength);

    Value vaArg(Value vaList, ValueType type);

    default Node store(PointerValue handle, Value value) {
        return store(handle, value, SinglePlain);
    }

    Node store(PointerValue handle, Value value, WriteAccessMode mode);

    Node initCheck(InitializerElement initializer, Value initThunk);

    Node fence(GlobalAccessMode fenceType);

    Node monitorEnter(Value obj);

    Node monitorExit(Value obj);

    // method invocation

    /**
     * Call an invocation target with normal program-order dependency behavior.  The target either does not throw an exception or
     * the current block does not catch exceptions.
     *
     * @param target the invocation target handle (must not be {@code null})
     * @param arguments the invocation arguments (must not be {@code null})
     * @return the invocation result (not {@code null})
     * @see Call
     */
    Value call(PointerValue target, List<Value> arguments);

    /**
     * Call an invocation target that does not have side-effects (and does not have any program-order dependency relationships).
     * The target either does not throw an exception or the current block does not catch exceptions.
     *
     * @param target the invocation target handle (must not be {@code null})
     * @param arguments the invocation arguments (must not be {@code null})
     * @return the invocation result (not {@code null})
     * @see CallNoSideEffects
     */
    Value callNoSideEffects(PointerValue target, List<Value> arguments);

    // misc

    /**
     * No operation.  The returned node is not guaranteed to be unique or of any particular type.
     *
     * @return a node that does not change the behavior of the block
     */
    Node nop();

    /**
     * Begin a new block.  The returned node will be a dependency (usually the topmost dependency) of the terminator.
     *
     * @param blockLabel the label of the new block (must not be {@code null} or resolved)
     * @return the node representing the block entry
     */
    Node begin(BlockLabel blockLabel);

    /**
     * Begin a new block, suspending the current block until it is complete.
     * If the maker throws a {@link BlockEarlyTermination}, then it will be caught before this method returns.
     * If the maker does not terminate the block, an error will be raised and the block will be
     * terminated as if by {@link #unreachable()}.
     *
     * @param blockLabel the label of the new block (must not be {@code null} or resolved)
     * @param arg the argument to the maker
     * @param maker the callback which builds the block (must not be {@code null})
     * @return the resolved target of {@code blockLabel} (not {@code null})
     * @param <T> the type of the argument to the maker
     */
    <T> BasicBlock begin(BlockLabel blockLabel, T arg, BiConsumer<T, BasicBlockBuilder> maker);

    /**
     * Begin a new block, suspending the current block until it is complete.
     *
     * @param blockLabel the label of the new block (must not be {@code null} or resolved)
     * @param maker the callback which builds the block (must not be {@code null})
     * @return the completed block (not {@code null})
     */
    default BasicBlock begin(BlockLabel blockLabel, Consumer<BasicBlockBuilder> maker) {
        return begin(blockLabel, maker, Consumer::accept);
    }

    /**
     * Establish that the given value is reachable at this point.
     *
     * @param value the reachable value (must not be {@code null})
     * @return the node representing the reachability fence
     */
    Node reachable(Value value);

    /**
     * Add a safepoint poll at this point.
     *
     * @return the node representing the safepoint poll
     */
    Node safePoint();

    // control flow - terminalBlock is updated to point to this terminator

    /**
     * Call an invocation target that does not return, thus terminating the block.
     *
     * @param target the invocation target handle (must not be {@code null})
     * @param arguments the invocation arguments (must not be {@code null})
     * @return the terminated block (not {@code null}
     * @see CallNoReturn
     */
    BasicBlock callNoReturn(PointerValue target, List<Value> arguments);

    /**
     * Call an invocation target that does not return - thus terminating the block - and catch the thrown exception.
     * The given arguments must provide an argument value for every parameter defined in the target block.
     * Extra arguments are ignored.
     * An implicit argument for the thrown exception is provided to the catch block (see {@link Slot#thrown()}.
     *
     * @param target the invocation target handle (must not be {@code null})
     * @param arguments the invocation arguments (must not be {@code null})
     * @param catchLabel the exception handler label (must not be {@code null})
     * @param targetArguments the block arguments to pass to the target block (must not be {@code null})
     * @return the terminated block (not {@code null}
     * @see InvokeNoReturn
     */
    BasicBlock invokeNoReturn(PointerValue target, List<Value> arguments, BlockLabel catchLabel, Map<Slot, Value> targetArguments);

    /**
     * Tail-call an invocation target that returns the same type as this method, thus terminating the block.  The
     * backend can optimize such calls into tail calls if the calling element is {@linkplain ClassFile#I_ACC_HIDDEN hidden}.
     *
     * @param target the invocation target handle (must not be {@code null})
     * @param arguments the invocation arguments (must not be {@code null})
     * @return the terminated block (not {@code null}
     * @see TailCall
     */
    BasicBlock tailCall(PointerValue target, List<Value> arguments);

    /**
     * Tail-call an invocation target that returns the same type as this method - thus terminating the block - and catch
     * the thrown exception.  The backend can optimize such calls into tail calls if the calling element is
     * {@linkplain ClassFile#I_ACC_HIDDEN hidden}.
     * The given arguments must provide an argument value for every parameter defined in the target block.
     * Extra arguments are ignored.
     * An implicit argument for the thrown exception is provided to the catch block (see {@link Slot#thrown()}.
     *
     * @param target the invocation target handle (must not be {@code null})
     * @param arguments the invocation arguments (must not be {@code null})
     * @param catchLabel the exception handler label (must not be {@code null})
     * @param targetArguments the block arguments to pass to the target blocks (must not be {@code null})
     * @return the terminated block (not {@code null}
     * @see TailInvoke
     */
    BasicBlock tailInvoke(PointerValue target, List<Value> arguments, BlockLabel catchLabel, Map<Slot, Value> targetArguments);

    /**
     * Call an invocation target and catch the thrown exception, terminating the block.
     * <b>Note</b>: the terminated block is not returned.
     * The return value of this method is the return value of the invocation,
     * which will always be pinned to the {@code resumeLabel} block.
     * The given arguments must provide an argument value for every parameter defined in the target block.
     * Extra arguments are ignored.
     * An implicit argument for the thrown exception is provided to the catch block (see {@link Slot#thrown()}.
     * An implicit argument for the return value is provided to the resume block (see {@link Slot#result()}.
     *
     * @param target the invocation target handle (must not be {@code null})
     * @param arguments the invocation arguments (must not be {@code null})
     * @param catchLabel the exception handler label (must not be {@code null})
     * @param resumeLabel the handle of the resume target (must not be {@code null})
     * @param targetArguments the block arguments to pass to the target blocks (must not be {@code null})
     * @return the invocation result (not {@code null})
     * @see Invoke
     */
    Value invoke(PointerValue target, List<Value> arguments, BlockLabel catchLabel, BlockLabel resumeLabel, Map<Slot, Value> targetArguments);

    /**
     * Generate a {@code goto} termination node.  The terminated block is returned.
     * The given arguments must provide an argument value for every parameter defined in the target block.
     * Extra arguments are ignored.
     *
     * @param resumeLabel the handle of the jump target (must not be {@code null})
     * @param arguments the block arguments to pass to the target block (must not be {@code null})
     * @return the terminated block
     */
    BasicBlock goto_(BlockLabel resumeLabel, Map<Slot, Value> arguments);

    default BasicBlock goto_(BlockLabel resumeLabel, Slot slot, Value argValue) {
        return goto_(resumeLabel, Map.of(slot, argValue));
    }

    /**
     * Construct an {@code if} node.  If the condition is true, the {@code trueTarget} will receive control.  Otherwise,
     * the {@code falseTarget} will receive control.
     * <p>
     * Terminates the current block, which is returned.
     * <p>
     * The given arguments must provide an argument value for every parameter defined in either of the target blocks.
     * Extra arguments are ignored.
     *
     * @param condition the condition (must not be {@code null})
     * @param trueTarget the execution target to use when {@code condition} is {@code true}
     * @param falseTarget the execution target to use when {@code condition} is {@code false}
     * @param targetArguments the block arguments to pass to the target blocks (must not be {@code null})
     * @return the terminated block
     */
    BasicBlock if_(Value condition, BlockLabel trueTarget, BlockLabel falseTarget, Map<Slot, Value> targetArguments);

    default BasicBlock return_() {
        return return_(emptyVoid());
    }

    BasicBlock return_(Value value);

    BasicBlock unreachable();

    BasicBlock throw_(Value value);

    BasicBlock switch_(Value value, int[] checkValues, BlockLabel[] targets, BlockLabel defaultTarget, Map<Slot, Value> targetArguments);

    /**
     * Return from a subroutine call.
     * <p>
     * Terminates the current block.
     * <p>
     * The given arguments must provide an argument value for every parameter defined in the return block.
     * Extra arguments are ignored.
     *
     * @param address the return address (must not be {@code null})
     * @param targetArguments the block arguments to pass to the return block (must not be {@code null})
     * @return the node
     */
    BasicBlock ret(Value address, Map<Slot, Value> targetArguments);

    /**
     * Get the current block's entry node.
     *
     * @return the current block's entry node
     */
    BlockEntry getBlockEntry();

    /**
     * Get the most-recently-terminated block.
     *
     * @return the most recently terminated block (not {@code null})
     * @throws IllegalStateException if no block has yet been terminated
     */
    BasicBlock getTerminatedBlock();

    static BasicBlockBuilder simpleBuilder(final ExecutableElement element) {
        return new SimpleBasicBlockBuilder(element);
    }

    /**
     * An object used to provide additional context to basic block builder construction.
     */
    interface FactoryContext {
        /**
         * Get a piece of context information, if available.
         *
         * @param clazz the class of the context information (must not be {@code null})
         * @return the context information (not {@code null})
         * @param <T> the context information type
         */
        <T> T get(Class<T> clazz);

        boolean has(Class<?> clazz);

        /**
         * An empty factory context.
         */
        FactoryContext EMPTY = new FactoryContext() {
            @Override
            public <T> T get(Class<T> clazz) {
                throw new NoSuchElementException();
            }

            @Override
            public boolean has(Class<?> clazz) {
                return false;
            }
        };

        /**
         * Get a factory context which holds a piece of context information.
         *
         * @param delegate the delegate factory context (must not be {@code null})
         * @param info the context information (must not be {@code null})
         * @return the factory context (not {@code null})
         * @param <T> the context information type
         */
        static <T> FactoryContext withInfo(FactoryContext delegate, Class<T> clazz, T info) {
            Assert.checkNotNullParam("delegate", delegate);
            Assert.checkNotNullParam("clazz", clazz);
            Assert.checkNotNullParam("info", info);
            return new FactoryContext() {
                @Override
                public <T> T get(Class<T> clazz0) {
                    if (clazz0 == clazz) {
                        return clazz0.cast(info);
                    } else {
                        return delegate.get(clazz0);
                    }
                }

                @Override
                public boolean has(Class<?> clazz0) {
                    return clazz == clazz0 || delegate.has(clazz0);
                }
            };
        }
    }
}
