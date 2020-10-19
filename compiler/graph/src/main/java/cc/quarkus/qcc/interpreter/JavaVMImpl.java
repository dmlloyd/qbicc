package cc.quarkus.qcc.interpreter;

import static java.lang.Math.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import cc.quarkus.qcc.graph.BasicBlock;
import cc.quarkus.qcc.graph.BasicBlockBuilder;
import cc.quarkus.qcc.graph.DispatchInvocation;
import cc.quarkus.qcc.graph.FieldWrite;
import cc.quarkus.qcc.graph.Goto;
import cc.quarkus.qcc.graph.If;
import cc.quarkus.qcc.graph.InstanceFieldWrite;
import cc.quarkus.qcc.graph.InstanceInvocation;
import cc.quarkus.qcc.graph.Invocation;
import cc.quarkus.qcc.graph.JavaAccessMode;
import cc.quarkus.qcc.graph.Jsr;
import cc.quarkus.qcc.graph.Node;
import cc.quarkus.qcc.graph.PhiValue;
import cc.quarkus.qcc.graph.Ret;
import cc.quarkus.qcc.graph.Return;
import cc.quarkus.qcc.graph.StaticInvocationValue;
import cc.quarkus.qcc.graph.Terminator;
import cc.quarkus.qcc.graph.Throw;
import cc.quarkus.qcc.graph.Value;
import cc.quarkus.qcc.graph.ValueReturn;
import cc.quarkus.qcc.graph.ValueVisitor;
import cc.quarkus.qcc.graph.literal.ArrayTypeIdLiteral;
import cc.quarkus.qcc.graph.literal.ClassTypeIdLiteral;
import cc.quarkus.qcc.graph.literal.InterfaceTypeIdLiteral;
import cc.quarkus.qcc.graph.literal.LiteralFactory;
import cc.quarkus.qcc.graph.literal.TypeIdLiteral;
import cc.quarkus.qcc.graph.literal.ValueArrayTypeIdLiteral;
import cc.quarkus.qcc.graph.schedule.Schedule;
import cc.quarkus.qcc.type.ReferenceType;
import cc.quarkus.qcc.type.SignedIntegerType;
import cc.quarkus.qcc.type.Type;
import cc.quarkus.qcc.type.TypeSystem;
import cc.quarkus.qcc.type.UnsignedIntegerType;
import cc.quarkus.qcc.type.ValueType;
import cc.quarkus.qcc.type.definition.DefinedTypeDefinition;
import cc.quarkus.qcc.type.definition.FieldContainer;
import cc.quarkus.qcc.type.definition.MethodBody;
import cc.quarkus.qcc.type.definition.MethodHandle;
import cc.quarkus.qcc.type.definition.ModuleDefinition;
import cc.quarkus.qcc.type.definition.ResolvedTypeDefinition;
import cc.quarkus.qcc.type.definition.ValidatedTypeDefinition;
import cc.quarkus.qcc.type.definition.classfile.ClassFile;
import cc.quarkus.qcc.type.definition.element.ConstructorElement;
import cc.quarkus.qcc.type.definition.element.FieldElement;
import cc.quarkus.qcc.type.definition.element.MethodElement;
import cc.quarkus.qcc.type.definition.element.ParameterizedExecutableElement;
import cc.quarkus.qcc.type.descriptor.ConstructorDescriptor;
import cc.quarkus.qcc.type.descriptor.MethodDescriptor;
import cc.quarkus.qcc.type.descriptor.ParameterizedExecutableDescriptor;
import io.smallrye.common.constraint.Assert;

