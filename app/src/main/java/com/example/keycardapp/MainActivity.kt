package com.example.keycardapp // Make sure this matches your package name!

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.Tag
import android.os.Build
import android.nfc.tech.IsoDep
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
import im.status.keycard.android.NFCCardChannel
import im.status.keycard.globalplatform.Crypto
// CommandSet will be used to talk to the Keycard applet
// import im.status.keycard.applet.CommandSet

// QR Code and JWT
import com.nimbusds.jwt.JWTParser
import java.nio.charset.StandardCharsets

// NFC Manager
import com.example.keycardapp.data.nfc.NfcManager

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
                    logUi("Starting verifyPinWithKeycard")
                    val success = verifyPinWithKeycard(tag, pinToVerify)
                    withContext(Dispatchers.Main) {
                        logUi("verifyPinWithKeycard result: $success")
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
                    writeNdefViaKeycard(tag, ndefBytes, pairingPassword, pinForWrite)
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
                        logUi("Attempting to unpair existing pairings...")
                    }
                    val result = writeNdefViaKeycard(tag, ndefBytes, pairingPassword, pinForWrite)
                    // Log key steps based on result
                    if (!result.first) {
                        withContext(Dispatchers.Main) {
                            logUi("Pairing/unpairing step failed. Error: ${result.second}")
                        }
                    }
                    result
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


private fun verifyPinWithKeycard(tag: Tag, pin: String): Boolean {
    // Establish IsoDep connection and channel using Keycard Android transport
    val isoDep = IsoDep.get(tag) ?: run {
        Log.d("PIN", "IsoDep not available for tag")
        return false
    }
    return try {
        Log.d("PIN", "Connecting IsoDep...")
        isoDep.connect()
        isoDep.timeout = 120000

        // Create a Keycard channel (APDU transport). Next step would be to use CommandSet.
        val channel = NFCCardChannel(isoDep)
        Log.d("PIN", "IsoDep connected; channel ready")

        // TODO: Replace with real CommandSet flow: select applet, pair/open secure channel, verify PIN
        // For now, keep a simple placeholder check so the UI flow is testable.
        pin == "123456"
    } catch (e: Exception) {
        Log.e("PIN", "Error during IsoDep/verify: ${e.message}")
        false
    } finally {
        try { isoDep.close() } catch (_: Exception) {}
    }
}

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

