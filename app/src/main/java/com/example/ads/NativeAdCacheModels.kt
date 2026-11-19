package com.example.ads

import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.nativead.NativeAd

/**
 * Official Google Mobile Ads Android SDK (23.6.0) native ad expiration window:
 * Cached NativeAd objects expire after 1 hour (3,600,000 ms) from load completion.
 */
const val NATIVE_AD_EXPIRATION_MS: Long = 60L * 60L * 1000L

data class NativeAdCacheConfig(
    val defaultUnusedTarget: Int = 1,
    val expandedUnusedReserveTarget: Int = 2,
    val maxUnusedPerUnit: Int = 2,
    val expirationMs: Long = NATIVE_AD_EXPIRATION_MS,
    val maxTransientRetries: Int = 5,
    val maxNoFillRetries: Int = 4,
    val transientBaseRetryDelayMs: Long = 10_000L,
    val noFillBaseRetryDelayMs: Long = 30_000L,
    val maxRetryDelayMs: Long = 300_000L,
    val retryCooldownResetMs: Long = 300_000L,
    val minVisibleAreaFraction: Float = 0.5f
)

enum class AdFailureCategory {
    RECOVERABLE_TRANSIENT,
    NO_FILL_OR_RATE_LIMIT,
    NON_RETRYABLE
}

data class AdLoadErrorInfo(
    val code: Int,
    val domain: String,
    val message: String
) {
    fun classify(): AdFailureCategory = when (code) {
        AdRequest.ERROR_CODE_INVALID_REQUEST,
        AdRequest.ERROR_CODE_APP_ID_MISSING,
        AdRequest.ERROR_CODE_INVALID_AD_STRING -> AdFailureCategory.NON_RETRYABLE

        AdRequest.ERROR_CODE_NO_FILL,
        AdRequest.ERROR_CODE_REQUEST_ID_MISMATCH -> AdFailureCategory.NO_FILL_OR_RATE_LIMIT

        else -> AdFailureCategory.RECOVERABLE_TRANSIENT
    }
}

/**
 * Abstraction over a loaded Google Mobile Ads [NativeAd] (or test fake in JVM unit tests)
 * ensuring `destroy()` is safe and decoupled from direct SDK instantiation during unit tests.
 */
interface ManagedAdHandle {
    val sdkNativeAd: NativeAd?
    fun setOnPaidEventCallback(onPaid: (valueMicros: Long, currencyCode: String, precisionType: Int) -> Unit)
    fun destroy()
}

interface AdRequestCallbacks {
    fun onAdLoaded(handle: ManagedAdHandle)
    fun onAdFailed(error: AdLoadErrorInfo)
    fun onAdImpression()
    fun onAdClicked()
}

interface NativeAdLoaderPort {
    fun initializeMobileAds(onCompleted: () -> Unit)
    fun loadSingleNativeAd(adUnitId: String, callbacks: AdRequestCallbacks)
    fun isNetworkAvailable(): Boolean
}

interface NativeAdScheduler {
    fun postDelayed(delayMs: Long, runnable: Runnable)
    fun removeCallbacks(runnable: Runnable)
    fun runOnMainThread(runnable: Runnable)
}

class ManagedAdEntry(
    val instanceId: String,
    val adUnitId: String,
    val handle: ManagedAdHandle,
    val requestTimestampMs: Long,
    val loadedAtMs: Long
) {
    var state: NativeAdLifecycleState = NativeAdLifecycleState.AVAILABLE
        internal set

    var assignedPlacementKey: String? = null
        internal set

    var assignedAtMs: Long? = null
        internal set

    var visibleRecordedForSession: Boolean = false
        internal set

    var impressionRecorded: Boolean = false
        internal set

    var returnedFromUnusedCache: Boolean = false
        internal set

    var pendingDisposal: Boolean = false
        internal set

    var isDisposed: Boolean = false
        internal set

    val attachedViewTokens: MutableSet<Int> = LinkedHashSet()

    val sdkNativeAd: NativeAd?
        get() = if (isDisposed) null else handle.sdkNativeAd

    fun cacheAgeMs(nowMs: Long): Long = (nowMs - loadedAtMs).coerceAtLeast(0L)

    fun isExpired(nowMs: Long, expirationMs: Long): Boolean = (nowMs - loadedAtMs) >= expirationMs

    fun isAttachedToAnyView(): Boolean = attachedViewTokens.isNotEmpty()
}
