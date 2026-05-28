/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.zeroclaw.android.model.OnDeviceStatus
import com.zeroclaw.android.model.ProcessedImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Minimum Android SDK version for AI Core (Android 12, API 31).
 */
private const val ON_DEVICE_MIN_SDK = 31

/**
 * Package name of the AI Core system app on Tensor / Pixel devices.
 *
 * No first-class ML Kit API exposes AICore presence; the documented
 * channel is to call `checkStatus()` and read a 601 `CONNECTION_ERROR`.
 * This package probe is an undocumented fallback used so onboarding can
 * draw a "Install AI Core" CTA before we ever incur the ML Kit IPC.
 */
private const val AI_CORE_PACKAGE = "com.google.android.aicore"

/**
 * Returns `true` when the AI Core system app is installed on the
 * device. Returns `false` on API < 31 (AI Core isn't supported there)
 * and on any device where the package is absent or hidden.
 *
 * Safe to call from the main thread.
 *
 * @param context Any Android context (application context preferred).
 * @return Whether AI Core is reachable via [PackageManager].
 */
fun isAiCoreInstalled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < ON_DEVICE_MIN_SDK) return false
    return try {
        context.packageManager.getPackageInfo(AI_CORE_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * Which AICore release channel an availability/download query targets.
 *
 * Mirrors ML Kit's [ModelReleaseStage] without forcing the rest of the
 * app to import the SDK constants. [Stable] is the default Gemini Nano
 * shipped to all AICore devices; [Preview] is the AICore Developer
 * Preview track, which gates access to newer variants (Gemma 4 E2B/E4B)
 * for users enrolled via the `aicore-experimental` Google Group.
 */
enum class OnDeviceReleaseTrack {
    /** Default Gemini Nano shipped to every AICore device. */
    Stable,

    /**
     * AICore Developer Preview track. Gates access to newer variants
     * such as Gemma 4 E2B/E4B for users enrolled via the
     * `aicore-experimental` Google Group.
     */
    Preview,
}

/**
 * Which model variant to bias for when AICore has multiple candidates.
 *
 * Mirrors ML Kit's [ModelPreference]. [Quality] selects the larger /
 * higher-fidelity variant (e.g. E4B on the preview track); [Speed]
 * selects the leaner variant (e.g. E2B) optimised for latency.
 */
enum class OnDevicePreference {
    /** Larger / higher-fidelity variant (e.g. E4B on the preview track). */
    Quality,

    /** Leaner variant (e.g. E2B) optimised for latency. */
    Speed,
}

/**
 * Translates the public [OnDeviceReleaseTrack] enum to the ML Kit
 * `ModelReleaseStage` Int constant required by [ModelConfig.Builder].
 */
private fun OnDeviceReleaseTrack.toMlKitStage(): Int =
    when (this) {
        OnDeviceReleaseTrack.Stable -> ModelReleaseStage.STABLE
        OnDeviceReleaseTrack.Preview -> ModelReleaseStage.PREVIEW
    }

/**
 * Translates the public [OnDevicePreference] enum to the ML Kit
 * `ModelPreference` Int constant required by [ModelConfig.Builder].
 */
private fun OnDevicePreference.toMlKitPreference(): Int =
    when (this) {
        OnDevicePreference.Quality -> ModelPreference.FULL
        OnDevicePreference.Speed -> ModelPreference.FAST
    }

/**
 * Builds a [GenerationConfig] carrying the requested model selection,
 * ready to hand to [Generation.getClient].
 */
private fun buildGenerationConfig(
    track: OnDeviceReleaseTrack,
    preference: OnDevicePreference,
): GenerationConfig {
    val modelConfig =
        ModelConfig
            .Builder()
            .apply {
                releaseStage = track.toMlKitStage()
                this.preference = preference.toMlKitPreference()
            }.build()
    return GenerationConfig
        .Builder()
        .apply { this.modelConfig = modelConfig }
        .build()
}

/**
 * Joined result of a single availability probe: the high-level status,
 * plus the concrete variant identity if the model is [OnDeviceStatus.Available].
 *
 * Returned by [resolveOnDeviceModel] so the caller can render both
 * "available" and "running Gemma 4 E4B (128K tokens)" with one IPC
 * round-trip instead of two.
 *
 * @property status Current [OnDeviceStatus] of the requested track.
 * @property modelName Concrete variant ML Kit handed back (e.g.
 *   `"gemma-nano-v3"`, `"gemma-4-e4b"`). Empty when the SDK refuses
 *   to answer or the model isn't ready.
 * @property tokenLimit Maximum input tokens the model accepts, or
 *   `-1` when unknown or the model isn't ready.
 */
data class OnDeviceResolution(
    val status: OnDeviceStatus,
    val modelName: String,
    val tokenLimit: Int,
)

/**
 * Resolves the current on-device AI availability + variant identity
 * for the requested track in a single ML Kit round-trip.
 *
 * Opens one [com.google.mlkit.genai.prompt.GenerativeModel] client,
 * calls `checkStatus()`, and — only when the model is `AVAILABLE` —
 * also queries `getBaseModelName()` and `getTokenLimit()` before
 * closing. Avoids the previous two-call pattern that opened a second
 * client just to introspect the name + limit.
 *
 * Safe to call from the main thread; the underlying IPC runs on
 * [dispatcher].
 *
 * @param track AICore release channel to query. Defaults to
 *   [OnDeviceReleaseTrack.Stable]; pass [OnDeviceReleaseTrack.Preview]
 *   to ask about Developer Preview variants such as Gemma 4 E4B.
 * @param preference Which variant to bias for when AICore has multiple
 *   candidates on the requested track.
 * @param dispatcher Dispatcher used for the ML Kit call.
 * @return Joined [OnDeviceResolution]. The [OnDeviceResolution.status]
 *   field is always populated; the variant identity fields are only
 *   populated when the model is `AVAILABLE`.
 */
@Suppress("TooGenericExceptionCaught")
suspend fun resolveOnDeviceModel(
    track: OnDeviceReleaseTrack = OnDeviceReleaseTrack.Stable,
    preference: OnDevicePreference = OnDevicePreference.Quality,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): OnDeviceResolution {
    if (Build.VERSION.SDK_INT < ON_DEVICE_MIN_SDK) {
        return OnDeviceResolution(OnDeviceStatus.NotSupported, "", -1)
    }
    return withContext(dispatcher) {
        try {
            val client = Generation.getClient(buildGenerationConfig(track, preference))
            try {
                val status = mapFeatureStatus(client.checkStatus())
                if (status is OnDeviceStatus.Available) {
                    OnDeviceResolution(
                        status = status,
                        modelName = client.getBaseModelName(),
                        tokenLimit = client.getTokenLimit(),
                    )
                } else {
                    OnDeviceResolution(status, "", -1)
                }
            } finally {
                client.close()
            }
        } catch (e: GenAiException) {
            OnDeviceResolution(
                OnDeviceStatus.Unavailable(e.message ?: "On-device AI is unavailable."),
                "",
                -1,
            )
        } catch (e: Exception) {
            OnDeviceResolution(
                OnDeviceStatus.Unavailable(e.message ?: "Unknown error"),
                "",
                -1,
            )
        }
    }
}

/**
 * Convenience wrapper around [resolveOnDeviceModel] for callers that
 * only need the status. Kept as a thin one-liner so existing call
 * sites don't have to destructure the resolution result.
 *
 * @param track AICore release channel to query.
 * @param preference Variant preference.
 * @param dispatcher Dispatcher for the ML Kit call.
 * @return Current [OnDeviceStatus] for the requested track.
 */
suspend fun checkOnDeviceStatus(
    track: OnDeviceReleaseTrack = OnDeviceReleaseTrack.Stable,
    preference: OnDevicePreference = OnDevicePreference.Quality,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): OnDeviceStatus = resolveOnDeviceModel(track, preference, dispatcher).status

/**
 * Cold flow that drives the AICore model download for the requested
 * track. Emits [OnDeviceStatus.Downloading] updates as bytes arrive,
 * then a final [OnDeviceStatus.Available] on success or
 * [OnDeviceStatus.Unavailable] with a reason on failure.
 *
 * Guarantees at least one terminal state (`Available` /
 * `Unavailable` / `NotSupported`) before completion, so callers can
 * safely treat the last emitted status as authoritative without
 * carrying a sentinel "no terminal seen" placeholder.
 *
 * Closes the underlying ML Kit client when the consumer cancels the
 * collection, so it's safe to back this with a ViewModel scope that
 * may be cleared mid-download.
 *
 * @param track AICore release channel to download from.
 * @param preference Variant preference.
 * @param dispatcher Dispatcher for the ML Kit collector.
 * @return Cold [Flow] of [OnDeviceStatus] events terminating in
 *   [OnDeviceStatus.Available] or [OnDeviceStatus.Unavailable].
 */
@Suppress("TooGenericExceptionCaught")
fun downloadOnDeviceModel(
    track: OnDeviceReleaseTrack = OnDeviceReleaseTrack.Stable,
    preference: OnDevicePreference = OnDevicePreference.Quality,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): Flow<OnDeviceStatus> =
    flow {
        if (Build.VERSION.SDK_INT < ON_DEVICE_MIN_SDK) {
            emit(OnDeviceStatus.NotSupported)
            return@flow
        }
        val client: GenerativeModel =
            try {
                Generation.getClient(buildGenerationConfig(track, preference))
            } catch (e: Exception) {
                emit(OnDeviceStatus.Unavailable(e.message ?: "Failed to initialize model"))
                return@flow
            }
        var sawTerminal = false
        try {
            var totalBytes = -1L
            client.download().collect { status ->
                when (status) {
                    is DownloadStatus.DownloadStarted -> {
                        totalBytes = status.bytesToDownload
                        emit(OnDeviceStatus.Downloading(0L, totalBytes))
                    }
                    is DownloadStatus.DownloadProgress ->
                        emit(
                            OnDeviceStatus.Downloading(
                                status.totalBytesDownloaded,
                                totalBytes,
                            ),
                        )
                    DownloadStatus.DownloadCompleted -> {
                        sawTerminal = true
                        emit(OnDeviceStatus.Available)
                    }
                    is DownloadStatus.DownloadFailed -> {
                        sawTerminal = true
                        emit(
                            OnDeviceStatus.Unavailable(
                                status.e.message ?: "Download failed",
                            ),
                        )
                    }
                }
            }
            if (!sawTerminal) {
                emit(
                    OnDeviceStatus.Unavailable(
                        "AI Core ended the download without reporting success or failure.",
                    ),
                )
            }
        } finally {
            client.close()
        }
    }.catch { e ->
        emit(OnDeviceStatus.Unavailable(e.message ?: "Download failed"))
    }.flowOn(dispatcher)

/**
 * Awaits a Nano model becoming ready, kicking off the AICore download
 * if needed. Mirrors the pattern the Terminal screen needs: a single
 * "is Nano usable right now?" gate that, on `DOWNLOADABLE` /
 * `DOWNLOADING`, transparently drives the download and only returns
 * once a terminal state is reached.
 *
 * Side effects are surfaced through the [onSystemMessage] and
 * [onError] callbacks so the caller decides how to render them
 * (scrollback entry, snackbar, log line, …). Byte-level progress goes
 * to [onProgress] instead of [onSystemMessage] to keep the UI quiet
 * for users who don't want a deluge of progress lines.
 *
 * @param status Already-known current status of the model. Avoids the
 *   helper running its own probe when the caller just queried.
 * @param track Release track to download from.
 * @param preference Variant preference.
 * @param onSystemMessage Invoked once with a "fetching…" notice when a
 *   download starts, and once with a "ready" notice when it succeeds.
 * @param onError Invoked once with a user-facing error message when
 *   the model can't be made available.
 * @param onProgress Invoked on every progress emission. Default no-op.
 * @return `true` when the model is ready to use, `false` after an
 *   [onError] message has been issued.
 */
@Suppress("LongParameterList")
suspend fun ensureNanoReady(
    status: OnDeviceStatus,
    track: OnDeviceReleaseTrack = OnDeviceReleaseTrack.Stable,
    preference: OnDevicePreference = OnDevicePreference.Quality,
    onSystemMessage: suspend (String) -> Unit,
    onError: suspend (String) -> Unit,
    onProgress: suspend (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
): Boolean {
    when (status) {
        OnDeviceStatus.Available -> return true
        OnDeviceStatus.NotSupported -> {
            onError(
                "On-device Gemini Nano is not supported on this device. " +
                    "Start the daemon to chat with your configured cloud provider, " +
                    "or use a Pixel 8/9/10-class device with AI Core.",
            )
            return false
        }
        is OnDeviceStatus.Unavailable -> {
            onError("On-device Nano unavailable: ${status.reason}")
            return false
        }
        OnDeviceStatus.Downloadable, is OnDeviceStatus.Downloading -> Unit
    }
    onSystemMessage("Fetching Nano model — this only happens once.")
    var terminal: OnDeviceStatus =
        OnDeviceStatus.Unavailable("AI Core ended the download without a terminal state.")
    downloadOnDeviceModel(track = track, preference = preference).collect { event ->
        when (event) {
            is OnDeviceStatus.Downloading ->
                onProgress(event.bytesDownloaded, event.totalBytes)
            OnDeviceStatus.Available,
            is OnDeviceStatus.Unavailable,
            OnDeviceStatus.NotSupported,
            -> terminal = event
            OnDeviceStatus.Downloadable -> Unit
        }
    }
    when (val t = terminal) {
        OnDeviceStatus.Available -> {
            onSystemMessage("Nano model ready.")
            return true
        }
        is OnDeviceStatus.Unavailable -> onError("Nano download didn't finish: ${t.reason}")
        OnDeviceStatus.NotSupported ->
            onError("On-device Gemini Nano is not supported on this device.")
        OnDeviceStatus.Downloadable, is OnDeviceStatus.Downloading ->
            onError("Nano download didn't finish.")
    }
    return false
}

/**
 * Maps the ML Kit `FeatureStatus` Int constant onto the project's
 * sealed [OnDeviceStatus]. Centralised so every entry point reports
 * the same shape and the `else` branch is impossible to forget.
 */
private fun mapFeatureStatus(status: Int): OnDeviceStatus =
    when (status) {
        FeatureStatus.AVAILABLE -> OnDeviceStatus.Available
        FeatureStatus.DOWNLOADABLE -> OnDeviceStatus.Downloadable
        FeatureStatus.DOWNLOADING -> OnDeviceStatus.Downloading(-1L)
        else ->
            OnDeviceStatus.Unavailable(
                "On-device AI is not available on this device.",
            )
    }

/**
 * Dispatch decision for a message that includes an image attachment.
 *
 * Pure data: produced by [decideImageDispatch] from the current cloud
 * capabilities + the on-device Nano caption, consumed by whoever is
 * driving the chat turn. Keeping the four outcomes as discrete variants
 * stops the routing logic from leaking into the caller as ad-hoc `if`
 * chains, and lets the decision be unit-tested in isolation.
 */
sealed interface ImageDispatch {
    /**
     * Cloud model is vision-capable. Forward the original image and prompt.
     *
     * @property prompt User's text accompanying the image.
     * @property image The processed image, with base64 + MIME ready for FFI.
     */
    data class VisionCloud(
        val prompt: String,
        val image: ProcessedImage,
    ) : ImageDispatch

    /**
     * Cloud model is text-only but configured. Forward the prompt with the
     * Nano caption spliced in as context (caption may be blank when Nano
     * itself failed; the prompt still goes through with an explicit
     * "description unavailable" marker so the model can respond honestly).
     *
     * @property prompt User's text accompanying the image.
     * @property caption Nano-generated description, possibly blank.
     */
    data class TextCloud(
        val prompt: String,
        val caption: String,
    ) : ImageDispatch

    /**
     * No cloud is reachable, but the Nano caption was non-blank and has
     * already been shown to the user. Nothing more to do.
     */
    data object CaptionOnly : ImageDispatch

    /**
     * No cloud is reachable AND Nano produced no caption. The caller should
     * surface a clear error so the user knows the message dead-ended.
     */
    data object NoDispatch : ImageDispatch
}

/**
 * Picks the right dispatch shape for an image-bearing message.
 *
 * Pure function of inputs — no side effects, safe to unit-test. The
 * priority is: prefer a vision-capable cloud, then fall back to the
 * text-only cloud carrying a Nano caption, then to a caption-only
 * scrollback entry, then to a surfaced error.
 *
 * @param prompt User's text accompanying the image.
 * @param caption Nano-generated caption (may be blank if Nano failed).
 * @param cloudSupportsVision Whether the active cloud model accepts images.
 * @param cloudConfigured Whether any cloud provider is configured at all.
 * @param image The processed image, passed through to [ImageDispatch.VisionCloud].
 * @return Selected [ImageDispatch] variant.
 */
fun decideImageDispatch(
    prompt: String,
    caption: String,
    cloudSupportsVision: Boolean,
    cloudConfigured: Boolean,
    image: ProcessedImage,
): ImageDispatch =
    when {
        cloudSupportsVision && cloudConfigured ->
            ImageDispatch.VisionCloud(prompt, image)
        cloudConfigured ->
            ImageDispatch.TextCloud(prompt, caption)
        caption.isNotBlank() ->
            ImageDispatch.CaptionOnly
        else ->
            ImageDispatch.NoDispatch
    }

/**
 * Marker rendered when Nano could not describe an attached image but the
 * prompt should still reach the cloud.
 */
private const val NO_CAPTION_MARKER = "(description unavailable)"

/** Truncation length for caption preview text written to logcat. */
private const val CAPTION_LOG_PREVIEW_CHARS = 200

/**
 * Composes a text-only prompt that carries the Nano caption back to a
 * non-multimodal cloud model.
 *
 * Uses XML-style framing because frontier chat models reliably read
 * `<image>…</image>` as ground-truth context rather than as user-typed
 * text — bracketed inline prefixes (`[Image: …]`) get echoed back as if
 * the user said them, which defeats the point of the fallback.
 *
 * @param prompt Original user prompt; may be blank.
 * @param caption Nano-generated description; may be blank.
 * @return Combined prompt string ready for the agent loop.
 */
fun captionedPrompt(
    prompt: String,
    caption: String,
): String {
    val descriptionBlock = "<image>\n${caption.ifBlank { NO_CAPTION_MARKER }}\n</image>"
    return if (prompt.isBlank()) descriptionBlock else "$descriptionBlock\n\n$prompt"
}

/**
 * Coordinates on-device image description for the Terminal fallback flow.
 *
 * Owns no UI state; takes a scrollback append callback so the caller
 * decides how to render. Keeping this class small and dependency-light
 * lets the Terminal ViewModel shrink and gives the fallback path a real
 * seam for tests.
 *
 * @param describer On-device image describer bridge.
 * @param appendCaption Side-effecting callback that writes a finished
 *   caption to the user-visible scrollback. Invoked with the trimmed,
 *   non-blank caption only.
 * @param warnFailure Callback for non-fatal Nano errors (typically a
 *   log sink). Invoked with the [Throwable.message].
 */
class NanoFallback(
    private val describer: OnDeviceImageDescriberBridge,
    private val appendCaption: suspend (caption: String) -> Unit,
    private val warnFailure: suspend (message: String?) -> Unit,
) {
    /**
     * Captions [image] via the on-device describer and returns the
     * caption text.
     *
     * Behaviour change (2026-05-28): this method NO LONGER writes
     * the caption to the visible chat scrollback. Earlier it did,
     * which produced a confusing double-print when the caption was
     * also forwarded to an agent — the user saw Nano's truncated
     * "A small, brown and tan dog with large" first, then the
     * agent's response (which often just echoes the same caption
     * back when the agent is text-only and has no other signal).
     *
     * The caller decides whether to surface the caption via
     * [showCaptionInScrollback], typically only for the
     * `CaptionOnly` dispatch path where no agent reply will appear.
     *
     * @param image Processed image with base64 data ready for decoding.
     * @return Trimmed caption text; empty when the bitmap could not
     *   be decoded or Nano returned no chunks.
     */
    suspend fun describeForScrollback(image: ProcessedImage): String {
        val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
        val builder = StringBuilder()
        var chunkCount = 0
        describer
            .describe(bitmap)
            .catch { e -> warnFailure(e.message) }
            .collect { chunk ->
                chunkCount += 1
                if (com.zeroclaw.android.BuildConfig.DEBUG) {
                    // Per-chunk caption text may describe sensitive
                    // image content. Debug-only.
                    android.util.Log.d(
                        "NanoFallback",
                        "Caption chunk #$chunkCount (${chunk.length} chars): " +
                            chunk.take(CAPTION_LOG_PREVIEW_CHARS).replace('\n', '·'),
                    )
                }
                builder.append(chunk)
            }
        val caption = builder.toString().trim()
        if (com.zeroclaw.android.BuildConfig.DEBUG) {
            android.util.Log.d(
                "NanoFallback",
                "Caption assembled: $chunkCount chunk(s), ${caption.length} chars total — " +
                    "preview: ${caption.take(CAPTION_LOG_PREVIEW_CHARS).replace('\n', '·')}",
            )
        }
        return caption
    }

    /**
     * Explicitly writes [caption] to the visible chat scrollback via
     * the injected `appendCaption` callback. No-op for blank input.
     *
     * Use this only when the dispatch decision is `CaptionOnly`
     * (no downstream agent will surface a reply). For agent-bound
     * paths, the caption is forwarded inside the prompt and the
     * agent's own response is what the user should see.
     */
    suspend fun showCaptionInScrollback(caption: String) {
        if (caption.isBlank()) return
        appendCaption(caption)
    }
}
