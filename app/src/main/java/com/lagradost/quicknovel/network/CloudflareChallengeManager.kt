package com.lagradost.quicknovel.network

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class CloudflareChallengeException(
    val url: String,
    val host: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

object CloudflareChallengeManager {
    private const val AUTO_PROMPT_COOLDOWN_MS = 15_000L
    private val hostLastPromptMs = ConcurrentHashMap<String, Long>()
    private val verificationMutex = Mutex()

    @Volatile
    private var pendingVerification: CompletableDeferred<Boolean>? = null

    fun findCloudflareChallenge(throwable: Throwable?): CloudflareChallengeException? {
        if (throwable == null) return null
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val queue = ArrayDeque<Throwable>()
        queue.addLast(throwable)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current)) continue
            if (current is CloudflareChallengeException) return current
            current.cause?.let(queue::addLast)
            current.suppressed.forEach(queue::addLast)
        }
        return null
    }

    internal fun shouldAllowAutoPrompt(
        host: String,
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = AUTO_PROMPT_COOLDOWN_MS,
    ): Boolean {
        val normalizedHost = normalizeHost(host) ?: return true
        val previous = hostLastPromptMs[normalizedHost] ?: return true
        return (nowMs - previous) >= cooldownMs
    }

    internal fun markPromptStarted(
        host: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val normalizedHost = normalizeHost(host) ?: return
        hostLastPromptMs[normalizedHost] = nowMs
    }

    internal fun clearCooldownForTest() {
        hostLastPromptMs.clear()
    }

    internal fun onVerificationFinished(verified: Boolean) {
        pendingVerification?.complete(verified)
        pendingVerification = null
    }

    suspend fun verify(activity: Activity, url: String): Boolean {
        val host = normalizeHostFromUrl(url).orEmpty()
        return verificationMutex.withLock {
            markPromptStarted(host)
            val deferred = CompletableDeferred<Boolean>()
            pendingVerification?.complete(false)
            pendingVerification = deferred
            try {
                activity.startActivity(CloudflareVerificationActivity.createIntent(activity, url))
                deferred.await()
            } catch (_: Throwable) {
                pendingVerification = null
                false
            }
        }
    }

    fun launchVerification(
        context: Context?,
        url: String,
        onComplete: (Boolean) -> Unit,
    ) {
        val activity = findActivity(context)
        if (activity == null) {
            onComplete(false)
            return
        }
        val owner = activity as? LifecycleOwner
        if (owner != null) {
            owner.lifecycleScope.launch {
                val verified = verify(activity, url)
                onComplete(verified)
            }
        } else {
            activity.startActivity(CloudflareVerificationActivity.createIntent(activity, url))
        }
    }

    fun findActivity(context: Context?): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun normalizeHostFromUrl(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.let(::normalizeHost)

    private fun normalizeHost(host: String?): String? =
        host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
}
