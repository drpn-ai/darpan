package darpan.conclusion.brain.fact

import groovy.transform.CompileStatic
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/**
 * DAR-BE-192. The return a replacement order (an exchange or warranty replacement) was made for, as HotWax holds it, and,
 * once asked, what NetSuite holds for it: its RMA and its credit memo. HotWax sends the replacement to NetSuite only once
 * the return has a linked memo, so this is what a held replacement waits on. Answered(returnRma | returnMemo, this): NetSuite
 * was asked; a null tranId after that is "NetSuite has none".
 */
@CompileStatic
@EqualsAndHashCode(includes = ["returnId"])
@ToString(includes = ["returnId", "statusId"], includePackage = false)
class ReplacementReturn {
    Order replacement
    String returnId
    String statusId
    String channel
    /** The credit HotWax gave the return (the payments its responses refunded). */
    BigDecimal credit
    /** When HotWax entered the return, YYYY-MM-DD in UTC. */
    String entered
    String rmaTranId
    String rmaStatus
    BigDecimal rmaTotal
    String memoTranId
    BigDecimal memoAmount
}
