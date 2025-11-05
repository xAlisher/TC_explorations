package com.example.keycardapp.domain.usecase

import android.nfc.Tag
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.example.keycardapp.domain.repository.KeycardRepository

/**
 * Result of URL write operation.
 */
data class WriteUrlResult(
    val success: Boolean,
    val errorMessage: String? = null,
    val hexOutput: String? = null,
    val isTagLost: Boolean = false
)

/**
 * Use case for writing URL to NDEF on Keycard.
 * Handles retry logic for tag loss scenarios.
 */
class WriteUrlUseCase(
    private val keycardRepository: KeycardRepository
) {
    companion object {
        private const val MAX_RETRIES = 3
    }
    
    /**
     * Write URL to NDEF on Keycard.
     * 
     * @param tag The NFC tag representing the Keycard
     * @param url The URL to write
     * @param pairingPassword The pairing password for the card
     * @param pin The PIN to verify before writing
     * @param retryCount Current retry count (for internal use)
     * @return WriteUrlResult containing success status, error message, and hex output
     */
    suspend operator fun invoke(
        tag: Tag,
        url: String,
        pairingPassword: String,
        pin: String,
        retryCount: Int = 0
    ): WriteUrlResult {
        // Build NDEF message from URL
        val ndefMessage = buildUriNdef(url)
        val ndefBytes = ndefMessage.toByteArray()
        
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
            
            return WriteUrlResult(
                success = true,
                hexOutput = hex
            )
        } else {
            val error = writeResult.exceptionOrNull()?.message ?: "Write failed"
            val isTagLost = isTagLostError(error)
            
            // If tag was lost and we haven't exceeded max retries, return with retry flag
            if (isTagLost && retryCount < MAX_RETRIES) {
                return WriteUrlResult(
                    success = false,
                    errorMessage = error,
                    isTagLost = true
                )
            }
            
            return WriteUrlResult(
                success = false,
                errorMessage = error,
                isTagLost = isTagLost
            )
        }
    }
    
    /**
     * Check if error is due to tag loss (can be retried).
     */
    private fun isTagLostError(error: String): Boolean {
        return error.contains("TagLostException", ignoreCase = true) ||
               error.contains("Tag was lost", ignoreCase = true) ||
               error.contains("TagLost", ignoreCase = true) ||
               error.contains("IOException", ignoreCase = true) ||
               error.contains("connection", ignoreCase = true)
    }
    
    /**
     * Build URI NDEF message from URL.
     */
    private fun buildUriNdef(url: String): NdefMessage {
        val uriRecord = NdefRecord.createUri(url)
        return NdefMessage(arrayOf(uriRecord))
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

