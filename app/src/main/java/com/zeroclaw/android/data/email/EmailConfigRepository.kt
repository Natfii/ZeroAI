/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.email

import android.content.SharedPreferences
import com.zeroclaw.android.data.local.dao.EmailConfigDao
import com.zeroclaw.android.data.local.entity.EmailConfigEntity
import java.util.TimeZone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/**
 * Immutable snapshot of the email integration configuration.
 *
 * Combines non-secret fields from Room with the encrypted password
 * from [SharedPreferences].
 *
 * @property imapHost IMAP server hostname.
 * @property imapPort IMAP server port (default 993 for IMAPS).
 * @property smtpHost SMTP server hostname.
 * @property smtpPort SMTP server port (default 465 for SMTPS).
 * @property address Email address for authentication and sending.
 * @property password Email account password (stored encrypted, never in Room).
 * @property checkTimes Scheduled check times in HH:mm format.
 * @property isEnabled Whether the email integration is active.
 */
data class EmailConfigState(
    val imapHost: String = "",
    val imapPort: Int = DEFAULT_IMAP_PORT,
    val smtpHost: String = "",
    val smtpPort: Int = DEFAULT_SMTP_PORT,
    val address: String = "",
    val password: String = "",
    val checkTimes: List<String> = emptyList(),
    val isEnabled: Boolean = false,
)

/**
 * Repository managing the email integration configuration.
 *
 * Non-secret fields (hosts, ports, address, schedule) are persisted in Room
 * via [EmailConfigDao]. The email password is stored separately in an
 * [EncryptedSharedPreferences][SharedPreferences] instance to keep secrets
 * out of the unencrypted SQLite database.
 *
 * @param dao Room DAO for the email_config table.
 * @param encryptedPrefs EncryptedSharedPreferences for password storage.
 */
class EmailConfigRepository(
    private val dao: EmailConfigDao,
    private val encryptedPrefs: SharedPreferences,
) {
    /**
     * Observes the email configuration as a reactive stream.
     *
     * Merges the Room entity with the encrypted password on each emission.
     *
     * @return A [Flow] emitting the current [EmailConfigState].
     */
    fun observe(): Flow<EmailConfigState> =
        dao.observe().map { entity ->
            val password =
                encryptedPrefs.getString(PREF_KEY_PASSWORD, "") ?: ""
            entity?.toState(password) ?: EmailConfigState(password = password)
        }

    /**
     * Persists the email configuration to Room and the password to
     * encrypted storage.
     *
     * @param state The complete configuration snapshot to save.
     */
    suspend fun save(state: EmailConfigState) {
        val editor = encryptedPrefs.edit()
        editor.putString(PREF_KEY_PASSWORD, state.password)
        check(editor.commit()) {
            "Encrypted storage unavailable: unable to persist email password"
        }
        dao.upsert(state.toEntity())
    }

    /**
     * Builds the JSON string expected by the Rust FFI `configure_email` and
     * `test_email_connection` functions.
     *
     * Includes the device timezone so the Rust cron scheduler can resolve
     * check times correctly.
     *
     * @param state The configuration snapshot to serialize.
     * @return JSON string matching the Rust `EmailConfig` schema.
     */
    fun toConfigJson(state: EmailConfigState): String {
        val json = JSONObject()
        json.put("imap_host", state.imapHost)
        json.put("imap_port", state.imapPort)
        json.put("smtp_host", state.smtpHost)
        json.put("smtp_port", state.smtpPort)
        json.put("address", state.address)
        json.put("password", state.password)
        json.put("timezone", TimeZone.getDefault().id)
        val timesArray = JSONArray()
        state.checkTimes.forEach { timesArray.put(it) }
        json.put("check_times", timesArray)
        return json.toString()
    }

    /** Constants for [EmailConfigRepository]. */
    companion object {
        private const val PREF_KEY_PASSWORD = "email_password"
    }
}

/** Default IMAP port for IMAPS connections. */
private const val DEFAULT_IMAP_PORT = 993

/** Default SMTP port for SMTPS connections. */
private const val DEFAULT_SMTP_PORT = 465

/**
 * Converts an [EmailConfigEntity] to an [EmailConfigState], injecting the
 * password from encrypted storage.
 *
 * @receiver The Room entity to convert.
 * @param password Decrypted password from EncryptedSharedPreferences.
 * @return The corresponding [EmailConfigState].
 */
private fun EmailConfigEntity.toState(password: String): EmailConfigState =
    EmailConfigState(
        imapHost = imapHost,
        imapPort = imapPort,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        address = address,
        password = password,
        checkTimes = if (checkTimes.isBlank()) emptyList() else checkTimes.split(","),
        isEnabled = isEnabled,
    )

/**
 * Converts an [EmailConfigState] to an [EmailConfigEntity] for Room persistence.
 *
 * The password is excluded because it lives in EncryptedSharedPreferences.
 *
 * @receiver The state snapshot to convert.
 * @return The corresponding [EmailConfigEntity] with singleton id = 1.
 */
private fun EmailConfigState.toEntity(): EmailConfigEntity =
    EmailConfigEntity(
        id = 1,
        imapHost = imapHost,
        imapPort = imapPort,
        smtpHost = smtpHost,
        smtpPort = smtpPort,
        address = address,
        checkTimes = checkTimes.joinToString(","),
        isEnabled = isEnabled,
    )
