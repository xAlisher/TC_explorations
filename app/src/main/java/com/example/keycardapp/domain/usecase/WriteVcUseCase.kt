package com.example.keycardapp.domain.usecase

import android.nfc.Tag
import com.example.keycardapp.domain.repository.KeycardRepository

/**
 * Result of VC write operation.
 */
data class WriteVcResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val hexOutput: String? = null,
    val isTagLost: Boolean = false
)

/**
 * Use case for writing Verifiable Credential to NDEF on Keycard.
 * Handles retry logic for tag loss scenarios.
 */
class WriteVcUseCase(
    private val keycardRepository: KeycardRepository,
    private val validateVcUseCase: ValidateVcUseCase
) {
    companion object {
        private const val MAX_RETRIES = 3
    }
    
    /**
     * Write Verifiable Credential to NDEF on Keycard.
     * 
     * @param tag The NFC tag representing the Keycard
     * @param jwtVc The JWT-VC string to write
     * @param pairingPassword The pairing password for the card
     * @param pin The PIN to verify before writing
     * @param retryCount Current retry count (for internal use)
     * @return WriteVcResult containing success status, error message, and hex output
     */
    suspend operator fun invoke(
        tag: Tag,
        jwtVc: String,
        pairingPassword: String,
        pin: String,
        retryCount: Int = 0
    ): WriteVcResult {
        // Ensure JWT-VC is trimmed (defensive check)
        val trimmedJwtVc = jwtVc.trim()
        
        // First validate the VC
        val validationResult = validateVcUseCase(trimmedJwtVc)
        if (!validationResult.isValid) {
            return WriteVcResult(
                success = false,
                errorMessage = validationResult.errorMessage
            )
        }
        
        val ndefMessage = validationResult.ndefMessage
            ?: return WriteVcResult(
                success = false,
                errorMessage = "Failed to create NDEF message"
            )
        
        val ndefBytes = ndefMessage.toByteArray()
        
        // Log NDEF size for debugging
        android.util.Log.d("WriteVcUseCase", "NDEF message size: ${ndefBytes.size} bytes (JWT-VC: ${trimmedJwtVc.length} chars)")
        
        // Validate NDEF size against 500-byte limit (with chunking support)
        if (ndefBytes.size > 500) {
            val sizeKB = String.format("%.1f", ndefBytes.size / 1024.0)
            android.util.Log.e("WriteVcUseCase", "NDEF message size (${ndefBytes.size} bytes, ${sizeKB}KB) exceeds 500-byte limit with chunking support")
            return WriteVcResult(
                success = false,
                errorMessage = "Credential Too Large: NDEF message (${ndefBytes.size} bytes, ${sizeKB}KB) exceeds the 500-byte limit. Please use a smaller credential."
            )
        }
        
        // Write NDEF via Keycard
        val writeResult = keycardRepository.writeNdef(tag, ndefBytes, pairingPassword, pin)
        
        if (writeResult.isSuccess) {
            // Calculate hex with length prefix (as per Keycard SDK spec)
            val lengthPrefix = byteArrayOf(
                ((ndefBytes.size shr 8) and 0xFF).toByte(),
                (ndefBytes.size and 0xFF).toByte()
            )
            val fullPayload = lengthPrefix + ndefBytes
            val hex = toHex(fullPayload)
            
            return WriteVcResult(
                success = true,
                hexOutput = hex
            )
        } else {
            val error = writeResult.exceptionOrNull()?.message ?: "Write failed"
            val isTagLost = isTagLostError(error)
            
            // If tag was lost and we haven't exceeded max retries, return with retry flag
            if (isTagLost && retryCount < MAX_RETRIES) {
                return WriteVcResult(
                    success = false,
                    errorMessage = error,
                    isTagLost = true
                )
            }
            
            return WriteVcResult(
                success = false,
                errorMessage = error,
                isTagLost = isTagLost
            )
        }
    }
    
    /**
     * Check if error is due to tag loss or secure channel loss (can be retried).
     */
    private fun isTagLostError(error: String): Boolean {
        return error.contains("TagLostException", ignoreCase = true) ||
               error.contains("Tag was lost", ignoreCase = true) ||
               error.contains("TagLost", ignoreCase = true) ||
               error.contains("IOException", ignoreCase = true) ||
               error.contains("connection", ignoreCase = true) ||
               error.contains("secure channel", ignoreCase = true) ||
               error.contains("AssertionError", ignoreCase = true) ||
               error.contains("session may have been lost", ignoreCase = true)
    }
    
    /**
     * Convert bytes to hex string.
     */
    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}

