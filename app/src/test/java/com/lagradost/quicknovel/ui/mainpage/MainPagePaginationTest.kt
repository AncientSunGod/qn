package com.lagradost.quicknovel.ui.mainpage

import com.lagradost.quicknovel.ui.common.ImmutableSearchResponse
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainPagePaginationTest {
    private fun item(id: Int) = ImmutableSearchResponse(
        name = "Novel $id",
        url = "https://example.com/novel/$id",
        apiName = "Test",
        timeOfCached = 0L,
        chaptersRead = 0,
    )

    @Test
    fun emptyOrDuplicatePageMarksEndReachedAndPreservesExistingItems() {
        val page1 = listOf(item(1), item(2))
        val (afterPage1, endAfter1) = mergeMainPageItems(persistentListOf(), page1)
        assertFalse(endAfter1)
        assertEquals(2, afterPage1.size)

        val (afterEmpty, endAfterEmpty) = mergeMainPageItems(afterPage1, emptyList())
        assertTrue(endAfterEmpty)
        assertEquals(2, afterEmpty.size)

        val (afterDuplicate, endAfterDuplicate) = mergeMainPageItems(afterPage1, listOf(item(1), item(2)))
        assertTrue(endAfterDuplicate)
        assertEquals(2, afterDuplicate.size)

        val (afterMixed, endAfterMixed) = mergeMainPageItems(afterPage1, listOf(item(2), item(3)))
        assertFalse(endAfterMixed)
        assertEquals(listOf("https://example.com/novel/1", "https://example.com/novel/2", "https://example.com/novel/3"), afterMixed.map { it.url })
    }
}
