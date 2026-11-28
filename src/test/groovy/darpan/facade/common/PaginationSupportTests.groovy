package darpan.facade.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class PaginationSupportTests {

    @Test
    void pagesAListAndCountsItsPages() {
        assertEquals([3, 4], PaginationSupport.pageRows([1, 2, 3, 4, 5], 1, 2))
        assertEquals([5], PaginationSupport.pageRows([1, 2, 3, 4, 5], 2, 2))
        assertEquals([], PaginationSupport.pageRows([1, 2, 3, 4, 5], 9, 2), "past the end is an empty page")
        assertEquals([pageIndex: 1, pageSize: 2, totalCount: 5, pageCount: 3], PaginationSupport.pagination(1, 2, 5))
        assertEquals(1, PaginationSupport.pagination(0, 20, 0).pageCount, "an empty list still has one page")
    }

    // S26a: a negative page threw out of subList, and a zero size divided by zero.
    @Test
    void anUnclampedPageOrSizeReadsAsTheFirstPageOfOne() {
        assertEquals([1, 2], PaginationSupport.pageRows([1, 2, 3], -1, 2))
        assertEquals([1], PaginationSupport.pageRows([1, 2, 3], 0, 0))
        assertEquals([pageIndex: 0, pageSize: 1, totalCount: 3, pageCount: 3], PaginationSupport.pagination(-4, 0, 3))
    }
}
