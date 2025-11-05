package com.example.keycardapp.viewmodel

/**
 * UI state for Write VC to NDEF use case.
 */
data class WriteVcState(
    val status: String = "",
    val logs: List<String> = emptyList(),
    val writtenHex: String? = null,
    val showPinDialog: Boolean = false,
    val pinInput: String = "",
    val showQrScanner: Boolean = false,
    val jwtInput: String = "",
    val validatingVc: Boolean = false,
    val validationError: String? = null,
    val pendingPin: String? = null,
    val pendingVcJwt: String? = null,
    val lastVerifiedPin: String? = null,
    val writeRetryCount: Int = 0
)

