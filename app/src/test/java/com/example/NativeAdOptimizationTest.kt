package com.example

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.ads.AdLoadErrorInfo
import com.example.ads.AdRequestCallbacks
import com.example.ads.ManagedAdHandle
import com.example.ads.NATIVE_AD_EXPIRATION_MS
import com.example.ads.NativeAdCacheConfig
import com.example.ads.NativeAdController
import com.example.ads.NativeAdDiagnosticsTracker
import com.example.ads.NativeAdEventType
import com.example.ads.NativeAdLifecycleState
import com.example.ads.NativeAdLoaderPort
import com.example.ads.NativeAdScheduler
import com.example.audio.MusicPlaybackManager
import com.example.data.MusicTrack
import com.example.ui.ADMOB_REWARDED_AD_UNIT_ID
import com.example.ui.CrossfadeRewardedAdManager
import com.example.ui.GridItemWrapper
import com.example.ui.LocalAdViewportCoordinates
import com.example.ui.LocalIsAdOccludedByOverlay
import com.example.ui.NATIVE_AD_UNIT_1
import com.example.ui.NATIVE_AD_UNIT_2
import com.example.ui.NATIVE_AD_UNIT_3
import com.example.ui.NATIVE_AD_UNIT_4
import com.example.ui.NATIVE_AD_UNIT_5
import com.example.ui.ProvideScrollAdViewport
import com.example.ui.SongListItemWrapper
import com.example.ui.buildCategoryDetailScreenKey
import com.example.ui.buildGridItemsWithAds
import com.example.ui.buildSongItemsWithAds
import com.example.ui.isMeaningfullyVisibleInViewport
import com.example.ui.rememberManagedNativeAdSlot
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.nativead.NativeAd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativeAdOptimizationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class FakeAdHandle : ManagedAdHandle {
        override val sdkNativeAd: NativeAd? = null
        var destroyCount = 0
            private set
        var paidCallback: ((Long, String, Int) -> Unit)? = null
            private set

        override fun setOnPaidEventCallback(onPaid: (Long, String, Int) -> Unit) {
            paidCallback = onPaid
        }

        override fun destroy() {
            destroyCount++
        }
    }

    private class FakeScheduler(private val clock: () -> Long) : NativeAdScheduler {
        data class ScheduledTask(val runAtMs: Long, val runnable: Runnable)

        val pendingTasks = mutableListOf<ScheduledTask>()

        override fun postDelayed(delayMs: Long, runnable: Runnable) {
            pendingTasks.add(ScheduledTask(clock() + delayMs, runnable))
        }

        override fun removeCallbacks(runnable: Runnable) {
            pendingTasks.removeAll { it.runnable === runnable }
        }

        override fun runOnMainThread(runnable: Runnable) {
            runnable.run()
        }

        fun runDueTasks(nowMs: Long) {
            val due = pendingTasks.filter { it.runAtMs <= nowMs }.sortedBy { it.runAtMs }
            pendingTasks.removeAll(due.toSet())
            due.forEach { it.runnable.run() }
        }
    }

    private class FakeLoaderPort : NativeAdLoaderPort {
        var sdkInitCount = 0
        var pendingInitCallback: (() -> Unit)? = null
        var networkAvailable = true
        val requestsByUnit = mutableMapOf<String, MutableList<AdRequestCallbacks>>()

        override fun initializeMobileAds(onCompleted: () -> Unit) {
            sdkInitCount++
            pendingInitCallback = onCompleted
        }

        fun completeInit() {
            val cb = pendingInitCallback
            pendingInitCallback = null
            cb?.invoke()
        }

        override fun loadSingleNativeAd(adUnitId: String, callbacks: AdRequestCallbacks) {
            requestsByUnit.getOrPut(adUnitId) { mutableListOf() }.add(callbacks)
        }

        override fun isNetworkAvailable(): Boolean = networkAvailable

        fun totalRequests(): Int = requestsByUnit.values.sumOf { it.size }

        fun completeLatestLoad(adUnitId: String): Pair<FakeAdHandle, AdRequestCallbacks> {
            val queue = requestsByUnit[adUnitId]!!
            val cb = queue.removeAt(0)
            val handle = FakeAdHandle()
            cb.onAdLoaded(handle)
            return handle to cb
        }

        fun failLatestLoad(adUnitId: String, code: Int) {
            val queue = requestsByUnit[adUnitId]!!
            val cb = queue.removeAt(0)
            cb.onAdFailed(AdLoadErrorInfo(code = code, domain = "com.google.android.gms.ads", message = "Error $code"))
        }
    }

    private fun createHarness(config: NativeAdCacheConfig = NativeAdCacheConfig()): Triple<NativeAdController, FakeLoaderPort, FakeScheduler> {
        var now = 1_000_000L
        val loader = FakeLoaderPort()
        val scheduler = FakeScheduler { now }
        val tracker = NativeAdDiagnosticsTracker(
            timeProvider = { now },
            debugLoggingEnabled = false
        )
        val controller = NativeAdController(
            config = config,
            loaderPort = loader,
            diagnostics = tracker,
            timeProvider = { now },
            scheduler = scheduler
        )
        return Triple(controller, loader, scheduler)
    }

    @Test
    fun `production ad unit IDs remain completely unchanged`() {
        assertEquals("ca-app-pub-8155064094205693/7166498907", NATIVE_AD_UNIT_1)
        assertEquals("ca-app-pub-8155064094205693/8340601899", NATIVE_AD_UNIT_2)
        assertEquals("ca-app-pub-8155064094205693/6655363017", NATIVE_AD_UNIT_3)
        assertEquals("ca-app-pub-8155064094205693/3417071560", NATIVE_AD_UNIT_4)
        assertEquals("ca-app-pub-8155064094205693/3662368162", NATIVE_AD_UNIT_5)
        assertEquals("ca-app-pub-8155064094205693/2082546817", ADMOB_REWARDED_AD_UNIT_ID)
    }

    @Test
    fun `sdk initializes once and never preloads all 5 units at launch`() {
        val (controller, loader, _) = createHarness()

        var rewardedPreloads = 0
        controller.initializeSdkOnce { rewardedPreloads++ }
        controller.initializeSdkOnce { rewardedPreloads++ }

        assertEquals(1, loader.sdkInitCount)
        assertEquals(0, rewardedPreloads)
        assertEquals(0, loader.totalRequests())

        loader.completeInit()
        assertEquals(2, rewardedPreloads)
        // Still zero native ad requests because no screen has registered demand
        assertEquals(0, loader.totalRequests())
    }

    @Test
    fun `demand is strictly per visited unit and never reuses same NativeAd across positions`() {
        val (controller, loader, _) = createHarness()
        controller.initializeSdkOnce()
        loader.completeInit()

        // Songs screen opens with 25 songs -> 2 eligible slots -> loads conservative reserve (1 + 1 up to target 2)
        controller.registerScreenDemand("songs_list", NATIVE_AD_UNIT_1, eligibleSlotCount = 2)
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_1]?.size ?: 0)
        assertEquals(0, loader.requestsByUnit[NATIVE_AD_UNIT_2]?.size ?: 0)
        assertEquals(0, loader.requestsByUnit[NATIVE_AD_UNIT_3]?.size ?: 0)
        assertEquals(0, loader.requestsByUnit[NATIVE_AD_UNIT_4]?.size ?: 0)
        assertEquals(0, loader.requestsByUnit[NATIVE_AD_UNIT_5]?.size ?: 0)

        // First ad finishes loading -> manager loads 2nd reserve ad up to expanded target 2
        loader.completeLatestLoad(NATIVE_AD_UNIT_1)
        assertEquals(1, controller.getUnusedValidCount(NATIVE_AD_UNIT_1))
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_1]?.size ?: 0) // 2nd in flight

        // Claim slot 0 (gets 1st unique ad)
        val slot0 = controller.acquireAdForSlot(NATIVE_AD_UNIT_1, "songs_list:ad_0")
        assertNotNull(slot0)
        // Slot 1 has NO ad ready yet -> MUST return null instead of duplicating slot 0!
        val slot1BeforeFill = controller.acquireAdForSlot(NATIVE_AD_UNIT_1, "songs_list:ad_1")
        assertNull(slot1BeforeFill)

        // 2nd ad loads -> slot 1 gets a distinct instance
        loader.completeLatestLoad(NATIVE_AD_UNIT_1)
        val slot1 = controller.acquireAdForSlot(NATIVE_AD_UNIT_1, "songs_list:ad_1")
        assertNotNull(slot1)
        assertNotEquals(slot0!!.instanceId, slot1!!.instanceId)
    }

    @Test
    fun `20 grid positions receive 20 unique ads cleanly as user scrolls`() {
        val (controller, loader, _) = createHarness()
        controller.initializeSdkOnce()
        loader.completeInit()

        controller.registerScreenDemand("albums_grid", NATIVE_AD_UNIT_2, eligibleSlotCount = 20)
        val assignedIds = mutableSetOf<String>()

        for (slotIdx in 0 until 20) {
            if (controller.getUnusedValidCount(NATIVE_AD_UNIT_2) == 0) {
                loader.completeLatestLoad(NATIVE_AD_UNIT_2)
            }
            val entry = controller.acquireAdForSlot(NATIVE_AD_UNIT_2, "albums_grid:ad_$slotIdx")
            assertNotNull("Expected unique ad for slot $slotIdx", entry)
            assertTrue("Duplicate instance ID assigned: ${entry!!.instanceId}", assignedIds.add(entry.instanceId))
        }
        assertEquals(20, assignedIds.size)
    }

    @Test
    fun `bounded exponential backoff, background cancellation, partial pool recovery, and offline recovery`() {
        var now = 1_000_000L
        val loader = FakeLoaderPort()
        val scheduler = FakeScheduler { now }
        val tracker = NativeAdDiagnosticsTracker(timeProvider = { now }, debugLoggingEnabled = false)
        val controller = NativeAdController(
            config = NativeAdCacheConfig(
                transientBaseRetryDelayMs = 10_000L,
                noFillBaseRetryDelayMs = 30_000L,
                maxNoFillRetries = 3,
                maxRetryDelayMs = 120_000L
            ),
            loaderPort = loader,
            diagnostics = tracker,
            timeProvider = { now },
            scheduler = scheduler
        )
        controller.initializeSdkOnce()
        loader.completeInit()

        controller.registerScreenDemand("albums_grid", NATIVE_AD_UNIT_2, eligibleSlotCount = 5)
        // 1st ad succeeds -> pool has 1 valid ad, manager requests 2nd for reserve
        loader.completeLatestLoad(NATIVE_AD_UNIT_2)
        assertEquals(1, controller.getUnusedValidCount(NATIVE_AD_UNIT_2))
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_2]?.size ?: 0)

        // 2nd ad fails with NO_FILL -> partially filled pool STILL retains 1 valid ad and schedules 30s retry
        loader.failLatestLoad(NATIVE_AD_UNIT_2, AdRequest.ERROR_CODE_NO_FILL)
        assertEquals(1, controller.getUnusedValidCount(NATIVE_AD_UNIT_2))
        assertEquals(1, scheduler.pendingTasks.size)
        assertEquals(now + 30_000L, scheduler.pendingTasks.first().runAtMs)

        // Run due task at +30s -> triggers 2nd attempt -> fail again -> next delay doubles to 60s
        now += 30_000L
        scheduler.runDueTasks(now)
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_2]?.size ?: 0)
        loader.failLatestLoad(NATIVE_AD_UNIT_2, AdRequest.ERROR_CODE_NO_FILL)
        assertEquals(now + 60_000L, scheduler.pendingTasks.first().runAtMs)

        // Background cancels pending retries!
        controller.onAppForegroundChanged(false)
        assertTrue(scheduler.pendingTasks.isEmpty())

        // Resume foreground while offline -> does not fire wasted request
        loader.networkAvailable = false
        controller.onAppForegroundChanged(true)
        assertEquals(0, loader.requestsByUnit[NATIVE_AD_UNIT_2]?.size ?: 0)

        // Network returns -> recovers immediately!
        loader.networkAvailable = true
        controller.onConnectivityChanged(true)
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_2]?.size ?: 0)

        // Non-retryable error stops further retries for that unit
        loader.failLatestLoad(NATIVE_AD_UNIT_2, AdRequest.ERROR_CODE_INVALID_REQUEST)
        assertTrue(scheduler.pendingTasks.isEmpty())
    }

    @Test
    fun `1-hour expiration, safe view detachment before destroy, and navigation retention vs impressed disposal`() {
        var now = 1_000_000L
        val loader = FakeLoaderPort()
        val scheduler = FakeScheduler { now }
        val tracker = NativeAdDiagnosticsTracker(timeProvider = { now }, debugLoggingEnabled = false)
        val controller = NativeAdController(
            config = NativeAdCacheConfig(),
            loaderPort = loader,
            diagnostics = tracker,
            timeProvider = { now },
            scheduler = scheduler
        )
        controller.initializeSdkOnce()
        loader.completeInit()

        // 1. Unused ad expires cleanly after 1 hour
        controller.registerScreenDemand("playlists", NATIVE_AD_UNIT_5, eligibleSlotCount = 1)
        val (handleExpired, _) = loader.completeLatestLoad(NATIVE_AD_UNIT_5)
        assertEquals(0, handleExpired.destroyCount)

        now += NATIVE_AD_EXPIRATION_MS + 1L
        controller.purgeExpiredUnusedAds()
        assertEquals(1, handleExpired.destroyCount)
        assertEquals(0, controller.getUnusedValidCount(NATIVE_AD_UNIT_5))

        // 2. Un-impressed valid ad returns to cache on navigation away; impressed ad is destroyed ONLY after view detaches
        controller.onConnectivityChanged(true)
        val (handleUnimpressed, _) = loader.completeLatestLoad(NATIVE_AD_UNIT_5)
        val entryA = controller.acquireAdForSlot(NATIVE_AD_UNIT_5, "playlists:ad_0")!!
        controller.onViewAttached(NATIVE_AD_UNIT_5, entryA.instanceId, viewToken = 101, placementKey = "playlists:ad_0")
        controller.onViewDetached(NATIVE_AD_UNIT_5, entryA.instanceId, viewToken = 101)
        controller.releaseSlot(NATIVE_AD_UNIT_5, "playlists:ad_0")

        assertEquals(0, handleUnimpressed.destroyCount)
        assertEquals(1, controller.getUnusedValidCount(NATIVE_AD_UNIT_5))
        assertEquals(NativeAdLifecycleState.AVAILABLE, entryA.state)

        // Re-open screen: reused from unused cache, becomes visible, records impression & paid event
        val entryAReturned = controller.acquireAdForSlot(NATIVE_AD_UNIT_5, "playlists:ad_0")!!
        assertEquals(entryA.instanceId, entryAReturned.instanceId)
        controller.onViewAttached(NATIVE_AD_UNIT_5, entryA.instanceId, viewToken = 202, placementKey = "playlists:ad_0")
        controller.onPlacementVisible(NATIVE_AD_UNIT_5, entryA.instanceId, "playlists:ad_0")

        val cb = loader.requestsByUnit[NATIVE_AD_UNIT_5]
        // Trigger impression via the ad's AdRequestCallbacks stored during load? Note we can get it cleanly:
        // Wait: completeLatestLoad returned (handle, cb) - let's test fresh load with impression & attached expiration!
        controller.releaseSlot(NATIVE_AD_UNIT_5, "playlists:ad_0")
        controller.onViewDetached(NATIVE_AD_UNIT_5, entryA.instanceId, viewToken = 202)
        // Release while still valid & un-impressed keeps it cached, destroy only if impressed:
        val (handleImpressed, reqCallbacks) = run {
            now += NATIVE_AD_EXPIRATION_MS + 10L
            controller.purgeExpiredUnusedAds() // cleans entryA
            controller.onConnectivityChanged(true)
            loader.completeLatestLoad(NATIVE_AD_UNIT_5)
        }
        val entryB = controller.acquireAdForSlot(NATIVE_AD_UNIT_5, "playlists:ad_0")!!
        controller.onViewAttached(NATIVE_AD_UNIT_5, entryB.instanceId, viewToken = 303, placementKey = "playlists:ad_0")
        controller.onPlacementVisible(NATIVE_AD_UNIT_5, entryB.instanceId, "playlists:ad_0")
        reqCallbacks.onAdImpression()
        handleImpressed.paidCallback?.invoke(1500L, "USD", AdValue.PrecisionType.PRECISE)

        // Even if slot releases BEFORE view detaches, must NOT destroy while view token 303 is attached!
        controller.releaseSlot(NATIVE_AD_UNIT_5, "playlists:ad_0")
        assertEquals(0, handleImpressed.destroyCount)

        // Once AndroidView.onRelease detaches view token 303, destroy() runs exactly once!
        controller.onViewDetached(NATIVE_AD_UNIT_5, entryB.instanceId, viewToken = 303)
        assertEquals(1, handleImpressed.destroyCount)
        controller.onViewDetached(NATIVE_AD_UNIT_5, entryB.instanceId, viewToken = 303)
        assertEquals(1, handleImpressed.destroyCount) // idempotent!

        val snapshot = controller.getDiagnosticsSnapshot()
        val unitMetrics = snapshot.byUnit[NATIVE_AD_UNIT_5]!!
        assertTrue(unitMetrics.impressionsRecorded >= 1L)
        assertEquals(1500L, unitMetrics.paidValueMicrosByCurrency["USD"])
        assertTrue(snapshot.recentEvents.any { it.eventType == NativeAdEventType.IMPRESSION_RECORDED })
        assertTrue(snapshot.recentEvents.any { it.eventType == NativeAdEventType.PAID_EVENT })
        assertTrue(snapshot.recentEvents.any { it.eventType == NativeAdEventType.AD_DISPOSED })
    }

    @Test
    fun `cadence builders preserve exact frequencies and produce unique keys even with duplicate tracks`() {
        val baseTrack = MusicTrack(
            id = 7, title = "Track", artist = "A", album = "Al",
            genre = "Pop", year = "2026", trackNumber = "1", durationSec = 180,
            lyrics = "", folder = "Music"
        )
        // 20 tracks with duplicate IDs in a queue
        val duplicateTracks = (1..20).map { idx -> baseTrack.copy(id = if (idx <= 2) 7 else idx) }
        val songItems = buildSongItemsWithAds(duplicateTracks)

        // 20 songs + 2 ads (after song 10 and song 20)
        assertEquals(22, songItems.size)
        assertTrue(songItems[10] is SongListItemWrapper.NativeAdItem)
        assertTrue(songItems[21] is SongListItemWrapper.NativeAdItem)

        val keys = songItems.map { wrapper ->
            when (wrapper) {
                is SongListItemWrapper.SongItem -> "song_${wrapper.track.id}_${wrapper.occurrence}"
                is SongListItemWrapper.NativeAdItem -> "ad_${wrapper.adIndex}"
            }
        }
        assertEquals(keys.size, keys.toSet().size)

        // 65 albums -> 1 ad per 3 items, capped at 20 ads -> 65 + 20 = 85 items
        val albums = (1..65).map { "Album $it" }
        val gridItems = buildGridItemsWithAds(albums)
        assertEquals(85, gridItems.size)
        assertEquals(20, gridItems.count { it is GridItemWrapper.NativeAdItem })
    }

    @Test
    fun `1 and 2 - different currencies never combine and paid events never increment impressions`() {
        val (controller, loader, _) = createHarness()
        controller.initializeSdkOnce()
        loader.completeInit()

        controller.registerScreenDemand("songs_list", NATIVE_AD_UNIT_1, eligibleSlotCount = 1)
        val (handle1, cb1) = loader.completeLatestLoad(NATIVE_AD_UNIT_1)
        val entry1 = controller.acquireAdForSlot(NATIVE_AD_UNIT_1, "songs_list:ad_0")!!
        controller.onViewAttached(NATIVE_AD_UNIT_1, entry1.instanceId, viewToken = 11, placementKey = "songs_list:ad_0")

        // Paid events with different currencies + precision types BEFORE impression should NOT increment impressions
        handle1.paidCallback?.invoke(2_500L, "usd", AdValue.PrecisionType.PRECISE)
        handle1.paidCallback?.invoke(800_000L, "TZS", AdValue.PrecisionType.ESTIMATED)
        handle1.paidCallback?.invoke(1_000L, "   ", AdValue.PrecisionType.UNKNOWN)
        handle1.paidCallback?.invoke(500L, "USD", AdValue.PrecisionType.PUBLISHER_PROVIDED)

        val beforeImpression = controller.getDiagnosticsSnapshot()
        val unitBefore = beforeImpression.byUnit[NATIVE_AD_UNIT_1]!!
        assertEquals(0L, unitBefore.impressionsRecorded)
        assertEquals(0L, beforeImpression.totalImpressionsRecorded)
        assertEquals(4L, unitBefore.paidEventsCount)
        assertEquals(3_000L, unitBefore.paidValueMicrosByCurrency["USD"])
        assertEquals(800_000L, unitBefore.paidValueMicrosByCurrency["TZS"])
        assertEquals(1_000L, unitBefore.paidValueMicrosByCurrency["UNKNOWN"])
        assertEquals(3, unitBefore.paidValueMicrosByCurrency.size)
        assertEquals(1L, unitBefore.paidEventsCountByPrecision[AdValue.PrecisionType.PRECISE])
        assertEquals(1L, unitBefore.paidEventsCountByPrecision[AdValue.PrecisionType.ESTIMATED])
        assertEquals(1L, unitBefore.paidEventsCountByPrecision[AdValue.PrecisionType.UNKNOWN])
        assertEquals(1L, unitBefore.paidEventsCountByPrecision[AdValue.PrecisionType.PUBLISHER_PROVIDED])

        // Authoritative impression comes ONLY from AdListener.onAdImpression()
        cb1.onAdImpression()
        val afterImpression = controller.getDiagnosticsSnapshot()
        assertEquals(1L, afterImpression.byUnit[NATIVE_AD_UNIT_1]!!.impressionsRecorded)
        assertEquals(1L, afterImpression.totalImpressionsRecorded)
        assertEquals(3_000L, afterImpression.paidValueMicrosByCurrency["USD"])
        assertEquals(800_000L, afterImpression.paidValueMicrosByCurrency["TZS"])
    }

    @Test
    fun `3 and 4 - viewport visibility threshold and modal overlay occlusion behave accurately`() {
        // Pure geometry check (< 50% vs >= 50%, offscreen, foreground, modal occlusion)
        val viewport = Rect(0f, 0f, 1000f, 1000f)
        // Only 20% visible (height 100, intersecting y 980..1000 -> 20px / 100px = 20%)
        assertFalse(
            isMeaningfullyVisibleInViewport(
                adBounds = Rect(0f, 980f, 1000f, 1080f),
                adFullWidthPx = 1000f,
                adFullHeightPx = 100f,
                viewportBounds = viewport,
                isAttached = true,
                isAppInForeground = true,
                isOccludedByModal = false,
                minVisibleFraction = 0.5f
            )
        )
        // Exactly 50% visible
        assertTrue(
            isMeaningfullyVisibleInViewport(
                adBounds = Rect(0f, 950f, 1000f, 1050f),
                adFullWidthPx = 1000f,
                adFullHeightPx = 100f,
                viewportBounds = viewport,
                isAttached = true,
                isAppInForeground = true,
                isOccludedByModal = false,
                minVisibleFraction = 0.5f
            )
        )
        // Occluded by active modal
        assertFalse(
            isMeaningfullyVisibleInViewport(
                adBounds = Rect(0f, 100f, 1000f, 200f),
                adFullWidthPx = 1000f,
                adFullHeightPx = 100f,
                viewportBounds = viewport,
                isAttached = true,
                isAppInForeground = true,
                isOccludedByModal = true,
                minVisibleFraction = 0.5f
            )
        )

        // Compose Robolectric UI verification with rememberManagedNativeAdSlot + ProvideScrollAdViewport
        val (controller, loader, _) = createHarness()
        controller.initializeSdkOnce()
        loader.completeInit()
        controller.registerScreenDemand("songs_list", NATIVE_AD_UNIT_1, eligibleSlotCount = 1)
        loader.completeLatestLoad(NATIVE_AD_UNIT_1)

        var isModalOpen by mutableStateOf(true)
        var adOffsetY by mutableStateOf(180) // viewport height = 200dp, ad height = 100dp -> 20% visible

        composeTestRule.setContent {
            Box(modifier = Modifier.size(300.dp, 200.dp)) {
                ProvideScrollAdViewport(isExtraOccluded = isModalOpen) { viewportModifier ->
                    Box(modifier = Modifier.fillMaxSize().then(viewportModifier)) {
                        val slot = rememberManagedNativeAdSlot(
                            screenKey = "songs_list",
                            adUnitId = NATIVE_AD_UNIT_1,
                            adIndex = 0,
                            controller = controller
                        )
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(0, adOffsetY) }
                                .size(200.dp, 100.dp)
                                .then(slot.visibilityModifier)
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()

        // Modal open AND only edge visible -> must NOT record PLACEMENT_VISIBLE
        assertEquals(0L, controller.getDiagnosticsSnapshot().byUnit[NATIVE_AD_UNIT_1]?.placementVisibilities ?: 0L)

        // Scroll fully into view while modal is STILL open -> still occluded, must NOT record
        adOffsetY = 20
        composeTestRule.waitForIdle()
        assertEquals(0L, controller.getDiagnosticsSnapshot().byUnit[NATIVE_AD_UNIT_1]?.placementVisibilities ?: 0L)

        // Close modal -> underlying ad is >= 50% visible -> now records PLACEMENT_VISIBLE (NOT impression)
        isModalOpen = false
        composeTestRule.waitForIdle()
        val snap = controller.getDiagnosticsSnapshot().byUnit[NATIVE_AD_UNIT_1]!!
        assertEquals(1L, snap.placementVisibilities)
        assertEquals(0L, snap.impressionsRecorded)
    }

    @Test
    fun `5 and 6 - obsolete late-loading callbacks cannot populate cleared controller and destroy once`() {
        val (controller, loader, scheduler) = createHarness()
        controller.initializeSdkOnce()
        loader.completeInit()

        controller.registerScreenDemand("albums_grid", NATIVE_AD_UNIT_2, eligibleSlotCount = 2)
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_2]?.size ?: 0)
        val inFlightSuccessCb = loader.requestsByUnit[NATIVE_AD_UNIT_2]!!.removeAt(0)

        // Trigger a second in-flight failure scenario as well on another unit
        controller.registerScreenDemand("artists_grid", NATIVE_AD_UNIT_3, eligibleSlotCount = 1)
        val inFlightFailCb = loader.requestsByUnit[NATIVE_AD_UNIT_3]!!.removeAt(0)

        // Clear UI session / shutdown while requests are in flight
        controller.clearAllForShutdown()

        // Late onAdLoaded returns after clear -> MUST NOT enter pool, MUST destroy handle exactly once
        val lateHandle = FakeAdHandle()
        inFlightSuccessCb.onAdLoaded(lateHandle)
        assertEquals(0, controller.getUnusedValidCount(NATIVE_AD_UNIT_2))
        assertTrue(controller.getEntriesSnapshot(NATIVE_AD_UNIT_2).isEmpty())
        assertEquals(1, lateHandle.destroyCount)

        // Late onAdFailed must NOT schedule retries
        inFlightFailCb.onAdFailed(AdLoadErrorInfo(AdRequest.ERROR_CODE_NO_FILL, "ads", "no fill"))
        assertTrue(scheduler.pendingTasks.isEmpty())

        // Resettable UI session: if MainActivity restarts while MusicPlaybackService kept process alive,
        // registering new demand cleanly loads fresh ads in the new generation
        controller.registerScreenDemand("albums_grid", NATIVE_AD_UNIT_2, eligibleSlotCount = 2)
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_2]?.size ?: 0)
        val (freshHandle, _) = loader.completeLatestLoad(NATIVE_AD_UNIT_2)
        assertEquals(0, freshHandle.destroyCount)
        assertEquals(1, controller.getUnusedValidCount(NATIVE_AD_UNIT_2))
    }

    @Test
    fun `7 and 8 - released-but-attached ad enters DETACHING, is excluded from unused count, then returns to AVAILABLE on detach`() {
        val (controller, loader, _) = createHarness()
        controller.initializeSdkOnce()
        loader.completeInit()

        controller.registerScreenDemand("queue_1", NATIVE_AD_UNIT_4, eligibleSlotCount = 1)
        val (handle, _) = loader.completeLatestLoad(NATIVE_AD_UNIT_4)
        val entry = controller.acquireAdForSlot(NATIVE_AD_UNIT_4, "queue_1:ad_0")!!
        controller.onViewAttached(NATIVE_AD_UNIT_4, entry.instanceId, viewToken = 505, placementKey = "queue_1:ad_0")

        // Compose DisposableEffect.onDispose calls releaseSlot BEFORE AndroidView.onRelease calls onViewDetached
        controller.releaseSlot(NATIVE_AD_UNIT_4, "queue_1:ad_0")
        assertEquals(NativeAdLifecycleState.DETACHING, entry.state)
        assertEquals("Attached detaching ad must NOT count as AVAILABLE cache", 0, controller.getUnusedValidCount(NATIVE_AD_UNIT_4))
        assertNull("Still-attached DETACHING ad must NOT be acquired by another slot", controller.acquireAdForSlot(NATIVE_AD_UNIT_4, "queue_1:ad_1"))
        assertEquals(0, handle.destroyCount)

        // AndroidView.onRelease finishes detaching -> valid un-impressed ad returns to AVAILABLE cache
        controller.onViewDetached(NATIVE_AD_UNIT_4, entry.instanceId, viewToken = 505)
        assertEquals(NativeAdLifecycleState.AVAILABLE, entry.state)
        assertEquals(1, controller.getUnusedValidCount(NATIVE_AD_UNIT_4))
        val reacquired = controller.acquireAdForSlot(NATIVE_AD_UNIT_4, "queue_1:ad_1")
        assertNotNull(reacquired)
        assertEquals(entry.instanceId, reacquired!!.instanceId)
    }

    @Test
    fun `9 and 10 - retry budgets recover after cooldown and Wi-Fi mobile handoffs do not reset active backoff`() {
        var now = 1_000_000L
        val loader = FakeLoaderPort()
        val scheduler = FakeScheduler { now }
        val tracker = NativeAdDiagnosticsTracker(timeProvider = { now }, debugLoggingEnabled = false)
        val controller = NativeAdController(
            config = NativeAdCacheConfig(
                noFillBaseRetryDelayMs = 30_000L,
                maxNoFillRetries = 2,
                maxRetryDelayMs = 120_000L,
                retryCooldownResetMs = 300_000L
            ),
            loaderPort = loader,
            diagnostics = tracker,
            timeProvider = { now },
            scheduler = scheduler
        )
        controller.initializeSdkOnce()
        loader.completeInit()

        controller.registerScreenDemand("songs_list", NATIVE_AD_UNIT_1, eligibleSlotCount = 1)
        // Attempt 0 fails -> schedules retry #1 at +30s
        loader.failLatestLoad(NATIVE_AD_UNIT_1, AdRequest.ERROR_CODE_NO_FILL)
        assertEquals(1, scheduler.pendingTasks.size)

        // Simulated Wi-Fi <-> Mobile handoff while already connected MUST NOT wipe active backoff!
        controller.onConnectivityChanged(true)
        assertEquals(1, scheduler.pendingTasks.size)
        assertEquals(0, loader.requestsByUnit[NATIVE_AD_UNIT_1]?.size ?: 0)

        // Advance +30s -> retry #1 fires and fails -> schedules retry #2 at +60s
        now += 30_000L
        scheduler.runDueTasks(now)
        loader.failLatestLoad(NATIVE_AD_UNIT_1, AdRequest.ERROR_CODE_NO_FILL)
        assertEquals(1, scheduler.pendingTasks.size)

        // Advance +60s -> retry #2 fires and fails -> retry budget (2) is now EXHAUSTED
        now += 60_000L
        scheduler.runDueTasks(now)
        loader.failLatestLoad(NATIVE_AD_UNIT_1, AdRequest.ERROR_CODE_NO_FILL)
        assertTrue(scheduler.pendingTasks.isEmpty())

        // Leave screen and return BEFORE cooldown (only +10s later) -> must NOT hammer AdMob
        controller.unregisterScreenDemand("songs_list", NATIVE_AD_UNIT_1)
        now += 10_000L
        controller.registerScreenDemand("songs_list", NATIVE_AD_UNIT_1, eligibleSlotCount = 1)
        assertEquals(0, loader.requestsByUnit[NATIVE_AD_UNIT_1]?.size ?: 0)

        // Return AFTER cooldown (300s) -> retry budget recovers cleanly!
        controller.unregisterScreenDemand("songs_list", NATIVE_AD_UNIT_1)
        now += 300_000L
        controller.registerScreenDemand("songs_list", NATIVE_AD_UNIT_1, eligibleSlotCount = 1)
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_1]?.size ?: 0)

        // And genuine Offline -> Online transition resets active backoff immediately
        loader.failLatestLoad(NATIVE_AD_UNIT_1, AdRequest.ERROR_CODE_NO_FILL)
        assertEquals(1, scheduler.pendingTasks.size)
        loader.networkAvailable = false
        controller.onConnectivityChanged(false)
        loader.networkAvailable = true
        controller.onConnectivityChanged(true)
        assertEquals(1, loader.requestsByUnit[NATIVE_AD_UNIT_1]?.size ?: 0)
    }

    @Test
    fun `11 - distinct folders with identical display names never collide and keys stay deterministic`() {
        val trackInRockHits = MusicTrack(
            id = 101, title = "Track A", artist = "Band 1", album = "Al 1",
            genre = "Rock", year = "2026", trackNumber = "1", durationSec = 200,
            lyrics = "", folder = "Hits",
            contentUri = "file:///storage/emulated/0/Rock/Hits/track_a.mp3"
        )
        val trackInJazzHits = MusicTrack(
            id = 202, title = "Track B", artist = "Band 2", album = "Al 2",
            genre = "Jazz", year = "2026", trackNumber = "1", durationSec = 210,
            lyrics = "", folder = "Hits",
            contentUri = "file:///storage/emulated/0/Jazz/Hits/track_b.mp3"
        )

        val keyRockHits1 = buildCategoryDetailScreenKey("Folder", "Hits", listOf(trackInRockHits))
        val keyRockHits2 = buildCategoryDetailScreenKey("Folder", "Hits", listOf(trackInRockHits))
        val keyJazzHits = buildCategoryDetailScreenKey("Folder", "Hits", listOf(trackInJazzHits))

        assertEquals("Same folder inputs must produce identical deterministic key across recompositions", keyRockHits1, keyRockHits2)
        assertNotEquals("Distinct folders sharing display name 'Hits' must not share placement keys", keyRockHits1, keyJazzHits)
    }

    @Test
    fun `12 and 13 - surplus discard metrics, rewarded ad unit ID, and background playback settings remain intact`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val playbackManager = MusicPlaybackManager.getInstance()
        playbackManager.setContext(context)
        val initialCrossfadeSec = playbackManager.crossfadeSec.value
        val initialDjEffects = playbackManager.djEffectsEnabled.value

        // Clear UI session must never alter playback/DJ state or rewarded unit ID
        val (controller, loader, _) = createHarness(NativeAdCacheConfig(maxUnusedPerUnit = 1, expandedUnusedReserveTarget = 1))
        controller.initializeSdkOnce()
        loader.completeInit()
        controller.registerScreenDemand("songs_list", NATIVE_AD_UNIT_1, eligibleSlotCount = 1)
        loader.completeLatestLoad(NATIVE_AD_UNIT_1)
        controller.clearUiSession()

        assertEquals(initialCrossfadeSec, playbackManager.crossfadeSec.value)
        assertEquals(initialDjEffects, playbackManager.djEffectsEnabled.value)
        assertEquals("ca-app-pub-8155064094205693/2082546817", ADMOB_REWARDED_AD_UNIT_ID)
        assertNotNull(CrossfadeRewardedAdManager)

        // Verify localDisplayableShowRatePercent excludes discarded surplus loads
        val tracker = NativeAdDiagnosticsTracker(debugLoggingEnabled = false)
        tracker.record(com.example.ads.NativeAdDiagnosticEvent(NativeAdEventType.LOAD_SUCCEEDED, NATIVE_AD_UNIT_1))
        tracker.record(com.example.ads.NativeAdDiagnosticEvent(NativeAdEventType.LOAD_SUCCEEDED, NATIVE_AD_UNIT_1))
        tracker.record(com.example.ads.NativeAdDiagnosticEvent(NativeAdEventType.AD_DISCARDED_SURPLUS, NATIVE_AD_UNIT_1))
        tracker.record(com.example.ads.NativeAdDiagnosticEvent(NativeAdEventType.IMPRESSION_RECORDED, NATIVE_AD_UNIT_1, impressionRecorded = true))

        val snap = tracker.snapshot()
        val unit = snap.byUnit[NATIVE_AD_UNIT_1]!!
        assertEquals(1L, unit.discardedSurplusCount)
        assertEquals(50.0, unit.showRatePercent, 0.01)
        assertEquals(100.0, unit.localDisplayableShowRatePercent, 0.01)
    }
}
