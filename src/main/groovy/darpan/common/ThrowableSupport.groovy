package darpan.common

/** DAR-OPS-029 S99-072. What a failure says, read from its deepest cause. */
class ThrowableSupport {
    /** The deepest cause's message, else the failure's own, else its class name; trimmed. A self-causing chain ends. */
    static String rootMessage(Throwable t) {
        Throwable cause = t
        while (cause.cause != null && cause.cause != cause) cause = cause.cause
        return (cause.message ?: t.message ?: t.class.simpleName).trim()
    }
}
