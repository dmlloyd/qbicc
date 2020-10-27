package cc.quarkus.qcc.driver;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import cc.quarkus.qcc.context.AttachmentKey;
import cc.quarkus.qcc.context.CompilationContext;
import cc.quarkus.qcc.context.Diagnostic;
import cc.quarkus.qcc.context.Location;
import cc.quarkus.qcc.graph.BasicBlock;
import cc.quarkus.qcc.graph.BasicBlockBuilder;
import cc.quarkus.qcc.graph.Node;
import cc.quarkus.qcc.graph.NodeVisitor;
import cc.quarkus.qcc.graph.Value;
import cc.quarkus.qcc.graph.literal.LiteralFactory;
import cc.quarkus.qcc.graph.literal.TypeIdLiteral;
import cc.quarkus.qcc.graph.schedule.Schedule;
import cc.quarkus.qcc.interpreter.JavaObject;
import cc.quarkus.qcc.interpreter.JavaVM;
import cc.quarkus.qcc.machine.arch.Platform;
import cc.quarkus.qcc.machine.tool.CCompiler;
import cc.quarkus.qcc.tool.llvm.LlvmTool;
import cc.quarkus.qcc.type.TypeSystem;
import cc.quarkus.qcc.type.definition.ClassContext;
import cc.quarkus.qcc.type.definition.DefinedTypeDefinition;
import cc.quarkus.qcc.type.definition.MethodBody;
import cc.quarkus.qcc.type.definition.MethodHandle;
import cc.quarkus.qcc.type.definition.ModuleDefinition;
import cc.quarkus.qcc.type.definition.ResolvedTypeDefinition;
import cc.quarkus.qcc.type.definition.classfile.ClassFile;
import cc.quarkus.qcc.type.definition.element.ElementVisitor;
import cc.quarkus.qcc.type.definition.element.ExecutableElement;
import cc.quarkus.qcc.type.definition.element.InitializerElement;
import cc.quarkus.qcc.type.definition.element.MethodElement;
import cc.quarkus.qcc.type.descriptor.MethodDescriptor;
import cc.quarkus.qcc.type.descriptor.ParameterizedExecutableDescriptor;
import io.smallrye.common.constraint.Assert;

/**
 * A simple driver to run all the stages of compilation.
 */
public class Driver implements Closeable {

    static final String MODULE_INFO = "module-info.class";

    private static final AttachmentKey<String> MAIN_CLASS_KEY = new AttachmentKey<>();

    final BaseDiagnosticContext initialContext;
    final CompilationContextImpl compilationContext;
    final List<Consumer<? super CompilationContext>> preAddHooks;
    final List<Consumer<? super CompilationContext>> postAddHooks;
    final BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock>, NodeVisitor<Node.Copier, Value, Node, BasicBlock>> interStageCopy;
    final List<Consumer<? super CompilationContext>> preCopyHooks;
    final List<Consumer<? super CompilationContext>> preGenerateHooks;
    final List<Consumer<? super CompilationContext>> postGenerateHooks;
    final List<ElementVisitor<CompilationContext, Void>> generateVisitors;
    final Map<String, BootModule> bootModules;
    final List<ClassPathElement> bootClassPath;
    final AtomicReference<Phase> phaseSwitch = new AtomicReference<>(Phase.ADD);
    final String mainClass;
    final Path outputDir;

    /*
        Reachability (Run Time)

        A class is reachable when any instance of that class can exist at run time.  This can happen only
        when either its constructor is reachable at run time, or when an instance of that class
        is reachable via the heap from an entry point.  The existence of a variable of a class type
        is not sufficient to cause the class to be reachable (the variable could be null-only) - there
        must be an actual value.

        A non-virtual method is reachable only when it can be directly called by another reachable method.

        A virtual method is reachable when it (or a method that the virtual method overrides) can be called
        by a reachable method and when its class is reachable.

        A static field is reachable when it can be accessed by a reachable method.
     */

