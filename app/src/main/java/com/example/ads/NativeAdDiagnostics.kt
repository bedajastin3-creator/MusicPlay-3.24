package com.example.ads

import android.util.Log
import com.example.BuildConfig
import java.util.Locale

enum class NativeAdLifecycleState {
    AVAILABLE,
    RESERVED,
    ATTACHED,
    DETACHING,
    IMPRESSED,
    DISPOSED
}

enum class NativeAdEventType {
    REQUEST_INITIATED,
    LOAD_SUCCEEDED,
    LOAD_FAILED,
    AD_ASSIGNED,
    VIEW_ATTACHED,
    PLACEMENT_VISIBLE,
    IMPRESSION_RECORDED,
    AD_CLICKED,
    PAID_EVENT,
    AD_EXPIRED,
    AD_DISCARDED_SURPLUS,
    AD_DISPOSED
}

fun normalizePaidCurrencyCode(currencyCode: String?): String {
    val trimmed = currencyCode?.trim().orEmpty()
    return if (trimmed.isEmpty()) "UNKNOWN" else trimmed.uppercase(Locale.US)
}

data class NativeAdDiagnosticEvent(
    val eventType: NativeAdEventType,
    val adUnitId: String,
    val adInstanceId: String? = null,
    val placementName: String? = null,
    val requestTimestampMs: Long? = null,
    val loadTimestampMs: Long? = null,
    val cacheAgeMs: Long? = null,
    val lifecycleState: NativeAdLifecycleState? = null,
    val impressionRecorded: Boolean = false,
    val errorCode: Int? = null,
    val errorDomain: String? = null,
    val errorMessage: String? = null,
    val paidValueMicros: Long? = null,
    val paidCurrencyCode: String? = null,
    val paidPrecisionType: Int? = null,
    val timestampMs: Long = 0L
)

/**
 * In-process per-unit SDK diagnostics.
 *
 * NOTE: [matchRatePercent], [showRatePercent], and [localDisplayableShowRatePercent] are computed
 * strictly from local in-process SDK callbacks for debugging funnel drop-off (requests, fills,
 * surplus discards, expirations, assignments, view attachments, viewport visibility, and
 * `AdListener.onAdImpression()`). They do NOT replicate AdMob console metrics (which apply
 * server-side invalid-traffic filtering and cross-session attribution).
 */
data class NativeAdUnitMetrics(
    val adUnitId: String,
    val requestsInitiated: Long = 0L,
    val loadsSucceeded: Long = 0L,
    val loadsFailed: Long = 0L,
    val discardedSurplusCount: Long = 0L,
    val assignments: Long = 0L,
    val viewAttachments: Long = 0L,
    val placementVisibilities: Long = 0L,
    val impressionsRecorded: Long = 0L,
    val clicks: Long = 0L,
    val paidEventsCount: Long = 0L,
    val paidValueMicrosByCurrency: Map<String, Long> = emptyMap(),
    val paidEventsCountByCurrency: Map<String, Long> = emptyMap(),
    val paidEventsCountByPrecision: Map<Int, Long> = emptyMap(),
    val expiredCount: Long = 0L,
    val disposedCount: Long = 0L,
    val cacheHitsReusedAcrossNavigation: Long = 0L
) {
    /** Local in-process fill rate: `loadsSucceeded / requestsInitiated * 100`. */
    val matchRatePercent: Double
        get() = if (requestsInitiated <= 0L) 0.0 else (loadsSucceeded.toDouble() / requestsInitiated.toDouble()) * 100.0

    /**
     * Local in-process load-to-impression ratio: `impressionsRecorded / loadsSucceeded * 100`.
     * Includes surplus loads discarded before display; see [localDisplayableShowRatePercent] for
     * retained loads only. Not directly comparable to the AdMob dashboard Show Rate.
     */
    val showRatePercent: Double
        get() = if (loadsSucceeded <= 0L) 0.0 else (impressionsRecorded.toDouble() / loadsSucceeded.toDouble()) * 100.0

    /**
     * Local in-process ratio excluding surplus ads discarded before assignment:
     * `impressionsRecorded / (loadsSucceeded - discardedSurplusCount) * 100`.
     */
    val localDisplayableShowRatePercent: Double
        get() {
            val retainedLoads = (loadsSucceeded - discardedSurplusCount).coerceAtLeast(0L)
            return if (retainedLoads <= 0L) 0.0 else (impressionsRecorded.toDouble() / retainedLoads.toDouble()) * 100.0
        }
}

