package com.example.keycardapp // Make sure this matches your package name!

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.Tag
import android.os.Build
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.keycardapp.ui.theme.KeycardappTheme

// --- 1. NEW IMPORTS ---
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Keycard Android transport (from .info references)
import im.status.keycard.globalplatform.Crypto
// CommandSet will be used to talk to the Keycard applet
// import im.status.keycard.applet.CommandSet

// QR Code and JWT
import com.nimbusds.jwt.JWTParser
import java.nio.charset.StandardCharsets

// NFC Manager
import com.example.keycardapp.data.nfc.NfcManager

// Keycard Repository
import com.example.keycardapp.domain.repository.KeycardRepository
import com.example.keycardapp.data.repository.KeycardRepositoryImpl

enum class UseCase {
    WRITE_URL_TO_NDEF,
    WRITE_VC_TO_NDEF,
    READ_VC_FROM_NDEF,
    SIGN_DATA_AND_WRITE_TO_NDEF,
    READ_SIGNED_DATA_FROM_NDEF
}

class MainActivity : ComponentActivity() {

    // --- 2. DEFINE YOUR CARD'S SECRETS ---
    private val pairingPassword = "MyNewCardPassword"
    private val pin = "123456"

    // NFC Manager - handles all NFC operations
    private lateinit var nfcManager: NfcManager
    
    // Keycard Repository - handles all Keycard operations
    private lateinit var keycardRepository: KeycardRepository
    
    private val currentUseCase = mutableStateOf<UseCase?>(null)
    private val nfcStatus = mutableStateOf("Waiting for Keycard tap...")
    private val showPinDialog = mutableStateOf(false)
    private val pinInput = mutableStateOf("")
    private var lastTag: Tag? = null
	private var pendingPin: String? = null

    private val showUrlDialog = mutableStateOf(false)
    private val urlInput = mutableStateOf("")
    private var pendingUrl: String? = null
    private var pendingNdefMessage: NdefMessage? = null
    private val writtenHex = mutableStateOf<String?>(null)
    private val uiLogs = mutableStateOf(listOf<String>())
    private var lastVerifiedPin: String? = null
    
    // VC writing state
    private val vcStatus = mutableStateOf("")
    private val vcLogs = mutableStateOf(listOf<String>())
    private val vcWrittenHex = mutableStateOf<String?>(null)
    private val showQrScanner = mutableStateOf(false)
    private val vcJwtInput = mutableStateOf("")
    private val validatingVc = mutableStateOf(false)
    private val vcValidationError = mutableStateOf<String?>(null)
    private var pendingVcJwt: String? = null
    private var pendingVcNdefMessage: NdefMessage? = null
    private var vcWriteRetryCount = 0
    private val MAX_RETRIES = 3
    

    // --- 3. CREATE A COROUTINE SCOPE ---
    // This lets us run background tasks easily
    private val activityScope = CoroutineScope(Dispatchers.Main)

