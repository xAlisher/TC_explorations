package com.example.keycardapp.viewmodel

/**
 * State for Read VC from NDEF use case.
 * Note: NDEF reading does not require PIN or secure channel.
 */
data class ReadVcState(
    val status: String = "Ready to read VC from Keycard. Tap your Keycard...",
    val logs: List<String> = emptyList(),
    val readingVc: Boolean = false,
    val verifyingProof: Boolean = false,
    val jwtVc: String? = null,
    val decodedPayload: String? = null,
    val issuer: String? = null,
    val subject: String? = null,
    val vcClaims: String? = null,
    val verificationError: String? = null
)

