package darpan.facade.common

class PaginationSupport {
    static Map<String, Object> pagination(int page, int size, int totalCount) {
        return [
                pageIndex : page,
                pageSize  : size,
                totalCount: totalCount,
                pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int),
        ]
    }

    static <T> List<T> pageRows(List<T> rows, int page, int size) {
        int totalCount = rows.size()
        int fromIndex = Math.min(page * size, totalCount)
        int toIndex = Math.min(fromIndex + size, totalCount)
        return rows.subList(fromIndex, toIndex)
    }
}
