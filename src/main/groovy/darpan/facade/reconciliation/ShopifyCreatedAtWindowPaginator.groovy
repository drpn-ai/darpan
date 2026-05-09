package darpan.facade.reconciliation

import java.sql.Timestamp

import static darpan.common.ValueSupport.boundedInt
import static darpan.common.ValueSupport.normalize

class ShopifyCreatedAtWindowPaginator {
    static final String DATE_FIELD = "created_at"
    static final int DEFAULT_MAX_PAGES_PER_WINDOW = 20
    static final int DEFAULT_MAX_WINDOWS = 512

    static Map<String, Object> collect(Timestamp windowStartDate, Timestamp windowEndDate, Map options = [:],
            Closure<Map<String, Object>> pageFetcher) {
        String field = normalize(options.field) ?: DATE_FIELD
        int maxPagesPerWindow = boundedInt(options.maxPagesPerWindow, DEFAULT_MAX_PAGES_PER_WINDOW, 1, Integer.MAX_VALUE)
        int maxWindows = boundedInt(options.maxWindows, DEFAULT_MAX_WINDOWS, 1, Integer.MAX_VALUE)
        Closure<String> formatWindow = options.formatWindow instanceof Closure ?
                (Closure<String>) options.formatWindow :
                { Timestamp timestamp -> timestamp.toInstant().toString() }

        if (windowStartDate == null || windowEndDate == null || !windowStartDate.before(windowEndDate)) {
            return [
                    records         : [],
                    searchQueries   : [],
                    dateFilterSets  : [[field: field]],
                    pageCount       : 0,
                    windowCount     : 0,
                    splitWindowCount: 0,
                    errors          : ["Shopify API window start must be before window end."],
            ]
        }

        ArrayDeque<Map<String, Timestamp>> pendingWindows = new ArrayDeque<>()
        pendingWindows.add([windowStartDate: windowStartDate, windowEndDate: windowEndDate] as Map<String, Timestamp>)

        List<Map<String, Object>> records = []
        List<String> searchQueries = []
        int pageCount = 0
        int windowCount = 0
        int splitWindowCount = 0

        while (!pendingWindows.isEmpty()) {
            if (windowCount >= maxWindows) {
                return failure(records, searchQueries, field, pageCount, windowCount, splitWindowCount,
                        "Shopify API window split limit (${maxWindows}) was reached for ${field}. Choose a smaller time period.")
            }

            Map<String, Timestamp> window = pendingWindows.removeFirst()
            windowCount++
            Map<String, Object> windowResult = scanWindow(window.windowStartDate, window.windowEndDate, field,
                    maxPagesPerWindow, formatWindow, pageFetcher)
            pageCount += (windowResult.pageCount ?: 0) as int

            List<String> errors = normalizeErrors(windowResult.errors)
            if (errors) {
                return [
                        records         : records,
                        searchQueries   : searchQueries.unique(),
                        dateFilterSets  : [[field: field]],
                        pageCount       : pageCount,
                        windowCount     : windowCount,
                        splitWindowCount: splitWindowCount,
                        errors          : errors,
                ]
            }

            if (windowResult.exhausted == true) {
                List<Map<String, Timestamp>> splitWindows = splitWindow(window.windowStartDate, window.windowEndDate)
                if (!splitWindows) {
                    return failure(records, searchQueries, field, pageCount, windowCount, splitWindowCount,
                            "Shopify API returned more than ${maxPagesPerWindow} pages for ${field} in the minimum split window. Choose a smaller time period.")
                }
                if (windowCount + pendingWindows.size() + splitWindows.size() > maxWindows) {
                    return failure(records, searchQueries, field, pageCount, windowCount, splitWindowCount,
                            "Shopify API window split limit (${maxWindows}) would be exceeded for ${field}. Choose a smaller time period.")
                }
                splitWindowCount++
                pendingWindows.addFirst(splitWindows[1])
                pendingWindows.addFirst(splitWindows[0])
                continue
            }

            records.addAll((List<Map<String, Object>>) (windowResult.records ?: []))
            searchQueries.addAll((List<String>) (windowResult.searchQueries ?: []))
        }

        return [
                records         : records,
                searchQueries   : searchQueries.unique(),
                dateFilterSets  : [[field: field]],
                pageCount       : pageCount,
                windowCount     : windowCount,
                splitWindowCount: splitWindowCount,
                errors          : [],
        ]
    }

