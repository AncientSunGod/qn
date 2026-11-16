package com.lagradost.quicknovel.extensions

import android.content.Context as AndroidContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Comment
import org.jsoup.parser.Parser
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Partial CommonJS-style JavaScript runtime for LNReader extension bundles.
 *
 * This is an in-process compatibility layer, not a security sandbox. It exposes a narrow,
 * bounded host bridge for networking and HTML selection; unknown imports fail closed rather
 * than being silently replaced with fake implementations. Arbitrary third-party bundles
 * should not be treated as safe until execution is moved to a genuinely isolated runtime.
 */
object ExtensionJsRuntime {
    private val sessionStorage = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    internal fun clearSessionStorage(extensionId: String) {
        sessionStorage.remove(extensionId)
    }

    internal fun clearExtensionData(context: AndroidContext, extensionId: String) {
        clearSessionStorage(extensionId)
        context.getSharedPreferences(
            "quicknovel_extension_storage_$extensionId",
            AndroidContext.MODE_PRIVATE,
        ).edit().clear().apply()
    }

    private fun jsByteArray(value: Any?): ByteArray {
        val array = value as? Scriptable
            ?: throw IllegalArgumentException("AES input must be a byte array.")
        val length = (ScriptableObject.getProperty(array, "length") as? Number)?.toInt()
            ?: throw IllegalArgumentException("AES input byte array has no length.")
        require(length in 0..(2 * 1024 * 1024)) { "AES input exceeds the 2 MB limit." }
        return ByteArray(length) { index ->
            (Context.toNumber(ScriptableObject.getProperty(array, index)).toInt() and 0xff).toByte()
        }
    }

    private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
    private const val MAX_ARGUMENT_BYTES = 256 * 1024
    private const val MAX_RESULT_BYTES = 8 * 1024 * 1024
    // Timers in published LNReader bundles are used for retry backoff and cooperative yields.
    // The bridge executes synchronously, so callbacks run inline after a bounded delay.
    private const val MAX_TIMER_DELAY_MS = 10_000L
    private const val MAX_TOTAL_TIMER_DELAY_MS = 30_000L
    // Parsing novel metadata commonly walks a large chapter catalogue. A single
    // global 2M budget was too small for otherwise valid LNReader plugins and caused
    // parseNovel to fail even when the network response and selectors were valid.
    // Keep operation-specific ceilings so catalogue parsing has room without removing
    // the runaway-script guard from cheaper operations.
    private const val MAX_DEFAULT_INSTRUCTIONS = 4_000_000
    private const val MAX_PARSE_NOVEL_INSTRUCTIONS = 24_000_000
    private const val MAX_PARSE_CHAPTER_INSTRUCTIONS = 12_000_000
    private const val MAX_LIST_INSTRUCTIONS = 8_000_000
    private const val OBSERVER_THRESHOLD = 10_000
    private val allowedOperations = setOf(
        "searchNovels",
        "parseNovel",
        "parseChapter",
        "popularNovels",
        "parsePage",
        "resolveUrl",
        "getPluginSettings",
        "setPluginSetting",
        "getPluginFilters",
        "transformChapterHtml",
        "getImageRequestInit",
    )

    data class ExecutionResult(val json: String)