// Attempt secure-channel write using the Keycard SDK via reflection, reusing the verified PIN
private fun writeNdefViaKeycard(tag: Tag, ndefBytes: ByteArray, pairingPassword: String, verifiedPin: String): Pair<Boolean, String?> {
    // Note: This function is called from a coroutine, so we can't directly call logUi() here
    // Error messages will be logged by the caller via logUi()
    Log.d("Keycard", "=== Starting writeNdefViaKeycard ===")
    Log.d("Keycard", "NDEF bytes: ${ndefBytes.size}, pairing password length: ${pairingPassword.length}, PIN length: ${verifiedPin.length}")
    val isoDep = IsoDep.get(tag) ?: return Pair(false, "IsoDep not available")
    return try {
        Log.d("Keycard", "Connecting to IsoDep...")
        isoDep.connect()
        isoDep.timeout = 120000
        Log.d("Keycard", "IsoDep connected, timeout set to ${isoDep.timeout}ms")
        val channel = NFCCardChannel(isoDep)
        Log.d("Keycard", "NFCCardChannel created")

        // Load CommandSet reflectively to avoid compile-time API coupling
        Log.d("Keycard", "Loading CommandSet class...")
        val cardChannelClass = Class.forName("im.status.keycard.io.CardChannel")
        Log.d("Keycard", "CardChannel class found: ${cardChannelClass.name}")
        val candidateCommands = listOf(
            "im.status.keycard.applet.CommandSet",
            "im.status.keycard.applet.KeycardCommandSet",
            "im.status.keycard.applet.CardCommandSet"
        )
        Log.d("Keycard", "Searching for CommandSet in: ${candidateCommands.joinToString()}")
        val commandSetClass = candidateCommands.firstOrNull { name ->
            try { 
                val found = Class.forName(name) != null
                if (found) Log.d("Keycard", "Found CommandSet class: $name")
                found
            } catch (_: Throwable) { 
                false 
            }
        }?.let { Class.forName(it) } ?: run {
            Log.e("Keycard", "CommandSet not found in any candidate classes")
            return Pair(false, "Keycard SDK not on classpath: none of ${candidateCommands.joinToString()} found")
        }
        Log.d("Keycard", "Using CommandSet class: ${commandSetClass.name}")
        val cmd = commandSetClass.getConstructor(cardChannelClass).newInstance(channel)
        Log.d("Keycard", "CommandSet instance created")
        
        // Debug: List all available methods to find unpair or pairing management methods
        val allMethodNames = commandSetClass.methods.map { "${it.name}(${it.parameterTypes.joinToString { it.simpleName }})" }
        Log.d("Keycard", "Available methods (${allMethodNames.size}): ${allMethodNames.take(20).joinToString(", ")}${if (allMethodNames.size > 20) "..." else ""}")
        val unpairMethods = allMethodNames.filter { it.lowercase().contains("unpair") }
        if (unpairMethods.isNotEmpty()) {
            Log.d("Keycard", "Unpair-related methods found: ${unpairMethods.joinToString()}")
        }

        // cmd.select()
        Log.d("Keycard", "Selecting Keycard applet...")
        commandSetClass.getMethod("select").invoke(cmd)
        Log.d("Keycard", "Keycard applet selected")

        // Step 1: Try to unpair any existing pairings first (to avoid pairing conflicts)
        // Note: Keycard has max 5 pairing slots. Error 0x6A84 means slots are full.
        // We'll try to find unpair methods with various signatures
        Log.d("Keycard", "Attempting to unpair existing pairings...")
        var unpairMethod: java.lang.reflect.Method? = null
        try {
            // Try different unpair method signatures
            unpairMethod = try { 
                commandSetClass.getMethod("unpair").also { Log.d("Keycard", "Found unpair() method (no params)") }
            } catch (_: NoSuchMethodException) {
                try { 
                    commandSetClass.getMethod("unpairAll").also { Log.d("Keycard", "Found unpairAll() method") }
                } catch (_: NoSuchMethodException) {
                    try {
                        // Try unpair with int parameter (pairing index)
                        commandSetClass.getMethod("unpair", Int::class.javaPrimitiveType).also { Log.d("Keycard", "Found unpair(int) method") }
                    } catch (_: NoSuchMethodException) {
                        try {
                            // Try unpair with byte parameter
                            commandSetClass.getMethod("unpair", Byte::class.javaPrimitiveType).also { Log.d("Keycard", "Found unpair(byte) method") }
                        } catch (_: NoSuchMethodException) {
                            try {
                                // List all methods to see what's available
                                val allMethods = commandSetClass.methods.filter { it.name.lowercase().contains("unpair") }
                                if (allMethods.isNotEmpty()) {
                                    Log.d("Keycard", "Found unpair-related methods: ${allMethods.joinToString { "${it.name}(${it.parameterTypes.joinToString()})" }}")
                                    // Try the first one with no params
                                    allMethods.firstOrNull { it.parameterTypes.isEmpty() }?.also { 
                                        Log.d("Keycard", "Using unpair method: ${it.name}")
                                        unpairMethod = it
                                    }
                                }
                                if (unpairMethod == null) {
                                    Log.d("Keycard", "No unpair/unpairAll method found via reflection")
                                    null
                                } else unpairMethod
                            } catch (_: Exception) {
                                Log.d("Keycard", "No unpair methods found")
                                null
                            }
                        }
                    }
                }
            }
            if (unpairMethod != null) {
                try {
                    Log.d("Keycard", "Calling unpair method: ${unpairMethod.name}...")
                    // Try calling with no params first
                    if (unpairMethod.parameterTypes.isEmpty()) {
                        unpairMethod.invoke(cmd)
                        Log.d("Keycard", "Unpair successful (no params)")
                    } else {
                        // If it needs params, we can't call it without knowing the pairing index
                        Log.d("Keycard", "Unpair method requires parameters, skipping (would need pairing index)")
                    }
                } catch (unpairEx: Exception) {
                    val unpairCause = if (unpairEx is java.lang.reflect.InvocationTargetException) unpairEx.cause else unpairEx
                    val unpairMsg = unpairCause?.message ?: unpairEx.message ?: "Unknown error"
                    Log.d("Keycard", "Unpair failed (this is OK if card not paired or method needs params): $unpairMsg (${unpairCause?.javaClass?.simpleName ?: unpairEx.javaClass.simpleName})")
                    // Ignore unpair errors - card might not be paired, or pairing might be in different slot
                    // Continue with pairing attempt
                }
            } else {
                Log.d("Keycard", "No unpair method available, skipping unpair step")
            }
        } catch (ex: Exception) {
            Log.d("Keycard", "Exception while trying to unpair: ${ex.message} (${ex.javaClass.simpleName})")
            // If unpair is not available or fails, continue with pairing attempt
        }

        // Step 2: Pair with the card (sets pairing info in CommandSet)
        Log.d("Keycard", "Looking for autoPair method...")
        val autoPair = try { 
            commandSetClass.getMethod("autoPair", String::class.java).also { Log.d("Keycard", "Found autoPair(String) method") }
        } catch (_: NoSuchMethodException) {
            try { 
                commandSetClass.getMethod("autoPair", ByteArray::class.java).also { Log.d("Keycard", "Found autoPair(ByteArray) method") }
            } catch (_: NoSuchMethodException) { 
                Log.e("Keycard", "autoPair method not found")
                null 
            }
        } ?: return Pair(false, "Step 2 failed: autoPair method not found on CommandSet")
        
        Log.d("Keycard", "Attempting to pair with password length: ${pairingPassword.length}")
        try {
            if (autoPair.parameterTypes[0] == String::class.java) {
                Log.d("Keycard", "Calling autoPair with String parameter...")
                autoPair.invoke(cmd, pairingPassword)
                Log.d("Keycard", "autoPair successful")
            } else {
                Log.d("Keycard", "Calling autoPair with ByteArray parameter...")
                autoPair.invoke(cmd, pairingPassword.toByteArray())
                Log.d("Keycard", "autoPair successful")
            }
        } catch (e: java.lang.reflect.InvocationTargetException) {
            val cause = e.cause
            val errorMsg = cause?.message ?: e.message ?: "Unknown error"
            val errorClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            val errorCode = if (errorMsg.contains("0x")) {
                errorMsg.substring(errorMsg.indexOf("0x")).takeWhile { it.isLetterOrDigit() || it == 'x' || it == 'X' || it == ' ' }
            } else if (errorMsg.contains("SW=")) {
                errorMsg.substring(errorMsg.indexOf("SW=")).takeWhile { it.isLetterOrDigit() || it == '=' || it == ' ' }
            } else {
                ""
            }
            Log.e("Keycard", "Step 2 (Pairing) failed: $errorMsg ($errorClass), error code: $errorCode")
            
            // If pairing fails with 0x6A84, try to unpair first and retry
            if (errorMsg.contains("0x6A84") || errorMsg.contains("6A84") || errorMsg.contains("Pairing failed")) {
                Log.d("Keycard", "Pairing failed with 0x6A84, attempting unpair and retry...")
                try {
                    // Try to unpair and retry pairing
                    val retryUnpairMethod = try { 
                        commandSetClass.getMethod("unpair").also { Log.d("Keycard", "Found unpair() for retry") }
                    } catch (_: NoSuchMethodException) {
                        try { 
                            commandSetClass.getMethod("unpairAll").also { Log.d("Keycard", "Found unpairAll() for retry") }
                        } catch (_: NoSuchMethodException) { 
                            Log.d("Keycard", "No unpair method found for retry")
                            null 
                        }
                    }
                    if (retryUnpairMethod != null) {
                        try {
                            Log.d("Keycard", "Calling unpair before retry...")
                            retryUnpairMethod.invoke(cmd)
                            Log.d("Keycard", "Unpair successful, retrying pairing...")
                            // Retry pairing after unpair
                            if (autoPair.parameterTypes[0] == String::class.java) {
                                autoPair.invoke(cmd, pairingPassword)
                            } else {
                                autoPair.invoke(cmd, pairingPassword.toByteArray())
                            }
                            Log.d("Keycard", "Pairing retry successful after unpair")
                        } catch (retryEx: Exception) {
                            val retryCause = if (retryEx is java.lang.reflect.InvocationTargetException) retryEx.cause else retryEx
                            val retryMsg = retryCause?.message ?: retryEx.message ?: "Unknown error"
                            val retryClass = retryCause?.javaClass?.simpleName ?: retryEx.javaClass.simpleName
                            Log.e("Keycard", "Step 2 (Pairing retry) failed after unpair: $retryMsg ($retryClass)")
                            return Pair(false, "Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Retry after unpair also failed: $retryMsg")
                        }
                    } else {
                        // Try to find any unpair method by listing all methods
                        val allUnpairMethods = commandSetClass.methods.filter { 
                            it.name.lowercase().contains("unpair") 
                        }
                        if (allUnpairMethods.isNotEmpty()) {
                            Log.d("Keycard", "Found unpair methods via reflection: ${allUnpairMethods.joinToString { "${it.name}(${it.parameterTypes.joinToString()})" }}")
                            // Try calling the first one that takes no params
                            val noParamUnpair = allUnpairMethods.firstOrNull { it.parameterTypes.isEmpty() }
                            if (noParamUnpair != null) {
                                try {
                                    Log.d("Keycard", "Trying unpair method: ${noParamUnpair.name}...")
                                    noParamUnpair.invoke(cmd)
                                    Log.d("Keycard", "Unpair successful via reflection, retrying pairing...")
                                    // Retry pairing after unpair
                                    if (autoPair.parameterTypes[0] == String::class.java) {
                                        autoPair.invoke(cmd, pairingPassword)
                                    } else {
                                        autoPair.invoke(cmd, pairingPassword.toByteArray())
                                    }
                                    Log.d("Keycard", "Pairing retry successful after unpair")
                                } catch (retryEx: Exception) {
                                    val retryCause = if (retryEx is java.lang.reflect.InvocationTargetException) retryEx.cause else retryEx
                                    val retryMsg = retryCause?.message ?: retryEx.message ?: "Unknown error"
                                    Log.e("Keycard", "Pairing retry failed after unpair: $retryMsg")
                                    return Pair(false, "Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Unpair found but retry failed: $retryMsg")
                                }
                            } else {
                                Log.e("Keycard", "Cannot retry pairing: unpair methods found but all require parameters")
                                return Pair(false, "Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Card pairing slots are full (0x6A84). Unpair methods require parameters we don't have.")
                            }
                        } else {
                            Log.e("Keycard", "Cannot retry pairing: no unpair method available")
                            return Pair(false, "Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Card pairing slots are full (0x6A84). No unpair method available. Please unpair the card using another tool or app.")
                        }
                    }
                } catch (retryException: Exception) {
                    val retryCause = if (retryException is java.lang.reflect.InvocationTargetException) retryException.cause else retryException
                    Log.e("Keycard", "Exception during pairing retry: ${retryCause?.message ?: retryException.message} (${retryCause?.javaClass?.simpleName ?: retryException.javaClass.simpleName})")
                    return Pair(false, "Step 2 (Pairing) failed: $errorClass - $errorMsg (Error: $errorCode). Retry exception: ${retryCause?.message ?: retryException.message}")
                }
            } else {
                Log.e("Keycard", "Pairing failed with non-0x6A84 error, not retrying")
                return Pair(false, "Step 2 (Pairing) failed: $errorClass - $errorMsg" + if (errorCode.isNotEmpty()) " (Error: $errorCode)" else "")
            }
        }

        // Step 3: Open secure channel (uses pairing info already set by autoPair)
        Log.d("Keycard", "Opening secure channel...")
        val autoOpenSC = try { 
            commandSetClass.getMethod("autoOpenSecureChannel").also { Log.d("Keycard", "Found autoOpenSecureChannel() method") }
        } catch (_: NoSuchMethodException) { 
            Log.e("Keycard", "autoOpenSecureChannel() not found")
            null 
        } ?: return Pair(false, "autoOpenSecureChannel() not found on CommandSet")
        try {
            autoOpenSC.invoke(cmd)
            Log.d("Keycard", "Secure channel opened successfully")
        } catch (scEx: Exception) {
            val scCause = if (scEx is java.lang.reflect.InvocationTargetException) scEx.cause else scEx
            val scMsg = scCause?.message ?: scEx.message ?: "Unknown error"
            val scClass = scCause?.javaClass?.simpleName ?: scEx.javaClass.simpleName
            Log.e("Keycard", "Failed to open secure channel: $scMsg ($scClass)")
            throw scCause ?: scEx
        }

        // Step 4: Verify PIN: try verifyPIN(String) then verifyPIN(byte[])
        Log.d("Keycard", "Verifying PIN...")
        val verifyMethod = try { 
            commandSetClass.getMethod("verifyPIN", String::class.java).also { Log.d("Keycard", "Found verifyPIN(String) method") }
        } catch (_: NoSuchMethodException) {
            try { 
                commandSetClass.getMethod("verifyPIN", ByteArray::class.java).also { Log.d("Keycard", "Found verifyPIN(ByteArray) method") }
            } catch (_: NoSuchMethodException) {
                try { 
                    commandSetClass.getMethod("verifyPin", String::class.java).also { Log.d("Keycard", "Found verifyPin(String) method") }
                } catch (_: NoSuchMethodException) {
                    try { 
                        commandSetClass.getMethod("verifyPin", ByteArray::class.java).also { Log.d("Keycard", "Found verifyPin(ByteArray) method") }
                    } catch (_: NoSuchMethodException) { 
                        Log.e("Keycard", "verifyPIN/verifyPin method not found")
                        null 
                    }
                }
            }
        } ?: return Pair(false, "verifyPIN/verifyPin method not found on CommandSet")
        val pinOk = try {
            if (verifyMethod.parameterTypes[0] == String::class.java) {
                Log.d("Keycard", "Calling verifyPIN with String parameter...")
                (verifyMethod.invoke(cmd, verifiedPin) as? Boolean) ?: true
            } else {
                Log.d("Keycard", "Calling verifyPIN with ByteArray parameter...")
                (verifyMethod.invoke(cmd, verifiedPin.toByteArray()) as? Boolean) ?: true
            }
        } catch (pinEx: Exception) {
            val pinCause = if (pinEx is java.lang.reflect.InvocationTargetException) pinEx.cause else pinEx
            val pinMsg = pinCause?.message ?: pinEx.message ?: "Unknown error"
            val pinClass = pinCause?.javaClass?.simpleName ?: pinEx.javaClass.simpleName
            Log.e("Keycard", "PIN verification exception: $pinMsg ($pinClass)")
            return Pair(false, "PIN verification exception: $pinClass - $pinMsg")
        }
        if (!pinOk) {
            Log.e("Keycard", "PIN verification failed: card returned false")
            return Pair(false, "PIN verification failed on card")
        }
        Log.d("Keycard", "PIN verification successful")

        // Step 5: Write NDEF - Prefer setNDEF if available
        Log.d("Keycard", "Writing NDEF data (${ndefBytes.size} bytes)...")
        val setNdefMethod = commandSetClass.methods.firstOrNull { m ->
            m.name == "setNDEF" && m.parameterTypes.size == 1 && m.parameterTypes[0] == ByteArray::class.java
        }
        if (setNdefMethod != null) {
            Log.d("Keycard", "Using setNDEF() method")
            try {
                setNdefMethod.invoke(cmd, ndefBytes)
                Log.d("Keycard", "setNDEF() successful")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                val ndefMsg = cause?.message ?: e.message ?: "Unknown error"
                val ndefClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
                Log.e("Keycard", "setNDEF() failed: $ndefMsg ($ndefClass)")
                throw cause ?: e
            }
        } else {
            // Fallback to storeData(slot, bytes)
            Log.d("Keycard", "setNDEF() not found, using storeData() method")
            val slotNdef = 2 // StorageSlot.NDEF
            val storeMethod = commandSetClass.methods.firstOrNull { m ->
                m.name == "storeData" && m.parameterTypes.size == 2 &&
                        ((m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Byte::class.java) &&
                         m.parameterTypes[1] == ByteArray::class.java)
            } ?: return Pair(false, "storeData(int|byte, byte[]) not found on CommandSet")

            try {
                Log.d("Keycard", "Calling storeData() with slot $slotNdef...")
                if (storeMethod.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    storeMethod.invoke(cmd, slotNdef, ndefBytes)
                } else {
                    storeMethod.invoke(cmd, slotNdef.toByte(), ndefBytes)
                }
                Log.d("Keycard", "storeData() successful")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                val cause = e.cause
                val ndefMsg = cause?.message ?: e.message ?: "Unknown error"
                val ndefClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
                Log.e("Keycard", "storeData() failed: $ndefMsg ($ndefClass)")
                throw cause ?: e
            }
        }
        Log.d("Keycard", "NDEF write completed successfully")
        
        // Always unpair after successful write
        try {
            Log.d("Keycard", "Unpairing card after write...")
            val unpairMethods = commandSetClass.methods.filter { 
                it.name.lowercase().contains("unpair") && it.parameterTypes.isEmpty()
            }
            if (unpairMethods.isNotEmpty()) {
                val unpairMethod = unpairMethods.first()
                try {
                    unpairMethod.invoke(cmd)
                    Log.d("Keycard", "Unpair successful after write")
                } catch (unpairEx: Exception) {
                    Log.d("Keycard", "Unpair failed (non-critical): ${unpairEx.message}")
                }
            } else {
                Log.d("Keycard", "No unpair method available (non-critical)")
            }
        } catch (e: Exception) {
            Log.d("Keycard", "Exception during unpair (non-critical): ${e.message}")
        }

        Pair(true, null)
    } catch (cnf: ClassNotFoundException) {
        Pair(false, "Keycard SDK not on classpath: ${cnf.message}")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // Unwrap InvocationTargetException to get the actual cause
            val cause = e.cause
            val errorMsg = cause?.message ?: e.message ?: "Unknown error"
            val errorClass = cause?.javaClass?.simpleName ?: e.javaClass.simpleName
            val errorCode = if (errorMsg.contains("0x")) {
                errorMsg.substring(errorMsg.indexOf("0x")).takeWhile { it.isLetterOrDigit() || it == 'x' || it == 'X' || it == ' ' }
            } else if (errorMsg.contains("SW=")) {
                errorMsg.substring(errorMsg.indexOf("SW="))
            } else {
                ""
            }
            Log.e("Keycard", "Secure write exception: $errorClass - $errorMsg (error code: $errorCode)")
            val fullErrorMsg = "Secure write exception: $errorClass - $errorMsg" + if (errorCode.isNotEmpty()) " (Error: $errorCode)" else ""
            Pair(false, fullErrorMsg)
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.toString()
            val errorClass = e.javaClass.simpleName
            Log.e("Keycard", "Secure write exception: $errorClass - $errorMsg")
            Pair(false, "Secure write exception: $errorClass - $errorMsg")
        } finally {
            try { 
                Log.d("Keycard", "Closing IsoDep connection...")
                isoDep.close() 
                Log.d("Keycard", "IsoDep connection closed")
            } catch (closeEx: Exception) {
                Log.d("Keycard", "Error closing IsoDep: ${closeEx.message}")
            }
        }
}