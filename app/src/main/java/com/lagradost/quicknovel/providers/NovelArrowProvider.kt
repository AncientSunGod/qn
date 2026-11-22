package com.lagradost.quicknovel.providers

import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

internal data class ParsedNovelPingSearchResult(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val latestChapter: String?,
)

internal fun parseNovelPingSearchHtml(
    document: Document,
    mainUrl: String = "https://novelping.com",
): List<ParsedNovelPingSearchResult> {
    val root = mainUrl.trimEnd('/')
    val cards = document.select("#search-results .list-novel > .row, .list-novel > .row")
    return cards.mapNotNull { row ->
        val link = row.selectFirst("h3.novel-title a[href], h3 a[href]") ?: return@mapNotNull null
        val title = link.attr("title").trim().ifBlank { link.text().trim() }
        if (title.isBlank()) return@mapNotNull null
        val rawHref = link.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val fullUrl = when {
            rawHref.startsWith("http://") || rawHref.startsWith("https://") -> rawHref
            rawHref.startsWith("/") -> "$root$rawHref"
            else -> "$root/$rawHref"
        }
        val img = row.selectFirst("img")
        val rawPoster = img?.let {
            it.attr("data-src").trim().ifBlank { it.attr("src").trim() }
        }?.takeIf { it.isNotBlank() }
        val posterUrl = rawPoster?.let {
            when {
                it.startsWith("http://") || it.startsWith("https://") -> it
                it.startsWith("//") -> "https:$it"
                it.startsWith("/") -> "$root$it"
                else -> "$root/$it"
            }
        }
        val latest = row.selectFirst(".chapter-text, .text-info a, a[href*='/chapter']")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        ParsedNovelPingSearchResult(
            name = title,
            url = fullUrl,
            posterUrl = posterUrl,
            latestChapter = latest,
        )
    }
}

internal fun parseNovelPingArchiveHtml(
    archiveDocument: Document,
    mainUrl: String = "https://novelping.com",
): List<ChapterData> {
    val root = mainUrl.trimEnd('/')
    val template = archiveDocument.selectFirst("template")
    val chapterDoc = template?.let { Jsoup.parse(it.html()) } ?: archiveDocument
    return chapterDoc.select("li a[href], a[href]").mapNotNull { link ->
        val href = link.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val chapterUrl = when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("/") -> "$root$href"
            else -> "$root/$href"
        }
        val title = link.attr("title").trim().ifBlank { link.text().trim() }
        if (title.isBlank()) return@mapNotNull null
        ChapterData(name = title, url = chapterUrl, dateOfRelease = null)
    }.distinctBy { it.url }
}

/**
 * NovelArrow (novelping.com) provider supporting both the current HTML /search + /book/{slug}
 * + /ajax/chapter-archive?novelId={slug} endpoints and the legacy /api-web/novels/{id} API.
 */
object NovelArrowProvider : MainAPI() {
    override val name = "NovelArrow"
    override val mainUrl = "https://novelping.com"
    override val hasMainPage = true
    override val usesCloudFlareKiller = true

    private val apiBase = "$mainUrl/api-web"

    override val mainCategories = listOf(
        "All" to "all",
        "Ongoing" to "ongoing",
        "Completed" to "completed"
    )

    override val orderBys = listOf("Default / latest" to "")

    override val tags = listOf(
        "All" to "",
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "Drama" to "drama",
        "Eastern" to "eastern",
        "Fan-fiction" to "fan-fiction",
        "Fantasy" to "fantasy",
        "Game" to "game",
        "Gender Bender" to "gender-bender",
        "Historical" to "historical",
        "Horror" to "horror",
        "Isekai" to "isekai",
        "Magic" to "magic",
        "Martial Arts" to "martial-arts",
        "Mecha" to "mecha",
        "Mystery" to "mystery",
        "Psychological" to "psychological",
        "Reincarnation" to "reincarnation",
        "Romance" to "romance",
        "School Life" to "school-life",
        "Sci-fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shounen" to "shounen",
        "Slice of Life" to "slice-of-life",
        "Sports" to "sports",
        "Supernatural" to "supernatural",
        "System" to "system",
        "Tragedy" to "tragedy",
        "Wuxia" to "wuxia",
        "Xianxia" to "xianxia",
        "Xuanhuan" to "xuanhuan"
    )