    internal fun execute(
        extension: InstalledExtension,
        operation: String,
        argumentsJson: String,
        testFetchOverride: ((String) -> ExtensionHttpClient.TextResponse)? = null,
        storageContext: AndroidContext? = null,
        testRequestOverride: ((String, String, Map<String, String>, String?) -> ExtensionHttpClient.TextResponse)? = null,
        customScript: String? = null,
    ): Result<ExecutionResult> = runCatching {
        require(operation in allowedOperations) { "Unsupported extension operation." }
        if (operation == "transformChapterHtml") {
            require(customScript != null) { "Custom JavaScript content is missing." }
            require(customScript.toByteArray(Charsets.UTF_8).size <= 512 * 1024) {
                "Custom JavaScript exceeds the 512 KB limit."
            }
        }
        require(argumentsJson.toByteArray(Charsets.UTF_8).size <= MAX_ARGUMENT_BYTES) {
            "Extension arguments exceed the size limit."
        }
        val arguments = JSONArray(argumentsJson)
        val sourceFile = File(extension.bundlePath)
        require(sourceFile.isFile) { "Extension bundle is missing." }
        require(sourceFile.length() in 1..MAX_SOURCE_BYTES.toLong()) {
            "Extension bundle is empty or exceeds the 8 MB limit."
        }
        val source = sourceFile.readText(Charsets.UTF_8)
        require(source.isNotBlank()) { "Extension bundle is empty." }

        val instructionLimit = when (operation) {
            "parseNovel" -> MAX_PARSE_NOVEL_INSTRUCTIONS
            "parseChapter" -> MAX_PARSE_CHAPTER_INSTRUCTIONS
            "searchNovels", "popularNovels", "parsePage" -> MAX_LIST_INSTRUCTIONS
            else -> MAX_DEFAULT_INSTRUCTIONS
        }
        val factory = BudgetedContextFactory(instructionLimit, operation)
        val output = factory.call { cx ->
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            cx.instructionObserverThreshold = OBSERVER_THRESHOLD
            cx.setClassShutter { false }

            val scope = cx.initSafeStandardObjects()
            ScriptableObject.putProperty(scope, "module", cx.newObject(scope).apply {
                put("exports", this, cx.newObject(scope))
            })
            ScriptableObject.putProperty(scope, "exports", ScriptableObject.getProperty(
                ScriptableObject.getProperty(scope, "module") as Scriptable,
                "exports"
            ))
            val approvedHosts = approvedHostsForBundle(extension.site, source)
            var fetchCount = 0
            val fetchFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    fetchCount += 1
                    val requestLimit = if (operation == "parseNovel") 80 else 20
                    require(fetchCount <= requestLimit) {
                        "Extension exceeded the $requestLimit-request operation limit during $operation."
                    }
                    val requestedUrl = args.firstOrNull()?.toString().orEmpty()
                    require(requestedUrl.isNotBlank()) { "Extension fetch URL is missing." }
                    val init = runCatching {
                        JSONObject(args.getOrNull(1)?.toString().orEmpty())
                    }.getOrDefault(JSONObject())
                    val method = init.optString("method", "GET").uppercase(java.util.Locale.ROOT)
                    require(method == "GET" || method == "POST" || method == "HEAD") {
                        "Extension requests currently support GET and POST (or HEAD) only."
                    }
                    val headerJson = init.optJSONObject("headers") ?: JSONObject()
                    val requestHeaders = buildMap {
                        val keys = headerJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, headerJson.optString(key, ""))
                        }
                    }
                    val bodyBase64 = init.optString("bodyBase64", "").takeIf { it.isNotEmpty() }
                    val requestBodyBytes = bodyBase64?.let(::decodeBase64)
                    val bodyValue = init.opt("body")
                    val requestBody = when {
                        requestBodyBytes != null -> null
                        bodyValue == null || bodyValue === JSONObject.NULL -> null
                        bodyValue is String -> bodyValue
                        else -> throw IllegalArgumentException(
                            "Extension request bodies must be strings; multipart and binary bodies are not supported."
                        )
                    }
                    val responseEncoding = args.getOrNull(2)?.toString()
                        ?.takeIf { it.isNotBlank() && it != "undefined" && it != "null" }
                    val binaryBase64 = args.getOrNull(3)?.toString() == "base64"
                    require(!binaryBase64 || method == "GET") {
                        "Binary fetchFile currently supports GET requests only."
                    }
                    require((method != "GET" && method != "HEAD") || (requestBody == null && requestBodyBytes == null)) {
                        "$method requests may not include a request body."
                    }
                    require((requestBody?.toByteArray(Charsets.UTF_8)?.size ?: requestBodyBytes?.size ?: 0) <= 256 * 1024) {
                        "Extension request body exceeds the 256 KB limit."
                    }
                    val response = testRequestOverride?.invoke(
                        requestedUrl, method, requestHeaders, requestBody
                    ) ?: testFetchOverride?.invoke(requestedUrl) ?: ExtensionHttpClient.requestText(
                        rawUrl = requestedUrl,
                        method = method,
                        body = requestBody,
                        headers = requestHeaders,
                        allowedHosts = approvedHosts,
                        responseEncoding = responseEncoding,
                        bodyBytes = requestBodyBytes,
                    ).getOrThrow()
                    val responseJson = JSONObject()
                        .put("url", response.url)
                        .put("statusCode", response.statusCode)
                        .put("statusText", response.statusText.ifBlank { response.statusCode.toString() })
                        .put("contentType", response.contentType)
                        .put("headers", JSONObject(response.headers))
                    val includeBinaryBase64 = binaryBase64 || (
                        response.bodyBytes.size <= 2 * 1024 * 1024 && (
                            response.contentType?.contains("grpc", ignoreCase = true) == true ||
                            response.contentType?.contains("protobuf", ignoreCase = true) == true ||
                            response.contentType?.contains("octet-stream", ignoreCase = true) == true ||
                            response.contentType?.startsWith("image/", ignoreCase = true) == true
                        )
                    )
                    if (binaryBase64) {
                        require(response.bodyBytes.size <= 1024 * 1024) {
                            "Extension fetchFile response exceeds the 1 MB binary limit."
                        }
                        responseJson.put("bodyBase64", encodeBase64(response.bodyBytes))
                    } else {
                        responseJson.put("body", response.body)
                        if (includeBinaryBase64) {
                            responseJson.put("bodyBase64", encodeBase64(response.bodyBytes))
                        }
                    }
                    return responseJson.toString()
                }
            }
            fetchFunction.parentScope = scope
            fetchFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnFetch", fetchFunction)
            var accumulatedTimerDelayMs = 0L
            val timerDelayFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val requestedDelayMs = when (val value = args.firstOrNull()) {
                        is Number -> value.toLong()
                        null -> 0L
                        else -> value.toString().toLongOrNull() ?: 0L
                    }
                    require(requestedDelayMs in 0..MAX_TIMER_DELAY_MS) {
                        "Extension timer delay exceeds the 10 second limit."
                    }
                    accumulatedTimerDelayMs += requestedDelayMs
                    require(accumulatedTimerDelayMs <= MAX_TOTAL_TIMER_DELAY_MS) {
                        "Extension exceeded the 30 second timer budget for one operation."
                    }
                    if (requestedDelayMs > 0L) {
                        try {
                            Thread.sleep(requestedDelayMs)
                        } catch (error: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IllegalStateException("Extension timer was interrupted.", error)
                        }
                    }
                    return 0
                }
            }
            timerDelayFunction.parentScope = scope
            timerDelayFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnDelay", timerDelayFunction)
            val htmlBridge = HtmlBridge(extension.site)
            val htmlFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val command = args.getOrNull(0)?.toString().orEmpty()
                    val selectionIdArg = args.getOrNull(1)
                    val selectionId = (selectionIdArg as? Number)?.toInt()
                        ?: selectionIdArg?.toString()?.toIntOrNull()
                        ?: 0
                    val value = args.getOrNull(2)?.toString()
                    val indexArg = args.getOrNull(3)
                    val index = (indexArg as? Number)?.toInt()
                        ?: indexArg?.toString()?.toIntOrNull()
                    return htmlBridge.invoke(command, selectionId, value, index)
                }
            }
            htmlFunction.parentScope = scope
            htmlFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnHtml", htmlFunction)
            val htmlEventsFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val xmlMode = args.getOrNull(1) == true || args.getOrNull(1)?.toString() == "true"
                    return htmlBridge.parseEvents(args.firstOrNull()?.toString().orEmpty(), xmlMode)
                }
            }
            htmlEventsFunction.parentScope = scope
            htmlEventsFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnHtmlEvents", htmlEventsFunction)
            val storageFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val command = args.getOrNull(0)?.toString().orEmpty()
                    val key = args.getOrNull(1)?.toString().orEmpty()
                    val rawValue = args.getOrNull(2)?.toString().orEmpty()
                    val expires = args.getOrNull(3)?.toString()?.toLongOrNull()
                    val raw = args.getOrNull(4)?.toString().equals("true", ignoreCase = true)
                    val area = args.getOrNull(5)?.toString().orEmpty()
                    require(area.isEmpty() || area == "local" || area == "session") {
                        "Unsupported extension storage area."
                    }
                    // localStorage and the @libs/storage helper persist across app restarts.
                    // sessionStorage is isolated per extension and kept only in this process.
                    // Unit tests and non-Android callers may omit a Context. Keep their
                    // storage in the same per-extension in-memory area rather than failing
                    // during module initialization; production providers pass app context.
                    val sessionValues = if (area == "session" || storageContext == null) {
                        sessionStorage.computeIfAbsent(extension.id) { ConcurrentHashMap() }
                    } else null
                    val prefs = if (sessionValues == null) {
                        storageContext?.getSharedPreferences(
                            "quicknovel_extension_storage_${extension.id}",
                            AndroidContext.MODE_PRIVATE,
                        ) ?: throw IllegalStateException("Extension storage requires an app context.")
                    } else null
                    val prefix = if (area == "local") "local_" else "entry_"
                    val output = when (command) {
                        "set" -> {
                            require(key.length <= 500) { "Extension storage key is too long." }
                            require(rawValue.length <= 100_000) { "Extension storage value is too large." }
                            val storedValue = runCatching { org.json.JSONTokener(rawValue).nextValue() }.getOrNull()
                            val envelope = JSONObject()
                                .put("created", System.currentTimeMillis())
                                .put("value", storedValue)
                            if (expires != null) envelope.put("expires", expires)
                            val existingKeys = sessionValues?.keys
                                ?: prefs!!.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
                            require(key in existingKeys || existingKeys.size < 500) {
                                "Extension storage exceeded the 500-key limit."
                            }
                            val currentSize = sessionValues?.values?.sumOf { it.length }
                                ?: prefs!!.all.entries.filter { it.key.startsWith(prefix) }
                                    .sumOf { it.value.toString().length }
                            val previousSize = if (sessionValues != null) {
                                sessionValues[key]?.length ?: 0
                            } else {
                                prefs!!.getString(prefix + key, null)?.length ?: 0
                            }
                            require(currentSize - previousSize + envelope.toString().length <= 2_097_152) {
                                "Extension storage exceeded the 2 MB limit."
                            }
                            if (sessionValues != null) sessionValues[key] = envelope.toString()
                            else prefs!!.edit().putString(prefix + key, envelope.toString()).apply()
                            "null"
                        }
                        "get" -> {
                            require(key.length <= 500) { "Extension storage key is too long." }
                            val stored = sessionValues?.get(key) ?: prefs?.getString(prefix + key, null)
                            if (stored == null) "null" else {
                                val envelope = JSONObject(stored)
                                val expiry = envelope.optLong("expires", 0L)
                                if (expiry > 0L && System.currentTimeMillis() >= expiry) {
                                    if (sessionValues != null) sessionValues.remove(key)
                                    else prefs!!.edit().remove(prefix + key).apply()
                                    "null"
                                } else if (raw) envelope.toString()
                                else {
                                    val value = envelope.opt("value")
                                    if (value == null) "null"
                                    else if (value is JSONObject || value is JSONArray) value.toString()
                                    else if (value is String) JSONObject.quote(value)
                                    else value.toString()
                                }
                            }
                        }
                        "delete" -> {
                            if (sessionValues != null) sessionValues.remove(key)
                            else prefs!!.edit().remove(prefix + key).apply()
                            "null"
                        }
                        "clearAll" -> {
                            if (sessionValues != null) sessionValues.clear()
                            else prefs!!.all.keys.filter { it.startsWith(prefix) }
                                .forEach { prefs!!.edit().remove(it).apply() }
                            "null"
                        }
                        "getAllKeys" -> {
                            val keys = sessionValues?.keys?.toList()
                                ?: prefs!!.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
                            JSONArray(keys).toString()
                        }
                        else -> throw IllegalArgumentException("Unsupported extension storage operation.")
                    }
                    return output
                }
            }
            storageFunction.parentScope = scope
            storageFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnStorage", storageFunction)
            val aesFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    callScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any {
                    val mode = args.getOrNull(0)?.toString().orEmpty()
                    require(mode == "encrypt" || mode == "decrypt") { "Unsupported AES-GCM operation." }
                    val key = jsByteArray(args.getOrNull(1))
                    val nonce = jsByteArray(args.getOrNull(2))
                    val aadValue = args.getOrNull(3)
                    val aad = if (aadValue == null || aadValue.toString() == "null") {
                        null
                    } else {
                        jsByteArray(aadValue)
                    }
                    val input = jsByteArray(args.getOrNull(4))
                    require(key.size == 16 || key.size == 24 || key.size == 32) {
                        "AES-GCM key must be 128, 192, or 256 bits."
                    }
                    require(nonce.isNotEmpty() && nonce.size <= 32) { "AES-GCM nonce length is invalid." }
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(
                        if (mode == "encrypt") Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(128, nonce),
                    )
                    if (aad != null) cipher.updateAAD(aad)
                    val output = cipher.doFinal(input)
                    return context.newArray(
                        callScope,
                        output.map { (it.toInt() and 0xff) as Any }.toTypedArray(),
                    )
                }
            }
            aesFunction.parentScope = scope
            aesFunction.prototype = ScriptableObject.getFunctionPrototype(scope)
            ScriptableObject.putProperty(scope, "__qnAesGcm", aesFunction)
            ScriptableObject.putProperty(scope, "__qnTimeZone", java.util.TimeZone.getDefault().id)
            val compatibilityBootstrap = """
                (function() {
                  if (typeof this.globalThis === 'undefined') this.globalThis = this;
                  if (!String.prototype.trimStart) {
                    String.prototype.trimStart = function() { return String(this).replace(/^\s+/, ''); };
                  }
                  if (!String.prototype.trimEnd) {
                    String.prototype.trimEnd = function() { return String(this).replace(/\s+$/, ''); };
                  }
                  if (!String.prototype.replaceAll) {
                    String.prototype.replaceAll = function(search, replacement) {
                      var target = String(this);
                      if (search instanceof RegExp) {
                        if (!search.global) throw new TypeError('String.prototype.replaceAll called with a non-global RegExp argument');
                        return target.replace(search, replacement);
                      }
                      var needle = String(search);
                      if (typeof replacement === 'function') {
                        return target.split(needle).map(function(part, i, arr) {
                          return i === 0 ? part : replacement(needle) + part;
                        }).join('');
                      }
                      return target.split(needle).join(String(replacement));
                    };
                  }
                  if (!String.prototype.matchAll) {
                    String.prototype.matchAll = function(regex) {
                      var flags = regex instanceof RegExp ? regex.flags : 'g';
                      if (flags.indexOf('g') === -1) throw new TypeError('String.prototype.matchAll called with a non-global RegExp argument');
                      var rx = new RegExp(regex instanceof RegExp ? regex.source : String(regex), flags);
                      var str = String(this), results = [], m;
                      while ((m = rx.exec(str)) !== null) {
                        results.push(m);
                        if (m[0] === '') rx.lastIndex++;
                      }
                      var idx = 0;
                      var iter = {
                        next: function() { return idx < results.length ? { value: results[idx++], done: false } : { value: undefined, done: true }; }
                      };
                      if (typeof Symbol !== 'undefined' && Symbol.iterator) {
                        iter[Symbol.iterator] = function() { return iter; };
                      }
                      return iter;
                    };
                  }
                  if (!Array.prototype.flat) {
                    Array.prototype.flat = function(depth) {
                      var d = depth === undefined ? 1 : Number(depth);
                      var out = [];
                      (function flatten(arr, curDepth) {
                        for (var i = 0; i < arr.length; i++) {
                          if (i in arr) {
                            if (Array.isArray(arr[i]) && curDepth > 0) flatten(arr[i], curDepth - 1);
                            else out.push(arr[i]);
                          }
                        }
                      })(this, d);
                      return out;
                    };
                  }
                  if (!Array.prototype.flatMap) {
                    Array.prototype.flatMap = function(fn, thisArg) {
                      return Array.prototype.map.call(this, fn, thisArg).flat(1);
                    };
                  }
                  if (!Array.prototype.at) {
                    Array.prototype.at = function(idx) {
                      var n = Math.trunc(Number(idx)) || 0;
                      if (n < 0) n += this.length;
                      return (n < 0 || n >= this.length) ? undefined : this[n];
                    };
                  }
                  if (!String.prototype.at) {
                    String.prototype.at = function(idx) {
                      var n = Math.trunc(Number(idx)) || 0;
                      if (n < 0) n += this.length;
                      return (n < 0 || n >= this.length) ? undefined : this.charAt(n);
                    };
                  }
                  if (!Object.fromEntries) {
                    Object.fromEntries = function(entries) {
                      var obj = {};
                      if (entries && typeof entries.forEach === 'function') {
                        entries.forEach(function(pair) { if (pair) obj[pair[0]] = pair[1]; });
                      } else if (entries && typeof entries[Symbol.iterator] === 'function') {
                        var it = entries[Symbol.iterator](), step;
                        while (!(step = it.next()).done) {
                          if (step.value) obj[step.value[0]] = step.value[1];
                        }
                      }
                      return obj;
                    };
                  }

                  var qnB64Chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
                  function qnBytesToBase64(bytes) {
                    var out = '';
                    for (var i = 0; i < bytes.length; i += 3) {
                      var b0 = bytes[i] & 255;
                      var has1 = i + 1 < bytes.length, has2 = i + 2 < bytes.length;
                      var b1 = has1 ? (bytes[i + 1] & 255) : 0;
                      var b2 = has2 ? (bytes[i + 2] & 255) : 0;
                      out += qnB64Chars.charAt(b0 >> 2) +
                        qnB64Chars.charAt(((b0 & 3) << 4) | (b1 >> 4)) +
                        (has1 ? qnB64Chars.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=') +
                        (has2 ? qnB64Chars.charAt(b2 & 63) : '=');
                    }
                    return out;
                  }
                  function qnBase64ToBytes(b64) {
                    var clean = String(b64 == null ? '' : b64).replace(/[\r\n\s]/g, '');
                    var out = [];
                    for (var i = 0; i < clean.length; i += 4) {
                      var c0 = qnB64Chars.indexOf(clean.charAt(i));
                      var c1 = qnB64Chars.indexOf(clean.charAt(i + 1));
                      var ch2 = clean.charAt(i + 2), ch3 = clean.charAt(i + 3);
                      var c2 = ch2 === '=' ? -1 : qnB64Chars.indexOf(ch2);
                      var c3 = ch3 === '=' ? -1 : qnB64Chars.indexOf(ch3);
                      if (c0 < 0 || c1 < 0) break;
                      out.push((c0 << 2) | (c1 >> 4));
                      if (c2 >= 0) out.push(((c1 & 15) << 4) | (c2 >> 2));
                      if (c3 >= 0) out.push(((c2 & 3) << 6) | c3);
                    }
                    return new Uint8Array(out);
                  }
                  if (typeof this.atob === 'undefined') {
                    this.atob = function(str) {
                      var bytes = qnBase64ToBytes(str), res = '';
                      for (var i = 0; i < bytes.length; i++) res += String.fromCharCode(bytes[i]);
                      return res;
                    };
                  }
                  if (typeof this.btoa === 'undefined') {
                    this.btoa = function(str) {
                      var s = String(str), bytes = new Uint8Array(s.length);
                      for (var i = 0; i < s.length; i++) bytes[i] = s.charCodeAt(i) & 255;
                      return qnBytesToBase64(bytes);
                    };
                  }
                  if (typeof this.TextEncoder === 'undefined') {
                    function QNTextEncoder() { this.encoding = 'utf-8'; }
                    QNTextEncoder.prototype.encode = function(str) {
                      var encoded = unescape(encodeURIComponent(String(str == null ? '' : str)));
                      var arr = new Uint8Array(encoded.length);
                      for (var i = 0; i < encoded.length; i++) arr[i] = encoded.charCodeAt(i) & 255;
                      return arr;
                    };
                    this.TextEncoder = QNTextEncoder;
                  }
                  if (typeof this.TextDecoder === 'undefined') {
                    function QNTextDecoder(label) { this.encoding = (label ? String(label).toLowerCase() : 'utf-8'); }
                    QNTextDecoder.prototype.decode = function(input) {
                      if (input == null) return '';
                      var bytes;
                      if (input instanceof Uint8Array) bytes = input;
                      else if (input instanceof ArrayBuffer) bytes = new Uint8Array(input);
                      else if (input.buffer instanceof ArrayBuffer) bytes = new Uint8Array(input.buffer, input.byteOffset || 0, input.byteLength != null ? input.byteLength : input.buffer.byteLength);
                      else bytes = input;
                      var out = '', i = 0, len = bytes.length || 0;
                      while (i < len) {
                        var b0 = bytes[i++] & 255;
                        if (b0 < 128) { out += String.fromCharCode(b0); }
                        else if (b0 >= 192 && b0 < 224 && i < len) {
                          var b1 = bytes[i++] & 63;
                          out += String.fromCharCode(((b0 & 31) << 6) | b1);
                        } else if (b0 >= 224 && b0 < 240 && i + 1 < len) {
                          var b1 = bytes[i++] & 63, b2 = bytes[i++] & 63;
                          out += String.fromCharCode(((b0 & 15) << 12) | (b1 << 6) | b2);
                        } else if (b0 >= 240 && i + 2 < len) {
                          var b1 = bytes[i++] & 63, b2 = bytes[i++] & 63, b3 = bytes[i++] & 63;
                          var cp = (((b0 & 7) << 18) | (b1 << 12) | (b2 << 6) | b3) - 0x10000;
                          out += String.fromCharCode(0xD800 + (cp >> 10), 0xDC00 + (cp & 1023));
                        } else {
                          out += '\uFFFD';
                        }
                      }
                      return out;
                    };
                    this.TextDecoder = QNTextDecoder;
                  }
                  if (typeof this.Buffer === 'undefined') {
                    var qnDec = new this.TextDecoder('utf-8'), qnEnc = new this.TextEncoder();
                    function qnWrapBuffer(u8) {
                      u8._isBuffer = true;
                      u8.toString = function(enc) {
                        var e = String(enc || 'utf8').toLowerCase();
                        if (e === 'base64' || e === 'base64url') {
                          var b64 = qnBytesToBase64(u8);
                          return e === 'base64url' ? b64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') : b64;
                        }
                        if (e === 'hex') {
                          var h = '';
                          for (var i = 0; i < u8.length; i++) h += (u8[i] < 16 ? '0' : '') + u8[i].toString(16);
                          return h;
                        }
                        if (e === 'latin1' || e === 'binary' || e === 'ascii') {
                          var s = '';
                          for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i] & 255);
                          return s;
                        }
                        return qnDec.decode(u8);
                      };
                      return u8;
                    }
                    var QNBuffer = {
                      from: function(input, encoding) {
                        if (typeof input === 'string') {
                          var enc = String(encoding || 'utf8').toLowerCase();
                          if (enc === 'base64' || enc === 'base64url') {
                            var clean = enc === 'base64url' ? input.replace(/-/g, '+').replace(/_/g, '/') : input;
                            return qnWrapBuffer(qnBase64ToBytes(clean));
                          }
                          if (enc === 'hex') {
                            var cleanHex = input.replace(/[^0-9a-fA-F]/g, '');
                            var arr = new Uint8Array(Math.floor(cleanHex.length / 2));
                            for (var i = 0; i < arr.length; i++) arr[i] = parseInt(cleanHex.substr(i * 2, 2), 16) & 255;
                            return qnWrapBuffer(arr);
                          }
                          if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') {
                            var arr = new Uint8Array(input.length);
                            for (var i = 0; i < input.length; i++) arr[i] = input.charCodeAt(i) & 255;
                            return qnWrapBuffer(arr);
                          }
                          return qnWrapBuffer(qnEnc.encode(input));
                        }
                        if (input instanceof ArrayBuffer) return qnWrapBuffer(new Uint8Array(input));
                        if (input && input.buffer instanceof ArrayBuffer) return qnWrapBuffer(new Uint8Array(input.buffer, input.byteOffset || 0, input.byteLength != null ? input.byteLength : input.buffer.byteLength));
                        if (Array.isArray(input) || (input && typeof input.length === 'number')) return qnWrapBuffer(new Uint8Array(input));
                        return qnWrapBuffer(new Uint8Array(0));
                      },
                      alloc: function(size, fill) {
                        var arr = new Uint8Array(Number(size) || 0);
                        if (fill !== undefined) {
                          var val = typeof fill === 'number' ? fill & 255 : String(fill).charCodeAt(0) & 255;
                          for (var i = 0; i < arr.length; i++) arr[i] = val;
                        }
                        return qnWrapBuffer(arr);
                      },
                      allocUnsafe: function(size) { return QNBuffer.alloc(size); },
                      isBuffer: function(obj) { return Boolean(obj && obj._isBuffer === true); },
                      concat: function(list, totalLength) {
                        var len = totalLength != null ? Number(totalLength) : list.reduce(function(acc, b) { return acc + b.length; }, 0);
                        var out = new Uint8Array(len), offset = 0;
                        for (var i = 0; i < list.length && offset < len; i++) {
                          var chunk = list[i];
                          for (var j = 0; j < chunk.length && offset < len; j++) out[offset++] = chunk[j];
                        }
                        return qnWrapBuffer(out);
                      },
                      byteLength: function(str, enc) { return QNBuffer.from(String(str), enc).length; }
                    };
                    this.Buffer = QNBuffer;
                  }
                  if (typeof this.AbortController === 'undefined') {
                    function QNAbortSignal() {
                      this.aborted = false;
                      this.reason = undefined;
                      this.onabort = null;
                      this._listeners = [];
                    }
                    QNAbortSignal.prototype.addEventListener = function(type, fn) {
                      if (type === 'abort' && typeof fn === 'function') this._listeners.push(fn);
                    };
                    QNAbortSignal.prototype.removeEventListener = function(type, fn) {
                      if (type === 'abort') this._listeners = this._listeners.filter(function(f) { return f !== fn; });
                    };
                    QNAbortSignal.abort = function(reason) {
                      var sig = new QNAbortSignal(); sig.aborted = true; sig.reason = reason; return sig;
                    };
                    function QNAbortController() { this.signal = new QNAbortSignal(); }
                    QNAbortController.prototype.abort = function(reason) {
                      if (this.signal.aborted) return;
                      this.signal.aborted = true;
                      this.signal.reason = reason;
                      if (typeof this.signal.onabort === 'function') this.signal.onabort({ type: 'abort' });
                      this.signal._listeners.slice().forEach(function(fn) { try { fn({ type: 'abort' }); } catch (_) {} });
                    };
                    this.AbortSignal = QNAbortSignal;
                    this.AbortController = QNAbortController;
                  }
                  if (typeof this.Intl === 'undefined') {
                    this.Intl = {
                      DateTimeFormat: function(locale, options) {
                        var tz = typeof __qnTimeZone === 'string' && __qnTimeZone ? __qnTimeZone : 'UTC';
                        return {
                          resolvedOptions: function() { return { locale: locale || 'en-US', timeZone: (options && options.timeZone) || tz }; },
                          format: function(d) { return (d instanceof Date ? d : new Date(d)).toUTCString(); }
                        };
                      }
                    };
                  }
                  // Rhino does not provide the browser/WHATWG URL global. LNReader bundles
                  // commonly use new URL(path, base) while resolving links and API endpoints.
                  if (typeof this.URL === 'undefined') {
                    function QNURLSearchParams(query, owner) {
                      this._pairs = [];
                      this._owner = owner || null;
                      // The WHATWG constructor accepts strings, iterable pairs, and
                      // plain records. LNReader multisource plugins commonly pass a
                      // record here for PHP chapter-archive POST bodies, e.g.
                      // new URLSearchParams({ novelId: id, acode: code }).
                      if (query && typeof query === 'object') {
                        if (query instanceof QNURLSearchParams) {
                          this._pairs = query._pairs.map(function(pair) { return [pair[0], pair[1]]; });
                        } else if (Array.isArray(query)) {
                          query.forEach(function(pair) {
                            if (Array.isArray(pair) && pair.length >= 2) {
                              this._pairs.push([String(pair[0]), String(pair[1])]);
                            }
                          }, this);
                        } else {
                          Object.keys(query).forEach(function(key) {
                            var value = query[key];
                            if (Array.isArray(value)) {
                              value.forEach(function(entry) {
                                this._pairs.push([key, String(entry)]);
                              }, this);
                            } else {
                              this._pairs.push([key, String(value)]);
                            }
                          }, this);
                        }
                        return;
                      }
                      var raw = String(query || '').replace(/^\?/, '');
                      if (raw) raw.split('&').forEach(function(part) {
                        if (!part) return;
                        var at = part.indexOf('=');
                        var key = at < 0 ? part : part.slice(0, at);
                        var value = at < 0 ? '' : part.slice(at + 1);
                        try { key = decodeURIComponent(key.replace(/\+/g, ' ')); } catch (_) {}
                        try { value = decodeURIComponent(value.replace(/\+/g, ' ')); } catch (_) {}
                        this._pairs.push([key, value]);
                      }, this);
                    }
                    QNURLSearchParams.prototype._sync = function() {
                      if (this._owner) this._owner.search = this.toString() ? '?' + this.toString() : '';
                    };
                    QNURLSearchParams.prototype.get = function(key) {
                      key = String(key);
                      for (var i=0;i<this._pairs.length;i++) if (this._pairs[i][0] === key) return this._pairs[i][1];
                      return null;
                    };
                    QNURLSearchParams.prototype.getAll = function(key) {
                      key=String(key); return this._pairs.filter(function(p){return p[0]===key;}).map(function(p){return p[1];});
                    };
                    QNURLSearchParams.prototype.has = function(key) { return this.get(key) !== null; };
                    QNURLSearchParams.prototype.append = function(k,v) { this._pairs.push([String(k),String(v)]); this._sync(); };
                    QNURLSearchParams.prototype.set = function(k,v) {
                      k=String(k); var first=true;
                      this._pairs=this._pairs.filter(function(p){if(p[0]!==k)return true;if(first){first=false;return false;}return false;});
                      this._pairs.push([k,String(v)]); this._sync();
                    };
                    QNURLSearchParams.prototype.delete = function(k) { k=String(k); this._pairs=this._pairs.filter(function(p){return p[0]!==k;}); this._sync(); };
                    QNURLSearchParams.prototype.toString = function() {
                      return this._pairs.map(function(p){return encodeURIComponent(p[0]).replace(/%20/g,'+')+'='+encodeURIComponent(p[1]).replace(/%20/g,'+');}).join('&');
                    };
                    function QNURL(input, base) {
                      if (!(this instanceof QNURL)) throw new TypeError("URL constructor must be called with 'new'");
                      var value=String(input), baseValue=base == null ? '' : (base instanceof QNURL ? base.href : String(base));
                      if (!/^[a-z][a-z0-9+.-]*:/i.test(value)) {
                        if (!baseValue) throw new TypeError('Invalid URL');
                        var bm=baseValue.match(/^([a-z][a-z0-9+.-]*:)\/\/([^\/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/i);
                        if (!bm) throw new TypeError('Invalid base URL');
                        if (value.indexOf('//')===0) value=bm[1]+value;
                        else if (value.charAt(0)==='#') value=bm[1]+'//'+bm[2]+(bm[3]||'/')+(bm[4]||'')+value;
                        else if (value.charAt(0)==='?') value=bm[1]+'//'+bm[2]+(bm[3]||'/')+value;
                        else {
                          var path=value.split(/[?#]/)[0], suffix=value.slice(path.length);
                          var joined=path.charAt(0)==='/'?path:((bm[3]||'/').replace(/[^/]*$/,'')+path);
                          var stack=[];
                          joined.split('/').forEach(function(part){if(part==='..')stack.pop();else if(part!=='.')stack.push(part);});
                          var normalized=stack.join('/'); if(normalized.charAt(0)!=='/')normalized='/'+normalized;
                          value=bm[1]+'//'+bm[2]+normalized+suffix;
                        }
                      }
                      var m=value.match(/^([a-z][a-z0-9+.-]*:)\/\/([^\/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/i);
                      if(!m) throw new TypeError('Invalid URL: '+value);
                      var authority=m[2], at=authority.lastIndexOf('@'), hostpart=at>=0?authority.slice(at+1):authority;
                      var colon=hostpart.lastIndexOf(':'), hostname=hostpart, port='';
                      if(colon>0 && /^\d+$/.test(hostpart.slice(colon+1))){hostname=hostpart.slice(0,colon);port=hostpart.slice(colon+1);}
                      this.protocol=m[1].toLowerCase(); this.username=at>=0?authority.slice(0,at).split(':')[0]:''; this.password=at>=0&&authority.slice(0,at).indexOf(':')>=0?authority.slice(0,at).split(':').slice(1).join(':'):'';
                      this.hostname=hostname.toLowerCase(); this.port=port; this.host=this.hostname+(port?':'+port:'');
                      this.pathname=m[3]||'/'; this.search=m[4]||''; this.hash=m[5]||'';
                      this.origin=this.protocol+'//'+this.host; this.searchParams=new QNURLSearchParams(this.search, this);
                    }
                    Object.defineProperty(QNURL.prototype,'href',{get:function(){var q=this.searchParams.toString();return this.origin+this.pathname+(q?'?'+q:'')+(this.hash||'');},set:function(v){var u=new QNURL(v);var self=this;['protocol','username','password','hostname','port','host','pathname','search','hash','origin','searchParams'].forEach(function(k){self[k]=u[k];});}});
                    QNURL.prototype.toString=function(){return this.href;};
                    QNURL.prototype.toJSON=function(){return this.href;};
                    this.URL=QNURL;
                    this.URLSearchParams=QNURLSearchParams;
                  }
                  // LNReader multisrc plugins use FormData for PHP/AJAX chapter-list
                  // endpoints. Keep this shim string-only (no file/blob uploads), then
                  // serialize it to a bounded multipart request in fetchApi below.
                  function QNFormData() {
                    this.__qnFormData = true;
                    this._entries = [];
                  }
                  QNFormData.prototype.append = function(name, value) {
                    this._entries.push([String(name), String(value)]);
                  };
                  QNFormData.prototype.set = function(name, value) {
                    name = String(name);
                    this.delete(name);
                    this.append(name, value);
                  };
                  QNFormData.prototype.get = function(name) {
                    name = String(name);
                    for (var i = 0; i < this._entries.length; i++)
                      if (this._entries[i][0] === name) return this._entries[i][1];
                    return null;
                  };
                  QNFormData.prototype.getAll = function(name) {
                    name = String(name);
                    return this._entries.filter(function(entry) { return entry[0] === name; })
                      .map(function(entry) { return entry[1]; });
                  };
                  QNFormData.prototype.has = function(name) {
                    return this.get(name) !== null;
                  };
                  QNFormData.prototype.delete = function(name) {
                    name = String(name);
                    this._entries = this._entries.filter(function(entry) { return entry[0] !== name; });
                  };
                  QNFormData.prototype.forEach = function(callback, thisArg) {
                    this._entries.forEach(function(entry) {
                      callback.call(thisArg, entry[1], entry[0], this);
                    }, this);
                  };
                  this.FormData = QNFormData;

                  // LNReader plugins use timers for retry delays and cooperative yields.
                  // Queue callbacks and drain them after the plugin operation starts. This
                  // avoids recursive async-generator continuation when a plugin yields with
                  // setTimeout(0), while retaining the bridge's deterministic sync contract.
                  var qnTimerSequence = 0;
                  var qnTimerQueue = [];
                  function qnScheduleTimer(callback, delay, args) {
                    if (typeof callback !== 'function') throw new TypeError('Timer callback must be a function');
                    var requestedDelay = Number(delay == null ? 0 : delay);
                    if (!isFinite(requestedDelay) || requestedDelay < 0 || requestedDelay > 10000)
                      throw new Error('Extension timer delay exceeds the 10 second limit.');
                    var id = ++qnTimerSequence;
                    qnTimerQueue.push({id:id, callback:callback, delay:requestedDelay, args:args});
                    return id;
                  }
                  this.setTimeout = function(callback, delay) {
                    return qnScheduleTimer(callback, delay, Array.prototype.slice.call(arguments, 2));
                  };
                  this.clearTimeout = function(id) {
                    qnTimerQueue = qnTimerQueue.filter(function(timer) { return timer.id !== id; });
                  };
                  this.setImmediate = function(callback) {
                    return qnScheduleTimer(callback, 0, Array.prototype.slice.call(arguments, 1));
                  };
                  this.clearImmediate = this.clearTimeout;
                  this.__qnRunTimers = function() {
                    var executed = 0;
                    while (qnTimerQueue.length) {
                      if (++executed > 10000)
                        throw new Error('Extension exceeded the 10000 timer-callback limit.');
                      var timer = qnTimerQueue.shift();
                      __qnDelay(timer.delay);
                      timer.callback.apply(undefined, timer.args);
                    }
                  };
                  this.console = {
                    log: function() {}, info: function() {},
                    warn: function() {}, error: function() {}
                  };

                  function QNPromise(executor) {
                    var self = this;
                    self.state = 0; self.value = void 0; self.handlers = [];
                    function settle(state, value) {
                      if (self.state !== 0) return;
                      if (value === self) return settle(2, new TypeError('Promise cycle'));
                      if (value && typeof value.then === 'function') {
                        try { return value.then(resolve, reject); } catch (e) { return reject(e); }
                      }
                      self.state = state; self.value = value;
                      self.handlers.forEach(run);
                    }
                    function resolve(value) { settle(1, value); }
                    function reject(error) { settle(2, error); }
                    function run(handler) {
                      var callback = self.state === 1 ? handler.ok : handler.fail;
                      if (typeof callback !== 'function') {
                        (self.state === 1 ? handler.resolve : handler.reject)(self.value); return;
                      }
                      try { handler.resolve(callback(self.value)); } catch (e) { handler.reject(e); }
                    }
                    self.then = function(ok, fail) {
                      return new QNPromise(function(resolveNext, rejectNext) {
                        var handler = {ok:ok, fail:fail, resolve:resolveNext, reject:rejectNext};
                        if (self.state === 0) self.handlers.push(handler); else run(handler);
                      });
                    };
                    self.catch = function(fail) { return self.then(null, fail); };
                    self.finally = function(onFinally) {
                      var P = QNPromise;
                      return self.then(
                        function(value) { return P.resolve(typeof onFinally === 'function' ? onFinally() : undefined).then(function() { return value; }); },
                        function(reason) { return P.resolve(typeof onFinally === 'function' ? onFinally() : undefined).then(function() { throw reason; }); }
                      );
                    };
                    try { executor(resolve, reject); } catch (e) { reject(e); }
                  }
                  QNPromise.resolve = function(value) {
                    if (value instanceof QNPromise) return value;
                    return new QNPromise(function(resolve) { resolve(value); });
                  };
                  QNPromise.reject = function(error) {
                    return new QNPromise(function(resolve, reject) { reject(error); });
                  };
                  QNPromise.all = function(values) {
                    return new QNPromise(function(resolve, reject) {
                      if (!values || typeof values.length !== 'number')
                        return reject(new TypeError('Promise.all expects an array-like value'));
                      var length = values.length, remaining = length, results = [];
                      if (length === 0) return resolve(results);
                      for (var i = 0; i < length; i++) (function(index) {
                        QNPromise.resolve(values[index]).then(function(value) {
                          results[index] = value;
                          remaining--;
                          if (remaining === 0) resolve(results);
                        }, reject);
                      })(i);
                    });
                  };
                  QNPromise.allSettled = function(values) {
                    return new QNPromise(function(resolve, reject) {
                      if (!values || typeof values.length !== 'number')
                        return reject(new TypeError('Promise.allSettled expects an array-like value'));
                      var length = values.length, remaining = length, results = [];
                      if (length === 0) return resolve(results);
                      for (var i = 0; i < length; i++) (function(index) {
                        QNPromise.resolve(values[index]).then(
                          function(value) { results[index] = { status: 'fulfilled', value: value }; if (--remaining === 0) resolve(results); },
                          function(reason) { results[index] = { status: 'rejected', reason: reason }; if (--remaining === 0) resolve(results); }
                        );
                      })(i);
                    });
                  };
                  QNPromise.race = function(values) {
                    return new QNPromise(function(resolve, reject) {
                      if (!values || typeof values.length !== 'number')
                        return reject(new TypeError('Promise.race expects an array-like value'));
                      for (var i = 0; i < values.length; i++) QNPromise.resolve(values[i]).then(resolve, reject);
                    });
                  };
                  QNPromise.any = function(values) {
                    return new QNPromise(function(resolve, reject) {
                      if (!values || typeof values.length !== 'number' || values.length === 0)
                        return reject(new Error('All promises were rejected'));
                      var remaining = values.length, errors = [];
                      for (var i = 0; i < values.length; i++) (function(index) {
                        QNPromise.resolve(values[index]).then(resolve, function(err) {
                          errors[index] = err;
                          if (--remaining === 0) reject(new Error('All promises were rejected'));
                        });
                      })(i);
                    });
                  };
                  this.Promise = QNPromise;
                  var qnTextEnc = new this.TextEncoder();
                  function QNBlob(bytes, type) {
                    this._bytes = bytes;
                    this.size = bytes.length;
                    this.type = type || '';
                  }
                  QNBlob.prototype.arrayBuffer = function() { return Promise.resolve(this._bytes.buffer); };
                  QNBlob.prototype.bytes = function() { return Promise.resolve(this._bytes); };
                  QNBlob.prototype.text = function() { return Promise.resolve(new TextDecoder('utf-8').decode(this._bytes)); };
                  this.require = function(name) {
                    if (name === '@libs/fetch') {
                      var fetchModule = {
                      fetchApi: function(url, init, responseEncoding) {
                        if (init && init.signal && init.signal.aborted) {
                          return Promise.reject(new Error('The operation was aborted.'));
                        }
                        var method = init && init.method ? String(init.method).toUpperCase() : 'GET';
                        if (method !== 'GET' && method !== 'POST' && method !== 'HEAD')
                          throw new Error('Only GET and POST (or HEAD) requests are supported by this extension bridge.');
                        var headers = init && init.headers ? init.headers : {};
                        if (headers && typeof headers.forEach === 'function') {
                          var normalized = {};
                          headers.forEach(function(value, key) { normalized[key] = value; });
                          headers = normalized;
                        }
                        // Browser fetch applies init.referrer as the Referer request header.
                        // LNReader providers such as WTR-LAB depend on that navigation context.
                        var hasReferer = Object.keys(headers).some(function(key) {
                          return key.toLowerCase() === 'referer';
                        });
                        if (!hasReferer && init && typeof init.referrer === 'string' &&
                            (init.referrer.indexOf('https://') === 0 || init.referrer.indexOf('http://') === 0)) {
                          headers = Object.assign({}, headers, {Referer:init.referrer});
                        }
                        var request = {method:method, headers:headers};
                        if (init && init.body != null) {
                          if (typeof init.body === 'string') {
                            request.body = init.body;
                          } else if (init.body && init.body.__qnFormData === true &&
                              Array.isArray(init.body._entries)) {
                            var boundary = '----QuickNovelFormBoundary' +
                              String(Math.floor(Math.random() * 10000000000000000));
                            var parts = init.body._entries.map(function(entry) {
                              var fieldName = String(entry[0]).replace(/"/g, '%22');
                              return '--' + boundary + '\r\n' +
                                'Content-Disposition: form-data; name="' + fieldName + '"\r\n\r\n' +
                                String(entry[1]) + '\r\n';
                            });
                            request.body = parts.join('') + '--' + boundary + '--\r\n';
                            var hasContentType = Object.keys(request.headers).some(function(key) {
                              return key.toLowerCase() === 'content-type';
                            });
                            if (!hasContentType) {
                              request.headers['Content-Type'] = 'multipart/form-data; boundary=' + boundary;
                            }
                          } else if (typeof URLSearchParams === 'function' &&
                              (init.body instanceof URLSearchParams ||
                              (typeof init.body.getAll === 'function' && typeof init.body.append === 'function'))) {
                            request.body = init.body.toString();
                            var hasUrlEncodedCt = Object.keys(request.headers).some(function(key) {
                              return key.toLowerCase() === 'content-type';
                            });
                            if (!hasUrlEncodedCt) {
                              request.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
                            }
                          } else if (init.body instanceof Uint8Array || init.body instanceof ArrayBuffer ||
                              (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(init.body))) {
                            var bytes = init.body instanceof Uint8Array
                              ? init.body
                              : (init.body instanceof ArrayBuffer
                                  ? new Uint8Array(init.body)
                                  : new Uint8Array(init.body.buffer, init.body.byteOffset || 0, init.body.byteLength));
                            request.bodyBase64 = qnBytesToBase64(bytes);
                          } else {
                            throw new Error('Only string and simple FormData request bodies are supported by this extension bridge.');
                          }
                        }
                        return Promise.resolve(JSON.parse(__qnFetch(String(url), JSON.stringify(request), responseEncoding))).then(function(r) {
                          var responseHeaders = r.headers || {};
                          function responseBytes() {
                            return r.bodyBase64 ? qnBase64ToBytes(r.bodyBase64) : qnTextEnc.encode(r.body || '');
                          }
                          return {
                            url: r.url, status: r.statusCode, ok: r.statusCode >= 200 && r.statusCode < 300,
                            statusText: r.statusText || String(r.statusCode), body: r.body,
                            text: function() { return Promise.resolve(r.body); },
                            json: function() { return Promise.resolve(JSON.parse(r.body)); },
                            arrayBuffer: function() { return Promise.resolve(responseBytes().buffer); },
                            bytes: function() { return Promise.resolve(responseBytes()); },
                            blob: function() { return Promise.resolve(new QNBlob(responseBytes(), r.contentType || '')); },
                            headers: {
                              get: function(key) {
                                var normalized = String(key).toLowerCase();
                                return responseHeaders[normalized] ||
                                  (normalized === 'content-type' ? r.contentType : null);
                              },
                              has: function(key) {
                                return this.get(key) != null;
                              },
                              forEach: function(callback) {
                                Object.keys(responseHeaders).forEach(function(key) {
                                  callback(responseHeaders[key], key);
                                });
                              }
                            }
                          };
                        });
                      },
                      fetchText: function(url, init, encoding) {
                        try {
                          return fetchModule.fetchApi(url, init, encoding).then(function(response) {
                            return response.ok ? response.text() : '';
                          }).catch(function() { return ''; });
                        } catch (error) {
                          return Promise.resolve('');
                        }
                      },
                      fetchFile: function(url, init) {
                        try {
                          var method = init && init.method ? String(init.method).toUpperCase() : 'GET';
                          if (method !== 'GET') throw new Error('fetchFile currently supports GET requests only.');
                          var headers = init && init.headers ? init.headers : {};
                          if (headers && typeof headers.forEach === 'function') {
                            var normalized = {};
                            headers.forEach(function(value, key) { normalized[key] = value; });
                            headers = normalized;
                          }
                          var hasReferer = Object.keys(headers).some(function(key) {
                            return key.toLowerCase() === 'referer';
                          });
                          if (!hasReferer && init && typeof init.referrer === 'string' &&
                              (init.referrer.indexOf('https://') === 0 || init.referrer.indexOf('http://') === 0)) {
                            headers = Object.assign({}, headers, {Referer:init.referrer});
                          }
                          var request = {method:method,headers:headers};
                          return Promise.resolve(JSON.parse(
                            __qnFetch(String(url), JSON.stringify(request), null, 'base64')
                          )).then(function(response) {
                            return response.statusCode >= 200 && response.statusCode < 300
                              ? (response.bodyBase64 || '') : '';
                          }).catch(function() { return ''; });
                        } catch (error) {
                          return Promise.resolve('');
                        }
                      },
                      fetchProto: function() {
                        throw new Error('LNReader fetchProto requires protobufjs and binary POST support, which are not supported by this QuickNovel runtime yet.');
                      }
                    };
                    fetchModule.default = fetchModule;
                    return fetchModule;
                    }
                    if (name === 'cheerio') {
                      function call(command, id, value, index) {
                        return JSON.parse(__qnHtml(command, id, value == null ? '' : String(value), index == null ? '' : index));
                      }
                      function contentToHtml(content) {
                        if (content && content._qnSelectionId != null) {
                          return call('outerHtml', content._qnSelectionId) || '';
                        }
                        return String(content == null ? '' : content);
                      }
                      function wrap(id, prevSelection) {
                        var selection = {_qnSelectionId:id, _qnPrevSelection:prevSelection || null};
                        if (prevSelection && prevSelection._qnSelectionId != null) {
                          selection._qnPrevId = prevSelection._qnSelectionId;
                        }
                        Object.defineProperty(selection, 'length', {get:function(){return call('length',id);}});
                        selection.text = function(val){
                          if (arguments.length > 0) {
                            call('textSet', id, String(val == null ? '' : val));
                            return selection;
                          }
                          return call('text',id);
                        };
                        selection.html = function(val){
                          if (arguments.length > 0) {
                            call('htmlSet', id, contentToHtml(val));
                            return selection;
                          }
                          return call('html',id);
                        };
                        selection.attr = function(name, value){
                          if (typeof name === 'object' && name !== null) {
                            Object.keys(name).forEach(function(k) {
                              call('attrSet', id, JSON.stringify([k, name[k] == null ? '' : String(name[k])]));
                            });
                            return selection;
                          }
                          if (arguments.length > 1) {
                            var resolvedVal = typeof value === 'function'
                              ? value.call(wrapNode(id), 0, call('attr', id, name))
                              : value;
                            if (resolvedVal === null) call('removeAttr', id, name);
                            else call('attrSet', id, JSON.stringify([name, resolvedVal == null ? '' : String(resolvedVal)]));
                            return selection;
                          }
                          return call('attr',id,name);
                        };
                        selection.prop = function(name, value){
                          if (arguments.length > 1) {
                            if (name === 'innerHTML') return selection.html(value);
                            if (name === 'textContent' || name === 'innerText') return selection.text(value);
                            call('attrSet', id, JSON.stringify([name, value == null ? '' : String(value)]));
                            return selection;
                          }
                          if (name === 'outerHTML') return call('outerHtml', id) || '';
                          if (name === 'innerHTML') return call('html', id);
                          if (name === 'textContent' || name === 'innerText') return call('text', id);
                          if (name === 'tagName' || name === 'nodeName') {
                            var tn = call('nodeName', id);
                            return tn ? String(tn).toUpperCase() : undefined;
                          }
                          return call('attr', id, name);
                        };
                        selection.data = function(name, value){
                          if (arguments.length === 0) return call('data', id, '');
                          if (arguments.length > 1) {
                            call('attrSet', id, JSON.stringify(['data-' + name, typeof value === 'string' ? value : JSON.stringify(value)]));
                            return selection;
                          }
                          return call('data', id, String(name));
                        };
                        selection.val = function(value){
                          if (arguments.length > 0) {
                            call('valSet', id, String(value == null ? '' : value));
                            return selection;
                          }
                          return call('val', id);
                        };
                        selection.hasClass = function(name){return call('hasClass',id,name);};
                        selection.addClass = function(name){call('addClass',id,String(name || ''));return selection;};
                        selection.removeClass = function(name){call('removeClass',id,name == null ? '' : String(name));return selection;};
                        selection.toggleClass = function(name, state){
                          call('toggleClass',id,String(name || ''), state == null ? '' : (state ? 1 : 0));
                          return selection;
                        };
                        selection.first = function(){return wrap(call('first',id), selection);};
                        selection.last = function(){return wrap(call('last',id), selection);};
                        selection.eq = function(index){return wrap(call('eq',id,'',index), selection);};
                        selection.slice = function(start, end){
                          return wrap(call('slice', id, end == null ? '' : String(end), start == null ? 0 : start), selection);
                        };
                        selection.find = function(selector){
                          return wrap(call('find',id,selector), selection);
                        };
                        selection.children = function(selector){return wrap(call('children',id,selector || ''), selection);};
                        selection.contents = function(){return wrap(call('contents',id), selection);};
                        selection.parent = function(selector){return wrap(call('parent',id,selector || ''), selection);};
                        selection.parents = function(selector){return wrap(call('parents',id,selector || ''), selection);};
                        selection.closest = function(selector){return wrap(call('closest',id,String(selector || '')), selection);};
                        selection.next = function(selector){return wrap(call('next',id,selector || ''), selection);};
                        selection.prev = function(selector){return wrap(call('prev',id,selector || ''), selection);};
                        selection.nextAll = function(selector){return wrap(call('nextAll',id,selector || ''), selection);};
                        selection.prevAll = function(selector){return wrap(call('prevAll',id,selector || ''), selection);};
                        selection.siblings = function(selector){return wrap(call('siblings',id,selector || ''), selection);};
                        selection.nextUntil = function(selector){
                          return wrap(call('nextUntil',id,String(selector || '*')), selection);
                        };
                        selection.clone = function(){return wrap(call('clone',id), selection);};
                        selection.end = function(){return selection._qnPrevSelection || selection;};
                        selection.toArray = function(){return selection.get();};
                        selection.addBack = function(){
                          return selection._qnPrevId == null ? selection :
                            wrap(call('merge',id,JSON.stringify([id,selection._qnPrevId])), selection);
                        };
                        selection.is = function(predicate){
                          if (typeof predicate === 'function') {
                            for (var i=0;i<selection.length;i++) {
                              var item=wrapNode(call('eq',id,'',i));
                              if (predicate.call(item,i,item)) return true;
                            }
                            return false;
                          }
                          if (predicate && predicate._qnSelectionId != null) {
                            return call('isNodes', id, String(predicate._qnSelectionId));
                          }
                          return call('is', id, String(predicate || ''));
                        };
                        selection.not = function(predicate){
                          if (typeof predicate === 'function') {
                            var ids=[];
                            for (var i=0;i<selection.length;i++) {
                              var item=wrapNode(call('eq',id,'',i));
                              if (!predicate.call(item,i,item)) ids.push(item._qnSelectionId);
                            }
                            return wrap(call('merge',id,JSON.stringify(ids)), selection);
                          }
                          if (predicate && predicate._qnSelectionId != null) {
                            return wrap(call('notNodes', id, String(predicate._qnSelectionId)), selection);
                          }
                          return wrap(call('not', id, String(predicate || '')), selection);
                        };
                        selection.filter = function(predicate){
                          if (typeof predicate === 'function') {
                            var ids=[];
                            for (var i=0;i<selection.length;i++) {
                              var item=wrapNode(call('eq',id,'',i));
                              if (predicate.call(item,i,item)) ids.push(item._qnSelectionId);
                            }
                            return wrap(call('merge',id,JSON.stringify(ids)), selection);
                          }
                          return wrap(call('filter',id,String(predicate)), selection);
                        };
                        selection.has = function(selector){return wrap(call('has',id,String(selector)), selection);};
                        selection.index = function(selector){
                          if (selector == null) return call('index', id, '', 0);
                          if (selector._qnSelectionId != null) return call('index', id, String(selector._qnSelectionId), 1);
                          return call('index', id, String(selector), 2);
                        };
                        selection.removeAttr = function(name){call('removeAttr',id,name);return selection;};
                        selection.replaceWith = function(content){
                          call('replaceWith',id,contentToHtml(content)); return selection;
                        };
                        selection.before = function(content){call('before',id,contentToHtml(content));return selection;};
                        selection.after = function(content){call('after',id,contentToHtml(content));return selection;};
                        selection.append = function(content){call('append',id,contentToHtml(content));return selection;};
                        selection.prepend = function(content){call('prepend',id,contentToHtml(content));return selection;};
                        selection.wrap = function(content){call('wrap',id,contentToHtml(content));return selection;};
                        selection.remove = function(){call('remove',id);return selection;};
                        selection.empty = function(){call('htmlSet',id,'');return selection;};
                        selection.each = function(callback) {
                          for (var i=0;i<selection.length;i++) {
                            var item=wrapNode(call('eq',id,'',i));
                            if (callback.call(item,i,item) === false) break;
                          }
                          return selection;
                        };
                        selection.map = function(callback) {
                          var values=[];
                          for (var i=0;i<selection.length;i++) {
                            var item=wrapNode(call('eq',id,'',i));
                            var res=callback.call(item,i,item);
                            if (res != null) values.push(res);
                          }
                          return {get:function(){return values;},toArray:function(){return values;}};
                        };
                        selection.get = function(index) {
                          if (index == null) { var all=[]; for(var i=0;i<selection.length;i++) all.push(wrapNode(call('eq',id,'',i))); return all; }
                          return wrapNode(call('eq',id,'',index));
                        };
                        return selection;
                      }
                      function wrapNode(id) {
                        var node = wrap(id);
                        Object.defineProperty(node, 'type', {get:function(){return call('nodeType',id);}});
                        Object.defineProperty(node, 'nodeType', {get:function(){
                          var type=call('nodeType',id);
                          return type==='tag'?1:type==='text'?3:type==='comment'?8:9;
                        }});
                        Object.defineProperty(node, 'name', {get:function(){return call('nodeName',id);}});
                        Object.defineProperty(node, 'tagName', {get:function(){return call('nodeName',id);}});
                        Object.defineProperty(node, 'data', {get:function(){return call('nodeData',id);}});
                        Object.defineProperty(node, 'attribs', {get:function(){return call('attribs',id) || {};}});
                        Object.defineProperty(node, 'next', {get:function(){
                          var nextId = call('nextNode',id);
                          return nextId == null ? null : wrapNode(nextId);
                        }});
                        return node;
                      }
                      function load(html, options) {
                        var isXml = !!(options && options.xmlMode);
                        var root = call('load', 0, String(html == null ? '' : html), isXml ? 1 : 0);
                        function $(selector, context) {
                          if (!selector) return wrap(call('empty', root));
                          if (selector._qnSelectionId != null) return selector;
                          if (typeof selector === 'string') {
                            var trimmed = selector.trim();
                            if (trimmed.charAt(0) === '<') {
                              return wrap(call('create', root, selector));
                            }
                            if (context != null) {
                              if (context._qnSelectionId != null) {
                                return wrap(call('find', context._qnSelectionId, selector));
                              }
                              return $(String(context)).find(selector);
                            }
                            return wrap(call('select', root, selector));
                          }
                          return wrap(call('empty', root));
                        }
                        $.root = function(){return wrap(root);};
                        $.html = function(sel){
                          if (arguments.length === 0) return call('html',root);
                          if (sel && sel._qnSelectionId != null) return call('outerHtml', sel._qnSelectionId) || '';
                          return call('outerHtml', $(sel)._qnSelectionId) || '';
                        };
                        $.text = function(sel){
                          if (arguments.length === 0) return call('text',root);
                          return $(sel).text();
                        };
                        $.parseHTML = function(fragment){
                          return wrap(call('create', root, String(fragment == null ? '' : fragment))).get();
                        };
                        return $;
                      }
                      return {load:load,default:{load:load}};
                    }
                    if (name === 'htmlparser2') {
                      function Parser(handlers, options) {
                        this.handlers = handlers || {};
                        this.options = options || {};
                        this.source = '';
                      }
                      // LNReader multisrc bundles use the htmlparser2 Parser instance
                      // method while rebuilding chapter HTML. Keep this list aligned with
                      // HTML's void elements: they have no closing tag and must not emit one.
                      var voidElements = {
                        area:true, base:true, basefont:true, bgsound:true, br:true,
                        col:true, embed:true, hr:true, img:true, input:true,
                        keygen:true, link:true, meta:true, param:true,
                        source:true, track:true, wbr:true
                      };
                      Parser.prototype.isVoidElement = function(name) {
                        return Object.prototype.hasOwnProperty.call(
                          voidElements, String(name == null ? '' : name).toLowerCase()
                        );
                      };
                      Parser.prototype.write = function(chunk) {
                        this.source += String(chunk == null ? '' : chunk);
                        return this;
                      };
                      Parser.prototype.end = function(chunk) {
                        if (chunk != null) this.source += String(chunk);
                        var events = JSON.parse(__qnHtmlEvents(this.source, !!(this.options && this.options.xmlMode)));
                        for (var i=0;i<events.length;i++) {
                          var event = events[i];
                          if (event.type === 'open') {
                            if (typeof this.handlers.onopentagname === 'function') {
                              this.handlers.onopentagname.call(this.handlers, event.name);
                            }
                            if (typeof this.handlers.onattribute === 'function' && event.attribs) {
                              var attrKeys = Object.keys(event.attribs);
                              for (var a = 0; a < attrKeys.length; a++) {
                                this.handlers.onattribute.call(this.handlers, attrKeys[a], event.attribs[attrKeys[a]], undefined);
                              }
                            }
                            if (typeof this.handlers.onopentag === 'function') {
                              this.handlers.onopentag.call(this.handlers, event.name, event.attribs);
                            }
                          } else if (event.type === 'text') {
                            if (typeof this.handlers.ontext === 'function') {
                              this.handlers.ontext.call(this.handlers, event.text);
                            }
                          } else if (event.type === 'close') {
                            if (typeof this.handlers.onclosetag === 'function') {
                              this.handlers.onclosetag.call(this.handlers, event.name, false);
                            }
                          }
                        }
                        if (typeof this.handlers.onend === 'function') {
                          this.handlers.onend.call(this.handlers);
                        }
                        this.source = '';
                        return this;
                      };
                      return {Parser:Parser,default:{Parser:Parser}};
                    }
                    if (name === '@libs/storage') {
                      function call(command, key, value, expires, raw, area) {
                        return JSON.parse(__qnStorage(command, String(key == null ? '' : key),
                          value == null ? '' : String(value), expires == null ? '' : String(expires),
                          raw ? 'true' : 'false', area || ''));
                      }
                      var storage = {
                        set: function(key, value, expires) {
                          var expiry = expires instanceof Date ? expires.getTime() : expires;
                          return call('set', key, JSON.stringify(value), expiry, false);
                        },
                        get: function(key, raw) {
                          var value = call('get', key, '', '', raw);
                          return value === null ? undefined : value;
                        },
                        delete: function(key) { return call('delete', key); },
                        clearAll: function() { return call('clearAll'); },
                        getAllKeys: function() { return call('getAllKeys'); }
                      };
                      function createWebStorage(area) {
                        var webStorage = {
                          getItem: function(key) {
                            var value = call('get', key, '', '', false, area);
                            return value == null ? null : String(value);
                          },
                          setItem: function(key, value) {
                            call('set', key, JSON.stringify(String(value)), '', false, area);
                          },
                          removeItem: function(key) { call('delete', key, '', '', false, area); },
                          clear: function() { call('clearAll', '', '', '', false, area); },
                          key: function(index) {
                            var keys = call('getAllKeys', '', '', '', false, area);
                            return keys[index] == null ? null : keys[index];
                          },
                          get: function(key) {
                            var value = call('get', key, '', '', false, area);
                            return value == null ? undefined : value;
                          },
                          set: function(key, value) {
                            call('set', key, JSON.stringify(String(value)), '', false, area);
                          }
                        };
                        Object.defineProperty(webStorage, 'length', {
                          get: function() { return call('getAllKeys', '', '', '', false, area).length; }
                        });
                        return webStorage;
                      }
                      return {
                        storage: storage,
                        localStorage: createWebStorage('local'),
                        sessionStorage: createWebStorage('session')
                      };
                    }
                    if (name === 'dayjs') {
                      var months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
                      var shortMonths = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
                      function parseWithFormat(text, format) {
                        var fmt = String(format);
                        if (fmt === 'DD.MM.YYYY') {
                          var mDot = text.match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/);
                          return mDot ? new Date(Number(mDot[3]), Number(mDot[2]) - 1, Number(mDot[1])) : new Date(NaN);
                        }
                        if (fmt === 'DD/MM/YYYY' || fmt === 'DD-MM-YYYY' || fmt === 'D/M/YYYY') {
                          var mDmy = text.match(/^(\d{1,2})[\/\-](\d{1,2})[\/\-](\d{4})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$/);
                          return mDmy ? new Date(Number(mDmy[3]), Number(mDmy[2]) - 1, Number(mDmy[1]),
                            Number(mDmy[4] || 0), Number(mDmy[5] || 0), Number(mDmy[6] || 0)) : new Date(NaN);
                        }
                        if (fmt === 'MM/DD/YYYY' || fmt === 'MM-DD-YYYY' || fmt === 'M/D/YYYY') {
                          var mMdy = text.match(/^(\d{1,2})[\/\-](\d{1,2})[\/\-](\d{4})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$/);
                          return mMdy ? new Date(Number(mMdy[3]), Number(mMdy[1]) - 1, Number(mMdy[2]),
                            Number(mMdy[4] || 0), Number(mMdy[5] || 0), Number(mMdy[6] || 0)) : new Date(NaN);
                        }
                        if (fmt === 'YYYY-MM-DD' || fmt === 'YYYY/MM/DD') {
                          var mYmd = text.match(/^(\d{4})[\/\-](\d{1,2})[\/\-](\d{1,2})(?:[T\s](\d{1,2}):(\d{2})(?::(\d{2}))?)?/);
                          return mYmd ? new Date(Number(mYmd[1]), Number(mYmd[2]) - 1, Number(mYmd[3]),
                            Number(mYmd[4] || 0), Number(mYmd[5] || 0), Number(mYmd[6] || 0)) : new Date(NaN);
                        }
                        return new Date(text);
                      }
                      function parseRelative(text) {
                        var lower = text.trim().toLowerCase();
                        if (lower === 'just now' || lower === 'now' || lower === 'today') return new Date();
                        if (lower === 'yesterday') { var d = new Date(); d.setDate(d.getDate() - 1); return d; }
                        var m = lower.match(/^(?:an?|\d+)\s+(second|sec|minute|min|hour|hr|day|week|wk|month|mo|year|yr)s?\s+ago$/);
                        if (!m) return null;
                        var numMatch = lower.match(/^(\d+)/);
                        var n = numMatch ? Number(numMatch[1]) : 1;
                        var unit = m[1], now = new Date();
                        if (unit.indexOf('sec') === 0) now.setSeconds(now.getSeconds() - n);
                        else if (unit.indexOf('min') === 0) now.setMinutes(now.getMinutes() - n);
                        else if (unit.indexOf('h') === 0) now.setHours(now.getHours() - n);
                        else if (unit === 'day') now.setDate(now.getDate() - n);
                        else if (unit.indexOf('w') === 0) now.setDate(now.getDate() - n * 7);
                        else if (unit.indexOf('mo') === 0) now.setMonth(now.getMonth() - n);
                        else if (unit.indexOf('y') === 0) now.setFullYear(now.getFullYear() - n);
                        return now;
                      }
                      function parseDate(input, format) {
                        if (input == null) return new Date();
                        if (input === '') return new Date(NaN);
                        if (input && typeof input.toDate === 'function') return input.toDate();
                        if (input instanceof Date) return new Date(input.getTime());
                        if (typeof input === 'number') return new Date(input);
                        var text = String(input).trim();
                        if (!text) return new Date(NaN);
                        if (format) return parseWithFormat(text, format);
                        var rel = parseRelative(text);
                        if (rel) return rel;
                        var direct = new Date(text);
                        if (!isNaN(direct.getTime())) return direct;
                        var ymdSlash = text.match(/^(\d{4})\/(\d{1,2})\/(\d{1,2})(?:\s+(\d{1,2}):(\d{2})(?::(\d{2}))?)?$/);
                        if (ymdSlash) {
                          return new Date(Number(ymdSlash[1]), Number(ymdSlash[2]) - 1, Number(ymdSlash[3]),
                            Number(ymdSlash[4] || 0), Number(ymdSlash[5] || 0), Number(ymdSlash[6] || 0));
                        }
                        var dmyDot = text.match(/^(\d{1,2})\.(\d{1,2})\.(\d{4})$/);
                        if (dmyDot) return new Date(Number(dmyDot[3]), Number(dmyDot[2]) - 1, Number(dmyDot[1]));
                        return direct;
                      }
                      function makeDate(date) {
                        function valid() { return !isNaN(date.getTime()); }
                        function pad(value) { return value < 10 ? '0' + value : String(value); }
                        function unitName(unit) {
                          var original = String(unit || 'millisecond');
                          if (original === 'M' || original.toLowerCase().indexOf('month') === 0) return 'month';
                          unit = original.toLowerCase();
                          if (unit === 'd' || unit.indexOf('day') === 0) return 'day';
                          if (unit === 'h' || unit.indexOf('hour') === 0) return 'hour';
                          if (unit === 'm' || unit.indexOf('minute') === 0) return 'minute';
                          if (unit === 's' || unit.indexOf('second') === 0) return 'second';
                          if (unit === 'w' || unit.indexOf('week') === 0) return 'week';
                          if (unit === 'y' || unit.indexOf('year') === 0) return 'year';
                          return 'millisecond';
                        }
                        var api = {
                          isValid: valid,
                          valueOf: function() { return date.getTime(); },
                          unix: function() { return Math.floor(date.getTime()/1000); },
                          toDate: function() { return new Date(date.getTime()); },
                          toISOString: function() { return valid() ? date.toISOString() : null; },
                          date: function(v) {
                            if (arguments.length === 0) return date.getDate();
                            var copy = new Date(date.getTime()); copy.setDate(Number(v)); return makeDate(copy);
                          },
                          day: function(v) {
                            if (arguments.length === 0) return date.getDay();
                            var copy = new Date(date.getTime()); copy.setDate(copy.getDate() + (Number(v) - copy.getDay())); return makeDate(copy);
                          },
                          month: function(v) {
                            if (arguments.length === 0) return date.getMonth();
                            var copy = new Date(date.getTime()); copy.setMonth(Number(v)); return makeDate(copy);
                          },
                          year: function(v) {
                            if (arguments.length === 0) return date.getFullYear();
                            var copy = new Date(date.getTime()); copy.setFullYear(Number(v)); return makeDate(copy);
                          },
                          hour: function(v) {
                            if (arguments.length === 0) return date.getHours();
                            var copy = new Date(date.getTime()); copy.setHours(Number(v)); return makeDate(copy);
                          },
                          minute: function(v) {
                            if (arguments.length === 0) return date.getMinutes();
                            var copy = new Date(date.getTime()); copy.setMinutes(Number(v)); return makeDate(copy);
                          },
                          second: function(v) {
                            if (arguments.length === 0) return date.getSeconds();
                            var copy = new Date(date.getTime()); copy.setSeconds(Number(v)); return makeDate(copy);
                          },
                          millisecond: function(v) {
                            if (arguments.length === 0) return date.getMilliseconds();
                            var copy = new Date(date.getTime()); copy.setMilliseconds(Number(v)); return makeDate(copy);
                          },
                          utc: function() { return makeDate(new Date(date.getTime())); },
                          local: function() { return makeDate(new Date(date.getTime())); },
                          tz: function() { return makeDate(new Date(date.getTime())); },
                          locale: function() { return api; },
                          format: function(pattern) {
                            if (!valid()) return 'Invalid Date';
                            pattern = pattern || 'YYYY-MM-DDTHH:mm:ssZ';
                            if (pattern === 'LL') pattern = 'MMMM D, YYYY';
                            if (pattern === 'LLL') pattern = 'MMMM D, YYYY h:mm A';
                            if (pattern === 'x') return String(date.getTime());
                            if (pattern === 'X') return String(Math.floor(date.getTime() / 1000));
                            var hour = date.getHours(), offset = -date.getTimezoneOffset();
                            var zone = (offset >= 0 ? '+' : '-') + pad(Math.floor(Math.abs(offset)/60)) + ':' + pad(Math.abs(offset)%60);
                            var values = {
                              YYYY:String(date.getFullYear()), YY:String(date.getFullYear()).slice(-2),
                              MMMM:months[date.getMonth()], MMM:shortMonths[date.getMonth()],
                              MM:pad(date.getMonth()+1), M:String(date.getMonth()+1),
                              DD:pad(date.getDate()), D:String(date.getDate()),
                              HH:pad(hour), H:String(hour), hh:pad(hour%12 || 12), h:String(hour%12 || 12),
                              mm:pad(date.getMinutes()), m:String(date.getMinutes()),
                              ss:pad(date.getSeconds()), s:String(date.getSeconds()),
                              A:hour < 12 ? 'AM' : 'PM', a:hour < 12 ? 'am' : 'pm', Z:zone
                            };
                            return pattern.replace(/YYYY|MMMM|MMM|YY|MM|M|DD|D|HH|H|hh|h|mm|m|ss|s|A|a|Z/g, function(token) { return values[token]; });
                          },
                          fromNow: function(withoutSuffix) {
                            if (!valid()) return 'Invalid Date';
                            var diffSec = Math.round((Date.now() - date.getTime()) / 1000);
                            var abs = Math.abs(diffSec), label;
                            if (abs < 45) label = 'a few seconds';
                            else if (abs < 90) label = 'a minute';
                            else if (abs < 2700) label = Math.round(abs / 60) + ' minutes';
                            else if (abs < 5400) label = 'an hour';
                            else if (abs < 79200) label = Math.round(abs / 3600) + ' hours';
                            else if (abs < 129600) label = 'a day';
                            else if (abs < 2160000) label = Math.round(abs / 86400) + ' days';
                            else if (abs < 3888000) label = 'a month';
                            else if (abs < 29808000) label = Math.round(abs / 2592000) + ' months';
                            else label = Math.max(1, Math.round(abs / 31536000)) + ' years';
                            if (withoutSuffix) return label;
                            return diffSec >= 0 ? (label + ' ago') : ('in ' + label);
                          },
                          add: function(amount, unit) {
                            var copy = new Date(date.getTime()), n = Number(amount), u = unitName(unit);
                            if (u === 'year' || u === 'month') {
                              // Day.js clamps calendar arithmetic to the last valid day
                              // (e.g. Jan 31 + 1 month becomes Feb 28/29, not March).
                              var monthsToAdd = Math.round(u === 'year' ? n * 12 : n);
                              var originalDay = copy.getDate();
                              copy.setDate(1);
                              copy.setMonth(copy.getMonth()+monthsToAdd);
                              var lastDay = new Date(copy.getFullYear(), copy.getMonth()+1, 0).getDate();
                              copy.setDate(Math.min(originalDay,lastDay));
                            }
                            else if (u === 'week') copy.setDate(copy.getDate()+n*7);
                            else if (u === 'day') copy.setDate(copy.getDate()+n);
                            else if (u === 'hour') copy.setHours(copy.getHours()+n);
                            else if (u === 'minute') copy.setMinutes(copy.getMinutes()+n);
                            else if (u === 'second') copy.setSeconds(copy.getSeconds()+n);
                            else copy.setTime(copy.getTime()+n);
                            return makeDate(copy);
                          },
                          subtract: function(amount, unit) { return api.add(-Number(amount), unit); },
                          startOf: function(unit) {
                            var copy = new Date(date.getTime()), u = unitName(unit);
                            if (u === 'year') { copy.setMonth(0); copy.setDate(1); copy.setHours(0,0,0,0); }
                            else if (u === 'month') { copy.setDate(1); copy.setHours(0,0,0,0); }
                            else if (u === 'week') {
                              copy.setDate(copy.getDate()-copy.getDay());
                              copy.setHours(0,0,0,0);
                            }
                            else if (u === 'day') copy.setHours(0,0,0,0);
                            else if (u === 'hour') copy.setMinutes(0,0,0);
                            else if (u === 'minute') copy.setSeconds(0,0);
                            else if (u === 'second') copy.setMilliseconds(0);
                            return makeDate(copy);
                          },
                          endOf: function(unit) {
                            var u = unitName(unit), next = api.startOf(unit).add(1, unit);
                            return makeDate(new Date(next.valueOf()-1));
                          },
                          diff: function(other, unit) {
                            var otherDate = parseDate(other), delta = date.getTime() - otherDate.getTime(), u = unitName(unit);
                            if (u === 'month' || u === 'year') {
                              var months = (date.getFullYear()-otherDate.getFullYear())*12 +
                                (date.getMonth()-otherDate.getMonth());
                              var candidate = new Date(otherDate.getTime());
                              var originalDay = candidate.getDate();
                              candidate.setDate(1);
                              candidate.setMonth(candidate.getMonth()+months);
                              var lastDay = new Date(candidate.getFullYear(), candidate.getMonth()+1, 0).getDate();
                              candidate.setDate(Math.min(originalDay,lastDay));
                              if (delta >= 0 && candidate.getTime() > date.getTime()) months--;
                              else if (delta < 0 && candidate.getTime() < date.getTime()) months++;
                              return Math.trunc(u === 'year' ? months/12 : months);
                            }
                            var divisor = u === 'week' ? 604800000 : u === 'day' ? 86400000 : u === 'hour' ? 3600000 : u === 'minute' ? 60000 : u === 'second' ? 1000 : 1;
                            return Math.trunc(delta/divisor);
                          },
                          isBefore: function(other) { return date.getTime() < parseDate(other).getTime(); },
                          isAfter: function(other) { return date.getTime() > parseDate(other).getTime(); },
                          isSame: function(other) { return date.getTime() === parseDate(other).getTime(); },
                          isSameOrBefore: function(other) { return date.getTime() <= parseDate(other).getTime(); },
                          isSameOrAfter: function(other) { return date.getTime() >= parseDate(other).getTime(); }
                        };
                        return api;
                      }
                      function dayjs(input, format) { return makeDate(parseDate(input, format)); }
                      dayjs.extend = function() { return dayjs; };
                      dayjs.locale = function() { return 'en'; };
                      dayjs.utc = function(input, format) { return makeDate(parseDate(input, format)); };
                      dayjs.tz = function(input) { return makeDate(parseDate(input)); };
                      dayjs.unix = function(ts) { return makeDate(new Date(Number(ts) * 1000)); };
                      dayjs.default = dayjs;
                      return dayjs;
                    }
                    if (name === '@libs/isAbsoluteUrl') return {
                      isUrlAbsolute: function(url) { return /^https?:\/\//i.test(String(url)); }
                    };
                    if (name === 'urlencode') {
                      var urlencode = {
                        encode: function(value) { return encodeURIComponent(String(value)); },
                        decode: function(value) { return decodeURIComponent(String(value)); }
                      };
                      urlencode.default = urlencode;
                      return urlencode;
                    }
                    if (name === '@libs/utils') {
                      function utf8ToBytes(value) {
                        var encoded = unescape(encodeURIComponent(String(value)));
                        var bytes = [];
                        for (var i = 0; i < encoded.length; i++) bytes.push(encoded.charCodeAt(i) & 255);
                        return bytes;
                      }
                      function bytesToUtf8(value) {
                        var encoded = '';
                        for (var i = 0; i < value.length; i++) {
                          var byte = Number(value[i]) & 255;
                          encoded += '%' + (byte < 16 ? '0' : '') + byte.toString(16);
                        }
                        return decodeURIComponent(encoded);
                      }
                      return {utf8ToBytes:utf8ToBytes, bytesToUtf8:bytesToUtf8};
                    }
                    if (name === '@libs/aes') {
                      function gcm(key, nonce, aad) {
                        return {
                          encrypt: function(plaintext) { return __qnAesGcm('encrypt', key, nonce, aad == null ? null : aad, plaintext); },
                          decrypt: function(ciphertext) { return __qnAesGcm('decrypt', key, nonce, aad == null ? null : aad, ciphertext); }
                        };
                      }
                      return {gcm:gcm};
                    }
                    if (name === '@libs/defaultCover') return {defaultCover: ''};
                    // Published NovelFire-family bundles still import this historical alias.
                    if (name === '@/types/constants') return {defaultCover: ''};
                    if (name === '@libs/novelStatus') return {
                      NovelStatus: {
                        Unknown:'Unknown', Ongoing:'Ongoing', Completed:'Completed',
                        Licensed:'Licensed', PublishingFinished:'Publishing Finished',
                        Cancelled:'Cancelled', OnHiatus:'On Hiatus', STUB:'STUB',
                        Inactive:'Inactive',
                        // Backwards-compatible aliases used by older published bundles.
                        Paused:'On Hiatus', Dropped:'Cancelled'
                      }
                    };
                    if (name === '@libs/filterInputs') return {
                      FilterTypes: {
                        Picker:'Picker', CheckboxGroup:'Checkbox',
                        ExcludableCheckboxGroup:'XCheckbox',
                        Switch:'Switch', TextInput:'Text', Input:'Text'
                      }
                    };
                    throw new Error("Unsupported LNReader module import: " + name);
                  };

                  // LNReader plugins commonly use the browser Fetch API directly
                  // and construct request headers with new Headers(). Rhino has
                  // neither global, so route both through the same bounded bridge.
                  function QNHeaders(init) {
                    this._entries = {};
                    if (!init) return;
                    var self = this;
                    if (init instanceof QNHeaders) {
                      Object.keys(init._entries).forEach(function(key) {
                        var entry = init._entries[key];
                        self._entries[key] = [entry[0], entry[1]];
                      });
                    } else if (Array.isArray(init)) {
                      init.forEach(function(entry) {
                        if (Array.isArray(entry) && entry.length >= 2) self.append(entry[0], entry[1]);
                      });
                    } else {
                      Object.keys(init).forEach(function(key) { self.append(key, init[key]); });
                    }
                  }
                  QNHeaders.prototype.append = function(name, value) {
                    var key = String(name).toLowerCase();
                    var entry = this._entries[key];
                    this._entries[key] = entry
                      ? [entry[0], entry[1] + ', ' + String(value)]
                      : [String(name), String(value)];
                  };
                  QNHeaders.prototype.set = function(name, value) {
                    this._entries[String(name).toLowerCase()] = [String(name), String(value)];
                  };
                  QNHeaders.prototype.get = function(name) {
                    var entry = this._entries[String(name).toLowerCase()];
                    return entry ? entry[1] : null;
                  };
                  QNHeaders.prototype.has = function(name) {
                    return Object.prototype.hasOwnProperty.call(this._entries, String(name).toLowerCase());
                  };
                  QNHeaders.prototype.delete = function(name) {
                    delete this._entries[String(name).toLowerCase()];
                  };
                  QNHeaders.prototype.forEach = function(callback, thisArg) {
                    var self = this;
                    Object.keys(this._entries).forEach(function(key) {
                      var entry = self._entries[key];
                      callback.call(thisArg, entry[1], entry[0], self);
                    });
                  };
                  this.Headers = QNHeaders;
                  var qnFetchModule = this.require('@libs/fetch');
                  this.fetch = function(url, init) {
                    return qnFetchModule.fetchApi(url, init);
                  };
                  var qnStorageModule = this.require('@libs/storage');
                  if (typeof this.localStorage === 'undefined') this.localStorage = qnStorageModule.localStorage;
                  if (typeof this.sessionStorage === 'undefined') this.sessionStorage = qnStorageModule.sessionStorage;
                }).call(this);
            """.trimIndent()
            cx.evaluateString(scope, compatibilityBootstrap, "qn-compatibility.js", 1, null)


            cx.evaluateString(scope, source, "extension-${extension.id}.js", 1, null)
            ScriptableObject.putProperty(scope, "__qnOperation", operation)
            ScriptableObject.putProperty(scope, "__qnArgumentsJson", arguments.toString())
            val output = if (operation == "transformChapterHtml") {
                val html = arguments.optString(0)
                require(html.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) {
                    "Custom JavaScript input HTML exceeds the 8 MB limit."
                }
                ScriptableObject.putProperty(scope, "__qnTransformHtml", html)
                cx.evaluateString(
                    scope,
                    "var \$ = require('cheerio').load(__qnTransformHtml); var loadedCheerio = \$;",
                    "extension-custom-dom.js",
                    1,
                    null,
                )
                if (!customScript.isNullOrBlank()) {
                    cx.evaluateString(scope, customScript, "extension-custom.js", 1, null)
                }
                val transformed = cx.evaluateString(scope, "\$.html()", "extension-custom-result.js", 1, null)
                JSONObject.quote(Context.toString(transformed))
            } else {
                val invocation = """
                (function() {
                  var exported = module.exports;
                  var plugin = exported && (exported.default || exported);
                  if (!plugin) throw new Error('Bundle does not export a plugin object.');
                  var args = JSON.parse(__qnArgumentsJson);

                  if (__qnOperation === 'getImageRequestInit') {
                    return JSON.stringify(plugin.imageRequestInit || {});
                  }

                  if (__qnOperation === 'getPluginFilters') {
                    var filterDefinitions = plugin.filters || {};
                    return JSON.stringify({
                      filters: filterDefinitions,
                      order: Object.keys(filterDefinitions)
                    });
                  }

                  if (__qnOperation === 'getPluginSettings') {
                    var definitions = plugin.pluginSettings || {};
                    var settingsStore = require('@libs/storage').storage;
                    var output = {};
                    Object.keys(definitions).forEach(function(key) {
                      var definition = definitions[key] || {};
                      var settingType = typeof definition.type === 'string' ? definition.type : 'Text';
                      var defaultValue = definition.value;
                      if (settingType === 'Switch') {
                        defaultValue = defaultValue === true || defaultValue === 'true' || defaultValue === 1;
                      }
                      var currentValue = settingsStore.get(key);
                      if (typeof currentValue === 'undefined') {
                        currentValue = defaultValue;
                        settingsStore.set(key, defaultValue);
                      } else if (settingType === 'Switch' && typeof currentValue !== 'boolean') {
                        currentValue = currentValue === true || currentValue === 'true' || currentValue === 1;
                        settingsStore.set(key, currentValue);
                      }
                      output[key] = {
                        label: typeof definition.label === 'string' ? definition.label : key,
                        type: settingType,
                        value: currentValue,
                        options: Array.isArray(definition.options) ? definition.options : []
                      };
                    });
                    return JSON.stringify(output);
                  }

                  if (__qnOperation === 'setPluginSetting') {
                    var settingKey = String(args[0] == null ? '' : args[0]);
                    var value = args[1];
                    var settingDefinitions = plugin.pluginSettings || {};
                    if (!Object.prototype.hasOwnProperty.call(settingDefinitions, settingKey)) {
                      throw new Error('Unknown plugin setting: ' + settingKey);
                    }
                    var settingDefinition = settingDefinitions[settingKey] || {};
                    var settingType = settingDefinition.type || 'Text';
                    var options = Array.isArray(settingDefinition.options) ? settingDefinition.options : [];
                    if (settingType === 'Text' && typeof value !== 'string') {
                      throw new Error('Text plugin settings must be strings.');
                    }
                    if (settingType === 'Switch' && typeof value !== 'boolean') {
                      throw new Error('Switch plugin settings must be booleans.');
                    }
                    if (settingType === 'Select' &&
                        (typeof value !== 'string' || !options.some(function(option) { return option.value === value; }))) {
                      throw new Error('Select plugin setting value is not one of its declared options.');
                    }
                    if (settingType === 'CheckboxGroup' &&
                        (!Array.isArray(value) || value.some(function(selected) {
                          return typeof selected !== 'string' ||
                            !options.some(function(option) { return option.value === selected; });
                        }))) {
                      throw new Error('CheckboxGroup plugin setting values must be declared option strings.');
                    }
                    if (['Text','Switch','Select','CheckboxGroup'].indexOf(settingType) < 0) {
                      throw new Error('Unsupported plugin setting type: ' + settingType);
                    }
                    require('@libs/storage').storage.set(settingKey, value);
                    return JSON.stringify(true);
                  }

                  if (typeof plugin[__qnOperation] !== 'function') {
                    throw new Error('Bundle does not export operation: ' + __qnOperation);
                  }
                  if (__qnOperation === 'popularNovels' && args[1] && args[1].filters &&
                      Object.keys(args[1].filters).length === 0) {
                    args[1].filters = plugin.filters || {};
                  }
                  var result = plugin[__qnOperation].apply(plugin, args);
                  if (result && typeof result.then === 'function') {
                    var resolved = false, rejected = null;
                    result.then(function(value) { result = value; resolved = true; },
                                function(error) { rejected = error; });
                    if (!resolved && !rejected) __qnRunTimers();
                    if (rejected) throw rejected;
                    if (!resolved) throw new Error('Extension operation did not resolve after its queued timers.');
                  }
                  __qnRunTimers();
                  return JSON.stringify(result === undefined ? null : result);
                })()
            """.trimIndent()
            val result = cx.evaluateString(scope, invocation, "extension-call.js", 1, null)
            Context.toString(result)
            }
            output
        }
        require(output.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) {
            "Extension result exceeds the 8 MB limit."
        }
        // Ensure the operation produced valid JSON before it crosses into app models.
        val parsedResult = org.json.JSONTokener(output).nextValue()
        require(parsedResult != null) { "Extension returned invalid JSON." }
        ExecutionResult(output)
    }

    internal fun approvedHostsForBundle(site: String, source: String): Set<String> {
        val siteHost = URI(site).host?.lowercase()?.trimEnd('.')
            ?: throw IllegalArgumentException("Extension site URL is invalid.")
        val hosts = linkedSetOf(siteHost)
        val literalUrl = Regex("""https?://[A-Za-z0-9.-]+(?::[0-9]{1,5})?""", RegexOption.IGNORE_CASE)
        literalUrl.findAll(source).forEach { match ->
            runCatching {
                URI(match.value).host
                    ?.lowercase()
                    ?.trimEnd('.')
                    ?.takeIf { it.isNotBlank() }
                    ?.let(hosts::add)
            }
        }
        return hosts
    }

    private fun encodeBase64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            val hasSecond = index + 1 < bytes.size
            val hasThird = index + 2 < bytes.size
            val second = if (hasSecond) bytes[index + 1].toInt() and 0xff else 0
            val third = if (hasThird) bytes[index + 2].toInt() and 0xff else 0
            output.append(alphabet[first ushr 2])
            output.append(alphabet[((first and 0x03) shl 4) or (second ushr 4)])
            output.append(if (hasSecond) alphabet[((second and 0x0f) shl 2) or (third ushr 6)] else '=')
            output.append(if (hasThird) alphabet[third and 0x3f] else '=')
            index += 3
        }
        return output.toString()
    }

    private fun decodeBase64(input: String): ByteArray {
        val clean = input.replace(Regex("[\\r\\n\\s]"), "")
            .replace('-', '+')
            .replace('_', '/')
        if (clean.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream((clean.length * 3) / 4)
        var i = 0
        while (i + 1 < clean.length) {
            val c0 = base64Value(clean[i])
            val c1 = base64Value(clean[i + 1])
            val ch2 = if (i + 2 < clean.length) clean[i + 2] else '='
            val ch3 = if (i + 3 < clean.length) clean[i + 3] else '='
            val c2 = if (ch2 == '=') -1 else base64Value(ch2)
            val c3 = if (ch3 == '=') -1 else base64Value(ch3)
            if (c0 < 0 || c1 < 0) break
            out.write((c0 shl 2) or (c1 ushr 4))
            if (c2 >= 0) out.write(((c1 and 0x0f) shl 4) or (c2 ushr 2))
            if (c3 >= 0) out.write(((c2 and 0x03) shl 6) or c3)
            i += 4
        }
        return out.toByteArray()
    }

    private fun base64Value(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    /**
     * Small Cheerio-compatible selector surface backed by Jsoup. Only bounded HTML and CSS
     * selectors cross the bridge; Java objects are never exposed to the JavaScript scope.
     */
    private class HtmlBridge(private val baseUrl: String) {
        private val maxHtmlChars = 8 * 1024 * 1024
        private val maxSelectorChars = 500
        private val maxResults = 1_000
        private val maxDocuments = 20
        private val maxSelections = 2_000
        private var nextId = 1
        private val documents = mutableMapOf<Int, Document>()
        private val selections = mutableMapOf<Int, List<Node>>()

        // LNReader's :icontains is not part of Jsoup's selector dialect. Translate it
        // to a Java-regex selector with case-insensitive matching, preserving arbitrary
        // text (including punctuation) as literal content rather than regex syntax.
        private fun normalizeSelector(selector: String): String =
            Regex(""":icontains\(\s*(['"])(.*?)\1\s*\)""", RegexOption.IGNORE_CASE)
                .replace(selector) { match ->
                    val literalText = Regex.escape(match.groupValues[2])
                    ":matches((?i).*" + literalText + ".*)"
                }.replace(Regex("""\+\s*:matches"""), "+ :matches")

        private fun normalizeOptionalSelector(selector: String?): String? {
            val trimmed = selector?.trim().orEmpty()
            if (trimmed.isEmpty()) return null
            val normalized = normalizeSelector(trimmed)
            require(normalized.length <= maxSelectorChars) { "CSS selector is too long." }
            return normalized
        }

        fun parseEvents(html: String, xmlMode: Boolean = false): String {
            require(html.length <= maxHtmlChars) { "HTML input exceeds the 8 MB limit." }
            val document = if (xmlMode) {
                Jsoup.parse(html, baseUrl, Parser.xmlParser())
            } else {
                Jsoup.parse(html, baseUrl)
            }
            val events = JSONArray()
            var visited = 0
            fun walk(node: Node, depth: Int) {
                visited++
                require(visited <= 10_000) { "HTML parser exceeded the node limit." }
                require(depth <= 256) { "HTML parser exceeded the nesting-depth limit." }
                when (node) {
                    is Document -> node.childNodes().forEach { walk(it, depth + 1) }
                    is Element -> {
                        val attributes = JSONObject()
                        node.attributes().asList().take(100).forEach { attribute ->
                            attributes.put(attribute.key.take(100), attribute.value.take(10_000))
                        }
                        events.put(JSONObject().put("type", "open").put("name", node.tagName()).put("attribs", attributes))
                        node.childNodes().forEach { walk(it, depth + 1) }
                        events.put(JSONObject().put("type", "close").put("name", node.tagName()))
                    }
                    is TextNode -> events.put(JSONObject().put("type", "text").put("text", node.getWholeText().take(100_000)))
                    is DataNode -> events.put(JSONObject().put("type", "text").put("text", node.getWholeData().take(100_000)))
                    else -> node.childNodes().forEach { walk(it, depth + 1) }
                }
                require(events.length() <= 20_000) { "HTML parser exceeded the event limit." }
            }
            walk(document, 0)
            val result = events.toString()
            require(result.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) {
                "HTML parser output exceeds the 8 MB limit."
            }
            return result
        }

        fun invoke(command: String, selectionId: Int, value: String?, index: Int?): String {
            val nodes = selections[selectionId].orEmpty()
            val result: Any? = when (command) {
                "load" -> {
                    val html = value.orEmpty()
                    require(html.length <= maxHtmlChars) { "HTML input exceeds the 8 MB limit." }
                    require(documents.size < maxDocuments) { "Extension exceeded the HTML document limit." }
                    val id = nextId++
                    documents[id] = if (index == 1) {
                        Jsoup.parse(html, baseUrl, Parser.xmlParser())
                    } else {
                        Jsoup.parse(html, baseUrl)
                    }
                    id
                }
                "empty" -> storeNodes(emptyList())
                "create" -> {
                    val fragmentHtml = value.orEmpty()
                    require(fragmentHtml.length <= maxHtmlChars) { "HTML input exceeds the 8 MB limit." }
                    val isXml = documents[selectionId]?.outputSettings()?.syntax() ==
                        Document.OutputSettings.Syntax.xml
                    val created = if (isXml) {
                        Jsoup.parse(fragmentHtml, baseUrl, Parser.xmlParser()).childNodes()
                    } else {
                        Jsoup.parseBodyFragment(fragmentHtml, baseUrl).body().childNodes()
                    }
                    storeNodes(created)
                }
                "select" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    val found = documents[selectionId]?.select(selector)
                        ?: nodes.filterIsInstance<Element>().flatMap { parent ->
                            parent.select(selector).filterNot { it === parent }
                        }
                    storeNodes(found)
                }
                "find" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    storeNodes(nodes.filterIsInstance<Element>().flatMap { parent ->
                        parent.select(selector).filterNot { it === parent }
                    })
                }
                "children" -> {
                    val selector = normalizeOptionalSelector(value)
                    val children = nodes.filterIsInstance<Element>().flatMap { it.children() }
                    storeNodes(if (selector == null) children else children.filter { it.`is`(selector) })
                }
                "contents" -> storeNodes(
                    (documents[selectionId]?.let { listOf(it as Node) } ?: nodes)
                        .flatMap { it.childNodes() }
                )
                "parent" -> {
                    val selector = normalizeOptionalSelector(value)
                    val parents = nodes.mapNotNull { it.parentNode() }
                        .filterNot { it is Document }
                        .distinct()
                    storeNodes(if (selector == null) parents else parents.filter { it is Element && it.`is`(selector) })
                }
                "parents" -> {
                    val selector = normalizeOptionalSelector(value)
                    val allParents = nodes.filterIsInstance<Element>().flatMap { element ->
                        element.parents().filterNot { it is Document }
                    }.distinct()
                    storeNodes(if (selector == null) allParents else allParents.filter { it.`is`(selector) })
                }
                "closest" -> {
                    val selector = normalizeOptionalSelector(value)
                    if (selector == null) {
                        storeNodes(emptyList())
                    } else {
                        val matched = nodes.filterIsInstance<Element>().mapNotNull { start ->
                            var cur: Element? = start
                            while (cur != null && cur !is Document && !cur.`is`(selector)) {
                                cur = cur.parent()
                            }
                            if (cur != null && cur !is Document) cur else null
                        }.distinct()
                        storeNodes(matched)
                    }
                }
                "next" -> {
                    val selector = normalizeOptionalSelector(value)
                    val nexts = nodes.filterIsInstance<Element>().mapNotNull { it.nextElementSibling() }
                    storeNodes(if (selector == null) nexts else nexts.filter { it.`is`(selector) })
                }
                "prev" -> {
                    val selector = normalizeOptionalSelector(value)
                    val prevs = nodes.filterIsInstance<Element>().mapNotNull { it.previousElementSibling() }
                    storeNodes(if (selector == null) prevs else prevs.filter { it.`is`(selector) })
                }
                "nextAll" -> {
                    val selector = normalizeOptionalSelector(value)
                    val collected = mutableListOf<Element>()
                    nodes.filterIsInstance<Element>().forEach { start ->
                        var sibling = start.nextElementSibling()
                        var visited = 0
                        while (sibling != null && visited < maxResults) {
                            if (selector == null || sibling.`is`(selector)) collected.add(sibling)
                            sibling = sibling.nextElementSibling()
                            visited++
                        }
                    }
                    storeNodes(collected.distinct())
                }
                "prevAll" -> {
                    val selector = normalizeOptionalSelector(value)
                    val collected = mutableListOf<Element>()
                    nodes.filterIsInstance<Element>().forEach { start ->
                        var sibling = start.previousElementSibling()
                        var visited = 0
                        while (sibling != null && visited < maxResults) {
                            if (selector == null || sibling.`is`(selector)) collected.add(sibling)
                            sibling = sibling.previousElementSibling()
                            visited++
                        }
                    }
                    storeNodes(collected.distinct())
                }
                "siblings" -> {
                    val selector = normalizeOptionalSelector(value)
                    val siblings = nodes.filterIsInstance<Element>()
                        .flatMap { it.siblingElements() }
                        .distinct()
                    storeNodes(if (selector == null) siblings else siblings.filter { it.`is`(selector) })
                }
                "nextUntil" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    val collected = mutableListOf<Element>()
                    nodes.filterIsInstance<Element>().forEach { start ->
                        var sibling = start.nextElementSibling()
                        var visited = 0
                        while (sibling != null) {
                            val currentSibling = sibling ?: break
                            if (currentSibling.`is`(selector)) break
                            collected.add(currentSibling)
                            sibling = currentSibling.nextElementSibling()
                            visited += 1
                            require(visited <= maxResults) {
                                "Cheerio nextUntil exceeded the sibling limit."
                            }
                        }
                    }
                    storeNodes(collected.distinct())
                }
                "nextNode" -> storeNodes(nodes.firstOrNull()?.let { sibling(it, 1) }?.let(::listOf).orEmpty())
                "clone" -> storeNodes(nodes.map { it.clone() })
                "merge" -> {
                    val ids = JSONArray(value.orEmpty())
                    storeNodes((0 until ids.length()).flatMap { selections[ids.optInt(it)].orEmpty() })
                }
                "filter" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.length <= maxSelectorChars) { "CSS selector is too long." }
                    storeNodes(nodes.filterIsInstance<Element>().filter { it.`is`(selector) })
                }
                "is" -> {
                    val selector = normalizeOptionalSelector(value)
                    selector != null && nodes.filterIsInstance<Element>().any { it.`is`(selector) }
                }
                "isNodes" -> {
                    val target = selections[value?.toIntOrNull()].orEmpty().toSet()
                    nodes.any { it in target }
                }
                "not" -> {
                    val selector = normalizeOptionalSelector(value)
                    if (selector == null) selectionId else {
                        storeNodes(nodes.filter { node -> node !is Element || !node.`is`(selector) })
                    }
                }
                "notNodes" -> {
                    val excluded = selections[value?.toIntOrNull()].orEmpty().toSet()
                    storeNodes(nodes.filterNot { it in excluded })
                }
                "has" -> {
                    val selector = normalizeSelector(value.orEmpty())
                    require(selector.isNotBlank() && selector.length <= maxSelectorChars) {
                        "CSS selector is empty or too long."
                    }
                    storeNodes(nodes.filterIsInstance<Element>().filter { it.select(selector).isNotEmpty() })
                }
                "first" -> storeNodes(nodes.take(1))
                "last" -> storeNodes(nodes.takeLast(1))
                "eq" -> {
                    val requested = index ?: 0
                    val resolved = if (requested < 0) nodes.size + requested else requested
                    storeNodes(nodes.getOrNull(resolved)?.let { listOf(it) }.orEmpty())
                }
                "slice" -> {
                    val startRaw = index ?: 0
                    val start = if (startRaw < 0) (nodes.size + startRaw).coerceAtLeast(0) else startRaw.coerceAtMost(nodes.size)
                    val endRaw = value?.takeIf { it.isNotEmpty() }?.toIntOrNull()
                    val end = if (endRaw == null) nodes.size else if (endRaw < 0) (nodes.size + endRaw).coerceAtLeast(0) else endRaw.coerceAtMost(nodes.size)
                    storeNodes(if (start >= end) emptyList() else nodes.subList(start, end))
                }
                "index" -> {
                    val mode = index ?: 0
                    val first = nodes.firstOrNull()
                    if (first == null) {
                        -1
                    } else if (mode == 1) {
                        val target = selections[value?.toIntOrNull()]?.firstOrNull()
                        if (target == null) -1 else nodes.indexOf(target)
                    } else if (mode == 2 && !value.isNullOrBlank()) {
                        val selector = normalizeSelector(value)
                        val pool = first.ownerDocument()?.select(selector).orEmpty()
                        pool.indexOf(first)
                    } else {
                        val siblings = first.parentNode()?.childNodes()?.filterIsInstance<Element>().orEmpty()
                        siblings.indexOf(first)
                    }
                }
                "nodeType" -> nodes.firstOrNull()?.let(::nodeType)
                "nodeData" -> nodes.firstOrNull()?.let(::nodeData)
                "attribs" -> nodes.filterIsInstance<Element>().firstOrNull()?.let { element ->
                    val obj = JSONObject()
                    element.attributes().asList().take(100).forEach { attr ->
                        obj.put(attr.key.take(100), attr.value.take(10_000))
                    }
                    obj.toString()
                } ?: "{}"
                "length" -> documents[selectionId]?.let { 1 } ?: nodes.size
                "text" -> documents[selectionId]?.text()?.take(100_000)
                    ?: nodes.joinToString("") { nodeText(it) }.take(100_000)
                "textSet" -> {
                    nodes.filterIsInstance<Element>().forEach { it.text(value.orEmpty()) }
                    selectionId
                }
                "html" -> documents[selectionId]?.html()?.take(100_000)
                    ?: nodes.firstOrNull()?.let { node ->
                        if (node is Element) node.html().take(100_000) else node.outerHtml().take(100_000)
                    }.orEmpty()
                "htmlSet" -> {
                    val doc = documents[selectionId]
                    if (doc != null) {
                        doc.body().html(value.orEmpty())
                    } else {
                        nodes.filterIsInstance<Element>().forEach { it.html(value.orEmpty()) }
                    }
                    selectionId
                }
                "attr" -> nodes.filterIsInstance<Element>().firstOrNull()?.let { element ->
                    value?.let { if (element.hasAttr(it)) element.attr(it).take(100_000) else null }
                }
                "attrSet" -> {
                    val pair = JSONArray(value.orEmpty())
                    val name = pair.optString(0)
                    val attributeValue = pair.optString(1)
                    require(name.isNotBlank() && name.length <= 200 && attributeValue.length <= 100_000) {
                        "HTML attribute name or value is invalid."
                    }
                    nodes.filterIsInstance<Element>().forEach { it.attr(name, attributeValue) }
                    selectionId
                }
                "data" -> {
                    val element = nodes.filterIsInstance<Element>().firstOrNull()
                    if (element == null) null
                    else if (value.isNullOrBlank()) {
                        val obj = JSONObject()
                        element.attributes().asList().forEach { attr ->
                            if (attr.key.startsWith("data-")) {
                                obj.put(attr.key.removePrefix("data-"), parseDataValue(attr.value))
                            }
                        }
                        obj.toString()
                    } else {
                        val kebab = value.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
                        val rawAttr = when {
                            element.hasAttr("data-$value") -> element.attr("data-$value")
                            element.hasAttr("data-$kebab") -> element.attr("data-$kebab")
                            else -> null
                        }
                        rawAttr?.let { rawStr ->
                            val parsed = parseDataValue(rawStr)
                            if (parsed is String) parsed else parsed.toString()
                        }
                    }
                }
                "val" -> nodes.filterIsInstance<Element>().firstOrNull()?.let { el ->
                    if (el.tagName().equals("textarea", ignoreCase = true)) el.text()
                    else if (el.hasAttr("value")) el.attr("value")
                    else ""
                } ?: ""
                "valSet" -> {
                    nodes.filterIsInstance<Element>().forEach { el ->
                        if (el.tagName().equals("textarea", ignoreCase = true)) el.text(value.orEmpty())
                        else el.attr("value", value.orEmpty())
                    }
                    selectionId
                }
                "removeAttr" -> {
                    nodes.filterIsInstance<Element>().forEach { it.removeAttr(value.orEmpty()) }
                    selectionId
                }
                "addClass" -> {
                    val classes = value.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    nodes.filterIsInstance<Element>().forEach { el -> classes.forEach(el::addClass) }
                    selectionId
                }
                "removeClass" -> {
                    val raw = value.orEmpty().trim()
                    if (raw.isEmpty()) {
                        nodes.filterIsInstance<Element>().forEach { it.removeAttr("class") }
                    } else {
                        val classes = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        nodes.filterIsInstance<Element>().forEach { el -> classes.forEach(el::removeClass) }
                    }
                    selectionId
                }
                "toggleClass" -> {
                    val classes = value.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    nodes.filterIsInstance<Element>().forEach { el ->
                        classes.forEach { cls ->
                            when (index) {
                                1 -> el.addClass(cls)
                                0 -> el.removeClass(cls)
                                else -> el.toggleClass(cls)
                            }
                        }
                    }
                    selectionId
                }
                "nodeName" -> nodes.firstOrNull()?.let { node ->
                    if (node is Element) node.tagName() else node.nodeName()
                }
                "outerHtml" -> nodes.firstOrNull()?.outerHtml()?.take(100_000)
                "replaceWith" -> {
                    nodes.forEach { node -> node.before(value.orEmpty()); node.remove() }
                    selectionId
                }
                "before" -> {
                    nodes.forEach { it.before(value.orEmpty()) }
                    selectionId
                }
                "after" -> {
                    nodes.asReversed().forEach { it.after(value.orEmpty()) }
                    selectionId
                }
                "append" -> {
                    nodes.filterIsInstance<Element>().forEach { it.append(value.orEmpty()) }
                    selectionId
                }
                "prepend" -> {
                    nodes.filterIsInstance<Element>().forEach { it.prepend(value.orEmpty()) }
                    selectionId
                }
                "wrap" -> {
                    nodes.forEach { it.wrap(value.orEmpty()) }
                    selectionId
                }
                "hasClass" -> nodes.filterIsInstance<Element>().firstOrNull()?.hasClass(value.orEmpty()) ?: false
                "remove" -> {
                    nodes.forEach { it.remove() }
                    selectionId
                }
                else -> throw IllegalArgumentException("Unsupported HTML selector operation.")
            }
            return when (result) {
                null -> "null"
                is String -> JSONObject.quote(result)
                is Number, is Boolean -> result.toString()
                else -> result.toString()
            }
        }

        private fun parseDataValue(raw: String): Any {
            val trimmed = raw.trim()
            if (trimmed == "true") return true
            if (trimmed == "false") return false
            if (trimmed == "null") return JSONObject.NULL
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
            ) {
                runCatching { return org.json.JSONTokener(trimmed).nextValue() }
            }
            trimmed.toLongOrNull()?.let { return it }
            trimmed.toDoubleOrNull()?.let { return it }
            return raw.take(100_000)
        }

        private fun nodeType(node: Node): String = when (node) {
            is Element -> "tag"
            is TextNode, is DataNode -> "text"
            is Comment -> "comment"
            else -> "root"
        }

        private fun nodeData(node: Node): String? = when (node) {
            is TextNode -> node.getWholeText()
            is DataNode -> node.getWholeData()
            is Comment -> node.getData()
            else -> null
        }

        private fun nodeText(node: Node): String = when (node) {
            is Element -> if (
                node.tagName().equals("script", ignoreCase = true) ||
                node.tagName().equals("style", ignoreCase = true)
            ) {
                node.data().ifEmpty { node.text() }
            } else {
                node.text()
            }
            is TextNode -> node.getWholeText()
            is DataNode -> node.getWholeData()
            is Comment -> node.getData()
            else -> ""
        }

        private fun sibling(node: Node, offset: Int): Node? {
            val siblings = node.parentNode()?.childNodes().orEmpty()
            val current = siblings.indexOf(node)
            return if (current < 0) null else siblings.getOrNull(current + offset)
        }

        private fun store(elements: List<Element>): Int = storeNodes(elements)

        private fun storeNodes(nodes: List<Node>): Int {
            require(selections.size < maxSelections) { "Extension exceeded the HTML selection limit." }
            val id = nextId++
            selections[id] = nodes.take(maxResults)
            return id
        }
    }

    private class BudgetedContextFactory(
        private val instructionLimit: Int,
        private val operation: String,
    ) : ContextFactory() {
        private var instructions = 0

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            instructions += instructionCount
            if (instructions > instructionLimit) {
                throw IllegalStateException(
                    "Extension exceeded its JavaScript instruction budget during $operation " +
                        "($instructionLimit instructions)."
                )
            }
        }
    }
}