    private fun logUi(msg: String) {
        Log.d("UI", msg)
        uiLogs.value = uiLogs.value + msg
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure BouncyCastle provider is present for secure channel crypto
        try { Crypto.addBouncyCastleProvider() } catch (_: Exception) {}

        // Initialize NFC Manager
        nfcManager = NfcManager(this)
        if (!nfcManager.initialize()) {
            nfcStatus.value = "NFC is not available on this device."
        }
        
        // Initialize Keycard Repository
        keycardRepository = KeycardRepositoryImpl()

		setContent {
            KeycardappTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (val useCase = currentUseCase.value) {
                        null -> UseCaseListScreen(
                            onUseCaseSelected = { selectedUseCase ->
                                currentUseCase.value = selectedUseCase
                                when (selectedUseCase) {
                                    UseCase.WRITE_URL_TO_NDEF -> {
                                        nfcStatus.value = "Please enter your PIN"
                                        showPinDialog.value = true
                                    }
                                    UseCase.WRITE_VC_TO_NDEF -> {
                                        vcStatus.value = "Please enter your PIN"
                                        showPinDialog.value = true
                                    }
                                    UseCase.READ_VC_FROM_NDEF,
                                    UseCase.SIGN_DATA_AND_WRITE_TO_NDEF,
                                    UseCase.READ_SIGNED_DATA_FROM_NDEF -> {
                                        nfcStatus.value = "Coming soon..."
                                    }
                                }
                            }
                        )
                        UseCase.WRITE_URL_TO_NDEF -> WriteUrlToNdefScreen(
                            nfcStatus = nfcStatus.value,
                            logs = uiLogs.value,
                            writtenHex = writtenHex.value,
                            onBack = {
                                currentUseCase.value = null
                                nfcStatus.value = "Waiting for Keycard tap..."
                                uiLogs.value = listOf()
                                writtenHex.value = null
                                lastVerifiedPin = null
                            }
                        )
                        UseCase.WRITE_VC_TO_NDEF -> WriteVcToNdefScreen(
                            vcStatus = vcStatus.value,
                            logs = vcLogs.value,
                            writtenHex = vcWrittenHex.value,
                            validatingVc = validatingVc.value,
                            validationError = vcValidationError.value,
                            onBack = {
                                currentUseCase.value = null
                                vcStatus.value = ""
                                vcLogs.value = listOf()
                                vcWrittenHex.value = null
                                pendingVcJwt = null
                                pendingVcNdefMessage = null
                                vcWriteRetryCount = 0
                                lastVerifiedPin = null
                            },
                            onScanQr = {
                                showQrScanner.value = true
                            }
                        )
                        else -> {
                            Column(modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                            ) {
                                Text("Coming soon: ${useCase.name}", fontSize = 24.sp)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { currentUseCase.value = null }) {
                                    Text("Back")
                                }
                            }
                        }
                    }
                    
					if (showPinDialog.value) {
                        PinDialog(
                            pin = pinInput.value,
                            onPinChange = { pinInput.value = it },
                            onConfirm = {
								showPinDialog.value = false
								pendingPin = pinInput.value
								pinInput.value = ""
								nfcStatus.value = "Now tap your Keycard to verify PIN"
                                enableReaderMode("verify PIN") { tag ->
                                    handleTag(tag)
                                }
                            },
                            onDismiss = { 
                                showPinDialog.value = false
                                if (currentUseCase.value == UseCase.WRITE_URL_TO_NDEF || 
                                    currentUseCase.value == UseCase.WRITE_VC_TO_NDEF) {
                                    currentUseCase.value = null
                                }
                            }
                        )
                    }

                    if (showUrlDialog.value) {
                        UrlDialog(
                            url = urlInput.value,
                            onUrlChange = { urlInput.value = it },
                            onConfirm = {
                                val url = urlInput.value.trim()
                                if (url.isNotEmpty()) {
                                    showUrlDialog.value = false
                                    pendingUrl = url
                                    pendingNdefMessage = buildUriNdef(url)
                                    writtenHex.value = null
                                    nfcStatus.value = "Searching for the card..."
                                    logUi("Waiting for card to write URL: $url")
                                    enableReaderMode("write NDEF") { tag ->
                                        handleTag(tag)
                                    }
                                }
                            },
                            onDismiss = { showUrlDialog.value = false }
                        )
                    }
                    
                    if (showQrScanner.value && currentUseCase.value == UseCase.WRITE_VC_TO_NDEF) {
                        QrScannerDialog(
                            activity = this@MainActivity,
                            jwtInput = vcJwtInput.value,
                            onJwtInputChange = { vcJwtInput.value = it },
                            onScanResult = { qrText ->
                                showQrScanner.value = false
                                vcJwtInput.value = ""
                                handleQrScannedVc(qrText)
                            },
                            onDismiss = { 
                                showQrScanner.value = false
                                vcJwtInput.value = ""
                            }
                        )
                    }
                    
                }
            }
        }
    }
    
    private fun handleQrScannedVc(qrText: String) {
        vcStatus.value = "Validating credential..."
        validatingVc.value = true
        vcValidationError.value = null
        logVc("QR code scanned, validating JWT-VC...")
        
        activityScope.launch(Dispatchers.IO) {
            try {
                // Validate JWT format
                val jwt = try {
                    JWTParser.parse(qrText)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        validatingVc.value = false
                        vcValidationError.value = "Invalid Credential Format: Not a valid JWT"
                        vcStatus.value = "❌ Invalid credential format"
                        logVc("JWT parsing failed: ${e.message}")
                    }
                    return@launch
                }
                
                // Check size (max 1000 bytes for safety)
                val vcBytes = qrText.toByteArray(StandardCharsets.UTF_8)
                val MAX_PAYLOAD_SIZE = 1000
                
                if (vcBytes.size > MAX_PAYLOAD_SIZE) {
                    withContext(Dispatchers.Main) {
                        validatingVc.value = false
                        val sizeKB = String.format("%.1f", vcBytes.size / 1024.0)
                        vcValidationError.value = "Credential Too Large: This credential (${sizeKB}KB) is too large for the Keycard (1KB limit). Please contact your issuer for a more compact credential."
                        vcStatus.value = "❌ Credential too large"
                        logVc("VC size check failed: ${vcBytes.size} bytes > $MAX_PAYLOAD_SIZE bytes")
                    }
                    return@launch
                }
                
                // Create NDEF record with MIME type application/vc+jwt
                val mimeType = "application/vc+jwt"
                val ndefRecord = NdefRecord.createMime(mimeType, vcBytes)
                val ndefMessage = NdefMessage(arrayOf(ndefRecord))
                
                withContext(Dispatchers.Main) {
                    validatingVc.value = false
                    pendingVcJwt = qrText
                    pendingVcNdefMessage = ndefMessage
                    vcWriteRetryCount = 0  // Reset retry count for new write attempt
                    vcStatus.value = "✅ Credential validated. Tap your Keycard to write..."
                    logVc("VC validated successfully. Size: ${vcBytes.size} bytes")
                    enableReaderMode("write VC NDEF") { tag ->
                        handleTag(tag)
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "VC validation failed:", e)
                withContext(Dispatchers.Main) {
                    validatingVc.value = false
                    vcValidationError.value = "Validation Error: ${e.message}"
                    vcStatus.value = "❌ Validation error"
                    logVc("VC validation exception: ${e.message}")
                }
            }
        }
    }
    
    private fun logVc(msg: String) {
        Log.d("VC", msg)
        vcLogs.value = vcLogs.value + msg
    }

    override fun onResume() {
        super.onResume()
        nfcManager.enableForegroundDispatch()
        logUi("Foreground dispatch enabled")
    }

    override fun onPause() {
        super.onPause()
        nfcManager.disableForegroundDispatch()
    }

		override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "New NFC Intent Received!")
        logUi("NFC intent received")

        val tag = nfcManager.handleIntent(intent)
        if (tag != null) {
            handleTag(tag)
        } else {
            logUi("No tag in intent")
        }
    }

    private fun enableReaderMode(reason: String, onTagDiscovered: (Tag) -> Unit) {
        nfcManager.enableReaderMode(reason) { tag ->
            when (currentUseCase.value) {
                UseCase.WRITE_VC_TO_NDEF -> logVc("ReaderMode tag discovered ($reason)")
                else -> logUi("ReaderMode tag discovered ($reason)")
            }
            onTagDiscovered(tag)
        }
        when (currentUseCase.value) {
            UseCase.WRITE_VC_TO_NDEF -> logVc("ReaderMode enabled: $reason")
            else -> logUi("ReaderMode enabled: $reason")
        }
    }

    private fun disableReaderMode() {
        when (currentUseCase.value) {
            UseCase.WRITE_VC_TO_NDEF -> logVc("ReaderMode disabled")
            else -> logUi("ReaderMode disabled")
        }
        nfcManager.disableReaderMode()
    }

    private fun handleTag(tag: Tag) {
        lastTag = tag
        
        val pinToVerify = pendingPin
        if (!pinToVerify.isNullOrEmpty()) {
            logUi("Tag detected for PIN verification")
            nfcStatus.value = "Verifying PIN..."
            activityScope.launch(Dispatchers.IO) {
                try {
                    logUi("Starting PIN verification")
                    val result = keycardRepository.verifyPin(tag, pinToVerify)
                    val success = result.getOrElse { false }
                    withContext(Dispatchers.Main) {
                        result.onFailure { error ->
                            logUi("PIN verification error: ${error.message}")
                        }
                        logUi("PIN verification result: $success")
                        if (success) {
                            lastVerifiedPin = pinToVerify
                            when (currentUseCase.value) {
                                UseCase.WRITE_URL_TO_NDEF -> {
                                    nfcStatus.value = "✅ PIN verified. Enter URL to write to NDEF."
                                    showUrlDialog.value = true
                                }
                                UseCase.WRITE_VC_TO_NDEF -> {
                                    vcStatus.value = "✅ PIN verified. Scan QR code to get credential."
                                    showQrScanner.value = true
                                }
                                else -> {
                                    nfcStatus.value = "✅ PIN verified"
                                }
                            }
                        } else {
                            when (currentUseCase.value) {
                                UseCase.WRITE_VC_TO_NDEF -> {
                                    vcStatus.value = "❌ Wrong PIN"
                                }
                                else -> {
                                    nfcStatus.value = "❌ Wrong PIN"
                                }
                            }
                        }
                        pendingPin = null
                        disableReaderMode()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Keycard operation failed:", e)
                    withContext(Dispatchers.Main) {
                        nfcStatus.value = "Error: ${e.message}"
                        logUi("PIN verification exception: ${e.message}")
                        disableReaderMode()
                    }
                }
            }
            return
        }

        // Handle VC writing
        val vcMessage = pendingVcNdefMessage
        if (vcMessage != null && currentUseCase.value == UseCase.WRITE_VC_TO_NDEF) {
            activityScope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    if (vcWriteRetryCount > 0) {
                        vcStatus.value = "Retrying... (Attempt ${vcWriteRetryCount + 1}/$MAX_RETRIES)"
                        logVc("Retry attempt ${vcWriteRetryCount + 1}/$MAX_RETRIES")
                    } else {
                        vcStatus.value = "Connection established, please don't move the card..."
                        logVc("Card detected. Preparing to write VC NDEF...")
                    }
                }

                val ndefBytes = vcMessage.toByteArray()
                val pinForWrite = lastVerifiedPin
                val secureResult = if (!pinForWrite.isNullOrEmpty()) {
                    val result = keycardRepository.writeNdef(tag, ndefBytes, pairingPassword, pinForWrite)
                    if (result.isSuccess) {
                        Pair(true, null)
                    } else {
                        Pair(false, result.exceptionOrNull()?.message ?: "Write failed")
                    }
                } else Pair(false, "No verified PIN available for secure write")

                if (!secureResult.first) {
                    val reason = secureResult.second ?: "Secure write failed"
                    val isTagLost = reason.contains("TagLostException", ignoreCase = true) || 
                                   reason.contains("Tag was lost", ignoreCase = true) ||
                                   reason.contains("TagLost", ignoreCase = true) ||
                                   reason.contains("IOException", ignoreCase = true) ||
                                   reason.contains("connection", ignoreCase = true)
                    
                    if (isTagLost && vcWriteRetryCount < MAX_RETRIES) {
                        // Tag was lost, retry
                        vcWriteRetryCount++
                        withContext(Dispatchers.Main) {
                            vcStatus.value = "⚠️ Tag lost. Retrying... (Attempt ${vcWriteRetryCount + 1}/$MAX_RETRIES)"
                            logVc("Tag lost. Retrying (${vcWriteRetryCount}/$MAX_RETRIES)...")
                            // Keep pendingVcNdefMessage so it can be retried
                            // Don't disable reader mode, keep listening for the next tap
                        }
                        // Don't return, let the reader mode continue listening
                        return@launch
                    } else {
                        // Failed after retries or non-retryable error
                        withContext(Dispatchers.Main) {
                            if (isTagLost && vcWriteRetryCount >= MAX_RETRIES) {
                                vcStatus.value = "❌ Failed after $MAX_RETRIES attempts. Please try again."
                                logVc("Failed after $MAX_RETRIES retry attempts")
                            } else {
                                vcStatus.value = "❌ Failed to write VC NDEF"
                                logVc("Secure write failed: $reason")
                            }
                            Log.e("VC", "Write failed details: $reason", Throwable())
                            vcWriteRetryCount = 0
                            pendingVcNdefMessage = null
                            disableReaderMode()
                        }
                        return@launch
                    }
                }

                // Success!
                withContext(Dispatchers.Main) {
                    val lengthPrefix = byteArrayOf(
                        ((ndefBytes.size shr 8) and 0xFF).toByte(),
                        (ndefBytes.size and 0xFF).toByte()
                    )
                    val fullPayload = lengthPrefix + ndefBytes
                    val hex = toHex(fullPayload)
                    vcWrittenHex.value = hex
                    if (vcWriteRetryCount > 0) {
                        vcStatus.value = "✅ VC written successfully after ${vcWriteRetryCount + 1} attempts!"
                        logVc("VC NDEF write success after ${vcWriteRetryCount + 1} attempts. Bytes: ${ndefBytes.size}, Hex length: ${hex.length}")
                    } else {
                        vcStatus.value = "✅ VC written successfully!"
                        logVc("VC NDEF write success. Bytes: ${ndefBytes.size}, Hex length: ${hex.length}")
                    }
                    vcWriteRetryCount = 0
                    pendingVcNdefMessage = null
                    disableReaderMode()
                }
            }
            return
        }

        val message = pendingNdefMessage
        if (message != null) {
            activityScope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    nfcStatus.value = "Connection established, please don't move the card..."
                    logUi("Card detected. Preparing to write NDEF...")
                }

                val ndefBytes = message.toByteArray()
                val pinForWrite = lastVerifiedPin
                withContext(Dispatchers.Main) {
                    logUi("Starting secure write: ${ndefBytes.size} bytes, pairing password length: ${pairingPassword.length}")
                }
                val secureResult = if (!pinForWrite.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        logUi("Attempting to write NDEF via Keycard...")
                    }
                    val result = keycardRepository.writeNdef(tag, ndefBytes, pairingPassword, pinForWrite)
                    if (result.isSuccess) {
                        Pair(true, null)
                    } else {
                        val error = result.exceptionOrNull()?.message ?: "Write failed"
                        withContext(Dispatchers.Main) {
                            logUi("Write failed. Error: $error")
                        }
                        Pair(false, error)
                    }
                } else Pair(false, "No verified PIN available for secure write")

                if (!secureResult.first) {
                    withContext(Dispatchers.Main) {
                        nfcStatus.value = "❌ Failed to write NDEF"
                        val reason = secureResult.second ?: "Secure write failed"
                        logUi("Secure write failed: $reason")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    val lengthPrefix = byteArrayOf(
                        ((ndefBytes.size shr 8) and 0xFF).toByte(),
                        (ndefBytes.size and 0xFF).toByte()
                    )
                    val fullPayload = lengthPrefix + ndefBytes
                    val hex = toHex(fullPayload)
                    writtenHex.value = hex
                    nfcStatus.value = "✅ NDEF written."
                    logUi("NDEF write success. Bytes: ${ndefBytes.size}, Hex length: ${hex.length}")
                    pendingNdefMessage = null
                    disableReaderMode()
                }
            }
        }
    }
}

