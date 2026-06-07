/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.model

/**
 * One of the on-device LLM variants ZeroAI can run via LiteRT-LM.
 *
 * The picker on the Agents-tab on-device screen surfaces these by id;
 * the downloader fetches [downloadUrl] into `filesDir/models/<id>.litertlm`,
 * and the daemon-lifecycle wiring loads the file with the right backend
 * when the daemon starts.
 *
 * Sizes and memory numbers come from the official `litert-community`
 * Hugging Face model cards (verified May 2026); they're shown to the
 * user verbatim in the picker so they can pick within their device's
 * RAM budget.
 *
 * @property id Stable identifier used in storage paths and selection state.
 * @property displayName Human-readable name shown in the picker.
 * @property variantNote Short tagline describing the trade-off
 *   ("speed" / "quality" / "long context").
 * @property contextTokens Token budget handed to the engine as
 *   `maxNumTokens`; it bounds the KV-cache allocation and therefore
 *   the GPU working-memory peak. Usually the model's native context
 *   window, but may be capped below it to keep a heavier variant
 *   within the device's memory budget.
 * @property fileBytes Size of the `.litertlm` file the downloader fetches.
 * @property workingMemoryBytes Steady-state GPU working memory required
 *   during inference, per the Hugging Face model card.
 * @property downloadUrl Direct HTTPS download URL on Hugging Face. The
 *   `litert-community` repos are un-gated as of May 2026 — no login needed.
 * @property risk Severity badge for the RAM/power warning UI.
 */
data class LiteRtModel(
    val id: String,
    val displayName: String,
    val variantNote: String,
    val contextTokens: Int,
    val fileBytes: Long,
    val workingMemoryBytes: Long,
    val downloadUrl: String,
    val risk: LiteRtRisk,
)

/**
 * Risk tier for an on-device LLM variant.
 *
 * Drives the colour of the model card chip in the picker so users can
 * see at a glance whether the variant is comfortable on their device.
 */
enum class LiteRtRisk {
    /** Comfortable RAM budget on a 16 GB device; safe default. */
    Comfortable,

    /** Larger weights but still fits 16 GB devices with the daemon running. */
    Moderate,

    /** Peak working memory approaches device RAM ceiling; warn hard. */
    Heavy,
}

/**
 * Static catalog of LiteRT-LM variants the picker exposes.
 *
 * Order matters: shown top-to-bottom in the picker as
 * lightest-to-heaviest, so users land on the comfortable choice first.
 * Adding a new variant means appending here, updating the downloader
 * + inference loader, and bumping the picker UI tests.
 */
object LiteRtModelCatalog {
    /**
     * Gemma 4 E2B-it (LiteRT-LM), the lightweight default variant.
     *
     * The shipped `.litertlm` is already the mobile QAT build (mixed
     * 2/4/8-bit weights baked in), so it runs comfortably on the GPU
     * (OpenCL) backend. On Tensor G5 LiteRT-LM currently mis-detects
     * the GPU as PowerVR and logs that GPU weight-prep is disabled,
     * but it still falls through to the OpenCL path and runs
     * (google-ai-edge/LiteRT-LM#1681).
     *
     * The per-SoC NPU build in the same Hugging Face repo
     * (`..._Google_Tensor_G5.litertlm`) is deliberately NOT used: its
     * dispatch shim needs the `Darwinn` TPU symbols, which exist only
     * in the gated, non-redistributable Tensor ML SDK build of
     * `libLiteRt.so` — verified absent from the public
     * `litertlm-android` AAR in 0.11, 0.12 and 0.13.1.
     */
    val Gemma4E2B: LiteRtModel =
        LiteRtModel(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B-it (QAT)",
            variantNote = "2B params · 32K context · mixed 2/4/8-bit QAT",
            contextTokens = 32_000,
            fileBytes = 2_588_147_712L,
            workingMemoryBytes = 1_200_000_000L,
            downloadUrl =
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
                    "resolve/main/gemma-4-E2B-it.litertlm",
            risk = LiteRtRisk.Comfortable,
        )

    /**
     * Gemma 4 E4B-it (LiteRT-LM), the higher-quality heavyweight
     * variant. Same mobile QAT packaging as [Gemma4E2B] but ~4B
     * effective params, so it carries a larger weight + KV-cache
     * footprint and is gated harder by [OnDeviceRamGate].
     *
     * Runs on the GPU backend like E2B. The [contextTokens] budget is
     * capped below the architecture's native 32K on purpose: the KV
     * cache scales with the token budget, and trimming it keeps the
     * GPU working-memory peak comfortable on Tensor G5. 24K is the
     * starting point now that the agent's baseline context overhead
     * has been reduced — raise or lower it from real device traces.
     */
    val Gemma4E4B: LiteRtModel =
        LiteRtModel(
            id = "gemma-4-e4b-it",
            displayName = "Gemma 4 E4B-it",
            variantNote = "4B params · 24K context · higher quality",
            contextTokens = 24_000,
            fileBytes = 3_659_530_240L,
            workingMemoryBytes = 1_800_000_000L,
            downloadUrl =
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/" +
                    "resolve/main/gemma-4-E4B-it.litertlm",
            risk = LiteRtRisk.Heavy,
        )

    /** Every variant in display order (lightest first). */
    val all: List<LiteRtModel> = listOf(Gemma4E2B, Gemma4E4B)

    /**
     * Looks up a variant by its [LiteRtModel.id].
     *
     * @param id Stable model identifier.
     * @return The matching variant, or `null` when [id] is unknown.
     */
    fun findById(id: String): LiteRtModel? = all.firstOrNull { it.id == id }
}
