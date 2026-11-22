package com.lagradost.quicknovel.network

import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.AnyThread
import com.lagradost.nicehttp.Requests.Companion.await
import com.lagradost.quicknovel.MainActivity.Companion.app
import com.lagradost.quicknovel.USER_AGENT
import com.lagradost.quicknovel.network.utils.CookiesUtils.clearCookiesForHost
import com.lagradost.quicknovel.network.utils.CookiesUtils.getAllCookiesForUrl
import com.lagradost.quicknovel.network.utils.CookiesUtils.toCookieString
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

internal fun isCloudflareChallengeResponse(
    statusCode: Int,
    cfRay: String?,
    server: String?,
    cfMitigated: String?,
    location: String?,
    bodySample: String,
): Boolean {
    if (cfMitigated?.equals("challenge", ignoreCase = true) == true) return true
    val hasCloudflareHeaders =
        !cfRay.isNullOrBlank() ||
            server?.contains("cloudflare", ignoreCase = true) == true

    val lowerBody = bodySample.lowercase()
    val isChallengeBody = lowerBody.contains("cf-browser-verification") ||
        lowerBody.contains("checking your browser") ||
        lowerBody.contains("just a moment") ||
        lowerBody.contains("attention required! | cloudflare") ||
        lowerBody.contains("/cdn-cgi/") ||
        lowerBody.contains("one moment...") ||
        lowerBody.contains("un momento…") ||
        lowerBody.contains("cf-turnstile") ||
        lowerBody.contains("challenges.cloudflare.com") ||
        lowerBody.contains("verifying access") ||
        lowerBody.contains("confirm you're human") ||
        lowerBody.contains("please complete the turnstile")

    if (statusCode in setOf(403, 429, 503, 520) && (hasCloudflareHeaders || isChallengeBody)) {
        return true
    }

    return location.orEmpty().lowercase().contains("/cdn-cgi/") || isChallengeBody
}

/**
 * Interceptor designed to bypass Cloudflare and Turnstile protections.
 * It detects challenges and uses a hidden WebView to solve them and capture the resulting session.
 */
@AnyThread
class CloudflareKiller : Interceptor {
    companion object {
        const val TAG = "CloudflareKiller"
        private val mutex = Mutex() // Ensures only one bypass runs at a time
    }

    /*//this is used to testing
    init {
        CookieManager.getInstance().removeAllCookies(null)
    }
    */
    /** In-memory cache for cookies associated with each host*/
    val savedCookies = ConcurrentHashMap<String, Map<String, String>>()

    override fun intercept(chain: Interceptor.Chain): Response = runBlocking {
        val request = chain.request()
        val url = request.url.toString()
        val host = request.url.host

        // If we already have cookies in CookieManager (e.g. cf_clearance, fr_read, or session
        // cookies from the integrated in-app browser), replay them with the captured WebView UA.
        val initialCookies = savedCookies[host]?.takeIf { it.isNotEmpty() } ?: getAllCookiesForUrl(url)
        if (initialCookies.isNotEmpty()) {
            val response = proceed(request, initialCookies)
            if (!looksLikeCloudflareChallenge(response)) return@runBlocking response

            // Only clear saved CF clearance if it was invalidated; keep trying bypass/verification
            response.close()
            if (initialCookies.containsKey("cf_clearance")) {
                clearCookiesForHost(request.url)
                savedCookies.remove(host)
            }
        }

        // Try the request normally. Only invoke WebView bypass when the response looks like a challenge.
        val initialResponse = chain.proceed(request)
        if (!looksLikeCloudflareChallenge(initialResponse)) return@runBlocking initialResponse
        initialResponse.close()

        // Bypass needed. Locked section to prevent multiple WebViews from opening
        mutex.withLock {
            val currentCookies = savedCookies[host]?.takeIf { it.isNotEmpty() } ?: getAllCookiesForUrl(url)
            if (currentCookies.isNotEmpty() && currentCookies != initialCookies) {
                val response = proceed(request, currentCookies)
                if (!looksLikeCloudflareChallenge(response)) return@runBlocking response

                response.close()
                if (currentCookies.containsKey("cf_clearance")) {
                    clearCookiesForHost(request.url)
                    savedCookies.remove(host)
                }
            }

            Log.d(TAG, "Resolving Cloudflare for $host...")
            val bypassResponse = bypassCloudflare(request)

            if (bypassResponse != null) {
                if (!looksLikeCloudflareChallenge(bypassResponse)) {
                    Log.d(TAG, "Succeeded bypassing cloudflare: ${request.url}")
                    return@runBlocking bypassResponse
                }
                bypassResponse.close()
            }
        }

        val finalResponse = chain.proceed(request)
        if (looksLikeCloudflareChallenge(finalResponse)) {
            finalResponse.close()
            throw CloudflareChallengeException(
                url = url,
                host = host,
                message = "Cloudflare verification required for $host. Tap 'Open verification' to continue.",
            )
        }
        return@runBlocking finalResponse
    }