final class JavaVMImpl implements JavaVM {
    private static final ThreadLocal<JavaVMImpl> currentVm = new ThreadLocal<>();
    private static final Object[] NO_OBJECTS = new Object[0];
    private boolean exited;
    private int exitCode = -1;
    private final Lock vmLock = new ReentrantLock();
    private final Condition stopCondition = vmLock.newCondition();
    private final Condition signalCondition = vmLock.newCondition();
    private final ArrayDeque<Signal> signalQueue = new ArrayDeque<>();
    private final Set<JavaThread> threads = ConcurrentHashMap.newKeySet();
    final ThreadLocal<JavaThreadImpl> attachedThread = new ThreadLocal<>();
    private final Dictionary bootstrapDictionary;
    private final TypeSystem typeSystem;
    private final LiteralFactory literalFactory;
    private final ConcurrentMap<JavaObject, Dictionary> classLoaderLoaders = new ConcurrentHashMap<>();
    private final ConcurrentMap<Dictionary, JavaObject> loaderClassLoaders = new ConcurrentHashMap<>();
    private final ConcurrentMap<ClassTypeIdLiteral, JavaClassImpl> loadedClasses = new ConcurrentHashMap<>();
    private final ConcurrentMap<InterfaceTypeIdLiteral, JavaClassImpl> loadedInterfaces = new ConcurrentHashMap<>();
    private final ConcurrentMap<MethodBody, Map<BasicBlock, List<Node>>> schedules = new ConcurrentHashMap<>();
    private final ConcurrentMap<ParameterizedExecutableDescriptor, ConcurrentMap<Type, MethodDescriptor>> methodDescriptorCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<ParameterizedExecutableDescriptor, ConstructorDescriptor> constructorDescriptorCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, JavaObject> stringInstanceCache = new ConcurrentHashMap<>();
    private final ConcurrentTrie<Type, ParameterizedExecutableDescriptor> descriptorCache = new ConcurrentTrie<>();
    private final Map<String, BootModule> bootstrapModules;
    private final BasicBlockBuilder.Factory graphFactory;
    private final JavaObject mainThreadGroup;
    final DefinedTypeDefinition classLoaderClass;
    final DefinedTypeDefinition classClass;
    final DefinedTypeDefinition objectClass;
    final DefinedTypeDefinition stringClass;
    final DefinedTypeDefinition threadClass;
    final DefinedTypeDefinition threadGroupClass;
    final DefinedTypeDefinition classNotFoundExceptionClass;
    final DefinedTypeDefinition noSuchMethodErrorClass;
    final DefinedTypeDefinition abstractMethodErrorClass;
    final DefinedTypeDefinition unsatisfiedLinkErrorClass;

