package com.lagradost.quicknovel.network

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.network.utils.CookiesUtils
import com.lagradost.quicknovel.util.AppUtils
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import java.util.Locale

/**
 * Integrated in-app WebView browser and interactive Cloudflare/Turnstile verification screen.
 *
 * Keeps all cookies in Android's CookieManager scoped cleanly per site, captures host-specific
 * User-Agent / Client-Hint headers for provider HTTP calls, and lets users manually complete
 * Cloudflare Turnstile or anti-bot challenges inside QN before retrying failed chapter/novel loads.
 */
class CloudflareVerificationActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_URL = "extra_verification_url"
        private const val EXTRA_MODE = "extra_browser_mode"
        const val MODE_VERIFICATION = "verification"
        const val MODE_BROWSER = "browser"

        fun createIntent(context: Context, url: String): Intent {
            return Intent(context, CloudflareVerificationActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_MODE, MODE_VERIFICATION)
            }
        }

        fun createBrowserIntent(context: Context, url: String): Intent {
            return Intent(context, CloudflareVerificationActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_MODE, MODE_BROWSER)
            }
        }

        fun openInAppBrowser(context: Context, url: String): Boolean {
            if (url.isBlank() || (!url.startsWith("https://", true) && !url.startsWith("http://", true))) {
                return false
            }
            return runCatching {
                context.startActivity(createBrowserIntent(context, url))
                true
            }.getOrDefault(false)
        }
    }

    private var webView: WebView? = null
    private lateinit var progressBar: ProgressBar
    private lateinit var statusContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var doneButton: Button
    private lateinit var titleView: TextView
    private lateinit var urlBar: EditText
    private lateinit var backNavButton: ImageButton
    private lateinit var forwardNavButton: ImageButton
    private lateinit var reloadStopButton: ImageButton
    private lateinit var errorBanner: TextView

    private var currentUrl: String = ""
    private var initialHost: String = ""
    private var isVerificationMode: Boolean = true
    private var isLoadingPage: Boolean = false
    private var verifiedResult = false
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val targetUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        if (targetUrl.isBlank() || (!targetUrl.startsWith("https://", true) && !targetUrl.startsWith("http://", true))) {
            finishWithResult(false)
            return
        }
        currentUrl = targetUrl
        isVerificationMode = intent.getStringExtra(EXTRA_MODE) != MODE_BROWSER
        initialHost = runCatching { URI(targetUrl).host.orEmpty() }.getOrDefault("")

        val dp = resources.displayMetrics.density
        fun px(value: Int): Int = (value * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor("#0F1117"))
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(8), px(8))
            setBackgroundColor(Color.parseColor("#181B24"))
        }

        val closeButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_baseline_arrow_back_24)
            setColorFilter(Color.WHITE)
            background = createPillBackground(Color.parseColor("#252A38"), px(18).toFloat())
            contentDescription = getString(R.string.close)
            layoutParams = LinearLayout.LayoutParams(px(40), px(40))
            setOnClickListener { finishWithResult(verifiedResult) }
        }

        val urlPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createPillBackground(Color.parseColor("#232734"), px(14).toFloat())
            setPadding(px(12), px(6), px(12), px(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = px(8)
                marginEnd = px(6)
            }
        }

        titleView = TextView(this).apply {
            text = if (isVerificationMode) "Verify $initialHost" else initialHost.ifBlank { getString(R.string.browser_title) }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        urlBar = EditText(this).apply {
            setText(targetUrl)
            setTextColor(Color.parseColor("#B8C1D6"))
            setHintTextColor(Color.parseColor("#7D8599"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = null
            setPadding(0, 0, 0, 0)
            maxLines = 1
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { v, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    val typed = text?.toString()?.trim().orEmpty()
                    val normalized = normalizeInputUrl(typed)
                    if (normalized != null) {
                        webView?.loadUrl(normalized)
                        clearFocus()
                        hideKeyboard(v)
                    } else {
                        Toast.makeText(this@CloudflareVerificationActivity, "Enter a valid http(s) URL", Toast.LENGTH_SHORT).show()
                    }
                    true
                } else {
                    false
                }
            }
        }

        urlPill.addView(titleView)
        urlPill.addView(urlBar)

        reloadStopButton = ImageButton(this).apply {
            setImageResource(R.drawable.refresh)
            setColorFilter(Color.WHITE)
            background = createPillBackground(Color.parseColor("#252A38"), px(18).toFloat())
            contentDescription = getString(R.string.refresh)
            layoutParams = LinearLayout.LayoutParams(px(40), px(40)).apply {
                marginEnd = px(4)
            }
            setOnClickListener {
                if (isLoadingPage) {
                    webView?.stopLoading()
                } else {
                    webView?.reload()
                }
            }
        }

        val moreButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_baseline_more_vert_24)
            setColorFilter(Color.WHITE)
            background = createPillBackground(Color.parseColor("#252A38"), px(18).toFloat())
            contentDescription = getString(R.string.history_more_options)
            layoutParams = LinearLayout.LayoutParams(px(40), px(40))
            setOnClickListener { anchor ->
                showBrowserMenu(anchor)
            }
        }

        topBar.addView(closeButton)
        topBar.addView(urlPill)
        topBar.addView(reloadStopButton)
        topBar.addView(moreButton)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 15
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(3))
        }

        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(8), px(12), px(8))
            setBackgroundColor(Color.parseColor("#222634"))
            visibility = if (isVerificationMode) View.VISIBLE else View.GONE
        }

        statusView = TextView(this).apply {
            text = "Complete the verification challenge below, then tap Done & Retry."
            setTextColor(Color.parseColor("#DCE2F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = px(8)
            }
        }

        doneButton = Button(this).apply {
            text = getString(R.string.done_and_retry)
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = createPillBackground(Color.parseColor("#3B6AF5"), px(10).toFloat())
            setPadding(px(14), px(6), px(14), px(6))
            setOnClickListener { finishWithResult(true) }
        }

        statusContainer.addView(statusView)
        statusContainer.addView(doneButton)

        errorBanner = TextView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#4B1D24"))
            setTextColor(Color.parseColor("#FFD9DF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(px(12), px(8), px(12), px(8))
        }

        val createdWebView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            WebViewResolver.webViewUserAgent = settings.userAgentString

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    isLoadingPage = newProgress in 1..99
                    progressBar.visibility = if (isLoadingPage) View.VISIBLE else View.GONE
                    reloadStopButton.setImageResource(
                        if (isLoadingPage) R.drawable.ic_baseline_stop_24 else R.drawable.refresh
                    )
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    if (!title.isNullOrBlank()) {
                        titleView.text = title
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val reqUrl = request?.url?.toString() ?: return false
                    return !(reqUrl.startsWith("https://", true) || reqUrl.startsWith("http://", true))
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (request != null && request.isForMainFrame) {
                        val reqUrl = request.url?.toString().orEmpty()
                        if (!reqUrl.contains("/cdn-cgi/") && !reqUrl.contains("cloudflare")) {
                            val host = runCatching { URI(reqUrl).host?.lowercase(Locale.ROOT) }.getOrNull()
                            val hints = request.requestHeaders.filterKeys { key ->
                                val lower = key.lowercase(Locale.ROOT)
                                lower == "accept-language" || lower.startsWith("sec-ch-ua")
                            }
                            if (!host.isNullOrBlank() && hints.isNotEmpty()) {
                                WebViewResolver.capturedHeaders[host] = hints
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        val desc = error?.description?.toString()?.takeIf { it.isNotBlank() } ?: "Page failed to load"
                        errorBanner.text = "Could not load page: $desc"
                        errorBanner.visibility = View.VISIBLE
                    }
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    errorBanner.visibility = View.GONE
                    runCatching { CookieManager.getInstance().flush() }
                    val urlNow = finishedUrl ?: view?.url ?: currentUrl
                    currentUrl = urlNow
                    if (!urlBar.hasFocus()) {
                        urlBar.setText(urlNow)
                    }
                    val hostNow = runCatching { URI(urlNow).host }.getOrNull() ?: initialHost
                    if (titleView.text.isNullOrBlank()) {
                        titleView.text = hostNow
                    }

                    updateNavButtons()

                    val cookies = runCatching { CookiesUtils.getAllCookiesForUrl(urlNow) }.getOrDefault(emptyMap())
                    val hasClearanceOrSession = cookies.containsKey("cf_clearance") || cookies.containsKey("fr_read")

                    // Inspect page DOM to know whether Cloudflare / Turnstile / Fenrir gate is still displayed.
                    view?.evaluateJavascript(
                        """
                        (function() {
                          var t = (document.title || '').toLowerCase();
                          var b = (document.body && document.body.innerText ? document.body.innerText.slice(0, 2000) : '').toLowerCase();
                          var challenged = !!(
                            document.querySelector('#challenge-form') ||
                            document.querySelector('#challenge-running') ||
                            document.querySelector('.cf-turnstile') ||
                            document.querySelector('iframe[src*="challenges.cloudflare.com"]') ||
                            t.indexOf('just a moment') !== -1 ||
                            t.indexOf('attention required') !== -1 ||
                            b.indexOf('verifying access') !== -1 ||
                            b.indexOf("confirm you're human") !== -1 ||
                            b.indexOf('please complete the turnstile') !== -1
                          );
                          return challenged ? 'CHALLENGED' : 'CLEAR';
                        })();
                        """.trimIndent()
                    ) { state ->
                        val cleanState = state?.trim('"')
                        if (cleanState == "CHALLENGED") {
                            statusContainer.visibility = View.VISIBLE
                            statusContainer.setBackgroundColor(Color.parseColor("#2A2230"))
                            statusView.setTextColor(Color.parseColor("#F5DCE8"))
                            statusView.text = "Challenge active on $hostNow. Complete it below, then tap Done & Retry."
                        } else if (hasClearanceOrSession || (cleanState == "CLEAR" && isVerificationMode)) {
                            verifiedResult = true
                            if (isVerificationMode) {
                                statusContainer.visibility = View.VISIBLE
                                statusContainer.setBackgroundColor(Color.parseColor("#1D3B2A"))
                                statusView.setTextColor(Color.parseColor("#D4F5DF"))
                                statusView.text = "Session cookies saved for $hostNow. Tap Done & Retry."
                                doneButton.text = "${getString(R.string.done_and_retry)} ✓"
                            }
                        }
                    }
                }
            }
            loadUrl(targetUrl)
        }
        webView = createdWebView

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12), px(6), px(12), px(6))
            setBackgroundColor(Color.parseColor("#181B24"))
        }

        backNavButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_baseline_arrow_back_24)
            setColorFilter(Color.WHITE)
            background = createPillBackground(Color.TRANSPARENT, px(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, px(40), 1f)
            setOnClickListener {
                if (webView?.canGoBack() == true) {
                    webView?.goBack()
                    updateNavButtons()
                }
            }
        }

        forwardNavButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_baseline_arrow_forward_24)
            setColorFilter(Color.WHITE)
            background = createPillBackground(Color.TRANSPARENT, px(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, px(40), 1f)
            setOnClickListener {
                if (webView?.canGoForward() == true) {
                    webView?.goForward()
                    updateNavButtons()
                }
            }
        }

        val navReloadButton = ImageButton(this).apply {
            setImageResource(R.drawable.refresh)
            setColorFilter(Color.WHITE)
            background = createPillBackground(Color.TRANSPARENT, px(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(0, px(40), 1f)
            setOnClickListener { webView?.reload() }
        }

        val clearCookiesButton = Button(this).apply {
            text = "Clear cookies"
            isAllCaps = false
            setTextColor(Color.parseColor("#DCE2F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = createPillBackground(Color.parseColor("#252A38"), px(10).toFloat())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36)).apply {
                marginStart = px(8)
            }
            setOnClickListener { clearCurrentSiteCookies() }
        }

        bottomBar.addView(backNavButton)
        bottomBar.addView(forwardNavButton)
        bottomBar.addView(navReloadButton)
        bottomBar.addView(clearCookiesButton)

        root.addView(topBar)
        root.addView(progressBar)
        root.addView(statusContainer)
        root.addView(errorBanner)
        root.addView(createdWebView)
        root.addView(bottomBar)
        setContentView(root)
        updateNavButtons()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView?.canGoBack() == true) {
                        webView?.goBack()
                    } else {
                        finishWithResult(verifiedResult)
                    }
                }
            },
        )
    }

    private fun updateNavButtons() {
        val canBack = webView?.canGoBack() == true
        val canForward = webView?.canGoForward() == true
        backNavButton.isEnabled = canBack
        backNavButton.alpha = if (canBack) 1f else 0.35f
        forwardNavButton.isEnabled = canForward
        forwardNavButton.alpha = if (canForward) 1f else 0.35f
    }

    private fun showBrowserMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.copy_link))
        popup.menu.add(0, 2, 1, getString(R.string.clear_cookies))
        popup.menu.add(0, 3, 2, getString(R.string.open_external_browser))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("URL", currentUrl))
                    Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                    true
                }
                2 -> {
                    clearCurrentSiteCookies()
                    true
                }
                3 -> {
                    AppUtils.openExternalBrowser(this, currentUrl)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun clearCurrentSiteCookies() {
        val target = currentUrl.ifBlank { return }
        runCatching {
            target.toHttpUrlOrNull()?.let { CookiesUtils.clearCookiesForHost(it) }
            CookieManager.getInstance().flush()
        }
        runCatching { URI(target).host?.lowercase(Locale.ROOT) }.getOrNull()?.let { host ->
            WebViewResolver.capturedHeaders.remove(host)
        }
        Toast.makeText(this, R.string.cookies_cleared, Toast.LENGTH_SHORT).show()
        webView?.reload()
    }

    private fun normalizeInputUrl(raw: String): String? {
        if (raw.isBlank()) return null
        val candidate = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else if (raw.contains(".") && !raw.contains(" ")) {
            "https://$raw"
        } else {
            return null
        }
        return runCatching {
            val uri = candidate.toUri()
            if ((uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) && !uri.host.isNullOrBlank()) {
                uri.toString()
            } else {
                null
            }
        }.getOrNull()
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun createPillBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun finishWithResult(verified: Boolean) {
        if (completed) return
        completed = true
        runCatching { CookieManager.getInstance().flush() }
        CloudflareChallengeManager.onVerificationFinished(verified)
        finish()
    }

    override fun onDestroy() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        if (!completed) {
            completed = true
            runCatching { CookieManager.getInstance().flush() }
            CloudflareChallengeManager.onVerificationFinished(verifiedResult)
        }
        super.onDestroy()
    }
}