    private fun looksLikeCloudflareChallenge(response: Response): Boolean {
        val bodySample = runCatching {
            response.peekBody(1024 * 10).string()
        }.getOrDefault("")

        return isCloudflareChallengeResponse(
            statusCode = response.code,
            cfRay = response.header("cf-ray"),
            server = response.header("server"),
            cfMitigated = response.header("cf-mitigated"),
            location = response.header("location"),
            bodySample = bodySample,
        )
    }

    /**
     * Reconstructs the request to mirror a real browser's identity
     * This clones the WebView's header order and Client Hints to bypass bot detection
     */
    private suspend fun proceed(request: Request, cookiesMap: Map<String, String>): Response {
        val host = request.url.host
        val ua = WebViewResolver.webViewUserAgent
                ?: WebViewResolver.getWebViewUserAgent()
                ?: USER_AGENT
        val captured = WebViewResolver.capturedHeaders[host] ?: emptyMap()

        val builder = Headers.Builder()
        builder.add("Host", host)
        captured.filter { it.key.lowercase().startsWith("sec-ch-ua") }.forEach { (k, v) ->
            val masked = v.replace(", \"Android WebView\";v=\"150\"", "")
                .replace("Android WebView", "Chromium")
            builder.add(k, masked)
        }
        builder.add("User-Agent", ua)
        builder.add(
            "Accept",
            captured["Accept"]
                ?: "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        )
        builder.add("Origin", "${request.url.scheme}://${request.url.host}")

        captured.filter { it.key.lowercase().startsWith("sec-fetch-") }.forEach { (k, v) ->
            builder.add(k, v)
        }
        val referer = request.header("Referer") ?: captured["Referer"]
        ?: "${request.url.scheme}://${request.url.host}/"
        builder.add("Referer", referer)
        builder.add("Accept-Language", captured["Accept-Language"] ?: "en-US,en;q=0.9")

        val finalCookies = cookiesMap + request.headers.values("Cookie").associate {
            val split = it.split("=", limit = 2)
            (split.getOrNull(0)?.trim() ?: "") to (split.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }

        builder.add("Cookie", finalCookies.toCookieString())

        val usedKeys = builder.build().names()
        request.headers.forEach { (k, v) ->
            if (!usedKeys.contains(k) //&&
            //!k.equals(DefaultImagesHeaders.useCloudflareKillerHeader.first, true) &&
            /*!k.equals(DefaultImagesHeaders.useIgnore500Header.first, true)*/) {
                builder.add(k, v)
            }
        }

        return app.baseClient.newCall(
            request.newBuilder()
                .headers(builder.build())
                .build()
        ).await()
    }

    /** Invokes the WebView to solve the challenge and extract new cookies*/
    private suspend fun bypassCloudflare(request: Request): Response? {
        val url = request.url.toString()
        val host = request.url.host

        // If no cookies then try to get them
        // Remove this if statement if cookies expire
        Log.d(TAG, "Loading webview to solve cloudflare for ${request.url}")
        WebViewResolver(
            // Never exit based on url
            interceptUrl = null,
            // Cloudflare needs default user agent
            userAgent = null,
            // Cannot use okhttp (i think intercepting cookies fails which causes the issues)
            useOkhttp = false,
        ).resolveUsingWebView(
            url = url,
            maxTimeout = 22.seconds,
        ) {
            /**
             * Returns true if the cf cookies were successfully fetched from the CookieManager
             * */
            getAllCookiesForUrl(it.url.toString()).containsKey("cf_clearance")
        }

        val cookies = getAllCookiesForUrl(url)
        if (!cookies.containsKey("cf_clearance")) return null
        savedCookies[host] = cookies
        return proceed(request, cookies)
    }
}