/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * Provider capability flags exposed to the Android UI.
 *
 * Maps to the native `FfiProviderCapabilities` record. The UI uses these
 * to adapt (e.g. disabling the vision button when the provider does not
 * support images).
 *
 * @property nativeToolCalling Whether the provider supports native tool calling via API primitives.
 * @property vision Whether the provider supports vision (image) inputs.
 */
data class ProviderCapabilities(
    val nativeToolCalling: Boolean,
    val vision: Boolean,
)