    JavaVMImpl(final Builder builder) {
        Map<String, BootModule> bootstrapModules = new LinkedHashMap<>();
        Dictionary bootstrapDictionary = new Dictionary(this);
        typeSystem = Assert.checkNotNullParam("builder.typeSystem", builder.typeSystem);
        literalFactory = Assert.checkNotNullParam("builder.literalFactory", builder.literalFactory);
        for (Path path : builder.bootstrapModules) {
            // open all bootstrap JARs (MR bootstrap JARs not supported)
            JarFile jarFile;
            try {
                jarFile = new JarFile(path.toFile(), true, ZipFile.OPEN_READ);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            String moduleInfo = "module-info.class";
            ByteBuffer buffer;
            try {
                buffer = getJarEntryBuffer(jarFile, moduleInfo);
                if (buffer == null) {
                    // ignore non-module
                    continue;
                }
            } catch (IOException e) {
                for (BootModule toClose : bootstrapModules.values()) {
                    try {
                        toClose.close();
                    } catch (IOException e2) {
                        e.addSuppressed(e2);
                    }
                }
                throw new RuntimeException(e);
            }
            ModuleDefinition moduleDefinition = ModuleDefinition.create(bootstrapDictionary, buffer);
            bootstrapModules.put(moduleDefinition.getName(), new BootModule(jarFile, moduleDefinition));
        }
        BootModule javaBase = bootstrapModules.get("java.base");
        if (javaBase == null) {
            throw new RuntimeException("Bootstrap failed: no java.base module found");
        }
        this.bootstrapModules = bootstrapModules;
        this.bootstrapDictionary = bootstrapDictionary;
        this.graphFactory = builder.graphFactory;
        JavaVMImpl old = currentVm.get();
        try {
            currentVm.set(this);
            objectClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Object");
            ClassTypeIdLiteral objClassId = literalFactory.literalOfClass("java/lang/Object", null);
            classClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Class");

            classLoaderClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/ClassLoader");
            stringClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/String");
            classNotFoundExceptionClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/ClassNotFoundException");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/io/Serializable");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/reflect/GenericDeclaration");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/reflect/Type");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/reflect/AnnotatedElement");
            // dependency classes start here
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Comparable");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/CharSequence");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Runnable");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Throwable");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Void");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/io/PrintStream");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/io/FilterOutputStream");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/io/OutputStream");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/io/Closeable");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/AutoCloseable");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/io/Flushable");
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Appendable");
            // dependency classes end here
            // now instantiate the main thread group
            defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Thread$UncaughtExceptionHandler");
            threadGroupClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/ThreadGroup");
            threadClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/Thread");
            mainThreadGroup = new JavaObjectImpl(threadGroupClass.validate());
            // run time linkage errors
            noSuchMethodErrorClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/NoSuchMethodError");
            abstractMethodErrorClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/AbstractMethodError");
            unsatisfiedLinkErrorClass = defineBootClass(bootstrapDictionary, javaBase.jarFile, "java/lang/UnsatisfiedLinkError");
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            currentVm.set(old);
        }
    }

    private static ByteBuffer getJarEntryBuffer(final JarFile jarFile, final String fileName) throws IOException {
        final ByteBuffer buffer;
        JarEntry jarEntry = jarFile.getJarEntry(fileName);
        if (jarEntry == null) {
            return null;
        }
        try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
            buffer = ByteBuffer.wrap(inputStream.readAllBytes());
        }
        return buffer;
    }

    private static DefinedTypeDefinition defineBootClass(final Dictionary bootstrapLoader, final JarFile javaBase, String name) throws IOException {
        ByteBuffer bytes = getJarEntryBuffer(javaBase, name + ".class");
        if (bytes == null) {
            throw new IllegalArgumentException("Initial class finder cannot find bootstrap class \"" + name + "\"");
        }
        return bootstrapLoader.defineClass(name, bytes);
    }

    public DefinedTypeDefinition defineClass(final String name, final JavaObject classLoader, final ByteBuffer bytes) {
        Dictionary dictionary = getDictionaryFor(classLoader);
        return dictionary.defineClass(name, bytes).validate();
    }

    private static final AtomicLong anonCounter = new AtomicLong();

    public DefinedTypeDefinition defineAnonymousClass(final DefinedTypeDefinition hostClass, final ByteBuffer bytes) {
        String newName = hostClass.getInternalName() + "/" + anonCounter.getAndIncrement();
        return defineClass(newName, ((Dictionary)hostClass.getContext()).getClassLoader(), bytes);
    }

    public DefinedTypeDefinition loadClass(JavaObject classLoader, final String name) throws Thrown {
        if (classLoader == null) {
            // do it the other way
            return loadBootstrapClass(name);
        }
        DefinedTypeDefinition loaded = findLoadedClass(classLoader, name);
        if (loaded != null) {
            return loaded;
        }
        JavaThread javaThread = JavaVM.requireCurrentThread();
        ResolvedTypeDefinition resolvedCL = classLoaderClass.validate().resolve();
        ValidatedTypeDefinition stringClassVerified = stringClass.validate();
        UnsignedIntegerType u16 = typeSystem.getUnsignedInteger16Type();
        ValueArrayTypeIdLiteral u16Array = literalFactory.literalOfArrayType(u16);
        MethodElement loadClass = resolvedCL.resolveMethodElementVirtual("loadClass", getMethodDescriptor(typeSystem.getReferenceType(resolvedCL.getTypeId()), typeSystem.getReferenceType(stringClassVerified.getTypeId())));
        int length = name.length();
        JavaArray array = allocateArray(u16Array, length);
        for (int i = 0; i < length; i++) {
            array.putArray(i, name.charAt(i));
        }
        ReferenceType u16ArrayType = typeSystem.getReferenceType(u16Array);
        ConstructorElement initString = stringClassVerified.resolve().resolveConstructorElement(getConstructorDescriptor(u16ArrayType));
        JavaObject nameInstance = allocateObject((ClassTypeIdLiteral) stringClassVerified.getTypeId());
        invokeExact(initString, nameInstance, array);
        return ((JavaClass) invokeVirtual(loadClass, nameInstance)).getTypeDefinition();
    }

    public DefinedTypeDefinition findLoadedBootstrapClass(final String name) {
        return bootstrapDictionary.findLoadedType(name);
    }

    public DefinedTypeDefinition loadBootstrapClass(final String name) {
        DefinedTypeDefinition loadedClass = findLoadedBootstrapClass(name);
        if (loadedClass != null) {
            return loadedClass;
        }
        // search the bootstrap modules for the class
        ByteBuffer bytes;
        for (BootModule module : bootstrapModules.values()) {
            try {
                bytes = getJarEntryBuffer(module.jarFile, name + ".class");
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to load class", e);
            }
            if (bytes != null) {
                DefinedTypeDefinition defined = bootstrapDictionary.tryDefineClass(name, bytes);
                if (defined != null) {
                    return defined;
                }
            }
        }
        // class not found
        throw classNotFound(name);
    }

    private Thrown classNotFound(String name) {
        return new Thrown(newException(classNotFoundExceptionClass, name));
    }

    private JavaObject newException(DefinedTypeDefinition type, String arg) {
        JavaObject e = allocateObject((ClassTypeIdLiteral) type.validate().getTypeId());
        ConstructorElement ctor = type.validate().resolve().resolveConstructorElement(getConstructorDescriptor(typeSystem.getReferenceType(stringClass.validate().getTypeId())));
        // todo: backtrace should be set to thread.tos
        invokeExact(ctor, e, newString(arg));
        return e;
    }

    private JavaObject newString(String str) {
        ValidatedTypeDefinition stringClassVerified = stringClass.validate();
        int length = str.length();
        SignedIntegerType s8 = typeSystem.getSignedInteger8Type();
        ValueArrayTypeIdLiteral s8Array = literalFactory.literalOfArrayType(s8);
        JavaArray array = allocateArray(s8Array, length << 1);
        for (int i = 0; i < length; i++) {
            array.putArray(i << 1, str.charAt(i) >>> 8);
            array.putArray((i << 1) + 1, str.charAt(i));
        }
        ReferenceType byteArrayType = typeSystem.getReferenceType(s8Array);
        ConstructorElement initString = stringClassVerified.resolve().resolveConstructorElement(getConstructorDescriptor(byteArrayType, s8));
        JavaObject res = allocateObject((ClassTypeIdLiteral) stringClassVerified.getTypeId());
        invokeExact(initString, res, array, Byte.valueOf((byte) 1 /* UTF16 */));
        return res;
    }

    public DefinedTypeDefinition findLoadedClass(final JavaObject classLoader, final String name) {
        return getDictionaryFor(classLoader).findLoadedType(name);
    }

    public JavaObject allocateObject(final ClassTypeIdLiteral literalType) {
        return new JavaObjectImpl(loadedClasses.get(literalType).getTypeDefinition());
    }

    public JavaArray allocateArray(final ArrayTypeIdLiteral type, final int length) {
        throw new UnsupportedOperationException();
    }

    public void invokeExact(final ConstructorElement ctor, final JavaObject instance, final Object... args) {
        Assert.checkNotNullParam("ctor", ctor);
        Assert.checkNotNullParam("instance", instance);
        MethodHandle exactHandle = ctor.getMethodBody();
        if (exactHandle == null) {
            throw new IllegalArgumentException("Method has no body");
        }
        invokeWith(exactHandle.createMethodBody(), instance, args);
    }

    public Object invokeExact(final MethodElement method, final JavaObject instance, final Object... args) {
        Assert.checkNotNullParam("method", method);
        MethodHandle exactHandle = method.getMethodBody();
        if (exactHandle == null) {
            if (method.hasAllModifiersOf(ClassFile.ACC_NATIVE)) {
                throw new Thrown(newException(unsatisfiedLinkErrorClass, method.getName()));
            }
            throw new IllegalArgumentException("Method has no body");
        }
        return invokeWith(exactHandle.createMethodBody(), instance, args);
    }

    public void initialize(final TypeIdLiteral typeId) {
        throw Assert.unsupported();
    }

    public Object invokeVirtual(final MethodElement method, final JavaObject instance, final Object... args) {
        MethodHandle exactHandle = method.getVirtualMethodBody();
        if (exactHandle == null) {
            throw new IllegalArgumentException("Method has no body");
        }
        return invokeWith(exactHandle.createMethodBody(), instance, args);
    }

    public Object invoke(final MethodHandle handle, final JavaObject instance, final Object... args) {
        return invokeWith(handle.createMethodBody(), instance, args);
    }

    private Object invokeWith(final MethodBody methodBody, final JavaObject instance, final Object... args) {
        int cnt = methodBody.getParameterCount();
        if (cnt != args.length) {
            throw new IllegalArgumentException("Invalid method parameter count");
        }
        BasicBlock entryBlock = methodBody.getEntryBlock();
        StackFrame frame = ((JavaThreadImpl) JavaVM.requireCurrentThread()).pushNewFrame(/* TODO */null);
        if (instance != null) {
            frame.bindValue(methodBody.getThisValue(), instance);
        }
        for (int i = 0; i < cnt; i ++) {
            Value paramValue = methodBody.getParameterValue(i);
            frame.bindValue(paramValue, args[i]);
        }
        return execute(methodBody);
    }

    private List<Node> scheduleBlock(MethodBody body, BasicBlock block) {
        return schedules.computeIfAbsent(body, b -> {
            Map<BasicBlock, LinkedHashSet<Node>> nodes = new HashMap<>();
            BasicBlock entryBlock = b.getEntryBlock();
            Schedule schedule = Schedule.forMethod(entryBlock);
            scheduleNode(nodes, schedule, entryBlock.getTerminator());
            Map<BasicBlock, List<Node>> finalMap = new HashMap<>(nodes.size());
            for (Map.Entry<BasicBlock, LinkedHashSet<Node>> entry : nodes.entrySet()) {
                finalMap.put(entry.getKey(), List.of(entry.getValue().toArray(Node[]::new)));
            }
            return finalMap;
        }).getOrDefault(block, List.of());
    }

    private void scheduleNode(final Map<BasicBlock, LinkedHashSet<Node>> nodes, final Schedule schedule, final Node node) {
        if (node == null) {
            // end of dependency chain
            return;
        }
        LinkedHashSet<Node> set = nodes.computeIfAbsent(schedule.getBlockForNode(node), b -> new LinkedHashSet<>());
        if (set.contains(node)) {
            return;
        }
        // todo - improve, improve
        int cnt = node.getBasicDependencyCount();
        for (int i = 0; i < cnt; i ++) {
            scheduleNode(nodes, schedule, node.getBasicDependency(i));
        }
        cnt = node.getValueDependencyCount();
        for (int i = 0; i < cnt; i ++) {
            scheduleNode(nodes, schedule, node.getValueDependency(i));
        }
        set.add(node);
        if (node instanceof Terminator) {
            Terminator terminator = (Terminator) node;
            int sc = terminator.getSuccessorCount();
            for (int i = 0; i < sc; i ++) {
                scheduleNode(nodes, schedule, terminator.getSuccessor(i).getTerminator());
            }
        }
    }

    private Object execute(MethodBody methodBody) {
        BasicBlock block = methodBody.getEntryBlock();
        BasicBlock predecessor = null;
        // run the method body by running each basic block in turn, following control flow as needed
        StackFrame frame = ((JavaThreadImpl) JavaVM.currentThread()).tos;
        for (;;) {
            List<Node> scheduledNodes = scheduleBlock(methodBody, block);
            // run the block by executing each node in scheduled order
            for (Node node : scheduledNodes) {
                if (node instanceof PhiValue) {
                    // no phi in entry block
                    assert predecessor != null;
                    PhiValue phiValue = (PhiValue) node;
                    frame.bindValue(phiValue, frame.getValue(phiValue.getValueForBlock(predecessor)));
                } else if (node instanceof Invocation) {
                    Invocation op = (Invocation) node;
                    if (op instanceof InstanceInvocation) {
                        InstanceInvocation instanceInvocation = (InstanceInvocation) op;
                        if (instanceInvocation.getKind() != DispatchInvocation.Kind.EXACT) {
                            throw new UnsupportedOperationException("Virtual dispatch");
                        }
                    }
                    ParameterizedExecutableElement it = op.getInvocationTarget();
                    ResolvedTypeDefinition owner = it.getEnclosingType().validate().resolve();
                    Object[] args = computeInvocationArguments(frame, op);
                    JavaObject instance;
                    if (op instanceof InstanceInvocation) {
                        instance = (JavaObject) frame.getValue(((InstanceInvocation) op).getInstance());
                    } else {
                        instance = null;
                    }
                    if (op instanceof StaticInvocationValue) {
                        frame.bindValue((Value) op, invoke(it.getMethodBody(), null, args));
                    } else {
                        if (it instanceof MethodElement) {
                            invokeExact((MethodElement) it, instance, args);
                        } else {
                            invokeExact((ConstructorElement) it, instance, args);
                        }
                    }
                } else if (node instanceof Throw) {
                    throw new Thrown((JavaObject) frame.getValue(((Throw) node).getThrownValue()));
                } else if (node instanceof FieldWrite) {
                    FieldWrite op = (FieldWrite) node;
                    FieldElement fieldElement = op.getFieldElement();
                    String fieldName = fieldElement.getName();
                    FieldContainer container;
                    if (op instanceof InstanceFieldWrite) {
                        JavaObjectImpl instance = (JavaObjectImpl) frame.getValue(((InstanceFieldWrite) op).getInstance());
                        container = instance.getFields();
                    } else {
                        container = fieldElement.getEnclosingType().validate().resolve().prepare().initialize().getStaticFields();
                    }
                    Object value = frame.getValue(op.getWriteValue());
                    // todo: improve this
                    JavaAccessMode mode = op.getMode();
                    if (mode == JavaAccessMode.DETECT) {
                        mode = container.getFieldSet().getField(fieldName).isVolatile() ? JavaAccessMode.VOLATILE : JavaAccessMode.PLAIN;
                    }
                    switch (mode) {
                        case PLAIN: {
                            if (value == null || value instanceof JavaObject) {
                                container.setFieldPlain(fieldName, (JavaObject) value);
                            } else if (value instanceof Long) {
                                container.setFieldPlain(fieldName, ((Long) value).longValue());
                            } else if (value instanceof Double) {
                                container.setFieldPlain(fieldName, Double.doubleToRawLongBits(((Double) value).doubleValue()));
                            } else if (value instanceof Float) {
                                container.setFieldPlain(fieldName, Float.floatToRawIntBits(((Float) value).floatValue()));
                            } else if (value instanceof Number) {
                                container.setFieldPlain(fieldName, ((Number) value).intValue());
                            } else {
                                throw new IllegalStateException("Unknown value type");
                            }
                            break;
                        }
                        case ORDERED: {
                            if (value == null || value instanceof JavaObject) {
                                container.setFieldRelease(fieldName, (JavaObject) value);
                            } else if (value instanceof Long) {
                                container.setFieldRelease(fieldName, ((Long) value).longValue());
                            } else if (value instanceof Double) {
                                container.setFieldRelease(fieldName, Double.doubleToRawLongBits(((Double) value).doubleValue()));
                            } else if (value instanceof Float) {
                                container.setFieldRelease(fieldName, Float.floatToRawIntBits(((Float) value).floatValue()));
                            } else if (value instanceof Number) {
                                container.setFieldRelease(fieldName, ((Number) value).intValue());
                            } else {
                                throw new IllegalStateException("Unknown value type");
                            }
                            break;
                        }
                        case VOLATILE: {
                            if (value == null || value instanceof JavaObject) {
                                container.setFieldVolatile(fieldName, (JavaObject) value);
                            } else if (value instanceof Long) {
                                container.setFieldVolatile(fieldName, ((Long) value).longValue());
                            } else if (value instanceof Double) {
                                container.setFieldVolatile(fieldName, Double.doubleToRawLongBits(((Double) value).doubleValue()));
                            } else if (value instanceof Float) {
                                container.setFieldVolatile(fieldName, Float.floatToRawIntBits(((Float) value).floatValue()));
                            } else if (value instanceof Number) {
                                container.setFieldVolatile(fieldName, ((Number) value).intValue());
                            } else {
                                throw new IllegalStateException("Unknown value type");
                            }
                            break;
                        }
                        default: {
                            throw new IllegalStateException("Unknown access mode");
                        }
                    }
                } else if (node instanceof Value) {
//                    node.accept(computer, frame);
                } else if (node instanceof Terminator) {
                    // todo: assert node is last in list
                    break;
                } else {
                    throw new IllegalStateException("Unsupported node " + node);
                }
            }
            // all nodes in the block have been executed; now execute the terminator
            predecessor = block;
            Terminator terminator = block.getTerminator();
            if (terminator instanceof ValueReturn) {
                return frame.getValue(((ValueReturn) terminator).getReturnValue());
            } else if (terminator instanceof Return) {
                return null;
            } else if (terminator instanceof Throw) {
                throw new Thrown((JavaObject) frame.getValue(((Throw) terminator).getThrownValue()));
            } else if (terminator instanceof Jsr) {
                Jsr jsr = (Jsr) terminator;
                frame.bindValue(jsr.getReturnAddressValue(), jsr.getReturnAddressValue());
                block = jsr.getJsrTarget();
            } else if (terminator instanceof Ret) {
                block = (BasicBlock) frame.getValue(((Ret) terminator).getReturnAddressValue());
            } else if (terminator instanceof Goto) {
                block = ((Goto) terminator).getResumeTarget();
            } else if (terminator instanceof If) {
                If if_ = (If) terminator;
                Object value = frame.getValue(if_.getCondition());
                if (((Integer) value).intValue() != 0) {
                    block = if_.getTrueBranch();
                } else {
                    block = if_.getFalseBranch();
                }
            }
        }
    }

    private final Computer computer = new Computer();

    LiteralFactory getLiteralFactory() {
        return literalFactory;
    }

    TypeSystem getTypeSystem() {
        return typeSystem;
    }

    final class Computer implements ValueVisitor<StackFrame, Void> {
        public Void visitUnknown(final StackFrame param, final Value node) {
            throw new IllegalStateException();
        }
    }

    private Object[] computeInvocationArguments(final StackFrame frame, final Invocation op) {
        int cnt = op.getArgumentCount();
        final Object[] args = cnt == 0 ? NO_OBJECTS : new Object[cnt];
        for (int i = 0; i < cnt; i++) {
            args[i] = frame.getValue(op.getArgument(i));
        }
        return args;
    }

    private MethodHandle computeInvocationTarget(final Invocation op, final ResolvedTypeDefinition owner, final int methodIndex) {
        final MethodHandle target;
        if (op instanceof InstanceInvocation) {
            InstanceInvocation iOp = (InstanceInvocation) op;
            Value instanceVal = iOp.getInstance();
            DispatchInvocation.Kind kind = iOp.getKind();
            if (kind == DispatchInvocation.Kind.EXACT) {
                target = owner.getMethod(methodIndex).getMethodBody();
            } else {
                target = owner.getMethod(methodIndex).getVirtualMethodBody();
            }
        } else {
            // static invocation
            target = owner.getMethod(methodIndex).getMethodBody();
        }
        return target;
    }

    public JavaThread newThread(final String threadName, final JavaObject threadGroup, final boolean daemon) {
        return new JavaThreadImpl(threadName, threadGroup, daemon, this);
    }

    boolean tryAttach(JavaThread thread) throws IllegalStateException {
        if (attachedThread.get() != null) {
            throw new IllegalStateException("Thread is already attached");
        }
        JavaVMImpl existing = currentVm.get();
        if (existing == this) {
            return false;
        }
        if (existing != this && existing != null) {
            throw new IllegalStateException("Another JVM is already attached");
        }
        currentVm.set(this);
        attachedThread.set((JavaThreadImpl) thread);
        return true;
    }

    void detach(JavaThread thread) throws IllegalStateException {
        JavaThread existing = attachedThread.get();
        if (existing != thread) {
            throw new IllegalStateException("Thread is not attached");
        }
        attachedThread.remove();
        currentVm.remove();
    }

    static JavaThreadImpl currentThread() {
        JavaVMImpl javaVM = currentVm();
        return javaVM == null ? null : javaVM.attachedThread.get();
    }

    public void doAttached(final Runnable r) {
        JavaVMImpl currentVm = JavaVMImpl.currentVm.get();
        if (currentVm == this) {
            r.run();
            return;
        }
        if (currentVm != null) {
            throw new IllegalStateException("Another JVM is already attached");
        }
        JavaVMImpl.currentVm.set(this);
        try {
            r.run();
        } finally {
            JavaVMImpl.currentVm.remove();
        }
    }

    public DefinedTypeDefinition getClassTypeDefinition() {
        return classClass;
    }

    public DefinedTypeDefinition getObjectTypeDefinition() {
        return objectClass;
    }

    static JavaVMImpl currentVm() {
        return currentVm.get();
    }

    public void deliverSignal(final Signal signal) {
        vmLock.lock();
        try {
            signalQueue.addLast(signal);
            signalCondition.notify();
        } finally {
            vmLock.unlock();
        }
    }

    Signal awaitSignal() throws InterruptedException {
        vmLock.lockInterruptibly();
        try {
            Signal signal;
            for (;;) {
                signal = signalQueue.pollFirst();
                if (signal != null) {
                    return signal;
                }
                signalCondition.await();
            }
        } finally {
            vmLock.unlock();
        }
    }

    public int awaitTermination() throws InterruptedException {
        vmLock.lockInterruptibly();
        try {
            while (! threads.isEmpty()) {
                stopCondition.await();
            }
            return max(0, exitCode);
        } finally {
            vmLock.unlock();
        }
    }

    public String deduplicate(final JavaObject classLoader, final String string) {
        // TODO
        return string;
    }

    public String deduplicate(final JavaObject classLoader, final ByteBuffer buffer, final int offset, final int length, final boolean expectTerminator) {
        // TODO: apart from being inefficient, this is also not strictly correct! don't copy this code
        byte[] bytes = new byte[length];
        buffer.duplicate().position(offset).get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public ParameterizedExecutableDescriptor getParameterizedExecutableDescriptor(final ValueType... paramTypes) {
        ConcurrentTrie<Type, ParameterizedExecutableDescriptor> current = this.descriptorCache;
        for (Type paramType : paramTypes) {
            current = current.computeIfAbsent(paramType, t -> new ConcurrentTrie<>());
        }
        ParameterizedExecutableDescriptor val = current.get();
        if (val != null) {
            return val;
        }
        ParameterizedExecutableDescriptor newVal = ParameterizedExecutableDescriptor.of(paramTypes);
        while (! current.compareAndSet(null, newVal)) {
            val = current.get();
            if (val != null) {
                return val;
            }
        }
        return newVal;
    }

    public JavaObject getSharedString(final String string) {
        return stringInstanceCache.computeIfAbsent(deduplicate(null, string), this::newString);
    }

    public MethodDescriptor getMethodDescriptor(final ValueType returnType, final ValueType... paramTypes) {
        return getMethodDescriptor(returnType, getParameterizedExecutableDescriptor(paramTypes));
    }

    public MethodDescriptor getMethodDescriptor(final ValueType returnType, final ParameterizedExecutableDescriptor paramDesc) {
        return methodDescriptorCache.computeIfAbsent(paramDesc, k -> new ConcurrentHashMap<>()).computeIfAbsent(returnType, r -> MethodDescriptor.of(paramDesc, returnType));
    }

    public ConstructorDescriptor getConstructorDescriptor(final ValueType... paramTypes) {
        return getConstructorDescriptor(getParameterizedExecutableDescriptor(paramTypes));
    }

    public ConstructorDescriptor getConstructorDescriptor(final ParameterizedExecutableDescriptor paramDesc) {
        return constructorDescriptorCache.computeIfAbsent(paramDesc, k -> ConstructorDescriptor.of(paramDesc));
    }

    public JavaObject allocateDirectBuffer(final ByteBuffer backingBuffer) {
        throw Assert.unsupported();
    }

    void exit(int status) {
        vmLock.lock();
        try {
            if (! exited) {
                exitCode = status;
                exited = true;
            }
        } finally {
            vmLock.unlock();
        }
    }

    public void close() {
        vmLock.lock();
        try {
            exit(134); // SIGKILL-ish
            for (JavaThread thread : threads) {
                thread.await();
            }
            // todo: this is all probably wrong; we need to figure out lifecycle more accurately
            for (BootModule bootModule : bootstrapModules.values()) {
                try {
                    bootModule.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        } finally {
            vmLock.unlock();
        }
    }

    public DefinedTypeDefinition.Builder newTypeDefinitionBuilder(final JavaObject classLoader) {
        // TODO: use plugins to get a builder
        DefinedTypeDefinition.Builder builder = DefinedTypeDefinition.Builder.basic();
        builder.setContext(getDictionaryFor(classLoader));
        return builder;
    }

    public BasicBlockBuilder newBasicBlockBuilder() {
        // TODO: new instance per call?
        return graphFactory.construct(new BasicBlockBuilder.Factory.Context() {
            public TypeSystem getTypeSystem() {
                return typeSystem;
            }

            public LiteralFactory getLiteralFactory() {
                return literalFactory;
            }
        }, BasicBlockBuilder.simpleBuilder(typeSystem));
    }

    public JavaObject getMainThreadGroup() {
        return mainThreadGroup;
    }

    Dictionary getDictionaryFor(final JavaObject classLoader) {
        if (classLoader == null) {
            return bootstrapDictionary;
        }
        Dictionary dictionary = classLoaderLoaders.get(classLoader);
        if (dictionary == null) {
            Dictionary appearing = classLoaderLoaders.putIfAbsent(classLoader, dictionary = new Dictionary(classLoader, this));
            if (appearing != null) {
                dictionary = appearing;
            }
        }
        return dictionary;
    }

    JavaObject getClassLoaderFor(final Dictionary dictionary) {
        JavaObject classLoader = loaderClassLoaders.get(dictionary);
        if (classLoader == null) {
            throw new IllegalStateException("Class loader object is unknown");
        }
        return classLoader;
    }

    static final class BootModule implements Closeable {
        private final JarFile jarFile;
        private final ModuleDefinition moduleDefinition;

        BootModule(final JarFile jarFile, final ModuleDefinition moduleDefinition) {
            this.jarFile = jarFile;
            this.moduleDefinition = moduleDefinition;
        }

        public void close() throws IOException {
            jarFile.close();
        }
    }

    static final class StackFrame {
        private final StackFrame parent;
        private final Invocation caller;
        private final Map<Value, Object> values = new HashMap<>();

        StackFrame(final StackFrame parent, final Invocation caller) {
            this.parent = parent;
            this.caller = caller;
        }

        Object getValue(Value v) {
            throw Assert.unsupported();
        }

        void bindValue(Value v, Object value) {
            values.put(v, value);
        }

        Invocation getCaller() {
            return caller;
        }

        StackFrame getParent() {
            return parent;
        }
    }
}