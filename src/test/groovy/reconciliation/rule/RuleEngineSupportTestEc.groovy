package reconciliation.rule

import darpan.common.DarpanEntityConstants
import darpan.reconciliation.support.FakeEntityFacade
import darpan.reconciliation.support.FakeTenantEc

/**
 * A stub ExecutionContext for RuleEngineSupport without Moqui: a signed-in user whose active tenant is {@code tenant},
 * over the shared {@link FakeEntityFacade} (finders answer as Moqui's do). Shared by the tenant-denial and invariant
 * tests.
 */
class RuleEngineSupportTestEc {

    /** An ec whose user is active in {@code tenant}; {@code ruleSet} is the one RuleSet row (null: none), {@code rules} the Rule rows. */
    static Expando ec(String tenant, Map ruleSet, List<Map> rules = []) {
        FakeEntityFacade entity = new FakeEntityFacade()
                .table(DarpanEntityConstants.RULE_SET, ["ruleSetId"], ruleSet ? [ruleSet] : [])
                .table("darpan.rule.Rule", ["ruleId"], rules)
        return FakeTenantEc.ec("EX_USER", tenant, entity)
    }
}
