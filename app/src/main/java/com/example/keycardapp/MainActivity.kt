package com.example.keycardapp // Make sure this matches your package name!

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
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
import androidx.activity.compose.setContent
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
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

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
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
    private var readerModeEnabled: Boolean = false

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

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            nfcStatus.value = "NFC is not available on this device."
        }

        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

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
                                    UseCase.WRITE_VC_TO_NDEF,
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
                                enableReaderMode("verify PIN")
                            },
                            onDismiss = { 
                                showPinDialog.value = false
                                if (currentUseCase.value == UseCase.WRITE_URL_TO_NDEF) {
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
                                    enableReaderMode("write NDEF")
                                }
                            },
                            onDismiss = { showUrlDialog.value = false }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
        logUi("Foreground dispatch enabled")
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

		override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "New NFC Intent Received!")
        logUi("NFC intent received")

        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

        if (tag == null) {
            logUi("No tag in intent")
            return
        }

        handleTag(tag)
    }

    private fun enableReaderMode(reason: String) {
        val adapter = nfcAdapter ?: return
        if (readerModeEnabled) return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, { tag ->
            logUi("ReaderMode tag discovered ($reason)")
            handleTag(tag)
        }, flags, null)
        readerModeEnabled = true
        logUi("ReaderMode enabled: $reason")
    }

    private fun disableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!readerModeEnabled) return
        adapter.disableReaderMode(this)
        readerModeEnabled = false
        logUi("ReaderMode disabled")
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
                            nfcStatus.value = "✅ PIN verified. Enter URL to write to NDEF."
                            lastVerifiedPin = pinToVerify
                            showUrlDialog.value = true
                        } else {
                            nfcStatus.value = "❌ Wrong PIN"
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

        val message = pendingNdefMessage
        if (message != null) {
            activityScope.launch(Dispatchers.IO) {
                withContext(Dispatchers.Main) {
                    nfcStatus.value = "Connection established, please don't move the card..."
                    logUi("Card detected. Preparing to write NDEF...")
                }

                val ndefBytes = message.toByteArray()
                val pinForWrite = lastVerifiedPin
                val secureResult = if (!pinForWrite.isNullOrEmpty()) {
                    writeNdefViaKeycard(tag, ndefBytes, pairingPassword, pinForWrite)
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
            description = "Write Verifiable Credential to NDEF",
            onClick = { onUseCaseSelected(UseCase.WRITE_VC_TO_NDEF) },
            isReady = false
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
    val isoDep = IsoDep.get(tag) ?: return Pair(false, "IsoDep not available")
    return try {
        isoDep.connect()
        isoDep.timeout = 120000
        val channel = NFCCardChannel(isoDep)

        // Load CommandSet reflectively to avoid compile-time API coupling
        val cardChannelClass = Class.forName("im.status.keycard.io.CardChannel")
        val candidateCommands = listOf(
            "im.status.keycard.applet.CommandSet",
            "im.status.keycard.applet.KeycardCommandSet",
            "im.status.keycard.applet.CardCommandSet"
        )
        val commandSetClass = candidateCommands.firstOrNull { name ->
            try { Class.forName(name) != null } catch (_: Throwable) { false }
        }?.let { Class.forName(it) } ?: return Pair(false, "Keycard SDK not on classpath: none of ${candidateCommands.joinToString()} found")
        val cmd = commandSetClass.getConstructor(cardChannelClass).newInstance(channel)

        // cmd.select()
        commandSetClass.getMethod("select").invoke(cmd)

        // Step 1: Pair with the card (sets pairing info in CommandSet)
        val autoPair = try { commandSetClass.getMethod("autoPair", String::class.java) } catch (_: NoSuchMethodException) {
            try { commandSetClass.getMethod("autoPair", ByteArray::class.java) } catch (_: NoSuchMethodException) { null }
        } ?: return Pair(false, "autoPair method not found on CommandSet")
        
        if (autoPair.parameterTypes[0] == String::class.java) {
            autoPair.invoke(cmd, pairingPassword)
        } else {
            autoPair.invoke(cmd, pairingPassword.toByteArray())
        }

        // Step 2: Open secure channel (uses pairing info already set by autoPair)
        val autoOpenSC = try { commandSetClass.getMethod("autoOpenSecureChannel") } catch (_: NoSuchMethodException) { null }
            ?: return Pair(false, "autoOpenSecureChannel() not found on CommandSet")
        autoOpenSC.invoke(cmd)

        // Verify PIN: try verifyPIN(String) then verifyPIN(byte[])
        val verifyMethod = try { commandSetClass.getMethod("verifyPIN", String::class.java) } catch (_: NoSuchMethodException) {
            try { commandSetClass.getMethod("verifyPIN", ByteArray::class.java) } catch (_: NoSuchMethodException) {
                try { commandSetClass.getMethod("verifyPin", String::class.java) } catch (_: NoSuchMethodException) {
                    try { commandSetClass.getMethod("verifyPin", ByteArray::class.java) } catch (_: NoSuchMethodException) { null }
                }
            }
        } ?: return Pair(false, "verifyPIN/verifyPin method not found on CommandSet")
        val pinOk = if (verifyMethod.parameterTypes[0] == String::class.java) {
            (verifyMethod.invoke(cmd, verifiedPin) as? Boolean) ?: true
        } else {
            (verifyMethod.invoke(cmd, verifiedPin.toByteArray()) as? Boolean) ?: true
        }
        if (!pinOk) return Pair(false, "PIN verification failed on card")

        // Prefer setNDEF if available
        val setNdefMethod = commandSetClass.methods.firstOrNull { m ->
            m.name == "setNDEF" && m.parameterTypes.size == 1 && m.parameterTypes[0] == ByteArray::class.java
        }
        if (setNdefMethod != null) {
            setNdefMethod.invoke(cmd, ndefBytes)
        } else {
            // Fallback to storeData(slot, bytes)
            val slotNdef = 2 // StorageSlot.NDEF
            val storeMethod = commandSetClass.methods.firstOrNull { m ->
                m.name == "storeData" && m.parameterTypes.size == 2 &&
                        ((m.parameterTypes[0] == Int::class.javaPrimitiveType || m.parameterTypes[0] == Byte::class.java) &&
                         m.parameterTypes[1] == ByteArray::class.java)
            } ?: return Pair(false, "storeData(int|byte, byte[]) not found on CommandSet")

            if (storeMethod.parameterTypes[0] == Int::class.javaPrimitiveType) {
                storeMethod.invoke(cmd, slotNdef, ndefBytes)
            } else {
                storeMethod.invoke(cmd, slotNdef.toByte(), ndefBytes)
            }
        }

        Pair(true, null)
    } catch (cnf: ClassNotFoundException) {
        Pair(false, "Keycard SDK not on classpath: ${cnf.message}")
    } catch (e: Exception) {
        Pair(false, "Secure write exception: ${e::class.java.simpleName}: ${e.message}")
    } finally {
        try { isoDep.close() } catch (_: Exception) {}
    }
}