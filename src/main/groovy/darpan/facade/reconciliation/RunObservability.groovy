package darpan.facade.reconciliation

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Single instrumentation seam for reconciliation run observability. Foundation increment:
 * status lifecycle + per-stage timeline + structured logging. The watchdog/cancel (Phase 3),
 * UI (Phase 4), and alerting (Phase 5) build on these constants and write methods.
 */
class RunObservability {

    private static final Logger logger = LoggerFactory.getLogger(RunObservability.class)

    static final String RUN_RESULT_ENTITY = "darpan.reconciliation.ReconciliationRunResult"
    static final String RUN_STEP_ENTITY   = "darpan.reconciliation.ReconciliationRunStep"

    static final String STATUS_PENDING  = "AUT_STAT_PENDING"
    static final String STATUS_RUNNING  = "AUT_STAT_RUNNING"
    static final String STATUS_SUCCESS  = "AUT_STAT_SUCCESS"
    static final String STATUS_FAILED   = "AUT_STAT_FAILED"
    static final String STATUS_NO_DATA  = "AUT_STAT_NO_DATA"
    static final String STATUS_SKIP_DUP = "AUT_STAT_SKIP_DUP"

    static final Set<String> ACTIVE_STATUSES   = [STATUS_PENDING, STATUS_RUNNING].toSet()
    static final Set<String> TERMINAL_STATUSES = [STATUS_SUCCESS, STATUS_FAILED, STATUS_NO_DATA, STATUS_SKIP_DUP].toSet()

    static final String STAGE_RESOLVE       = "RESOLVE"
    static final String STAGE_EXTRACT_FILE1 = "EXTRACT_FILE1"
    static final String STAGE_EXTRACT_FILE2 = "EXTRACT_FILE2"
    static final String STAGE_COMPARE       = "COMPARE"
    static final String STAGE_WRITE_OUTPUT  = "WRITE_OUTPUT"
    static final String STAGE_NOTIFY        = "NOTIFY"

    static final Map<String, Integer> STAGE_SEQUENCE = [
            (STAGE_RESOLVE)      : 1,
            (STAGE_EXTRACT_FILE1): 2,
            (STAGE_EXTRACT_FILE2): 3,
            (STAGE_COMPARE)      : 4,
            (STAGE_WRITE_OUTPUT) : 5,
            (STAGE_NOTIFY)       : 6,
    ]

    static boolean isTerminalStatus(String status) { TERMINAL_STATUSES.contains(status) }
    static boolean isActiveStatus(String status) { ACTIVE_STATUSES.contains(status) }
    static int stageSequenceOf(String stageCode) { (STAGE_SEQUENCE[stageCode] ?: 0) as int }

    private static String norm(Object v) { v?.toString()?.trim() ?: null }
}
