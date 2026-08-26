package darpan.facade.reconciliation

import darpan.reconciliation.automation.SourceSystemConnectorSupport

import static darpan.common.ValueSupport.hasField
import static darpan.common.ValueSupport.readField
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readOptionalString

/**
 * The verification pass's decision, lookup-resolution and count-adjustment logic, callable from any
 * run entry point.
 *
 * <p>Lifted verbatim out of {@code runSavedRunDiff.groovy}'s script-local closures (design
 * {@code 2026-08-26-reconciliation-pipeline-unification}, step 2). Those closures are bound to a
 * Moqui script's own scope, so nothing outside that one script could ever call them — which is why
 * the scheduled path reimplemented the pipeline around them rather than reusing it, and why the
 * scheduled path ended up with no verification at all ({@code grep -c STAGE_VERIFY} over
 * {@code darpan/reconciliation/automation/} returns 0). On gorjana automation 100616 that meant a
 * scheduled run reporting ~532 differences where the verified interactive rerun reported 2.</p>
 *
 * <p><b>This class deliberately contains no behaviour change.</b> It is the seam that lets step 3
 * call verification from the automation path without copying the closures a fourth time into a file
 * pair already kept in sync by hand-written "mirrors runSavedRunDiff" comments.</p>
 *
 * <p>Observability (opening/closing the STAGE_VERIFY step) stays with the caller: the two entry
 * points mint their run rows differently, and folding that in here would couple this seam to one of
 * them.</p>
 */
class RunVerificationSupport {

    /**
     * Whether the missing-diff recheck is worth running at all.
     *
     * <p>A rule-execution failure preserves partial diffs for investigation, so those must never be
     * rewritten. Beyond that, a pass with nothing reported missing on either side has nothing to
     * recheck.</p>
     */
    static boolean shouldVerifyMissingDiffs(Map serviceResult) {
        if (serviceResult == null) return false
        if (serviceResult.get("ruleExecutionFailed") == true) return false
        return missingCount(serviceResult, "missingInFile1Count") > 0L ||
                missingCount(serviceResult, "missingInFile2Count") > 0L
    }

    /** Null-safe read of one of the compare's missing-side counts. */
    static long missingCount(Map serviceResult, String key) {
        return ((serviceResult?.get(key) ?: 0) as Number).longValue()
    }

    /**
     * Fold a completed verification back into the compare's summary, in place.
     *
     * <p>The pre-pass missing counts are passed in rather than re-read, because the caller has
     * already captured them before the diff document was rewritten.</p>
     *
     * <p>Every subtraction clamps at zero. A lookup that reports more removals than the compare
     * found would otherwise produce a negative difference count, which renders as nonsense and
     * silently breaks any downstream alert threshold comparing against it.</p>
     */
    static Map applyVerificationOutcome(Map serviceResult, Map verification,
                                        long missingInFile1, long missingInFile2) {
        if (serviceResult == null) return serviceResult
        Map result = verification ?: [:]

        if (result.get("rewritten")) {
            long removed = longValue(result.get("removedCount"))
            serviceResult.put("differenceCount",
                    Math.max(0L, longValue(serviceResult.get("differenceCount")) - removed))
            serviceResult.put("missingInFile1Count",
                    Math.max(0L, missingInFile1 - longValue(result.get("removedMissingInFile1"))))
            serviceResult.put("missingInFile2Count",
                    Math.max(0L, missingInFile2 - longValue(result.get("removedMissingInFile2"))))
            // Only adjusted when the compare actually reported one: inventing the key here would
            // publish a count the compare never produced.
            if (serviceResult.get("missingObjectDifferenceCount") != null) {
                serviceResult.put("missingObjectDifferenceCount",
                        Math.max(0L, longValue(serviceResult.get("missingObjectDifferenceCount")) - removed))
            }
        }

        List notes = []
        if (result.get("auditNote")) notes.add(result.get("auditNote"))
        notes.addAll((result.get("warnings") ?: []) as List)
        // Guarded so a silent pass cannot append an empty array to the result document.
        if (notes) {
            serviceResult.put("processingWarnings",
                    ((serviceResult.get("processingWarnings") ?: []) as List) + notes)
        }
        return serviceResult
    }

