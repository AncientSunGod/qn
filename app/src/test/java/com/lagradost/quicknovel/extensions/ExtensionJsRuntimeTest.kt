package com.lagradost.quicknovel.extensions

import org.junit.Assert.assertEquals
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class ExtensionJsRuntimeTest {
    private fun extension(source: String): Pair<InstalledExtension, File> {
        val file = File.createTempFile("quicknovel-extension-", ".js")
        file.writeText(source)
        return InstalledExtension(
            id = "test-extension",
            name = "Test Extension",
            site = "https://example.com",
            version = "1",
            language = "en",
            url = "https://example.com/plugin.js",
            repositoryUrl = "https://example.com/plugins.min.json",
            bundlePath = file.absolutePath,
        ) to file
    }

    @Test
    fun executesSimpleCommonJsExportAndReturnsJson() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function(term, page) { " +
                "return [{ title: term, url: 'https://example.com/' + page }]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                """["hello",2]""",
            ).getOrThrow()
            assertEquals("""[{"title":"hello","url":"https://example.com/2"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsUnknownImportsRatherThanFakingCompatibility() {
        val (extension, file) = extension(
            "var helper = require('@unsupported/module'); module.exports = { " +
                "searchNovels: function() { return []; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Unsupported LNReader module import"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun stopsInstructionHeavyExtensionCode() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { while (true) {} } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("instruction budget"))
        } finally {
            file.delete()
        }
    }
    @Test
    fun allowsLargerButBoundedNovelMetadataParsingBudget() {
        val (extension, file) = extension(
            "module.exports = { parseNovel: function() { " +
                "var count = 0; while (count < 500000) { count++; } " +
                "return { name: 'budget test', chapters: [] }; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "parseNovel", "[]")
            assertTrue(result.isSuccess)
            assertTrue(result.getOrThrow().json.contains("budget test"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsLnReaderAesGcmAndUtf8UtilityModules() {
        val (extension, file) = extension(
            "var aes = require('@libs/aes'); " +
                "var utils = require('@libs/utils'); " +
                "module.exports = { searchNovels: function() { " +
                "var key = [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]; " +
                "var nonce = [0,0,0,0,0,0,0,0,0,0,0,0]; " +
                "var cipher = aes.gcm(key, nonce); " +
                "var encrypted = cipher.encrypt(utils.utf8ToBytes('Fenrir Realm')); " +
                "return [{name:utils.bytesToUtf8(cipher.decrypt(encrypted)),path:'/aes-test'}]; " +
                "} };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertTrue(result.json.contains("Fenrir Realm"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsBrowserFetchAndHeadersGlobalsForLnReaderPlugins() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { " +
                "var headers = new Headers({'X-Token':'first'}); headers.append('X-Token','second'); " +
                "return fetch('https://example.com/api', {method:'POST',headers:headers,body:'payload'})" +
                ".then(function(response) { return response.text().then(function(body) { " +
                "return [{ok:response.ok,status:response.status,token:response.headers.get('x-reply')," +
                "body:body,requestToken:headers.get('X-TOKEN')}]; }); }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                "[]",
                testRequestOverride = { url, method, headers, body ->
                    assertEquals("https://example.com/api", url)
                    assertEquals("POST", method)
                    assertEquals("first, second", headers["X-Token"])
                    assertEquals("payload", body)
                    ExtensionHttpClient.TextResponse(
                        url, 200, "text/plain", "fetch bridge works",
                        headers = mapOf("x-reply" to "ok"),
                    )
                },
            ).getOrThrow()
            assertEquals(
                """[{"ok":true,"status":200,"token":"ok","body":"fetch bridge works","requestToken":"first, second"}]""",
                result.json,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsAsyncStyleBundleAndKnownLnReaderModules() {
        val (extension, file) = extension(
            "var cover = require('@libs/defaultCover'); " +
                "var filters = require('@libs/filterInputs'); " +
                "var status = require('@libs/novelStatus'); " +
                "module.exports = { searchNovels: function(term) { " +
                "return Promise.resolve([{name:term,path:'slug',cover:cover.defaultCover," +
                "filter:filters.FilterTypes.Picker,status:status.NovelStatus.Ongoing}]); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                """["sample"]""",
            ).getOrThrow()
            assertTrue(result.json.contains("\"name\":\"sample\""))
            assertTrue(result.json.contains("\"filter\":\"Picker\""))
            assertTrue(result.json.contains("\"status\":\"Ongoing\""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsLegacyConstantsAliasUsedByNovelFireFamilyBundles() {
        val (extension, file) = extension(
            "var constants = require('@/types/constants'); " +
                "module.exports = { searchNovels: function() { " +
                "return [{name:'Novel Fire',path:'/novel',cover:constants.defaultCover}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"Novel Fire","path":"/novel","cover":""}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun popularListingsUsePluginFilterDefaultsWhenNoFiltersAreConfigured() {
        val (extension, file) = extension(
            "module.exports = { filters:{genre:{value:'Fantasy'}}, " +
                "popularNovels:function(page,options){return [{name:options.filters.genre.value,path:'sample'}];} };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "popularNovels",
                """[1,{"showLatestNovels":false,"filters":{}}]""",
            ).getOrThrow()
            assertEquals("""[{"name":"Fantasy","path":"sample"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsPromiseAllForBundledAsyncHelpers() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { " +
                "return Promise['all']([Promise.resolve('a'), Promise.resolve('b')])" +
                ".then(function(values) { return values; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""["a","b"]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun executesLnReaderCustomJavaScriptAgainstChapterCheerioDocument() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { return []; } };"
        )
        try {
            val html = """<div><!-- remove-me --><p>Remove first</p><p>Remove second</p><p>Keep this chapter text</p></div>"""
            val customScript = """
                loadedCheerio('*').contents().each(function() {
                  if (this.type === 'comment' && this.data.trim() === 'remove-me') {
                    var currentNode = this.next;
                    for (var i = 0; i < 2 && currentNode; i++) {
                      var nextNode = currentNode.next;
                      loadedCheerio(currentNode).remove();
                      currentNode = nextNode;
                    }
                  }
                });
            """.trimIndent()
            val result = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "transformChapterHtml",
                argumentsJson = JSONArray().put(html).toString(),
                customScript = customScript,
            ).getOrThrow()
            val transformed = org.json.JSONTokener(result.json).nextValue() as String
            assertTrue(!transformed.contains("Remove first"))
            assertTrue(!transformed.contains("Remove second"))
            assertTrue(transformed.contains("Keep this chapter text"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun exposesHtmlparser2VoidElementCheckUsedByNovelPingAndFreeWebNovel() {
        val (extension, file) = extension(
            "var Parser = require('htmlparser2').Parser; " +
                "module.exports = { searchNovels: function() { " +
                "var parser = new Parser({}); " +
                "return [{br:parser.isVoidElement('br'),img:parser.isVoidElement('IMG')," +
                "input:parser.isVoidElement('input'),keygen:parser.isVoidElement('keygen')," +
                "basefont:parser.isVoidElement('basefont'),div:parser.isVoidElement('div')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"br":true,"img":true,"input":true,"keygen":true,"basefont":true,"div":false}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun parsesChapterHtmlBeyondLegacyTwoMegabyteParserOutputLimit() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var html = '<main>'; for (var i=0;i<25;i++) { html += '<div>' + new Array(100001).join('x') + '</div>'; } html += '</main>'; " +
                "var \$ = cheerio.load(html); var length = 0; \$('div').each(function(i, el) { length += \$(el).text().length; }); return [{length:length}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"length":2500000}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsCommonCheerioLoadSelectTextAndAttributeCalls() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<div class=\"entry\"><a href=\"/novel\">Sample Novel</a></div>'); " +
                "return [{name:$('.entry a').text(),path:$('.entry a').attr('href')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"Sample Novel","path":"/novel"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsCheerioEachAndNestedFind() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<ul><li><a href=\"/one\"> One </a></li><li><a href=\"/two\">Two</a></li></ul>'); " +
                "var items = []; $('li').each(function(i, el) { " +
                "items.push({name:$(el).find('a').text().trim(),path:$(el).find('a').attr('href')}); }); " +
                "return items; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            println("Cheerio each/find result: ${result.json}")
            val actual = JSONArray(result.json)
            assertEquals("Unexpected Cheerio each/find result: ${result.json}", 2, actual.length())
            assertEquals("Unexpected first Cheerio item: ${result.json}", "One", actual.getJSONObject(0).getString("name"))
            assertEquals("Unexpected first Cheerio path: ${result.json}", "/one", actual.getJSONObject(0).getString("path"))
            assertEquals("Unexpected second Cheerio item: ${result.json}", "Two", actual.getJSONObject(1).getString("name"))
            assertEquals("Unexpected second Cheerio path: ${result.json}", "/two", actual.getJSONObject(1).getString("path"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsCommonCheerioTraversalFilteringAndMutationMethods() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<div id=\"root\"><p>One</p><p>Two</p></div>'); " +
                "var nodes = $('#root').find('*').addBack().contents(); " +
                "var paragraphs = nodes.filter(function(i, node) { " +
                "return node.nodeType === 1 && node.name === 'p'; }); " +
                "paragraphs.first().attr('data-added','yes').before('<b>Before</b>').after('<i>After</i>'); " +
                "paragraphs.last().replaceWith('<strong>Last</strong>'); " +
                "return [{count:paragraphs.toArray().length,html:$('#root').html(),last:$('strong').text()}]; " +
                "} };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertTrue(result.json.contains("\"count\":2"))
            assertTrue(result.json.contains("<b>Before</b>"))
            assertTrue(result.json.contains("data-added=\\\"yes\\\""))
            assertTrue(result.json.contains("<i>After</i>"))
            assertTrue(result.json.contains("<strong>Last</strong>"))
            assertTrue(result.json.contains("\"last\":\"Last\""))
        } finally {
            file.delete()
        }
    }

    @Test
    fun decodesNonUtf8FetchTextUsingRequestedOrResponseCharset() {
        val gbk = Charset.forName("GBK")
        val source = "章节内容"
        val bytes = source.toByteArray(gbk)

        assertEquals(source, ExtensionHttpClient.decodeText(bytes, "GBK"))
        assertEquals(
            source,
            ExtensionHttpClient.decodeText(bytes, contentType = "text/html; charset=gbk"),
        )
    }

    @Test
    fun supportsDestructuredFetchTextImport() {
        val (extension, file) = extension(
            "var fetchText = require('@libs/fetch').fetchText; " +
                "module.exports = { searchNovels: function() { " +
                "return fetchText('https://example.com/text').then(function(body) { " +
                "return [{name:body}]; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                "[]",
                testFetchOverride = { url ->
                    assertEquals("https://example.com/text", url)
                    ExtensionHttpClient.TextResponse(
                        url = url,
                        statusCode = 200,
                        contentType = "text/plain",
                        body = "destructured fetchText works",
                    )
                },
            ).getOrThrow()
            assertEquals(
                """[{"name":"destructured fetchText works"}]""",
                result.json,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsLnReaderFetchFileBinaryBase64Responses() {
        val (extension, file) = extension(
            "var fetchFile = require('@libs/fetch').fetchFile; " +
                "module.exports = { searchNovels: function() { " +
                "return fetchFile('https://example.com/cover.jpg').then(function(encoded) { " +
                "return [{name:encoded}]; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "searchNovels",
                argumentsJson = "[]",
                testRequestOverride = { url, method, headers, body ->
                    assertEquals("https://example.com/cover.jpg", url)
                    assertEquals("GET", method)
                    assertTrue(headers.isEmpty())
                    assertEquals(null, body)
                    ExtensionHttpClient.TextResponse(
                        url = url,
                        statusCode = 200,
                        contentType = "image/jpeg",
                        body = "",
                        bodyBytes = byteArrayOf(0, 1, 2, 3, 0xfe.toByte(), 0xff.toByte()),
                    )
                },
            ).getOrThrow()
            assertEquals("AAECA/7/", JSONArray(result.json).getJSONObject(0).getString("name"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsFetchApiPostOptionsAndStandardResponseMetadata() {
        val (extension, file) = extension(
            "var fetch = require('@libs/fetch'); " +
                "module.exports = { searchNovels: function() { " +
                "return fetch.fetchApi('https://example.com/api', {" +
                "method:'POST',headers:{'Content-Type':'application/json'}," +
                "body:'{\\\"query\\\":\\\"sample\\\"}'}).then(function(response) { " +
                "return [{name:response.statusText,path:response.headers.get('content-type')," +
                "latestChapter:response.ok ? String(response.status) : 'failed'}]; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                "[]",
                testRequestOverride = { url, method, headers, body ->
                    assertEquals("https://example.com/api", url)
                    assertEquals("POST", method)
                    assertEquals("application/json", headers["Content-Type"])
                    assertEquals("""{"query":"sample"}""", body)
                    ExtensionHttpClient.TextResponse(
                        url = url,
                        statusCode = 201,
                        contentType = "application/json",
                        body = """{"ok":true}""",
                        statusText = "Created",
                        headers = mapOf("content-type" to "application/json"),
                    )
                },
            ).getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals("Created", item.getString("name"))
            assertEquals("application/json", item.getString("path"))
            assertEquals("201", item.getString("latestChapter"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsSimpleFormDataPostsUsedByLnReaderMultisrcPlugins() {
        val (extension, file) = extension(
            "var fetch = require('@libs/fetch'); " +
                "module.exports = { parseNovel: function() { " +
                "var form = new FormData(); form.append('action','manga_get_chapters'); " +
                "form.append('manga','12345'); " +
                "return fetch.fetchApi('https://example.com/ajax', {method:'POST',body:form})" +
                ".then(function(response) { return {name:String(response.status)}; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "parseNovel",
                argumentsJson = "[]",
                testRequestOverride = { url, method, headers, body ->
                    assertEquals("https://example.com/ajax", url)
                    assertEquals("POST", method)
                    assertTrue(headers["Content-Type"].orEmpty().startsWith("multipart/form-data; boundary="))
                    assertTrue(body.orEmpty().contains("name=\"action\""))
                    assertTrue(body.orEmpty().contains("manga_get_chapters"))
                    assertTrue(body.orEmpty().contains("12345"))
                    ExtensionHttpClient.TextResponse(
                        url = url,
                        statusCode = 200,
                        contentType = "text/plain",
                        body = "ok",
                    )
                },
            ).getOrThrow()
            assertTrue(result.json.contains("200"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsUnsupportedFetchMethods() {
        val (extension, file) = extension(
            "var fetch = require('@libs/fetch'); " +
                "module.exports = { searchNovels: function() { " +
                "return fetch.fetchApi('https://example.com/api', {method:'PUT'}); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("GET and POST"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsFetchTextAndCommonUrlHelpers() {
        val (extension, file) = extension(
            "var fetch = require('@libs/fetch'); " +
                "var absolute = require('@libs/isAbsoluteUrl'); " +
                "var encoding = require('urlencode'); " +
                "module.exports = { searchNovels: function() { " +
                "return fetch.fetchText('https://example.com/data').then(function(body) { " +
                "return [{name:body,path:encoding.encode('a b'),absolute:absolute.isUrlAbsolute('https://example.com/x')," +
                "invalidAbsolute:absolute.isUrlAbsolute('httpx://example.com/x')}]; }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                "[]",
                testFetchOverride = { url ->
                    assertEquals("https://example.com/data", url)
                    ExtensionHttpClient.TextResponse(url, 200, "text/plain", "sample")
                },
            ).getOrThrow()
            assertEquals("""[{"name":"sample","path":"a%20b","absolute":true,"invalidAbsolute":false}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsHtmlparser2ParserCallbacksForCommonHtmlScrapers() {
        val (extension, file) = extension(
            "var Parser = require('htmlparser2').Parser; " +
                "module.exports = { searchNovels: function() { " +
                "var items = [], current = {}, inAnchor = false; " +
                "var parser = new Parser({ " +
                "onopentag:function(name,attrs){if(name==='a'){current.path=attrs.href;inAnchor=true;}}, " +
                "ontext:function(text){if(inAnchor)current.name=(current.name||'')+text;}, " +
                "onclosetag:function(name){if(name==='a'){items.push(current);current={};inAnchor=false;}} " +
                "}); parser.write('<a href=/novel>Sample Novel</a>'); parser.end(); return items; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"path":"/novel","name":"Sample Novel"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsCommonDayjsDateParsingFormattingAndSubtraction() {
        val (extension, file) = extension(
            "var dayjs = require('dayjs').default; " +
                "module.exports = { searchNovels: function() { " +
                "var parsed = dayjs('07.08.2026','DD.MM.YYYY'); " +
                "return [{name:parsed.format('YYYY-MM-DD'),path:dayjs('2026-08-01').subtract(1,'day').format('YYYY-MM-DD')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"2026-08-07","path":"2026-07-31"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsDayjsWeekBoundariesAndCalendarMonthDiffs() {
        val (extension, file) = extension(
            "var dayjs = require('dayjs'); " +
                "module.exports = { searchNovels: function() { " +
                "var date = dayjs('2026-08-07T15:30:00'); " +
                "return [{name:date.startOf('week').format('YYYY-MM-DD'), " +
                "path:date.diff('2026-07-01T15:30:00','month')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"2026-08-02","path":1}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun keepsSessionStorageOnlyForTheCurrentExtensionSession() {
        val (extension, file) = extension(
            "var session = require('@libs/storage').sessionStorage; " +
                "module.exports = { searchNovels: function() { " +
                "session.setItem('token','temporary'); " +
                "return [{name:session.getItem('token')}]; } };"
        )
        val readExtension = extension.copy(
            bundlePath = file.absolutePath,
            id = extension.id,
        )
        val reader = File.createTempFile("quicknovel-session-reader-", ".js")
        reader.writeText(
            "var session = require('@libs/storage').sessionStorage; " +
                "module.exports = { searchNovels: function() { " +
                "return [{name:session.getItem('token')}]; } };"
        )
        try {
            val write = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("temporary", JSONArray(write.json).getJSONObject(0).getString("name"))

            val read = ExtensionJsRuntime.execute(
                extension = readExtension.copy(bundlePath = reader.absolutePath),
                operation = "searchNovels",
                argumentsJson = "[]",
            ).getOrThrow()
            assertEquals("temporary", JSONArray(read.json).getJSONObject(0).getString("name"))

            ExtensionJsRuntime.clearSessionStorage(extension.id)
            val afterClear = ExtensionJsRuntime.execute(
                extension = readExtension.copy(bundlePath = reader.absolutePath),
                operation = "searchNovels",
                argumentsJson = "[]",
            ).getOrThrow()
            assertTrue(JSONArray(afterClear.json).getJSONObject(0).isNull("name"))
        } finally {
            file.delete()
            reader.delete()
            ExtensionJsRuntime.clearSessionStorage(extension.id)
        }
    }

    @Test
    fun exposesStorageHelperMethodsToBundles() {
        val (extension, file) = extension(
            "var helpers = require('@libs/storage'); var store = helpers.storage; " +
                "module.exports = { searchNovels: function() { " +
                "return [{name:typeof store.get,path:typeof store.set," +
                "latestChapter:typeof helpers.localStorage.getItem," +
                "cover:typeof helpers.sessionStorage.setItem}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"function","path":"function","latestChapter":"function","cover":"function"}]""", result.json)
        } finally {
            file.delete()
        }
    }

    @Test
    fun sessionStoragePersistsAcrossExtensionCallsButClearsWithTheSession() {
        val (baseExtension, file) = extension(
            "var session = require('@libs/storage').sessionStorage; " +
                "module.exports = { searchNovels: function(mode) { " +
                "if (mode === 'set') { session.setItem('session-key','session-value'); return []; } " +
                "return [{name:session.getItem('session-key'),path:'read'}]; } };"
        )
        val extension = baseExtension.copy(id = "session-storage-lifecycle")
        try {
            ExtensionJsRuntime.execute(extension, "searchNovels", """["set"]""").getOrThrow()
            val stored = ExtensionJsRuntime.execute(extension, "searchNovels", """["read"]""").getOrThrow()
            assertEquals("""[{"name":"session-value","path":"read"}]""", stored.json)

            ExtensionJsRuntime.clearSessionStorage(extension.id)
            val cleared = ExtensionJsRuntime.execute(extension, "searchNovels", """["read"]""").getOrThrow()
            assertEquals("""[{"name":null,"path":"read"}]""", cleared.json)
        } finally {
            ExtensionJsRuntime.clearSessionStorage(extension.id)
            file.delete()
        }
    }

    @Test
    fun exposesLnReaderImageRequestInitHeaders() {
        val (extension, file) = extension(
            "module.exports = { " +
                "imageRequestInit:{headers:{referrer:'https://example.com','User-Agent':'QuickNovel test'}}, " +
                "searchNovels:function(){return [];} };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "getImageRequestInit",
                argumentsJson = "[]",
            ).getOrThrow()
            val headers = org.json.JSONObject(result.json).getJSONObject("headers")
            assertEquals("https://example.com", headers.getString("referrer"))
            assertEquals("QuickNovel test", headers.getString("User-Agent"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun approvesAdditionalPublicHostsDeclaredInAnExtensionBundle() {
        val hosts = ExtensionJsRuntime.approvedHostsForBundle(
            "https://genesistudio.com",
            """
                var api = "https://api.genesistudio.com";
                var images = 'https://cdn.genesistudio.com/covers';
            """.trimIndent(),
        )
        assertEquals(
            setOf("genesistudio.com", "api.genesistudio.com", "cdn.genesistudio.com"),
            hosts,
        )
    }

    @Test
    fun fetchBridgeRejectsRequestsOutsideTheExtensionSite() {
        val (extension, file) = extension(
            "var fetch = require('@libs/fetch'); var externalHost = 'other.example'; " +
                "module.exports = { searchNovels: function() { " +
                "return fetch.fetchApi('https://' + externalHost + '/data').then(function(r) { return r.json(); }); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("approved site"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun executesRealChikariPluginBundleWithMockedSiteResponse() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)
        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "searchNovels",
                argumentsJson = """["sample",1]""",
                testFetchOverride = { url ->
                    assertEquals(
                        "https://chikari.moe/api/series?type=novel&sort=popular&q=sample&limit=36&offset=0",
                        url,
                    )
                    ExtensionHttpClient.TextResponse(
                        url = url,
                        statusCode = 200,
                        contentType = "application/json",
                        body = """{"items":[{"title":"Sample Novel","slug":"sample-novel","cover_url":"https://chikari.moe/cover.jpg"}]}""",
                    )
                },
            ).getOrThrow()
            val items = org.json.JSONArray(result.json)
            assertEquals(1, items.length())
            assertEquals("Sample Novel", items.getJSONObject(0).getString("name"))
            assertEquals("sample-novel", items.getJSONObject(0).getString("path"))
            assertEquals("https://chikari.moe/cover.jpg", items.getJSONObject(0).getString("cover"))
        } finally {
            file.delete()
        }
    }


    @Test
    fun executesRealFirstKissNovelBundleWithMockedSiteResponse() {
        val source = javaClass.getResourceAsStream("/lnreader/firstkissnovel.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)
        try {
            val execution = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://1stkissnovel.org"),
                operation = "searchNovels",
                argumentsJson = """["sample",1]""",
                testFetchOverride = { url ->
                    assertEquals(
                        "https://1stkissnovel.org/page/1/?s=sample&post_type=wp-manga",
                        url,
                    )
                    ExtensionHttpClient.TextResponse(
                        url = url,
                        statusCode = 200,
                        contentType = "text/html; charset=utf-8",
                        body = """
                            <html><head><title>FirstKissNovel</title></head><body>
                              <div class="page-item-detail">
                                <div class="post-title"><a href="https://1stkissnovel.org/novel/sample/">Sample Novel</a></div>
                                <img src="https://1stkissnovel.org/cover.jpg">
                              </div>
                            </body></html>
                        """.trimIndent(),
                    )
                },
            )
            assertTrue(execution.exceptionOrNull()?.message ?: "FirstKissNovel execution failed", execution.isSuccess)
            val result = execution.getOrThrow()
            val items = org.json.JSONArray(result.json)
            assertEquals(1, items.length())
            assertEquals("Sample Novel", items.getJSONObject(0).getString("name"))
            assertEquals("novel/sample/", items.getJSONObject(0).getString("path"))
            assertEquals("https://1stkissnovel.org/cover.jpg", items.getJSONObject(0).getString("cover"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun exposesCurrentChikariFilterDefinitionsToTheAdapter() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)
        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "getPluginFilters",
                argumentsJson = "[]",
            ).getOrThrow()
            val payload = org.json.JSONObject(result.json)
            assertEquals(
                listOf("sort", "genres"),
                (0 until payload.getJSONArray("order").length()).map { payload.getJSONArray("order").getString(it) },
            )
            val filters = payload.getJSONObject("filters")
            assertEquals("Picker", filters.getJSONObject("sort").getString("type"))
            assertEquals("popular", filters.getJSONObject("sort").getString("value"))
            assertEquals("XCheckbox", filters.getJSONObject("genres").getString("type"))
            assertEquals(30, filters.getJSONObject("genres").getJSONArray("options").length())
        } finally {
            file.delete()
        }
    }

    @Test
    fun executesCurrentChikariPopularListingWithPickerAndGenreFilters() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)
        try {
            val options = org.json.JSONObject()
                .put("showLatestNovels", false)
                .put(
                    "filters",
                    org.json.JSONObject()
                        .put(
                            "sort",
                            org.json.JSONObject()
                                .put("type", "Picker")
                                .put("value", "trending"),
                        )
                        .put(
                            "genres",
                            org.json.JSONObject()
                                .put("type", "XCheckbox")
                                .put(
                                    "value",
                                    org.json.JSONObject()
                                        .put("include", org.json.JSONArray().put("fantasy"))
                                        .put("exclude", org.json.JSONArray().put("horror")),
                                ),
                        ),
                )
            val result = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "popularNovels",
                argumentsJson = org.json.JSONArray().put(1).put(options).toString(),
                testFetchOverride = { url ->
                    assertEquals(
                        "https://chikari.moe/api/series?type=novel&sort=trending&genre=fantasy&genre_exclude=horror&limit=60&offset=0",
                        url,
                    )
                    ExtensionHttpClient.TextResponse(
                        url,
                        200,
                        "application/json",
                        """{"items":[{"title":"Filtered Novel","slug":"filtered-novel","cover_url":"https://chikari.moe/filtered.jpg"}]}""",
                    )
                },
            ).getOrThrow()
            val items = JSONArray(result.json)
            assertEquals(1, items.length())
            assertEquals("Filtered Novel", items.getJSONObject(0).getString("name"))
            assertEquals("filtered-novel", items.getJSONObject(0).getString("path"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun executesRealChikariNovelDetailsAndChapterOperations() {
        val source = javaClass.getResourceAsStream("/lnreader/chikari.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)
        try {
            val details = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "parseNovel",
                argumentsJson = """["sample-novel"]""",
                testFetchOverride = { url ->
                    when (url) {
                        "https://chikari.moe/api/novels/sample-novel" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"title":"Sample Novel","authors":[{"name":"Test Author"}],"genres":[{"name":"Fantasy"}],"description":"A sample.","status":"releasing"}""",
                            )
                        "https://chikari.moe/api/novels/sample-novel/chapters?order=asc&limit=500&offset=0" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json",
                                """{"items":[{"title":"Chapter One","number":1,"created_at":"2026-10-01"}]}""",
                            )
                        else -> throw AssertionError("Unexpected Chikari request: $url")
                    }
                },
            ).getOrThrow()
            val novel = org.json.JSONObject(details.json)
            assertEquals("Sample Novel", novel.getString("name"))
            assertEquals("Test Author", novel.getString("author"))
            assertEquals("api/novels/sample-novel/chapters/1/read",
                novel.getJSONArray("chapters").getJSONObject(0).getString("path"))

            val chapter = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://chikari.moe"),
                operation = "parseChapter",
                argumentsJson = """["api/novels/sample-novel/chapters/1/read"]""",
                testFetchOverride = { url ->
                    assertEquals("https://chikari.moe/api/novels/sample-novel/chapters/1/read", url)
                    ExtensionHttpClient.TextResponse(
                        url, 200, "application/json",
                        """{"body":"First paragraph\nSecond paragraph"}""",
                    )
                },
            ).getOrThrow()
            assertTrue(chapter.json.contains("<p>First paragraph</p>"))
            assertTrue(chapter.json.contains("<p>Second paragraph</p>"))
        } finally {
            file.delete()
        }
    }


    @Test
    fun clampsDayjsMonthAndYearArithmeticAtMonthBoundaries() {
        val (extension, file) = extension(
            "var dayjs = require('dayjs'); " +
                "module.exports = { searchNovels: function() { " +
                "return [{name:dayjs('2024-01-31T12:00:00').add(1,'month').format('YYYY-MM-DD'), " +
                "path:dayjs('2024-02-29T12:00:00').add(1,'year').format('YYYY-MM-DD'), " +
                "diff:dayjs('2024-02-29T12:00:00').diff('2024-01-31T12:00:00','month')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals("""[{"name":"2024-02-29","path":"2025-02-28","diff":1}]""", result.json)
        } finally {
            file.delete()
        }
    }
    @Test
    fun keepsUrlHrefSynchronizedWhenSearchParamsChange() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { " +
                "var url = new URL('/search?old=1&keep=yes', 'https://example.com/'); " +
                "url.searchParams.set('page', '2'); " +
                "url.searchParams.append('tag', 'science fiction'); " +
                "url.searchParams.delete('old'); " +
                "return [{name:url.href, path:url.search, cover:url.searchParams.get('page')}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals("https://example.com/search?keep=yes&page=2&tag=science+fiction", item.getString("name"))
            assertEquals("?keep=yes&page=2&tag=science+fiction", item.getString("path"))
            assertEquals("2", item.getString("cover"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun providesWhatwgUrlGlobalForLnReaderBundles() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { " +
                "var url = new URL('/popular?page=2', 'https://example.com/catalog/index'); " +
                "return [{name:url.hostname, path:url.pathname, cover:url.searchParams.get('page'), " +
                "absolute:new URL('https://cdn.example.com/a').origin}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            assertEquals(
                """[{"name":"example.com","path":"/popular","cover":"2","absolute":"https://cdn.example.com"}]""",
                result.json,
            )
        } finally {
            file.delete()
        }
    }


    @Test
    fun supportsLnReaderCaseInsensitiveContainsSelector() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<div id=\"Icp_instance_0\"></div><span>Complete</span>'); " +
                "return [{name:$(\"#Icp_instance_0 +:icontains('complete')\").text(),path:'/faq'}]; " +
                "} };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            val actual = JSONArray(result.json)
            assertEquals(1, actual.length())
            assertEquals("Complete", actual.getJSONObject(0).getString("name"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsQueuedNestedTimersAndConsoleGlobalsUsedByPublishedBundles() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function(term) { " +
                "return new Promise(function(resolve) { " +
                "setTimeout(function() { console.warn('first yield'); " +
                "setTimeout(function() { console.info('retry complete'); " +
                "resolve([{name:term,path:'/timer-test'}]); }, 1); }, 1); " +
                "}); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension,
                "searchNovels",
                """["timer bundle"]""",
            ).getOrThrow()
            assertTrue(result.json.contains("timer bundle"))
            assertTrue(result.json.contains("/timer-test"))
        } finally {
            file.delete()
        }
    }


    @Test
    fun supportsCheerioNextUntilSelectorUsedByFaqWiki() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<p><strong>author(s):</strong></p><p>Author A</p><p>More author detail</p><p><strong>genre:</strong> Fantasy</p><p>Do not include</p>'); " +
                "var nodes = $('strong').first().parent().nextUntil('p:has(strong)'); " +
                "return [{name:nodes.map(function(i,node){return $(node).text().trim();}).get().join('|'),path:'/next-until'}]; " +
                "} };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals("Author A|More author detail", item.getString("name"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsObjectAndPairArrayUrlSearchParamsConstructors() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { " +
                "var params = new URLSearchParams({novelId:123, acode:'sample code', tag:['one','two']}); " +
                "var pairs = new URLSearchParams([['page','2'],['size',40]]); " +
                "return [{name:params.toString(),path:pairs.toString()}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals("novelId=123&acode=sample+code&tag=one&tag=two", item.getString("name"))
            assertEquals("page=2&size=40", item.getString("path"))
        } finally {
            file.delete()
        }
    }


    @Test
    fun parsesNovelHtmlLargerThanTwoMegabytesWithinBoundedLimit() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { parseNovel: function() { " +
                "var largeText = new Array(2 * 1024 * 1024 + 2048).join('x'); " +
                "var $ = cheerio.load('<html><body><h1>Large catalogue</h1><div>' + largeText + '</div></body></html>'); " +
                "return { name: $('h1').text(), chapters: [] }; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "parseNovel", "[]").getOrThrow()
            assertTrue(result.json.contains("Large catalogue"))
        } finally {
            file.delete()
        }
    }


    @Test
    fun supportsLargeHtmlParser2InputUsedByNovelPingWithinBoundedLimit() {
        val (extension, file) = extension(
            "var htmlparser2 = require('htmlparser2'); " +
                "module.exports = { parseNovel: function() { " +
                "var largeText = new Array(2 * 1024 * 1024 + 2048).join('x'); " +
                "var title = ''; " +
                "var parser = new htmlparser2.Parser({onopentag:function(name){}, ontext:function(text){ if (!title && text.indexOf('Large catalogue') >= 0) title = 'Large catalogue'; }}); " +
                "parser.write('<h1>Large catalogue</h1><div>' + largeText + '</div>'); " +
                "parser.end(); " +
                "return { name: title, chapters: [] }; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "parseNovel", "[]").getOrThrow()
            assertTrue(result.json.contains("Large catalogue"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsBase64BufferTextEncodingAbortControllerIntlAndEs2020Globals() {
        val (extension, file) = extension(
            "module.exports = { searchNovels: function() { " +
                "var b64 = btoa('QuickNovel'); " +
                "var decoded = atob(b64); " +
                "var buf = Buffer.from('Hello', 'utf8'); " +
                "var bytes = new TextEncoder().encode('Hi!'); " +
                "var txt = new TextDecoder().decode(bytes); " +
                "var ac = new AbortController(); ac.abort(); " +
                "var dtf = new Intl.DateTimeFormat('en-US'); " +
                "var replaced = 'a-b-c'.replaceAll('-', '_'); " +
                "var matches = Array.from('a1b2'.matchAll(/\\d/g)).map(function(m){return m[0];}).join(''); " +
                "var flat = [[1,[2]],3].flat(2).at(-1); " +
                "return [{decoded:decoded,hex:buf.toString('hex'),b64Buf:buf.toString('base64'),txt:txt," +
                "aborted:ac.signal.aborted,dtfOk:typeof dtf.format(new Date(0))==='string'," +
                "replaced:replaced,matches:matches,flat:flat,hasGlobal:globalThis===this}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals("QuickNovel", item.getString("decoded"))
            assertEquals("48656c6c6f", item.getString("hex"))
            assertEquals("SGVsbG8=", item.getString("b64Buf"))
            assertEquals("Hi!", item.getString("txt"))
            assertEquals(true, item.getBoolean("aborted"))
            assertEquals(true, item.getBoolean("dtfOk"))
            assertEquals("a_b_c", item.getString("replaced"))
            assertEquals("12", item.getString("matches"))
            assertEquals(3, item.getInt("flat"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsHeadRequestsUrlSearchParamsPostAndBinaryArrayBufferFetch() {
        val (extension, file) = extension(
            "var fetchApi = require('@libs/fetch').fetchApi; " +
                "module.exports = { searchNovels: function() { " +
                "var headP = fetchApi('https://example.com/ping', {method:'HEAD'}).then(function(r){return r.status;}); " +
                "var params = new URLSearchParams({q:'hello world',page:2}); " +
                "var formP = fetchApi('https://example.com/form', {method:'POST',body:params}).then(function(r){return r.text();}); " +
                "var binP = fetchApi('https://example.com/bin', {method:'POST',body:new Uint8Array([1,2,3,4])})" +
                ".then(function(r){return r.arrayBuffer();}).then(function(buf){return new Uint8Array(buf).length;}); " +
                "return Promise.all([headP, formP, binP]).then(function(res){return [{head:res[0],form:res[1],binLen:res[2]}];}); } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension,
                operation = "searchNovels",
                argumentsJson = "[]",
                testRequestOverride = { url, method, headers, body ->
                    when (url) {
                        "https://example.com/ping" -> {
                            assertEquals("HEAD", method)
                            ExtensionHttpClient.TextResponse(url, 204, "text/plain", "")
                        }
                        "https://example.com/form" -> {
                            assertEquals("POST", method)
                            assertEquals("application/x-www-form-urlencoded;charset=UTF-8", headers["Content-Type"])
                            assertEquals("q=hello+world&page=2", body)
                            ExtensionHttpClient.TextResponse(url, 200, "text/plain", "form-ok")
                        }
                        "https://example.com/bin" -> {
                            assertEquals("POST", method)
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/octet-stream", "",
                                bodyBytes = byteArrayOf(9, 8, 7, 6, 5),
                            )
                        }
                        else -> throw AssertionError("Unexpected URL: $url")
                    }
                },
            ).getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals(204, item.getInt("head"))
            assertEquals("form-ok", item.getString("form"))
            assertEquals(5, item.getInt("binLen"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsExtendedCheerioTraversalMutationAndXmlMode() {
        val (extension, file) = extension(
            "var cheerio = require('cheerio'); " +
                "module.exports = { searchNovels: function() { " +
                "var $ = cheerio.load('<section class=\"wrap\"><div id=\"a\" data-info=\"42\"><h2 class=\"t\">Title<span>(Extra)</span></h2><p class=\"p1\">One</p><p class=\"p2\">Two</p><p class=\"p3\">Three</p></div></section>'); " +
                "var cleanTitle = $('h2.t').clone().children().remove().end().text().trim(); " +
                "var clonedTreeCount = $('.p1').parents('section').length; " +
                "var closestId = $('.p2').closest('div').attr('id'); " +
                "var isP2 = $('.p2').is('.p2'); " +
                "var notCount = $('#a p').not('.p2').length; " +
                "var slicedText = $('#a p').slice(1, 3).map(function(){return $(this).text();}).get().join(','); " +
                "var nextAllCount = $('.p1').nextAll('p').length; " +
                "var prevAllCount = $('.p3').prevAll('p').length; " +
                "var siblingsCount = $('.p2').siblings('p').length; " +
                "var dataVal = $('#a').data('info'); " +
                "$('.p1').addClass('active').removeClass('p1').html('<em>Updated</em>'); " +
                "var createdText = $('<span>Fresh</span>').text(); " +
                "var xml$ = cheerio.load('<feed><entry><title>XML Item</title></entry></feed>', {xmlMode:true}); " +
                "return [{cleanTitle:cleanTitle,parents:clonedTreeCount,closestId:closestId,isP2:isP2," +
                "notCount:notCount,slicedText:slicedText,nextAllCount:nextAllCount,prevAllCount:prevAllCount," +
                "siblingsCount:siblingsCount,dataVal:dataVal,hasActive:$('.p1').length===0 && $('.active').length===1," +
                "outer:$.html('.active'),createdText:createdText,xmlTitle:xml$('entry > title').text()}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals("Title", item.getString("cleanTitle"))
            assertEquals(1, item.getInt("parents"))
            assertEquals("a", item.getString("closestId"))
            assertEquals(true, item.getBoolean("isP2"))
            assertEquals(2, item.getInt("notCount"))
            assertEquals("Two,Three", item.getString("slicedText"))
            assertEquals(2, item.getInt("nextAllCount"))
            assertEquals(2, item.getInt("prevAllCount"))
            assertEquals(2, item.getInt("siblingsCount"))
            assertEquals("42", item.getString("dataVal"))
            assertEquals(true, item.getBoolean("hasActive"))
            assertTrue(item.getString("outer").contains("<em>Updated</em>"))
            assertEquals("Fresh", item.getString("createdText"))
            assertEquals("XML Item", item.getString("xmlTitle"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsHtmlparser2OnAttributeOnOpenTagNameOnEndAndDayjsFormats() {
        val (extension, file) = extension(
            "var Parser = require('htmlparser2').Parser; " +
                "var dayjs = require('dayjs'); " +
                "dayjs.extend(function(){}); " +
                "module.exports = { searchNovels: function() { " +
                "var seenTags = [], seenAttrs = [], ended = false; " +
                "var parser = new Parser({" +
                "onopentagname:function(n){seenTags.push(n);}," +
                "onattribute:function(k,v){seenAttrs.push(k+'='+v);}," +
                "onend:function(){ended=true;}" +
                "}); parser.write('<div data-id=\"7\">Hello</div>'); parser.end(); " +
                "localStorage.setItem('gkey','gval'); " +
                "var d1 = dayjs('14/08/2026', 'DD/MM/YYYY').format('YYYY-MM-DD'); " +
                "var d2 = dayjs('08/14/2026', 'MM/DD/YYYY').format('YYYY-MM-DD'); " +
                "var rel = dayjs('3 days ago'); " +
                "return [{tags:seenTags.join(','),attrs:seenAttrs.join(','),ended:ended," +
                "gval:localStorage.getItem('gkey'),d1:d1,d2:d2,relValid:rel.isValid(),fn:dayjs().fromNow()}]; } };"
        )
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[]").getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertTrue(item.getString("tags").contains("div"))
            assertTrue(item.getString("attrs").contains("data-id=7"))
            assertEquals(true, item.getBoolean("ended"))
            assertEquals("gval", item.getString("gval"))
            assertEquals("2026-08-14", item.getString("d1"))
            assertEquals("2026-08-14", item.getString("d2"))
            assertEquals(true, item.getBoolean("relValid"))
            assertTrue(item.getString("fn").isNotEmpty())
        } finally {
            ExtensionJsRuntime.clearSessionStorage(extension.id)
            file.delete()
        }
    }

    @Test
    fun normalizesFenrirEs6UnicodeRegexDivisionAndTemplateLiterals() {
        val fenrirSnippet = """
            var invisibleRx = /[\u200B-\u200D\uFEFF\u200E\u200F\u202A-\u202E\u{E0000}-\u{E007F}\u{E0100}-\u{E01EF}]/gu;
            var dynamicRx = new RegExp('foo\\s+bar', 'giu');
            var division = 100 / 5 / 2;
            var literalStr = '/[\\u{E0000}]/gu';
            module.exports = {
              searchNovels: function(q) {
                var cleaned = 'Hello\u200B\uDB40\uDC20World\uDB40\uDD01!'.replace(invisibleRx, '');
                var tpl = 'div=' + division + ';match=' + dynamicRx.test('FOO   BAR') + ';str=' + literalStr;
                return [{ name: cleaned, path: tpl }];
              }
            };
        """.trimIndent()
        val (extension, file) = extension(fenrirSnippet)
        try {
            val result = ExtensionJsRuntime.execute(extension, "searchNovels", "[\"test\"]").getOrThrow()
            val item = JSONArray(result.json).getJSONObject(0)
            assertEquals("HelloWorld!", item.getString("name"))
            assertEquals("div=10;match=true;str=/[\\u{E0000}]/gu", item.getString("path"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsFetchViaWebviewBridgeAndEnforcesApprovedHosts() {
        val pluginCode = """
            var fetchLib = require('@libs/fetch');
            module.exports = {
              parseChapter: function(path) {
                return fetchLib.fetchViaWebview('https://fenrirrealm.com/' + path, {
                  waitForSelector: '.chapter-content',
                  timeoutMs: 12000,
                  settleMs: 350
                }).then(function(res) {
                  return res && res.ok ? res.html : '';
                });
              },
              searchNovels: function() {
                var unapprovedHost = 'unapproved.invalid';
                return fetchLib.fetchViaWebview('https://' + unapprovedHost + '/page', {});
              }
            };
        """.trimIndent()
        val (extension, file) = extension(pluginCode)
        try {
            val chapter = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://fenrirrealm.com"),
                operation = "parseChapter",
                argumentsJson = """["series/test/chapter-1"]""",
                testWebviewOverride = { url, selector, timeoutMs, settleMs ->
                    assertEquals("https://fenrirrealm.com/series/test/chapter-1", url)
                    assertEquals(".chapter-content", selector)
                    assertEquals(12000L, timeoutMs)
                    assertEquals(350L, settleMs)
                    "<div class=\"chapter-content\"><p>Loaded via WebView</p></div>"
                },
            ).getOrThrow()
            assertTrue(chapter.json.contains("Loaded via WebView"))

            val blocked = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://fenrirrealm.com"),
                operation = "searchNovels",
                argumentsJson = "[]",
                testWebviewOverride = { _, _, _, _ -> "<p>should not reach</p>" },
            ).getOrThrow()
            assertTrue(blocked.json.contains("\"ok\":false"))
            assertTrue(blocked.json.contains("outside the approved extension site"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun supportsWtrLabWebModeAesDecryptionWithManyNextChunksAndGoogleTranslate() {
        val source = javaClass.getResourceAsStream("/lnreader/wtrlab.js")!!
            .bufferedReader()
            .use { it.readText() }
        val (extension, file) = extension(source)

        // Encrypt chapter JSON lines with the exact AES-256-GCM envelope WTR-Lab v1.2.6 decodes.
        val aesKey = "0123456789abcdef0123456789abcdef"
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plaintext = """["Paragraph one.","Paragraph two."]""".toByteArray(Charsets.UTF_8)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(aesKey.toByteArray(Charsets.UTF_8), "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv),
        )
        val sealed = cipher.doFinal(plaintext)
        val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
        val bodyBytes = sealed.copyOfRange(0, sealed.size - 16)
        val b64 = java.util.Base64.getEncoder()
        val encryptedEnvelope = "arr:" +
            b64.encodeToString(iv) + ":" +
            b64.encodeToString(tag) + ":" +
            b64.encodeToString(bodyBytes)

        val scriptTags = (1..50).joinToString("") { idx ->
            """<script src="/_next/static/chunks/chunk-$idx.js"></script>"""
        }
        val chapterHtml = "<html><head>$scriptTags</head><body></body></html>"

        try {
            val result = ExtensionJsRuntime.execute(
                extension = extension.copy(site = "https://wtr-lab.com/"),
                operation = "parseChapter",
                argumentsJson = """["en/serie-42/test-novel/chapter-25"]""",
                testRequestOverride = { url, method, _, _ ->
                    when {
                        url == "https://wtr-lab.com/api/reader/get" -> {
                            assertEquals("POST", method)
                            ExtensionHttpClient.TextResponse(
                                url = url,
                                statusCode = 200,
                                contentType = "application/json",
                                body = """{"success":true,"data":{"data":{"title":"Chapter 25","body":${org.json.JSONObject.quote(encryptedEnvelope)}}}}""",
                            )
                        }
                        url == "https://wtr-lab.com/en/serie-42/test-novel/chapter-25" ->
                            ExtensionHttpClient.TextResponse(url, 200, "text/html", chapterHtml)
                        url.contains("/_next/static/chunks/chunk-45.js") ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/javascript",
                                """var k=new TextEncoder().encode("$aesKey");""",
                            )
                        url.contains("/_next/static/chunks/") ->
                            ExtensionHttpClient.TextResponse(url, 200, "application/javascript", "/* unrelated chunk */")
                        url == "https://wtr-lab.com/api/auth/get-session" ->
                            ExtensionHttpClient.TextResponse(url, 200, "application/json", "{}")
                        url == "https://translate-pa.googleapis.com/v1/translateHtml" ->
                            ExtensionHttpClient.TextResponse(
                                url, 200, "application/json+protobuf",
                                """[["<a i=0>Translated paragraph one.</a>","<a i=1>Translated paragraph two.</a>"]]""",
                            )
                        else -> throw AssertionError("Unexpected WTR-Lab request: $url")
                    }
                },
            ).getOrThrow()

            val htmlOut = org.json.JSONTokener(result.json).nextValue() as String
            assertTrue(htmlOut.contains("Chapter 25"))
            assertTrue(htmlOut.contains("Translated paragraph one."))
            assertTrue(htmlOut.contains("Translated paragraph two."))
        } finally {
            ExtensionJsRuntime.clearSessionStorage(extension.id)
            file.delete()
        }
    }

}
