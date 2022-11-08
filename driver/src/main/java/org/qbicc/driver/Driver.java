package org.qbicc.driver;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import io.smallrye.common.constraint.Assert;
import io.smallrye.common.function.Functions;
import org.jboss.logging.Logger;
import org.qbicc.context.AttachmentKey;
import org.qbicc.context.ClassContext;
import org.qbicc.context.CompilationContext;
import org.qbicc.context.Diagnostic;
import org.qbicc.graph.BasicBlock;
import org.qbicc.graph.BasicBlockBuilder;
import org.qbicc.graph.Node;
import org.qbicc.graph.NodeVisitor;
import org.qbicc.graph.Value;
import org.qbicc.graph.PointerValue;
import org.qbicc.graph.literal.LiteralFactory;
import org.qbicc.interpreter.Vm;
import org.qbicc.machine.arch.Platform;
import org.qbicc.machine.object.ObjectFileProvider;
import org.qbicc.machine.tool.CToolChain;
import org.qbicc.tool.llvm.LlvmToolChain;
import org.qbicc.type.TypeSystem;
import org.qbicc.type.definition.DefinedTypeDefinition;
import org.qbicc.type.definition.DescriptorTypeResolver;
import org.qbicc.type.definition.LoadedTypeDefinition;
import org.qbicc.type.definition.ModuleDefinition;
import org.qbicc.type.definition.NativeMethodConfigurator;
import org.qbicc.type.definition.classfile.ClassFile;
import org.qbicc.type.definition.element.ExecutableElement;

/**
 * A simple driver to run all the stages of compilation.
 */
public class Driver implements Closeable {
    private static final Logger log = Logger.getLogger("org.qbicc.driver");
    private static final AttachmentKey<Driver> KEY = new AttachmentKey<>();

    static final String MODULE_INFO = "module-info.class";

    public static final AttachmentKey<CToolChain> C_TOOL_CHAIN_KEY = new AttachmentKey<>();
    public static final AttachmentKey<LlvmToolChain> LLVM_TOOL_KEY = new AttachmentKey<>();
    public static final AttachmentKey<ObjectFileProvider> OBJ_PROVIDER_TOOL_KEY = new AttachmentKey<>();

    final BaseDiagnosticContext initialContext;
    final CompilationContextImpl compilationContext;
    // at this point, the phase is initialized to ADD
    final List<UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>>> addTaskWrapperFactories;
    final List<Consumer<CompilationContext>> preAddHooks;
    final List<BiFunction<? super ClassContext, DefinedTypeDefinition.Builder, DefinedTypeDefinition.Builder>> typeBuilderFactories;
    final BiFunction<BasicBlockBuilder.FactoryContext, ExecutableElement, BasicBlockBuilder> addBuilderFactory;
    final List<Consumer<CompilationContext>> postAddHooks;
    // at this point, the phase is switched to ANALYZE
    final List<UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>>> analyzeTaskWrapperFactories;
    final List<Consumer<CompilationContext>> preAnalyzeHooks;
    final BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>> addToAnalyzeCopiers;
    final BiFunction<BasicBlockBuilder.FactoryContext, ExecutableElement, BasicBlockBuilder> analyzeBuilderFactory;
    final List<Consumer<CompilationContext>> postAnalyzeHooks;
    // at this point, the phase is switched to LOWER
    final List<UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>>> lowerTaskWrapperFactories;
    final List<Consumer<CompilationContext>> preLowerHooks;
    final BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>> analyzeToLowerCopiers;
    final BiFunction<BasicBlockBuilder.FactoryContext, ExecutableElement, BasicBlockBuilder> lowerBuilderFactory;
    final List<Consumer<CompilationContext>> postLowerHooks;
    // at this point, the phase is switched to GENERATE
    final List<UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>>> generateTaskWrapperFactories;
    final List<Consumer<CompilationContext>> preGenerateHooks;
    final List<Consumer<CompilationContext>> postGenerateHooks;
    final Map<String, BootModule> bootModules;
    final List<ClassPathItem> bootClassPath;
    final List<ClassPathItem> appClassPath;
    final Path outputDir;
    final float threadsPerCpu;
    final long stackSize;
    final Consumer<ClassContext> classContextListener;