/**
 * Process-local aggregated diagnostics snapshot across all ad units.
 *
 * Paid values are never converted or summed across distinct currency codes; check
 * [paidValueMicrosByCurrency] per currency instead.
 */
data class NativeAdDiagnosticsSnapshot(
    val byUnit: Map<String, NativeAdUnitMetrics>,
    val totalRequestsInitiated: Long,
    val totalLoadsSucceeded: Long,
    val totalLoadsFailed: Long,
    val totalDiscardedSurplusCount: Long,
    val totalAssignments: Long,
    val totalViewAttachments: Long,
    val totalPlacementVisibilities: Long,
    val totalImpressionsRecorded: Long,
    val totalClicks: Long,
    val totalPaidEventsCount: Long,
    val paidValueMicrosByCurrency: Map<String, Long>,
    val paidEventsCountByCurrency: Map<String, Long>,
    val paidEventsCountByPrecision: Map<Int, Long>,
    val totalExpiredCount: Long,
    val totalDisposedCount: Long,
    val recentEvents: List<NativeAdDiagnosticEvent>
) {
    /** Local in-process match rate across all units (not equivalent to AdMob console reporting). */
    val overallMatchRatePercent: Double
        get() = if (totalRequestsInitiated <= 0L) 0.0 else (totalLoadsSucceeded.toDouble() / totalRequestsInitiated.toDouble()) * 100.0

    /** Local in-process load-to-impression ratio across all units (not equivalent to AdMob console reporting). */
    val overallShowRatePercent: Double
        get() = if (totalLoadsSucceeded <= 0L) 0.0 else (totalImpressionsRecorded.toDouble() / totalLoadsSucceeded.toDouble()) * 100.0

    /** Local in-process impression ratio for retained (non-surplus) loads across all units. */
    val overallLocalDisplayableShowRatePercent: Double
        get() {
            val retained = (totalLoadsSucceeded - totalDiscardedSurplusCount).coerceAtLeast(0L)
            return if (retained <= 0L) 0.0 else (totalImpressionsRecorded.toDouble() / retained.toDouble()) * 100.0
        }
}

/**
 * Lightweight, thread-safe, PII-free in-process diagnostic tracker distinguishing ad requests,
 * loads, surplus discards, expirations, assignments, view attachments, meaningful viewport
 * visibility, authoritative SDK impressions, clicks, currency-separated paid events, and disposals.
 */
