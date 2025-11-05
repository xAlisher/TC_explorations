package com.example.keycardapp.viewmodel

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.keycardapp.domain.usecase.VerifyPinUseCase
import com.example.keycardapp.domain.usecase.WriteUrlUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for Write URL to NDEF use case.
 * Manages UI state and coordinates use cases.
 */
class WriteUrlViewModel(
    private val verifyPinUseCase: VerifyPinUseCase,
    private val writeUrlUseCase: WriteUrlUseCase,
    private val pairingPassword: String
) : ViewModel() {
    
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
     */
    fun verifyPin(tag: Tag) {
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
     */
    fun writeUrl(tag: Tag) {
        val url = _state.value.pendingUrl ?: return
        val pin = _state.value.lastVerifiedPin ?: run {
            updateStatus("❌ No verified PIN available for secure write")
            addLog("No verified PIN available")
            return
        }
        
        viewModelScope.launch {
            updateStatus("Connection established, please don't move the card...")
            addLog("Card detected. Preparing to write URL to NDEF...")
            addLog("Starting secure write...")
            
            val writeResult = writeUrlUseCase(tag, url, pairingPassword, pin)
            
            if (writeResult.isSuccess) {
                val hex = writeResult.getOrNull()
                _state.update {
                    it.copy(
                        writtenHex = hex,
                        pendingUrl = null,
                        status = "✅ NDEF written."
                    )
                }
                addLog("NDEF write success. Hex length: ${hex?.length ?: 0}")
            } else {
                val error = writeResult.exceptionOrNull()?.message ?: "Write failed"
                updateStatus("❌ Failed to write NDEF")
                addLog("Secure write failed: $error")
                _state.update { it.copy(pendingUrl = null) }
            }
        }
    }
    
    /**
     * Reset state when navigating away.
     */
    fun reset() {
        _state.value = WriteUrlState()
    }
}

