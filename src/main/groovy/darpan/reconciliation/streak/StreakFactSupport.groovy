package darpan.reconciliation.streak

import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.notification.AstronomyFacts
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.util.concurrent.ThreadLocalRandom

/**
 * DAR-BE-138: the astronomy fact a badge or side-quest message carries. A tenant never hears the same fact twice
 * (Aditi 2026-10-27): each fact told is a TenantStreakFact row keyed by tenant and fact, so a second reservation of it
 * fails on insert.
 *
 * <p>The fact is picked when the message is built, not when the badge is earned, so a history backfill that earns
 * badges silently spends none. Building the same message again finds the row it reserved and tells the same fact.
 * Once a tenant has heard every fact the message goes out without one: silence beats a repeat.</p>
 */
class StreakFactSupport {

    static final String FACT = "darpan.reconciliation.TenantStreakFact"

    /** Two messages of one tenant reserving at the same moment can pick the same fact; the loser picks again. */
    private static final int ATTEMPTS = 3

    private static final Logger logger = LoggerFactory.getLogger(StreakFactSupport.class)

    /**
     * The fact for the message celebrating {@code awardKey}: the one already reserved for this earn, or a fact this
     * tenant has not heard. Null when every fact is spent, or when reserving failed: a fact must never cost a message.
     */
    static String factFor(def ec, String tenant, String awardKey, String automationId, Timestamp earnedSlotDate) {
        if (!tenant || !awardKey) return null
        Throwable last = null
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                String factId = (String) AutomationRuntimeSupport.runInNewTransaction(ec, "Error reserving a streak fact") { ->
                    return reserve(ec, tenant, awardKey, automationId, earnedSlotDate)
                }
                return AstronomyFacts.text(factId)
            } catch (Throwable t) {
                last = t
            }
        }
        logger.warn("No streak fact for tenant {} award {}: {}", tenant, awardKey, last?.message)
        return null
    }

    private static String reserve(def ec, String tenant, String awardKey, String automationId, Timestamp earnedSlotDate) {
        List told = TenantScopedFinder.findGlobalUnscoped(ec, FACT,
                "streak facts a tenant has heard — explicit companyUserGroupId condition applied")
                .condition("companyUserGroupId", tenant).useCache(false).list() ?: []
        def mine = told.find {
            it.awardKey == awardKey && it.automationId == automationId && (it.earnedSlotDate as Timestamp) == earnedSlotDate
        }
        if (mine != null) return mine.factId as String

        String factId = AstronomyFacts.pickUnheard(told.collect { it.factId as String } as Set<String>, ThreadLocalRandom.current())
        if (factId == null) return null
        def row = ec.entity.makeValue(FACT)
        row.set("companyUserGroupId", tenant)
        row.set("factId", factId)
        row.set("awardKey", awardKey)
        row.set("automationId", automationId)
        row.set("earnedSlotDate", earnedSlotDate)
        row.set("usedDate", new Timestamp(System.currentTimeMillis()))
        row.create()
        return factId
    }
}
