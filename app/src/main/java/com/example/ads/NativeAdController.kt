package com.example.ads

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Thread-safe, demand-driven Native Ad coordinator managing per-unit caches,
 * single-shot SDK initialization, unique placement assignment (zero modulo reuse),
 * bounded exponential-backoff retries, generation-guarded callbacks, 1-hour SDK
 * expiration, and safe disposal.
 */
class NativeAdController(
    val config: NativeAdCacheConfig = NativeAdCacheConfig(),
    private val loaderPort: NativeAdLoaderPort,
    val diagnostics: NativeAdDiagnosticsTracker = NativeAdDiagnosticsTracker(),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val scheduler: NativeAdScheduler,
    private val onUnitStateChanged: (String) -> Unit = {}
) {
    private val lock = Any()
    private val sdkInitStarted = AtomicBoolean(false)
    @Volatile
    private var sdkInitialized = false
    private val pendingPostInitActions = mutableListOf<() -> Unit>()

    @Volatile
    private var appInForeground = true

    private var sessionGeneration: Long = 1L
    private var lastKnownConnected: Boolean? = null

    private val instanceSequence = AtomicLong(0L)
    private val pools = mutableMapOf<String, UnitAdPool>()

    private class UnitAdPool(val adUnitId: String) {
        val entries = mutableListOf<ManagedAdEntry>()
        val screenDemands = mutableMapOf<String, Int>()
        var isLoading = false
        var retryAttempt = 0
        var nextRetryAllowedAtMs = 0L
        var retriesExhaustedAtMs = 0L
        var pendingRetryRunnable: Runnable? = null
        var blockedByNonRetryableError = false
    }

    fun isSdkInitialized(): Boolean = sdkInitialized

    fun isAppInForeground(): Boolean = appInForeground

    fun getSessionGeneration(): Long = synchronized(lock) { sessionGeneration }

    fun initializeSdkOnce(onCompleted: () -> Unit = {}) {
        val shouldStart = sdkInitStarted.compareAndSet(false, true)
        if (!shouldStart) {
            val alreadyReady: Boolean
            synchronized(lock) {
                alreadyReady = sdkInitialized
                if (!alreadyReady) {
                    pendingPostInitActions.add(onCompleted)
                }
            }
            if (alreadyReady) {
                onCompleted()
            }
            return
        }

        synchronized(lock) {
            pendingPostInitActions.add(onCompleted)
        }

        loaderPort.initializeMobileAds {
            val callbacksToRun: List<() -> Unit>
            val unitsToWarm: List<String>
            synchronized(lock) {
                sdkInitialized = true
                callbacksToRun = pendingPostInitActions.toList()
                pendingPostInitActions.clear()
                unitsToWarm = pools.values
                    .filter { it.screenDemands.values.sum() > 0 }
                    .map { it.adUnitId }
            }
            callbacksToRun.forEach { it.invoke() }
            unitsToWarm.forEach { maybeRequestMoreForUnit(it) }
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        appInForeground = inForeground
        if (!inForeground) {
            val runnablesToCancel: List<Runnable>
            synchronized(lock) {
                runnablesToCancel = pools.values.mapNotNull { pool ->
                    val r = pool.pendingRetryRunnable
                    pool.pendingRetryRunnable = null
                    r
                }
            }
            runnablesToCancel.forEach { scheduler.removeCallbacks(it) }
            return
        }

        purgeExpiredUnusedAds()
        val activeUnits: List<String> = synchronized(lock) {
            pools.values
                .filter { it.screenDemands.values.sum() > 0 }
                .map { it.adUnitId }
        }
        activeUnits.forEach { maybeRequestMoreForUnit(it) }
    }

    /**
     * Handles network availability changes, resetting retry budgets ONLY on a genuine
     * disconnected -> connected transition. Wi-Fi <-> mobile handoffs while already
     * connected preserve active retry backoff so NO_FILL throttling is respected.
     */
    fun onConnectivityChanged(isConnected: Boolean) {
        if (!isConnected) {
            synchronized(lock) {
                lastKnownConnected = false
            }
            return
        }

        val networkNowAvailable = loaderPort.isNetworkAvailable()
        if (!networkNowAvailable) {
            synchronized(lock) {
                lastKnownConnected = false
            }
            return
        }

        val runnablesToCancel = mutableListOf<Runnable>()
        var unitsToRecover: List<String> = emptyList()
        val now = timeProvider()
        synchronized(lock) {
            val wasDisconnected = (lastKnownConnected == false)
            lastKnownConnected = true
            if (!appInForeground || !sdkInitialized) {
                return@synchronized
            }
            unitsToRecover = pools.values.filter { pool ->
                val hasUnmetDemand = !pool.blockedByNonRetryableError &&
                    pool.screenDemands.values.sum() > 0 &&
                    countUnusedValidLocked(pool, now) < computeTargetUnusedLocked(pool)
                if (!hasUnmetDemand) {
                    false
                } else if (wasDisconnected) {
                    true
                } else {
                    // Already connected (e.g., Wi-Fi <-> mobile handoff): never reset or bypass
                    // active NO_FILL backoff, pending retry timers, or exhausted retry budgets.
                    pool.retryAttempt == 0 &&
                        pool.retriesExhaustedAtMs == 0L &&
                        pool.pendingRetryRunnable == null &&
                        now >= pool.nextRetryAllowedAtMs
                }
            }.map { pool ->
                if (wasDisconnected) {
                    pool.retryAttempt = 0
                    pool.nextRetryAllowedAtMs = 0L
                    pool.retriesExhaustedAtMs = 0L
                    pool.pendingRetryRunnable?.let { runnablesToCancel.add(it) }
                    pool.pendingRetryRunnable = null
                }
                pool.adUnitId
            }
        }
        runnablesToCancel.forEach { scheduler.removeCallbacks(it) }
        unitsToRecover.forEach { maybeRequestMoreForUnit(it) }
    }

    fun registerScreenDemand(screenKey: String, adUnitId: String, eligibleSlotCount: Int) {
        if (eligibleSlotCount <= 0) {
            unregisterScreenDemand(screenKey, adUnitId)
            return
        }

        val now = timeProvider()
        synchronized(lock) {
            val pool = getOrCreatePoolLocked(adUnitId)
            val wasEmpty = pool.screenDemands.isEmpty()
            pool.screenDemands[screenKey] = eligibleSlotCount
            if (wasEmpty &&
                pool.retriesExhaustedAtMs > 0L &&
                now >= pool.retriesExhaustedAtMs + config.retryCooldownResetMs
            ) {
                pool.retryAttempt = 0
                pool.nextRetryAllowedAtMs = 0L
                pool.retriesExhaustedAtMs = 0L
            }
        }
        maybeRequestMoreForUnit(adUnitId)
    }

    fun unregisterScreenDemand(screenKey: String, adUnitId: String) {
        var retryToCancel: Runnable? = null
        synchronized(lock) {
            val pool = pools[adUnitId] ?: return
            pool.screenDemands.remove(screenKey)
            if (pool.screenDemands.isEmpty()) {
                retryToCancel = pool.pendingRetryRunnable
                pool.pendingRetryRunnable = null
            }
        }
        retryToCancel?.let { scheduler.removeCallbacks(it) }
    }

    /**
     * Assigns a single unique, valid, non-expired [ManagedAdEntry] exclusively to [placementKey].
     * Never reuses an ad currently assigned or attached elsewhere, and never performs modulo fallback.
     */
    fun acquireAdForSlot(adUnitId: String, placementKey: String): ManagedAdEntry? {
        val now = timeProvider()
        val toDestroy = mutableListOf<ManagedAdEntry>()
        var selected: ManagedAdEntry? = null
        var reusedUnused = false
        var shouldReplenish = false
        var notifyUnit = false

        synchronized(lock) {
            val pool = getOrCreatePoolLocked(adUnitId)
            purgeExpiredUnusedLocked(pool, now, toDestroy)

            // 1. Check if placement already holds an exclusive ad entry
            val existing = pool.entries.firstOrNull {
                !it.isDisposed && !it.pendingDisposal && it.assignedPlacementKey == placementKey
            }
            if (existing != null) {
                if (existing.isExpired(now, config.expirationMs)) {
                    existing.assignedPlacementKey = null
                    existing.pendingDisposal = true
                    recordExpiredEvent(existing, now)
                    if (!existing.isAttachedToAnyView()) {
                        pool.entries.remove(existing)
                        toDestroy.add(existing)
                    }
                    notifyUnit = true
                } else {
                    return existing
                }
            }

            // 2. Claim the oldest AVAILABLE, non-expired, unattached entry
            val available = pool.entries.firstOrNull {
                it.state == NativeAdLifecycleState.AVAILABLE &&
                    !it.isDisposed &&
                    !it.pendingDisposal &&
                    it.assignedPlacementKey == null &&
                    !it.isAttachedToAnyView() &&
                    !it.isExpired(now, config.expirationMs)
            }

            if (available != null) {
                reusedUnused = available.returnedFromUnusedCache
                available.state = NativeAdLifecycleState.RESERVED
                available.assignedPlacementKey = placementKey
                available.assignedAtMs = now
                available.visibleRecordedForSession = false
                selected = available
                shouldReplenish = true
            } else {
                shouldReplenish = true
            }
        }

        destroyEntriesSafely(toDestroy, now)

        if (selected != null) {
            diagnostics.record(
                NativeAdDiagnosticEvent(
                    eventType = NativeAdEventType.AD_ASSIGNED,
                    adUnitId = adUnitId,
                    adInstanceId = selected!!.instanceId,
                    placementName = placementKey,
                    requestTimestampMs = selected!!.requestTimestampMs,
                    loadTimestampMs = selected!!.loadedAtMs,
                    cacheAgeMs = selected!!.cacheAgeMs(now),
                    lifecycleState = selected!!.state,
                    impressionRecorded = selected!!.impressionRecorded
                ),
                reusedFromUnusedCache = reusedUnused
            )
        }

        if (notifyUnit) {
            onUnitStateChanged(adUnitId)
        }
        if (shouldReplenish) {
            maybeRequestMoreForUnit(adUnitId)
        }
        return selected
    }

    fun onViewAttached(adUnitId: String, instanceId: String, viewToken: Int, placementKey: String) {
        val now = timeProvider()
        var attachedEntry: ManagedAdEntry? = null
        synchronized(lock) {
            val entry = findEntryLocked(adUnitId, instanceId) ?: return
            if (entry.isDisposed) return
            entry.attachedViewTokens.add(viewToken)
            if (entry.state == NativeAdLifecycleState.RESERVED ||
                entry.state == NativeAdLifecycleState.AVAILABLE ||
                entry.state == NativeAdLifecycleState.DETACHING
            ) {
                entry.state = NativeAdLifecycleState.ATTACHED
            }
            attachedEntry = entry
        }
        attachedEntry?.let { entry ->
            diagnostics.record(
                NativeAdDiagnosticEvent(
                    eventType = NativeAdEventType.VIEW_ATTACHED,
                    adUnitId = adUnitId,
                    adInstanceId = entry.instanceId,
                    placementName = placementKey,
                    requestTimestampMs = entry.requestTimestampMs,
                    loadTimestampMs = entry.loadedAtMs,
                    cacheAgeMs = entry.cacheAgeMs(now),
                    lifecycleState = entry.state,
                    impressionRecorded = entry.impressionRecorded
                )
            )
        }
    }

    fun onPlacementVisible(adUnitId: String, instanceId: String, placementKey: String) {
        if (!appInForeground) return
        val now = timeProvider()
        var newlyVisible: ManagedAdEntry? = null
        synchronized(lock) {
            val entry = findEntryLocked(adUnitId, instanceId) ?: return
            if (entry.isDisposed || entry.pendingDisposal || entry.visibleRecordedForSession) return
            entry.visibleRecordedForSession = true
            newlyVisible = entry
        }
        newlyVisible?.let { entry ->
            diagnostics.record(
                NativeAdDiagnosticEvent(
                    eventType = NativeAdEventType.PLACEMENT_VISIBLE,
                    adUnitId = adUnitId,
                    adInstanceId = entry.instanceId,
                    placementName = placementKey,
                    requestTimestampMs = entry.requestTimestampMs,
                    loadTimestampMs = entry.loadedAtMs,
                    cacheAgeMs = entry.cacheAgeMs(now),
                    lifecycleState = entry.state,
                    impressionRecorded = entry.impressionRecorded
                )
            )
        }
    }

    fun onViewDetached(adUnitId: String, instanceId: String, viewToken: Int) {
        val now = timeProvider()
        var entryToDestroy: ManagedAdEntry? = null
        var discardedSurplus = false
        var notifyAvailable = false
        synchronized(lock) {
            val pool = pools[adUnitId] ?: return
            val entry = pool.entries.firstOrNull { it.instanceId == instanceId } ?: return
            entry.attachedViewTokens.remove(viewToken)
            if (!entry.isAttachedToAnyView()) {
                if (entry.pendingDisposal && !entry.isDisposed) {
                    pool.entries.remove(entry)
                    entryToDestroy = entry
                } else if (entry.state == NativeAdLifecycleState.DETACHING && !entry.isDisposed) {
                    val expired = entry.isExpired(now, config.expirationMs)
                    val hasPoolCapacity = countUnusedValidLocked(pool, now) < config.maxUnusedPerUnit
                    if (expired) {
                        recordExpiredEvent(entry, now)
                        entry.pendingDisposal = true
                        pool.entries.remove(entry)
                        entryToDestroy = entry
                    } else if (!hasPoolCapacity) {
                        discardedSurplus = true
                        entry.pendingDisposal = true
                        pool.entries.remove(entry)
                        entryToDestroy = entry
                    } else {
                        entry.state = NativeAdLifecycleState.AVAILABLE
                        entry.returnedFromUnusedCache = true
                        notifyAvailable = true
                    }
                } else if (entry.state == NativeAdLifecycleState.AVAILABLE && !entry.isDisposed) {
                    notifyAvailable = true
                }
            }
        }
        if (discardedSurplus && entryToDestroy != null) {
            recordSurplusDiscardedEvent(entryToDestroy!!, now)
        }
        entryToDestroy?.let {
            destroyEntriesSafely(listOf(it), now)
            onUnitStateChanged(adUnitId)
        }
        if (notifyAvailable) {
            onUnitStateChanged(adUnitId)
        }
    }

    /**
     * Releases a slot when its composable leaves composition or navigates away.
     * - Impressed or expired ads are scheduled for safe single disposal (after view detachment).
     * - Valid un-impressed ads return to AVAILABLE cache up to [NativeAdCacheConfig.maxUnusedPerUnit],
     *   entering [NativeAdLifecycleState.DETACHING] if any view is still attached until [onViewDetached]
     *   removes the final view token.
     */
    fun releaseSlot(adUnitId: String, placementKey: String) {
        val now = timeProvider()
        var entryToDestroy: ManagedAdEntry? = null
        var returnedToCache = false
        var discardedSurplus: ManagedAdEntry? = null

        synchronized(lock) {
            val pool = pools[adUnitId] ?: return
            val entry = pool.entries.firstOrNull {
                !it.isDisposed && it.assignedPlacementKey == placementKey
            } ?: return

            entry.assignedPlacementKey = null
            entry.assignedAtMs = null
            entry.visibleRecordedForSession = false

            val expired = entry.isExpired(now, config.expirationMs)
            if (expired) {
                recordExpiredEvent(entry, now)
            }
            val overCapacity = !entry.impressionRecorded &&
                !expired &&
                countUnusedValidLocked(pool, now) >= config.maxUnusedPerUnit
            val shouldDiscard = entry.impressionRecorded || expired || overCapacity

            if (shouldDiscard) {
                if (overCapacity) {
                    discardedSurplus = entry
                }
                entry.pendingDisposal = true
                if (!entry.isAttachedToAnyView()) {
                    pool.entries.remove(entry)
                    entryToDestroy = entry
                }
            } else if (entry.isAttachedToAnyView()) {
                // Keep out of AVAILABLE count until AndroidView.onRelease finishes detaching
                entry.state = NativeAdLifecycleState.DETACHING
                entry.returnedFromUnusedCache = true
            } else {
                entry.state = NativeAdLifecycleState.AVAILABLE
                entry.returnedFromUnusedCache = true
                returnedToCache = true
            }
        }

        discardedSurplus?.let { recordSurplusDiscardedEvent(it, now) }
        if (entryToDestroy != null) {
            destroyEntriesSafely(listOf(entryToDestroy!!), now)
        }
        if (returnedToCache || entryToDestroy != null) {
            onUnitStateChanged(adUnitId)
        }
    }

    private fun recordExpiredEvent(entry: ManagedAdEntry, nowMs: Long) {
        diagnostics.record(
            NativeAdDiagnosticEvent(
                eventType = NativeAdEventType.AD_EXPIRED,
                adUnitId = entry.adUnitId,
                adInstanceId = entry.instanceId,
                placementName = entry.assignedPlacementKey,
                requestTimestampMs = entry.requestTimestampMs,
                loadTimestampMs = entry.loadedAtMs,
                cacheAgeMs = entry.cacheAgeMs(nowMs),
                lifecycleState = entry.state,
                impressionRecorded = entry.impressionRecorded
            )
        )
    }

    private fun recordSurplusDiscardedEvent(entry: ManagedAdEntry, nowMs: Long) {
        diagnostics.record(
            NativeAdDiagnosticEvent(
                eventType = NativeAdEventType.AD_DISCARDED_SURPLUS,
                adUnitId = entry.adUnitId,
                adInstanceId = entry.instanceId,
                placementName = entry.assignedPlacementKey,
                requestTimestampMs = entry.requestTimestampMs,
                loadTimestampMs = entry.loadedAtMs,
                cacheAgeMs = entry.cacheAgeMs(nowMs),
                lifecycleState = NativeAdLifecycleState.DISPOSED,
                impressionRecorded = entry.impressionRecorded
            )
        )
    }

    fun purgeExpiredUnusedAds() {
        val now = timeProvider()
        val toDestroy = mutableListOf<ManagedAdEntry>()
        val changedUnits = mutableSetOf<String>()

        synchronized(lock) {
            for (pool in pools.values) {
                val removed = purgeExpiredUnusedLocked(pool, now, toDestroy)
                if (removed) {
                    changedUnits.add(pool.adUnitId)
                }
            }
        }
        if (toDestroy.isNotEmpty()) {
            destroyEntriesSafely(toDestroy, now)
            changedUnits.forEach { onUnitStateChanged(it) }
        }
    }

    /**
     * Resettable UI/session cleanup safe for when a host Activity finishes while a background
     * playback service keeps the process alive. Releases UI-bound ad slots, cancels pending
     * retries, invalidates in-flight load callbacks via [sessionGeneration], and keeps the
     * controller reusable if an Activity restarts later.
     */
    fun clearUiSession() {
        val toDestroy = mutableListOf<ManagedAdEntry>()
        val runnablesToCancel = mutableListOf<Runnable>()
        synchronized(lock) {
            sessionGeneration++
            for (pool in pools.values) {
                pool.pendingRetryRunnable?.let { runnablesToCancel.add(it) }
                pool.pendingRetryRunnable = null
                pool.isLoading = false
                pool.retryAttempt = 0
                pool.nextRetryAllowedAtMs = 0L
                pool.retriesExhaustedAtMs = 0L
                pool.blockedByNonRetryableError = false
                pool.screenDemands.clear()
                val iterator = pool.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    entry.assignedPlacementKey = null
                    entry.assignedAtMs = null
                    entry.visibleRecordedForSession = false
                    entry.pendingDisposal = true
                    if (!entry.isAttachedToAnyView()) {
                        iterator.remove()
                        toDestroy.add(entry)
                    }
                }
            }
        }
        runnablesToCancel.forEach { scheduler.removeCallbacks(it) }
        destroyEntriesSafely(toDestroy, timeProvider())
    }

    fun clearAllForShutdown() {
        clearUiSession()
    }

    fun getUnusedValidCount(adUnitId: String): Int = synchronized(lock) {
        val pool = pools[adUnitId] ?: return 0
        countUnusedValidLocked(pool, timeProvider())
    }

    fun getEntriesSnapshot(adUnitId: String): List<ManagedAdEntry> = synchronized(lock) {
        pools[adUnitId]?.entries?.toList() ?: emptyList()
    }

    fun getDiagnosticsSnapshot(): NativeAdDiagnosticsSnapshot = diagnostics.snapshot()

    internal fun maybeRequestMoreForUnit(adUnitId: String) {
        if (!sdkInitialized || !appInForeground) return

        val now = timeProvider()
        val networkAvailable = loaderPort.isNetworkAvailable()
        val expiredToDestroy = mutableListOf<ManagedAdEntry>()
        var shouldLoadNow = false
        var requestGeneration = 0L

        synchronized(lock) {
            lastKnownConnected = networkAvailable
            val pool = getOrCreatePoolLocked(adUnitId)
            purgeExpiredUnusedLocked(pool, now, expiredToDestroy)

            if (pool.blockedByNonRetryableError) {
                return@synchronized
            }

            if (pool.retriesExhaustedAtMs > 0L) {
                if (now >= pool.retriesExhaustedAtMs + config.retryCooldownResetMs) {
                    pool.retryAttempt = 0
                    pool.nextRetryAllowedAtMs = 0L
                    pool.retriesExhaustedAtMs = 0L
                } else {
                    return@synchronized
                }
            }

            val targetUnused = computeTargetUnusedLocked(pool)
            val currentUnused = countUnusedValidLocked(pool, now)

            if (targetUnused <= 0 || currentUnused >= targetUnused) {
                return@synchronized
            }
            if (pool.isLoading || pool.pendingRetryRunnable != null) {
                return@synchronized
            }
            if (now < pool.nextRetryAllowedAtMs) {
                scheduleRetryLocked(pool, (pool.nextRetryAllowedAtMs - now).coerceAtLeast(1L))
                return@synchronized
            }
            if (!networkAvailable) {
                return@synchronized
            }

            pool.isLoading = true
            shouldLoadNow = true
            requestGeneration = sessionGeneration
        }

        destroyEntriesSafely(expiredToDestroy, now)
        if (expiredToDestroy.isNotEmpty()) {
            onUnitStateChanged(adUnitId)
        }
        if (shouldLoadNow) {
            executeSingleLoad(adUnitId, now, requestGeneration)
        }
    }

    private fun executeSingleLoad(adUnitId: String, requestTimestampMs: Long, requestGeneration: Long) {
        diagnostics.record(
            NativeAdDiagnosticEvent(
                eventType = NativeAdEventType.REQUEST_INITIATED,
                adUnitId = adUnitId,
                requestTimestampMs = requestTimestampMs
            )
        )

        var createdEntry: ManagedAdEntry? = null

        loaderPort.loadSingleNativeAd(
            adUnitId = adUnitId,
            callbacks = object : AdRequestCallbacks {
                override fun onAdLoaded(handle: ManagedAdHandle) {
                    val loadTimeMs = timeProvider()
                    val entry = ManagedAdEntry(
                        instanceId = "ad_${adUnitId.takeLast(4)}_${instanceSequence.incrementAndGet()}",
                        adUnitId = adUnitId,
                        handle = handle,
                        requestTimestampMs = requestTimestampMs,
                        loadedAtMs = loadTimeMs
                    )
                    createdEntry = entry

                    handle.setOnPaidEventCallback { valueMicros, currencyCode, precisionType ->
                        diagnostics.record(
                            NativeAdDiagnosticEvent(
                                eventType = NativeAdEventType.PAID_EVENT,
                                adUnitId = adUnitId,
                                adInstanceId = entry.instanceId,
                                placementName = entry.assignedPlacementKey,
                                requestTimestampMs = entry.requestTimestampMs,
                                loadTimestampMs = entry.loadedAtMs,
                                cacheAgeMs = entry.cacheAgeMs(timeProvider()),
                                lifecycleState = entry.state,
                                impressionRecorded = entry.impressionRecorded,
                                paidValueMicros = valueMicros,
                                paidCurrencyCode = currencyCode,
                                paidPrecisionType = precisionType
                            )
                        )
                    }

                    var discardImmediately = false
                    var staleGeneration = false
                    synchronized(lock) {
                        if (requestGeneration != sessionGeneration) {
                            staleGeneration = true
                            discardImmediately = true
                            entry.pendingDisposal = true
                            return@synchronized
                        }

                        val pool = getOrCreatePoolLocked(adUnitId)
                        pool.isLoading = false
                        pool.retryAttempt = 0
                        pool.nextRetryAllowedAtMs = 0L
                        pool.retriesExhaustedAtMs = 0L

                        if (countUnusedValidLocked(pool, loadTimeMs) >= config.maxUnusedPerUnit) {
                            discardImmediately = true
                            entry.pendingDisposal = true
                        } else {
                            pool.entries.add(entry)
                        }
                    }

                    diagnostics.record(
                        NativeAdDiagnosticEvent(
                            eventType = NativeAdEventType.LOAD_SUCCEEDED,
                            adUnitId = adUnitId,
                            adInstanceId = entry.instanceId,
                            requestTimestampMs = requestTimestampMs,
                            loadTimestampMs = loadTimeMs,
                            cacheAgeMs = 0L,
                            lifecycleState = if (discardImmediately) NativeAdLifecycleState.DISPOSED else entry.state
                        )
                    )

                    if (discardImmediately) {
                        recordSurplusDiscardedEvent(entry, loadTimeMs)
                        destroyEntriesSafely(listOf(entry), loadTimeMs)
                    } else if (!staleGeneration) {
                        onUnitStateChanged(adUnitId)
                        maybeRequestMoreForUnit(adUnitId)
                    }
                }

                override fun onAdFailed(error: AdLoadErrorInfo) {
                    val failTimeMs = timeProvider()
                    val category = error.classify()

                    synchronized(lock) {
                        if (requestGeneration != sessionGeneration) {
                            return@synchronized
                        }

                        val pool = getOrCreatePoolLocked(adUnitId)
                        pool.isLoading = false

                        when (category) {
                            AdFailureCategory.NON_RETRYABLE -> {
                                pool.blockedByNonRetryableError = true
                            }
                            AdFailureCategory.NO_FILL_OR_RATE_LIMIT,
                            AdFailureCategory.RECOVERABLE_TRANSIENT -> {
                                val maxAttempts = if (category == AdFailureCategory.NO_FILL_OR_RATE_LIMIT) {
                                    config.maxNoFillRetries
                                } else {
                                    config.maxTransientRetries
                                }
                                val targetUnused = computeTargetUnusedLocked(pool)
                                val currentUnused = countUnusedValidLocked(pool, failTimeMs)
                                val networkAvailable = loaderPort.isNetworkAvailable()
                                lastKnownConnected = networkAvailable

                                if (appInForeground &&
                                    targetUnused > 0 &&
                                    currentUnused < targetUnused &&
                                    networkAvailable
                                ) {
                                    if (pool.retryAttempt < maxAttempts) {
                                        val baseDelay = if (category == AdFailureCategory.NO_FILL_OR_RATE_LIMIT) {
                                            config.noFillBaseRetryDelayMs
                                        } else {
                                            config.transientBaseRetryDelayMs
                                        }
                                        val delayMs = min(
                                            baseDelay * (1L shl pool.retryAttempt.coerceAtMost(10)),
                                            config.maxRetryDelayMs
                                        )
                                        pool.retryAttempt++
                                        pool.nextRetryAllowedAtMs = failTimeMs + delayMs
                                        scheduleRetryLocked(pool, delayMs)
                                    } else {
                                        pool.retriesExhaustedAtMs = failTimeMs
                                        pool.nextRetryAllowedAtMs = maxOf(
                                            pool.nextRetryAllowedAtMs,
                                            failTimeMs + config.retryCooldownResetMs
                                        )
                                    }
                                }
                            }
                        }
                    }

                    diagnostics.record(
                        NativeAdDiagnosticEvent(
                            eventType = NativeAdEventType.LOAD_FAILED,
                            adUnitId = adUnitId,
                            requestTimestampMs = requestTimestampMs,
                            errorCode = error.code,
                            errorDomain = error.domain,
                            errorMessage = error.message
                        )
                    )
                }

                override fun onAdImpression() {
                    val entry = createdEntry ?: return
                    val now = timeProvider()
                    synchronized(lock) {
                        if (entry.isDisposed) return
                        entry.impressionRecorded = true
                        entry.state = NativeAdLifecycleState.IMPRESSED
                    }
                    diagnostics.record(
                        NativeAdDiagnosticEvent(
                            eventType = NativeAdEventType.IMPRESSION_RECORDED,
                            adUnitId = adUnitId,
                            adInstanceId = entry.instanceId,
                            placementName = entry.assignedPlacementKey,
                            requestTimestampMs = entry.requestTimestampMs,
                            loadTimestampMs = entry.loadedAtMs,
                            cacheAgeMs = entry.cacheAgeMs(now),
                            lifecycleState = entry.state,
                            impressionRecorded = true
                        )
                    )
                }

                override fun onAdClicked() {
                    val entry = createdEntry ?: return
                    val now = timeProvider()
                    diagnostics.record(
                        NativeAdDiagnosticEvent(
                            eventType = NativeAdEventType.AD_CLICKED,
                            adUnitId = adUnitId,
                            adInstanceId = entry.instanceId,
                            placementName = entry.assignedPlacementKey,
                            requestTimestampMs = entry.requestTimestampMs,
                            loadTimestampMs = entry.loadedAtMs,
                            cacheAgeMs = entry.cacheAgeMs(now),
                            lifecycleState = entry.state,
                            impressionRecorded = entry.impressionRecorded
                        )
                    )
                }
            }
        )
    }

    private fun scheduleRetryLocked(pool: UnitAdPool, delayMs: Long) {
        if (pool.pendingRetryRunnable != null || !appInForeground) return
        val scheduledGeneration = sessionGeneration
        val runnable = object : Runnable {
            override fun run() {
                var validGeneration = false
                synchronized(lock) {
                    if (pool.pendingRetryRunnable === this) {
                        pool.pendingRetryRunnable = null
                    }
                    validGeneration = (scheduledGeneration == sessionGeneration)
                }
                if (validGeneration) {
                    maybeRequestMoreForUnit(pool.adUnitId)
                }
            }
        }
        pool.pendingRetryRunnable = runnable
        scheduler.postDelayed(delayMs, runnable)
    }

    private fun getOrCreatePoolLocked(adUnitId: String): UnitAdPool {
        return pools.getOrPut(adUnitId) { UnitAdPool(adUnitId) }
    }

    private fun findEntryLocked(adUnitId: String, instanceId: String): ManagedAdEntry? {
        return pools[adUnitId]?.entries?.firstOrNull { it.instanceId == instanceId }
    }

    private fun computeTargetUnusedLocked(pool: UnitAdPool): Int {
        val totalDemandSlots = pool.screenDemands.values.sum()
        if (totalDemandSlots <= 0) return 0
        val desired = if (totalDemandSlots >= 2) {
            config.expandedUnusedReserveTarget
        } else {
            config.defaultUnusedTarget
        }
        return desired.coerceAtMost(config.maxUnusedPerUnit)
    }

    private fun countUnusedValidLocked(pool: UnitAdPool, nowMs: Long): Int {
        return pool.entries.count {
            it.state == NativeAdLifecycleState.AVAILABLE &&
                !it.isDisposed &&
                !it.pendingDisposal &&
                it.assignedPlacementKey == null &&
                !it.isAttachedToAnyView() &&
                !it.isExpired(nowMs, config.expirationMs)
        }
    }

    private fun purgeExpiredUnusedLocked(
        pool: UnitAdPool,
        nowMs: Long,
        outDestroyed: MutableList<ManagedAdEntry>
    ): Boolean {
        var removedAny = false
        val iterator = pool.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (!entry.isDisposed && entry.isExpired(nowMs, config.expirationMs)) {
                if ((entry.state == NativeAdLifecycleState.AVAILABLE || entry.state == NativeAdLifecycleState.DETACHING) &&
                    !entry.isAttachedToAnyView()
                ) {
                    entry.pendingDisposal = true
                    iterator.remove()
                    outDestroyed.add(entry)
                    removedAny = true
                    diagnostics.record(
                        NativeAdDiagnosticEvent(
                            eventType = NativeAdEventType.AD_EXPIRED,
                            adUnitId = pool.adUnitId,
                            adInstanceId = entry.instanceId,
                            requestTimestampMs = entry.requestTimestampMs,
                            loadTimestampMs = entry.loadedAtMs,
                            cacheAgeMs = entry.cacheAgeMs(nowMs),
                            lifecycleState = entry.state,
                            impressionRecorded = entry.impressionRecorded
                        )
                    )
                } else {
                    // Already assigned or attached: mark for disposal when detached, never destroy while visible
                    entry.pendingDisposal = true
                }
            }
        }
        return removedAny
    }

    private fun destroyEntriesSafely(entries: List<ManagedAdEntry>, nowMs: Long) {
        if (entries.isEmpty()) return
        for (entry in entries) {
            var shouldDestroy = false
            synchronized(lock) {
                if (!entry.isDisposed && !entry.isAttachedToAnyView()) {
                    entry.isDisposed = true
                    entry.state = NativeAdLifecycleState.DISPOSED
                    shouldDestroy = true
                }
            }
            if (shouldDestroy) {
                scheduler.runOnMainThread {
                    try {
                        entry.handle.destroy()
                    } catch (_: Exception) {
                    }
                }
                diagnostics.record(
                    NativeAdDiagnosticEvent(
                        eventType = NativeAdEventType.AD_DISPOSED,
                        adUnitId = entry.adUnitId,
                        adInstanceId = entry.instanceId,
                        placementName = entry.assignedPlacementKey,
                        requestTimestampMs = entry.requestTimestampMs,
                        loadTimestampMs = entry.loadedAtMs,
                        cacheAgeMs = entry.cacheAgeMs(nowMs),
                        lifecycleState = NativeAdLifecycleState.DISPOSED,
                        impressionRecorded = entry.impressionRecorded
                    )
                )
            }
        }
    }
}