@Composable
fun UseCaseListScreen(onUseCaseSelected: (UseCase) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Keycard POC Use Cases",
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )
        
        UseCaseCard(
            title = "1. Write URL to NDEF",
            description = "Ready - Write any URL to NDEF record on Keycard",
            onClick = { onUseCaseSelected(UseCase.WRITE_URL_TO_NDEF) },
            isReady = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        UseCaseCard(
            title = "2. Write VC to NDEF",
            description = "Ready - Write Verifiable Credential to NDEF",
            onClick = { onUseCaseSelected(UseCase.WRITE_VC_TO_NDEF) },
            isReady = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        UseCaseCard(
            title = "3. Read VC from NDEF",
            description = "Read and verify Verifiable Credential from NDEF",
            onClick = { onUseCaseSelected(UseCase.READ_VC_FROM_NDEF) },
            isReady = false
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        UseCaseCard(
            title = "4. Sign data and write to NDEF",
            description = "Sign data with Keycard and write signed data to NDEF",
            onClick = { onUseCaseSelected(UseCase.SIGN_DATA_AND_WRITE_TO_NDEF) },
            isReady = false
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        UseCaseCard(
            title = "5. Read signed data from NDEF",
            description = "Read and verify signed data from NDEF",
            onClick = { onUseCaseSelected(UseCase.READ_SIGNED_DATA_FROM_NDEF) },
            isReady = false
        )
    }
}

@Composable
fun UseCaseCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    isReady: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 18.sp,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 14.sp,
                style = MaterialTheme.typography.bodyMedium
            )
            if (isReady) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "✓ Ready",
                    fontSize = 12.sp,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
fun WriteUrlToNdefScreen(
    nfcStatus: String,
    logs: List<String>,
    writtenHex: String?,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Button(onClick = onBack) {
            Text("← Back to Use Cases")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        StatusText(status = nfcStatus)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LogsList(logs = logs, writtenHex = writtenHex)
    }
}

@Composable
fun StatusText(status: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = status,
            fontSize = 24.sp
        )
    }
}