    Driver(final Builder builder) {
        initialContext = Assert.checkNotNullParam("builder.initialContext", builder.initialContext);
        outputDir = Assert.checkNotNullParam("builder.outputDirectory", builder.outputDirectory);
        typeBuilderFactories = builder.typeBuilderFactories;
        initialContext.putAttachment(C_TOOL_CHAIN_KEY, Assert.checkNotNullParam("builder.toolChain", builder.toolChain));
        initialContext.putAttachment(LLVM_TOOL_KEY, Assert.checkNotNullParam("builder.llvmToolChain", builder.llvmToolChain));
        initialContext.putAttachment(OBJ_PROVIDER_TOOL_KEY, Assert.checkNotNullParam("builder.objectFileProvider", builder.objectFileProvider));
        // type system
        final TypeSystem typeSystem = builder.typeSystem;
        final LiteralFactory literalFactory = LiteralFactory.create(typeSystem);

        // boot modules
        Map<String, BootModule> bootModules = new HashMap<>();

        this.bootClassPath = List.copyOf(builder.bootClassPath);
        for (ClassPathItem item : bootClassPath) {
            // open all bootstrap JARs (MR bootstrap JARs not supported)
            try (ClassPathElement.Resource moduleInfo = item.findResource(MODULE_INFO)) {
                if (moduleInfo == ClassPathElement.NON_EXISTENT) {
                    // ignore non-module
                    continue;
                }
                ByteBuffer buffer = moduleInfo.getBuffer();
                ModuleDefinition moduleDefinition = ModuleDefinition.create(buffer);
                bootModules.put(moduleDefinition.getName(), new BootModule(item, moduleDefinition));
            } catch (Exception e) {
                initialContext.error("Failed to read module from class path element \"%s\": %s", item, e);
            }
        }
        BootModule javaBase = bootModules.get("java.base");
        if (javaBase == null) {
            initialContext.error("Bootstrap failed: no java.base module found");
        }
        this.bootModules = bootModules;
        this.appClassPath = List.copyOf(builder.appClassPath);

        // ADD phase
        addTaskWrapperFactories = List.copyOf(builder.taskWrapperFactories.getOrDefault(Phase.ADD, List.of()));
        preAddHooks = List.copyOf(builder.preHooks.getOrDefault(Phase.ADD, List.of()));
        // (no copiers)
        addBuilderFactory = constructFactory(builder, Phase.ADD);
        postAddHooks = List.copyOf(builder.postHooks.getOrDefault(Phase.ADD, List.of()));

        // ANALYZE phase
        analyzeTaskWrapperFactories = List.copyOf(builder.taskWrapperFactories.getOrDefault(Phase.ANALYZE, List.of()));
        preAnalyzeHooks = List.copyOf(builder.preHooks.getOrDefault(Phase.ANALYZE, List.of()));
        addToAnalyzeCopiers = constructCopiers(builder, Phase.ANALYZE);
        analyzeBuilderFactory = constructFactory(builder, Phase.ANALYZE);
        postAnalyzeHooks = List.copyOf(builder.postHooks.getOrDefault(Phase.ANALYZE, List.of()));

        // LOWER phase
        lowerTaskWrapperFactories = List.copyOf(builder.taskWrapperFactories.getOrDefault(Phase.LOWER, List.of()));
        preLowerHooks = List.copyOf(builder.preHooks.getOrDefault(Phase.LOWER, List.of()));
        analyzeToLowerCopiers = constructCopiers(builder, Phase.LOWER);
        lowerBuilderFactory = constructFactory(builder, Phase.LOWER);
        postLowerHooks = List.copyOf(builder.postHooks.getOrDefault(Phase.LOWER, List.of()));

        // GENERATE phase
        generateTaskWrapperFactories = List.copyOf(builder.taskWrapperFactories.getOrDefault(Phase.GENERATE, List.of()));
        preGenerateHooks = List.copyOf(builder.preHooks.getOrDefault(Phase.GENERATE, List.of()));
        // (no builder factory)
        postGenerateHooks = List.copyOf(builder.postHooks.getOrDefault(Phase.GENERATE, List.of()));

        List<BiFunction<? super ClassContext, DescriptorTypeResolver, DescriptorTypeResolver>> resolverFactories = new ArrayList<>(builder.resolverFactories);
        Collections.reverse(resolverFactories);
        classContextListener = builder.classContextListener;

        java.util.function.Function<CompilationContext, Vm> vmFactory = Assert.checkNotNullParam("builder.vmFactory", builder.vmFactory);
        NativeMethodConfigurator nativeMethodConfigurator = constructNativeMethodConfigurator(builder);
        compilationContext = new CompilationContextImpl(initialContext, builder.targetPlatform, typeSystem, literalFactory, this::defaultFinder, this::defaultResourceFinder, this::defaultResourcesFinder, this::appFinder, this::appResourceFinder, this::appResourcesFinder, vmFactory, outputDir, resolverFactories, typeBuilderFactories, nativeMethodConfigurator, classContextListener);
        // start with ADD
        compilationContext.setBlockFactory(addBuilderFactory);

        threadsPerCpu = builder.threadsPerCpu;
        stackSize = builder.stackSize;
        compilationContext.putAttachment(KEY, this);
    }

