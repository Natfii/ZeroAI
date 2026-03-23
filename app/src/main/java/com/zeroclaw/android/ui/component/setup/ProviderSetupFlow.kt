/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component.setup

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.SlotCredentialType
import com.zeroclaw.android.data.validation.ValidationResult
import com.zeroclaw.android.model.DiscoveredServer
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.ui.component.ModelSuggestionField
import com.zeroclaw.android.ui.component.ProviderCredentialForm
import com.zeroclaw.android.ui.theme.ZeroAITheme
import com.zeroclaw.android.util.DeepLinkTarget
import com.zeroclaw.android.util.ExternalAppLauncher

/** Standard spacing between form fields. */
private val FieldSpacing = 16.dp

/** Spacing after the title. */
private val TitleSpacing = 8.dp

/** Spacing between the deep-link button and the validate button in the action row. */
private val ActionRowSpacing = 8.dp

/** Spacing before the skip hint text. */
private val HintSpacing = 8.dp

/** Spacing between the validate button icon and label. */
private val ButtonIconSpacing = 4.dp

/** Size of the circular progress indicator inside the OAuth login button. */
private val OAuthProgressSize = 18.dp

/** Size of the provider logo icon in the OAuth login button. */
private val OAuthLogoSize = 20.dp

/** Pixel size for the provider logo Coil request (20dp at 4x density). */
private const val OAUTH_LOGO_PX = 80

/** Google Favicon API URL for the ChatGPT logo. */
private const val CHATGPT_FAVICON_URL =
    "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON" +
        "&fallback_opts=TYPE,SIZE,URL&url=https://chatgpt.com&size=128"

/** Google Favicon API URL for the Anthropic logo. */
private const val ANTHROPIC_FAVICON_URL =
    "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON" +
        "&fallback_opts=TYPE,SIZE,URL&url=https://anthropic.com&size=128"

/** Google Favicon API URL for the Google logo used by the Google account login button. */
private const val GOOGLE_FAVICON_URL =
    "https://t3.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON" +
        "&fallback_opts=TYPE,SIZE,URL&url=https://google.com&size=128"

/** Risk disclaimer shown when the Anthropic OAuth path is available. */
private const val ANTHROPIC_OAUTH_RISK =
    "Anthropic may restrict OAuth tokens obtained outside " +
        "Claude Code. Your session could be revoked without notice."

/** Stroke width for the OAuth progress indicator. */
private val OAuthProgressStroke = 2.dp

/** Set of provider IDs that identify any Qwen regional variant. */
private val QWEN_PROVIDER_IDS = setOf("qwen", "qwen-cn", "qwen-us")

/**
 * DashScope regional endpoint selection for Qwen.
 *
 * Each entry maps to a distinct provider ID written to the daemon TOML config.
 * The [disclosure] field is shown as a small warning when the user picks a
 * region with notable data-routing implications.
 *
 * @property providerId Provider ID written to the daemon TOML config.
 * @property displayName Human-readable region label.
 * @property disclosure Optional data-residency disclosure shown below the label.
 */
enum class QwenRegion(
    val providerId: String,
    val displayName: String,
    val disclosure: String = "",
) {
    /** International DashScope endpoint (default). */
    INTERNATIONAL("qwen", "International"),

    /** China mainland DashScope endpoint. */
    CHINA("qwen-cn", "China", "Traffic and credentials route through Alibaba Cloud (China)."),

    /** US-region DashScope endpoint. */
    US("qwen-us", "US"),
}

/** Internal padding for the OAuth connected chip. */
private val ChipPadding = 12.dp

/** Size of the check icon inside the OAuth connected chip. */
private val ChipIconSize = 20.dp

/** Spacing between the icon and text columns in the OAuth connected chip. */
private val ChipIconTextSpacing = 8.dp

