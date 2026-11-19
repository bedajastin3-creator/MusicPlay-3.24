package com.example.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ads.AdLoadErrorInfo
import com.example.ads.AdRequestCallbacks
import com.example.ads.ManagedAdEntry
import com.example.ads.ManagedAdHandle
import com.example.ads.NativeAdCacheConfig
import com.example.ads.NativeAdController
import com.example.ads.NativeAdDiagnosticsSnapshot
import com.example.ads.NativeAdLoaderPort
import com.example.ads.NativeAdScheduler
import com.example.data.MusicTrack
import com.example.ui.theme.SpaceCardBg
import com.example.ui.theme.TealPrimary
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions
import com.google.android.gms.ads.nativead.NativeAdView
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob Configuration Constants (Production Unit IDs — preserved unchanged)
 * Official App ID: ca-app-pub-8155064094205693~4470440036 (declared in AndroidManifest.xml)
 */
const val NATIVE_AD_UNIT_1 = "ca-app-pub-8155064094205693/7166498907" // Songs List
const val NATIVE_AD_UNIT_2 = "ca-app-pub-8155064094205693/8340601899" // Albums Grid
const val NATIVE_AD_UNIT_3 = "ca-app-pub-8155064094205693/6655363017" // Artists Grid
const val NATIVE_AD_UNIT_4 = "ca-app-pub-8155064094205693/3417071560" // Active Queue & Category/Folder Songs
const val NATIVE_AD_UNIT_5 = "ca-app-pub-8155064094205693/3662368162" // Playlists Bottom Card & Playlist Dialog

const val ADMOB_NATIVE_AD_UNIT_ID = NATIVE_AD_UNIT_2
const val ADMOB_SONGS_NATIVE_AD_UNIT_ID = NATIVE_AD_UNIT_1
const val ADMOB_REWARDED_AD_UNIT_ID = "ca-app-pub-8155064094205693/2082546817"

/**
 * Helper to locate the hosting Activity from Context.
 */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Singleton manager to preload and show AdMob Rewarded Ads when switching DJ Crossfade seconds.
 * Kept independent from Native Ad caching and disposal.
 */
object CrossfadeRewardedAdManager {
    private var rewardedAd: RewardedAd? = null
    private var isLoading = false

    fun preload(context: Context) {
        if (rewardedAd != null || isLoading) return
        isLoading = true
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            context.applicationContext,
            ADMOB_REWARDED_AD_UNIT_ID,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoading = false
                    Log.d("AdMobRewarded", "Crossfade rewarded ad preloaded successfully")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isLoading = false
                    Log.w("AdMobRewarded", "Failed to load crossfade rewarded ad: ${error.message}")
                }
            }
        )
    }

    fun showAd(context: Context, onComplete: () -> Unit) {
        val activity = context.findActivity()
        val ad = rewardedAd
        if (activity != null && ad != null) {
            ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    rewardedAd = null
                    preload(context)
                    onComplete()
                }

                override fun onAdFailedToShowFullScreenContent(adError: com.google.android.gms.ads.AdError) {
                    rewardedAd = null
                    preload(context)
                    onComplete()
                }
            }
            ad.show(activity) { rewardItem ->
                Log.d("AdMobRewarded", "User earned reward: ${rewardItem.amount} ${rewardItem.type}")
            }
        } else {
            preload(context)
            onComplete()
        }
    }
}

private class SdkNativeAdHandle(
    override val sdkNativeAd: NativeAd
) : ManagedAdHandle {
    override fun setOnPaidEventCallback(onPaid: (valueMicros: Long, currencyCode: String, precisionType: Int) -> Unit) {
        sdkNativeAd.setOnPaidEventListener { adValue ->
            onPaid(adValue.valueMicros, adValue.currencyCode, adValue.precisionType)
        }
    }

    override fun destroy() {
        sdkNativeAd.destroy()
    }
}

/**
 * Centralized, lifecycle-aware AdMob Native Ad Manager backed by [NativeAdController].
 * Maintains independent per-unit caches, strictly unique placement assignments,
 * bounded exponential backoff retries, 1-hour expiration, and paid/impression telemetry.
 */
object AppNativeAdsManager {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val unitRevisions = mutableStateMapOf<String, Int>()
    private val observersRegistered = AtomicBoolean(false)
    private val _appInForegroundState = mutableStateOf(true)
    val appInForegroundState: State<Boolean> get() = _appInForegroundState

