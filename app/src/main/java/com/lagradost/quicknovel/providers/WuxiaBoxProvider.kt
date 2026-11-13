package com.lagradost.quicknovel.providers


import com.lagradost.quicknovel.ChapterData
import com.lagradost.quicknovel.HeadMainPageResponse
import com.lagradost.quicknovel.LoadResponse
import com.lagradost.quicknovel.MainAPI
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.SearchResponse
import com.lagradost.quicknovel.fixUrl
import com.lagradost.quicknovel.fixUrlNull
import com.lagradost.quicknovel.newChapterData
import com.lagradost.quicknovel.newSearchResponse
import com.lagradost.quicknovel.newStreamResponse
import com.lagradost.quicknovel.setStatus
import org.jsoup.nodes.Document

internal fun extractWuxiaBoxSearchId(url: String): String? =
    Regex("""searchid=(\d+)""").find(url)?.groupValues?.getOrNull(1)

open class WuxiaBoxProvider : MainAPI() {
    companion object {
        const val MAX_CHAPTER_PAGES = 100
        const val MAX_SEARCH_PAGES = 20
    }

    override val name = "WuxiaBox"
    override val mainUrl = "https://www.wuxiabox.com"
    override val iconId = R.drawable.icon_wuxiabox
    override val usesCloudFlareKiller = true

    override val hasMainPage = true

    override val mainCategories = listOf(
        "All" to "all",
        "Completed" to "Completed",
        "Ongoing" to "Ongoing"
    )
    override val tags = listOf(
        "All" to "all",
        "Fan-Fic" to "fan-fiction",
        "Faloo" to "faloo",
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "CRomance" to "contemporary-romance",
        "Drama" to "drama",
        "Eastern Fantasy" to "eastern-fantasy",
        "Fantasy" to "fantasy",
        "Fantasy Romance" to "fantasy-romance",
        "Gender Bender" to "gender-bender",
        "Harem" to "harem",
        "Historical" to "historical",
        "Horror" to "horror",
        "Josei" to "josei",
        "Lolicon" to "lolicon",
        "Magical Realism" to "magical-realism",
        "Martial Arts" to "martial-arts",
        "Mecha" to "mecha",
        "Mystery" to "mystery",
        "Psychological" to "psychological",
        "Romance" to "romance",
        "School Life" to "school-life",
        "Sci-fi" to "sci-fi",
        "Seinen" to "seinen",
        "Shoujo" to "shoujo",
        "Shounen" to "shounen",
        "Shounen Ai" to "shounen-ai",
        "Slice of Life" to "slice-of-life",
        "Sports" to "sports",
        "Supernatural" to "supernatural",
        "Tragedy" to "tragedy",
        "Video Games" to "video-games",
        "Wuxia" to "wuxia",
        "Xianxia" to "xianxia",
        "Xuanhuan" to "xuanhuan",
        "Yaoi" to "yaoi",
        "Two-D" to "two-dimensional",
        "Erciyuan" to "erciyuan",
        "Game" to "game",
        "Military" to "military",
        "Urban Life" to "urban-life",
        "Yuri" to "yuri",
        "Chinese" to "chinese",
        "Japanese" to "japanese",
        "Hentai" to "hentai",
        "Isekai" to "isekai",
        "Magic" to "magic",
        "Shoujo Ai" to "shoujo-ai",
        "Urban" to "urban",
        "VR" to "virtual-reality",
        "Wuxia Xianxia" to "wuxia_xianxia",
        "Official" to "official_circles",
        "Sci-fi" to "science_fiction",
        "Thriller" to "suspense_thriller",
        "Travel Through Time" to "travel_through_time"
    )

    override val orderBys = listOf("New" to "newstime", "Popular" to "onclick", "Updates" to "lastdotime")


