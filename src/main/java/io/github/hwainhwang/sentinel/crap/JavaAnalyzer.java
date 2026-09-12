package io.github.hwainhwang.sentinel.crap;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CatchTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

/** Inventories Java callables from the JDK compiler tree without executing source code. */
public final class JavaAnalyzer {
    private static final Map<TypeKind, String> SIMPLE_DESCRIPTORS = descriptors();

    private JavaAnalyzer() {
        throw new AssertionError("no instances");
    }

    public static List<Models.CallableDefinition> analyze(byte[] source, String moduleRelativePath) {
        Map<String, byte[]> sources = new LinkedHashMap<>();
        sources.put(moduleRelativePath, source);
        return analyzeAll(sources);
    }

    public static List<Models.CallableDefinition> analyzeAll(Map<String, byte[]> sourceBytes) {
        return analyzeAll(sourceBytes, List.of());
    }

    public static List<Models.CallableDefinition> analyzeAll(
            Map<String, byte[]> sourceBytes, List<Path> dependencyClasspath) {
        if (sourceBytes == null || sourceBytes.isEmpty()) {
            throw new JavaAnalysisException("sourceMissing");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new JavaAnalysisException("jdkCompilerUnavailable");
        }
        List<SourceUnit> sources = prepareSources(sourceBytes);
        return compileAndAnalyze(compiler, sources, validateClasspath(dependencyClasspath));
    }

    private static List<Path> validateClasspath(List<Path> dependencyClasspath) {
        if (dependencyClasspath == null) {
            throw new JavaAnalysisException("dependencyClasspathMissing");
        }
        List<Path> paths = new ArrayList<>();
        Set<Path> unique = new HashSet<>();
        for (Path value : dependencyClasspath) {
            Path path = validateClasspathEntry(value);
            if (!unique.add(path)) {
                throw new JavaAnalysisException("dependencyClasspathDuplicate");
            }
            paths.add(path);
        }
        return List.copyOf(paths);
    }