    @Volatile
    private var appContext: Context? = null

    private val scheduler = object : NativeAdScheduler {
        override fun postDelayed(delayMs: Long, runnable: Runnable) {
            mainHandler.postDelayed(runnable, delayMs)
        }

        override fun removeCallbacks(runnable: Runnable) {
            mainHandler.removeCallbacks(runnable)
        }

        override fun runOnMainThread(runnable: Runnable) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                runnable.run()
            } else {
                mainHandler.post(runnable)
            }
        }
    }

    private val loaderPort = object : NativeAdLoaderPort {
        override fun initializeMobileAds(onCompleted: () -> Unit) {
            val ctx = appContext ?: return
            MobileAds.initialize(ctx) {
                scheduler.runOnMainThread(Runnable { onCompleted() })
            }
        }

        override fun loadSingleNativeAd(adUnitId: String, callbacks: AdRequestCallbacks) {
            val ctx = appContext ?: return
            val adLoader = AdLoader.Builder(ctx, adUnitId)
                .forNativeAd { nativeAd ->
                    callbacks.onAdLoaded(SdkNativeAdHandle(nativeAd))
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        callbacks.onAdFailed(
                            AdLoadErrorInfo(
                                code = loadAdError.code,
                                domain = loadAdError.domain,
                                message = loadAdError.message
                            )
                        )
                    }

                    override fun onAdImpression() {
                        callbacks.onAdImpression()
                    }

                    override fun onAdClicked() {
                        callbacks.onAdClicked()
                    }
                })
                .withNativeAdOptions(
                    NativeAdOptions.Builder()
                        .setAdChoicesPlacement(NativeAdOptions.ADCHOICES_TOP_RIGHT)
                        .setReturnUrlsForImageAssets(false)
                        .build()
                )
                .build()

            adLoader.loadAd(AdRequest.Builder().build())
        }

        override fun isNetworkAvailable(): Boolean {
            val ctx = appContext ?: return false
            return try {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return true
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (_: SecurityException) {
                true
            } catch (_: Exception) {
                true
            }
        }
    }

    val controller: NativeAdController = NativeAdController(
        config = NativeAdCacheConfig(),
        loaderPort = loaderPort,
        scheduler = scheduler,
        onUnitStateChanged = { adUnitId ->
            scheduler.runOnMainThread(Runnable {
                unitRevisions[adUnitId] = (unitRevisions[adUnitId] ?: 0) + 1
            })
        }
    )

    fun initializeMobileAds(context: Context, onInitialized: () -> Unit = {}) {
        val app = context.applicationContext
        appContext = app
        registerLifecycleAndNetworkObservers(app)
        controller.initializeSdkOnce(onInitialized)
    }

    private fun registerLifecycleAndNetworkObservers(app: Context) {
        if (!observersRegistered.compareAndSet(false, true)) return

        val application = app as? Application
        application?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: Activity) {
                val wasBackground = startedCount == 0
                startedCount++
                if (wasBackground) {
                    _appInForegroundState.value = true
                    controller.onAppForegroundChanged(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
                if (startedCount == 0) {
                    _appInForegroundState.value = false
                    controller.onAppForegroundChanged(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        scheduler.runOnMainThread(Runnable {
                            controller.onConnectivityChanged(true)
                        })
                    }

                    override fun onLost(network: Network) {
                        scheduler.runOnMainThread(Runnable {
                            controller.onConnectivityChanged(loaderPort.isNetworkAvailable())
                        })
                    }
                })
            } catch (_: Exception) {
            }
        }
    }

    fun observeUnitRevision(adUnitId: String): Int {
        return unitRevisions[adUnitId] ?: 0
    }

    fun registerScreenDemand(context: Context, screenKey: String, adUnitId: String, eligibleSlotCount: Int) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        controller.registerScreenDemand(screenKey, adUnitId, eligibleSlotCount)
    }

    fun unregisterScreenDemand(screenKey: String, adUnitId: String) {
        controller.unregisterScreenDemand(screenKey, adUnitId)
    }

    fun acquireAdForSlot(adUnitId: String, placementKey: String): ManagedAdEntry? {
        return controller.acquireAdForSlot(adUnitId, placementKey)
    }

    fun onViewAttached(adUnitId: String, instanceId: String, viewToken: Int, placementKey: String) {
        controller.onViewAttached(adUnitId, instanceId, viewToken, placementKey)
    }

    fun onPlacementVisible(adUnitId: String, instanceId: String, placementKey: String) {
        controller.onPlacementVisible(adUnitId, instanceId, placementKey)
    }

    fun onViewDetached(adUnitId: String, instanceId: String, viewToken: Int) {
        controller.onViewDetached(adUnitId, instanceId, viewToken)
    }

    fun releaseSlot(adUnitId: String, placementKey: String) {
        controller.releaseSlot(adUnitId, placementKey)
    }

    /**
     * Releases UI-bound ad slots and pending retries when the host Activity genuinely finishes,
     * while keeping the singleton reusable if an Activity restarts in the same MusicPlaybackService process.
     */
    fun onHostActivityFinishing() {
        controller.clearUiSession()
    }

    fun getDiagnosticsSnapshot(): NativeAdDiagnosticsSnapshot = controller.getDiagnosticsSnapshot()
}