    public static Driver get(CompilationContext ctxt) {
        Driver driver = ctxt.getAttachment(KEY);
        if (driver == null) {
            throw new IllegalStateException();
        }
        return driver;
    }

    public ClassPathItem getBootModuleClassPathItem(String name) {
        BootModule module = bootModules.get(name);
        if (module == null) {
            throw new NoSuchElementException();
        }
        return module.item();
    }

    public Collection<String> getBootModuleNames() {
        return List.copyOf(bootModules.keySet());
    }

    private NativeMethodConfigurator constructNativeMethodConfigurator(final Builder builder) {
        List<UnaryOperator<NativeMethodConfigurator>> list = new ArrayList<>(builder.nativeMethodConfiguratorFactories);
        Collections.reverse(list);
        NativeMethodConfigurator result = (bbb, enclosing, name, methodDescriptor) -> {};
        for (UnaryOperator<NativeMethodConfigurator> item : list) {
            result = item.apply(result);
        }
        return result;
    }

    private static BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>> constructCopiers(final Builder builder, final Phase phase) {
        List<BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>>> list = builder.copyFactories.getOrDefault(phase, List.of());
        if (list.isEmpty()) {
            return (c, v) -> v;
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        // `var` because the type is absurdly long
        var copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return (c, v) -> {
            // `var` because the type is absurdly long
            for (var fn : copy) {
                v = fn.apply(c, v);
            }
            return v;
        };
    }

    private static BiFunction<BasicBlockBuilder.FactoryContext, ExecutableElement, BasicBlockBuilder> constructFactory(final Builder builder, final Phase phase) {
        BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder> addWrapper = assembleFactories(builder.builderFactories.getOrDefault(phase, Map.of()));
        return (ctxt, executableElement) -> addWrapper.apply(ctxt, BasicBlockBuilder.simpleBuilder(executableElement));
    }

    private static BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder> assembleFactories(Map<BuilderStage, List<BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder>>> map) {
        return assembleFactories(List.of(
            assembleFactories(map.getOrDefault(BuilderStage.TRANSFORM, List.of())),
            assembleFactories(map.getOrDefault(BuilderStage.CORRECT, List.of())),
            assembleFactories(map.getOrDefault(BuilderStage.OPTIMIZE, List.of())),
            assembleFactories(map.getOrDefault(BuilderStage.INTEGRITY, List.of()))
        ));
    }

    private static BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder> assembleFactories(List<BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder>> list) {
        if (list.isEmpty()) {
            return (c, b) -> b;
        }
        if (list.size() == 1) {
            return list.get(0);
        }
        List<BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder>> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return (c, builder) -> {
            for (BiFunction<? super BasicBlockBuilder.FactoryContext , BasicBlockBuilder, BasicBlockBuilder> fn : copy) {
                builder = fn.apply(c, builder);
            }
            return builder;
        };
    }

    private DefinedTypeDefinition defaultFinder(ClassContext classContext, String name) {
        return findClassDefinition(classContext, name, bootClassPath);
    }

    private byte[] defaultResourceFinder(ClassContext classContext, String name) {
        return findResource(classContext, name, bootClassPath);
    }

    private List<byte[]> defaultResourcesFinder(final ClassContext classContext, final String name) {
        return findResources(classContext, name, bootClassPath);
    }

    private DefinedTypeDefinition appFinder(ClassContext classContext, String name) {
        DefinedTypeDefinition found;
        found = getCompilationContext().getBootstrapClassContext().findDefinedType(name);
        if (found == null) {
            found = findClassDefinition(classContext, name, appClassPath);
        }
        return found;
    }

    private byte[] appResourceFinder(ClassContext classContext, String name) {
        return findResource(classContext, name, appClassPath);
    }

    private List<byte[]> appResourcesFinder(final ClassContext classContext, final String name) {
        return findResources(classContext, name, appClassPath);
    }

    private DefinedTypeDefinition findClassDefinition(final ClassContext classContext, final String name, final List<ClassPathItem> classPath) {
        String fileName = name + ".class";
        ByteBuffer buffer;
        for (ClassPathItem item : classPath) {
            try (ClassPathElement.Resource resource = item.findResource(fileName)) {
                if (resource == ClassPathElement.NON_EXISTENT) {
                    continue;
                }
                buffer = resource.getBuffer();
                ClassFile classFile = ClassFile.of(classContext, buffer);
                DefinedTypeDefinition.Builder builder = classContext.newTypeBuilder();
                classFile.accept(builder);
                DefinedTypeDefinition def = builder.build();
                classContext.defineClass(name, def);
                return def;
            } catch (Exception e) {
                log.warnf(e, "An exception was thrown while loading class \"%s\"", name);
                classContext.getCompilationContext().warning("Failed to load class \"%s\" due to an exception: %s", name, e);
                return null;
            }
        }
        return null;
    }

    private byte[] findResource(final ClassContext classContext, final String name, final List<ClassPathItem> classPath) {
        ByteBuffer buffer;
        for (ClassPathItem item : classPath) {
            try (ClassPathElement.Resource resource = item.findResource(name)) {
                if (resource == ClassPathElement.NON_EXISTENT) {
                    continue;
                }
                buffer = resource.getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                return bytes;
            } catch (Exception e) {
                log.warnf(e, "An exception was thrown while loading resource \"%s\"", name);
                classContext.getCompilationContext().warning("Failed to load resource \"%s\" due to an exception: %s", name, e);
                return null;
            }
        }
        return null;
    }

    private List<byte[]> findResources(final ClassContext classContext, final String name, final List<ClassPathItem> classPath) {
        ByteBuffer buffer;
        ArrayList<byte[]> list = new ArrayList<>();
        for (ClassPathItem item : classPath) {
            try (ClassPathElement.Resource resource = item.findResource(name)) {
                if (resource == ClassPathElement.NON_EXISTENT) {
                    continue;
                }
                buffer = resource.getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                list.add(bytes);
            } catch (Exception e) {
                log.warnf(e, "An exception was thrown while loading resource \"%s\"", name);
                classContext.getCompilationContext().warning("Failed to load resource \"%s\" due to an exception: %s", name, e);
                // might as well continue though
            }
        }
        return List.copyOf(list);
    }

    public CompilationContext getCompilationContext() {
        return compilationContext;
    }

    private LoadedTypeDefinition loadBootstrapClass(String name) {
        DefinedTypeDefinition clazz = compilationContext.getBootstrapClassContext().findDefinedType(name);
        if (clazz == null) {
            compilationContext.error("Required bootstrap class \"%s\" was not found", name);
            return null;
        }
        try {
            return clazz.load();
        } catch (Exception ex) {
            log.error("An exception was thrown while loading a bootstrap class", ex);
            compilationContext.error("Failed to load bootstrap class \"%s\": %s", name, ex);
            return null;
        }
    }

    /**
     * Execute the compilation.
     *
     * @return {@code true} if compilation succeeded, {@code false} otherwise
     */
    public boolean execute() {
        // start threads
        int threadCnt = (int) Math.max(1, ((float)Runtime.getRuntime().availableProcessors()) * threadsPerCpu);
        compilationContext.startThreads(threadCnt, stackSize);
        try {
            return execute0();
        } finally {
            // shut down threads
            compilationContext.exitThreads();
        }
    }

    public Consumer<ExecutableElement> constructCopyingStage(
        Phase phase,
        Function<
            BiFunction<
                CompilationContext,
                NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>,
                NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>
            >,
            Consumer<ExecutableElement>
        > constructor) {
        switch (phase) {
            case ANALYZE: return constructor.apply(addToAnalyzeCopiers);
            case LOWER: return constructor.apply(analyzeToLowerCopiers);
            default: throw new IllegalArgumentException();
        }
    }

    boolean execute0() {
        CompilationContextImpl compilationContext = this.compilationContext;

        BiConsumer<Consumer<CompilationContext>, CompilationContext> wrapper = Consumer::accept;

        for (UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>> factory : addTaskWrapperFactories) {
            wrapper = factory.apply(wrapper);
        }
        compilationContext.setTaskRunner(wrapper);

        // ADD phase

        Phase.ADD.setCurrent(compilationContext);

        for (Consumer<CompilationContext> hook : preAddHooks) {
            try {
                wrapper.accept(hook, compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a pre-add hook", e);
                compilationContext.error(e, "Pre-add hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }
        LoadedTypeDefinition stringClass = loadBootstrapClass("java/lang/String");
        if (stringClass == null) {
            return false;
        }
        LoadedTypeDefinition threadClass = loadBootstrapClass("java/lang/Thread");
        if (threadClass == null) {
            return false;
        }

        // trace out the program graph, enqueueing each item one time and then processing every item in the queue;
        // in this stage we're just loading everything that *might* be reachable
        for (ExecutableElement entryPoint : compilationContext.getEntryPoints()) {
            compilationContext.enqueue(entryPoint);
        }

        compilationContext.processQueue();

        if (compilationContext.errors() > 0) {
            // bail out
            return false;
        }

        for (Consumer<? super CompilationContext> hook : postAddHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a post-add hook", e);
                compilationContext.error("Post-add hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        if (compilationContext.errors() > 0) {
            // bail out
            return false;
        }

        compilationContext.cyclePhaseAttachments();

        // ANALYZE phase

        Phase.ANALYZE.setCurrent(compilationContext);

        compilationContext.setBlockFactory(analyzeBuilderFactory);
        compilationContext.setCopier(addToAnalyzeCopiers);

        wrapper = Consumer::accept;

        for (UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>> factory : analyzeTaskWrapperFactories) {
            wrapper = factory.apply(wrapper);
        }
        compilationContext.setTaskRunner(wrapper);

        for (Consumer<? super CompilationContext> hook : preAnalyzeHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a pre-analyze hook", e);
                compilationContext.error("Pre-analyze hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        // In this phase we start from the entry points again, and then copy (and filter) all of the nodes to a smaller reachable set

        for (ExecutableElement entryPoint : compilationContext.getEntryPoints()) {
            compilationContext.enqueue(entryPoint);
        }

        compilationContext.processQueue();

        if (compilationContext.errors() > 0) {
            // bail out
            return false;
        }

        for (Consumer<? super CompilationContext> hook : postAnalyzeHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a post-analyze hook", e);
                compilationContext.error("Post-analyze hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        compilationContext.cyclePhaseAttachments();

        // LOWER phase

        Phase.LOWER.setCurrent(compilationContext);

        wrapper = Consumer::accept;

        for (UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>> factory : lowerTaskWrapperFactories) {
            wrapper = factory.apply(wrapper);
        }

        compilationContext.setTaskRunner(wrapper);
        compilationContext.setBlockFactory(lowerBuilderFactory);
        compilationContext.setCopier(analyzeToLowerCopiers);

        for (Consumer<? super CompilationContext> hook : preLowerHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a pre-lower hook", e);
                compilationContext.error("Pre-lower hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        // start from entry points one more time, and copy the method bodies to their corresponding function body

        for (ExecutableElement entryPoint : compilationContext.getEntryPoints()) {
            compilationContext.enqueue(entryPoint);
        }

        compilationContext.processQueue();

        if (compilationContext.errors() > 0) {
            // bail out
            return false;
        }

        for (Consumer<? super CompilationContext> hook : postLowerHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a post-lower hook", e);
                compilationContext.error("Post-lower hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        compilationContext.cyclePhaseAttachments();

        // GENERATE phase

        Phase.GENERATE.setCurrent(compilationContext);

        wrapper = Consumer::accept;

        for (UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>> factory : generateTaskWrapperFactories) {
            wrapper = factory.apply(wrapper);
        }
        compilationContext.setTaskRunner(wrapper);

        compilationContext.setCopier(null);

        for (Consumer<? super CompilationContext> hook : preGenerateHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a pre-generate hook", e);
                compilationContext.error("Pre-generate hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        // Finalize

        for (Consumer<? super CompilationContext> hook : postGenerateHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                log.error("An exception was thrown in a post-generate hook " + hook.getClass().getName(), e);
                compilationContext.error("Post-generate hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        Phase.complete(compilationContext);

        return compilationContext.errors() == 0;
    }

    public void close() {
        for (ClassPathItem item : bootClassPath) {
            item.close();
        }
    }

    /**
     * Construct a new builder.
     *
     * @return the new builder (not {@code null})
     */
    public static Builder builder() {
        return new Builder();
    }

    public Iterable<Diagnostic> getDiagnostics() {
        return initialContext.getDiagnostics();
    }

    public static final class Builder {
        final List<ClassPathItem> bootClassPath = new ArrayList<>();
        final List<ClassPathItem> appClassPath = new ArrayList<>();
        final List<ClassPathItem> appModulePath = new ArrayList<>();
        final Map<Phase, Map<BuilderStage, List<BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder>>>> builderFactories = new EnumMap<>(Phase.class);
        final Map<Phase, List<BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>>>> copyFactories = new EnumMap<>(Phase.class);
        final List<BiFunction<? super ClassContext, DefinedTypeDefinition.Builder, DefinedTypeDefinition.Builder>> typeBuilderFactories = new ArrayList<>();
        final List<BiFunction<? super ClassContext, DescriptorTypeResolver, DescriptorTypeResolver>> resolverFactories = new ArrayList<>();
        final Map<Phase, List<Consumer<CompilationContext>>> preHooks = new EnumMap<>(Phase.class);
        final Map<Phase, List<Consumer<CompilationContext>>> postHooks = new EnumMap<>(Phase.class);
        final Map<Phase, List<UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>>>> taskWrapperFactories = new EnumMap<>(Phase.class);
        final List<UnaryOperator<NativeMethodConfigurator>> nativeMethodConfiguratorFactories = new ArrayList<>();

        Path outputDirectory = Path.of(".");
        BaseDiagnosticContext initialContext;
        Platform targetPlatform;
        TypeSystem typeSystem;
        java.util.function.Function<CompilationContext, Vm> vmFactory;
        CToolChain toolChain;
        LlvmToolChain llvmToolChain;
        ObjectFileProvider objectFileProvider;

        float threadsPerCpu = 2.0f;
        // 16 MB is the default stack size
        long stackSize = 0x1000000L;

        String mainClass;
        Consumer<ClassContext> classContextListener = Functions.discardingConsumer();

        Builder() {}

        public Builder setInitialContext(BaseDiagnosticContext initialContext) {
            this.initialContext = Assert.checkNotNullParam("initialContext", initialContext);
            return this;
        }

        public Builder addBootClassPathItem(ClassPathItem item) {
            if (item != null) {
                bootClassPath.add(item);
            }
            return this;
        }

        public Builder addAppClassPathItem(ClassPathItem item) {
            if (item != null) {
                appClassPath.add(item);
            }
            return this;
        }

        public Builder addAppModulePathItem(ClassPathItem item) {
            if (item != null) {
                appModulePath.add(item);
            }
            return this;
        }

        public String getMainClass() {
            return mainClass;
        }

        public Builder setMainClass(final String mainClass) {
            this.mainClass = Assert.checkNotNullParam("mainClass", mainClass);
            return this;
        }

        public Builder addResolverFactory(BiFunction<? super ClassContext, DescriptorTypeResolver, DescriptorTypeResolver> factory) {
            resolverFactories.add(factory);
            return this;
        }

        private static <V> EnumMap<BuilderStage, V> newBuilderStageMap(Object key) {
            return new EnumMap<BuilderStage, V>(BuilderStage.class);
        }

        private static <E> ArrayList<E> newArrayList(Object key) {
            return new ArrayList<>();
        }

        public Builder addBuilderFactory(Phase phase, BuilderStage stage, BiFunction<? super BasicBlockBuilder.FactoryContext, BasicBlockBuilder, BasicBlockBuilder> factory) {
            Assert.checkNotNullParam("phase", phase);
            Assert.checkNotNullParam("stage", stage);
            Assert.checkNotNullParam("factory", factory);
            builderFactories.computeIfAbsent(phase, Builder::newBuilderStageMap).computeIfAbsent(stage, Builder::newArrayList).add(factory);
            return this;
        }

        public Builder addCopyFactory(Phase phase, BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>, NodeVisitor<Node.Copier, Value, Node, BasicBlock, PointerValue>> factory) {
            Assert.checkNotNullParam("phase", phase);
            Assert.checkNotNullParam("factory", factory);
            copyFactories.computeIfAbsent(phase, Builder::newArrayList).add(factory);
            return this;
        }

        public Builder addTypeBuilderFactory(BiFunction<? super ClassContext, DefinedTypeDefinition.Builder, DefinedTypeDefinition.Builder> factory) {
            typeBuilderFactories.add(Assert.checkNotNullParam("factory", factory));
            return this;
        }

        public Builder addPreHook(Phase phase, Consumer<CompilationContext> hook) {
            if (hook != null) {
                Assert.checkNotNullParam("phase", phase);
                preHooks.computeIfAbsent(phase, Builder::newArrayList).add(hook);
            }
            return this;
        }

        public Builder addPostHook(Phase phase, Consumer<CompilationContext> hook) {
            if (hook != null) {
                Assert.checkNotNullParam("phase", phase);
                postHooks.computeIfAbsent(phase, Builder::newArrayList).add(hook);
            }
            return this;
        }

        public Builder addTaskWrapperFactory(Phase phase, UnaryOperator<BiConsumer<Consumer<CompilationContext>, CompilationContext>> factory) {
            if (factory != null) {
                Assert.checkNotNullParam("phase", phase);
                taskWrapperFactories.computeIfAbsent(phase, Builder::newArrayList).add(factory);
            }
            return this;
        }

        public Path getOutputDirectory() {
            return outputDirectory;
        }

        public Builder setOutputDirectory(final Path outputDirectory) {
            this.outputDirectory = Assert.checkNotNullParam("outputDirectory", outputDirectory);
            return this;
        }

        public Platform getTargetPlatform() {
            return targetPlatform;
        }

        public Builder setTargetPlatform(final Platform targetPlatform) {
            this.targetPlatform = targetPlatform;
            return this;
        }

        public TypeSystem getTypeSystem() {
            return typeSystem;
        }

        public Builder setTypeSystem(final TypeSystem typeSystem) {
            this.typeSystem = typeSystem;
            return this;
        }

        public java.util.function.Function<CompilationContext, Vm> getVmFactory() {
            return vmFactory;
        }

        public Builder setVmFactory(final java.util.function.Function<CompilationContext, Vm> vmFactory) {
            this.vmFactory = vmFactory;
            return this;
        }

        public CToolChain getToolChain() {
            return toolChain;
        }

        public Builder setToolChain(final CToolChain toolChain) {
            this.toolChain = toolChain;
            return this;
        }

        public LlvmToolChain getLlvmToolChain() {
            return llvmToolChain;
        }

        public Builder setLlvmToolChain(final LlvmToolChain llvmToolChain) {
            this.llvmToolChain = llvmToolChain;
            return this;
        }

        public ObjectFileProvider getObjectFileProvider() {
            return objectFileProvider;
        }

        public Builder setObjectFileProvider(final ObjectFileProvider objectFileProvider) {
            this.objectFileProvider = objectFileProvider;
            return this;
        }

        public float getThreadsPerCpu() {
            return threadsPerCpu;
        }

        public Builder setThreadsPerCpu(float threadsPerCpu) {
            Assert.checkMinimumParameter("threadsPerCpu", 0.0f, threadsPerCpu);
            this.threadsPerCpu = threadsPerCpu;
            return this;
        }

        public long getStackSize() {
            return stackSize;
        }

        public Builder setStackSize(long stackSize) {
            // 1 MB
            Assert.checkMinimumParameter("stackSize", 0x100000L, stackSize);
            this.stackSize = stackSize;
            return this;
        }

        public Builder addNativeMethodConfiguratorFactory(UnaryOperator<NativeMethodConfigurator> factory) {
            Assert.checkNotNullParam("factory", factory);
            nativeMethodConfiguratorFactories.add(factory);
            return this;
        }

        public Builder setClassContextListener(Consumer<ClassContext> classContextListener) {
            this.classContextListener = Assert.checkNotNullParam("classContextListener", classContextListener);
            return this;
        }

        public Driver build() {
            return new Driver(this);
        }
    }

    record BootModule(ClassPathItem item, ModuleDefinition moduleDefinition) implements Closeable {
        BootModule {
            Assert.checkNotNullParam("item", item);
            Assert.checkNotNullParam("moduleDefinition", moduleDefinition);
        }

        public void close() {
            item.close();
        }
    }
}
