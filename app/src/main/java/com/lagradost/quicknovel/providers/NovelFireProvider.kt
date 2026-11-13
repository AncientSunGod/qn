package com.lagradost.quicknovel.providers

import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrl
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newReview
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

internal data class ParsedNovelFireChapter(
    val name: String,
    val url: String,
)

internal fun extractNovelFirePostId(document: Document): String? {
    document.selectFirst("a#novel-report")?.attr("report-post_id")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    val scriptText = document.select("script").joinToString("\n") { it.html() }
    return Regex("""(?:report-post_id|post_id)\s*[:=]\s*["']?(\d+)["']?""")
        .find(scriptText)
        ?.groupValues
        ?.getOrNull(1)
        ?.takeIf { it.isNotEmpty() }
}

internal fun parseNovelFireArchiveChapters(
    document: Document,
    mainUrl: String = "https://novelfire.net",
): List<ParsedNovelFireChapter> {
    val root = mainUrl.trimEnd('/')
    return document.select(".chapter-list li").mapNotNull { element ->
        val link = element.selectFirst("a[href]") ?: return@mapNotNull null
        val rawHref = link.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val fullUrl = when {
            rawHref.startsWith("http://") || rawHref.startsWith("https://") -> rawHref
            rawHref.startsWith("/") -> "$root$rawHref"
            else -> "$root/$rawHref"
        }
        val title = link.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: link.text().trim().takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        ParsedNovelFireChapter(title, fullUrl)
    }
}

internal fun extractNovelFireRedirectUrl(document: Document): String? {
    if (document.selectFirst("div#content") != null) return null
    val scriptText = document.select("script").joinToString("\n") { it.html() }
    return Regex("""window\.location\.href\s*=\s*["']([^"']+)["']""")
        .find(scriptText)
        ?.groupValues
        ?.getOrNull(1)
        ?.replace("\\/", "/")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun parseNovelFireChapterHtml(
    document: Document,
    normalize: (String) -> String = { text ->
        text.lowercase()
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9]"), "")
    },
): String? {
    var title = document.selectFirst("span.chapter-title")?.text().orEmpty()
    val contentElement = document.selectFirst("div#content") ?: return null
    contentElement.selectFirst("p")?.let { p ->
        if (title.isNotBlank() && normalize(p.text()) == normalize(title)) {
            title = ""
        }
    }
    contentElement.select("img[src*=disable-blocker.jpg]").forEach { it.remove() }
    contentElement.select("*").forEach { element ->
        val tag = element.tagName()
        if (tag.length > 5 && tag.startsWith("nf", ignoreCase = true)) {
            element.remove()
        }
    }
    val bodyHtml = contentElement.html().replace("&nbsp;", " ").trim()
    if (bodyHtml.isEmpty()) return null
    return if (title.isEmpty()) bodyHtml else "<p>$title</p><br>$bodyHtml"
}

open class NovelFireProvider : MainAPI() {
    override val name = "NovelFire"
    override val mainUrl = "https://novelfire.net"
    override val iconId = R.drawable.icon_novelfire
    override val rateLimitTime = 500L
    override val hasMainPage = true
    override val hasReviews = true
    //novel phoenix uses novel instead of book
    open val sectionUrl = "book"
    override val mainCategories = listOf(
        "All" to "status-all",
        "Completed" to "status-completed",
        "Ongoing" to "status-ongoing"
    )
    override val orderBys =
        listOf("Popular" to "sort-popular", "New" to "sort-new", "Updates" to "sort-latest-release")

    override val tags = listOf(
        "All" to "all",
        "Action" to "action",
        "Adventure" to "adventure",
        "Anime" to "anime",
        "Arts" to "arts",
        "Comedy" to "comedy",
        "Drama" to "drama",
        "Eastern" to "eastern",
        "Fan-fiction" to "fan-fiction",
        "Fantasy" to "fantasy",
        "Game" to "game",
        "Gender-bender" to "gender-bender",
        "Harem" to "harem",
        "Historical" to "historical",
        "Horror" to "horror",
        "Isekai" to "isekai",
        "Josei" to "josei",
        "Lgbt" to "lgbt",
        "Magic" to "magic",
        "Magical-realism" to "magical-realism",
        "Manhua" to "manhua",
        "Martial-arts" to "martial-arts",
        "Mecha" to "mecha",
        "Military" to "military",
        "Modern-life" to "modern-life",
        "Movies" to "movies",
        "Mystery" to "mystery",
        "Other" to "other",
        "Psychological" to "psychological",
        "Realistic-fiction" to "realistic-fiction",
        "Reincarnation" to "reincarnation",
        "Romance" to "romance",
        "School-life" to "school-life",
        "Sci-fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shoujo-ai" to "shoujo-ai",
        "Shounen" to "shounen",
        "Shounen-ai" to "shounen-ai",
        "Slice-of-life" to "slice-of-life",
        "Sports" to "sports",
        "Supernatural" to "supernatural",
        "System" to "system",
        "Tragedy" to "tragedy",
        "Urban" to "urban",
        "Urban-life" to "urban-life",
        "Video-games" to "video-games",
        "War" to "war",
        "Wuxia" to "wuxia",
        "Xianxia" to "xianxia",
        "Xuanhuan" to "xuanhuan",
        "Yaoi" to "yaoi",
        "Yuri" to "yuri"
    )

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val url = "$mainUrl/genre-${tag}/${orderBy}/${mainCategory}/all-novel?page=$page"
        val document = app.get(url).document


