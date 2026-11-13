package com.lagradost.quicknovel.providers

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.quicknovel.ErrorLoadingException
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal data class ParsedFaqWikiNovel(
    val name: String,
    val url: String,
    val posterUrl: String?,
)

internal fun normalizeFaqWikiNovelUrl(url: String, defaultUrl: String = "https://faqwiki.us/novel"): String {
    val canonical = url.trim()
        .replace("http://", "https://")
        .replace("faqwiki.xyz", "faqwiki.us")
    val path = canonical.substringAfter("faqwiki.us/", "")
        .ifBlank { if (canonical.startsWith("/")) canonical else "" }
        .trimStart('/')
    if (path.isBlank()) return defaultUrl
    val novelPath = if (path.startsWith("novel/")) path else "novel/$path"
    return "https://faqwiki.us/$novelPath"
}

internal fun extractFaqWikiImageUrl(img: Element?, siteRoot: String = "https://faqwiki.us"): String? {
    img ?: return null
    val raw = img.attr("data-ezsrc").trim().takeIf { it.isNotBlank() }
        ?.replace(Regex("""\?ezimgfmt=.*$"""), "")
        ?: img.attr("data-src").trim().takeIf { it.isNotBlank() }
        ?: img.attr("src").trim().takeIf { it.isNotBlank() }
        ?: return null
    return when {
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "${siteRoot.trimEnd('/')}$raw"
        else -> "${siteRoot.trimEnd('/')}/$raw"
    }
}

internal fun parseFaqWikiNovels(
    document: Document,
    filterQuery: String? = null,
    siteRoot: String = "https://faqwiki.us",
): List<ParsedFaqWikiNovel> {
    val defaultUrl = "${siteRoot.trimEnd('/')}/novel"
    val seenUrls = HashSet<String>()
    val query = filterQuery?.trim()?.takeIf { it.isNotEmpty() }
    return document.select("div.plt-page-list > div, .plt-page-item").mapNotNull { h ->
        val link = h.selectFirst("a") ?: return@mapNotNull null
        val rawHref = link.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val rawName = h.selectFirst("h3")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: link.text().trim().takeIf { it.isNotEmpty() }
            ?: h.text().trim()
        val name = rawName.replace("Novel – All Chapters", "").trim()
        if (name.isEmpty()) return@mapNotNull null
        if (query != null && !name.contains(query, ignoreCase = true)) return@mapNotNull null
        val normalizedUrl = normalizeFaqWikiNovelUrl(rawHref, defaultUrl)
        if (!seenUrls.add(normalizedUrl)) return@mapNotNull null
        ParsedFaqWikiNovel(
            name = name,
            url = normalizedUrl,
            posterUrl = extractFaqWikiImageUrl(h.selectFirst("img"), siteRoot),
        )
    }
}

class FaqWikiProvider : MainAPI() {
    override val name = "FaqWiki"
    override val mainUrl = "https://faqwiki.us/novel"
    private val siteRoot = "https://faqwiki.us"
    override val iconId = R.drawable.icon_faqwiki
    override val iconBackgroundId = R.color.black
    override val hasMainPage = true

    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse {
        if (page > 1) return HeadMainPageResponse("", emptyList())
        val document = app.get(mainUrl).document

        val returnValue = parseFaqWikiNovels(document, siteRoot = siteRoot).map { novel ->
            newSearchResponse(
                name = novel.name,
                url = novel.url
            ) {
                posterUrl = fixUrlNull(novel.posterUrl)
            }
        }

        return HeadMainPageResponse(mainUrl, returnValue)
    }

    private fun normalizeNovelUrl(url: String): String = normalizeFaqWikiNovelUrl(url, mainUrl)

    override suspend fun load(url: String): LoadResponse {
        val normalizedUrl = normalizeNovelUrl(url)
        val document = app.get(normalizedUrl).document
        val rawTitle = document.selectFirst("h1, .entry-title")?.text()
            ?: throw ErrorLoadingException("Invalid Name")
        val title = rawTitle.replace("Novel – All Chapters", "").trim()

        val chapters = document.select(
            "div.entry-content ul.lcp_catlist li, ul.lcp_catlist li, div.entry-content ul li, article .entry-content ul li, main article ul li, .entry-content li"
        ).mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val name = a.text().trim()
            val href = a.attr("href").trim()
            if (name.isNotEmpty() && href.isNotEmpty())
                newChapterData(name, href)
            else return@mapNotNull null
        }

        return newStreamResponse(title, normalizedUrl, chapters) {
            this.posterUrl = fixUrlNull(
                extractFaqWikiImageUrl(
                    document.selectFirst("figure > img, .wp-block-image img, div.entry-content img"),
                    siteRoot
                )
            )
            val tags = mutableListOf<String>()

            document.select("div.entry-content p").forEach { p ->
                val text = p.text().trim()
                when {
                    text.startsWith("Description:", ignoreCase = true) -> {
                        synopsis = text.substringAfter(":").trim()
                    }
                    text.startsWith("Author(s):", ignoreCase = true) -> {
                        author = text.substringAfter(":").trim()
                    }
                    text.startsWith("Genre:", ignoreCase = true) -> {
                        text.substringAfter(":")
                            .split(" ")
                            .filter { it.isNotBlank() }
                            .map { it.trim().removeSuffix(",") }
                            .forEach { tags.add(it) }
                    }
                    text.startsWith("Status:", ignoreCase = true) -> {
                        setStatus(text.substringAfter(":").trim())
                    }
                }
            }
            this.tags = tags
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val document = app.get(url).document
        val content = document.selectFirst("main article .entry-content")
            ?: document.selectFirst("article .entry-content")
            ?: document.selectFirst("main > article > div")
            ?: document.selectFirst("main article")
            ?: document.selectFirst("article")
            ?: document.body()
        content.select("script, style, iframe, noscript, nav, .sharedaddy, .jp-relatedposts").remove()
        return content.html().trim().takeIf { it.isNotBlank() }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val ajaxResults = runCatching {
            val searchUrl = "$siteRoot/wp-admin/admin-ajax.php"
            val response = app.post(
                searchUrl,
                headers = mapOf("Referer" to mainUrl),
                data = mapOf(
                    "action" to "ajaxsearchlite_search",
                    "aslp" to query,
                    "asid" to "1",
                    "options" to "customset[]=page&asl_gen[]=excerpt&asl_gen[]=content&asl_gen[]=title&qtranslate_lang=0&filters_initial=1&filters_changed=0",
                    "asl_req_json" to "1"
                )
            ).parsed<SearchResult>()

            response.html?.let { html ->
                Jsoup.parse(html).select("div.item").mapNotNull { h ->
                    val name = h.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                    val href = h.selectFirst("a")?.attr("href")?.trim() ?: return@mapNotNull null
                    newSearchResponse(
                        name = name,
                        url = normalizeNovelUrl(href)
                    ) {
                        posterUrl = fixUrlNull(extractFaqWikiImageUrl(h.selectFirst("img"), siteRoot))
                    }
                }
            } ?: emptyList()
        }.getOrDefault(emptyList())

        if (ajaxResults.isNotEmpty()) return ajaxResults

        val document = app.get(mainUrl).document
        return parseFaqWikiNovels(document, filterQuery = query, siteRoot = siteRoot).map { novel ->
            newSearchResponse(name = novel.name, url = novel.url) {
                posterUrl = fixUrlNull(novel.posterUrl)
            }
        }
    }

    data class SearchResult(
        @JsonProperty("html") val html: String?,
    )
}