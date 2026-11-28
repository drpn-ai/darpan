package darpan.common

/**
 * DAR-OPS-029 S99-050: one message scope for calls whose errors and messages must not reach the caller's reply.
 *
 * A failed Moqui service call does not throw: it adds its exception text to ec.message's errors. clearErrors() is not a way
 * to drop them, because it turns each error into a danger message that rides out in the reply. These helpers snapshot the
 * caller's messages, run the work behind pushErrors(), and put the caller's messages, public messages and errors back
 * exactly as they were.
 */
class MessageScope {

    /** The caller's messages: [infos, publics]. addPublic puts one MessageInfo in both lists. */
    static Map snapshot(def ec) {
        return [infos: new ArrayList(ec.message.messageInfos), publics: new ArrayList(ec.message.publicMessageInfos)]
    }

    /** Drops every message and error now held and puts back the snapshot's messages (public ones as public). */
    static void restore(def ec, Map snapshot) {
        ec.message.clearAll()
        List publics = snapshot.publics as List
        (snapshot.infos as List).each { mi ->
            if (publics.any { it.is(mi) }) ec.message.addPublic(mi.message, mi.type)
            else ec.message.addMessage(mi.message, mi.type)
        }
    }

    /**
     * Runs work behind a fresh error scope and returns its result. Whatever it adds (errors, messages) is discarded, so read
     * ec.message inside work if the outcome matters; afterwards the caller's messages and errors are exactly as before, also
     * when work throws.
     */
    static <T> T isolated(def ec, Closure<T> work) {
        Map before = snapshot(ec)
        ec.message.pushErrors()
        try {
            return work.call()
        } finally {
            restore(ec, before)
            ec.message.popErrors()
        }
    }
}
