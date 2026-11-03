package darpan.agent

/**
 * DAR-BE-159 (R28). Marks the current thread as running one of the agent's tool calls, so a service that only a person may
 * call (apply, reject, revert of a proposal) can refuse it even if the server-side tool allow-list is ever wrong. The agent
 * loop wraps every tool execution in {@link #run}; nothing else sets it. Nested runs keep it set until the outer one ends.
 */
class AgentCallMarker {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial { 0 }

    static final String REFUSAL = "AGENT_CANNOT_APPROVE: only a person can apply, reject or revert a change."

    /** True while the current thread is inside {@link #run}. */
    static boolean inAgentLoop() {
        return DEPTH.get() > 0
    }

    /** Runs work marked as the agent's; the mark is cleared when it returns or throws. */
    static <T> T run(Closure<T> work) {
        DEPTH.set(DEPTH.get() + 1)
        try {
            return work.call()
        } finally {
            int left = DEPTH.get() - 1
            if (left > 0) DEPTH.set(left) else DEPTH.remove()
        }
    }
}