@Composable
fun LogsList(logs: List<String>, writtenHex: String?) {
    Column {
        Text(text = "Logs:", fontSize = 18.sp)
        logs.forEach { line ->
            Text(text = line, fontSize = 12.sp)
        }
        if (writtenHex != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "NDEF Hex:", fontSize = 18.sp)
            Text(text = writtenHex, fontSize = 12.sp)
        }
    }
}

@Composable
fun PinDialog(
    pin: String,
    onPinChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Enter PIN") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = onPinChange,
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.padding(top = 8.dp)
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Verify") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun UrlDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Enter URL") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text("URL (e.g. https://example.com)") },
                singleLine = true,
                modifier = Modifier.padding(top = 8.dp)
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Write") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun WriteVcToNdefScreen(
    vcStatus: String,
    logs: List<String>,
    writtenHex: String?,
    validatingVc: Boolean,
    validationError: String?,
    onBack: () -> Unit,
    onScanQr: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Button(onClick = onBack) {
            Text("← Back to Use Cases")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        StatusText(status = vcStatus)
        
        if (validatingVc) {
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Validating credential...")
                }
            }
        }
        
        if (validationError != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFEBEE)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Error",
                        fontSize = 18.sp,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFC62828)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = validationError,
                        fontSize = 14.sp,
                        color = Color(0xFF424242)
                    )
                }
            }
        }
        
        if (!validatingVc && validationError == null && vcStatus.contains("validated")) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Scan QR Code Again")
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LogsList(logs = logs, writtenHex = writtenHex)
    }
}