    private fun JSONObject.stringOrNull(key: String): String? {
        val value = optString(key, "").trim()
        return value.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun coverUrl(id: String): String = "https://images.novelping.com/novel/$id.jpg"

    private suspend fun getJson(url: String): JSONObject {
        val response = app.get(url)
        val body = response.text
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ErrorLoadingException("NovelArrow returned an unreadable response. Open verification if challenge is shown.")
        }
    }

    private fun parseNovelItems(items: JSONArray): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val genres = item.optJSONArray("novel_genres")
            val restrictedGenres = setOf("adult", "ecchi", "smut", "mature")
            val hasRestrictedGenre = genres?.let { values ->
                (0 until values.length()).any { genreIndex ->
                    values.optString(genreIndex).trim().lowercase() in restrictedGenres
                }
            } ?: false
            if (hasRestrictedGenre) continue

            val slug = item.firstNonBlank("novel_slug", "slug")
            val id = item.stringOrNull("novel_id") ?: slug ?: continue
            val title = item.stringOrNull("novel_name") ?: continue
            val itemUrl = if (!slug.isNullOrBlank()) "$mainUrl/book/$slug" else "$mainUrl/novel/$id"
            results += newSearchResponse(title, itemUrl) {
                posterUrl = coverUrl(slug ?: id)
            }
        }
        return results
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val status = mainCategory?.takeIf { it.isNotBlank() } ?: "all"
        val genre = tag?.takeIf { it.isNotBlank() } ?: ""
        val apiUrl = "$apiBase/novels?page=$page&status=${URLEncoder.encode(status, "UTF-8")}&genre=${URLEncoder.encode(genre, "UTF-8")}"
        val apiResults = runCatching {
            val json = getJson(apiUrl)
            parseNovelItems(json.optJSONArray("items") ?: JSONArray())
        }.getOrElse { error ->
            if (com.lagradost.quicknovel.network.CloudflareChallengeManager.findCloudflareChallenge(error) != null) {
                throw error
            }
            emptyList()
        }
        if (apiResults.isNotEmpty()) {
            return HeadMainPageResponse(apiUrl, apiResults)
        }

        val htmlUrl = if (genre.isNotBlank()) {
            "$mainUrl/genre/${URLEncoder.encode(genre, "UTF-8")}?page=$page"
        } else if (status == "completed") {
            "$mainUrl/completed?page=$page"
        } else {
            "$mainUrl/latest?page=$page"
        }
        val doc = app.get(htmlUrl).document
        val parsed = parseNovelPingSearchHtml(doc, mainUrl).map { item ->
            newSearchResponse(item.name, item.url) {
                posterUrl = fixUrlNull(item.posterUrl)
                latestChapter = item.latestChapter
            }
        }
        return HeadMainPageResponse(htmlUrl, parsed)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val combined = linkedMapOf<String, SearchResponse>()
        var maxDiscoveredPage = 1

        // 1. Primary live HTML search on novelping.com/search?keyword=...&page=N (20/page, real pagination)
        for (page in 1..MAX_SEARCH_PAGES) {
            val pageUrl = "$mainUrl/search?keyword=$encoded&page=$page"
            val doc = app.get(pageUrl).document
            if (page == 1) {
                val maxFromPager = doc.select("ul.pagination a[href*='page='], .pagination a[href*='page=']")
                    .mapNotNull { Regex("""page=(\d+)""").find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    .maxOrNull() ?: 1
                maxDiscoveredPage = maxFromPager.coerceIn(1, MAX_SEARCH_PAGES)
            }
            val parsed = parseNovelPingSearchHtml(doc, mainUrl)
            val before = combined.size
            parsed.forEach { item ->
                if (!combined.containsKey(item.url)) {
                    combined[item.url] = newSearchResponse(item.name, item.url) {
                        posterUrl = fixUrlNull(item.posterUrl)
                        latestChapter = item.latestChapter
                    }
                }
            }
            if (parsed.isEmpty() || combined.size == before || page >= maxDiscoveredPage || combined.size >= MAX_SEARCH_RESULTS) {
                break
            }
        }

        if (combined.isNotEmpty()) {
            return combined.values.take(MAX_SEARCH_RESULTS)
        }

        // 2. Fallback to JSON search endpoint
        var previousPageKeys: List<String>? = null
        for (page in 1..MAX_SEARCH_PAGES) {
            val url = "$apiBase/novels?page=$page&status=all&genre=&keyword=$encoded"
            val json = runCatching { getJson(url) }.getOrNull() ?: break
            val pageResults = parseNovelItems(json.optJSONArray("items") ?: JSONArray())
            val pageKeys = pageResults.map { it.url }
            if (pageKeys.isNotEmpty() && pageKeys == previousPageKeys) break
            previousPageKeys = pageKeys
            pageResults.forEach { result ->
                if (!combined.containsKey(result.url)) {
                    combined[result.url] = result
                }
            }
            if (pageResults.isEmpty() || combined.size >= MAX_SEARCH_RESULTS) break
        }

        return combined.values.take(MAX_SEARCH_RESULTS)
    }

    private fun novelIdFromUrl(url: String): String {
        return url.substringBefore("?").substringBefore("#").trimEnd('/').substringAfterLast('/')
    }

    private fun chapterItems(json: JSONObject): JSONArray? {
        val data = json.optJSONObject("data")
        val item = json.optJSONObject("item")
        return json.optJSONArray("items")
            ?: json.optJSONArray("chapters")
            ?: json.optJSONArray("data")
            ?: data?.optJSONArray("items")
            ?: data?.optJSONArray("chapters")
            ?: data?.optJSONArray("results")
            ?: data?.optJSONArray("docs")
            ?: data?.optJSONArray("list")
            ?: item?.optJSONArray("chapters")
            ?: item?.optJSONObject("novelInfo")?.optJSONArray("chapters")
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> stringOrNull(key) }

    private suspend fun getChapters(novelId: String): List<ChapterData> {
        val combined = linkedMapOf<String, ChapterData>()
        for (page in 1..MAX_CHAPTER_PAGES) {
            val pageQuery = if (page == 1) "" else "&page=$page"
            val json = getJson("$apiBase/novels/$novelId/chapters?sort=asc$pageQuery")
            val items = chapterItems(json) ?: break
            if (items.length() == 0) break

            val beforePage = combined.size
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val chapterId = item.firstNonBlank("chapter_id", "chapterId", "id", "_id")
                val chapterUrl = item.firstNonBlank("url", "path", "chapter_url", "chapterUrl")
                val title = item.firstNonBlank("chapter_name", "chapter_title", "title", "name")
                    ?: "Chapter ${combined.size + 1}"
                val targetUrl = when {
                    !chapterUrl.isNullOrBlank() && chapterUrl.startsWith("http") -> chapterUrl
                    !chapterUrl.isNullOrBlank() -> "$mainUrl${if (chapterUrl.startsWith("/")) "" else "/"}$chapterUrl"
                    !chapterId.isNullOrBlank() -> "$apiBase/novels/$novelId/chapters/$chapterId"
                    else -> continue
                }
                if (!combined.containsKey(targetUrl)) {
                    combined[targetUrl] = newChapterData(title, targetUrl)
                }
            }

            if (combined.size == beforePage) break
            val pagination = json.optJSONObject("pagination")
                ?: json.optJSONObject("meta")
                ?: json.optJSONObject("data")?.optJSONObject("pagination")
                ?: json.optJSONObject("data")?.optJSONObject("meta")
            val totalPages = pagination?.let {
                it.optInt(
                    "total_pages",
                    it.optInt("totalPages", it.optInt("last_page", it.optInt("lastPage", 0)))
                )
            } ?: 0
            if (totalPages > 0 && page >= totalPages) break
        }
        return combined.values.toList()
    }

    private suspend fun fetchArchiveChapters(novelSlug: String): List<ChapterData> {
        val archiveUrl = "$mainUrl/ajax/chapter-archive?novelId=${URLEncoder.encode(novelSlug, "UTF-8")}"
        val archiveDocument = app.get(archiveUrl, headers = mapOf("Referer" to "$mainUrl/book/$novelSlug")).document
        return parseNovelPingArchiveHtml(archiveDocument, mainUrl)
    }

    private suspend fun getHtmlChapterFallback(info: JSONObject): List<ChapterData> {
        val slugOrUrl = info.firstNonBlank(
            "novel_slug", "slug", "url_slug", "novel_url", "novel_link", "permalink"
        )
        val bookUrl = slugOrUrl?.let { candidate ->
            when {
                candidate.startsWith("http") -> candidate
                candidate.startsWith("/") -> "$mainUrl$candidate"
                else -> "$mainUrl/book/$candidate"
            }
        }

        val novelSlug = bookUrl?.let { raw ->
            runCatching { URI(raw).path?.trim('/')?.split('/')?.getOrNull(1) }.getOrNull()
        } ?: slugOrUrl?.takeIf { !it.contains("/") && !it.startsWith("http") }

        val archiveChapters = novelSlug?.let { slug ->
            runCatching { fetchArchiveChapters(slug) }.getOrDefault(emptyList())
        }.orEmpty()
        if (archiveChapters.isNotEmpty()) return archiveChapters

        val document = bookUrl?.let { candidate ->
            runCatching { app.get(candidate).document }.getOrNull()
        }
        val linkedChapters = document?.select(
            "#chapter-list a, #chapterList a, .chapter-list a, .chapter-list-content a, a[href*='chapter-']"
        )?.mapNotNull { link ->
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val chapterUrl = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
            val title = link.attr("title").trim().ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            newChapterData(title, chapterUrl)
        }?.distinctBy { it.url }.orEmpty()

        if (linkedChapters.isNotEmpty()) return linkedChapters
        val firstChapterUrl = document?.selectFirst("meta[property=og:novel:read_url]")
            ?.attr("content")
            ?.takeIf { it.startsWith("http") }
            ?: info.firstNonBlank("first_chapter_url", "firstChapterUrl", "read_url")
                ?.takeIf { it.startsWith("http") }
            ?: return emptyList()
        return listOf(newChapterData("Chapter 1", firstChapterUrl))
    }

    private suspend fun loadBookHtmlPage(bookUrl: String): LoadResponse {
        val response = app.get(bookUrl)
        val doc = response.document
        val title = doc.selectFirst("h3.title, h1.title, h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:novel:novel_name]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ErrorLoadingException("NovelArrow title was not found on $bookUrl")

        val dataNovelId = doc.selectFirst("[data-novel-id]")?.attr("data-novel-id")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: novelIdFromUrl(response.url)

        val chapters = runCatching { fetchArchiveChapters(dataNovelId) }.getOrElse { err ->
            if (com.lagradost.quicknovel.network.CloudflareChallengeManager.findCloudflareChallenge(err) != null) {
                throw err
            }
            emptyList()
        }.ifEmpty {
            doc.select("#list-chapter a, .list-chapter a, ul.list-chapter li a").mapNotNull { link ->
                val href = link.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val full = if (href.startsWith("http")) href else "$mainUrl${if (href.startsWith("/")) "" else "/"}$href"
                val name = link.attr("title").trim().ifBlank { link.text().trim() }
                if (name.isBlank()) return@mapNotNull null
                newChapterData(name, full)
            }.distinctBy { it.url }
        }

        return newStreamResponse(title, response.url, chapters) {
            author = doc.selectFirst("meta[property=og:novel:author]")?.attr("content")?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: doc.selectFirst("a[href*='/author/']")?.text()?.trim()
            posterUrl = fixUrlNull(
                doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst(".book img, .novel-cover-loading")?.attr("src")
            )
            synopsis = doc.selectFirst(".desc-text")?.let { el ->
                el.select("p").joinToString("\n\n") { it.text() }.ifBlank { el.text() }
            }?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            val rawGenres = doc.select("a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotEmpty() }
                .ifEmpty {
                    doc.selectFirst("meta[property=og:novel:genre]")?.attr("content")
                        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                }
            tags = rawGenres.ifEmpty { null }
            val statusStr = doc.selectFirst("meta[property=og:novel:status]")?.attr("content")
                ?: doc.selectFirst(".info a[href*='status'], .info .text-primary")?.text()
            setStatus(statusStr)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("?").substringBefore("#").trimEnd('/')
        if (cleanUrl.contains("/book/")) {
            return loadBookHtmlPage(cleanUrl)
        }

        val novelId = novelIdFromUrl(cleanUrl)
        if (novelId.isBlank()) throw ErrorLoadingException("NovelArrow novel ID not found")

        val apiResult = runCatching { getJson("$apiBase/novels/$novelId") }.getOrElse { error ->
            if (com.lagradost.quicknovel.network.CloudflareChallengeManager.findCloudflareChallenge(error) != null) {
                throw error
            }
            null
        }
        val info = apiResult?.optJSONObject("item")?.optJSONObject("novelInfo")
        if (info == null) {
            return loadBookHtmlPage("$mainUrl/book/$novelId")
        }

        val title = info.stringOrNull("novel_name")
            ?: return loadBookHtmlPage("$mainUrl/book/$novelId")
        val restrictedGenres = setOf("adult", "ecchi", "smut", "mature")
        val genres = info.optJSONArray("novel_genres")
        if (genres != null && (0 until genres.length()).any { index ->
                genres.optString(index).trim().lowercase() in restrictedGenres
            }) {
            throw ErrorLoadingException("This title is not available in the reader.")
        }
        val chapters = runCatching { getChapters(novelId) }.getOrDefault(emptyList()).ifEmpty {
            runCatching { getHtmlChapterFallback(info) }.getOrDefault(emptyList())
        }
        val synopsis = info.stringOrNull("novel_desc")?.let { html ->
            val body = Jsoup.parse(html).body()
            body.select("p").joinToString("\n\n") { it.text() }.ifBlank { body.text() }
        }?.takeIf { it.isNotBlank() }

        return newStreamResponse(title, cleanUrl, chapters) {
            author = info.stringOrNull("novel_author")
            posterUrl = coverUrl(info.firstNonBlank("novel_slug", "slug") ?: novelId)
            this.synopsis = synopsis
            tags = info.optJSONArray("novel_genres")?.let { genreList ->
                (0 until genreList.length()).mapNotNull { i ->
                    genreList.optString(i).takeIf { it.isNotBlank() && it != "null" }
                }
            }?.ifEmpty { null }

            when (info.optInt("novel_status", -1)) {
                0 -> setStatus("Ongoing")
                1 -> setStatus("Completed")
            }

            rating = info.optJSONObject("avgPoint")
                ?.optString("\$numberDecimal", "")
                ?.toFloatOrNull()
                ?.let { (it / 5f * 1000f).toInt().coerceIn(0, 1000) }
            peopleVoted = info.optInt("voteCount", 0).takeIf { it > 0 }
        }
    }

    private const val MAX_SEARCH_PAGES = 10
    private const val MAX_CHAPTER_PAGES = 100
    private const val MAX_SEARCH_RESULTS = 500

    override suspend fun loadHtml(url: String): String? {
        val chapterPath = url.substringBefore("?").substringAfter("$apiBase/", "")
        val parts = chapterPath.split("/")
        if (parts.size >= 4 && parts[0] == "novels" && parts[2] == "chapters") {
            val novelId = parts[1]
            val chapterId = parts[3]
            val json = getJson("$apiBase/novels/$novelId/chapters/$chapterId")
            val chapterInfo = json.optJSONObject("item")?.optJSONObject("chapterInfo")
                ?: json.optJSONObject("chapterInfo")
                ?: json.optJSONObject("data")?.optJSONObject("chapterInfo")
                ?: json.optJSONObject("data")
                ?: json.optJSONObject("item")
            val content = chapterInfo?.firstNonBlank(
                "chapter_content", "chapterContent", "content", "body", "html"
            )?.trim()?.takeIf { it.isNotBlank() }
            return content ?: throw ErrorLoadingException(
                "NovelPing chapter content was empty (novel $novelId, chapter $chapterId)"
            )
        }

        val document = app.get(url).document
        val content = document.selectFirst("#chr-content, #chapter-content, .chr-c")
            ?: throw ErrorLoadingException("NovelPing chapter content container was not found")
        content.select("script, style, iframe, noscript, .js-ad-slot").remove()
        val html = content.html().trim()
        if (html.isBlank()) throw ErrorLoadingException("NovelPing chapter content was empty")
        return html
    }
}
