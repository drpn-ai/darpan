package darpan.ops

import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.LogEventSubscriber

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertSame

/** S99-213: the tool factory registers the one capture subscriber once, however often Moqui inits it. Pure: no Moqui. */
class ErrorLogToolFactoryTests {

    @Test
    void initRegistersTheCaptureOnceAndHandsOutTheSameInstance() {
        List<LogEventSubscriber> subscribers = []
        ExecutionContextFactory ecf = [getLogEventSubscribers   : { -> subscribers },
                                       registerLogEventSubscriber: { LogEventSubscriber s -> subscribers << s }] as ExecutionContextFactory
        ErrorLogToolFactory factory = new ErrorLogToolFactory()

        factory.init(ecf)
        factory.init(ecf)

        assertEquals(1, subscribers.count { it.is(ErrorLogCapture.INSTANCE) })
        assertEquals(1, subscribers.size())
        assertSame(ErrorLogCapture.INSTANCE, factory.getInstance())
    }
}
