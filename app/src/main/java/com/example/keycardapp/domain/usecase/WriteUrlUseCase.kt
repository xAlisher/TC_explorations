package com.example.keycardapp.domain.usecase

import android.nfc.Tag
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import com.example.keycardapp.domain.repository.KeycardRepository

/**
 * Use case for writing URL to NDEF on Keycard.
 */
class WriteUrlUseCase(
    private val keycardRepository: KeycardRepository
) {
    /**
     * Write URL to NDEF on Keycard.
     * 
     * @param tag The NFC tag representing the Keycard
     * @param url The URL to write
     * @param pairingPassword The pairing password for the card
     * @param pin The PIN to verify before writing
     * @return Result containing the hex representation of the written NDEF
     */
    suspend operator fun invoke(
        tag: Tag,
        url: String,
        pairingPassword: String,
        pin: String
    ): Result<String> {
        // Build NDEF message from URL
        val ndefMessage = buildUriNdef(url)
        val ndefBytes = ndefMessage.toByteArray()
        
        // Write NDEF via Keycard
        val writeResult = keycardRepository.writeNdef(tag, ndefBytes, pairingPassword, pin)
        
        return writeResult.map {
            // Calculate hex with length prefix (as per Keycard SDK spec)
            val lengthPrefix = byteArrayOf(
                ((ndefBytes.size shr 8) and 0xFF).toByte(),
                (ndefBytes.size and 0xFF).toByte()
            )
            val fullPayload = lengthPrefix + ndefBytes
            toHex(fullPayload)
        }
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

