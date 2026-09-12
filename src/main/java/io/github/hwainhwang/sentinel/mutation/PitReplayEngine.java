package io.github.hwainhwang.sentinel.mutation;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Calls the pinned upstream engine; no mutation operator implementation is copied. */
final class PitReplayEngine {
    private PitReplayEngine() {
        throw new AssertionError("no instances");
    }

    static byte[] materialize(PitProbeArtifacts artifacts, Map<String, byte[]> classes,
            PitReport.Result result, long deadline) throws IOException, InterruptedException {
        if (result.identity().indexes().size() != 1) {
            throw new IllegalArgumentException("pitReplayGroupedUnsupported");
        }
        PitProbeProcess.check(deadline);
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] {artifacts.core().toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            // RISK(security): upstream ServiceLoader must see only the verified core artifact and JDK.
            Thread.currentThread().setContextClassLoader(loader);
            byte[] bytes = generate(loader, classes, result);
            PitProbeProcess.check(deadline);
            return bytes;
        } catch (ReflectiveOperationException error) {
            throw new IllegalArgumentException("pitReplayEngineFailed", error);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static byte[] generate(ClassLoader loader, Map<String, byte[]> classes, PitReport.Result expected)
            throws ReflectiveOperationException {
        Object mutater = mutater(loader, classes);
        Class<?> className = loader.loadClass("org.pitest.classinfo.ClassName");
        Object name = className.getMethod("fromString", String.class).invoke(null, expected.identity().mutatedClass());
        List<?> discovered = (List<?>) mutater.getClass().getMethod("findMutations", className).invoke(mutater, name);
        Object id = selected(discovered, expected);
        Object mutant = mutater.getClass().getMethod("getMutation", id.getClass()).invoke(mutater, id);
        requireDetails(call(mutant, "getDetails"), expected);
        byte[] bytes = (byte[]) call(mutant, "getBytes");
        byte[] original = classes.get("target/classes/" + expected.identity().mutatedClass().replace('.', '/') + ".class");
        if (bytes.length == 0 || bytes.length > PitProbeFiles.MAX_SOURCE_BYTES || Arrays.equals(bytes, original)) {
            throw new IllegalArgumentException("pitReplayMutationInvalid");
        }
        return bytes;
    }

    private static Object mutater(ClassLoader loader, Map<String, byte[]> classes) throws ReflectiveOperationException {
        Class<?> argumentsType = loader.loadClass("org.pitest.mutationtest.EngineArguments");
        Object arguments = argumentsType.getMethod("arguments").invoke(null);
        arguments = argumentsType.getMethod("withMutators", Collection.class)
                .invoke(arguments, List.of(PitProbeCommands.MUTATORS.split(",")));
        Object factory = loader.loadClass("org.pitest.mutationtest.engine.gregor.config.GregorEngineFactory")
                .getConstructor().newInstance();
        Object engine = factory.getClass().getMethod("createEngine", argumentsType).invoke(factory, arguments);
        Class<?> sourceType = loader.loadClass("org.pitest.classinfo.ClassByteArraySource");
        Object source = Proxy.newProxyInstance(loader, new Class<?>[] {sourceType}, new ByteSource(classes));
        return engine.getClass().getMethod("createMutator", sourceType).invoke(engine, source);
    }

    private static final class ByteSource implements InvocationHandler {
        private final Map<String, byte[]> classes;

        private ByteSource(Map<String, byte[]> classes) {
            this.classes = classes;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] values) throws IOException {
            return byteSource(classes, (String) values[0]);
        }
    }

    private static Optional<byte[]> byteSource(Map<String, byte[]> classes, String name) throws IOException {
        String path = name.replace('.', '/') + ".class";
        byte[] bytes = classes.get("target/classes/" + path);
        if (bytes != null) {
            return Optional.of(bytes.clone());
        }
        // No application-classpath fallback: only classes from the pinned running JDK are eligible.
        try (var input = ClassLoader.getPlatformClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                return Optional.empty();
            }
            byte[] resource = input.readNBytes(PitProbeFiles.MAX_SOURCE_BYTES + 1);
            if (resource.length > PitProbeFiles.MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException("pitReplayClassTooLarge");
            }
            return Optional.of(resource);
        }
    }

    private static Object selected(List<?> discovered, PitReport.Result expected) throws ReflectiveOperationException {
        Object selected = null;
        for (Object details : discovered) {
            if (identity(details).equals(expected.identity())) {
                if (selected != null) {
                    throw new IllegalArgumentException("pitReplayMutationAmbiguous");
                }
                requireDetails(details, expected);
                selected = call(details, "getId");
            }
        }
        if (selected == null) {
            throw new IllegalArgumentException("pitReplayMutationMissing");
        }
        return selected;
    }

    private static void requireDetails(Object details, PitReport.Result expected) throws ReflectiveOperationException {
        if (!identity(details).equals(expected.identity()) || !call(details, "getFilename").equals(expected.sourceFile())
                || !call(details, "getLineNumber").equals(expected.lineNumber())) {
            throw new IllegalArgumentException("pitReplayMutationMismatch");
        }
    }

    private static PitReport.Identity identity(Object details) throws ReflectiveOperationException {
        Object id = call(details, "getId");
        Object location = call(id, "getLocation");
        List<Integer> indexes = ((List<?>) call(id, "getIndexes")).stream().map(Integer.class::cast).toList();
        return new PitReport.Identity((String) call(call(location, "getClassName"), "asJavaName"),
                (String) call(location, "getMethodName"), (String) call(location, "getMethodDesc"),
                (String) call(id, "getMutator"), indexes);
    }

    private static Object call(Object target, String method) throws ReflectiveOperationException {
        return target.getClass().getMethod(method).invoke(target);
    }
}
