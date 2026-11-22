package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.Score
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.UserReview
import com.lagradost.quicknovel.fixUrl
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newReview
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import com.lagradost.quicknovel.util.AppUtils.parseJson
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

internal const val DEFAULT_WTR_LAB_AES_KEY = "IJAFUUxjM25hyzL2AZrn0wl7cESED6Ru"

internal fun decodeWtrLabBase64(input: String): ByteArray {
    val clean = input.replace(Regex("""\s+"""), "")
        .replace('-', '+')
        .replace('_', '/')
        .trimEnd('=')
    if (clean.isEmpty()) return ByteArray(0)
    val out = java.io.ByteArrayOutputStream((clean.length * 3) / 4 + 1)
    var buffer = 0
    var bitsLeft = 0
    for (ch in clean) {
        val value = when (ch) {
            in 'A'..'Z' -> ch - 'A'
            in 'a'..'z' -> ch - 'a' + 26
            in '0'..'9' -> ch - '0' + 52
            '+' -> 62
            '/' -> 63
            else -> throw IllegalArgumentException("Invalid Base64 character: $ch")
        }
        buffer = (buffer shl 6) or value
        bitsLeft += 6
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            out.write((buffer ushr bitsLeft) and 0xFF)
        }
    }
    return out.toByteArray()
}

internal fun extractWtrLabAesKeyFromSource(source: String): String? {
    val match = Regex("""new\s+TextEncoder(?:\(\))?\.encode\(\s*["']([^"']{32,})["']\s*\)""")
        .find(source)
        ?: return null
    val key = match.groupValues[1]
    return if (key.toByteArray(Charsets.UTF_8).size >= 32) key.substring(0, 32) else null
}

internal fun extractWtrLabChapterNo(order: Long, url: String): Long {
    if (order > 0L) return order
    return Regex("""(?:chapter-|/)(\d+)(?:[/?#]|$)""")
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
        ?: 1L
}

private val WTR_PLACEHOLDER_TOKEN_REGEX = Regex(
    """%\{(?:"([^"]*)":\s*"?([A-Za-z0-9+/=_-]+)"?|([^|{}%\u0000-\u001F\u007F\u2028\u2029]+?)\s*\|\s*([A-Za-z0-9+/=_-]+))\}"""
)

internal fun resolveWtrLabPlaceholderTokens(text: String): String {
    if (!text.contains("%{")) return text
    return WTR_PLACEHOLDER_TOKEN_REGEX.replace(text) { match ->
        val rawFallback = (match.groupValues[1].ifEmpty { match.groupValues[3] }).trim()
        val rawB64 = (match.groupValues[2].ifEmpty { match.groupValues[4] }).trim()
        val decoded = runCatching {
            String(decodeWtrLabBase64(rawB64), Charsets.UTF_8)
        }.getOrDefault("")
        decoded.ifBlank { rawFallback }.ifBlank { match.value }
    }
}


object WtrLabProvider : MainAPI() {
    override val mainUrl = "https://wtr-lab.com"
    override val name = "WTR-LAB"
    override val lang = "en"
    override val iconId = R.drawable.icon_wtrlab
    override val hasMainPage = true
    override val usesCloudFlareKiller = true
    override val hasReviews = true
    override val mainCategories = listOf(
        "All" to "all",
        "Ongoing" to "ongoing",
        "Completed" to "completed"
    )

