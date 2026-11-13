package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newReview
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

private val FENRIR_ZERO_WIDTH_REGEX = Regex("""[\u200B-\u200D\uFEFF]""")

private fun escapeHtmlText(text: String): String =
    text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

private fun escapeHtmlAttr(text: String): String =
    escapeHtmlText(text).replace("'", "&#39;")

private fun renderFenrirTipTapNode(node: JSONObject): String {
    val type = node.optString("type")
    if (type == "text") {
        var text = escapeHtmlText(
            node.optString("text").replace(FENRIR_ZERO_WIDTH_REGEX, "")
        )
        val marks = node.optJSONArray("marks") ?: return text
        for (i in 0 until marks.length()) {
            val mark = marks.optJSONObject(i) ?: continue
            when (mark.optString("type")) {
                "bold" -> text = "<b>$text</b>"
                "italic" -> text = "<i>$text</i>"
                "underline" -> text = "<u>$text</u>"
                "strike" -> text = "<s>$text</s>"
                "link" -> {
                    val href = mark.optJSONObject("attrs")?.optString("href").orEmpty()
                    if (href.startsWith("http://") || href.startsWith("https://") || href.startsWith("/")) {
                        text = """<a href="${escapeHtmlAttr(href)}">$text</a>"""
                    }
                }
            }
        }
        return text
    }

    val childrenHtml = buildString {
        val content = node.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val child = content.optJSONObject(i) ?: continue
                append(renderFenrirTipTapNode(child))
            }
        }
    }

    return when (type) {
        "doc" -> childrenHtml
        "paragraph" -> "<p>$childrenHtml</p>"
        "heading" -> {
            val level = node.optJSONObject("attrs")?.optInt("level", 2)?.coerceIn(1, 6) ?: 2
            "<h$level>$childrenHtml</h$level>"
        }
        "blockquote" -> "<blockquote>$childrenHtml</blockquote>"
        "bulletList" -> "<ul>$childrenHtml</ul>"
        "orderedList" -> "<ol>$childrenHtml</ol>"
        "listItem" -> "<li>$childrenHtml</li>"
        "horizontalRule" -> "<hr>"
        "hardBreak" -> "<br>"
        "systemWindow" -> """<div class="system-window">$childrenHtml</div>"""
        else -> childrenHtml
    }
}

internal fun renderFenrirTipTapDocToHtml(raw: String): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return null
    val doc = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
    if (doc.optString("type") != "doc") return null
    val html = renderFenrirTipTapNode(doc).trim()
    return html.takeIf { it.isNotBlank() }
}

internal fun sanitizeFenrirChapterHtml(rawHtml: String): String? {
    if (rawHtml.isBlank()) return null
    val doc = Jsoup.parseBodyFragment(rawHtml)
    doc.select("script, style, iframe, svg, noscript, [aria-hidden=\"true\"], .reader-attribution, [data-fr-attr]")
        .remove()
    val cleaned = doc.body().html()
        .replace(FENRIR_ZERO_WIDTH_REGEX, "")
        .trim()
    return cleaned.takeIf { it.isNotBlank() }
}

internal fun parseFenrirApiChapterJson(rawJson: String): String? {
    val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
    val content = obj.optString("content").trim()
    if (content.isBlank()) return null
    val format = obj.optString("content_format").lowercase()
    return when {
        format == "tiptap" || content.startsWith("{") -> {
            renderFenrirTipTapDocToHtml(content) ?: sanitizeFenrirChapterHtml(content)
        }
        else -> sanitizeFenrirChapterHtml(content)
    }
}

internal fun parseFenrirSvelteKitChapterData(rawJson: String): String? {
    val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
    val nodes = root.optJSONArray("nodes") ?: return null
    val dataCandidates = mutableListOf<String>()

    for (nodeIndex in nodes.length() - 1 downTo 0) {
        val node = nodes.optJSONObject(nodeIndex) ?: continue
        if (node.optString("type") != "data") continue
        val dataArray = node.optJSONArray("data") ?: continue
        for (i in 0 until dataArray.length()) {
            val item = dataArray.opt(i) as? String ?: continue
            val trimmed = item.trim()
            if (trimmed.length < 16) continue
            if (trimmed.startsWith("{") && trimmed.contains("\"type\"") && trimmed.contains("\"doc\"")) {
                renderFenrirTipTapDocToHtml(trimmed)?.let { return it }
            }
            if (trimmed.contains("<p", ignoreCase = true) || trimmed.contains("<br", ignoreCase = true)) {
                dataCandidates += trimmed
            }
        }
    }

    for (candidate in dataCandidates) {
        sanitizeFenrirChapterHtml(candidate)?.let { return it }
    }
    return null
}