    private static Map<String, Object> scanWindow(Timestamp windowStartDate, Timestamp windowEndDate, String field,
            int maxPagesPerWindow, Closure<String> formatWindow, Closure<Map<String, Object>> pageFetcher) {
        String windowStartText = formatWindow.call(windowStartDate)
        String windowEndText = formatWindow.call(windowEndDate)
        String searchQuery = "${field}:>=${renderSearchDateTime(windowStartText)} ${field}:<${renderSearchDateTime(windowEndText)}"
        List<Map<String, Object>> records = []
        int pageCount = 0
        String afterCursor = null
        boolean hasMorePages = false

        while (pageCount < maxPagesPerWindow) {
            pageCount++
            Map<String, Object> pageResult = (Map<String, Object>) (pageFetcher.call([
                    field          : field,
                    windowStartDate: windowStartDate,
                    windowEndDate  : windowEndDate,
                    windowStartText: windowStartText,
                    windowEndText  : windowEndText,
                    searchQuery    : searchQuery,
                    afterCursor    : afterCursor,
                    pageNumber     : pageCount,
            ]) ?: [:])

            List<String> errors = normalizeErrors(pageResult.errors)
            if (errors) return [records: records, searchQueries: [searchQuery], pageCount: pageCount, errors: errors]

            if (pageResult.records instanceof Collection) {
                records.addAll(((Collection) pageResult.records).findAll { Object record -> record instanceof Map } as List<Map<String, Object>>)
            }

            hasMorePages = pageResult.hasMorePages == true
            if (!hasMorePages) {
                return [records: records, searchQueries: [searchQuery], pageCount: pageCount, exhausted: false, errors: []]
            }
            afterCursor = normalize(pageResult.afterCursor ?: pageResult.endCursor)
            if (!afterCursor) {
                return [records: records, searchQueries: [searchQuery], pageCount: pageCount, exhausted: false, errors: []]
            }
        }

        return [records: records, searchQueries: [searchQuery], pageCount: pageCount, exhausted: hasMorePages, errors: []]
    }

    private static List<Map<String, Timestamp>> splitWindow(Timestamp windowStartDate, Timestamp windowEndDate) {
        long startMillis = windowStartDate.time
        long endMillis = windowEndDate.time
        if (endMillis - startMillis < 2L) return []

        long middleMillis = startMillis + ((endMillis - startMillis) / 2L)
        if (middleMillis <= startMillis || middleMillis >= endMillis) return []

        Timestamp middle = new Timestamp(middleMillis)
        return [
                [windowStartDate: windowStartDate, windowEndDate: middle] as Map<String, Timestamp>,
                [windowStartDate: middle, windowEndDate: windowEndDate] as Map<String, Timestamp>,
        ]
    }

    private static Map<String, Object> failure(List<Map<String, Object>> records, List<String> searchQueries, String field,
            int pageCount, int windowCount, int splitWindowCount, String error) {
        return [
                records         : records,
                searchQueries   : searchQueries.unique(),
                dateFilterSets  : [[field: field]],
                pageCount       : pageCount,
                windowCount     : windowCount,
                splitWindowCount: splitWindowCount,
                errors          : [error],
        ]
    }

    private static List<String> normalizeErrors(Object rawErrors) {
        if (!(rawErrors instanceof Collection)) return []
        return ((Collection) rawErrors)
                .collect { Object error -> normalize(error) }
                .findAll { String error -> error }
    }

    private static String renderSearchDateTime(String value) {
        if (value == null) return "''"
        return "'${value.replace("'", "\\'")}'"
    }

}
