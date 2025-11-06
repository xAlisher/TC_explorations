package com.example.keycardapp.domain.usecase

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.nimbusds.jwt.JWTParser
import java.nio.charset.StandardCharsets

/**
 * Result of VC validation.
 */
data class ValidateVcResult(
    val isValid: Boolean,
    val errorMessage: String? = null,
    val ndefMessage: NdefMessage? = null,
    val sizeBytes: Int = 0
)

/**
 * Use case for validating Verifiable Credential (JWT-VC).
 */
class ValidateVcUseCase {
    
    companion object {
        // Updated SDK supports chunking up to 500 bytes
        private const val MAX_PAYLOAD_SIZE = 1000 // 1KB limit for Keycard (theoretical)
        private const val MAX_NDEF_SIZE = 500 // Max NDEF size with chunking support
        private const val MIME_TYPE = "application/vc+jwt"
    }
    
    /**
     * Validate Verifiable Credential (JWT-VC).
     * Note: JWT validation is temporarily disabled for APDU size limit testing.
     * 
     * @param jwtVc The JWT-VC string to validate (or any string for testing)
     * @return ValidateVcResult containing validation status, error message, and NDEF message
     */
    suspend operator fun invoke(jwtVc: String): ValidateVcResult {
        // TEMPORARY: Skip JWT validation for size limit testing
        // TODO: Re-enable JWT validation after finding APDU size limit
        /*
        val jwt = try {
            JWTParser.parse(jwtVc)
        } catch (e: Exception) {
            return ValidateVcResult(
                isValid = false,
                errorMessage = "Invalid Credential Format: Not a valid JWT"
            )
        }
        */
        
        // Check size (max 1000 bytes for safety)
        val vcBytes = jwtVc.toByteArray(StandardCharsets.UTF_8)
        
        if (vcBytes.size > MAX_PAYLOAD_SIZE) {
            val sizeKB = String.format("%.1f", vcBytes.size / 1024.0)
            return ValidateVcResult(
                isValid = false,
                errorMessage = "Credential Too Large: This credential (${sizeKB}KB) is too large for the Keycard (1KB limit). Please contact your issuer for a more compact credential.",
                sizeBytes = vcBytes.size
            )
        }
        
        // Create NDEF record with MIME type application/vc+jwt
        val ndefRecord = NdefRecord.createMime(MIME_TYPE, vcBytes)
        val ndefMessage = NdefMessage(arrayOf(ndefRecord))
        val ndefBytes = ndefMessage.toByteArray()
        
        // Check if NDEF size exceeds max with chunking support
        if (ndefBytes.size > MAX_NDEF_SIZE) {
            val sizeKB = String.format("%.1f", ndefBytes.size / 1024.0)
            android.util.Log.w("ValidateVcUseCase", "Warning: NDEF message size (${ndefBytes.size} bytes, ${sizeKB}KB) exceeds maximum supported size (${MAX_NDEF_SIZE} bytes) even with chunking. Write will fail.")
            // Note: We don't fail validation here, but WriteVcUseCase will check and fail with a clear error message
        } else {
            android.util.Log.d("ValidateVcUseCase", "NDEF message size: ${ndefBytes.size} bytes (within ${MAX_NDEF_SIZE} bytes limit with chunking support)")
        }
        
        return ValidateVcResult(
            isValid = true,
            ndefMessage = ndefMessage,
            sizeBytes = vcBytes.size
        )
    }
}

