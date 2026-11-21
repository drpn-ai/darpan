package darpan.reconciliation.support

import darpan.config.DarpanSettings

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarFile

/**
 * DAR-OPS-026: finds and calls every `static void resetForTests()` in this component's compiled main classes, so a
 * shared-Moqui smoke class starts with every process-global seam, hook, clock and cache at its default. There is no
 * hand-kept list: a class joins by declaring the method. Only the main output of this component is scanned (the
 * code-source location of DarpanSettings), whatever a class's package (RuleEngineSupport is `reconciliation.rule`);
 * framework and other jars are never touched. A class that has no resetForTests() yet is simply not called.
 */
class StaticResets {
    private static final byte[] MARKER = 'resetForTests'.getBytes(StandardCharsets.UTF_8)
    private static volatile List<Method> resetMethods

    /** Calls every resetForTests(); returns the names of the classes reset. All failures are reported together. */
    static List<String> resetAll() {
        List<Method> methods = methods()
        List<String> failures = []
        List<String> reset = []
        for (Method m : methods) {
            try {
                m.invoke(null)
                reset << m.declaringClass.name
            } catch (InvocationTargetException e) {
                failures << "${m.declaringClass.name}.resetForTests(): ${e.cause ?: e}".toString()
            } catch (Throwable t) {
                failures << "${m.declaringClass.name}.resetForTests(): ${t}".toString()
            }
        }
        if (failures) {
            throw new AssertionError("StaticResets.resetAll: ${failures.size()} reset(s) failed:\n  " + failures.join("\n  "))
        }
        return reset
    }

    /** The classes resetAll() calls, in call order. */
    static List<String> resetClassNames() { return methods().collect { Method m -> m.declaringClass.name } }

    private static List<Method> methods() {
        List<Method> cached = resetMethods
        if (cached != null) return cached
        synchronized (StaticResets) {
            if (resetMethods == null) resetMethods = Collections.unmodifiableList(scan())
            return resetMethods
        }
    }

    private static List<Method> scan() {
        URL location = DarpanSettings.protectionDomain?.codeSource?.location
        if (location == null) throw new IllegalStateException("StaticResets: no code source for ${DarpanSettings.name}")
        Path root = Paths.get(location.toURI())
        ClassLoader loader = DarpanSettings.classLoader
        List<String> candidates = []
        if (Files.isDirectory(root)) {
            Files.walk(root).withCloseable { stream ->
                stream.filter { Path p -> p.toString().endsWith('.class') && contains(Files.readAllBytes(p), MARKER) }
                        .forEach { Path p -> candidates << className(root.relativize(p).toString()) }
            }
        } else {
            new JarFile(root.toFile()).withCloseable { JarFile jar ->
                for (JarEntry entry : Collections.list(jar.entries())) {
                    if (!entry.name.endsWith('.class')) continue
                    byte[] bytes = jar.getInputStream(entry).withCloseable { it.bytes }
                    if (contains(bytes, MARKER)) candidates << className(entry.name)
                }
            }
        }
        List<Method> found = []
        for (String name : candidates.sort()) {
            Class<?> type = Class.forName(name, false, loader)
            // Only classes that really come from this component's main output; a same-named class elsewhere is skipped.
            if (type.protectionDomain?.codeSource?.location != location) continue
            Method m
            try {
                m = type.getDeclaredMethod('resetForTests')
            } catch (NoSuchMethodException ignored) {
                continue // the name appears only as a call (e.g. a delegating caller), not a declaration
            }
            if (!Modifier.isStatic(m.modifiers) || m.returnType != void.class) continue
            m.accessible = true
            found << m
        }
        return found
    }

    private static String className(String relativePath) {
        return relativePath.replace('\\', '/').replaceFirst(/\.class$/, '').replace('/', '.')
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer
            }
            return true
        }
        return false
    }
}
