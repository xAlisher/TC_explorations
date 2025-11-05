package com.example.keycardapp.domain.usecase

import android.nfc.Tag
import com.example.keycardapp.domain.repository.KeycardRepository

/**
 * Use case for verifying PIN with Keycard.
 */
class VerifyPinUseCase(
    private val keycardRepository: KeycardRepository
) {
    /**
     * Verify PIN with Keycard.
     * 
     * @param tag The NFC tag representing the Keycard
     * @param pin The PIN to verify
     * @return Result containing success status
     */
    suspend operator fun invoke(tag: Tag, pin: String): Result<Boolean> {
        return keycardRepository.verifyPin(tag, pin)
    }
}

