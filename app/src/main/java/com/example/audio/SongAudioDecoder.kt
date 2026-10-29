package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * Extracts raw PCM audio snippets directly from the user's music files.
 * Used by the Turntable Scratch Engine and Flanger DSP to manipulate the actual
 * song audio in real-time, giving authentic vinyl scratching and flanging
 * that sounds naturally integrated with the currently playing track.
 */
class SongAudioDecoder(private val context: Context) {

    companion object {
        private const val TAG = "SongAudioDecoder"
        const val TARGET_SAMPLE_RATE = 22050
        private const val TIMEOUT_US = 8000L
        private const val MAX_SNIPPET_CACHE = 6
    }

    // Cache of extracted snippets: key = "${uri}_${offsetSec}_${durationSec}"
    private val snippetCache = mutableMapOf<String, FloatArray>()

    /**
     * Asynchronously decodes a short chunk of PCM audio from the track at the specified offset.
     */
    suspend fun extractAudioSnippet(
        contentUri: String,
        startOffsetSec: Int,
        durationSec: Double,
        fallbackSeed: Int = 120
    ): FloatArray = withContext(Dispatchers.IO) {
        val cacheKey = "${contentUri}_${startOffsetSec}_${durationSec.toInt()}"
        synchronized(snippetCache) {
            val cached = snippetCache[cacheKey]
            if (cached != null && cached.isNotEmpty()) {
                return@withContext cached
            }
        }

        val targetSamples = (TARGET_SAMPLE_RATE * durationSec).toInt().coerceIn(2000, 160000)

        if (contentUri.isBlank()) {
            val fallback = generateMusicalFallback(targetSamples, fallbackSeed)
            return@withContext fallback
        }

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            val uri = Uri.parse(contentUri)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
                pfd.close()
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            var mimeType = ""

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    mimeType = mime
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return@withContext generateMusicalFallback(targetSamples, fallbackSeed)
            }

            extractor.selectTrack(audioTrackIndex)
            val seekUs = (startOffsetSec * 1_000_000L).coerceAtLeast(0L)
            extractor.seekTo(seekUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val sourceSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
            } else 44100

            val channelCount = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 8)
            } else 2

            codec = MediaCodec.createDecoderByType(mimeType)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val pcmFloats = mutableListOf<Float>()
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false
            var safetyCounter = 0

            while (!isOutputEos && pcmFloats.size < targetSamples * (sourceSampleRate / TARGET_SAMPLE_RATE.toDouble()) && safetyCounter < 180) {
                safetyCounter++

                if (!isInputEos) {
                    val inputBufIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEos = true
                            } else {
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufIndex >= 0) {
                    val outputBuf = codec.getOutputBuffer(outputBufIndex)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuf.order(ByteOrder.LITTLE_ENDIAN)

                        val shortBuffer = outputBuf.asShortBuffer()
                        val shorts = ShortArray(shortBuffer.remaining())
                        shortBuffer.get(shorts)

                        // Convert multi-channel 16-bit PCM to mono float samples [-1.0, 1.0]
                        var sampleIdx = 0
                        while (sampleIdx < shorts.size) {
                            var sum = 0f
                            for (c in 0 until channelCount) {
                                if (sampleIdx + c < shorts.size) {
                                    sum += (shorts[sampleIdx + c] / 32768.0f)
                                }
                            }
                            pcmFloats.add((sum / channelCount).coerceIn(-1.0f, 1.0f))
                            sampleIdx += channelCount
                        }
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEos = true
                    }
                }
            }

            if (pcmFloats.size < 500) {
                return@withContext generateMusicalFallback(targetSamples, fallbackSeed)
            }

            // Resample to TARGET_SAMPLE_RATE (22050Hz) if source rate differs
            val resampled = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                val ratio = sourceSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
                val outSize = (pcmFloats.size / ratio).toInt().coerceAtMost(targetSamples)
                FloatArray(outSize) { idx ->
                    val srcPos = idx * ratio
                    val i0 = srcPos.toInt().coerceIn(0, pcmFloats.size - 1)
                    val i1 = (i0 + 1).coerceIn(0, pcmFloats.size - 1)
                    val frac = (srcPos - i0).toFloat()
                    (pcmFloats[i0] * (1f - frac) + pcmFloats[i1] * frac).coerceIn(-1.0f, 1.0f)
                }
            } else {
                FloatArray(pcmFloats.size.coerceAtMost(targetSamples)) { idx -> pcmFloats[idx] }
            }

            synchronized(snippetCache) {
                if (snippetCache.size > MAX_SNIPPET_CACHE) {
                    val oldestKey = snippetCache.keys.first()
                    snippetCache.remove(oldestKey)
                }
                snippetCache[cacheKey] = resampled
            }

            resampled
        } catch (e: Throwable) {
            Log.w(TAG, "Audio snippet decoding fallback: ${e.message}")
            generateMusicalFallback(targetSamples, fallbackSeed)
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
        }
    }

    /**
     * Generates a musically rich, harmonic vinyl sample if the media source is protected or unavailable.
     */
    private fun generateMusicalFallback(targetSamples: Int, seed: Int): FloatArray {
        val rootFreq = 110.0 + (seed % 14) * 8.0
        val pcm = FloatArray(targetSamples)
        for (i in 0 until targetSamples) {
            val t = i.toDouble() / TARGET_SAMPLE_RATE
            val f0 = sin(2.0 * Math.PI * rootFreq * t) * 0.45
            val f1 = sin(2.0 * Math.PI * rootFreq * 1.5 * t) * 0.25
            val f2 = sin(2.0 * Math.PI * rootFreq * 2.0 * t) * 0.15
            val sub = sin(2.0 * Math.PI * (rootFreq * 0.5) * t) * 0.20
            val noise = (Math.random() * 2.0 - 1.0) * 0.04
            pcm[i] = ((f0 + f1 + f2 + sub + noise) * 0.85).toFloat().coerceIn(-1.0f, 1.0f)
        }
        return pcm
    }

    fun clearCache() {
        synchronized(snippetCache) {
            snippetCache.clear()
        }
    }
}
