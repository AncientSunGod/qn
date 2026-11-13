package com.lagradost.quicknovel.providers

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProvidersCompatibilityTest {
    @Test
    fun faqWikiNormalizesMirrorUrlsAndExtractsEzsrcImagesAndFiltersSearch() {
        assertEquals(
            "https://faqwiki.us/novel/shadow-slave/",
            normalizeFaqWikiNovelUrl("http://faqwiki.xyz/shadow-slave/"),
        )

        val doc = Jsoup.parse(
            """
            <div class="plt-page-list">
              <div class="plt-page-item">
                <a href="http://faqwiki.xyz/shadow-slave/">Shadow Slave Novel – All Chapters</a>
                <img data-ezsrc="https://cdn.faqwiki.us/ss.jpg?ezimgfmt=ng:webp/ngcb2" src="/fallback.jpg" />
              </div>
              <div class="plt-page-item">
                <a href="/novel/lord-of-mysteries/"><h3>Lord of Mysteries</h3></a>
                <img data-src="/covers/lotm.png" />
              </div>
            </div>
            """.trimIndent()
        )

        val all = parseFaqWikiNovels(doc)
        assertEquals(2, all.size)
        assertEquals("Shadow Slave", all[0].name)
        assertEquals("https://faqwiki.us/novel/shadow-slave/", all[0].url)
        assertEquals("https://cdn.faqwiki.us/ss.jpg", all[0].posterUrl)
        assertEquals("https://faqwiki.us/covers/lotm.png", all[1].posterUrl)

        val filtered = parseFaqWikiNovels(doc, filterQuery = "shadow")
        assertEquals(1, filtered.size)
        assertEquals("Shadow Slave", filtered.single().name)
    }

    @Test
    fun fenrirRendersTipTapJsonAndFindsDynamicSvelteKitPayload() {
        val tipTapDoc = """
            {
              "type": "doc",
              "content": [
                {
                  "type": "heading",
                  "attrs": {"level": 2},
                  "content": [{"type": "text", "text": "Chapter One"}]
                },
                {
                  "type": "paragraph",
                  "content": [
                    {"type": "text", "text": "Hello\u200B "},
                    {"type": "text", "text": "World", "marks": [{"type": "bold"}]}
                  ]
                }
              ]
            }
        """.trimIndent()

        val rendered = renderFenrirTipTapDocToHtml(tipTapDoc)
        assertNotNull(rendered)
        assertEquals("<h2>Chapter One</h2><p>Hello <b>World</b></p>", rendered)

        // Wrap TipTap JSON at a non-hardcoded index inside SvelteKit __data.json nodes
        val svelteJson = org.json.JSONObject()
            .put(
                "nodes",
                org.json.JSONArray()
                    .put(org.json.JSONObject().put("type", "data").put("data", org.json.JSONArray().put("ignored")))
                    .put(
                        org.json.JSONObject().put(
                            "type",
                            "data",
                        ).put(
                            "data",
                            org.json.JSONArray()
                                .put("meta")
                                .put(123)
                                .put(tipTapDoc)
                                .put("trailing"),
                        )
                    )
            )
            .toString()

        assertEquals(rendered, parseFenrirSvelteKitChapterData(svelteJson))

        val apiHtml = parseFenrirApiChapterJson("""{"content":"<p>Clean<script>bad()</script><span aria-hidden='true'>watermark</span></p>","content_format":"html"}""")
        assertEquals("<p>Clean</p>", apiHtml)
    }

    @Test
    fun novelFireParsesArchiveWithoutPostIdAndStripsAntiScraperTags() {
        val novelDoc = Jsoup.parse("<html><body><h1 class=\"novel-title\">Test Novel</h1></body></html>")
        assertNull(extractNovelFirePostId(novelDoc))

        val archiveDoc = Jsoup.parse(
            """
            <ul class="chapter-list">
              <li><a href="/book/test/chapter-1" title="Chapter 1: Start">Ch 1</a></li>
              <li><a href="https://novelfire.net/book/test/chapter-2">Chapter 2</a></li>
            </ul>
            """.trimIndent()
        )
        val chapters = parseNovelFireArchiveChapters(archiveDoc)
        assertEquals(2, chapters.size)
        assertEquals("https://novelfire.net/book/test/chapter-1", chapters[0].url)
        assertEquals("Chapter 1: Start", chapters[0].name)

        val redirectDoc = Jsoup.parse(
            """<html><script>window.location.href = "https:\/\/novelfire.net\/book\/test\/chapter-1?t=1";</script></html>"""
        )
        assertEquals("https://novelfire.net/book/test/chapter-1?t=1", extractNovelFireRedirectUrl(redirectDoc))

        val chapterDoc = Jsoup.parse(
            """
            <span class="chapter-title">Chapter 1</span>
            <div id="content">
              <p>Chapter 1</p>
              <p>Real paragraph.</p>
              <img src="/assets/disable-blocker.jpg" />
              <nfabcdef>Watermark trap</nfabcdef>
            </div>
            """.trimIndent()
        )
        val parsedHtml = parseNovelFireChapterHtml(chapterDoc)
        assertNotNull(parsedHtml)
        assertTrue(parsedHtml!!.contains("Real paragraph."))
        assertFalse(parsedHtml.contains("Watermark trap"))
        assertFalse(parsedHtml.contains("disable-blocker.jpg"))
    }

    @Test
    fun wtrLabDecryptsWithDynamicAndDefaultKeysAndResolvesTokens() {
        assertEquals(7L, extractWtrLabChapterNo(7L, "https://wtr-lab.com/en/serie-1/novel/chapter-99"))
        assertEquals(42L, extractWtrLabChapterNo(0L, "https://wtr-lab.com/en/serie-1/novel/chapter-42?service=web"))

        val dynamicKey = "AbCdEfGhIjKlMnOpQrStUvWxYz012345"
        assertEquals(
            dynamicKey,
            extractWtrLabAesKeyFromSource("""const k = new TextEncoder().encode("$dynamicKey");"""),
        )

        val encoded = Base64.getEncoder().encodeToString("Azure Flame".toByteArray(Charsets.UTF_8))
        assertEquals(
            "He cast Azure Flame immediately.",
            resolveWtrLabPlaceholderTokens("He cast %{raw|$encoded} immediately."),
        )

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dynamicKey.toByteArray(Charsets.UTF_8), "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal("""["First decrypted paragraph","Second decrypted paragraph"]""".toByteArray(Charsets.UTF_8))
        val splitPoint = sealed.size - 16
        val longPart = Base64.getEncoder().encodeToString(sealed.copyOfRange(0, splitPoint))
        val shortPart = Base64.getEncoder().encodeToString(sealed.copyOfRange(splitPoint, sealed.size))
        val ivPart = Base64.getEncoder().encodeToString(iv)

        val decrypted = WtrLabProvider().decryptContent("arr:$ivPart:$shortPart:$longPart", dynamicKey)
        assertEquals(listOf("First decrypted paragraph", "Second decrypted paragraph"), decrypted)
    }

    @Test
    fun wuxiaBoxExtractsSearchIdCorrectly() {
        assertEquals(
            "98765",
            extractWuxiaBoxSearchId("https://www.wuxiabox.com/e/search/result/?searchid=98765"),
        )
        assertNull(extractWuxiaBoxSearchId("https://www.wuxiabox.com/e/search/index.php"))
    }
}