/**
 * Provides the scrollable container's [LayoutCoordinates] so nested native ad slots can measure
 * actual intersection with the visible viewport rather than raw window attachment alone.
 */
val LocalAdViewportCoordinates = compositionLocalOf<LayoutCoordinates?> { null }

/**
 * Centrally signals when a modal overlay or dialog obscures the underlying screen content.
 */
val LocalIsAdOccludedByOverlay = compositionLocalOf { false }

/**
 * Minimum visible area fraction [0f..1f] required before recording `PLACEMENT_VISIBLE`.
 */
val LocalAdMinVisibleFraction = compositionLocalOf { 0.5f }

fun computeViewportVisibleFraction(
    adCoords: LayoutCoordinates,
    viewportCoords: LayoutCoordinates?
): Float {
    if (!adCoords.isAttached) return 0f
    val fullWidth = adCoords.size.width.toFloat()
    val fullHeight = adCoords.size.height.toFloat()
    val fullArea = fullWidth * fullHeight
    if (fullArea <= 0f) return 0f

    val adBounds = adCoords.boundsInWindow()
    if (adBounds.isEmpty) return 0f

    val effectiveRect = if (viewportCoords != null && viewportCoords.isAttached) {
        val viewportBounds = viewportCoords.boundsInWindow()
        if (viewportBounds.isEmpty) return 0f
        adBounds.intersect(viewportBounds)
    } else {
        adBounds
    }
    if (effectiveRect.isEmpty || effectiveRect.width <= 0f || effectiveRect.height <= 0f) {
        return 0f
    }
    return ((effectiveRect.width * effectiveRect.height) / fullArea).coerceIn(0f, 1f)
}

fun isMeaningfullyVisibleInViewport(
    adBounds: Rect,
    adFullWidthPx: Float,
    adFullHeightPx: Float,
    viewportBounds: Rect?,
    isAttached: Boolean,
    isAppInForeground: Boolean,
    isOccludedByModal: Boolean,
    minVisibleFraction: Float = 0.5f
): Boolean {
    if (!isAttached || !isAppInForeground || isOccludedByModal) return false
    val fullArea = adFullWidthPx * adFullHeightPx
    if (fullArea <= 0f || adBounds.isEmpty) return false
    val intersection = if (viewportBounds != null) {
        if (viewportBounds.isEmpty) return false
        adBounds.intersect(viewportBounds)
    } else {
        adBounds
    }
    if (intersection.isEmpty || intersection.width <= 0f || intersection.height <= 0f) return false
    val fraction = ((intersection.width * intersection.height) / fullArea).coerceIn(0f, 1f)
    return fraction >= minVisibleFraction.coerceIn(0.01f, 1f)
}

/**
 * Provides the scrollable container's [LayoutCoordinates] and combined modal occlusion flag
 * to descendant native ad slots via CompositionLocals.
 */
@Composable
fun ProvideScrollAdViewport(
    isExtraOccluded: Boolean = false,
    content: @Composable (viewportModifier: Modifier) -> Unit
) {
    val viewportCoords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val occluded = LocalIsAdOccludedByOverlay.current || isExtraOccluded
    CompositionLocalProvider(
        LocalAdViewportCoordinates provides viewportCoords.value,
        LocalIsAdOccludedByOverlay provides occluded
    ) {
        content(Modifier.onGloballyPositioned { viewportCoords.value = it })
    }
}

/**
 * Builds a deterministic, collision-resistant screen key for category/folder detail views so
 * distinct folders or categories sharing the same display name never share ad placement keys.
 */