        val returnValue = document.select("li.novel-item").mapNotNull { select ->
            val node = select.selectFirst("a[title]") ?: return@mapNotNull null
            val href = node.attr("href")
            val title =
                node.attr("title").takeIf { !it.isEmpty() } ?: node.selectFirst("h4.novel-title")
                    ?.text() ?: return@mapNotNull null

            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(
                    select.selectFirst("img")?.attr("data-src")
                        ?: select.selectFirst("img")?.attr("src")
                )
            }

        }
        return HeadMainPageResponse(url, returnValue)
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val infoDiv = document.select("div.novel-info")

        val title = infoDiv.selectFirst("h1.novel-title")?.text()
            ?: document.selectFirst("h1.novel-title, h1")?.text()
            ?: throw ErrorLoadingException("Title not found")
        val id = extractNovelFirePostId(document)
        val chapters = getChapters(url, id)

        return newStreamResponse(title, fixUrl(url), chapters) {
            this.author = infoDiv.selectFirst("div.author > a, .author .property-item > span")?.text()
            this.posterUrl = fixUrlNull(
                document.selectFirst("figure.cover img, .cover img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }
            )
            this.synopsis =
                document.selectFirst("meta[itemprop=description]")?.attr("content")
                    ?: document.selectFirst("div.summary > div, .summary .content")?.html() ?: ""

            this.tags = infoDiv.select("div.categories ul li").mapNotNull {
                it.text().trim().takeIf { text -> text.isNotEmpty() }
            }

            infoDiv.select("div.header-stats span").forEach { span ->
                if (span.text().contains("Status")) {
                    setStatus(span.selectFirst("strong")?.text())
                } else if (span.text().contains("Views")) {
                    this.views = span.selectFirst("strong")?.ownText()?.trim()?.let {
                        if (it.contains("k", true)) it.replace("k", "", true).toFloatOrNull()
                            ?.times(1000)?.roundToInt()
                        else if (it.contains("m", true)) it.replace("m", "", true).toFloatOrNull()
                            ?.times(1000000)?.roundToInt()
                        else it.toIntOrNull()
                    }
                }
            }

            this.peopleVoted =
                document.selectFirst("div.novel-body.container a.grdbtn.reviews-latest-container p.latest.text1row")
                    ?.text()
                    ?.replace(Regex("[^0-9]"), "")
                    ?.trim()
                    ?.toIntOrNull() ?: 0
            this.rating = document.selectFirst("div.rating strong.nub")?.text()
                ?.toFloatOrNull()?.times(20)?.times(10)?.roundToInt()
            related = id?.let { runCatching { getRelated(it) }.getOrDefault(emptyList()) } ?: emptyList()
            reviewData = id
        }
    }

    open suspend fun getChapters(url: String, id: String?): List<ChapterData> {
        val bookSlug = url.trimEnd('/').substringAfterLast("/")
        if (!id.isNullOrBlank()) {
            val ajaxChapters = runCatching {
                val ajaxUrl = "$mainUrl/ajax/listChapterDataAjax"
                val response = app.get(
                    ajaxUrl, params = mapOf(
                        "draw" to "1",
                        "columns[0][data]" to "n_sort",
                        "columns[0][name]" to "cmm_posts_detail.n_sort",
                        "columns[0][searchable]" to "true",
                        "columns[0][orderable]" to "true",
                        "columns[0][search][value]" to "",
                        "columns[0][search][regex]" to "false",
                        "columns[1][data]" to "bookmark_created_at",
                        "columns[1][name]" to "bookmark_chapters.created_at",
                        "columns[1][searchable]" to "false",
                        "columns[1][orderable]" to "true",
                        "columns[1][search][value]" to "",
                        "columns[1][search][regex]" to "false",
                        "order[0][column]" to "0",
                        "order[0][dir]" to "asc",
                        "order[0][name]" to "cmm_posts_detail.n_sort",
                        "start" to "0",
                        "length" to "-1",
                        "search[value]" to "",
                        "search[regex]" to "false",
                        "post_id" to id,
                        "only_bookmark" to "false",
                        // Avoid serving a cached, stale DataTables response.
                        "_" to System.currentTimeMillis().toString()
                    )
                ).parsed<AjaxChapterRoot>()

                response.data?.mapNotNull { item ->
                    val rawSort = item.nSort?.trim() ?: return@mapNotNull null
                    val chapterNumber = rawSort.toDoubleOrNull() ?: return@mapNotNull null
                    val chapterSlug = if (chapterNumber % 1.0 == 0.0) {
                        chapterNumber.toLong().toString()
                    } else {
                        rawSort
                    }
                    val rawTitle = item.title?.takeIf { it.isNotBlank() } ?: "Chapter $chapterSlug"
                    val chapterUrl = "$mainUrl/$sectionUrl/$bookSlug/chapter-$chapterSlug"
                    newChapterData(rawTitle, chapterUrl) {
                        this.dateOfRelease = item.createdAt
                    }
                } ?: emptyList()
            }.getOrDefault(emptyList())

            if (ajaxChapters.isNotEmpty()) return ajaxChapters
        }

        // Fall back to the novel's paginated HTML chapter archive so novels never show 0 chapters
        // even when #novel-report or DataTables AJAX is unavailable.
        val allChapters = mutableListOf<ChapterData>()
        val seenUrls = HashSet<String>()
        var page = 1
        var maxPage = 1
        while (page <= maxPage && page <= 50) {
            val archiveUrl = "$mainUrl/$sectionUrl/$bookSlug/chapters?page=$page"
            val archiveDocument = app.get(archiveUrl).document
            if (page == 1) {
                val discoveredMax = archiveDocument.select("ul.pagination a[href*=page=], .pagination a[href*=page=]")
                    .mapNotNull { Regex("""page=(\d+)""").find(it.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    .maxOrNull() ?: 1
                maxPage = discoveredMax.coerceIn(1, 50)
            }
            val parsed = parseNovelFireArchiveChapters(archiveDocument, mainUrl)
            val newItems = parsed.filter { seenUrls.add(it.url) }.map { newChapterData(it.name, it.url) }
            if (newItems.isEmpty()) break
            allChapters.addAll(newItems)
            page++
        }
        return allChapters
    }

    suspend fun getRelated(id: String): List<SearchResponse> {
        val url = "$mainUrl/ajax/novelYouMayLike?post_id=$id"
        val document = app.get(url).parsed<RelatedResponse>()
        return Jsoup.parse(document.html).select("li.novel-item").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("h5")?.text() ?: return@mapNotNull null
            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(
                    element.selectFirst("img")?.attr("data-src")
                        ?: element.selectFirst("img")?.attr("src")
                )
            }
        }
    }


    companion object {
        /** Dirty, but otherwise we have to update the mainapi too much */
        private val reviewCursor: ConcurrentHashMap<Pair<String, Int>, String> = ConcurrentHashMap()
    }

    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val id = data ?: return emptyList()
        val cursor = if (page == 1) {
            ""
        } else {
            reviewCursor[id to page] ?: return emptyList()
        }

        val realUrl = "$mainUrl/comment/show?post_id=$id&chapter_id=&order_by=newest&cursor=$cursor"
        val res = app.get(realUrl).parsed<PostsResponse>()

        if (res.nextCursor.isNotBlank())
            reviewCursor[id to (page + 1)] = res.nextCursor

        val reviews = Jsoup.parse(res.html).select("li:has(.comment-item)")

        return reviews.mapNotNull { r ->
            val header = r.selectFirst("div.comment-header")
            val body = r.selectFirst("div.comment-body")

            val reviewContent = body?.selectFirst(".comment-text")
            val isSpoiler = reviewContent?.attr("data-spoiler") == "1"
            val reviewTxt = reviewContent?.html()

            newReview(reviewTxt ?: return@mapNotNull null) {
                username = header?.selectFirst(".username")?.text()
                date = header?.selectFirst(".post-date")?.text() // Ej: "11h", "1d"
                avatarUrl = fixUrlNull(header?.selectFirst("img.avatar")?.attr("src"))
                containsSpoilers = isSpoiler
            }
        }
    }

    fun normalize(text: String): String {
        return text
            .lowercase()
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9]"), "")
    }

    override suspend fun loadHtml(url: String): String? {
        var document = app.get(url).document
        extractNovelFireRedirectUrl(document)?.let { redirectUrl ->
            document = app.get(fixUrl(redirectUrl)).document
        }
        return parseNovelFireChapterHtml(document, ::normalize)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?keyword=${Uri.encode(query.trim()).replace("%20", "+")}&page=1"
        val document = app.get(url).document

        return document.select("ul.novel-list.chapters li.novel-item, ul.novel-list li.novel-item, li.novel-item")
            .mapNotNull { element ->
                val a = element.selectFirst("a") ?: return@mapNotNull null
                val title = a.attr("title").trim()
                    .ifBlank { element.selectFirst("h4.novel-title, h4, .novel-title")?.text()?.trim().orEmpty() }
                if (title.isBlank()) return@mapNotNull null
                val novelUrl = a.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val coverUrl = fixUrlNull(
                    element.selectFirst("img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("src") }
                    }
                )
                newSearchResponse(title, novelUrl) {
                    posterUrl = coverUrl
                }
            }
    }

    data class RelatedResponse(
        @JsonProperty("html")
        val html: String
    )

    data class PostsResponse(
        @JsonProperty("has_more_pages")
        val hasMore: Boolean,
        @JsonProperty("html")
        val html: String,
        @JsonProperty("next_cursor")
        val nextCursor: String,
    )

    data class AjaxChapterRoot(
        @JsonProperty("data") val data: List<AjaxChapterItem>? = null
    )

    data class AjaxChapterItem(
        @JsonProperty("n_sort") val nSort: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("bookmark_created_at") val createdAt: String? = null
    )
}