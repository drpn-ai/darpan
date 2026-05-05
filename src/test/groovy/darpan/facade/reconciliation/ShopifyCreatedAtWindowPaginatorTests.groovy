package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ShopifyCreatedAtWindowPaginatorTests {
    @Test
    void collectsSingleWindowWhenPageCapIsNotReached() {
        Map<String, Object> result = ShopifyCreatedAtWindowPaginator.collect(
                timestamp("2026-05-01T00:00:00Z"),
                timestamp("2026-05-02T00:00:00Z"),
                [formatWindow: formatter()]
        ) { Map<String, Object> request ->
            return [
                    records     : [[id: "A100", searchQuery: request.searchQuery]],
                    hasMorePages: false,
            ]
        }

        assertTrue(((List) result.errors).isEmpty())
        assertEquals(1, ((List) result.records).size())
        assertEquals(["created_at:>='2026-05-01T00:00:00Z' created_at:<'2026-05-02T00:00:00Z'"], result.searchQueries)
        assertEquals(1, result.pageCount)
        assertEquals(1, result.windowCount)
        assertEquals(0, result.splitWindowCount)
    }

    @Test
    void splitsOversizedWindowsAndKeepsOnlyAcceptedSliceRecords() {
        Map<String, Object> result = ShopifyCreatedAtWindowPaginator.collect(
                timestamp("2026-05-01T00:00:00Z"),
                timestamp("2026-05-02T00:00:00Z"),
                [maxPagesPerWindow: 2, maxWindows: 8, formatWindow: formatter()]
        ) { Map<String, Object> request ->
            Timestamp start = (Timestamp) request.windowStartDate
            Timestamp end = (Timestamp) request.windowEndDate
            long hours = Duration.between(start.toInstant(), end.toInstant()).toHours()
            if (hours > 12L) {
                return [
                        records     : [[id: "discard-${request.pageNumber}"]],
                        hasMorePages: true,
                        afterCursor : "cursor-${request.pageNumber}",
                ]
            }

            return [
                    records     : [[id: "${request.windowStartText}:${request.pageNumber}"]],
                    hasMorePages: ((Integer) request.pageNumber) < 2,
                    afterCursor : "cursor-${request.pageNumber}",
            ]
        }

        List<Map<String, Object>> records = (List<Map<String, Object>>) result.records
        assertTrue(((List) result.errors).isEmpty())
        assertEquals(4, records.size())
        assertFalse(records.any { Map<String, Object> record -> (record.id as String).startsWith("discard-") })
        assertEquals([
                "created_at:>='2026-05-01T00:00:00Z' created_at:<'2026-05-01T12:00:00Z'",
                "created_at:>='2026-05-01T12:00:00Z' created_at:<'2026-05-02T00:00:00Z'",
        ], result.searchQueries)
        assertEquals(6, result.pageCount)
        assertEquals(3, result.windowCount)
        assertEquals(1, result.splitWindowCount)
    }

    @Test
    void returnsSplitLimitErrorInsteadOfLoopingForever() {
        Map<String, Object> result = ShopifyCreatedAtWindowPaginator.collect(
                timestamp("2026-05-01T00:00:00Z"),
                timestamp("2026-05-02T00:00:00Z"),
                [maxPagesPerWindow: 1, maxWindows: 1, formatWindow: formatter()]
        ) { Map<String, Object> request ->
            return [
                    records     : [[id: "discarded"]],
                    hasMorePages: true,
                    afterCursor : "cursor-${request.pageNumber}",
            ]
        }

        assertTrue(((List) result.records).isEmpty())
        assertTrue(((List<String>) result.errors).any { String error -> error.contains("split limit") })
        assertEquals(1, result.pageCount)
        assertEquals(1, result.windowCount)
    }

    private static Closure<String> formatter() {
        return { Timestamp timestamp -> timestamp.toInstant().toString() }
    }

    private static Timestamp timestamp(String instantText) {
        return Timestamp.from(Instant.parse(instantText))
    }
}
