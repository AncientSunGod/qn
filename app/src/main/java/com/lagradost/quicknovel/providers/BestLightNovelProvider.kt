package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.Score
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.textClean
import java.net.URLEncoder

object BestLightNovelProvider : MainAPI() {
    override val name = "BestLightNovel"
    override val mainUrl = "https://bestlightnovel.com"

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        val res = document.selectFirst("div.vung_doc")
        return res?.html().textClean?.replace("[Updated from F r e e w e b n o v e l. c o m]", "")
            ?.replace(
                "Find authorized novels in Webnovel，faster updates, better experience，Please click for visiting. ",
                ""
            )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim().replace(' ', '_'), "UTF-8")
        val document = app.get("$mainUrl/search_novels/$encoded").document

        val headers = document.select("div.danh_sach > div.list_category")
        return headers.mapNotNull {
            val head = it.selectFirst("> a")
            val name = head?.attr("title")?.takeIf { t -> t.isNotBlank() } ?: return@mapNotNull null
            val url = head.attr("href").takeIf { h -> h.isNotBlank() } ?: return@mapNotNull null

            newSearchResponse(name = name, url = url) {
                latestChapter = it.selectFirst("> a.chapter")?.text()
                posterUrl = fixUrlNull(head.selectFirst("> img")?.attr("src"))
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val infoHeaders = document.select("ul.truyen_info_right > li")

        val name = infoHeaders.getOrNull(0)?.selectFirst("> h1")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: return null

        val chapterHeaders = document.select("div.chapter-list > div").mapNotNull {
            val spans = it.select("> span")
            val text = spans.getOrNull(0)?.selectFirst("> a") ?: return@mapNotNull null
            val cUrl = text.attr("href").takeIf { href -> href.isNotBlank() } ?: return@mapNotNull null
            val cName = text.text().takeIf { title -> title.isNotBlank() } ?: return@mapNotNull null
            newChapterData(name = cName, url = cUrl) {
                dateOfRelease = spans.getOrNull(1)?.text()
            }
        }.reversed()

        return newStreamResponse(url = url, name = name, data = chapterHeaders) {
            infoHeaders.getOrNull(1)?.select("> a")?.forEach { a ->
                val href = a.attr("href")
                if (a.hasText() && href.length > "$mainUrl/search_author/".length && href.startsWith("$mainUrl/search_author/")) {
                    author = a.text()
                    return@forEach
                }
            }
            posterUrl = fixUrlNull(document.selectFirst("span.info_image > img")?.attr("src"))
            tags = infoHeaders.getOrNull(2)?.select("> a")?.map { it.text() }
            synopsis = document.select("div.entry-header > div").getOrNull(1)?.text().textClean
            setStatus(infoHeaders.getOrNull(3)?.selectFirst("> a")?.text())
            views = infoHeaders.getOrNull(6)?.text()
                ?.replace(",", "")
                ?.replace("\"", "")
                ?.removePrefix("View : ")
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
            try {
                val ratingHeader = infoHeaders.getOrNull(9)?.selectFirst("> em > em")?.select("> em")
                score = Score.from5(ratingHeader?.getOrNull(1)?.selectFirst("> em > em")?.text())
                peopleVoted = ratingHeader?.getOrNull(2)?.text()?.replace(",", "")?.toIntOrNull() ?: 0
            } catch (_: Throwable) {
            }
        }
    }
}