    Driver(final Builder builder) {
        initialContext = Assert.checkNotNullParam("builder.initialContext", builder.initialContext);
        mainClass = Assert.checkNotNullParam("builder.mainClass", builder.mainClass);
        outputDir = Assert.checkNotNullParam("builder.outputDirectory", builder.outputDirectory);
        initialContext.putAttachment(MAIN_CLASS_KEY, mainClass);
        // type system
        final TypeSystem typeSystem = builder.typeSystem;
        final LiteralFactory literalFactory = LiteralFactory.create(typeSystem);

        // boot modules
        Map<String, BootModule> bootModules = new HashMap<>();
        List<ClassPathElement> bootClassPath = new ArrayList<>();
        for (Path path : builder.bootClassPathElements) {
            // open all bootstrap JARs (MR bootstrap JARs not supported)
            ClassPathElement element;
            if (Files.isDirectory(path)) {
                element = new DirectoryClassPathElement(path);
            } else {
                try {
                    element = new JarFileClassPathElement(new JarFile(path.toFile(), true, ZipFile.OPEN_READ));
                } catch (Exception e) {
                    initialContext.error("Failed to open boot class path JAR \"%s\": %s", path, e);
                    continue;
                }
            }
            bootClassPath.add(element);
            try (ClassPathElement.Resource moduleInfo = element.getResource(MODULE_INFO)) {
                ByteBuffer buffer = moduleInfo.getBuffer();
                if (buffer == null) {
                    // ignore non-module
                    continue;
                }
                ModuleDefinition moduleDefinition = ModuleDefinition.create(buffer);
                bootModules.put(moduleDefinition.getName(), new BootModule(element, moduleDefinition));
            } catch (Exception e) {
                initialContext.error("Failed to read module from class path element \"%s\": %s", path, e);
            }
        }
        BootModule javaBase = bootModules.get("java.base");
        if (javaBase == null) {
            initialContext.error("Bootstrap failed: no java.base module found");
        }
        this.bootModules = bootModules;
        this.bootClassPath = bootClassPath;

        // phase factories

        ArrayList<BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder>> additivePhaseFactories = new ArrayList<>();
        for (List<BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder>> list : builder.additiveFactories.values()) {
            additivePhaseFactories.addAll(list);
        }
        Collections.reverse(additivePhaseFactories);
        additivePhaseFactories.trimToSize();

        ArrayList<BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder>> analyticPhaseFactories = new ArrayList<>();
        for (List<BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder>> list : builder.analyticFactories.values()) {
            analyticPhaseFactories.addAll(list);
        }
        Collections.reverse(analyticPhaseFactories);
        analyticPhaseFactories.trimToSize();

        ArrayList<BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock>, NodeVisitor<Node.Copier, Value, Node, BasicBlock>>> copiedCopyFactories = new ArrayList<>(builder.copyFactories);
        Collections.reverse(copiedCopyFactories);
        this.interStageCopy = (c, res) -> {
            for (BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock>, NodeVisitor<Node.Copier, Value, Node, BasicBlock>> copiedCopyFactory : copiedCopyFactories) {
                res = copiedCopyFactory.apply(c, res);
            }
            return res;
        };

        BiFunction<CompilationContext, ExecutableElement, BasicBlockBuilder> finalFactory = (ctxt, element) -> {
            List<BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder>> list;
            Phase phase = phaseSwitch.get();
            if (phase == Phase.ADD) {
                list = additivePhaseFactories;
            } else {
                assert phase == Phase.GENERATE;
                list = analyticPhaseFactories;
            }
            BasicBlockBuilder result = BasicBlockBuilder.simpleBuilder(typeSystem, element);
            for (BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder> item : list) {
                result = item.apply(ctxt, result);
            }
            return result;
        };

        final BiFunction<JavaObject, String, DefinedTypeDefinition> finder;
        JavaVM vm = builder.vm;
        if (vm != null) {
            finder = vm::loadClass;
        } else {
            // use a simple finder instead
            finder = this::defaultFinder;
        }

        compilationContext = new CompilationContextImpl(initialContext, typeSystem, literalFactory, finalFactory, finder, outputDir);

        generateVisitors = List.copyOf(builder.generateVisitors);

        // hooks

        preAddHooks = List.copyOf(builder.preAddHooks);
        postAddHooks = List.copyOf(builder.postAddHooks);
        preCopyHooks = List.copyOf(builder.preGenerateHooks);
        preGenerateHooks = List.copyOf(builder.preGenerateHooks);
        postGenerateHooks = List.copyOf(builder.postGenerateHooks);
    }

