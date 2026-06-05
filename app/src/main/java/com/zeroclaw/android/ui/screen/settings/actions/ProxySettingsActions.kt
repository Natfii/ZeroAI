/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings.actions

import androidx.compose.runtime.Stable

/**
 * Proxy settings area holder.
 *
 * Owns daemon-affecting proxy enablement, per-scheme proxies, no-proxy list,
 * scope, and service selectors; all mark a restart as required.
 */
@Stable
internal class ProxySettingsActions(
    private val s: SettingsActionScope,
) {
    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyEnabled */
    fun updateProxyEnabled(enabled: Boolean) {
        s.updateDaemonSetting { setProxyEnabled(enabled) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyHttpProxy */
    fun updateProxyHttpProxy(proxy: String) {
        s.updateDaemonSetting { setProxyHttpProxy(proxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyHttpsProxy */
    fun updateProxyHttpsProxy(proxy: String) {
        s.updateDaemonSetting { setProxyHttpsProxy(proxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyAllProxy */
    fun updateProxyAllProxy(proxy: String) {
        s.updateDaemonSetting { setProxyAllProxy(proxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyNoProxy */
    fun updateProxyNoProxy(noProxy: String) {
        s.updateDaemonSetting { setProxyNoProxy(noProxy) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyScope */
    fun updateProxyScope(scope: String) {
        s.updateDaemonSetting { setProxyScope(scope) }
    }

    /** @see com.zeroclaw.android.data.repository.SettingsRepository.setProxyServiceSelectors */
    fun updateProxyServiceSelectors(selectors: String) {
        s.updateDaemonSetting { setProxyServiceSelectors(selectors) }
    }
}
