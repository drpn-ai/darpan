package darpan.script

import groovy.transform.CompileStatic

/** A script hit one of its limits; kind is UNITS, TIME, MEMORY, NUMBER or ACCESS. */
@CompileStatic
class ScriptLimitException extends ArithmeticException {
    final String kind

    ScriptLimitException(String kind, String message) {
        super(message)
        this.kind = kind
    }
}