internal fun parseFenrirHtmlChapterPage(document: Document): String? {
    val container = document.selectFirst("div.reader-area, [id^=reader-area], div.content-area, article")
        ?: return null
    return sanitizeFenrirChapterHtml(container.html())
}

class FenrirRealProvider : MainAPI() {
    override val name = "FenrirRealm"
    override val mainUrl = "https://fenrirealm.com"
    override val iconId = R.drawable.icon_fenrirealm
    override val hasMainPage = true

    override val rateLimitTime = 500L
    override val hasReviews = true
    override val usesCloudFlareKiller = true

    override val mainCategories = listOf(
        "All" to "any",
        "Completed" to "completed",
        "Ongoing" to "on-going"
    )
    override val orderBys =
        listOf(
            "Popular" to "popular",
            "Latest" to "latest",
            "Updated" to "updated"
        )
    override val tags = listOf(
        "All" to "0",
        "Action" to "1",
        "Adult" to "2",
        "Adventure" to "3",
        "Comedy" to "4",
        "Drama" to "5",
        "Ecchi" to "6",
        "Fantasy" to "7",
        "Gender Bender" to "8",
        "Harem" to "9",
        "Historical" to "10",
        "Horror" to "11",
        "Josei" to "12",
        "Martial Arts" to "13",
        "Mature" to "14",
        "Mecha" to "15",
        "Mystery" to "16",
        "Psychological" to "17",
        "Romance" to "18",
        "School Life" to "19",
        "Sci-fi" to "20",
        "Seinen" to "21",
        "Shoujo" to "22",
        "Shoujo Ai" to "23",
        "Shounen" to "24",
        "Shounen Ai" to "25",
        "Slice of Life" to "26",
        "Smut" to "27",
        "Sports" to "28",
        "Supernatural" to "29",
        "Tragedy" to "30",
        "Wuxia" to "31",
        "Xianxia" to "32",
        "Xuanhuan" to "33",
        "Yaoi" to "34",
        "Yuri" to "35",
    )

