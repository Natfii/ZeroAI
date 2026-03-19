/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing the singleton email configuration.
 *
 * Only one row exists in this table (id = 1). The email password is
 * stored separately in EncryptedSharedPreferences; this entity holds
 * only non-secret IMAP/SMTP settings and scheduling information.
 *
 * @property id Singleton primary key, always 1.
 * @property imapHost IMAP server hostname.
 * @property imapPort IMAP server port (default 993 for IMAPS).
 * @property smtpHost SMTP server hostname.
 * @property smtpPort SMTP server port (default 465 for SMTPS).
 * @property address Email address for authentication and sending.
 * @property checkTimes Comma-separated list of HH:mm times to check email.
 * @property isEnabled Whether the email integration is active.
 */
@Entity(tableName = "email_config")
data class EmailConfigEntity(
    @PrimaryKey
    val id: Int = 1,
    @ColumnInfo(name = "imap_host")
    val imapHost: String = "",
    @ColumnInfo(name = "imap_port")
    val imapPort: Int = 993,
    @ColumnInfo(name = "smtp_host")
    val smtpHost: String = "",
    @ColumnInfo(name = "smtp_port")
    val smtpPort: Int = 465,
    val address: String = "",
    @ColumnInfo(name = "check_times")
    val checkTimes: String = "",
    @ColumnInfo(name = "is_enabled")
    val isEnabled: Boolean = false,
)