/**
 * Reusable provider setup form combining credential entry, validation, and model selection.
 *
 * Composes a scrollable vertical layout containing:
 * 1. Provider dropdown via [ProviderCredentialForm]
 * 2. An action row with a [DeepLinkButton] to the provider's API-key console
 *    (when available) and a "Validate" [FilledTonalButton]
 * 3. A [ValidationIndicator] showing the current validation state
 * 4. A [ModelSuggestionField] for selecting a model (when a provider is chosen)
 * 5. An optional skip hint (only during onboarding)
 *
 * This composable is intentionally state-hoisted: all form values and callbacks
 * are provided by the parent. The "Validate" button invokes [onValidate] without
 * managing validation state internally.
 *
 * Used by
 * [ProviderStep][com.zeroclaw.android.ui.screen.onboarding.steps.ProviderStep]
 * during onboarding and by settings screens for provider re-configuration.
 *
 * @param selectedProvider Currently selected provider ID, or empty string.
 * @param apiKey Current API key input value.
 * @param baseUrl Current base URL input value.
 * @param selectedModel Current model name input value.
 * @param availableModels Live model names fetched from the provider API.
 * @param validationResult Current state of the validation operation.
 * @param onProviderChanged Callback when provider selection changes (receives provider ID).
 * @param onApiKeyChanged Callback when API key text changes.
 * @param onBaseUrlChanged Callback when base URL text changes.
 * @param onModelChanged Callback when model text changes.
 * @param onValidate Callback to trigger credential validation.
 * @param showSkipHint Whether to display a "skip this step" hint at the bottom.
 * @param modifier Modifier applied to the root scrollable [Column].
 * @param isLoadingModels Whether live model data is currently being fetched.
 * @param isLiveModelData Whether [availableModels] represents real-time data.
 * @param onServerSelected Optional callback invoked when a server is picked from
 *   the network scan sheet for local providers.
 * @param isOAuthInProgress Whether an OAuth login flow is currently running.
 * @param oauthEmail Display email or label for the connected OAuth session,
 *   or empty string when not connected.
 * @param onOAuthLogin Optional callback to initiate the OAuth login flow.
 *   When non-null and the provider supports [ProviderAuthType.API_KEY_OR_OAUTH],
 *   a provider-branded OAuth button is rendered (e.g. "Login with ChatGPT"
 *   for OpenAI, "Login with Claude" for Anthropic).
 * @param onOAuthDisconnect Optional callback to disconnect the current OAuth session.
 * @param scrollable Whether the root [Column] applies its own vertical scroll. Set to
 *   false when embedding inside an already-scrollable parent to avoid nested scrolling.
 * @param showProviderPicker Whether to show the provider dropdown selector.
 * @param credentialTypeOverride Optional fixed credential mode used by provider-slot flows.
 * @param oauthButtonLabelOverride Optional branded override for the OAuth button label.
 * @param showModelPicker Whether to render the model picker field.
 */
