package darpan.config

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-OPS-015. TenantConfigPending carries the DARPAN_ACTIVE_COMPANY_SCOPE filter like every tenant-owned entity, and its
 * two services still work with it loaded: Moqui runs them (a scheduled job and an async mark) with authz disabled, where
 * no entity filter applies, so the job sees every tenant's row and a mark finds the row it updates.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantConfigPendingFilterSmokeTests {

    private ExecutionContext ec
    private static final Timestamp T = Timestamp.valueOf("2026-11-01 00:00:00")

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "tenant-config-pending-filter")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    /**
     * One service call on a fresh thread, no user, the way Moqui runs both: a scheduled job and an async call each call
     * their service with disableAuthz() (ServiceCallJobImpl, ServiceCallAsyncImpl).
     */
    private Map asJob(String verbNoun, Map params) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                holder.result = tec.service.sync().name("config.TenantConfigServices.${verbNoun}").parameters(new LinkedHashMap(params))
                        .disableAuthz().call()
                holder.errors = new ArrayList<String>(tec.message.errors)
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "pending-filter-job")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private Map pending(String tenantId) {
        def row = ec.entity.find(TenantConfigPendingSupport.ENTITY).condition("companyUserGroupId", tenantId).disableAuthz().useCache(false).one()
        return row?.getMap()
    }

    @Test
    void theFilterIsLoadedAndTheJobAndTheMarkStillSeeEveryTenantsRow() {
        assertEquals(1L, ec.entity.find("moqui.security.EntityFilter").condition("entityFilterId", "DARPAN_SCOPE_TENANT_CONFIG_PENDING")
                .disableAuthz().count(), "the filter row is loaded")

        asJob("note#TenantConfigChange", [tenantId: "PF_ALPHA", userId: "U1"])
        asJob("note#TenantConfigChange", [tenantId: "PF_ALPHA", userId: "U1"])
        asJob("note#TenantConfigChange", [tenantId: "PF_BRAVO", userId: "U2"])
        assertEquals(2L, pending("PF_ALPHA").changeCount as long, "the second mark found the row the first made")
        assertEquals(1L, pending("PF_BRAVO").changeCount as long)

        ec.entity.find(TenantConfigPendingSupport.ENTITY).disableAuthz().list().each { it.set("lastChangeDate", T); it.update() }
        Map out = asJob("snapshot#PendingTenantConfigs", [nowStamp: new Timestamp(T.time + 61_000L)])
        assertEquals(["PF_ALPHA", "PF_BRAVO"], (out.result.outcomes as List<Map>)*.tenant.sort(), "the job sees both tenants' rows")
    }
}