class NativeAdDiagnosticsTracker(
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val debugLoggingEnabled: Boolean = BuildConfig.DEBUG,
    private val maxRecentEvents: Int = 200,
    private val logger: (Int, String, String) -> Unit = { priority, tag, msg ->
        Log.println(priority, tag, msg)
    }
) {
    private val lock = Any()
    private val unitMetrics = mutableMapOf<String, MutableUnitCounters>()
    private val recentEvents = ArrayDeque<NativeAdDiagnosticEvent>(maxRecentEvents)

    private class MutableUnitCounters {
        var requestsInitiated = 0L
        var loadsSucceeded = 0L
        var loadsFailed = 0L
        var discardedSurplusCount = 0L
        var assignments = 0L
        var viewAttachments = 0L
        var placementVisibilities = 0L
        var impressionsRecorded = 0L
        var clicks = 0L
        var paidEventsCount = 0L
        val paidValueMicrosByCurrency = mutableMapOf<String, Long>()
        val paidEventsCountByCurrency = mutableMapOf<String, Long>()
        val paidEventsCountByPrecision = mutableMapOf<Int, Long>()
        var expiredCount = 0L
        var disposedCount = 0L
        var cacheHitsReusedAcrossNavigation = 0L
    }

    fun record(event: NativeAdDiagnosticEvent, reusedFromUnusedCache: Boolean = false) {
        val normalizedCurrency = if (event.eventType == NativeAdEventType.PAID_EVENT) {
            normalizePaidCurrencyCode(event.paidCurrencyCode)
        } else {
            event.paidCurrencyCode
        }
        val stampedEvent = event.copy(
            paidCurrencyCode = normalizedCurrency,
            timestampMs = if (event.timestampMs == 0L) timeProvider() else event.timestampMs
        )

        synchronized(lock) {
            val counters = unitMetrics.getOrPut(stampedEvent.adUnitId) { MutableUnitCounters() }
            when (stampedEvent.eventType) {
                NativeAdEventType.REQUEST_INITIATED -> counters.requestsInitiated++
                NativeAdEventType.LOAD_SUCCEEDED -> counters.loadsSucceeded++
                NativeAdEventType.LOAD_FAILED -> counters.loadsFailed++
                NativeAdEventType.AD_DISCARDED_SURPLUS -> counters.discardedSurplusCount++
                NativeAdEventType.AD_ASSIGNED -> {
                    counters.assignments++
                    if (reusedFromUnusedCache) {
                        counters.cacheHitsReusedAcrossNavigation++
                    }
                }
                NativeAdEventType.VIEW_ATTACHED -> counters.viewAttachments++
                NativeAdEventType.PLACEMENT_VISIBLE -> counters.placementVisibilities++
                NativeAdEventType.IMPRESSION_RECORDED -> counters.impressionsRecorded++
                NativeAdEventType.AD_CLICKED -> counters.clicks++
                NativeAdEventType.PAID_EVENT -> {
                    counters.paidEventsCount++
                    val currencyKey = normalizedCurrency ?: "UNKNOWN"
                    val micros = (stampedEvent.paidValueMicros ?: 0L).coerceAtLeast(0L)
                    counters.paidValueMicrosByCurrency[currencyKey] =
                        (counters.paidValueMicrosByCurrency[currencyKey] ?: 0L) + micros
                    counters.paidEventsCountByCurrency[currencyKey] =
                        (counters.paidEventsCountByCurrency[currencyKey] ?: 0L) + 1L
                    stampedEvent.paidPrecisionType?.let { precision ->
                        counters.paidEventsCountByPrecision[precision] =
                            (counters.paidEventsCountByPrecision[precision] ?: 0L) + 1L
                    }
                }
                NativeAdEventType.AD_EXPIRED -> counters.expiredCount++
                NativeAdEventType.AD_DISPOSED -> counters.disposedCount++
            }

            if (recentEvents.size >= maxRecentEvents) {
                recentEvents.removeFirst()
            }
            recentEvents.addLast(stampedEvent)
        }

        logIfAppropriate(stampedEvent)
    }

    fun snapshot(): NativeAdDiagnosticsSnapshot = synchronized(lock) {
        val immutableByUnit = unitMetrics.mapValues { (unitId, c) ->
            NativeAdUnitMetrics(
                adUnitId = unitId,
                requestsInitiated = c.requestsInitiated,
                loadsSucceeded = c.loadsSucceeded,
                loadsFailed = c.loadsFailed,
                discardedSurplusCount = c.discardedSurplusCount,
                assignments = c.assignments,
                viewAttachments = c.viewAttachments,
                placementVisibilities = c.placementVisibilities,
                impressionsRecorded = c.impressionsRecorded,
                clicks = c.clicks,
                paidEventsCount = c.paidEventsCount,
                paidValueMicrosByCurrency = c.paidValueMicrosByCurrency.toMap(),
                paidEventsCountByCurrency = c.paidEventsCountByCurrency.toMap(),
                paidEventsCountByPrecision = c.paidEventsCountByPrecision.toMap(),
                expiredCount = c.expiredCount,
                disposedCount = c.disposedCount,
                cacheHitsReusedAcrossNavigation = c.cacheHitsReusedAcrossNavigation
            )
        }
        val values = immutableByUnit.values
        val aggregatedCurrencyMicros = linkedMapOf<String, Long>()
        val aggregatedCurrencyCounts = linkedMapOf<String, Long>()
        val aggregatedPrecisionCounts = linkedMapOf<Int, Long>()
        for (unit in values) {
            for ((currency, micros) in unit.paidValueMicrosByCurrency) {
                aggregatedCurrencyMicros[currency] = (aggregatedCurrencyMicros[currency] ?: 0L) + micros
            }
            for ((currency, count) in unit.paidEventsCountByCurrency) {
                aggregatedCurrencyCounts[currency] = (aggregatedCurrencyCounts[currency] ?: 0L) + count
            }
            for ((precision, count) in unit.paidEventsCountByPrecision) {
                aggregatedPrecisionCounts[precision] = (aggregatedPrecisionCounts[precision] ?: 0L) + count
            }
        }

        NativeAdDiagnosticsSnapshot(
            byUnit = immutableByUnit,
            totalRequestsInitiated = values.sumOf { it.requestsInitiated },
            totalLoadsSucceeded = values.sumOf { it.loadsSucceeded },
            totalLoadsFailed = values.sumOf { it.loadsFailed },
            totalDiscardedSurplusCount = values.sumOf { it.discardedSurplusCount },
            totalAssignments = values.sumOf { it.assignments },
            totalViewAttachments = values.sumOf { it.viewAttachments },
            totalPlacementVisibilities = values.sumOf { it.placementVisibilities },
            totalImpressionsRecorded = values.sumOf { it.impressionsRecorded },
            totalClicks = values.sumOf { it.clicks },
            totalPaidEventsCount = values.sumOf { it.paidEventsCount },
            paidValueMicrosByCurrency = aggregatedCurrencyMicros.toMap(),
            paidEventsCountByCurrency = aggregatedCurrencyCounts.toMap(),
            paidEventsCountByPrecision = aggregatedPrecisionCounts.toMap(),
            totalExpiredCount = values.sumOf { it.expiredCount },
            totalDisposedCount = values.sumOf { it.disposedCount },
            recentEvents = recentEvents.toList()
        )
    }

    fun reset() = synchronized(lock) {
        unitMetrics.clear()
        recentEvents.clear()
    }

    private fun logIfAppropriate(event: NativeAdDiagnosticEvent) {
        if (!debugLoggingEnabled && event.eventType != NativeAdEventType.LOAD_FAILED) {
            return
        }
        val suffix = buildString {
            append("unit=").append(event.adUnitId.takeLast(10))
            event.adInstanceId?.let { append(" id=").append(it) }
            event.placementName?.let { append(" placement=").append(it) }
            event.lifecycleState?.let { append(" state=").append(it.name) }
            event.cacheAgeMs?.let { append(" ageMs=").append(it) }
            if (event.impressionRecorded) append(" impressed=true")
            event.errorCode?.let { append(" errCode=").append(it) }
            event.errorDomain?.let { append(" errDomain=").append(it) }
            event.errorMessage?.let { append(" errMsg=").append(it) }
            event.paidValueMicros?.let {
                append(" paidMicros=").append(it)
                append(" currency=").append(event.paidCurrencyCode.orEmpty())
                append(" precision=").append(event.paidPrecisionType ?: -1)
            }
        }
        val priority = if (event.eventType == NativeAdEventType.LOAD_FAILED) Log.WARN else Log.DEBUG
        logger(priority, TAG, "${event.eventType.name}: $suffix")
    }

    companion object {
        private const val TAG = "AdMobNativeDiag"
    }
}