@Composable
fun ProviderSetupFlow(
    selectedProvider: String,
    apiKey: String,
    baseUrl: String,
    selectedModel: String,
    availableModels: List<String> = emptyList(),
    validationResult: ValidationResult = ValidationResult.Idle,
    onProviderChanged: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onBaseUrlChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onValidate: () -> Unit = {},
    showSkipHint: Boolean = false,
    modifier: Modifier = Modifier,
    isLoadingModels: Boolean = false,
    isLiveModelData: Boolean = false,
    onServerSelected: ((DiscoveredServer) -> Unit)? = null,
    isOAuthInProgress: Boolean = false,
    oauthEmail: String = "",
    onOAuthLogin: (() -> Unit)? = null,
    onOAuthDisconnect: (() -> Unit)? = null,
    scrollable: Boolean = true,
    showProviderPicker: Boolean = true,
    credentialTypeOverride: SlotCredentialType? = null,
    oauthButtonLabelOverride: String? = null,
    showModelPicker: Boolean = true,
) {
    val isQwenProvider = selectedProvider in QWEN_PROVIDER_IDS
    val selectedQwenRegion =
        remember(selectedProvider) {
            QwenRegion.entries.firstOrNull { it.providerId == selectedProvider }
                ?: QwenRegion.INTERNATIONAL
        }
    val effectiveProvider = if (isQwenProvider) selectedQwenRegion.providerId else selectedProvider
    val providerInfo = ProviderRegistry.findById(effectiveProvider)
    val suggestedModels = providerInfo?.suggestedModels.orEmpty()
    val consoleTarget = ExternalAppLauncher.providerConsoleTarget(effectiveProvider)
    val isOAuthConnected = oauthEmail.isNotEmpty()
    val validateEnabled =
        effectiveProvider.isNotBlank() &&
            (apiKey.isNotBlank() || baseUrl.isNotBlank())

    val columnModifier =
        if (scrollable) {
            modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
        } else {
            modifier
        }
    val showOAuthSection =
        when (credentialTypeOverride) {
            SlotCredentialType.OAUTH -> onOAuthLogin != null
            SlotCredentialType.API_KEY,
            SlotCredentialType.URL_KEY,
            -> false
            null ->
                providerInfo?.authType == ProviderAuthType.API_KEY_OR_OAUTH &&
                    onOAuthLogin != null
        }

    Column(
        modifier = columnModifier,
    ) {
        ProviderCredentialForm(
            selectedProviderId = effectiveProvider,
            apiKey = apiKey,
            baseUrl = baseUrl,
            onProviderChanged = onProviderChanged,
            onApiKeyChanged = onApiKeyChanged,
            onBaseUrlChanged = onBaseUrlChanged,
            onServerSelected = onServerSelected,
            oauthConnected = isOAuthConnected,
            showProviderDropdown = showProviderPicker,
            credentialTypeOverride = credentialTypeOverride,
            modifier = Modifier.fillMaxWidth(),
        )

        if (isQwenProvider) {
            Spacer(modifier = Modifier.height(FieldSpacing))
            QwenRegionPicker(
                selectedRegion = selectedQwenRegion,
                onRegionSelected = { region -> onProviderChanged(region.providerId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showOAuthSection) {
            val isAnthropic = selectedProvider == "anthropic"
            val isGemini =
                selectedProvider == "google-gemini" ||
                    selectedProvider == "gemini" ||
                    selectedProvider == "google"
            val oauthLogin = onOAuthLogin ?: {}
            val oauthLabel =
                oauthButtonLabelOverride ?: when {
                    isAnthropic -> "Login with Claude"
                    isGemini -> "Login with Google"
                    else -> "Login with ChatGPT"
                }
            val oauthFaviconUrl =
                when {
                    isAnthropic -> ANTHROPIC_FAVICON_URL
                    isGemini -> GOOGLE_FAVICON_URL
                    else -> CHATGPT_FAVICON_URL
                }

            Spacer(modifier = Modifier.height(FieldSpacing))

            if (oauthEmail.isNotEmpty()) {
                OAuthConnectedChip(
                    email = oauthEmail,
                    onDisconnect = onOAuthDisconnect ?: {},
                    providerLabel = oauthLabel,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isAnthropic) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ANTHROPIC_OAUTH_RISK,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                Button(
                    onClick = oauthLogin,
                    enabled = !isOAuthInProgress,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = oauthLabel
                            },
                ) {
                    if (isOAuthInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(OAuthProgressSize),
                            strokeWidth = OAuthProgressStroke,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(modifier = Modifier.width(ButtonIconSpacing))
                    } else {
                        val context = LocalContext.current
                        val logoRequest =
                            remember(oauthFaviconUrl) {
                                ImageRequest
                                    .Builder(context)
                                    .data(oauthFaviconUrl)
                                    .size(OAUTH_LOGO_PX, OAUTH_LOGO_PX)
                                    .build()
                            }
                        AsyncImage(
                            model = logoRequest,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier
                                    .size(OAuthLogoSize)
                                    .clip(CircleShape),
                        )
                        Spacer(modifier = Modifier.width(ButtonIconSpacing))
                    }
                    Text(
                        text =
                            if (isOAuthInProgress) {
                                "Logging in\u2026"
                            } else {
                                oauthLabel
                            },
                    )
                }

                if (isAnthropic) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ANTHROPIC_OAUTH_RISK,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.padding(horizontal = 4.dp),
                    )
                }

                if (credentialTypeOverride == null) {
                    Spacer(modifier = Modifier.height(FieldSpacing))
                    OAuthDividerRow(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        if (credentialTypeOverride != SlotCredentialType.OAUTH) {
            Spacer(modifier = Modifier.height(FieldSpacing))
        }

        if (!isOAuthConnected && credentialTypeOverride != SlotCredentialType.OAUTH) {
            ActionRow(
                consoleTarget = consoleTarget,
                validateEnabled = validateEnabled,
                validationResult = validationResult,
                onValidate = onValidate,
            )

            Spacer(modifier = Modifier.height(TitleSpacing))
        }

        ValidationIndicator(
            result = validationResult,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(FieldSpacing))

        if (showModelPicker && effectiveProvider.isNotBlank()) {
            ModelSuggestionField(
                value = selectedModel,
                onValueChanged = onModelChanged,
                suggestions = suggestedModels,
                liveSuggestions = availableModels,
                isLoadingLive = isLoadingModels,
                isLiveData = isLiveModelData,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (showSkipHint) {
            Spacer(modifier = Modifier.height(HintSpacing))
            Text(
                text = "You can add keys later in Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Horizontal row containing the optional deep-link button and the validate button.
 *
 * The deep-link button is only rendered when [consoleTarget] is non-null. Both
 * buttons meet the 48x48dp minimum touch target through their default Material 3
 * sizing.
 *
 * @param consoleTarget Optional deep-link target for the provider's API-key console.
 * @param validateEnabled Whether the validate button is interactive.
 * @param validationResult Current validation state, used to disable during loading.
 * @param onValidate Callback invoked when the validate button is clicked.
 */
@Composable
private fun ActionRow(
    consoleTarget: DeepLinkTarget?,
    validateEnabled: Boolean,
    validationResult: ValidationResult,
    onValidate: () -> Unit,
) {
    val isLoading = validationResult is ValidationResult.Loading

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (consoleTarget != null) {
            DeepLinkButton(
                target = consoleTarget,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(ActionRowSpacing))
        }

        FilledTonalButton(
            onClick = onValidate,
            enabled = validateEnabled && !isLoading,
            modifier =
                if (consoleTarget != null) {
                    Modifier.weight(1f)
                } else {
                    Modifier.fillMaxWidth()
                }.semantics {
                    contentDescription = "Validate provider credentials"
                },
        ) {
            Icon(
                imageVector = Icons.Filled.Verified,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(ButtonIconSpacing))
            Text(text = if (isLoading) "Validating\u2026" else "Validate")
        }
    }
}

/**
 * Horizontal divider with centered "or use API key" text.
 *
 * Rendered below the OAuth login button to indicate the alternative
 * API-key authentication path.
 *
 * @param modifier Modifier applied to the root [Row].
 */
@Composable
private fun OAuthDividerRow(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "or use API key",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = FieldSpacing),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * Compact chip showing the connected OAuth session with a disconnect action.
 *
 * Displays a [CheckCircle][Icons.Default.CheckCircle] icon, the connected
 * account label, a provider-specific "Connected via ..." subtitle, and a
 * "Disconnect" [TextButton].
 *
 * @param email Display label for the connected OAuth account.
 * @param onDisconnect Callback invoked when the user taps "Disconnect".
 * @param providerLabel Provider-branded label such as "Login with ChatGPT"
 *   or "Login with Claude", used to derive the subtitle text.
 * @param modifier Modifier applied to the root [Surface].
 */
@Composable
private fun OAuthConnectedChip(
    email: String,
    onDisconnect: () -> Unit,
    providerLabel: String = "Login with ChatGPT",
    modifier: Modifier = Modifier,
) {
    val serviceName =
        providerLabel
            .removePrefix("Login with ")
            .removePrefix("Connect ")

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(ChipPadding),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(ChipIconSize),
            )
            Spacer(modifier = Modifier.width(ChipIconTextSpacing))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = email,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "Connected via $serviceName",
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme.onSecondaryContainer
                            .copy(alpha = 0.7f),
                )
            }
            TextButton(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }
    }
}

/**
 * Radio-group picker for Qwen's three regional DashScope endpoints.
 *
 * Displayed below the API key field whenever the selected provider is any
 * Qwen variant (`"qwen"`, `"qwen-cn"`, `"qwen-us"`). Each option maps to a
 * distinct [QwenRegion.providerId] that is written to the daemon TOML config.
 * The China entry shows a [QwenRegion.disclosure] warning explaining that
 * traffic and credentials route through Alibaba Cloud's Chinese infrastructure.
 *
 * @param selectedRegion Currently selected [QwenRegion].
 * @param onRegionSelected Callback invoked when the user picks a different region.
 * @param modifier Modifier applied to the root [Column].
 */
@Composable
private fun QwenRegionPicker(
    selectedRegion: QwenRegion,
    onRegionSelected: (QwenRegion) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = "Region",
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        QwenRegion.entries.forEach { region ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedRegion == region,
                            onClick = { onRegionSelected(region) },
                        ).padding(vertical = 4.dp),
            ) {
                RadioButton(
                    selected = selectedRegion == region,
                    onClick = { onRegionSelected(region) },
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(text = region.displayName)
                    if (region.disclosure.isNotEmpty()) {
                        Text(
                            text = region.disclosure,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Provider Setup - Empty")
@Composable
private fun PreviewEmpty() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "",
                apiKey = "",
                baseUrl = "",
                selectedModel = "",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
            )
        }
    }
}

@Preview(name = "Provider Setup - With Provider")
@Composable
private fun PreviewWithProvider() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "openai",
                apiKey = "sk-test1234",
                baseUrl = "",
                selectedModel = "gpt-4o",
                validationResult =
                    ValidationResult.Success(details = "3 models available"),
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - Loading")
@Composable
private fun PreviewLoading() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "anthropic",
                apiKey = "sk-ant-test",
                baseUrl = "",
                selectedModel = "",
                validationResult = ValidationResult.Loading,
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
            )
        }
    }
}

@Preview(name = "Provider Setup - Failure")
@Composable
private fun PreviewFailure() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "openai",
                apiKey = "sk-bad-key",
                baseUrl = "",
                selectedModel = "",
                validationResult =
                    ValidationResult.Failure(message = "Invalid API key"),
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
            )
        }
    }
}