    private static Path validateClasspathEntry(Path value) {
        if (value == null || !value.normalize().equals(value) || Files.isSymbolicLink(value)) {
            throw new JavaAnalysisException("dependencyClasspathInvalid");
        }
        boolean validType = Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)
                || Files.isDirectory(value, LinkOption.NOFOLLOW_LINKS);
        if (!validType) {
            throw new JavaAnalysisException("dependencyClasspathInvalid");
        }
        return value;
    }

    private static List<SourceUnit> prepareSources(Map<String, byte[]> sourceBytes) {
        List<SourceUnit> sources = new ArrayList<>(sourceBytes.size());
        for (Map.Entry<String, byte[]> entry : sourceBytes.entrySet()) {
            try {
                Models.validateModulePath(entry.getKey());
                sources.add(SourceUnit.create(entry.getKey(), entry.getValue()));
            } catch (IllegalArgumentException error) {
                throw new JavaAnalysisException("sourceInvalid:" + entry.getKey(), error);
            }
        }
        sources.sort(JavaAnalyzer::compareSourceUnits);
        return List.copyOf(sources);
    }

    private static int compareSourceUnits(SourceUnit left, SourceUnit right) {
        return SemanticSite.compareUtf8(left.path(), right.path());
    }

    private static List<Models.CallableDefinition> compileAndAnalyze(
            JavaCompiler compiler, List<SourceUnit> sources, List<Path> dependencyClasspath) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                diagnostics, java.util.Locale.ROOT, StandardCharsets.UTF_8)) {
            Compilation compilation = createCompilation(
                    compiler, files, diagnostics, sources, dependencyClasspath);
            List<com.sun.source.tree.CompilationUnitTree> units = parsedUnits(compilation.task());
            Set<Tree> explicitCallables = explicitCallables(units);
            compilation.task().analyze();
            rejectErrors(diagnostics);
            return inventory(compilation.task(), units, compilation.byUri(), explicitCallables);
        } catch (JavaAnalysisException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new JavaAnalysisException("javaAnalysisFailed", error);
        }
    }

    private static List<com.sun.source.tree.CompilationUnitTree> parsedUnits(JavacTask task)
            throws IOException {
        List<com.sun.source.tree.CompilationUnitTree> units = new ArrayList<>();
        for (com.sun.source.tree.CompilationUnitTree unit : task.parse()) {
            units.add(unit);
        }
        return List.copyOf(units);
    }

    private static Set<Tree> explicitCallables(
            List<com.sun.source.tree.CompilationUnitTree> units) {
        Set<Tree> callables = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        ExplicitCallableCollector collector = new ExplicitCallableCollector(callables);
        for (com.sun.source.tree.CompilationUnitTree unit : units) {
            collector.scan(unit, null);
        }
        return callables;
    }

    private static Compilation createCompilation(
            JavaCompiler compiler,
            StandardJavaFileManager files,
            DiagnosticCollector<JavaFileObject> diagnostics,
            List<SourceUnit> sources,
            List<Path> dependencyClasspath) throws IOException {
        files.setLocationFromPaths(StandardLocation.CLASS_PATH, dependencyClasspath);
        List<JavaFileObject> filesToCompile = new ArrayList<>(sources.size());
        Map<URI, SourceUnit> byUri = new HashMap<>();
        for (SourceUnit source : sources) {
            MemorySource file = new MemorySource(source);
            filesToCompile.add(file);
            byUri.put(file.toUri(), source);
        }
        JavacTask task = (JavacTask) compiler.getTask(
                null,
                files,
                diagnostics,
                List.of("-proc:none", "--release", "17"),
                null,
                filesToCompile);
        return new Compilation(task, Map.copyOf(byUri));
    }

    private static void rejectErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                throw new JavaAnalysisException(
                        "sourceSyntaxOrTypeInvalid:" + diagnostic.getCode());
            }
        }
    }

    private static List<Models.CallableDefinition> inventory(
            JavacTask task,
            Iterable<? extends com.sun.source.tree.CompilationUnitTree> units,
            Map<URI, SourceUnit> byUri,
            Set<Tree> explicitCallables) {
        Trees trees = Trees.instance(task);
        AnalysisScanner scanner = new AnalysisScanner(
                trees, task.getElements(), task.getTypes(), explicitCallables);
        for (com.sun.source.tree.CompilationUnitTree unit : units) {
            SourceUnit source = byUri.get(unit.getSourceFile().toUri());
            if (source == null) {
                throw new JavaAnalysisException("sourceIdentityMissing");
            }
            scanner.scanUnit(unit, source);
        }
        return scanner.finish();
    }

    private static Map<TypeKind, String> descriptors() {
        Map<TypeKind, String> values = new EnumMap<>(TypeKind.class);
        values.put(TypeKind.BOOLEAN, "Z");
        values.put(TypeKind.BYTE, "B");
        values.put(TypeKind.SHORT, "S");
        values.put(TypeKind.INT, "I");
        values.put(TypeKind.LONG, "J");
        values.put(TypeKind.CHAR, "C");
        values.put(TypeKind.FLOAT, "F");
        values.put(TypeKind.DOUBLE, "D");
        values.put(TypeKind.VOID, "V");
        return Map.copyOf(values);
    }

    private record Compilation(JavacTask task, Map<URI, SourceUnit> byUri) {}

    private record LocatedDefinition(String path, Models.CallableDefinition definition) {}

    private record Owner(String semanticName, String jacocoInternalName) {}

    private static final class ExplicitCallableCollector extends TreeScanner<Void, Void> {
        private final Set<Tree> callables;

        private ExplicitCallableCollector(Set<Tree> callables) {
            this.callables = callables;
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            callables.add(tree);
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            callables.add(tree);
            return super.visitLambdaExpression(tree, unused);
        }
    }

    private static final class MemorySource extends SimpleJavaFileObject {
        private final String content;

        private MemorySource(SourceUnit source) {
            super(uri(source.path()), Kind.SOURCE);
            content = source.text();
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }

        private static URI uri(String path) {
            try {
                return new URI("memory", null, "/" + path, null);
            } catch (URISyntaxException error) {
                throw new JavaAnalysisException("sourcePathInvalid", error);
            }
        }
    }

    private record SourceUnit(String path, String text, long[] byteOffsets) {
        private static SourceUnit create(String path, byte[] source) {
            if (source == null) {
                throw new IllegalArgumentException("sourceMissing");
            }
            int bomBytes = hasBom(source) ? 3 : 0;
            String text = decode(source, bomBytes);
            return new SourceUnit(path, text, offsets(text, bomBytes));
        }

        private Models.SourceRange range(long startCharacter, long endCharacter) {
            if (startCharacter < 0 || endCharacter <= startCharacter
                    || endCharacter >= byteOffsets.length) {
                throw new JavaAnalysisException("sourceRangeUnavailable");
            }
            long start = byteOffset(startCharacter);
            long end = byteOffset(endCharacter);
            return new Models.SourceRange(start, end);
        }

        private long byteOffset(long character) {
            if (character > Integer.MAX_VALUE || byteOffsets[(int) character] < 0) {
                throw new JavaAnalysisException("sourcePositionSplitsUnicodeScalar");
            }
            return byteOffsets[(int) character];
        }

        private static boolean hasBom(byte[] source) {
            return source.length >= 3
                    && source[0] == (byte) 0xef
                    && source[1] == (byte) 0xbb
                    && source[2] == (byte) 0xbf;
        }

        private static String decode(byte[] source, int offset) {
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(source, offset, source.length - offset))
                        .toString();
            } catch (CharacterCodingException error) {
                throw new IllegalArgumentException("sourceNotUtf8", error);
            }
        }

        private static long[] offsets(String text, int bomBytes) {
            long[] offsets = new long[text.length() + 1];
            java.util.Arrays.fill(offsets, -1);
            long byteOffset = bomBytes;
            for (int index = 0; index < text.length(); ) {
                offsets[index] = byteOffset;
                int codePoint = text.codePointAt(index);
                int characters = Character.charCount(codePoint);
                byteOffset += utf8Length(codePoint);
                index += characters;
            }
            offsets[text.length()] = byteOffset;
            return offsets;
        }

        private static int utf8Length(int codePoint) {
            if (codePoint <= 0x7f) {
                return 1;
            }
            if (codePoint <= 0x7ff) {
                return 2;
            }
            return codePoint <= 0xffff ? 3 : 4;
        }
    }

    private static final class AnalysisScanner extends TreePathScanner<Void, Void> {
        private final Trees trees;
        private final Elements elements;
        private final Types types;
        private final SourcePositions positions;
        private final Set<Tree> explicitCallables;
        private final Deque<Owner> owners;
        private final Deque<Models.CallableIdentity> callables;
        private final Deque<Integer> callableFloors;
        private final List<LocatedDefinition> definitions;
        private com.sun.source.tree.CompilationUnitTree unit;
        private SourceUnit source;

        private AnalysisScanner(
                Trees trees, Elements elements, Types types, Set<Tree> explicitCallables) {
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.explicitCallables = explicitCallables;
            positions = trees.getSourcePositions();
            owners = new ArrayDeque<>();
            callables = new ArrayDeque<>();
            callableFloors = new ArrayDeque<>();
            definitions = new ArrayList<>();
        }

        private void scanUnit(
                com.sun.source.tree.CompilationUnitTree compilationUnit, SourceUnit sourceUnit) {
            unit = compilationUnit;
            source = sourceUnit;
            scan(new TreePath(compilationUnit), null);
        }

        private List<Models.CallableDefinition> finish() {
            rejectDuplicateIdentities();
            definitions.sort(AnalysisScanner::compareDefinitions);
            return definitions.stream().map(LocatedDefinition::definition).toList();
        }

        private static int compareDefinitions(LocatedDefinition left, LocatedDefinition right) {
            int path = SemanticSite.compareUtf8(left.path(), right.path());
            if (path != 0) {
                return path;
            }
            int start = Long.compare(
                    left.definition().sourceRange().startByte(),
                    right.definition().sourceRange().startByte());
            if (start != 0) {
                return start;
            }
            return SemanticSite.compareUtf8(
                    left.definition().identity().callableId(),
                    right.definition().identity().callableId());
        }

        private void rejectDuplicateIdentities() {
            Set<String> identities = new HashSet<>();
            for (LocatedDefinition item : definitions) {
                if (!identities.add(item.definition().identity().callableId())) {
                    throw new JavaAnalysisException(
                            "identityAmbiguous:" + item.definition().identity().callableId());
                }
            }
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            rejectUnownedDecisions(tree);
            Owner owner = owner(tree);
            owners.push(owner);
            callableFloors.push(callables.size());
            try {
                return super.visitClass(tree, unused);
            } finally {
                callableFloors.pop();
                owners.pop();
            }
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            if (!explicitCallables.contains(tree)) {
                return null;
            }
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end <= start) {
                return null;
            }
            ExecutableElement element = executableElement();
            String sourceDescriptor = executableDescriptor((ExecutableType) element.asType());
            String classfileDescriptor = classfileDescriptor(element, sourceDescriptor);
            Models.CallableIdentity identity = methodIdentity(
                    element, classfileDescriptor == null ? sourceDescriptor : classfileDescriptor);
            addDefinition(
                    identity,
                    start,
                    end,
                    DecisionCounter.count(tree.getBody()),
                    classfileDescriptor != null);
            callables.push(identity);
            try {
                return super.visitMethod(tree, unused);
            } finally {
                callables.pop();
            }
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            if (!explicitCallables.contains(tree)) {
                return null;
            }
            if (owners.isEmpty()) {
                throw new JavaAnalysisException("lambdaOwnerMissing");
            }
            TypeMirror target = trees.getTypeMirror(getCurrentPath());
            if (target == null) {
                throw new JavaAnalysisException("lambdaTargetTypeUnavailable");
            }
            String targetType = semanticType(target);
            String role = lambdaRole(getCurrentPath(), tree);
            String site = SemanticSite.lambdaDescriptor(
                    lambdaAnchor(), targetType, role);
            Models.CallableIdentity identity = new Models.CallableIdentity(
                    source.path(),
                    Models.CallableKind.LAMBDA,
                    owners.peek().semanticName(),
                    "lambda",
                    "{" + targetType + "}",
                    site);
            addLambda(identity, tree);
            callables.push(identity);
            try {
                return super.visitLambdaExpression(tree, unused);
            } finally {
                callables.pop();
            }
        }

        private String lambdaRole(TreePath path, LambdaExpressionTree lambda) {
            List<String> roles = new ArrayList<>();
            Tree child = lambda;
            for (TreePath cursor = path.getParentPath(); cursor != null; cursor = cursor.getParentPath()) {
                Tree parent = cursor.getLeaf();
                if (parent instanceof MethodTree
                        || parent instanceof LambdaExpressionTree
                        || parent instanceof ClassTree) {
                    break;
                }
                String role = edgeRole(cursor, child);
                if (role != null) {
                    roles.add(role);
                }
                child = parent;
            }
            roles.add("arity:" + lambda.getParameters().size());
            roles.add("body-kind:" + lambda.getBodyKind().name());
            return String.join("/", roles);
        }

        private String edgeRole(TreePath parentPath, Tree child) {
            Tree parent = parentPath.getLeaf();
            String declaration = declarationRole(parent, child);
            if (declaration != null) {
                return declaration;
            }
            String call = callRole(parent, child);
            if (call != null) {
                return call;
            }
            String branch = branchRole(parent, child);
            if (branch != null) {
                return branch;
            }
            String wrapper = wrapperRole(parentPath, parent, child);
            return wrapper == null ? parent.getKind().name().toLowerCase(java.util.Locale.ROOT) : wrapper;
        }

        private String declarationRole(Tree parent, Tree child) {
            if (parent instanceof VariableTree variable && variable.getInitializer() == child) {
                return "binding:" + variable.getName();
            }
            if (parent instanceof AssignmentTree assignment && assignment.getExpression() == child) {
                return "assignment:" + assignment.getVariable();
            }
            return null;
        }

        private String callRole(Tree parent, Tree child) {
            if (parent instanceof MethodInvocationTree call && containsIdentity(call.getArguments(), child)) {
                return "call-argument:" + call.getMethodSelect();
            }
            if (parent instanceof NewClassTree construction
                    && containsIdentity(construction.getArguments(), child)) {
                return "constructor-argument:" + construction.getIdentifier();
            }
            if (parent instanceof NewClassTree construction && construction.getClassBody() == child) {
                return "anonymous-body:" + construction.getIdentifier();
            }
            return null;
        }

        private String branchRole(Tree parent, Tree child) {
            if (parent instanceof ConditionalExpressionTree conditional) {
                return conditionalRole(conditional, child);
            }
            return nonConditionalBranchRole(parent, child);
        }

        private String nonConditionalBranchRole(Tree parent, Tree child) {
            if (parent instanceof IfTree conditional) {
                return ifRole(conditional, child);
            }
            return binaryRole(parent, child);
        }

        private String binaryRole(Tree parent, Tree child) {
            if (parent instanceof BinaryTree binary) {
                return binaryOperandRole(binary, child);
            }
            return null;
        }

        private String binaryOperandRole(BinaryTree binary, Tree child) {
            return binary.getLeftOperand() == child
                    ? "binary-left:" + binary.getKind()
                    : "binary-right:" + binary.getKind();
        }

        private String conditionalRole(ConditionalExpressionTree conditional, Tree child) {
            if (conditional.getTrueExpression() == child) {
                return "conditional-true";
            }
            return "conditional-false";
        }

        private String ifRole(IfTree conditional, Tree child) {
            if (conditional.getThenStatement() == child) {
                return "if-then";
            }
            return "if-else";
        }

        private String wrapperRole(TreePath parentPath, Tree parent, Tree child) {
            if (parent instanceof TypeCastTree cast) {
                return castRole(parentPath, cast, child);
            }
            return nonCastWrapperRole(parent, child);
        }

        private String castRole(TreePath parentPath, TypeCastTree cast, Tree child) {
            if (cast.getExpression() != child) {
                return null;
            }
            TypeMirror castType = trees.getTypeMirror(new TreePath(parentPath, cast.getType()));
            if (castType == null) {
                throw new JavaAnalysisException("lambdaCastTypeUnavailable");
            }
            return "target-cast:" + semanticType(castType);
        }

        private String nonCastWrapperRole(Tree parent, Tree child) {
            if (parent instanceof ParenthesizedTree) {
                return null;
            }
            return arrayOrReturnRole(parent, child);
        }

        private String arrayOrReturnRole(Tree parent, Tree child) {
            if (parent instanceof NewArrayTree array) {
                return arrayRole(array, child);
            }
            return parent instanceof ReturnTree ? "return" : null;
        }

        private String arrayRole(NewArrayTree array, Tree child) {
            return containsIdentity(array.getInitializers(), child) ? "array-element" : null;
        }

        private boolean containsIdentity(List<? extends Tree> values, Tree expected) {
            if (values == null) {
                return false;
            }
            for (Tree value : values) {
                if (value == expected) {
                    return true;
                }
            }
            return false;
        }

        private String lambdaAnchor() {
            int floor = callableFloors.isEmpty() ? 0 : callableFloors.peek();
            if (callables.size() > floor) {
                return callables.peek().callableId();
            }
            return owners.peek().semanticName() + "#<initializer>";
        }

        private void addLambda(Models.CallableIdentity identity, LambdaExpressionTree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            Models.CallableDefinition definition = new Models.CallableDefinition(
                    identity,
                    source.range(start, end),
                    line(start),
                    line(end - 1),
                    DecisionCounter.count(tree.getBody()),
                    null,
                    null);
            definitions.add(new LocatedDefinition(source.path(), definition));
        }

        private void addDefinition(
                Models.CallableIdentity identity,
                long start,
                long end,
                int complexity,
                boolean classfileMappingAvailable) {
            Owner owner = owners.peek();
            if (owner == null) {
                throw new JavaAnalysisException("callableOwnerMissing");
            }
            Models.CallableDefinition definition = new Models.CallableDefinition(
                    identity,
                    source.range(start, end),
                    line(start),
                    line(end - 1),
                    complexity,
                    classfileMappingAvailable ? owner.jacocoInternalName() : null,
                    classfileMappingAvailable ? identity.callableName() : null);
            definitions.add(new LocatedDefinition(source.path(), definition));
        }

        private String classfileDescriptor(
                ExecutableElement executable, String sourceDescriptor) {
            if (executable.getKind() != ElementKind.CONSTRUCTOR) {
                return sourceDescriptor;
            }
            return constructorDescriptor(executable, sourceDescriptor);
        }

        private String constructorDescriptor(
                ExecutableElement executable, String sourceDescriptor) {
            Element enclosing = executable.getEnclosingElement();
            if (!(enclosing instanceof TypeElement type)) {
                throw new JavaAnalysisException("constructorOwnerUnavailable");
            }
            if (type.getKind() == ElementKind.ENUM) {
                return prependConstructorParameters(
                        sourceDescriptor, "Ljava/lang/String;I");
            }
            return nestedConstructorDescriptor(type, sourceDescriptor);
        }

        private String nestedConstructorDescriptor(TypeElement type, String sourceDescriptor) {
            NestingKind nesting = type.getNestingKind();
            if (captureDescriptorUnavailable(nesting)) {
                return null;
            }
            if (nesting != NestingKind.MEMBER) {
                return sourceDescriptor;
            }
            return memberConstructorDescriptor(type, sourceDescriptor);
        }

        private boolean captureDescriptorUnavailable(NestingKind nesting) {
            return nesting == NestingKind.LOCAL || nesting == NestingKind.ANONYMOUS;
        }

        private String memberConstructorDescriptor(TypeElement type, String sourceDescriptor) {
            if (type.getModifiers().contains(Modifier.STATIC)) {
                return sourceDescriptor;
            }
            return prependConstructorParameters(
                    sourceDescriptor, enclosingInstanceDescriptor(type));
        }

        private String enclosingInstanceDescriptor(TypeElement type) {
            Element enclosing = type.getEnclosingElement();
            if (!(enclosing instanceof TypeElement enclosingType)) {
                throw new JavaAnalysisException("enclosingInstanceTypeUnavailable");
            }
            return typeDescriptor(enclosingType.asType());
        }

        private String prependConstructorParameters(String descriptor, String prefix) {
            if (!descriptor.startsWith("(")) {
                throw new JavaAnalysisException("constructorDescriptorInvalid");
            }
            return "(" + prefix + descriptor.substring(1);
        }

        private void rejectUnownedDecisions(ClassTree tree) {
            for (Tree member : tree.getMembers()) {
                Tree initializer = memberInitializer(member);
                if (initializer != null && DecisionCounter.count(initializer) > 1) {
                    throw new JavaAnalysisException("decisionOutsideCallable");
                }
            }
        }

        private Tree memberInitializer(Tree member) {
            if (member instanceof VariableTree variable) {
                return variable.getInitializer();
            }
            return member instanceof BlockTree block ? block : null;
        }

        private long line(long characterOffset) {
            long line = unit.getLineMap().getLineNumber(characterOffset);
            if (line < 1) {
                throw new JavaAnalysisException("sourceLineUnavailable");
            }
            return line;
        }

        private Models.CallableIdentity methodIdentity(
                ExecutableElement element, String descriptor) {
            Owner owner = owners.peek();
            if (owner == null) {
                throw new JavaAnalysisException("callableOwnerMissing");
            }
            boolean constructor = element.getKind() == ElementKind.CONSTRUCTOR;
            String name = constructor ? "<init>" : element.getSimpleName().toString();
            return new Models.CallableIdentity(
                    source.path(),
                    constructor ? Models.CallableKind.CONSTRUCTOR : Models.CallableKind.METHOD,
                    owner.semanticName(),
                    name,
                    descriptor,
                    null);
        }

        private ExecutableElement executableElement() {
            Element element = trees.getElement(getCurrentPath());
            if (!(element instanceof ExecutableElement executable)) {
                throw new JavaAnalysisException("callableElementUnavailable");
            }
            return executable;
        }

        private Owner owner(ClassTree tree) {
            Element element = trees.getElement(getCurrentPath());
            if (!(element instanceof TypeElement type)) {
                throw new JavaAnalysisException("classElementUnavailable");
            }
            String binaryName = elements.getBinaryName(type).toString();
            String jacocoName = binaryName.isEmpty() ? null : binaryName.replace('.', '/');
            if (stableNesting(type.getNestingKind())) {
                return new Owner(binaryName, jacocoName);
            }
            return semanticLocalOwner(type, tree, jacocoName);
        }

        private Owner semanticLocalOwner(
                TypeElement type, ClassTree tree, String jacocoInternalName) {
            String parent = semanticAnchor();
            String simpleName = type.getSimpleName().toString();
            String kind = simpleName.isEmpty() ? "anonymous" : "local:" + simpleName;
            String site = SemanticSite.lambdaDescriptor(parent, kind, classRole(getCurrentPath(), tree));
            return new Owner(parent + "$" + kind + ":" + site, jacocoInternalName);
        }

        private String classRole(TreePath path, ClassTree tree) {
            List<String> roles = new ArrayList<>();
            Tree child = tree;
            for (TreePath cursor = path.getParentPath(); cursor != null; cursor = cursor.getParentPath()) {
                Tree parent = cursor.getLeaf();
                if (parent instanceof MethodTree
                        || parent instanceof LambdaExpressionTree
                        || parent instanceof ClassTree) {
                    break;
                }
                String role = edgeRole(cursor, child);
                if (role != null) {
                    roles.add(role);
                }
                child = parent;
            }
            roles.add("type-kind:" + tree.getKind());
            return String.join("/", roles);
        }

        private String semanticAnchor() {
            int floor = callableFloors.isEmpty() ? 0 : callableFloors.peek();
            if (callables.size() > floor) {
                return callables.peek().callableId();
            }
            return owners.isEmpty() ? "local" : owners.peek().semanticName() + "#<initializer>";
        }

        private boolean stableNesting(NestingKind nesting) {
            return nesting == NestingKind.TOP_LEVEL || nesting == NestingKind.MEMBER;
        }

        private String executableDescriptor(ExecutableType executable) {
            StringBuilder descriptor = new StringBuilder("(");
            for (TypeMirror parameter : executable.getParameterTypes()) {
                descriptor.append(typeDescriptor(parameter));
            }
            return descriptor.append(')')
                    .append(typeDescriptor(executable.getReturnType()))
                    .toString();
        }

        private String typeDescriptor(TypeMirror type) {
            String simple = SIMPLE_DESCRIPTORS.get(type.getKind());
            if (simple != null) {
                return simple;
            }
            if (type instanceof ArrayType array) {
                return "[" + typeDescriptor(array.getComponentType());
            }
            if (type instanceof DeclaredType declared) {
                TypeElement element = (TypeElement) declared.asElement();
                return "L" + elements.getBinaryName(element).toString().replace('.', '/') + ";";
            }
            TypeMirror erased = types.erasure(type);
            if (!erased.equals(type)) {
                return typeDescriptor(erased);
            }
            throw new JavaAnalysisException("jvmDescriptorUnavailable:" + type.getKind());
        }

        private String semanticType(TypeMirror type) {
            String simple = SIMPLE_DESCRIPTORS.get(type.getKind());
            if (simple != null) {
                return simple;
            }
            return semanticReferenceType(type);
        }

        private String semanticReferenceType(TypeMirror type) {
            if (type instanceof ArrayType array) {
                return "[" + semanticType(array.getComponentType());
            }
            return semanticNonArrayType(type);
        }

        private String semanticNonArrayType(TypeMirror type) {
            if (type instanceof IntersectionType intersection) {
                return semanticIntersectionType(intersection);
            }
            return semanticNonIntersectionType(type);
        }

        private String semanticNonIntersectionType(TypeMirror type) {
            if (type instanceof DeclaredType declared) {
                return semanticDeclaredType(declared);
            }
            return semanticWildcardOrErasedType(type);
        }

        private String semanticWildcardOrErasedType(TypeMirror type) {
            if (type instanceof WildcardType wildcard) {
                return semanticWildcardType(wildcard);
            }
            TypeMirror erased = types.erasure(type);
            if (!erased.equals(type)) {
                return "erased{" + typeDescriptor(erased) + "}";
            }
            throw new JavaAnalysisException("semanticTypeUnavailable:" + type.getKind());
        }

        private String semanticDeclaredType(DeclaredType declared) {
            TypeElement element = (TypeElement) declared.asElement();
            StringBuilder value = new StringBuilder("L")
                    .append(elements.getBinaryName(element).toString().replace('.', '/'));
            if (!declared.getTypeArguments().isEmpty()) {
                value.append('<');
                for (TypeMirror argument : declared.getTypeArguments()) {
                    value.append(semanticType(argument));
                }
                value.append('>');
            }
            return value.append(';').toString();
        }

        private String semanticWildcardType(WildcardType wildcard) {
            if (wildcard.getExtendsBound() != null) {
                return "+" + semanticType(wildcard.getExtendsBound());
            }
            return semanticWildcardWithoutExtends(wildcard);
        }

        private String semanticWildcardWithoutExtends(WildcardType wildcard) {
            if (wildcard.getSuperBound() != null) {
                return "-" + semanticType(wildcard.getSuperBound());
            }
            return "*";
        }

        private String semanticIntersectionType(IntersectionType intersection) {
            List<String> bounds = intersection.getBounds().stream()
                    .map(this::semanticType)
                    .sorted(SemanticSite::compareUtf8)
                    .toList();
            return "intersection{" + String.join("&", bounds) + "}";
        }
    }

    private static final class DecisionCounter extends TreeScanner<Void, Void> {
        private int complexity = 1;

        private static int count(Tree body) {
            DecisionCounter counter = new DecisionCounter();
            if (body != null) {
                counter.scan(body, null);
            }
            return counter.complexity;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            return null;
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            return null;
        }

        @Override
        public Void visitIf(IfTree tree, Void unused) {
            complexity++;
            return super.visitIf(tree, unused);
        }

        @Override
        public Void visitForLoop(ForLoopTree tree, Void unused) {
            complexity++;
            return super.visitForLoop(tree, unused);
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree tree, Void unused) {
            complexity++;
            return super.visitEnhancedForLoop(tree, unused);
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree tree, Void unused) {
            complexity++;
            return super.visitWhileLoop(tree, unused);
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree tree, Void unused) {
            complexity++;
            return super.visitDoWhileLoop(tree, unused);
        }

        @Override
        public Void visitCatch(CatchTree tree, Void unused) {
            complexity++;
            return super.visitCatch(tree, unused);
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree tree, Void unused) {
            complexity++;
            return super.visitConditionalExpression(tree, unused);
        }

        @Override
        public Void visitCase(CaseTree tree, Void unused) {
            if (!tree.getExpressions().isEmpty()) {
                complexity++;
            }
            return super.visitCase(tree, unused);
        }

        @Override
        public Void visitBinary(BinaryTree tree, Void unused) {
            if (tree.getKind() == Tree.Kind.CONDITIONAL_AND
                    || tree.getKind() == Tree.Kind.CONDITIONAL_OR) {
                complexity++;
            }
            return super.visitBinary(tree, unused);
        }
    }
}