    private DefinedTypeDefinition defaultFinder(JavaObject classLoader, String name) {
        if (classLoader != null) {
            return null;
        }
        String fileName = name + ".class";
        ByteBuffer buffer;
        for (ClassPathElement element : bootClassPath) {
            try (ClassPathElement.Resource resource = element.getResource(fileName)) {
                buffer = resource.getBuffer();
                if (buffer == null) {
                    // non existent
                    continue;
                }
                ClassContext ctxt = compilationContext.getBootstrapClassContext();
                ClassFile classFile = ClassFile.of(ctxt, buffer);
                DefinedTypeDefinition.Builder builder = DefinedTypeDefinition.Builder.basic();
                classFile.accept(builder);
                DefinedTypeDefinition def = builder.build();
                ctxt.defineClass(name, def);
                return def;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public CompilationContext getCompilationContext() {
        return compilationContext;
    }

    private ResolvedTypeDefinition loadAndResolveBootstrapClass(String name) {
        DefinedTypeDefinition clazz = compilationContext.getBootstrapClassContext().findDefinedType(name);
        if (clazz == null) {
            compilationContext.error("Required bootstrap class \"%s\" was not found", name);
            return null;
        }
        try {
            return clazz.validate().resolve();
        } catch (Exception ex) {
            compilationContext.error(ex, "Failed to resolve bootstrap class \"%s\": %s", name, ex);
            return null;
        }
    }

    public static String getMainClass(CompilationContext context) {
        return context.getAttachment(MAIN_CLASS_KEY);
    }

    /**
     * Execute the compilation.
     *
     * @return {@code true} if compilation succeeded, {@code false} otherwise
     */
    public boolean execute() {
        CompilationContextImpl compilationContext = this.compilationContext;
        for (Consumer<? super CompilationContext> hook : preAddHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                compilationContext.error(e, "Pre-additive hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }
        // find all the entry points
        // todo: for now it's just the one main method
        ResolvedTypeDefinition resolvedMainClass = loadAndResolveBootstrapClass(this.mainClass);
        if (resolvedMainClass == null) {
            return false;
        }
        ResolvedTypeDefinition stringClass = loadAndResolveBootstrapClass("java/lang/String");
        if (stringClass == null) {
            return false;
        }
        TypeIdLiteral stringId = stringClass.getTypeId();
        TypeSystem ts = compilationContext.getTypeSystem();
        LiteralFactory lf = compilationContext.getLiteralFactory();
        int idx = resolvedMainClass.findMethodIndex("main", MethodDescriptor.of(ParameterizedExecutableDescriptor.of(ts.getReferenceType(lf.literalOfArrayType(ts.getReferenceType(stringId)))), ts.getVoidType()));
        if (idx == -1) {
            compilationContext.error("No valid main method found on \"%s\"", this.mainClass);
            return false;
        }
        MethodElement mainMethod = resolvedMainClass.getMethod(idx);
        if (! mainMethod.hasAllModifiersOf(ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) {
            compilationContext.error("Main method must be declared public static on \"%s\"", this.mainClass);
            return false;
        }
        compilationContext.registerEntryPoint(mainMethod);

        // trace out the program graph, enqueueing each item one time and then processing every item in the queue;
        // in this stage we're just loading everything that *might* be reachable

        for (MethodElement entryPoint : compilationContext.getEntryPoints()) {
            compilationContext.enqueue(entryPoint);
        }

        ExecutableElement element = compilationContext.dequeue();
        if (element != null) do {
            // make sure the initializer is enqueued
            InitializerElement initializer = element.getEnclosingType().validate().resolve().getInitializer();
            if (initializer != null) {
                compilationContext.enqueue(initializer);
            }
            MethodHandle methodHandle = element.getMethodBody();
            if (methodHandle != null) {
                // cause method and field references to be resolved
                try {
                    methodHandle.getOrCreateMethodBody();
                } catch (Exception e) {
                    compilationContext.error(e, element, "Exception while constructing method body: %s", e);
                }
            }
            element = compilationContext.dequeue();
        } while (element != null);

        if (compilationContext.errors() > 0) {
            // bail out
            return false;
        }

        for (Consumer<? super CompilationContext> hook : postAddHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                compilationContext.error(e, "Post-additive hook failed: %s", e);
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

        compilationContext.clearEnqueuedSet();
        phaseSwitch.set(Phase.GENERATE);

        for (Consumer<? super CompilationContext> hook : preCopyHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                compilationContext.error(e, "Pre-analytic hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        // In this phase we start from the entry points again, and then copy (and filter) all of the nodes to a smaller reachable set

        for (MethodElement entryPoint : compilationContext.getEntryPoints()) {
            compilationContext.enqueue(entryPoint);
        }

        element = compilationContext.dequeue();
        if (element != null) do {
            // make sure the initializer is enqueued
            InitializerElement initializer = element.getEnclosingType().validate().resolve().getInitializer();
            if (initializer != null) {
                compilationContext.enqueue(initializer);
            }
            MethodHandle methodHandle = element.getMethodBody();
            if (methodHandle != null) {
                // rewrite the method body
                ClassContext classContext = element.getEnclosingType().getContext();
                MethodBody original = methodHandle.getOrCreateMethodBody();
                BasicBlock entryBlock = original.getEntryBlock();
                List<Value> paramValues = original.getParameterValues();
                Value thisValue = original.getThisValue();
                BasicBlock copyBlock = Node.Copier.execute(entryBlock, classContext.newBasicBlockBuilder(element), compilationContext, interStageCopy);
                methodHandle.replaceMethodBody(MethodBody.of(copyBlock, Schedule.forMethod(copyBlock), thisValue, paramValues));
            }
            element = compilationContext.dequeue();
        } while (element != null);

        if (compilationContext.errors() > 0) {
            // bail out
            return false;
        }
        for (Consumer<? super CompilationContext> hook : preGenerateHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                compilationContext.error(e, "Post-copy hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        // Visit each reachable node to build the executable program

        compilationContext.clearEnqueuedSet();

        for (MethodElement entryPoint : compilationContext.getEntryPoints()) {
            compilationContext.enqueue(entryPoint);
        }

        List<ElementVisitor<CompilationContext, Void>> generateVisitors = this.generateVisitors;

        element = compilationContext.dequeue();
        if (element != null) do {
            for (ElementVisitor<CompilationContext, Void> elementVisitor : generateVisitors) {
                try {
                    element.accept(elementVisitor, compilationContext);
                } catch (Exception e) {
                    compilationContext.error(e, element, "Element visitor threw an exception: %s", e);
                }
            }
            element = compilationContext.dequeue();
        } while (element != null);

        // Finalize

        for (Consumer<? super CompilationContext> hook : postGenerateHooks) {
            try {
                hook.accept(compilationContext);
            } catch (Exception e) {
                compilationContext.error(e, "Post-analytic hook failed: %s", e);
            }
            if (compilationContext.errors() > 0) {
                // bail out
                return false;
            }
        }

        return compilationContext.errors() == 0;
    }

    public void close() {
        for (ClassPathElement element : bootClassPath) {
            try {
                element.close();
            } catch (IOException e) {
                compilationContext.warning(Location.builder().setSourceFilePath(element.getName()).build(), "Failed to close boot class path element: %s", e);
            }
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
        final List<Path> bootClassPathElements = new ArrayList<>();
        final Map<BuilderStage, List<BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder>>> additiveFactories = new EnumMap<>(BuilderStage.class);
        final Map<BuilderStage, List<BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder>>> analyticFactories = new EnumMap<>(BuilderStage.class);
        final List<BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock>, NodeVisitor<Node.Copier, Value, Node, BasicBlock>>> copyFactories = new ArrayList<>();
        final List<BiFunction<? super CompilationContext, DefinedTypeDefinition.Builder, DefinedTypeDefinition.Builder>> typeBuilderFactories = new ArrayList<>();
        final List<Consumer<? super CompilationContext>> preAddHooks = new ArrayList<>();
        final List<Consumer<? super CompilationContext>> postAddHooks = new ArrayList<>();
        final List<Consumer<? super CompilationContext>> preCopyHooks = new ArrayList<>();
        final List<Consumer<? super CompilationContext>> preGenerateHooks = new ArrayList<>();
        final List<Consumer<? super CompilationContext>> postGenerateHooks = new ArrayList<>();
        final List<ElementVisitor<CompilationContext, Void>> generateVisitors = new ArrayList<>();

        Path outputDirectory;
        BaseDiagnosticContext initialContext;
        Platform targetPlatform;
        TypeSystem typeSystem;
        JavaVM vm;
        CCompiler toolChain;
        LlvmTool llvmTool;

        String mainClass;

        Builder() {}

        public Builder setInitialContext(BaseDiagnosticContext initialContext) {
            this.initialContext = Assert.checkNotNullParam("initialContext", initialContext);
            return this;
        }

        public Builder addBootClassPathElement(Path path) {
            if (path != null) {
                bootClassPathElements.add(path);
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

        public Builder addAdditivePhaseBlockBuilderFactory(BuilderStage stage, BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder> factory) {
            additiveFactories.computeIfAbsent(Assert.checkNotNullParam("stage", stage), s -> new ArrayList<>()).add(Assert.checkNotNullParam("factory", factory));
            return this;
        }

        public Builder addAnalyticPhaseBlockBuilderFactory(BuilderStage stage, BiFunction<? super CompilationContext, BasicBlockBuilder, BasicBlockBuilder> factory) {
            analyticFactories.computeIfAbsent(Assert.checkNotNullParam("stage", stage), s -> new ArrayList<>()).add(Assert.checkNotNullParam("factory", factory));
            return this;
        }

        public Builder addCopyFactory(BiFunction<CompilationContext, NodeVisitor<Node.Copier, Value, Node, BasicBlock>, NodeVisitor<Node.Copier, Value, Node, BasicBlock>> factory) {
            copyFactories.add(Assert.checkNotNullParam("factory", factory));
            return this;
        }

        public Builder addTypeBuilderFactory(BiFunction<? super CompilationContext, DefinedTypeDefinition.Builder, DefinedTypeDefinition.Builder> factory) {
            typeBuilderFactories.add(Assert.checkNotNullParam("factory", factory));
            return this;
        }

        public Builder addPreAdditiveHook(Consumer<? super CompilationContext> hook) {
            if (hook != null) {
                preAddHooks.add(hook);
            }
            return this;
        }

        public Builder addPostAdditiveHook(Consumer<? super CompilationContext> hook) {
            if (hook != null) {
                preAddHooks.add(hook);
            }
            return this;
        }

        public Builder addPreAnalyticHook(Consumer<? super CompilationContext> hook) {
            if (hook != null) {
                preGenerateHooks.add(hook);
            }
            return this;
        }

        public Builder addPostAnalyticHook(Consumer<? super CompilationContext> hook) {
            if (hook != null) {
                preGenerateHooks.add(hook);
            }
            return this;
        }

        public Builder addGenerateVisitor(ElementVisitor<CompilationContext, Void> visitor) {
            generateVisitors.add(Assert.checkNotNullParam("visitor", visitor));
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

        public JavaVM getVm() {
            return vm;
        }

        public Builder setVm(final JavaVM vm) {
            this.vm = vm;
            return this;
        }

        public CCompiler getToolChain() {
            return toolChain;
        }

        public Builder setToolChain(final CCompiler toolChain) {
            this.toolChain = toolChain;
            return this;
        }

        public LlvmTool getLlvmTool() {
            return llvmTool;
        }

        public Builder setLlvmTool(final LlvmTool llvmTool) {
            this.llvmTool = llvmTool;
            return this;
        }

        public Driver build() {
            return new Driver(this);
        }
    }

    static final class BootModule implements Closeable {
        private final ClassPathElement element;
        private final ModuleDefinition moduleDefinition;

        BootModule(final ClassPathElement element, final ModuleDefinition moduleDefinition) {
            this.element = element;
            this.moduleDefinition = moduleDefinition;
        }

        public void close() throws IOException {
            element.close();
        }
    }
}
