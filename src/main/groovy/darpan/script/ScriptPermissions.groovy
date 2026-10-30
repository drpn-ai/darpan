package darpan.script

import groovy.transform.CompileStatic
import org.apache.commons.jexl3.introspection.JexlPermissions

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * DAR-BE-164. Everything JEXL's introspection may reach on behalf of a tenant script:
 * <ul>
 *   <li>the public static functions of {@link ScriptLibrary} (the {@code d:} namespace);</li>
 *   <li>the operator overloads declared on {@link ScriptArithmetic}. JEXL finds them through the same introspection,
 *   and when they are denied it falls back to its defaults without a word: the meter and the map-only property rule
 *   switch off (spike: a 100M-step empty loop then ran to the end). A script holds no reference to the arithmetic,
 *   so this opens nothing to it.</li>
 * </ul>
 * No constructors, no fields, no other class.
 *
 * <p>Written out instead of {@code JexlPermissions.NONE.compose("darpan.script.ScriptLibrary")}: the spike found that
 * composing NONE with a class name answers allow(java.lang.Runtime) == true. Both classes are Groovy, so they carry
 * GroovyObject's methods (invokeMethod, setMetaClass...); those are shut out by name.</p>
 */
@CompileStatic
class ScriptPermissions implements JexlPermissions {

    static final ScriptPermissions INSTANCE = new ScriptPermissions()

    private static final Set<String> GROOVY_OBJECT_METHODS =
            ['getMetaClass', 'setMetaClass', 'invokeMethod', 'getProperty', 'setProperty'] as Set<String>

    @Override boolean allow(Class<?> clazz) { clazz == ScriptLibrary || clazz == ScriptArithmetic }

    @Override boolean allow(Package pack) { pack != null && pack.name == ScriptLibrary.package.name }

    @Override boolean allow(Constructor<?> constructor) { false }

    @Override boolean allow(Field field) { false }

    @Override
    boolean allow(Method method) {
        if (method == null || method.synthetic || method.name.contains('$') || GROOVY_OBJECT_METHODS.contains(method.name)) return false
        int mod = method.modifiers
        if (!Modifier.isPublic(mod)) return false
        if (method.declaringClass == ScriptLibrary) return Modifier.isStatic(mod)
        if (method.declaringClass == ScriptArithmetic) return !Modifier.isStatic(mod)
        return false
    }

    /** Nothing may widen this set at run time. */
    @Override JexlPermissions compose(String... src) { this }
}
