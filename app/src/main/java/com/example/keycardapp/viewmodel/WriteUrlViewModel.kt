package com.example.keycardapp.viewmodel

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.keycardapp.domain.usecase.VerifyPinUseCase
import com.example.keycardapp.domain.usecase.WriteUrlUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import javax.inject.Inject

/**
 * ViewModel for Write URL to NDEF use case.
 * Manages UI state and coordinates use cases.
 */
@HiltViewModel
class WriteUrlViewModel @Inject constructor(
    private val verifyPinUseCase: VerifyPinUseCase,
    private val writeUrlUseCase: WriteUrlUseCase,
    @javax.inject.Named("pairingPassword") private val pairingPassword: String
) : ViewModel() {
    
    companion object {
        private const val MAX_RETRIES = 3
        private const val RETRY_COOLDOWN_MS = 3000L // 3 seconds
    }
    
    private val _state = MutableStateFlow(WriteUrlState())
    val state: StateFlow<WriteUrlState> = _state.asStateFlow()
    
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
                            status = "✅ PIN verified. Enter URL to write to NDEF.",
                            showUrlDialog = true
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
                // Execute callback after operation completes (on main thread)
                onComplete()
            }
        }
    }
    
    /**
     * Show URL dialog.
     */
    fun showUrlDialog() {
        _state.update { it.copy(showUrlDialog = true) }
    }
    
    /**
     * Update URL input.
     */
    fun updateUrlInput(url: String) {
        _state.update { it.copy(urlInput = url) }
    }
    
    /**
     * Confirm URL and start writing.
     */
    fun confirmUrl() {
        val url = _state.value.urlInput.trim()
        if (url.isNotEmpty()) {
            _state.update {
                it.copy(
                    showUrlDialog = false,
                    urlInput = "",
                    pendingUrl = url,
                    writtenHex = null,
                    status = "Searching for the card..."
                )
            }
            addLog("Waiting for card to write URL: $url")
        }
    }
    
    /**
     * Dismiss URL dialog.
     */
    fun dismissUrlDialog() {
        _state.update { it.copy(showUrlDialog = false) }
    }
    
    /**
     * Write URL to NDEF on Keycard.
     * @param onComplete Callback to execute after operation completes (e.g., disable reader mode)
     *                   Parameter indicates whether reader mode should remain enabled (for retries)
     */
    fun writeUrl(tag: Tag, onComplete: (Boolean) -> Unit = { _ -> }) {
        val url = _state.value.pendingUrl ?: return
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
                addLog("Card detected. Preparing to write URL to NDEF...")
            }
            addLog("Starting secure write...")
            
            val writeResult = writeUrlUseCase(tag, url, pairingPassword, pin, retryCount)
            
            if (!writeResult.success) {
                if (writeResult.isTagLost && retryCount < MAX_RETRIES) {
                    // Tag was lost, show cooldown message and retry
                    _state.update { it.copy(writeRetryCount = retryCount + 1) }
                    updateStatus("⚠️ Tag lost. Waiting 3 seconds before retry...")
                    addLog("Tag lost. Waiting ${RETRY_COOLDOWN_MS / 1000} seconds before retry (${retryCount + 1}/$MAX_RETRIES)...")
                    
                    // Wait 3 seconds (cooldown)
                    delay(RETRY_COOLDOWN_MS)
                    
                    updateStatus("Retrying... (Attempt ${retryCount + 2}/$MAX_RETRIES)")
                    addLog("Cooldown complete. Retrying...")
                    
                    // Keep pendingUrl so it can be retried
                    // Reader mode should remain enabled for next tap
                    onComplete(true) // Keep reader mode enabled
                    return@launch
                } else {
                    // Failed after retries or non-retryable error
                    if (writeResult.isTagLost && retryCount >= MAX_RETRIES) {
                        updateStatus("❌ Failed after $MAX_RETRIES attempts. Please try again.")
                        addLog("Failed after $MAX_RETRIES retry attempts")
                    } else {
                        updateStatus("❌ Failed to write NDEF")
                        addLog("Secure write failed: ${writeResult.errorMessage}")
                    }
                    _state.update {
                        it.copy(
                            writeRetryCount = 0,
                            pendingUrl = null
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
                    pendingUrl = null
                )
            }
            
            if (retryCount > 0) {
                updateStatus("✅ URL written successfully after ${retryCount + 1} attempts!")
                addLog("NDEF write success after ${retryCount + 1} attempts. Hex length: ${writeResult.hexOutput?.length ?: 0}")
            } else {
                updateStatus("✅ NDEF written.")
                addLog("NDEF write success. Hex length: ${writeResult.hexOutput?.length ?: 0}")
            }
            
            // Execute callback after operation completes
            onComplete(false) // Disable reader mode on success
        }
    }
    
    /**
     * Reset state when navigating away.
     */
    fun reset() {
        _state.value = WriteUrlState()
    }
    
    /**
     * Check if reader mode should remain enabled for retries.
     */
    fun shouldKeepReaderModeEnabled(): Boolean {
        val state = _state.value
        return state.pendingUrl != null && 
               state.writeRetryCount > 0 && 
               state.writeRetryCount < MAX_RETRIES
    }
}