    //&orderBy=
    override val orderBys = listOf(
        "Date" to "date",
        "Name" to "name",
        "View" to "view",
        "Reader" to "reader",
        "Chapter" to "chapter"
    )
    override val tags = listOf(
        "All" to "",
        "Action" to "1",
        "Adult" to "2",
        "Adventure" to "3",
        "Anime" to "4",
        "Arts" to "5",
        "Comedy" to "6",
        "Drama" to "7",
        "Eastern" to "8",
        "Ecchi" to "ecchi",
        "Fan-fiction" to "9",
        "Fantasy" to "10",
        "Game" to "11",
        "Gender-bender" to "12",
        "Harem" to "13",
        "Historical" to "14",
        "Horror" to "15",
        "Isekai" to "16",
        "Josei" to "17",
        "Lgbt" to "18",
        "Magic" to "19",
        "Magical-realism" to "20",
        "Manhua" to "21",
        "Martial-arts" to "22",
        "Mature" to "23",
        "Mecha" to "24",
        "Military" to "25",
        "Modern-life" to "26",
        "Movies" to "27",
        "Mystery" to "28",
        "Other" to "29",
        "Psychological" to "30",
        "Realistic-fiction" to "31",
        "Reincarnation" to "32",
        "Romance" to "33",
        "School-life" to "34",
        "Sci-fi" to "35",
        "Seinen" to "36",
        "Shoujo" to "37",
        "Shoujo-ai" to "38",
        "Shounen" to "39",
        "Shounen-ai" to "40",
        "Slice-of-life" to "41",
        "Smut" to "42",
        "Sports" to "43",
        "Supernatural" to "44",
        "System" to "45",
        "Tragedy" to "46",
        "Urban" to "47",
        "Urban-life" to "48",
        "Video-games" to "49",
        "War" to "50",
        "Wuxia" to "51",
        "Xianxia" to "52",
        "Xuanhuan" to "53",
        "Yaoi" to "54",
        "Yuri" to "55"
    )


    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        val safeCat = mainCategory?.takeIf { it.isNotBlank() } ?: "all"
        val safeOrder = orderBy?.takeIf { it.isNotBlank() } ?: "date"
        val safeTag = tag.orEmpty()
        val url =
            "$mainUrl/en/novel-list?page=$page&status=$safeCat&orderBy=$safeOrder&genre=$safeTag"
        val doc = app.get(url).document
        val returnValue = doc.select(".series-list>div").mapNotNull { select ->
            val titleHolder = select.selectFirst("a") ?: return@mapNotNull null
            val href = titleHolder.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = titleHolder.attr("title").trim()
                .ifBlank { select.selectFirst(".title, h3, h4")?.text()?.trim().orEmpty() }
                .ifBlank { titleHolder.text().trim() }
            if (name.isBlank()) return@mapNotNull null
            newSearchResponse(name, href) {
                posterUrl = fixUrlNull(
                    select.selectFirst("a img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("src") }
                    }
                )
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/en/novel-finder?text=$encoded"
        val doc = app.get(url).document
        return doc.select(".series-list>div").mapNotNull { select ->
            val titleHolder = select.selectFirst("a") ?: return@mapNotNull null
            val href = titleHolder.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = titleHolder.attr("title").trim()
                .ifBlank { select.selectFirst(".title, h3, h4")?.text()?.trim().orEmpty() }
                .ifBlank { titleHolder.text().trim() }
            if (name.isBlank()) return@mapNotNull null
            newSearchResponse(name, href) {
                posterUrl = fixUrlNull(
                    select.selectFirst("a img")?.let { img ->
                        img.attr("data-src").ifBlank { img.attr("src") }
                    }
                )
            }
        }
    }


    private suspend fun getChapterRange(
        url: String,
        chaptersJson: ResultJsonResponse.Root,
        start: Long,
        end: Long
    ): List<ChapterData> {
        val chapterDataUrl =
            "$mainUrl/api/chapters/${chaptersJson.props.pageProps.serie.serieData.rawId}?start=$start&end=$end"
        val chaptersDataJson =
            app.get(chapterDataUrl).text
        val chaptersData = parseJson<ResultChaptersJsonResponse.Root>(chaptersDataJson)
        return chaptersData.chapters.map { chapter ->
            val titleText = chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${chapter.order}"
            newChapterData(
                "#${chapter.order} $titleText",
                "${url.trimEnd('/')}/chapter-${chapter.order}"
            ) {
                dateOfRelease = chapter.updatedAt
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: throw ErrorLoadingException("No title")
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
        val json = jsonNode?.data() ?: throw ErrorLoadingException("no chapters")
        val chaptersJson = parseJson<ResultJsonResponse.Root>(json)

        val chapters = mutableListOf<ChapterData>()
        chapters.addAll(
            getChapterRange(
                url,
                chaptersJson,
                1,
                chaptersJson.props.pageProps.serie.serieData.rawChapterCount
            )
        )
        val id = chaptersJson.props.pageProps.serie.serieData.id.toString()
        return newStreamResponse(title, url, chapters) {
            synopsis = doc.selectFirst(".desc-wrap")?.text()
            posterUrl = fixUrlNull(doc.selectFirst(".image-wrap > img")?.attr("src"))
            val details = doc.select("div.detail-buttons div")
            details.forEach { div ->
                if (div.text().contains("Views")) {
                    val text = div.ownText().split(" ")
                    this.views = text.getOrNull(2)?.trim()?.toIntOrNull()
                    setStatus(text.getOrNull(0)?.trim())
                }
            }
            val ratingElement = details.selectFirst("div.rating")
            val ratingText = ratingElement?.text()

            peopleVoted = ratingText?.let { text ->
                Regex("""\((\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            score = ratingText?.let { text ->
                Score.from5(Regex("""([\d.]+)""").find(text)?.groupValues?.getOrNull(1))
            }
            reviewData = id
            related = runCatching { getRelated(url, id) }.getOrDefault(emptyList())
        }
    }

    suspend fun getRelated(url: String, id: String): List<SearchResponse> {
        val apiUrl = "$mainUrl/api/v2/novel/similar/$id"
        val response = app.get(apiUrl).parsedSafe<RelatedResponse>()

        return response?.data?.map { item ->
            val title = item.innerData.title
            val itemId = item.id
            val slug = item.slug

            val href = "$mainUrl/en/serie-$itemId/$slug"

            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(item.innerData.image)
            }
        } ?: emptyList()
    }

    override suspend fun loadReviews(url: String, page: Int, data: String?): List<UserReview> {
        val id = data ?: return emptyList()
        val realUrl =
            "$mainUrl/api/review/get?serie_id=$id&page=${page - 1}&sort=most_liked"
        val res = app.get(realUrl).parsedSafe<ReviewResponse>()
        return res?.data?.mapNotNull { item ->
            val reviewTxt = item.comment ?: return@mapNotNull null

            newReview(reviewTxt) {
                username = item.username
                date = item.createdAt
                score = Score.from5(item.rate)
            }
        } ?: emptyList()
    }

    @Volatile
    private var cachedAesKey: String = DEFAULT_WTR_LAB_AES_KEY

    private suspend fun resolveWtrLabBodyWithService(
        url: String,
        doc: org.jsoup.nodes.Document,
        chapter: LoadJsonResponse.Serie,
        chapterNo: Long,
        service: String,
    ): List<String>? {
        val postRes = app.post(
            url = "$mainUrl/api/reader/get",
            headers = mapOf(
                "Accept" to "application/json",
                "Referer" to url,
            ),
            data = mapOf(
                "chapter_id" to chapter.chapter.id.toString(),
                "chapter_no" to chapterNo.toString(),
                "force_retry" to "false",
                "language" to "en",
                "raw_id" to chapter.serieData.rawId.toString(),
                "retry" to "false",
                "translate" to service,
            ),
        )
        val rawBody = postRes.text
        if (postRes.code == 403 || rawBody.contains("turnstile", ignoreCase = true) ||
            rawBody.contains("require_turnstile", ignoreCase = true)
        ) {
            throw com.lagradost.quicknovel.network.CloudflareChallengeException(
                url = url,
                host = "wtr-lab.com",
                message = "WTR-LAB requires Turnstile verification. Tap 'Verify in browser' to continue reading.",
            )
        }

        val json = runCatching { org.json.JSONObject(rawBody) }.getOrNull() ?: return null
        if (json.optBoolean("require_turnstile", false)) {
            throw com.lagradost.quicknovel.network.CloudflareChallengeException(
                url = url,
                host = "wtr-lab.com",
                message = "WTR-LAB requires Turnstile verification. Tap 'Verify in browser' to continue reading.",
            )
        }

        // Current WTR-LAB API often returns a content_url JSON endpoint instead of inline body
        val contentUrl = json.optString("content_url", "")
            .ifBlank { json.optJSONObject("data")?.optString("content_url", "").orEmpty() }
            .trim()
        val payloadObj = if (contentUrl.isNotBlank() && contentUrl != "null") {
            val contentRes = app.get(fixUrl(contentUrl), headers = mapOf("Referer" to url))
            if (contentRes.code == 403 || contentRes.text.contains("turnstile", ignoreCase = true)) {
                throw com.lagradost.quicknovel.network.CloudflareChallengeException(
                    url = url,
                    host = "wtr-lab.com",
                    message = "WTR-LAB requires Turnstile verification to fetch chapter content.",
                )
            }
            runCatching { org.json.JSONObject(contentRes.text) }.getOrNull() ?: json
        } else {
            json
        }

        val dataObj = payloadObj.optJSONObject("data")
        val innerDataObj = dataObj?.optJSONObject("data")
        val rawBodyField = innerDataObj?.opt("body")
            ?: dataObj?.opt("body")
            ?: payloadObj.opt("body")

        if (rawBodyField is org.json.JSONArray) {
            return (0 until rawBodyField.length()).mapNotNull { i ->
                rawBodyField.optString(i, "").takeIf { it.isNotEmpty() }
            }.takeIf { it.isNotEmpty() }
        }

        val bodyStr = rawBodyField?.toString()?.trim().orEmpty()
        if (bodyStr.isBlank() || bodyStr == "null") return null

        if (bodyStr.startsWith("[")) {
            return runCatching { parseJson<List<String>>(bodyStr) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }

        return runCatching {
            decryptContent(bodyStr, cachedAesKey)
        }.getOrElse { firstError ->
            var refreshedKey: String? = null
            val scriptUrls = doc.select("script[src]")
                .mapNotNull { it.attr("src").trim().takeIf { src -> src.contains("_next/static") } }
                .take(6)
            for (scriptUrl in scriptUrls) {
                val scriptBody = runCatching { app.get(fixUrl(scriptUrl)).text }.getOrNull() ?: continue
                val candidate = extractWtrLabAesKeyFromSource(scriptBody)
                if (candidate != null) {
                    refreshedKey = candidate
                    break
                }
            }
            if (refreshedKey != null) {
                cachedAesKey = refreshedKey
                decryptContent(bodyStr, refreshedKey)
            } else {
                throw firstError
            }
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val doc = app.get(url).document
        doc.select("script:not([src])").forEach { scriptEl ->
            extractWtrLabAesKeyFromSource(scriptEl.html())?.let { cachedAesKey = it }
        }
        val jsonNode = doc.selectFirst("#__NEXT_DATA__")
        val json = jsonNode?.data() ?: throw ErrorLoadingException("Could not find chapter data on WTR-LAB")

        val rootJson = runCatching { org.json.JSONObject(json) }.getOrNull()
        val defaultService = rootJson?.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("serie")
            ?.optString("default_service", "")
            ?.takeIf { it.isNotBlank() && it != "null" }

        val chaptersJson = parseJson<LoadJsonResponse.Root>(json)
        val chapter = chaptersJson.props.pageProps.serie
        val chapterNo = extractWtrLabChapterNo(chapter.chapter.order, url)

        val servicesToTry = listOfNotNull(defaultService, "web", "ai").distinct()
        var lastError: Throwable? = null
        var paragraphs: List<String>? = null

        for (service in servicesToTry) {
            try {
                val candidate = resolveWtrLabBodyWithService(
                    url = url,
                    doc = doc,
                    chapter = chapter,
                    chapterNo = chapterNo,
                    service = service,
                )
                if (!candidate.isNullOrEmpty()) {
                    paragraphs = candidate
                    break
                }
            } catch (e: Throwable) {
                if (com.lagradost.quicknovel.network.CloudflareChallengeManager.findCloudflareChallenge(e) != null) {
                    throw e
                }
                lastError = e
            }
        }

        if (paragraphs != null) {
            val text = StringBuilder()
            for (p in paragraphs) {
                val trimmed = p.trim()
                if (trimmed.isEmpty()) continue
                text.append("<p>")
                text.append(resolveWtrLabPlaceholderTokens(trimmed))
                text.append("</p>")
            }
            val resultHtml = text.toString()
            if (resultHtml.isNotBlank()) return resultHtml
        }

        if (lastError != null) {
            throw lastError
        }
        throw ErrorLoadingException("Could not retrieve WTR-LAB chapter content for $url")
    }

    @JvmOverloads
    fun decryptContent(
        encryptedText: String,
        keyString: String = cachedAesKey,
    ): List<String> {
        val trimmed = encryptedText.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            return parseJson<List<String>>(trimmed)
        }

        var isArray = false
        var rawText = trimmed

        if (trimmed.startsWith("arr:")) {
            isArray = true
            rawText = trimmed.removePrefix("arr:")
        } else if (trimmed.startsWith("str:")) {
            rawText = trimmed.removePrefix("str:")
        }

        val parts = rawText.split(":")
        if (parts.size != 3) throw IllegalArgumentException("Invalid format")

        val ivBytes = decodeWtrLabBase64(parts[0])
        val shortCipher = decodeWtrLabBase64(parts[1])
        val longCipher = decodeWtrLabBase64(parts[2])

        val cipherBytes = ByteArray(longCipher.size + shortCipher.size)
        System.arraycopy(longCipher, 0, cipherBytes, 0, longCipher.size)
        System.arraycopy(shortCipher, 0, cipherBytes, longCipher.size, shortCipher.size)

        val effectiveKey = if (keyString.length >= 32) keyString else DEFAULT_WTR_LAB_AES_KEY
        val keyBytes = effectiveKey.substring(0, 32).toByteArray(Charsets.UTF_8)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, ivBytes)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        val decryptedBytes = cipher.doFinal(cipherBytes)
        val decryptedText = decryptedBytes.toString(Charsets.UTF_8)

        return if (isArray) {
            parseJson<List<String>>(decryptedText)
        } else {
            listOf(decryptedText)
        }
    }

    data class RelatedResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("data") val data: List<RelatedItem>? = null
    )

    data class RelatedItem(
        @JsonProperty("raw_id") val id: Long,
        @JsonProperty("slug") val slug: String,
        @JsonProperty("data") val innerData: RelatedInnerData,
    )

    data class RelatedInnerData(
        @JsonProperty("title") val title: String,
        @JsonProperty("image") val image: String? = null,
    )

    data class ReviewResponse(
        @JsonProperty("success") val success: Boolean? = null,
        @JsonProperty("data") val data: List<ReviewItem>? = null
    )

    data class ReviewItem(
        @JsonProperty("comment") val comment: String? = null,
        @JsonProperty("rate") val rate: Int? = null,
        @JsonProperty("username") val username: String? = null,
        @JsonProperty("created_at") val createdAt: String? = null,
        @JsonProperty("user_id") val userId: String? = null
    )

    object ResultChaptersJsonResponse {
        data class Root(
            val chapters: List<Chapter>,
        )

        data class Chapter(
            @JsonProperty("serie_id")
            val serieId: Long = 0,
            val id: Long = 0,
            val order: Long = 0,
            val title: String? = null,
            val name: String? = null,
            @JsonProperty("updated_at")
            val updatedAt: String? = null,
        )
    }

    object ResultJsonResponse {
        data class Root(
            val props: Props,
            /*val page: String,
            val query: Query,
            val buildId: String,
            val isFallback: Boolean,
            val isExperimentalCompile: Boolean,
            val gssp: Boolean,
            val locale: String,
            val locales: List<String>,
            val defaultLocale: String,
            val scriptLoader: List<Any?>,*/
        )

        data class Props(
            val pageProps: PageProps,
            // @JsonProperty("__N_SSP")
            /// val nSsp: Boolean,
        )

        data class PageProps(
            val serie: Serie,
            /*val tags: List<Tag>,
            @JsonProperty("server_time")
            val serverTime: String,
            @JsonProperty("disabe_ads")
            val disabeAds: Boolean,
            @JsonProperty("_sentryTraceData")
            val sentryTraceData: String,
            @JsonProperty("_sentryBaggage")
            val sentryBaggage: String,*/
        )

        data class Serie(
            @JsonProperty("serie_data")
            val serieData: SerieData,
            /*val ranks: Ranks,
            val recommendation: List<Recommendation>,
            val raws: List<Raw3>,
            val names: List<Name>,
            @JsonProperty("other_series")
            val otherSeries: List<Series>,
            @JsonProperty("last_chapters")
            val lastChapters: List<LastChapter>,*/
            /*@JsonProperty("raw_rank")
            val rawRank: Any?,
            @JsonProperty("released_user")
            val releasedUser: Any?,*/
        )

        data class SerieData(
            @JsonProperty("raw_id")
            val rawId: Long,
            @JsonProperty("id")
            val id: Long,
            /*
            @JsonProperty("search_text")
            val searchText: String,
            val status: Long,
            val data: Data,
            @JsonProperty("created_at")
            val createdAt: String,
            @JsonProperty("updated_at")
            val updatedAt: String,
            val view: Long,
            @JsonProperty("in_library")
            val inLibrary: Long,
            val rating: Any?,
            @JsonProperty("chapter_count")
            val chapterCount: Long,
            val power: Long,
            @JsonProperty("total_rate")
            val totalRate: Long,
            @JsonProperty("user_status")
            val userStatus: Long,
            val verified: Boolean,
            val from: String,
            val author: String,
            @JsonProperty("ai_enabled")
            val aiEnabled: Boolean,
            @JsonProperty("released_by")
            val releasedBy: Any?,
            @JsonProperty("raw_status")
            val rawStatus: Long,*/
            @JsonProperty("raw_chapter_count")
            val rawChapterCount: Long,
            /*
            val genres: List<Long>,
            @JsonProperty("raw_verified")
            val rawVerified: Boolean,
            @JsonProperty("requested_by")
            val requestedBy: String,
            @JsonProperty("requested_by_name")
            val requestedByName: String,
            @JsonProperty("requested_member")
            val requestedMember: String,
            @JsonProperty("requested_role")
            val requestedRole: Long,*/
        )

        /*data class Ranks(
            val week: Any?,
            val month: Any?,
            val all: String,
        )

        data class Recommendation(
            @JsonProperty("serie_id")
            val serieId: Long,
            @JsonProperty("recommendation_id")
            val recommendationId: Long,
            val score: Long,
            val id: Long,
            val slug: String,
            @JsonProperty("search_text")
            val searchText: String,
            val status: Long,
            val data: Data2,
            @JsonProperty("created_at")
            val createdAt: String,
            @JsonProperty("updated_at")
            val updatedAt: String,
            val view: Long,
            @JsonProperty("in_library")
            val inLibrary: Long,
            val rating: Double?,
            @JsonProperty("chapter_count")
            val chapterCount: Long,
            val power: Long,
            @JsonProperty("total_rate")
            val totalRate: Long,
            @JsonProperty("user_status")
            val userStatus: Long,
            val verified: Boolean,
            val from: String?,
            val author: String,
            @JsonProperty("raw_id")
            val rawId: Long,
            @JsonProperty("ai_enabled")
            val aiEnabled: Boolean,
        )

        data class Data2(
            val title: String,
            val author: String,
            val description: String,
            @JsonProperty("from_user")
            val fromUser: String?,
            val raw: Raw2,
            val image: String,
        )

        data class Raw2(
            val title: String,
            val author: String,
            val description: String,
        )

        data class Raw3(
            val id: Long,
            @JsonProperty("chapter_count")
            val chapterCount: Long,
            val view: Long,
            val slug: String,
            @JsonProperty("created_at")
            val createdAt: String,
            val default: Boolean,
            val verified: Boolean,
        )

        data class Name(
            val title: String,
            @JsonProperty("raw_title")
            val rawTitle: String,
        )

        data class Series(
            val id: Long,
            val slug: String,
            @JsonProperty("search_text")
            val searchText: String,
            val status: Long,
            val data: Data3,
            @JsonProperty("created_at")
            val createdAt: String,
            @JsonProperty("updated_at")
            val updatedAt: String,
            val view: Long,
            @JsonProperty("in_library")
            val inLibrary: Long,
            val rating: Double,
            @JsonProperty("chapter_count")
            val chapterCount: Long,
            val power: Long,
            @JsonProperty("total_rate")
            val totalRate: Long,
            @JsonProperty("user_status")
            val userStatus: Long,
            val verified: Boolean,
            val from: String,
            val author: String,
            @JsonProperty("raw_id")
            val rawId: Long,
        )

        data class Data3(
            val title: String,
            val author: String,
            val description: String,
            @JsonProperty("from_user")
            val fromUser: String,
            val raw: Raw4,
            val image: String,
        )

        data class Raw4(
            val title: String,
            val author: String,
            val description: String,
        )

        data class LastChapter(
            //@JsonProperty("serie_id")
            //val serieId: Long,
            //val id: Long,
            val order: Long,
            val title: String,
            //val name: String,
            @JsonProperty("updated_at")
            val updatedAt: String?,
        )

        data class Tag(
            val id: Long,
            val title: String,
            val slug: String,
        )

        data class Query(
            val sid: String,
            @JsonProperty("serie_slug")
            val serieSlug: String,
        )*/
    }

    object LoadJsonResponse2 {

        data class Root(
            // val success: Boolean,
            // val chapter: Chapter,
            val data: Data,
        )

        data class Chapter(
            val id: Long,
            @JsonProperty("raw_id")
            val rawId: Long,
            val order: Long,
            val title: String,
        )

        data class Data(
            /*@JsonProperty("raw_id")
            val rawId: Long,
            @JsonProperty("chapter_id")
            val chapterId: Long,
            val status: Long,
            */
            val data: Data2,
            /*@JsonProperty("created_at")
            val createdAt: String,
            val language: String,*/
        )

        data class Data2(
            val body: String = "",
            /*val hans: String,
            val hash: String,
            val model: String,
            val patch: Any?,
            val title: String,
            val prompt: String,
            @JsonProperty("glossory_hash")
            val glossoryHash: String,
            @JsonProperty("glossary_build")
            val glossaryBuild: Long,*/
        )
    }

    object LoadJsonResponse {
        data class Root(
            val props: Props,
            val page: String = "",
            val query: Query = Query(),
            val buildId: String = "",
            val isFallback: Boolean = false,
            val isExperimentalCompile: Boolean = false,
            val gssp: Boolean = false,
        )

        data class Props(
            val pageProps: PageProps,
        )

        data class PageProps(
            val serie: Serie,
        )

        data class Chapter(
            val id: Long = 0,
            val slug: String? = null,
            val order: Long = 0,
            @JsonProperty("raw_id")
            val rawId: Long = 0,
        )

        data class Serie(
            @JsonProperty("serie_data")
            val serieData: SerieData,
            val chapter: Chapter = Chapter(),
        )

        data class SerieData(
            val id: Long = 0,
            val slug: String = "",
            val data: Data = Data(),
            @JsonProperty("raw_id")
            val rawId: Long = 0,
            @JsonProperty("user_status")
            val userStatus: Long = 0,
            @JsonProperty("is_default")
            val isDefault: Boolean = false,
            @JsonProperty("chapter_count")
            val chapterCount: Long = 0,
            @JsonProperty("ai_enabled")
            val aiEnabled: Boolean = false,
            @JsonProperty("raw_status")
            val rawStatus: Long = 0,
        )

        data class Data(
            val title: String? = null,
            val author: String? = null,
            val description: String? = null,
            @JsonProperty("from_user")
            val fromUser: String? = null,
            val raw: Raw? = null,
            val image: String? = null,
        )

        data class Raw(
            val title: String? = null,
            val author: String? = null,
            val description: String? = null,
        )

        data class Query(
            val locale: String = "en",
            @JsonProperty("serie_slug")
            val serieSlug: String = "",
            @JsonProperty("chapter_no")
            val chapterNo: String = "",
        )
    }
}
