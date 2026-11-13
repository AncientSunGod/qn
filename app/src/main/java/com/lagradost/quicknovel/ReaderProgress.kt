package com.lagradost.quicknovel

import kotlin.math.roundToInt

/**
 * Converts a character offset in the current chapter into a continuous 0..1000
 * whole-book progress value. Keeping this independent of RecyclerView positions
 * avoids jumps when the reader replaces its bounded set of adapter rows.
 */
internal fun calculateReaderProgress(
    chapterIndex: Int,
    chapterCount: Int,
    characterPosition: Int,
    chapterLength: Int,
): Int {
    if (chapterCount <= 0) return 0

    val safeChapterIndex = chapterIndex.coerceIn(0, chapterCount - 1)
    val chapterFraction = if (chapterLength <= 0) {
        0f
    } else {
        characterPosition.coerceIn(0, chapterLength).toFloat() / chapterLength.toFloat()
    }
    val bookFraction = (safeChapterIndex + chapterFraction) / chapterCount.toFloat()
    return (bookFraction * 1000f).roundToInt().coerceIn(0, 1000)
}

/**
 * Resolves the whole-book progress value using viewport top position during normal scrolling,
 * and advances to the end of the visible chapter when the reader cannot scroll down further
 * (e.g., short chapters or the bottom of the final chapter).
 */
internal fun resolveEffectiveReaderProgress(
    topChapterIndex: Int,
    topCharacterPosition: Int,
    topChapterLength: Int,
    bottomChapterIndex: Int?,
    bottomChapterLength: Int,
    chapterCount: Int,
    canScrollDown: Boolean,
    hasLaterRenderedChapter: Boolean = false,
): Int {
    if (!canScrollDown && bottomChapterIndex != null && !hasLaterRenderedChapter) {
        return calculateReaderProgress(
            chapterIndex = bottomChapterIndex,
            chapterCount = chapterCount,
            characterPosition = bottomChapterLength,
            chapterLength = bottomChapterLength,
        )
    }
    return calculateReaderProgress(
        chapterIndex = topChapterIndex,
        chapterCount = chapterCount,
        characterPosition = topCharacterPosition,
        chapterLength = topChapterLength,
    )
}