fun buildCategoryDetailScreenKey(
    categoryType: String,
    title: String,
    tracks: List<MusicTrack>,
    explicitCategoryId: String? = null
): String {
    val normalizedType = categoryType.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    val normalizedTitle = title.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
    val uniquePayload = if (!explicitCategoryId.isNullOrBlank()) {
        explicitCategoryId
    } else {
        tracks.map { track ->
            val parentHint = track.contentUri.substringBeforeLast('/', "")
            "${track.id}|${track.contentUri}|$parentHint|${track.folder}|${track.album}|${track.artist}"
        }.sorted().joinToString(separator = "\n")
    }
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$categoryType\u0000$title\u0000$uniquePayload".toByteArray(Charsets.UTF_8))
    val hash = digest.take(8).joinToString("") { "%02x".format(it) }
    return "category_${normalizedType}_${normalizedTitle}_$hash"
}

@Stable
data class ManagedAdSlotState(
    val entry: ManagedAdEntry?,
    val visibilityModifier: Modifier
)

/**
 * Registers screen-level demand while the screen is active and cancels pending retries
 * when the screen leaves composition or has zero eligible placements.
 */
@Composable
fun rememberNativeAdScreenDemand(
    screenKey: String,
    itemCount: Int,
    adUnitId: String,
    cadence: Int,
    maxSlots: Int = Int.MAX_VALUE
) {
    val context = LocalContext.current
    val eligibleSlots = if (itemCount < cadence) 0 else (itemCount / cadence).coerceAtMost(maxSlots)

    DisposableEffect(screenKey, adUnitId, eligibleSlots) {
        AppNativeAdsManager.registerScreenDemand(
            context = context,
            screenKey = screenKey,
            adUnitId = adUnitId,
            eligibleSlotCount = eligibleSlots
        )
        onDispose {
            AppNativeAdsManager.unregisterScreenDemand(
                screenKey = screenKey,
                adUnitId = adUnitId
            )
        }
    }
}

/**
 * Acquires and retains a strictly unique [ManagedAdEntry] for one ad placement slot.
 * Never reuses another slot's ad and releases cleanly when leaving the viewport/navigation.
 * Records diagnostic `PLACEMENT_VISIBLE` only when attached, in foreground, unobstructed by
 * modal overlays, and intersecting the scrollable viewport by at least [LocalAdMinVisibleFraction].
 */
@Composable
fun rememberManagedNativeAdSlot(
    screenKey: String,
    adUnitId: String,
    adIndex: Int,
    controller: NativeAdController = AppNativeAdsManager.controller
): ManagedAdSlotState {
    val placementKey = remember(screenKey, adIndex) { "$screenKey:ad_$adIndex" }
    val unitRevision = if (controller === AppNativeAdsManager.controller) {
        AppNativeAdsManager.observeUnitRevision(adUnitId)
    } else {
        0
    }
    val slotHolder = remember(controller, adUnitId, placementKey) { mutableStateOf<ManagedAdEntry?>(null) }

    LaunchedEffect(controller, adUnitId, placementKey, unitRevision) {
        val current = slotHolder.value
        if (current == null || current.isDisposed || current.pendingDisposal) {
            slotHolder.value = controller.acquireAdForSlot(adUnitId, placementKey)
        }
    }

    DisposableEffect(controller, adUnitId, placementKey) {
        onDispose {
            slotHolder.value = null
            controller.releaseSlot(adUnitId, placementKey)
        }
    }

    val viewportCoords = LocalAdViewportCoordinates.current
    val isOccluded = LocalIsAdOccludedByOverlay.current
    val minVisibleFraction = LocalAdMinVisibleFraction.current.coerceAtLeast(
        controller.config.minVisibleAreaFraction
    )
    val appInForeground by if (controller === AppNativeAdsManager.controller) {
        AppNativeAdsManager.appInForegroundState
    } else {
        remember(controller) { mutableStateOf(controller.isAppInForeground()) }
    }

    val adCoordsHolder = remember(controller, adUnitId, placementKey) { mutableStateOf<LayoutCoordinates?>(null) }
    val visibleFractionHolder = remember(controller, adUnitId, placementKey) { mutableFloatStateOf(0f) }

    val active = slotHolder.value?.takeIf { !it.isDisposed && !it.pendingDisposal }

    LaunchedEffect(
        active?.instanceId,
        visibleFractionHolder.floatValue,
        viewportCoords,
        isOccluded,
        appInForeground,
        minVisibleFraction
    ) {
        val entry = active ?: return@LaunchedEffect
        val coords = adCoordsHolder.value ?: return@LaunchedEffect
        if (!coords.isAttached || isOccluded || !appInForeground || !controller.isAppInForeground()) {
            return@LaunchedEffect
        }
        val fraction = computeViewportVisibleFraction(coords, viewportCoords)
        if (fraction >= minVisibleFraction) {
            controller.onPlacementVisible(
                adUnitId = adUnitId,
                instanceId = entry.instanceId,
                placementKey = placementKey
            )
        }
    }

    val visibilityModifier = if (active != null) {
        Modifier.onGloballyPositioned { coords ->
            adCoordsHolder.value = coords
            visibleFractionHolder.floatValue = computeViewportVisibleFraction(coords, viewportCoords)
        }
    } else {
        Modifier
    }

    return ManagedAdSlotState(entry = active, visibilityModifier = visibilityModifier)
}

