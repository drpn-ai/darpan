package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport

import static darpan.common.ValueSupport.hasField
import static darpan.common.ValueSupport.readField
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBlankToNull
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
 * <p><b>As of 2026-08-27 the missing-diff pass is not just resolved here but RUN here</b>
 * ({@link #runMissingDiffPass}), STAGE_VERIFY step and all. Leaving observability with each caller
 * meant each kept its own open/run/fold/close block, and those blocks were where the two paths
 * diverged in the first place: a triggered run and a manual run of the same window published
 * different counts for a whole release. An automation is the thing that FIRES a run — after the
 * trigger it must execute the same process. The two entry points now supply only what is genuinely
 * theirs: the run id, the step context, the dispatch seam, the tenant anchor, and (scheduled only)
 * the kill switch and the config defaults its extractor resolved.</p>
 *
 * <p>The other two passes ({@link #prepareReturnPresencePass}, {@link #prepareExchangePairPass})
 * still hand a prepared pass back to the caller, which keeps its own step handling. Same shape,
 * same divergence risk — they are the next ones to move.</p>
 *
 * <p><b>A pass that declines now says why</b> ({@link #recordVerificationSkipped}). Every gate in
 * front of it used to return before the step was opened, so "no VERIFY row" meant equally: switched
 * off, no config id, no readable diff, or nothing to check. That ambiguity shipped inert
 * verification through v1.5.0 and cost a day on the 2026-08-27 gorjana report.</p>
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

    // --- the missing-diff pass, as ONE implementation both entry points call --------------------

    /** Verification is switched off for this deployment. Gates every pass, so it is reported once. */
    static final String SKIP_DISABLED = "VERIFICATION_DISABLED"
    /** The compare's diff document could not be resolved to a readable file. */
    static final String SKIP_NO_DIFF_FILE = "DIFF_ARTIFACT_UNREADABLE"
    /** Neither side could be point-checked; the detail names each side and why. */
    static final String SKIP_NO_LOOKUP = "NO_POINT_LOOKUP"

    // Named from the one place that owns it, not restated: two copies of a property name drift, and
    // a skip reason naming a switch that no longer exists is worse than no reason at all.
    private static final String KILL_SWITCH_PROPERTY = AutomationExecutionSupport.VERIFY_MISSING_DIFFS_PROPERTY

    /**
     * Resolve the missing-diff pass for a run: either a runnable pass, or the reason there is none.
     *
     * <p>args: {@code ec}, {@code enabled} (defaults true — the interactive path has no kill switch),
     * {@code serviceResult}, {@code diffFile}, {@code file1Source}/{@code file2Source},
     * {@code file1Label}/{@code file2Label}, {@code runOwnerUserGroupId}, {@code dispatcher},
     * {@code runConfigDefaults}.</p>
     *
     * <p>Returns {@code [applies: true, run: Closure, verifiedLabels: List, missingInFile1,
     * missingInFile2]}, or {@code [applies: false, skipReason, skipDetail]}. A null {@code skipReason}
     * means there was nothing to verify in the first place (no missing rows, or a rule-execution
     * failure whose partial diffs are preserved deliberately) — that is not a skip and must not be
     * reported as one, or every clean run carries an "unverified" note.</p>
     */
    static Map<String, Object> prepareMissingDiffPass(Map args) {
        Map serviceResult = (Map) args?.get("serviceResult")
        // Nothing-to-check is decided BEFORE the switch: a run with no missing rows is not
        // "unverified" just because verification happens to be off.
        if (!shouldVerifyMissingDiffs(serviceResult)) return [applies: false] as Map<String, Object>

        boolean enabled = args?.get("enabled") == null ? true : (args.get("enabled") as boolean)
        if (!enabled) {
            return skipped(SKIP_DISABLED,
                    "verification is switched off on this deployment (${KILL_SWITCH_PROPERTY}=false)")
        }

        // Resolved LAZILY, after the two guards above: both callers resolve it through the execution
        // context, and a disabled or empty pass must still cost nothing and touch no ec — several
        // unit tests call this with a null one precisely to prove that.
        File diffFile
        try {
            Object diffFileArg = args.get("diffFile")
            diffFile = diffFileArg instanceof Closure ? (File) ((Closure) diffFileArg).call() : (File) diffFileArg
        } catch (Throwable t) {
            return skipped(SKIP_NO_DIFF_FILE,
                    "the compare's diff document could not be located: ${normalize(t.message) ?: t.class.simpleName}".toString())
        }
        if (diffFile == null || !diffFile.isFile()) {
            return skipped(SKIP_NO_DIFF_FILE, "the compare's diff document could not be read back")
        }

        def ec = args.get("ec")
        Closure dispatcher = (Closure) args.get("dispatcher")
        Map<String, Object> runConfigDefaults = args.get("runConfigDefaults") instanceof Map ?
                (Map<String, Object>) args.get("runConfigDefaults") : null
        String runOwnerUserGroupId = normalize(args.get("runOwnerUserGroupId"))
        String file1Label = normalize(args.get("file1Label")) ?: normalize(serviceResult.get("file1Label")) ?: "file1"
        String file2Label = normalize(args.get("file2Label")) ?: normalize(serviceResult.get("file2Label")) ?: "file2"

        long missingInFile1 = missingCount(serviceResult, "missingInFile1Count")
        long missingInFile2 = missingCount(serviceResult, "missingInFile2Count")
        Map<String, Closure> sideLookups = [:]
        Map<String, Integer> sideMaxLookupIds = [:]
        List<String> blockers = []
        // A side with nothing missing needs no lookup and is not a blocker — only the sides this run
        // actually has rows to recheck on can explain why nothing was rechecked.
        [[missingInFile1, args.get("file1Source"), file1Label],
         [missingInFile2, args.get("file2Source"), file2Label]].each { List side ->
            if (((long) side[0]) <= 0L) return
            Map<String, Object> resolved = resolveSideLookup(ec, side[1], runOwnerUserGroupId, dispatcher, runConfigDefaults)
            Closure lookup = (Closure) resolved.get("lookup")
            if (lookup == null) {
                if (resolved.get("applicable") == true) blockers.add("${side[2]}: ${resolved.get('reason')}".toString())
                return
            }
            sideLookups.put((String) side[2], lookup)
            Integer cap = buildVerificationLookupCap(ec, side[1])
            if (cap != null) sideMaxLookupIds.put((String) side[2], cap)
        }
        // No lookups AND no blocker means verification never applied to this run — a diff between
        // two uploaded files has no source of record to recheck against. Silent, or every such run
        // would carry an "unverified" note and the report would stop meaning anything.
        if (!sideLookups) {
            return blockers ? skipped(SKIP_NO_LOOKUP, blockers.join("; ")) : ([applies: false] as Map<String, Object>)
        }

        return [applies       : true,
                verifiedLabels: sideLookups.keySet() as List,
                missingInFile1: missingInFile1,
                missingInFile2: missingInFile2,
                run           : {
                    return MissingDiffVerificationSupport.verifyMissingDiffs([
                            diffFile        : diffFile, file1Label: file1Label, file2Label: file2Label,
                            sideLookups     : sideLookups,
                            sideMaxLookupIds: sideMaxLookupIds])
                }] as Map<String, Object>
    }

    /**
     * Run the missing-diff pass for a run, observability and all. Returns whether it ran.
     *
     * <p>This is the whole VERIFY phase for that pass — decide, open the step, run, fold the counts,
     * close the step, or record WHY it was skipped — so a triggered run and a manual one execute the
     * same process rather than two hand-synced copies of it. Before this the two entry points each
     * carried their own copy of the open/run/fold/close block, and they diverged: the scheduled one
     * ran no verification at all until 2026-08-27, then ran it inert.</p>
     *
     * <p>Best-effort throughout: nothing here may fail a run that already produced a complete
     * compare. A lookup that raises is demoted to a warning on a FAILED step, and a pass that cannot
     * even be prepared leaves the counts untouched and says so.</p>
     */
    static boolean runMissingDiffPass(Map args) {
        def ec = args?.get("ec")
        Map serviceResult = (Map) args?.get("serviceResult")
        String runResultId = normalize(args?.get("runResultId"))
        Map stepCtx = (args?.get("stepCtx") ?: [:]) as Map

        Map<String, Object> prepared
        try {
            prepared = prepareMissingDiffPass(args)
        } catch (Throwable t) {
            // Preparing reads the connector registry and the source row; a shape this code cannot
            // read must degrade to an unverified run with a visible reason, never a failed one.
            prepared = skipped(SKIP_NO_LOOKUP, "it could not be prepared: ${normalize(t.message) ?: t.class.simpleName}".toString())
            if (ec?.message?.hasError()) ec.message.clearErrors()
        }
        if (prepared.get("applies") != true) {
            recordVerificationSkipped(ec, runResultId, stepCtx, serviceResult,
                    (String) prepared.get("skipReason"), (String) prepared.get("skipDetail"))
            return false
        }

        long missingInFile1 = ((prepared.get("missingInFile1") ?: 0L) as Number).longValue()
        long missingInFile2 = ((prepared.get("missingInFile2") ?: 0L) as Number).longValue()
        def verifyStep = runResultId ? RunObservability.beginStep(ec, runResultId, stepCtx, RunObservability.STAGE_VERIFY) : null
        Map verification
        try {
            verification = (Map) ((Closure) prepared.get("run")).call()
        } catch (Throwable t) {
            verification = [performed: true, rewritten: false, checkedCount: 0, removedCount: 0, lookupFailed: true,
                            warnings : ["Verification pass failed: ${normalize(t.message) ?: t.class.simpleName}".toString()]] as Map
        }
        // The lookup dispatch is best-effort after a complete compare: demote any service-level error
        // it raised (auth config, transport) to a warning so it cannot fail the run.
        if (ec?.message?.hasError()) {
            verification.warnings = ((verification.warnings ?: []) as List) + ((ec.message.getErrors() ?: []) as List)
            verification.lookupFailed = true
            ec.message.clearErrors()
        }
        applyVerificationOutcome(serviceResult, verification, missingInFile1, missingInFile2)
        // verifiedSystems names the sides this pass actually rechecked — several passes share the
        // VERIFY stage code, so without it a run that ran two of them shows two identical rows.
        RunObservability.endStep(ec, verifyStep,
                verification.lookupFailed ? RunObservability.STATUS_FAILED : RunObservability.STATUS_SUCCESS,
                [recordCount : verification.checkedCount ?: 0,
                 errorMessage: verification.lookupFailed && verification.warnings ? verification.warnings.first().toString() : null,
                 metricsJson : JsonOutput.toJson([verifiedSystems: prepared.get("verifiedLabels"),
                                                  checkedCount   : verification.checkedCount ?: 0,
                                                  removedCount   : verification.removedCount ?: 0])])
        return true
    }

    /**
     * Record that a run's differences went unverified, and why — on the timeline AND on the result.
     *
     * <p>"Count the run steps" is how these runs are read, so a run that skipped verification has to
     * say so as a row rather than as a missing row. The step is NO_DATA, not FAILED: nothing broke in
     * the run itself, and a red step on every run of a deployment that has deliberately switched the
     * pass off would train operators to ignore the colour.</p>
     *
     * <p>A null {@code skipReason} means there was nothing to verify, which is not a skip.</p>
     */
    static void recordVerificationSkipped(def ec, String runResultId, Map stepCtx, Map serviceResult,
                                          String skipReason, String skipDetail) {
        if (skipReason == null) return
        // endStep truncates errorMessage at 255 characters, so the sentence leads with the fact and
        // carries the detail behind it — a truncated reason still says the run went unverified.
        String sentence = "Differences were not verified: ${skipDetail ?: skipReason}".toString()
        try {
            if (runResultId) {
                def step = RunObservability.beginStep(ec, runResultId, (stepCtx ?: [:]) as Map, RunObservability.STAGE_VERIFY)
                RunObservability.endStep(ec, step, RunObservability.STATUS_NO_DATA,
                        [recordCount : 0, errorMessage: sentence,
                         metricsJson : JsonOutput.toJson([skipReason: skipReason])])
            }
        } catch (Throwable ignored) {
            // Reporting a skip must never be the thing that fails a run.
        }
        if (serviceResult != null) {
            serviceResult.put("processingWarnings",
                    ((serviceResult.get("processingWarnings") ?: []) as List) + [sentence])
        }
    }

    private static Map<String, Object> skipped(String reason, String detail) {
        return [applies: false, skipReason: reason, skipDetail: detail] as Map<String, Object>
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
    static Closure buildVerificationLookup(def ec, Object source, String runOwnerUserGroupId, Closure dispatcher,
                                          Map<String, Object> runConfigDefaults = null) {
        return (Closure) resolveSideLookup(ec, source, runOwnerUserGroupId, dispatcher, runConfigDefaults).get("lookup")
    }

    /**
     * One side's point-lookup, or the reason there is none — {@code [lookup: Closure]} or
     * {@code [reason: String]}, never both.
     *
     * <p>The reason is the whole point. {@link #buildVerificationLookup} answers null for six
     * different situations, and a null lookup means the pass silently rechecks nothing, so on a live
     * run "no VERIFY step" was indistinguishable between the switch being off, a connector with no
     * lookup service, and a config id that would not resolve. That ambiguity cost two days on the
     * 2026-08-27 gorjana report and hid inert verification for the whole of v1.5.0. Every early
     * return here now says which one it was, in words an operator can act on.</p>
     */
    static Map<String, Object> resolveSideLookup(def ec, Object source, String runOwnerUserGroupId,
                                                 Closure dispatcher, Map<String, Object> runConfigDefaults = null) {
        if (source == null) return [reason: "no source is configured on that side"] as Map<String, Object>
        if (dispatcher == null) return [reason: "no service dispatcher was supplied to the pass"] as Map<String, Object>
        String systemEnumId = readOptionalString(source, "systemEnumId") ?: "that source"
        Map<String, Object> connector = resolveConnector(ec, source)
        if (connector == null) return [reason: "no connector is registered for ${systemEnumId}".toString()] as Map<String, Object>
        String lookupServiceName = normalize(connector.lookupServiceName)
        if (lookupServiceName == null) {
            // NOT applicable rather than blocked: this source has no point-lookup by design (two
            // uploaded CSVs, NetSuite), so nothing failed to happen and there is nothing to report.
            return [reason: "the ${systemEnumId} connector declares no point-lookup service".toString()] as Map<String, Object>
        }
        // From here on the side COULD have been rechecked, so every remaining exit is a real blocker
        // an operator can act on, and is reported on the run.
        Map<String, Object> blocked = [applicable: true] as Map<String, Object>
        // Narrower sibling of the extractor fence: the lookup slot may only dispatch lookup#* services.
        if (!SourceSystemConnectorSupport.isAllowedLookupServiceShape(lookupServiceName)) {
            return blocked + [reason: "${systemEnumId}'s lookup service is not an allowed lookup# shape".toString()]
        }
        // Same two shapes as resolveConnector. A saved-run Map carries a generic sourceConfigId; an
        // automation source row does not — it carries the id under the connector's OWN parameter name
        // (omsRestSourceConfigId, shopifyAuthConfigId, databaseSourceQueryId...), which is exactly
        // what configParameterName names. Falling back to it is what makes the lookup resolvable on
        // the scheduled path at all; without it verification ran, found no config, and silently
        // rechecked nothing.
        String configParameterName = normalize(connector.configParameterName) ?: "sourceConfigId"
        String configId = resolveSourceConfigId(source, connector, runConfigDefaults)
        if (configId == null) {
            return blocked + [reason: "no ${configParameterName} could be resolved for ${systemEnumId}".toString()]
        }
        // Blank means the canonical order lookups' "orderIds". The returns pair rechecks refund/return
        // ids, which must not be sent under an order-id name — Moqui would silently drop the parameter.
        String idsParameterName = normalize(connector.lookupIdsParameterName) ?: "orderIds"
        return [lookup: { List<String> ids ->
            dispatcher.call(lookupServiceName, [(configParameterName): configId, (idsParameterName): ids,
                                                companyUserGroupId   : runOwnerUserGroupId])
        }] as Map<String, Object>
    }

    /**
     * The config id backing a run source, across BOTH run shapes.
     *
     * <p>A saved run passes a Map carrying a generic {@code sourceConfigId}. A scheduled run passes a
     * {@code ReconciliationAutomationSource} row, which has no such column — and for the API/SFTP
     * connectors (OMS, Shopify) has no config column at all. Those keep the id in
     * {@code safeMetadataJson.parameters} under the connector's own parameter name, which
     * {@code AutomationExecutionSupport.resolveSourceExtractorMetadata} states outright: they
     * <em>"store the config id in safeMetadataJson.parameters, never as a column on the source row"</em>.
     * Only a connector whose {@code configParameterName} names a real column (DATABASE →
     * {@code databaseSourceQueryId}) is readable directly.</p>
     *
     * <p><b>Reading only the two column shapes is what made verification inert on the scheduled path
     * (2026-08-27).</b> It returned null for every OMS/Shopify automation, so no lookup was built, no
     * side was rechecked, and {@code verifyMissingDiffsIfEnabled} returned false without opening a
     * VERIFY step — indistinguishable from the pass being switched off. The smoke fixture that
     * covered it was a hand-built Map with a {@code sourceConfigId} key, a shape the scheduled path
     * never produces, so the suite stayed green throughout.</p>
     *
     * <p>Returns null when no id is declared anywhere on the source; the caller decides whether a
     * tenant-wide default applies (the scheduled path resolves one, the same as it does for extraction).</p>
     */
    static String resolveSourceConfigId(Object source, Map<String, Object> connector,
                                        Map<String, Object> runConfigDefaults = null) {
        if (source == null) return null
        String configParameterName = normalize(connector?.get("configParameterName")) ?: "sourceConfigId"
        String columnValue = readOptionalString(source, "sourceConfigId") ?:
                readOptionalString(source, configParameterName)
        if (columnValue) return columnValue
        Object parameters = parseJsonMap(readOptionalString(source, "safeMetadataJson")).get("parameters")
        String declared = parameters instanceof Map ?
                normalizeBlankToNull(((Map) parameters).get(configParameterName)) : null
        if (declared) return declared
        // Last link in the SAME chain resolveSourceExtractorMetadata walks, and in the same order:
        // row column -> the source's own metadata -> the run's resolved defaults. The scheduled path
        // computes those defaults once per execution (resolveSourceExtractorConfigDefaults, which
        // ends at the tenant's single active config), so a source that extracted fine with no id of
        // its own now verifies with the same id it extracted with. Null for the interactive path,
        // whose sources always carry their own.
        return normalizeBlankToNull(runConfigDefaults?.get(configParameterName))
    }

    /**
     * Resolve a run's file location to a real File, or null.
     *
     * <p>Lifted from {@code runSavedRunDiff.groovy}'s {@code resolveLocationFile} closure. The
     * try/catch is the one addition: a scheduled run's extract location may be a data-manager
     * relative path that {@code getLocationReference} rejects outright, and every caller here treats
     * null as "this optional input is unavailable" and degrades a rule rather than failing a run.</p>
     */
    static File resolveLocationFile(def ec, String location) {
        String value = normalize(location)
        if (!value) return null
        if (value.startsWith("/")) return new File(value)
        try {
            return ec?.resource?.getLocationReference(value)?.getFile()
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * The OMS order-state lookup behind cancellation-refund suppression, or null when this run
     * cannot do it.
     *
     * <p>The OMS API is what gets called, so the connector, the config id and the tenant all come
     * from the OMS side even though every row being suppressed is a Shopify event. Same {@code
     * lookup#*} shape fence the other verification slots use, and the same shape-tolerant config
     * resolution — {@code runSavedRunDiff}'s own {@code buildFencedLookup} reads
     * {@code source?.sourceConfigId} directly, which on a {@code ReconciliationAutomationSource}
     * row raises an EntityException rather than answering null.</p>
     */
    static Closure buildCancelledOrderLookup(def ec, Object omsSource, Map<String, Object> connector,
                                             String runOwnerUserGroupId, Closure dispatcher,
                                             Map<String, Object> runConfigDefaults = null) {
        if (omsSource == null || connector == null || dispatcher == null) return null
        String serviceName = normalize(connector.get("orderStateLookupServiceName"))
        if (serviceName == null) return null
        if (!SourceSystemConnectorSupport.isAllowedLookupServiceShape(serviceName)) return null
        String configId = resolveSourceConfigId(omsSource, connector, runConfigDefaults)
        if (configId == null) return null
        String configParameterName = normalize(connector.get("configParameterName")) ?: "sourceConfigId"
        return { List<String> ids ->
            Map out = (Map) dispatcher.call(serviceName, [(configParameterName): configId,
                                                          externalIds       : ids,
                                                          companyUserGroupId: runOwnerUserGroupId])
            return [ok: out?.get("ok"), ordersByExternalId: out?.get("ordersByExternalId") ?: [:],
                    errors: out?.get("errors") ?: []]
        }
    }

    /**
     * Resolve the return-presence pass for a run, or null when this run is not a returns pair.
     *
     * <p>args: {@code ec}, {@code diffFile}, {@code sides} (each {@code [source, extractResult, label]}),
     * {@code file1Label}, {@code file2Label}, {@code windowStartMillis}, {@code runOwnerUserGroupId},
     * {@code dispatcher}, {@code runConfigDefaults}.</p>
     *
     * <p>Returns {@code [omsLabel, shopifyLabel, run]}, where {@code run} is a no-arg closure that
     * executes the pass and returns its verification map. Split that way so the STAGE_VERIFY step
     * opens only once the caller knows the pass applies — a run that is not a returns pair must leave
     * no VERIFY row claiming it was checked — while every resolution decision stays here, shared.</p>
     */
    static Map prepareReturnPresencePass(Map args) {
        def ec = args?.get("ec")
        File diffFile = (File) args?.get("diffFile")
        if (ec == null || diffFile == null || !diffFile.isFile()) return null

        List sides = (args.get("sides") ?: []) as List
        Map omsSide = (Map) sides.find { Map side ->
            normalize(resolveConnector(ec, side?.get("source"))?.systemEnumId) ==
                    ReconciliationSavedRunSupport.SYSTEM_HOTWAX_OMS_RETURNS
        }
        Map shopifySide = (Map) sides.find { Map side ->
            normalize(resolveConnector(ec, side?.get("source"))?.systemEnumId) ==
                    ReconciliationSavedRunSupport.SYSTEM_SHOPIFY_RETURN_REFS
        }
        if (omsSide == null || shopifySide == null) return null

        Map<String, Object> omsConnector = resolveConnector(ec, omsSide.get("source"))
        Map<String, Object> runConfigDefaults = args.get("runConfigDefaults") instanceof Map ?
                (Map<String, Object>) args.get("runConfigDefaults") : null
        // OMS extract for the superseded-sibling rule. Null (no location, or an unreadable file)
        // disables that one rule; the set comes back empty and nothing is suppressed by it.
        File omsExtractFile = resolveLocationFile(ec,
                normalize((omsSide.get("extractResult") as Map)?.get("fileLocation")))
        Closure cancelledOrderLookup = buildCancelledOrderLookup(ec, omsSide.get("source"), omsConnector,
                normalize(args.get("runOwnerUserGroupId")), (Closure) args.get("dispatcher"), runConfigDefaults)

        String omsLabel = normalize(omsSide.get("label"))
        String shopifyLabel = normalize(shopifySide.get("label"))
        return [omsLabel    : omsLabel,
                shopifyLabel: shopifyLabel,
                run         : {
                    return ReturnPresenceVerificationSupport.verifyReturnPresenceForRun([
                            diffFile            : diffFile,
                            file1Label          : normalize(args.get("file1Label")),
                            file2Label          : normalize(args.get("file2Label")),
                            omsSideLabel        : omsLabel,
                            shopifySideLabel    : shopifyLabel,
                            nowMillis           : System.currentTimeMillis(),
                            windowStartMillis   : args.get("windowStartMillis"),
                            omsExtractFile      : omsExtractFile,
                            cancelledOrderLookup: cancelledOrderLookup,
                    ])
                }] as Map
    }

    /**
     * Resolve the exchange-pair pass for a run, or null when it does not apply.
     *
     * <p>args: {@code ec}, {@code diffFile}, {@code sides} (each {@code [source, extractResult, label,
     * fileSide]}), {@code windowStartMillis}, {@code windowEndMillis}, {@code runOwnerUserGroupId},
     * {@code dispatcher}, {@code runConfigDefaults}.</p>
     *
     * <p>Sides are identified by capability, not by system id: the OMS side is whichever declares a
     * {@code pairLookupServiceName}, the Shopify side whichever declares an
     * {@code exchangeSweepServiceName}. Presence semantics need a window — exchanges are enumerated
     * from Shopify by return date — so a run without one resolves to null rather than sweeping an
     * unbounded range.</p>
     *
     * <p>Returns {@code [omsLabel, shopifyLabel, omsFileSide, run]}, the same prepare/run split
     * {@link #prepareReturnPresencePass} uses so the caller opens STAGE_VERIFY only once it knows the
     * pass applies.</p>
     */
    static Map prepareExchangePairPass(Map args) {
        def ec = args?.get("ec")
        File diffFile = (File) args?.get("diffFile")
        if (ec == null || diffFile == null || !diffFile.isFile()) return null
        Long windowStartMillis = longOrNull(args.get("windowStartMillis"))
        Long windowEndMillis = longOrNull(args.get("windowEndMillis"))
        if (windowStartMillis == null || windowEndMillis == null) return null

        List sides = (args.get("sides") ?: []) as List
        Map omsSide = null, shopifySide = null
        Map<String, Object> omsConnector = null, shopifyConnector = null
        for (Map side : sides) {
            Map<String, Object> connector = resolveConnector(ec, side?.get("source"))
            if (connector == null) continue
            if (omsSide == null && normalize(connector.get("pairLookupServiceName"))) {
                omsSide = side; omsConnector = connector
            } else if (shopifySide == null && normalize(connector.get("exchangeSweepServiceName"))) {
                shopifySide = side; shopifyConnector = connector
            }
        }
        if (omsSide == null || shopifySide == null) return null

        Map<String, Object> runConfigDefaults = args.get("runConfigDefaults") instanceof Map ?
                (Map<String, Object>) args.get("runConfigDefaults") : null
        String runOwnerUserGroupId = normalize(args.get("runOwnerUserGroupId"))
        Closure dispatcher = (Closure) args.get("dispatcher")
        if (dispatcher == null) return null

        // The manifest sidecar is OPTIONAL context (fast matching): an OMS window with zero exchange
        // orders writes none, and the presence check must still run — Shopify exchanges that were
        // never imported are exactly what it exists to catch.
        String omsFileLocation = normalize((omsSide.get("extractResult") as Map)?.get("fileLocation"))
        File manifestFile = omsFileLocation == null ? null :
                resolveLocationFile(ec, omsFileLocation.replaceAll(/(?i)\.json$/, "") + ".exchange-manifest.json")

        Closure omsPairLookup = buildFencedSourceLookup(ec, omsSide.get("source"), omsConnector,
                normalize(omsConnector.get("pairLookupServiceName")), "externalIds",
                runOwnerUserGroupId, dispatcher, runConfigDefaults)
        String sweepServiceName = normalize(shopifyConnector.get("exchangeSweepServiceName"))
        String shopifyConfigId = resolveSourceConfigId(shopifySide.get("source"), shopifyConnector, runConfigDefaults)
        String shopifyConfigParameterName = normalize(shopifyConnector.get("configParameterName")) ?: "sourceConfigId"
        if (omsPairLookup == null || shopifyConfigId == null
                || !SourceSystemConnectorSupport.isAllowedLookupServiceShape(sweepServiceName)) return null

        String omsLabel = normalize(omsSide.get("label"))
        String shopifyLabel = normalize(shopifySide.get("label"))
        return [omsLabel    : omsLabel,
                shopifyLabel: shopifyLabel,
                omsFileSide : normalize(omsSide.get("fileSide")),
                run         : {
                    return ExchangePairVerificationSupport.verifyExchangePairs([
                            manifestFile     : manifestFile,
                            diffFile         : diffFile,
                            nowMillis        : System.currentTimeMillis(),
                            windowStartMillis: windowStartMillis,
                            windowEndMillis  : windowEndMillis,
                            omsSideLabel     : omsLabel,
                            omsFileSide      : normalize(omsSide.get("fileSide")),
                            shopifySweep     : { long sweepStartMillis, long sweepEndMillis ->
                                Map out = (Map) dispatcher.call(sweepServiceName,
                                        [(shopifyConfigParameterName): shopifyConfigId,
                                         windowStartMillis           : sweepStartMillis,
                                         windowEndMillis             : sweepEndMillis,
                                         companyUserGroupId          : runOwnerUserGroupId])
                                return [ok       : out?.get("ok"), exchanges: out?.get("exchanges") ?: [],
                                        truncated: out?.get("truncated") == true, errors: out?.get("errors") ?: []]
                            },
                            omsPairLookup    : { List<String> ids ->
                                Map out = (Map) omsPairLookup.call(ids)
                                return [ok: out?.get("ok"), ordersByExternalId: out?.get("ordersByExternalId") ?: [:],
                                        errors: out?.get("errors") ?: []]
                            },
                    ])
                }] as Map
    }

    /**
     * Fold a completed exchange-pair pass back into the compare's summary, in place.
     *
     * <p>This pass APPENDS rows the compare never found (Shopify exchanges absent from OMS), so every
     * count moves UP — the mirror image of {@link #applyVerificationOutcome}. The appended rows are
     * missing on the OMS side, so they land on that side's own missing count.</p>
     */
    static Map applyExchangeOutcome(Map serviceResult, Map verification, String omsFileSide) {
        if (serviceResult == null) return serviceResult
        Map result = verification ?: [:]
        long appended = longValue(result.get("appendedCount"))
        if (appended > 0L) {
            serviceResult.put("differenceCount", longValue(serviceResult.get("differenceCount")) + appended)
            String missingCountKey = "FILE_1" == omsFileSide ? "missingInFile1Count" : "missingInFile2Count"
            serviceResult.put(missingCountKey, longValue(serviceResult.get(missingCountKey)) + appended)
            if (serviceResult.get("missingObjectDifferenceCount") != null) {
                serviceResult.put("missingObjectDifferenceCount",
                        longValue(serviceResult.get("missingObjectDifferenceCount")) + appended)
            }
        }
        List notes = []
        if (result.get("auditNote")) notes.add(result.get("auditNote"))
        notes.addAll((result.get("warnings") ?: []) as List)
        if (notes) {
            serviceResult.put("processingWarnings",
                    ((serviceResult.get("processingWarnings") ?: []) as List) + notes)
        }
        return serviceResult
    }

    /** The STAGE_VERIFY metrics body for an exchange-pair pass, shared by both entry points. */
    static Map<String, Object> exchangePairMetrics(Map verification, String omsLabel, String shopifyLabel) {
        Map result = verification ?: [:]
        return [verifiedSystems    : [omsLabel, shopifyLabel].findAll { it },
                sweepExchangeCount : result.get("sweepExchangeCount") ?: 0,
                matchedCount       : result.get("matchedCount") ?: 0,
                missingCount       : longValue(result.get("appendedCount")),
                inTransitCount     : result.get("inTransitCount") ?: 0,
                pendingCount       : result.get("pendingCount") ?: 0,
                deferredLookupCount: result.get("deferredLookupCount") ?: 0] as Map<String, Object>
    }

    /**
     * A fenced, tenant-carrying lookup closure for an arbitrary connector-declared lookup slot.
     *
     * <p>Generalises {@code runSavedRunDiff}'s {@code buildFencedLookup}, with two corrections it did
     * not have: shape-tolerant config resolution (its {@code source?.sourceConfigId} raises on an
     * automation source row) and an explicit {@code companyUserGroupId}. The scheduler authenticates
     * as anonymous {@code _NA_}, so a lookup that relies on the session's tenant resolves the wrong
     * config or none at all.</p>
     */
    static Closure buildFencedSourceLookup(def ec, Object source, Map<String, Object> connector,
                                           String serviceName, String idsParameterName,
                                           String runOwnerUserGroupId, Closure dispatcher,
                                           Map<String, Object> runConfigDefaults = null) {
        if (source == null || connector == null || dispatcher == null) return null
        String lookupServiceName = normalize(serviceName)
        if (lookupServiceName == null) return null
        if (!SourceSystemConnectorSupport.isAllowedLookupServiceShape(lookupServiceName)) return null
        String configId = resolveSourceConfigId(source, connector, runConfigDefaults)
        if (configId == null) return null
        String configParameterName = normalize(connector.get("configParameterName")) ?: "sourceConfigId"
        return { List<String> ids ->
            return dispatcher.call(lookupServiceName, [(configParameterName): configId,
                                                       (idsParameterName)   : ids,
                                                       companyUserGroupId   : runOwnerUserGroupId])
        }
    }

    private static Long longOrNull(Object raw) {
        return raw instanceof Number ? ((Number) raw).longValue() : null
    }

    /**
     * The STAGE_VERIFY metrics body for a return-presence pass. Shared so the two entry points cannot
     * publish differently-shaped timeline metrics for the same pass — several passes share the stage
     * code and are told apart only by what they record here.
     */
    static Map<String, Object> returnPresenceMetrics(Map verification, String omsLabel, String shopifyLabel) {
        Map result = verification ?: [:]
        return [verifiedSystems               : [omsLabel, shopifyLabel].findAll { it },
                performed                     : result.get("performed") == true,
                pendingCount                  : result.get("pendingCount") ?: 0,
                preWindowSuppressedCount      : result.get("preWindowSuppressedCount") ?: 0,
                cancelledRefundSuppressedCount: result.get("cancelledRefundSuppressedCount") ?: 0,
                siblingReturnSuppressedCount  : result.get("siblingReturnSuppressedCount") ?: 0,
                removedCount                  : result.get("removedCount") ?: 0] as Map<String, Object>
    }

    /** Lenient JSON-object read: an absent, blank or malformed document is an empty map, never a throw. */
    private static Map<String, Object> parseJsonMap(String rawJson) {
        if (!rawJson) return [:]
        try {
            Object parsed = new JsonSlurper().parseText(rawJson)
            return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
        } catch (Exception ignored) {
            return [:]
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
