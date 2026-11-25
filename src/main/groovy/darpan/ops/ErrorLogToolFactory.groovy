package darpan.ops

import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ToolFactory

/** DAR-BE-015 (spec E). Registers {@link ErrorLogCapture#INSTANCE} as a log event subscriber when Moqui starts (MoquiConf.xml). */
class ErrorLogToolFactory implements ToolFactory<ErrorLogCapture> {
    @Override
    void init(ExecutionContextFactory ecf) {
        if (!ecf.getLogEventSubscribers().any { it.is(ErrorLogCapture.INSTANCE) }) ecf.registerLogEventSubscriber(ErrorLogCapture.INSTANCE)
    }

    @Override
    ErrorLogCapture getInstance(Object... parameters) { return ErrorLogCapture.INSTANCE }
}
