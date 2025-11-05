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
        private const val MAX_PAYLOAD_SIZE = 1000 // 1KB limit for Keycard
        private const val MIME_TYPE = "application/vc+jwt"
    }
    
    /**
     * Validate Verifiable Credential (JWT-VC).
     * 
     * @param jwtVc The JWT-VC string to validate
     * @return ValidateVcResult containing validation status, error message, and NDEF message
     */
    suspend operator fun invoke(jwtVc: String): ValidateVcResult {
        // Validate JWT format
        val jwt = try {
            JWTParser.parse(jwtVc)
        } catch (e: Exception) {
            return ValidateVcResult(
                isValid = false,
                errorMessage = "Invalid Credential Format: Not a valid JWT"
            )
        }
        
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
        
        return ValidateVcResult(
            isValid = true,
            ndefMessage = ndefMessage,
            sizeBytes = vcBytes.size
        )
    }
}

