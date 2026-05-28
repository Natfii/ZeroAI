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
 * @property contextTokens Maximum input context the model architecture
 *   supports. Distinct from any library-side cap (LiteRT-LM imposes none).
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
     * Gemma 4 E2B-it (LiteRT-LM). The only variant we currently ship.
     *
     * Other variants in the family (E4B) and the Phi-4 catalog entry
     * were trimmed because they could not be validated on hardware
     * before release: E4B's working-memory footprint exceeds what
     * Tensor G5's CPU backend handles cleanly at 32K context (the
     * GPU backend isn't viable on PowerVR until LiteRT-LM ships
     * support), and Phi-4-mini was never tested end-to-end on
     * device. They can be re-introduced here once we have a phone
     * to validate them on.
     */
    val Gemma4E2B: LiteRtModel =
        LiteRtModel(
            id = "gemma-4-e2b-it",
            displayName = "Gemma 4 E2B-it",
            variantNote = "2B params · 32K context · GPU only",
            contextTokens = 32_000,
            fileBytes = 2_590_000_000L,
            workingMemoryBytes = 700_000_000L,
            downloadUrl =
                "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
                    "resolve/main/gemma-4-E2B-it.litertlm",
            risk = LiteRtRisk.Comfortable,
        )

    /** Every variant in display order. */
    val all: List<LiteRtModel> = listOf(Gemma4E2B)

    /**
     * Looks up a variant by its [LiteRtModel.id].
     *
     * @param id Stable model identifier.
     * @return The matching variant, or `null` when [id] is unknown.
     */
    fun findById(id: String): LiteRtModel? = all.firstOrNull { it.id == id }
}
