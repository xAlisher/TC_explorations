package com.example.keycardapp.domain.repository

import android.nfc.Tag

/**
 * Repository interface for Keycard operations.
 * Provides a clean abstraction for all Keycard interactions.
 */
interface KeycardRepository {
    
    /**
     * Verify PIN with Keycard.
     * 
     * @param tag The NFC tag representing the Keycard
     * @param pin The PIN to verify
     * @return Result containing success status and optional error message
     */
    suspend fun verifyPin(tag: Tag, pin: String): Result<Boolean>
    
    /**
     * Write NDEF data to Keycard via secure channel.
     * 
     * This operation:
     * 1. Selects the Keycard applet
     * 2. Pairs with the card (unpairing existing pairings if needed)
     * 3. Opens a secure channel
     * 4. Verifies PIN
     * 5. Writes NDEF data
     * 6. Unpairs after successful write
     * 
     * @param tag The NFC tag representing the Keycard
     * @param ndefBytes The NDEF data to write (as bytes)
     * @param pairingPassword The pairing password for the card
     * @param pin The PIN to verify before writing
     * @return Result containing success status and optional error message
     */
    suspend fun writeNdef(
        tag: Tag,
        ndefBytes: ByteArray,
        pairingPassword: String,
        pin: String
    ): Result<Unit>
}