    override suspend fun loadMainPage(
        page: Int,
        mainCategory: String?,
        orderBy: String?,
        tag: String?
    ): HeadMainPageResponse
    {
        val url = "$mainUrl/list/${tag}/${mainCategory}-${orderBy}-${page}.html"
        val document = app.get(url, timeout = 60).document

        val returnValue = document.select("li.novel-item").mapNotNull { select ->

            val a = select.selectFirst("a[title]") ?: return@mapNotNull null
            val title =
                a.attr("title").takeIf { it.trim().isNotEmpty() }
                    ?: a.selectFirst("h4.novel-title")
                        ?.text() ?: return@mapNotNull null

            val href = a.attr("href")

            newSearchResponse(name = title, href) {
                posterUrl = fixUrlNull(select.selectFirst("img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                })
            }
        }
        return HeadMainPageResponse(url, returnValue)
    }

    private fun getRelated(document: Document): List<SearchResponse> {
        return document.select("div.section-body > ul.novel-list > li").mapNotNull { element ->
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst("h5")?.text() ?: return@mapNotNull null
            newSearchResponse(
                name = title,
                url = href
            ) {
                posterUrl = fixUrlNull(
                    element.selectFirst("img")?.attr("data-src")
                )
            }
        }
    }

    override suspend fun load(url: String): LoadResponse?
    {
        val document = app.get(url).document


        val title = document.selectFirst("h1.novel-title")?.text()?: return null
        val author = document.selectFirst("div.author [itemprop=author]")?.text()
        val synopsis = document.selectFirst("meta[itemprop=description]")?.attr("content")
            ?: document.selectFirst("div.summary, div.desc, #intro")?.text()
            ?: ""

        val cover = document.selectFirst("div.fixed-img img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val status = document.selectFirst("div.header-stats strong:matches(Ongoing|Completed)")?.text()


        // Extract bookId from the novel URL
        val bookId = url.substringAfterLast("/").substringBefore(".html")


        val chapters = mutableListOf<ChapterData>()
        val seenChapterUrls = HashSet<String>()

        var currentPage = 0
        while (currentPage < MAX_CHAPTER_PAGES) {
            val chaptersPageUrl = "$mainUrl/e/extend/fy.php?page=$currentPage&wjm=$bookId&X-Requested-With=XMLHttpRequest&_=${System.currentTimeMillis()}"

            val doc = app.get(chaptersPageUrl).document
            val chapterElements = doc.select("ul.chapter-list li")

            if (chapterElements.isEmpty()) break

            val pageChapters = chapterElements.mapNotNull { li ->
                val link = li.selectFirst("a") ?: return@mapNotNull null
                val rawHref = link.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val chapterUrl = fixUrl(rawHref)
                val chapterTitle = link.selectFirst("strong.chapter-title")?.text()?.trim().orEmpty()
                    .ifBlank { link.text().trim() }
                if (chapterTitle.isEmpty()) return@mapNotNull null
                newChapterData(name = chapterTitle, url = chapterUrl)
            }.filter { seenChapterUrls.add(it.url) }

            if (pageChapters.isEmpty()) break

            chapters.addAll(pageChapters)
            currentPage++
        }


        return newStreamResponse(title,fixUrl(url), chapters) {
            this.author = author
            this.posterUrl = fixUrlNull(cover)
            this.synopsis = synopsis
            setStatus(status)
            related = getRelated(document)
        }
    }

    override suspend fun loadHtml(url: String): String? {
        val fullUrl = fixUrl(url)
        val document = app.get(fullUrl).document
        // Select the actual chapter text container
        val contentElement = document.selectFirst("div.chapter-content")?:return null

        // Remove ad placeholders/images
        contentElement.select("img[src*=disable-blocker.jpg], script, div[align=center]").forEach { it.remove() }

        // Get the HTML string for display
        return contentElement.html()
    }

    private fun parseSearchDocument(document: Document): List<SearchResponse> {
        return document.select("li.novel-item").mapNotNull { element ->
            val node = element.selectFirst("a[title]") ?: return@mapNotNull null
            val rawHref = node.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val href = fixUrl(rawHref)
            val title = node.attr("title").trim()
                .ifBlank { element.selectFirst("h4.novel-title")?.text()?.trim().orEmpty() }
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null

            val cover = element.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }

            newSearchResponse(title, href) {
                posterUrl = fixUrlNull(cover)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val response = app.post(
            "$mainUrl/e/search/index.php",
            data = mapOf(
                "show" to "title",
                "tempid" to "1",
                "tbname" to "news",
                "keyboard" to cleanQuery
            ),
            allowRedirects = true
        )

        val searchId = extractWuxiaBoxSearchId(response.url)
        if (searchId.isNullOrBlank()) {
            return parseSearchDocument(response.document)
        }

        var currentPage = 0
        val results = mutableListOf<SearchResponse>()
        val seenUrls = HashSet<String>()

        while (currentPage < MAX_SEARCH_PAGES) {
            val url = "$mainUrl/e/search/result/index.php?page=$currentPage&searchid=$searchId"
            val document = app.get(url, timeout = 60).document
            val pageResults = parseSearchDocument(document).filter { seenUrls.add(it.url) }

            if (pageResults.isEmpty()) break

            results.addAll(pageResults)
            currentPage++
        }

        return results
    }

}