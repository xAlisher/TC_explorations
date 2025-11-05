package com.example.keycardapp.viewmodel

/**
 * UI state for Write URL to NDEF use case.
 */
data class WriteUrlState(
    val status: String = "Waiting for Keycard tap...",
    val logs: List<String> = emptyList(),
    val writtenHex: String? = null,
    val showPinDialog: Boolean = false,
    val pinInput: String = "",
    val showUrlDialog: Boolean = false,
    val urlInput: String = "",
    val pendingPin: String? = null,
    val pendingUrl: String? = null,
    val lastVerifiedPin: String? = null,
    val writeRetryCount: Int = 0
)

