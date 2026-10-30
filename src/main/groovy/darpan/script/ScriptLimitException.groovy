package darpan.script

import groovy.transform.CompileStatic

/** DAR-BE-164. A script hit one of its limits. kind: UNITS, TIME, MEMORY, NUMBER, ACCESS. */
@CompileStatic
class ScriptLimitException extends ArithmeticException {
    final String kind

    ScriptLimitException(String kind, String message) {
        super(message)
        this.kind = kind
    }
}