    /**
     * The connector backing a run source: by systemEnumId first, falling back to the declared
     * config type when the row's systemEnumId resolves to a connector expecting a different one.
     *
     * <p>Deduplicates a four-line block that appeared three times in {@code runSavedRunDiff.groovy}
     * alone (the lookup builder, the cap reader, and the exchange pass's own resolver).</p>
     */
    static Map<String, Object> resolveConnector(def ec, Object source) {
        if (source == null) return null
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, readOptionalString(source, "systemEnumId"))
        // TWO SOURCE SHAPES REACH HERE. A saved run passes a Map carrying sourceConfigType; a
        // scheduled run passes a ReconciliationAutomationSource row, which has no such column and
        // whose systemEnumId is the only key it has. Reading the field blind threw an EntityException
        // out of every scheduled run once verification defaulted on (2026-08-26) — Moqui's
        // EntityValue.get raises on an undeclared name where a Map simply answers null. So: absent
        // means "this shape keys on systemEnumId alone", which is NOT the same as present-and-blank,
        // where the saved-run contract still expects the config-type fallback below.
        if (!hasField(source, "sourceConfigType")) return connector
        String sourceConfigType = normalize(readField(source, "sourceConfigType"))
        if (connector == null || normalize(connector.expectedSourceConfigType) != sourceConfigType) {
            connector = SourceSystemConnectorSupport.resolveByExpectedSourceConfigType(ec, sourceConfigType)
        }
        return connector
    }

    /**
     * A point-lookup closure for one side, or null when this side cannot be rechecked.
     *
     * <p>{@code dispatcher} is the caller's service-invocation seam ({@code (name, params) -> Map});
     * it stays injected because the two entry points dispatch differently and this class must not
     * pick one.</p>
     *
     * <p>{@code runOwnerUserGroupId} is the RUN OWNER's tenant, not the session's. Every lookup
     * service in this slot declares it for that reason — a scheduled run has no user-derived tenant,
     * and omitting it is what broke the 2026-07-31 scheduled automations.</p>
     */
    static Closure buildVerificationLookup(def ec, Object source, String runOwnerUserGroupId, Closure dispatcher) {
        if (source == null || dispatcher == null) return null
        Map<String, Object> connector = resolveConnector(ec, source)
        String lookupServiceName = connector == null ? null : normalize(connector.lookupServiceName)
        if (lookupServiceName == null) return null
        // Narrower sibling of the extractor fence: the lookup slot may only dispatch lookup#* services.
        if (!SourceSystemConnectorSupport.isAllowedLookupServiceShape(lookupServiceName)) return null
        // Same two shapes as resolveConnector. A saved-run Map carries a generic sourceConfigId; an
        // automation source row does not — it carries the id under the connector's OWN parameter name
        // (omsRestSourceConfigId, shopifyAuthConfigId, databaseSourceQueryId...), which is exactly
        // what configParameterName names. Falling back to it is what makes the lookup resolvable on
        // the scheduled path at all; without it verification ran, found no config, and silently
        // rechecked nothing.
        String configParameterName = normalize(connector.configParameterName) ?: "sourceConfigId"
        String configId = readOptionalString(source, "sourceConfigId") ?: readOptionalString(source, configParameterName)
        if (configId == null) return null
        // Blank means the canonical order lookups' "orderIds". The returns pair rechecks refund/return
        // ids, which must not be sent under an order-id name — Moqui would silently drop the parameter.
        String idsParameterName = normalize(connector.lookupIdsParameterName) ?: "orderIds"
        return { List<String> ids ->
            dispatcher.call(lookupServiceName, [(configParameterName): configId, (idsParameterName): ids,
                                                companyUserGroupId   : runOwnerUserGroupId])
        }
    }

    /**
     * This side's connector-declared ceiling on point lookups, or null to let the verification pass
     * apply its own default. Resolved separately from the lookup closure so an unreadable or blank
     * cap can never stop a usable lookup from being built.
     */
    static Integer buildVerificationLookupCap(def ec, Object source) {
        if (source == null) return null
        Object cap = resolveConnector(ec, source)?.lookupMaxIds
        return (cap instanceof Number && ((Number) cap).intValue() > 0) ? ((Number) cap).intValue() : null
    }

    private static long longValue(Object raw) {
        return ((raw ?: 0) as Number).longValue()
    }
}
