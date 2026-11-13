package com.lagradost.quicknovel.extensions

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportedProviderChapterCompatibilityTest {
    private fun fixture(resource: String): String =
        javaClass.getResourceAsStream(resource)!!.bufferedReader().use { it.readText() }

    private fun installed(id: String, name: String, site: String, source: String): Pair<InstalledExtension, java.io.File> {
        val file = java.io.File.createTempFile("lnreader-$id-", ".js")
        file.writeText(source)
        return InstalledExtension(
            id = id,
            name = name,
            site = site,
            version = "test",
            language = "English",
            url = "$site/plugin.js",
            repositoryUrl = "https://example.com/plugins.min.json",
            bundlePath = file.absolutePath,
        ) to file
    }

    @Test
    fun fenrirRealmImportedBundleBuildsChapterListFromCurrentApi() {
        val (extension, file) = installed(
            "fenrirrealm", "Fenrir Realm", "https://fenrirealm.com",
            fixture("/lnreader/fenrirrealm.js"),
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension, "parseNovel", """["sample"]""",
                testRequestOverride = { url, method, _, _ ->
                    assertEquals("GET", method)
                    when (url) {
                        "https://fenrirealm.com/api/new/v2/series/sample/chapters" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """[{"id":101,"slug":"chapter-one","number":1,"title":"Chapter 1","locked":{"price":0}}]""",
                            )
                        "https://fenrirealm.com/api/new/v2/series/sample" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"title":"Sample Novel","description":"<p>Summary</p>","user":{"name":"Writer"},"cover":"https://fenrirealm.com/cover.jpg","genres":[{"name":"Fantasy"}],"status":"Ongoing"}""",
                            )
                        else -> throw AssertionError("Unexpected Fenrir request: $url")
                    }
                },
            ).getOrThrow()
            val novel = JSONObject(result.json)
            assertEquals("Sample Novel", novel.getString("name"))
            assertEquals(1, novel.getJSONArray("chapters").length())
            assertEquals("Chapter 1", novel.getJSONArray("chapters").getJSONObject(0).getString("name"))
            assertTrue(novel.getJSONArray("chapters").getJSONObject(0).getString("path").contains("chapter-one~~101"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun wtrLabImportedBundleBuildsChapterListFromCurrentApi() {
        val (extension, file) = installed(
            "wtrlab", "WTR-Lab", "https://wtr-lab.com",
            fixture("/lnreader/wtrlab.js"),
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension, "parseNovel", """["serie-123/sample"]""",
                testRequestOverride = { url, method, _, _ ->
                    assertEquals("GET", method)
                    when (url) {
                        "https://wtr-lab.com/serie-123/sample" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "text/html",
                                """
                                <html><head>
                                  <meta name="baggage" content="sample-baggage">
                                  <meta name="sentry-trace" content="sample-trace">
                                  <script id="__NEXT_DATA__" type="application/json">
                                    {"props":{"pageProps":{"serie":{"serie_data":{"data":{"title":"Sample WTR Novel","image":"/cover.jpg","description":"Summary","author":"Writer"},"raw_id":123,"slug":"sample","genres":[],"chapter_count":1,"status":0}}}}}
                                  </script>
                                </head><body><h1 class="text-uppercase">Sample WTR Novel</h1></body></html>
                                """.trimIndent(),
                            )
                        "https://wtr-lab.com/api/chapters/123?start=1&end=500" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"chapters":[{"title":"Chapter One","order":1,"updated_at":"2026-10-01"}]}""",
                            )
                        else -> throw AssertionError("Unexpected WTR-Lab request: $url")
                    }
                },
            ).getOrThrow()
            val novel = JSONObject(result.json)
            assertEquals("Sample WTR Novel", novel.getString("name"))
            assertEquals(1, novel.getJSONArray("chapters").length())
            assertEquals("Chapter One", novel.getJSONArray("chapters").getJSONObject(0).getString("name"))
        } finally {
            file.delete()
        }
    }
    @Test
    fun fenrirRealmImportedBundleLoadsChapterBodyThroughCurrentApi() {
        val (extension, file) = installed(
            "fenrirrealm-chapter", "Fenrir Realm", "https://fenrirealm.com",
            fixture("/lnreader/fenrirrealm.js"),
        )
        val requested = mutableListOf<Pair<String, String>>()
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "parseChapter",
                """["sample/chapter-one~~101"]""",
                testRequestOverride = { url, method, _, _ ->
                    requested += method to url
                    when {
                        method == "POST" && url == "https://fenrirealm.com/api/read-token" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"active":false}""",
                            )
                        method == "GET" && url == "https://fenrirealm.com/api/new/v2/chapters/101" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"id":101,"content_format":"html","content":"<p>Fenrir Realm chapter body</p>"}""",
                            )
                        else -> throw AssertionError("Unexpected Fenrir chapter request: $method $url")
                    }
                },
            ).getOrThrow()

            assertTrue(
                "Fenrir chapter endpoint was not requested",
                requested.contains("GET" to "https://fenrirealm.com/api/new/v2/chapters/101"),
            )
            assertTrue(result.json.contains("Fenrir Realm chapter body"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun wtrLabChapterUsesSelectedTranslationFallbackAndBrowserReferer() {
        val (extension, file) = installed(
            "wtrlab-translation", "WTR-Lab", "https://wtr-lab.com",
            fixture("/lnreader/wtrlab.js"),
        )
        val requestedModes = mutableListOf<String>()
        val expectedReferer = "https://wtr-lab.com/serie-123/sample/chapter-1"
        try {
            ExtensionJsRuntime.execute(extension, "getPluginSettings", "[]").getOrThrow()
            ExtensionJsRuntime.execute(
                extension, "setPluginSetting", """["showModeNotice",false]"""
            ).getOrThrow()

            val result = ExtensionJsRuntime.execute(
                extension,
                "parseChapter",
                """["serie-123/sample/chapter-1"]""",
                testRequestOverride = { url, method, headers, body ->
                    assertEquals("https://wtr-lab.com/api/reader/get", url)
                    assertEquals("POST", method)
                    assertEquals(
                        expectedReferer,
                        headers.entries.firstOrNull { it.key.equals("referer", ignoreCase = true) }?.value,
                    )
                    val mode = JSONObject(body.orEmpty()).getString("translate")
                    requestedModes += mode
                    if (mode == "ai") {
                        ExtensionHttpClient.TextResponse(
                            url, 200, "application/json",
                            """{"success":false,"message":"AI translation unavailable"}""",
                        )
                    } else {
                        assertEquals("web", mode)
                        ExtensionHttpClient.TextResponse(
                            url, 200, "application/json",
                            """{"success":true,"chapter":{"title":"Chapter One"},"data":{"data":{"body":"WTR-LAB chapter body"}}}""",
                        )
                    }
                },
            ).getOrThrow()

            assertEquals(listOf("ai", "web"), requestedModes)
            assertTrue(result.json.contains("Chapter One"))
            assertTrue(result.json.contains("WTR-LAB chapter body"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun fenrirAndNovelFireSwitchSettingsNormalizeEmptyDefaultsToBoolean() {
        val fenrir = installed(
            "fenrirrealm-settings", "Fenrir Realm", "https://fenrirealm.com",
            fixture("/lnreader/fenrirrealm.js"),
        )
        val novelfire = installed(
            "novelfire-settings", "Novel Fire", "https://novelfire.net",
            fixture("/lnreader/official/novelfire-v3.js"),
        )
        try {
            listOf(fenrir.first to "hideLocked", novelfire.first to "pageLength").forEach { (ext, key) ->
                val settingsJson = JSONObject(
                    ExtensionJsRuntime.execute(ext, "getPluginSettings", "[]").getOrThrow().json
                )
                val setting = settingsJson.getJSONObject(key)
                assertEquals("Switch", setting.getString("type"))
                assertEquals(false, setting.get("value"))

                val saved = ExtensionJsRuntime.execute(
                    ext,
                    "setPluginSetting",
                    JSONArray().put(key).put(setting.getBoolean("value")).toString(),
                ).getOrThrow()
                assertEquals("true", saved.json.trim())
            }
        } finally {
            fenrir.second.delete()
            novelfire.second.delete()
        }
    }

    @Test
    fun extensionHttpClientPermitsWtrLabAndFenrirCustomHeaders() {
        assertTrue(ExtensionHttpClient.isAllowedExtensionRequestHeader("x-goog-api-key"))
        assertTrue(ExtensionHttpClient.isAllowedExtensionRequestHeader("X-Accepts-Encrypted-Content"))
        assertTrue(ExtensionHttpClient.isAllowedExtensionRequestHeader("baggage"))
        assertTrue(ExtensionHttpClient.isAllowedExtensionRequestHeader("sentry-trace"))
        assertTrue(ExtensionHttpClient.isAllowedExtensionRequestHeader("cache-control"))
        assertTrue(ExtensionHttpClient.isAllowedExtensionRequestHeader("pragma"))
    }

    @Test
    fun faqWikiOfficialBundleParsesDataEzsrcNovelsAndFiltersSearch() {
        val (extension, file) = installed(
            "faqwiki-official", "Faq Wiki", "https://faqwiki.xyz",
            fixture("/lnreader/official/faqwiki-v3.js"),
        )
        try {
            val catalogHtml = """
                <html><body>
                  <div class="plt-page-list">
                    <div class="plt-page-item">
                      <a href="https://faqwiki.xyz/shadow-slave/">Shadow Slave Novel – All Chapters</a>
                      <img data-ezsrc="https://cdn.faqwiki.xyz/cover1.jpg?ezimgfmt=ng:webp/ngcb1" src="/placeholder.jpg" />
                    </div>
                    <div class="plt-page-item">
                      <a href="https://faqwiki.xyz/other-novel/">Other Novel – All Chapters</a>
                      <img src="https://cdn.faqwiki.xyz/cover2.jpg" />
                    </div>
                  </div>
                </body></html>
            """.trimIndent()

            val searchResult = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                """["shadow", 1]""",
                testRequestOverride = { _, _, _, _ ->
                    ExtensionHttpClient.TextResponse("https://faqwiki.xyz", 200, "text/html", catalogHtml)
                },
            ).getOrThrow()

            val items = JSONArray(searchResult.json)
            assertEquals(1, items.length())
            assertEquals("Shadow Slave", items.getJSONObject(0).getString("name"))
            assertEquals("https://cdn.faqwiki.xyz/cover1.jpg", items.getJSONObject(0).getString("cover"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun novelFireOfficialBundleFollowsJsRedirectAndParsesChapter() {
        val (extension, file) = installed(
            "novelfire-official", "Novel Fire", "https://novelfire.net/",
            fixture("/lnreader/official/novelfire-v3.js"),
        )
        try {
            val redirectHtml = """
                <html><head><title>Redirecting</title></head><body>
                  <script>window.location.href = "https://novelfire.net/book/sample/chapter-1?token=ok";</script>
                </body></html>
            """.trimIndent()
            val chapterHtml = """
                <html><head><title>Chapter 1</title></head><body>
                  <div id="content">
                    <p>First real&nbsp;paragraph.</p>
                    <p>Second real paragraph.</p>
                  </div>
                </body></html>
            """.trimIndent()

            val requested = mutableListOf<String>()
            val parsed = ExtensionJsRuntime.execute(
                extension,
                "parseChapter",
                """["book/sample/chapter-1"]""",
                testRequestOverride = { url, _, _, _ ->
                    requested += url
                    if (url.contains("token=ok")) {
                        ExtensionHttpClient.TextResponse(url, 200, "text/html", chapterHtml)
                    } else {
                        ExtensionHttpClient.TextResponse(url, 200, "text/html", redirectHtml)
                    }
                },
            ).getOrThrow()

            assertEquals(
                listOf(
                    "https://novelfire.net/book/sample/chapter-1",
                    "https://novelfire.net/book/sample/chapter-1?token=ok",
                ),
                requested,
            )
            assertTrue(parsed.json.contains("First real paragraph."))
            assertTrue(parsed.json.contains("Second real paragraph."))
        } finally {
            file.delete()
        }
    }

}
