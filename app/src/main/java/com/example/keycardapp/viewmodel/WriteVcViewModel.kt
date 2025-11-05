package com.example.keycardapp.viewmodel

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.keycardapp.domain.usecase.VerifyPinUseCase
import com.example.keycardapp.domain.usecase.WriteVcUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for Write VC to NDEF use case.
 * Manages UI state and coordinates use cases.
 */
class WriteVcViewModel(
    private val verifyPinUseCase: VerifyPinUseCase,
    private val writeVcUseCase: WriteVcUseCase,
    private val pairingPassword: String
) : ViewModel() {
    
    companion object {
        private const val MAX_RETRIES = 3
    }
    
    private val _state = MutableStateFlow(WriteVcState())
    val state: StateFlow<WriteVcState> = _state.asStateFlow()
    
    /**
     * Update status message.
     */
    fun updateStatus(status: String) {
        _state.update { it.copy(status = status) }
    }
    
    /**
     * Add log message.
     */
    fun addLog(message: String) {
        _state.update { it.copy(logs = it.logs + message) }
    }
    
    /**
     * Show PIN dialog.
     */
    fun showPinDialog() {
        _state.update { it.copy(showPinDialog = true, status = "Please enter your PIN") }
    }
    
    /**
     * Update PIN input.
     */
    fun updatePinInput(pin: String) {
        _state.update { it.copy(pinInput = pin) }
    }
    
    /**
     * Confirm PIN and start verification.
     */
    fun confirmPin() {
        val pin = _state.value.pinInput
        if (pin.isNotEmpty()) {
            _state.update {
                it.copy(
                    showPinDialog = false,
                    pinInput = "",
                    pendingPin = pin,
                    status = "Now tap your Keycard to verify PIN"
                )
            }
        }
    }
    
    /**
     * Dismiss PIN dialog.
     */
    fun dismissPinDialog() {
        _state.update { it.copy(showPinDialog = false, pinInput = "") }
    }
    
    /**
     * Verify PIN with Keycard.
     * @param onComplete Callback to execute after operation completes (e.g., disable reader mode)
     */
    fun verifyPin(tag: Tag, onComplete: () -> Unit = {}) {
        val pin = _state.value.pendingPin ?: return
        
        viewModelScope.launch {
            addLog("Tag detected for PIN verification")
            updateStatus("Verifying PIN...")
            
            try {
                addLog("Starting PIN verification")
                val result = verifyPinUseCase(tag, pin)
                val success = result.getOrElse { false }
                
                result.onFailure { error ->
                    addLog("PIN verification error: ${error.message}")
                }
                addLog("PIN verification result: $success")
                
                if (success) {
                    _state.update {
                        it.copy(
                            pendingPin = null,
                            lastVerifiedPin = pin,
                            status = "✅ PIN verified. Scan QR code to get credential.",
                            showQrScanner = true
                        )
                    }
                } else {
                    updateStatus("❌ Wrong PIN")
                    _state.update { it.copy(pendingPin = null) }
                }
            } catch (e: Exception) {
                addLog("PIN verification exception: ${e.message}")
                updateStatus("Error: ${e.message}")
                _state.update { it.copy(pendingPin = null) }
            } finally {
                // Execute callback after operation completes
                onComplete()
            }
        }
    }
    
    /**
     * Show QR scanner dialog.
     */
    fun showQrScanner() {
        _state.update { it.copy(showQrScanner = true) }
    }
    
    /**
     * Update JWT input.
     */
    fun updateJwtInput(jwt: String) {
        _state.update { it.copy(jwtInput = jwt) }
    }
    
    /**
     * Handle QR scan result (JWT-VC).
     */
    fun handleQrScanned(jwtVc: String) {
        _state.update {
            it.copy(
                showQrScanner = false,
                jwtInput = "",
                validatingVc = true,
                validationError = null,
                status = "Validating credential..."
            )
        }
        addLog("QR code scanned, validating JWT-VC...")
        
        viewModelScope.launch {
            try {
                // Note: Validation is done in WriteVcUseCase, but we can pre-validate here
                // For now, just store it and validate when writing
                _state.update {
                    it.copy(
                        validatingVc = false,
                        pendingVcJwt = jwtVc,
                        writeRetryCount = 0,
                        status = "✅ Credential ready. Tap your Keycard to write..."
                    )
                }
                addLog("VC ready for writing")
            } catch (e: Exception) {
                addLog("VC validation exception: ${e.message}")
                _state.update {
                    it.copy(
                        validatingVc = false,
                        validationError = "Validation Error: ${e.message}",
                        status = "❌ Validation error"
                    )
                }
            }
        }
    }
    
    /**
     * Dismiss QR scanner dialog.
     */
    fun dismissQrScanner() {
        _state.update { it.copy(showQrScanner = false, jwtInput = "") }
    }
    
    /**
     * Write VC to NDEF on Keycard.
     * @param onComplete Callback to execute after operation completes (e.g., disable reader mode)
     *                   Parameter indicates whether reader mode should remain enabled (for retries)
     */
    fun writeVc(tag: Tag, onComplete: (Boolean) -> Unit = { _ -> }) {
        val jwtVc = _state.value.pendingVcJwt ?: return
        val pin = _state.value.lastVerifiedPin ?: run {
            updateStatus("❌ No verified PIN available for secure write")
            addLog("No verified PIN available")
            onComplete(false)
            return
        }
        val retryCount = _state.value.writeRetryCount
        
        viewModelScope.launch {
            if (retryCount > 0) {
                updateStatus("Retrying... (Attempt ${retryCount + 1}/$MAX_RETRIES)")
                addLog("Retry attempt ${retryCount + 1}/$MAX_RETRIES")
            } else {
                updateStatus("Connection established, please don't move the card...")
                addLog("Card detected. Preparing to write VC NDEF...")
            }
            
            val writeResult = writeVcUseCase(tag, jwtVc, pairingPassword, pin, retryCount)
            
            if (!writeResult.success) {
                if (writeResult.isTagLost && retryCount < MAX_RETRIES) {
                    // Tag was lost, retry - keep reader mode enabled
                    _state.update { it.copy(writeRetryCount = retryCount + 1) }
                    updateStatus("⚠️ Tag lost. Retrying... (Attempt ${retryCount + 2}/$MAX_RETRIES)")
                    addLog("Tag lost. Retrying (${retryCount + 1}/$MAX_RETRIES)...")
                    // Keep pendingVcJwt so it can be retried
                    // Reader mode should remain enabled for next tap
                    onComplete(true) // Keep reader mode enabled
                    return@launch
                } else {
                    // Failed after retries or non-retryable error
                    if (writeResult.isTagLost && retryCount >= MAX_RETRIES) {
                        updateStatus("❌ Failed after $MAX_RETRIES attempts. Please try again.")
                        addLog("Failed after $MAX_RETRIES retry attempts")
                    } else {
                        updateStatus("❌ Failed to write VC NDEF")
                        addLog("Secure write failed: ${writeResult.errorMessage}")
                    }
                    _state.update {
                        it.copy(
                            writeRetryCount = 0,
                            pendingVcJwt = null
                        )
                    }
                    onComplete(false) // Disable reader mode
                    return@launch
                }
            }
            
            // Success!
            _state.update {
                it.copy(
                    writtenHex = writeResult.hexOutput,
                    writeRetryCount = 0,
                    pendingVcJwt = null
                )
            }
            
            if (retryCount > 0) {
                updateStatus("✅ VC written successfully after ${retryCount + 1} attempts!")
                addLog("VC NDEF write success after ${retryCount + 1} attempts. Hex length: ${writeResult.hexOutput?.length ?: 0}")
            } else {
                updateStatus("✅ VC written successfully!")
                addLog("VC NDEF write success. Hex length: ${writeResult.hexOutput?.length ?: 0}")
            }
            
            // Execute callback after operation completes
            onComplete(false) // Disable reader mode on success
        }
    }
    
    /**
     * Check if reader mode should remain enabled for retries.
     */
    fun shouldKeepReaderModeEnabled(): Boolean {
        val state = _state.value
        return state.pendingVcJwt != null && 
               state.writeRetryCount > 0 && 
               state.writeRetryCount < MAX_RETRIES
    }
    
    /**
     * Reset state when navigating away.
     */
    fun reset() {
        _state.value = WriteVcState()
    }
}

