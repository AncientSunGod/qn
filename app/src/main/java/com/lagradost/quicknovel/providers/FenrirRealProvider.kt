package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.network.CloudflareChallengeException
import com.lagradost.quicknovel.network.CloudflareChallengeManager
import com.lagradost.quicknovel.network.isCloudflareChallengeResponse
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newReview
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.util.AppUtils.parseJson
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

private fun isFenrirVerificationHtml(text: String): Boolean {
    val lower = text.lowercase()
    return lower.contains("verifying access") ||
        lower.contains("confirm you're human") ||
        lower.contains("confirm you’re human") ||
        lower.contains("cf-turnstile") ||
        lower.contains("challenges.cloudflare.com") ||
        lower.contains("just a moment")
}

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
    if (isFenrirVerificationHtml(rawHtml)) return null
    val doc = Jsoup.parseBodyFragment(rawHtml)
    doc.select("script, style, iframe, svg, noscript, [aria-hidden=\"true\"], .reader-attribution, [data-fr-attr]")
        .remove()
    val cleaned = doc.body().html()
        .replace(FENRIR_ZERO_WIDTH_REGEX, "")
        .trim()
    return cleaned.takeIf { it.isNotBlank() && !isFenrirVerificationHtml(cleaned) }
}

internal fun parseFenrirApiChapterJson(rawJson: String): String? {
    val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
    val content = obj.optString("content").trim()
    if (content.isBlank()) return null
    val format = obj.optString("content_format").lowercase()
    if (format == "locked") return null
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

internal fun parseFenrirChaptersJson(rawText: String, cleanSeriesUrl: String): List<ChapterData> {
    val trimmed = rawText.trim()
    if (trimmed.isBlank()) return emptyList()
    val items: List<FenrirRealProvider.ChapterInf> = if (trimmed.startsWith("[")) {
        runCatching { parseJson<List<FenrirRealProvider.ChapterInf>>(trimmed) }.getOrDefault(emptyList())
    } else {
        val obj = runCatching { JSONObject(trimmed) }.getOrNull()
        val dataArr = obj?.optJSONArray("data")?.toString()
        if (dataArr != null) {
            runCatching { parseJson<List<FenrirRealProvider.ChapterInf>>(dataArr) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    return items.mapNotNull { ch ->
        if ((ch.locked?.price ?: 0) > 0) return@mapNotNull null
        val slug = ch.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val groupSlug = ch.group?.slug?.trim()?.takeIf { it.isNotEmpty() }
        val displayName = buildString {
            val base = ch.name?.trim()?.takeIf { it.isNotEmpty() }
                ?: ch.number?.let { num ->
                    if (num % 1.0 == 0.0) "Chapter ${num.toLong()}" else "Chapter $num"
                }
            if (base != null) append(base)
            val sub = ch.title?.trim()?.takeIf { it.isNotEmpty() }
            if (sub != null) {
                if (isNotEmpty()) append(" - ")
                append(sub)
            }
        }.ifBlank { slug }

        val pathPart = if (groupSlug != null) "$groupSlug/$slug" else slug
        val apiRef = ch.uuid?.trim()?.takeIf { it.isNotEmpty() } ?: ch.id?.toString()
        val chapterUrl = if (apiRef != null) {
            "$cleanSeriesUrl/$pathPart~~$apiRef"
        } else {
            "$cleanSeriesUrl/$pathPart"
        }
        ChapterData(
            name = displayName,
            url = chapterUrl,
            dateOfRelease = ch.updatedAt?.split("T")?.firstOrNull(),
        )
    }
}

object FenrirRealProvider : MainAPI() {
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
        this.trimEnd('/').substringAfter("/series/").substringBefore("/")

    private fun checkVerificationResponse(statusCode: Int, body: String, url: String) {
        if (statusCode == 403 || isFenrirVerificationHtml(body) || isCloudflareChallengeResponse(
                statusCode = statusCode,
                cfRay = null,
                server = null,
                cfMitigated = null,
                location = null,
                bodySample = body.take(8192),
            )
        ) {
            throw CloudflareChallengeException(
                url = url,
                host = "fenrirealm.com",
                message = "Fenrir Realm requires browser verification. Tap 'Open verification' to complete Turnstile and retry.",
            )
        }
    }

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val safeTag = tag?.takeIf { it.isNotBlank() } ?: "0"
        val safeCat = mainCategory?.takeIf { it.isNotBlank() } ?: "any"
        val safeSort = orderBy?.takeIf { it.isNotBlank() } ?: "popular"
        val url =
            if (safeTag != "0") "$mainUrl/api/new/v2/series?page=$page&per_page=12&status=$safeCat&sort=$safeSort&tags%5B%5D=$safeTag"
            else "$mainUrl/api/new/v2/series?page=$page&per_page=24&status=$safeCat&sort=$safeSort"
        val response = app.get(url)
        checkVerificationResponse(response.code, response.text, "$mainUrl/")
        val document = parseJson<FenrirMainPageResponse>(response.text)
        val returnValue = document.data.mapNotNull { novel ->
            val title = novel.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val slug = novel.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            newSearchResponse(
                name = title,
                url = "$mainUrl/series/$slug"
            ) {
                posterUrl = fixUrlNull(novel.cover)
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    suspend fun getRelated(url: String): List<SearchResponse> {
        val name = url.getSlugFromUrl().takeIf { it.isNotBlank() } ?: return emptyList()
        val relatedUrl = "$mainUrl/api/new/v2/series/$name/recommendations?limit=10"
        val document = runCatching { app.get(relatedUrl).parsedSafe<FenrirMainPageResponse>() }.getOrNull()
            ?: return emptyList()
        return document.data.mapNotNull { element ->
            val title = element.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val slug = element.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val href = "$mainUrl/series/$slug"
            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(element.cover)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("?").substringBefore("#").trimEnd('/')
        val pageRes = app.get(cleanUrl)
        checkVerificationResponse(pageRes.code, pageRes.text, cleanUrl)
        val document = pageRes.document
        document.select("style, iframe, svg, noscript").remove()
        val infoDiv = document.select("div#series-info")
        val seriesSlug = cleanUrl.getSlugFromUrl()
        val chaptersResponse = app.get("$mainUrl/api/new/v2/series/$seriesSlug/chapters")
        checkVerificationResponse(chaptersResponse.code, chaptersResponse.text, cleanUrl)
        val chapters = parseFenrirChaptersJson(chaptersResponse.text, cleanUrl)

        val title = infoDiv.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
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
                    ?: document.selectFirst("meta[property=og:description]")?.attr("content")
            }
            this.posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.let { fixUrlNull(it) }
            related = runCatching { getRelated(cleanUrl) }.getOrDefault(emptyList())
            reviewData = Regex("""seriesData:\s*\{\s*id:\s*(\d+)""").find(script)?.groupValues?.getOrNull(1)
        }
    }

    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val libraryId = data ?: return emptyList()
        val realUrl = "$mainUrl/api/new/v2/comments/series/$libraryId?page=$page&sort=latest"
        val res = runCatching { app.get(realUrl).parsedSafe<CommentsResponse>() }.getOrNull() ?: return emptyList()
        return res.data.mapNotNull { comment ->
            val text = comment.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newReview(text) {
                username = comment.user?.username ?: "User"
                date = comment.createdAt?.split("T")?.firstOrNull()
                avatarUrl = fixUrlNull(comment.user?.avatar)
            }
        }
    }

    override suspend fun loadHtml(url: String): String {
        val chapterRef = Regex("""~~([A-Za-z0-9-]+)$""").find(url)?.groupValues?.getOrNull(1)
        val baseChapterUrl = url.substringBefore("~~").substringBefore("/__data.json")
        var sawChallenge = false

        // 1. Acquire short-lived read token if available so API chapter fetches carry session state
        runCatching {
            app.get("$mainUrl/api/read-token", headers = mapOf("Referer" to baseChapterUrl))
        }

        // 2. Try chapter REST API (/api/new/v2/chapters/{uuid-or-id})
        if (chapterRef != null) {
            val apiRes = runCatching {
                app.get(
                    "$mainUrl/api/new/v2/chapters/$chapterRef",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Referer" to baseChapterUrl,
                    ),
                )
            }.getOrElse { error ->
                if (CloudflareChallengeManager.findCloudflareChallenge(error) != null) throw error
                null
            }
            if (apiRes != null) {
                if (apiRes.code == 403 || isFenrirVerificationHtml(apiRes.text)) {
                    sawChallenge = true
                } else {
                    val parsedObj = runCatching { JSONObject(apiRes.text) }.getOrNull()
                    val format = parsedObj?.optString("content_format").orEmpty().lowercase()
                    if (format == "locked" || apiRes.text.contains("Verifying access", true)) {
                        sawChallenge = true
                    } else {
                        parseFenrirApiChapterJson(apiRes.text)?.let { return it }
                    }
                }
            }
        }

        // 3. Try SvelteKit __data.json
        val dataUrl = if (url.contains("__data.json")) {
            url
        } else {
            "$baseChapterUrl/__data.json?x-sveltekit-invalidated=10001"
        }
        val svelteRes = runCatching {
            app.get(dataUrl, headers = mapOf("Referer" to baseChapterUrl))
        }.getOrElse { error ->
            if (CloudflareChallengeManager.findCloudflareChallenge(error) != null) throw error
            null
        }
        if (svelteRes != null) {
            if (svelteRes.code == 403 || isFenrirVerificationHtml(svelteRes.text)) {
                sawChallenge = true
            } else {
                parseFenrirSvelteKitChapterData(svelteRes.text)?.let { return it }
            }
        }

        // 4. Fallback to rendered HTML chapter page
        val htmlRes = runCatching {
            app.get(baseChapterUrl, headers = mapOf("Referer" to mainUrl))
        }.getOrElse { error ->
            if (CloudflareChallengeManager.findCloudflareChallenge(error) != null) throw error
            null
        }
        if (htmlRes != null) {
            if (htmlRes.code == 403 || isFenrirVerificationHtml(htmlRes.text)) {
                sawChallenge = true
            } else {
                parseFenrirHtmlChapterPage(htmlRes.document)?.let { return it }
            }
        }

        if (sawChallenge) {
            throw CloudflareChallengeException(
                url = baseChapterUrl,
                host = "fenrirealm.com",
                message = "Fenrir Realm requires Turnstile verification to read this chapter. Tap 'Verify in browser' to continue.",
            )
        }

        throw ErrorLoadingException("Could not load Fenrir Realm chapter: $baseChapterUrl")
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/new/v2/series?page=1&per_page=24&search=$encoded"
        val response = app.get(url)
        checkVerificationResponse(response.code, response.text, "$mainUrl/")
        val document = parseJson<FenrirMainPageResponse>(response.text)

        return document.data.mapNotNull { novel ->
            val title = novel.title?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val slug = novel.slug?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val novelUrl = "$mainUrl/series/$slug"
            newSearchResponse(title, novelUrl) {
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
        val title: String? = null,
        @JsonProperty("slug")
        val slug: String? = null,
        @JsonProperty("cover")
        val cover: String? = null,
    )

    data class ChapterGroup(
        @JsonProperty("slug")
        val slug: String? = null,
        @JsonProperty("index")
        val index: Int? = null,
    )

    data class ChapterInf(
        @JsonProperty("id")
        val id: Long? = null,
        @JsonProperty("uuid")
        val uuid: String? = null,
        @JsonProperty("slug")
        val slug: String? = null,
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
        @JsonProperty("group")
        val group: ChapterGroup? = null,
    )

    data class Locked(
        @JsonProperty("price")
        val price: Int? = 0,
    )

    data class CommentsResponse(
        @JsonProperty("data")
        val data: List<CommentData> = emptyList()
    )

    data class CommentData(
        @JsonProperty("content")
        val content: String? = null,
        @JsonProperty("created_at")
        val createdAt: String? = null,
        @JsonProperty("user")
        val user: CommentUser? = null
    )

    data class CommentUser(
        @JsonProperty("username")
        val username: String? = null,
        @JsonProperty("avatar")
        val avatar: String? = null
    )
}