@Preview(name = "Provider Setup - Local Provider")
@Composable
private fun PreviewLocalProvider() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "ollama",
                apiKey = "",
                baseUrl = "http://192.168.1.100:11434",
                selectedModel = "llama3.3",
                validationResult =
                    ValidationResult.Success(details = "Connected \u2014 6 models available"),
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(
    name = "Provider Setup - Dark",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PreviewDark() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "openai",
                apiKey = "sk-test1234",
                baseUrl = "",
                selectedModel = "gpt-4o",
                validationResult =
                    ValidationResult.Success(details = "3 models available"),
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - OAuth Login")
@Composable
private fun PreviewOAuthLogin() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "openai",
                apiKey = "",
                baseUrl = "",
                selectedModel = "",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                onOAuthLogin = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - OAuth In Progress")
@Composable
private fun PreviewOAuthInProgress() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "openai",
                apiKey = "",
                baseUrl = "",
                selectedModel = "",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                isOAuthInProgress = true,
                onOAuthLogin = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - OAuth Connected")
@Composable
private fun PreviewOAuthConnected() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "openai",
                apiKey = "",
                baseUrl = "",
                selectedModel = "gpt-4o",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                oauthEmail = "ChatGPT Login",
                onOAuthLogin = {},
                onOAuthDisconnect = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - Anthropic OAuth Login")
@Composable
private fun PreviewAnthropicOAuthLogin() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "anthropic",
                apiKey = "",
                baseUrl = "",
                selectedModel = "",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                onOAuthLogin = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - Anthropic OAuth Connected")
@Composable
private fun PreviewAnthropicOAuthConnected() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "anthropic",
                apiKey = "",
                baseUrl = "",
                selectedModel = "claude-sonnet-4-20250514",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                oauthEmail = "Claude Login",
                onOAuthLogin = {},
                onOAuthDisconnect = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - Qwen International")
@Composable
private fun PreviewQwenInternational() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "qwen",
                apiKey = "sk-testqwenkey1234567890",
                baseUrl = "",
                selectedModel = "qwen3.5-plus",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                showSkipHint = true,
            )
        }
    }
}

@Preview(name = "Provider Setup - Qwen China")
@Composable
private fun PreviewQwenChina() {
    ZeroAITheme {
        Surface {
            ProviderSetupFlow(
                selectedProvider = "qwen-cn",
                apiKey = "sk-testqwenkey1234567890",
                baseUrl = "",
                selectedModel = "qwen3.5-plus",
                onProviderChanged = {},
                onApiKeyChanged = {},
                onBaseUrlChanged = {},
                onModelChanged = {},
                showSkipHint = false,
            )
        }
    }
}