    fun String.getSlugFromUrl(): String =
        this.trimEnd('/').substringAfter("/series/")

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val safeTag = tag ?: "0"
        val safeCat = mainCategory ?: "any"
        val safeSort = orderBy ?: "popular"
        val url =
            if (safeTag != "0") "$mainUrl/api/new/v2/series?page=$page&per_page=12&status=$safeCat&sort=$safeSort&tags%5B%5D=$safeTag"
            else "$mainUrl/api/new/v2/series?page=$page&per_page=24&status=$safeCat&sort=$safeSort"
        val document = app.get(url).parsed<FenrirMainPageResponse>()
        val returnValue = document.data.map { novel ->
            newSearchResponse(
                name = novel.title,
                url = "$mainUrl/series/${novel.slug}"
            ) {
                posterUrl = fixUrlNull(novel.cover)
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    suspend fun getRelated(url: String): List<SearchResponse> {
        val name = url.trimEnd('/').substringAfter("/series/")
        val relatedUrl = "$mainUrl/api/new/v2/series/$name/recommendations?limit=10"
        val document = runCatching { app.get(relatedUrl).parsed<FenrirMainPageResponse>() }.getOrNull()
            ?: return emptyList()
        return document.data.map { element ->
            val href = "$mainUrl/series/${element.slug}"
            newSearchResponse(
                name = element.title,
                url = href
            ) {
                posterUrl = fixUrlNull(element.cover)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.trimEnd('/')
        val document = app.get(cleanUrl).document
        document.select("style, iframe, svg, noscript").remove()
        val infoDiv = document.select("div#series-info")
        val seriesSlug = cleanUrl.getSlugFromUrl()
        val chapters = app
            .get("$mainUrl/api/new/v2/series/$seriesSlug/chapters")
            .parsed<Array<ChapterInf>>()
            .mapNotNull { ch ->
                if ((ch.locked?.price ?: 0) > 0) null
                else {
                    val displayName = buildString {
                        val base = ch.name?.trim()?.takeIf { it.isNotEmpty() }
                            ?: ch.number?.let { "Chapter $it" }
                        if (base != null) append(base)
                        val sub = ch.title?.trim()?.takeIf { it.isNotEmpty() }
                        if (sub != null) {
                            if (isNotEmpty()) append(" - ")
                            append(sub)
                        }
                    }.ifBlank { ch.slug }
                    val chapterUrl = if (ch.id != null) {
                        "$cleanUrl/${ch.slug}~~${ch.id}"
                    } else {
                        "$cleanUrl/${ch.slug}/__data.json?x-sveltekit-invalidated=10001"
                    }
                    newChapterData(displayName, chapterUrl) {
                        dateOfRelease = ch.updatedAt?.split("T")?.firstOrNull()
                    }
                }
            }

        val title = infoDiv.selectFirst("h1")?.text()
            ?: document.selectFirst("h1")?.text()
            ?: throw ErrorLoadingException("Title not found")
        val script =
            document.select("script").find { it.html().contains("seriesData") }?.html() ?: ""

        return newStreamResponse(title, cleanUrl, chapters) {
            infoDiv.select(" > div").forEachIndexed { index, inf ->
                when (index) {
                    1 -> setStatus(inf.selectFirst("span")?.text())
                    2 -> this.author = inf.selectFirst("a")?.text()
                    3 -> {
                        this.tags = infoDiv.select("a").mapNotNull {
                            it.text().trim().takeIf { text -> text.isNotEmpty() }
                        }
                    }
                }
            }
            if (this.synopsis.isNullOrBlank()) {
                this.synopsis = document.selectFirst("div.synopsis")?.text()
            }
            this.posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            related = getRelated(cleanUrl)
            reviewData = Regex("""seriesData:\{id:(\d+)""").find(script)?.groupValues?.get(1)
        }
    }

    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val libraryId = data ?: return emptyList()
        val realUrl = "$mainUrl/api/new/v2/comments/series/$libraryId?page=$page&sort=latest"
        val res = app.get(realUrl).parsed<CommentsResponse>()
        return res.data.map { comment ->
            newReview(comment.content) {
                username = comment.user.username
                date = comment.createdAt.split("T").firstOrNull()
                avatarUrl = comment.user.avatar
            }
        }
    }

    override suspend fun loadHtml(url: String): String {
        val chapterId = Regex("""~~(\d+)$""").find(url)?.groupValues?.getOrNull(1)
        val baseChapterUrl = url.substringBefore("~~").substringBefore("/__data.json")

        if (chapterId != null) {
            val apiJson = runCatching {
                app.get(
                    "$mainUrl/api/new/v2/chapters/$chapterId",
                    headers = mapOf("Accept" to "application/json")
                ).text
            }.getOrNull()
            if (apiJson != null) {
                parseFenrirApiChapterJson(apiJson)?.let { return it }
            }
        }

        val dataUrl = if (url.contains("__data.json")) {
            url
        } else {
            "$baseChapterUrl/__data.json?x-sveltekit-invalidated=10001"
        }
        val svelteJson = runCatching { app.get(dataUrl).text }.getOrNull()
        if (svelteJson != null) {
            parseFenrirSvelteKitChapterData(svelteJson)?.let { return it }
        }

        val htmlDoc = runCatching { app.get(baseChapterUrl).document }.getOrNull()
        if (htmlDoc != null) {
            parseFenrirHtmlChapterPage(htmlDoc)?.let { return it }
        }

        throw ErrorLoadingException("Cant' access to chapter: $url")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/new/v2/series?page=1&per_page=10&search=$encoded"
        val document = app.get(url).parsed<FenrirMainPageResponse>()

        return document.data.map { novel ->
            val novelUrl = "$mainUrl/series/${novel.slug}"
            newSearchResponse(novel.title, novelUrl) {
                posterUrl = fixUrlNull(novel.cover)
            }
        }
    }

    data class FenrirMainPageResponse(
        @JsonProperty("data")
        val data: List<Daum> = emptyList(),
    )

    data class Daum(
        @JsonProperty("title")
        val title: String,
        @JsonProperty("slug")
        val slug: String,
        @JsonProperty("cover")
        val cover: String? = null,
    )

    data class ChapterInf(
        @JsonProperty("id")
        val id: Long? = null,
        @JsonProperty("slug")
        val slug: String,
        @JsonProperty("number")
        val number: Double? = null,
        @JsonProperty("name")
        val name: String? = null,
        @JsonProperty("title")
        val title: String? = null,
        @JsonProperty("updated_at")
        val updatedAt: String? = null,
        @JsonProperty("locked")
        val locked: Locked? = null,
    )

    data class Locked(
        @JsonProperty("price")
        val price: Int = 0,
    )

    data class CommentsResponse(
        @JsonProperty("data")
        val data: List<CommentData> = emptyList()
    )

    data class CommentData(
        @JsonProperty("content")
        val content: String,
        @JsonProperty("created_at")
        val createdAt: String,
        @JsonProperty("user")
        val user: CommentUser
    )

    data class CommentUser(
        @JsonProperty("username")
        val username: String,
        @JsonProperty("avatar")
        val avatar: String?
    )
}