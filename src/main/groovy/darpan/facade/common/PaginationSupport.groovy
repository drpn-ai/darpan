package darpan.facade.common

/** One page of an in-memory list. A negative page reads as the first and a size under one as one, so no caller's
 *  unclamped input can throw (subList) or divide by zero. */
class PaginationSupport {
    static Map<String, Object> pagination(int page, int size, int totalCount) {
        int safeSize = Math.max(1, size)
        return [
                pageIndex : Math.max(0, page),
                pageSize  : safeSize,
                totalCount: totalCount,
                pageCount : Math.max(1, Math.ceil(totalCount / (double) safeSize) as int),
        ]
    }

    static <T> List<T> pageRows(List<T> rows, int page, int size) {
        int totalCount = rows.size()
        int fromIndex = Math.min(Math.max(0, page) * Math.max(1, size), totalCount)
        int toIndex = Math.min(fromIndex + Math.max(1, size), totalCount)
        return rows.subList(fromIndex, toIndex)
    }
}
