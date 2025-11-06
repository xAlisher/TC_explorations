package com.example.keycardapp.viewmodel

import android.nfc.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.keycardapp.domain.usecase.ReadVcFromNdefUseCase
import com.example.keycardapp.domain.usecase.VerifyVcProofUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * ViewModel for Read VC from NDEF use case.
 * Manages UI state and coordinates use cases.
 * Note: NDEF reading does not require PIN or secure channel.
 */
@HiltViewModel
class ReadVcViewModel @Inject constructor(
    private val readVcFromNdefUseCase: ReadVcFromNdefUseCase,
    private val verifyVcProofUseCase: VerifyVcProofUseCase
) : ViewModel() {
    
    private val _state = MutableStateFlow(ReadVcState())
    val state: StateFlow<ReadVcState> = _state.asStateFlow()
    
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
     * Read VC from NDEF on Keycard.
     * Note: NDEF reading does not require PIN or secure channel.
     * @param onComplete Callback to execute after operation completes
     */
    fun readVc(tag: Tag, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            addLog("Tag detected for VC read")
            updateStatus("Reading VC from Keycard...")
            _state.update { it.copy(readingVc = true, verificationError = null) }
            
            try {
                addLog("Starting VC read from NDEF (no PIN required)")
                val readResult = readVcFromNdefUseCase(tag)
                
                if (readResult.isTagLost) {
                    // Tag was lost, ask user to retry
                    addLog("Tag lost, please tap again")
                    _state.update {
                        it.copy(
                            readingVc = false,
                            status = "Tag lost. Please tap your Keycard again..."
                        )
                    }
                    onComplete()
                    return@launch
                }
                
                if (!readResult.success) {
                    addLog("VC read failed: ${readResult.errorMessage}")
                    _state.update {
                        it.copy(
                            readingVc = false,
                            verificationError = readResult.errorMessage,
                            status = "❌ Read failed: ${readResult.errorMessage}"
                        )
                    }
                    onComplete()
                    return@launch
                }
                
                val jwtVc = readResult.jwtVc
                if (jwtVc == null) {
                    addLog("No JWT-VC found in read result")
                    _state.update {
                        it.copy(
                            readingVc = false,
                            verificationError = "No JWT-VC found",
                            status = "❌ No VC found"
                        )
                    }
                    onComplete()
                    return@launch
                }
                
                addLog("VC read successfully (${jwtVc.length} chars)")
                _state.update {
                    it.copy(
                        readingVc = false,
                        jwtVc = jwtVc,
                        status = "✅ VC read. Verifying cryptographic proof..."
                    )
                }
                
                // Now verify the cryptographic proof
                verifyVcProof(jwtVc)
                
            } catch (e: Exception) {
                addLog("VC read exception: ${e.message}")
                _state.update {
                    it.copy(
                        readingVc = false,
                        verificationError = "Read error: ${e.message}",
                        status = "❌ Error: ${e.message}"
                    )
                }
            } finally {
                onComplete()
            }
        }
    }
    
    /**
     * Verify cryptographic proof of VC.
     */
    private fun verifyVcProof(jwtVc: String) {
        viewModelScope.launch {
            _state.update { it.copy(verifyingProof = true) }
            addLog("Verifying cryptographic proof...")
            
            try {
                val verifyResult = verifyVcProofUseCase(jwtVc)
                
                if (!verifyResult.isValid) {
                    addLog("Proof verification failed: ${verifyResult.errorMessage}")
                    _state.update {
                        it.copy(
                            verifyingProof = false,
                            verificationError = verifyResult.errorMessage,
                            status = "❌ Proof verification failed: ${verifyResult.errorMessage}"
                        )
                    }
                    return@launch
                }
                
                // Format decoded payload for display
                val decodedPayload = verifyResult.decodedPayload
                val decodedPayloadStr = decodedPayload?.toString() // JSON string
                
                // Format VC claims for display
                val vcClaims = verifyResult.vcClaims
                val vcClaimsStr = vcClaims?.toString() // JSON string
                
                addLog("Proof verification successful")
                addLog("Issuer: ${verifyResult.issuer}")
                addLog("Subject: ${verifyResult.subject}")
                
                _state.update {
                    it.copy(
                        verifyingProof = false,
                        decodedPayload = decodedPayloadStr,
                        issuer = verifyResult.issuer,
                        subject = verifyResult.subject,
                        vcClaims = vcClaimsStr,
                        verificationError = null,
                        status = "✅ VC verified and decoded successfully!"
                    )
                }
                
            } catch (e: Exception) {
                addLog("Proof verification exception: ${e.message}")
                _state.update {
                    it.copy(
                        verifyingProof = false,
                        verificationError = "Verification error: ${e.message}",
                        status = "❌ Verification error: ${e.message}"
                    )
                }
            }
        }
    }
    
    /**
     * Reset state.
     */
    fun reset() {
        _state.value = ReadVcState()
    }
}