/**
 * Sealed wrapper class to insert Native Ads into the Songs LazyColumn alongside real song tracks.
 */
sealed class SongListItemWrapper {
    data class SongItem(val track: MusicTrack, val occurrence: Int = 0) : SongListItemWrapper()
    data class NativeAdItem(val adIndex: Int) : SongListItemWrapper()
}

/**
 * Sealed wrapper class to insert Native Ads into LazyVerticalGrid alongside real data items.
 */
sealed class GridItemWrapper<out T> {
    data class RealItem<T>(val item: T) : GridItemWrapper<T>()
    data class NativeAdItem(val adIndex: Int) : GridItemWrapper<Nothing>()
}

/**
 * Combines real list items with Native Ad slots according to existing placement policies:
 * Cadence rule: 1 ad after every 3 items in albums and artists (max 20 slots).
 */
fun <T> buildGridItemsWithAds(
    realItems: List<T>,
    failedAdIndices: Collection<Int> = emptyList()
): List<GridItemWrapper<T>> {
    if (realItems.isEmpty()) return emptyList()

    val count = realItems.size
    val maxAdsAllowed = if (count < 3) 0 else (count / 3).coerceAtMost(20)

    val result = mutableListOf<GridItemWrapper<T>>()
    var adsInserted = 0

    for (i in realItems.indices) {
        result.add(GridItemWrapper.RealItem(realItems[i]))
        val itemNum = i + 1

        if (itemNum % 3 == 0 && adsInserted < maxAdsAllowed) {
            result.add(GridItemWrapper.NativeAdItem(adsInserted))
            adsInserted++
        }
    }

    return result
}

/**
 * Combines real songs with Native Ad slots according to existing rules:
 * - If songs are below 10: do not show ad
 * - If songs >= 10: insert an ad after every 10 songs
 */
fun buildSongItemsWithAds(
    realItems: List<MusicTrack>,
    failedAdIndices: Collection<Int> = emptyList()
): List<SongListItemWrapper> {
    val occurrences = HashMap<Int, Int>(realItems.size)
    if (realItems.size < 10) {
        return realItems.map { track ->
            val occ = occurrences.getOrDefault(track.id, 0)
            occurrences[track.id] = occ + 1
            SongListItemWrapper.SongItem(track, occ)
        }
    }

    val result = mutableListOf<SongListItemWrapper>()
    var adIndex = 0

    for (i in realItems.indices) {
        val track = realItems[i]
        val occ = occurrences.getOrDefault(track.id, 0)
        occurrences[track.id] = occ + 1
        result.add(SongListItemWrapper.SongItem(track, occ))
        val songNumber = i + 1

        if (songNumber % 10 == 0) {
            result.add(SongListItemWrapper.NativeAdItem(adIndex))
            adIndex++
        }
    }

    return result
}

