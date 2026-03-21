/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zeroclaw.ffi.resolveCapabilityRequest

/**
 * Broadcast receiver for capability approval notification actions.
 *
 * Receives Approve/Deny intents from notification action buttons and
 * forwards the decision to the Rust daemon via [resolveCapabilityRequest].
 */
class CapabilityApprovalReceiver : BroadcastReceiver() {
    /**
     * Handles an incoming broadcast from a capability approval notification action.
     *
     * Extracts the `request_id` and `approved` extras from the intent, resolves
     * the capability request via FFI, and dismisses the originating notification.
     * Intents missing a `request_id` are silently dropped. FFI failures are logged
     * at error level but do not propagate.
     *
     * @param context Receiver context, used to access the application instance.
     * @param intent The broadcast intent containing `request_id` and `approved` extras.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val requestId = intent.getStringExtra("request_id") ?: return
        val approved = intent.getBooleanExtra("approved", false)

        try {
            resolveCapabilityRequest(requestId, approved)
            Log.d(TAG, "Resolved $requestId: approved=$approved")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve $requestId", e)
        }

        val app = context.applicationContext as? com.zeroclaw.android.ZeroAIApplication
        app?.capabilityApprovalNotifier?.dismissNotification(requestId)
    }

    /** Constants for [CapabilityApprovalReceiver]. */
    companion object {
        private const val TAG = "CapApproval"
    }
}