@Composable
fun QrScannerDialog(
    activity: ComponentActivity,
    jwtInput: String,
    onJwtInputChange: (String) -> Unit,
    onScanResult: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var hasPermission by remember { mutableStateOf(
        ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    ) }
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
        if (!isGranted) {
            onDismiss()
        }
    }
    
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.CAMERA)
        }
    }
    
    if (!hasPermission) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Camera Permission Required") },
            text = { Text("Camera permission is needed to scan QR codes.") },
            confirmButton = {
                Button(onClick = { 
                    launcher.launch(Manifest.permission.CAMERA)
                }) { Text("Grant Permission") }
            },
            dismissButton = {
                Button(onClick = onDismiss) { Text("Cancel") }
            }
        )
        return
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter JWT-VC") },
        text = {
            Column {
                Text(
                    text = "Scan the QR code provided by your credential issuer, or paste the JWT-VC string here.",
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                // For now, use text input for JWT-VC
                // Full QR scanner with CameraX integration can be added later
                OutlinedTextField(
                    value = jwtInput,
                    onValueChange = onJwtInputChange,
                    label = { Text("JWT-VC String") },
                    placeholder = { Text("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Note: Full QR scanner with camera integration coming soon. For now, paste the JWT-VC string here.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val trimmed = jwtInput.trim()
                if (trimmed.isNotEmpty()) {
                    onScanResult(trimmed)
                }
            }) { Text("Validate") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


// verifyPinWithKeycard and writeNdefViaKeycard moved to KeycardRepositoryImpl

private fun buildUriNdef(url: String): NdefMessage {
    val uriRecord = NdefRecord.createUri(url)
    return NdefMessage(arrayOf(uriRecord))
}

private fun writeNdefToTag(tag: Tag, message: NdefMessage): Pair<Boolean, String?> {
    return try {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
            } catch (e: Exception) {
                return Pair(false, "Failed to connect NDEF: ${e.message}")
            }
            val writable = try { ndef.isWritable } catch (e: Exception) { true }
            if (!writable) {
                try { ndef.close() } catch (_: Exception) {}
                return Pair(false, "Tag is read-only")
            }
            val needed = message.toByteArray().size
            val capacity = try { ndef.maxSize } catch (e: Exception) { -1 }
            if (capacity >= 0 && capacity < needed) {
                try { ndef.close() } catch (_: Exception) {}
                return Pair(false, "Insufficient capacity: need $needed, have $capacity")
            }
            return try {
                ndef.writeNdefMessage(message)
                try { ndef.close() } catch (_: Exception) {}
                Pair(true, null)
            } catch (e: Exception) {
                try { ndef.close() } catch (_: Exception) {}
                Pair(false, "Write failed: ${e.message}")
            }
        } else {
            val format = NdefFormatable.get(tag) ?: return Pair(false, "No NDEF tech and not NdefFormatable")
            return try {
                format.connect()
                format.format(message)
                try { format.close() } catch (_: Exception) {}
                Pair(true, null)
            } catch (e: Exception) {
                try { format.close() } catch (_: Exception) {}
                Pair(false, "Format failed: ${e.message}")
            }
        }
    } catch (e: Exception) {
        Pair(false, e.message)
    }
}

private fun toHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        sb.append(String.format("%02x", b))
    }
    return sb.toString()
}

// writeNdefViaKeycard moved to KeycardRepositoryImpl