@Composable
private fun ManagedNativeAdAndroidView(
    entry: ManagedAdEntry,
    factory: (Context) -> NativeAdView,
    binder: (NativeAdView, NativeAd) -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = factory,
        update = { nativeAdView ->
            val nativeAd = entry.sdkNativeAd
            val currentTag = nativeAdView.tag as? Pair<*, *>
            val boundInstanceId = currentTag?.second as? String
            if (nativeAd != null && !entry.isDisposed && boundInstanceId != entry.instanceId) {
                if (currentTag != null) {
                    val prevUnit = currentTag.first as? String
                    val prevInstance = currentTag.second as? String
                    if (prevUnit != null && prevInstance != null) {
                        AppNativeAdsManager.onViewDetached(
                            adUnitId = prevUnit,
                            instanceId = prevInstance,
                            viewToken = System.identityHashCode(nativeAdView)
                        )
                    }
                }
                binder(nativeAdView, nativeAd)
                nativeAdView.tag = entry.adUnitId to entry.instanceId
                AppNativeAdsManager.onViewAttached(
                    adUnitId = entry.adUnitId,
                    instanceId = entry.instanceId,
                    viewToken = System.identityHashCode(nativeAdView),
                    placementKey = entry.assignedPlacementKey.orEmpty()
                )
            }
        },
        onRelease = { nativeAdView ->
            val currentTag = nativeAdView.tag as? Pair<*, *>
            val prevUnit = currentTag?.first as? String ?: entry.adUnitId
            val prevInstance = currentTag?.second as? String ?: entry.instanceId
            AppNativeAdsManager.onViewDetached(
                adUnitId = prevUnit,
                instanceId = prevInstance,
                viewToken = System.identityHashCode(nativeAdView)
            )
            nativeAdView.tag = null
        },
        modifier = modifier
    )
}

/**
 * Album Card Native Ad layout matching exact size, shape, and style of Album cards.
 * Leaves the slot temporarily empty when no unique valid ad is available.
 */
@Composable
fun AlbumNativeAdCard(
    adEntry: ManagedAdEntry?,
    modifier: Modifier = Modifier
) {
    if (adEntry == null || adEntry.sdkNativeAd == null || adEntry.isDisposed) {
        Box(modifier = modifier.fillMaxWidth().height(1.dp))
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpaceCardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        ManagedNativeAdAndroidView(
            entry = adEntry,
            factory = ::createAlbumNativeAdView,
            binder = ::bindAlbumNativeAdView,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Artist Card Native Ad layout matching exact size, shape, and style of Artist cards.
 * Leaves the slot temporarily empty when no unique valid ad is available.
 */
@Composable
fun ArtistNativeAdCard(
    adEntry: ManagedAdEntry?,
    modifier: Modifier = Modifier
) {
    if (adEntry == null || adEntry.sdkNativeAd == null || adEntry.isDisposed) {
        Box(modifier = modifier.fillMaxWidth().height(1.dp))
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SpaceCardBg),
        shape = RoundedCornerShape(12.dp)
    ) {
        ManagedNativeAdAndroidView(
            entry = adEntry,
            factory = ::createArtistNativeAdView,
            binder = ::bindArtistNativeAdView,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Song Native Ad Row matching the exact appearance of a song list row/card.
 * Leaves the slot temporarily empty when no unique valid ad is available.
 */
@Composable
fun SongNativeAdRow(
    adEntry: ManagedAdEntry?,
    modifier: Modifier = Modifier
) {
    if (adEntry == null || adEntry.sdkNativeAd == null || adEntry.isDisposed) {
        Box(modifier = modifier.fillMaxWidth().height(0.dp))
        return
    }
    Column(modifier = modifier.fillMaxWidth()) {
        ManagedNativeAdAndroidView(
            entry = adEntry,
            factory = ::createSongNativeAdView,
            binder = ::bindSongNativeAdView,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Native Advanced Ad Card placed at the bottom of Playlists screen below promotional cards.
 */
@Composable
fun PlaylistNativeAdCard(
    modifier: Modifier = Modifier,
    adUnitId: String = NATIVE_AD_UNIT_5,
    screenKey: String = "playlists_bottom"
) {
    rememberNativeAdScreenDemand(
        screenKey = screenKey,
        itemCount = 1,
        adUnitId = adUnitId,
        cadence = 1,
        maxSlots = 1
    )
    val slot = rememberManagedNativeAdSlot(
        screenKey = screenKey,
        adUnitId = adUnitId,
        adIndex = 0
    )

    val entry = slot.entry
    if (entry == null || entry.sdkNativeAd == null || entry.isDisposed) {
        Box(modifier = modifier.fillMaxWidth().height(0.dp))
        return
    }

    Card(
        modifier = modifier
            .then(slot.visibilityModifier)
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SpaceCardBg),
        border = BorderStroke(1.dp, TealPrimary.copy(alpha = 0.25f))
    ) {
        SongNativeAdRow(
            adEntry = entry,
            modifier = Modifier.padding(vertical = 4.dp)
        )
    }
}
