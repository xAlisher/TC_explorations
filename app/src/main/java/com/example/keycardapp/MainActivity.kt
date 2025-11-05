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

// QR Code and JWT (now in use cases)

// NFC Manager
import com.example.keycardapp.data.nfc.NfcManager


// Domain Models
import com.example.keycardapp.domain.model.UseCase

// ViewModels
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.example.keycardapp.viewmodel.UseCaseViewModel
import com.example.keycardapp.viewmodel.WriteUrlViewModel
import com.example.keycardapp.viewmodel.WriteVcViewModel
import com.example.keycardapp.viewmodel.ViewModelFactory

class MainActivity : ComponentActivity() {

    // --- 2. DEFINE YOUR CARD'S SECRETS ---
    private val pairingPassword = "MyNewCardPassword"
    private val pin = "123456"

    // NFC Manager - handles all NFC operations
    private lateinit var nfcManager: NfcManager
    
    // ViewModel Factory
    private lateinit var viewModelFactory: ViewModelFactory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure BouncyCastle provider is present for secure channel crypto
        try { Crypto.addBouncyCastleProvider() } catch (_: Exception) {}

        // Initialize NFC Manager
        nfcManager = NfcManager(this)
        if (!nfcManager.initialize()) {
            Log.w("MainActivity", "NFC is not available on this device.")
        }
        
        // Initialize ViewModel Factory
        viewModelFactory = ViewModelFactory(pairingPassword)

		setContent {
            val useCaseViewModel: UseCaseViewModel = viewModel(
                factory = viewModelFactory
            )
            
            val currentUseCase by useCaseViewModel.currentUseCase.collectAsState()
            val writeUrlState by useCaseViewModel.writeUrlViewModel.state.collectAsState()
            val writeVcState by useCaseViewModel.writeVcViewModel.state.collectAsState()
            
            KeycardappTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentUseCase) {
                        null -> UseCaseListScreen(
                            onUseCaseSelected = { selectedUseCase ->
                                useCaseViewModel.navigateToUseCase(selectedUseCase)
                                when (selectedUseCase) {
                                    UseCase.READ_VC_FROM_NDEF,
                                    UseCase.SIGN_DATA_AND_WRITE_TO_NDEF,
                                    UseCase.READ_SIGNED_DATA_FROM_NDEF -> {
                                        // Coming soon
                                    }
                                    else -> {}
                                }
                            }
                        )
                        UseCase.WRITE_URL_TO_NDEF -> WriteUrlToNdefScreen(
                            nfcStatus = writeUrlState.status,
                            logs = writeUrlState.logs,
                            writtenHex = writeUrlState.writtenHex,
                            onBack = {
                                useCaseViewModel.navigateBack()
                            },
                            onTagDiscovered = { tag ->
                                handleTagForWriteUrl(tag, useCaseViewModel.writeUrlViewModel)
                            }
                        )
                        UseCase.WRITE_VC_TO_NDEF -> WriteVcToNdefScreen(
                            vcStatus = writeVcState.status,
                            logs = writeVcState.logs,
                            writtenHex = writeVcState.writtenHex,
                            validatingVc = writeVcState.validatingVc,
                            validationError = writeVcState.validationError,
                            onBack = {
                                useCaseViewModel.navigateBack()
                            },
                            onScanQr = {
                                useCaseViewModel.writeVcViewModel.showQrScanner()
                            },
                            onTagDiscovered = { tag ->
                                handleTagForWriteVc(tag, useCaseViewModel.writeVcViewModel)
                            }
                        )
                        else -> {
                            Column(modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                            ) {
                                Text("Coming soon: ${currentUseCase?.name}", fontSize = 24.sp)
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(onClick = { useCaseViewModel.navigateBack() }) {
                                    Text("Back")
                                }
                            }
                        }
                    }
                    
                    // PIN Dialog for Write URL
                    if (writeUrlState.showPinDialog && currentUseCase == UseCase.WRITE_URL_TO_NDEF) {
                        PinDialog(
                            pin = writeUrlState.pinInput,
                            onPinChange = { useCaseViewModel.writeUrlViewModel.updatePinInput(it) },
                            onConfirm = {
                                useCaseViewModel.writeUrlViewModel.confirmPin()
                                enableReaderMode("verify PIN") { tag ->
                                    handleTagForWriteUrl(tag, useCaseViewModel.writeUrlViewModel)
                                }
                            },
                            onDismiss = { 
                                useCaseViewModel.writeUrlViewModel.dismissPinDialog()
                                if (currentUseCase == UseCase.WRITE_URL_TO_NDEF) {
                                    useCaseViewModel.navigateBack()
                                }
                            }
                        )
                    }

                    // URL Dialog for Write URL
                    if (writeUrlState.showUrlDialog && currentUseCase == UseCase.WRITE_URL_TO_NDEF) {
                        UrlDialog(
                            url = writeUrlState.urlInput,
                            onUrlChange = { useCaseViewModel.writeUrlViewModel.updateUrlInput(it) },
                            onConfirm = {
                                useCaseViewModel.writeUrlViewModel.confirmUrl()
                                    enableReaderMode("write NDEF") { tag ->
                                    handleTagForWriteUrl(tag, useCaseViewModel.writeUrlViewModel)
                                }
                            },
                            onDismiss = { useCaseViewModel.writeUrlViewModel.dismissUrlDialog() }
                        )
                    }
                    
                    // PIN Dialog for Write VC
                    if (writeVcState.showPinDialog && currentUseCase == UseCase.WRITE_VC_TO_NDEF) {
                        PinDialog(
                            pin = writeVcState.pinInput,
                            onPinChange = { useCaseViewModel.writeVcViewModel.updatePinInput(it) },
                            onConfirm = {
                                useCaseViewModel.writeVcViewModel.confirmPin()
                                enableReaderMode("verify PIN") { tag ->
                                    handleTagForWriteVc(tag, useCaseViewModel.writeVcViewModel)
                                }
                            },
                            onDismiss = {
                                useCaseViewModel.writeVcViewModel.dismissPinDialog()
                                if (currentUseCase == UseCase.WRITE_VC_TO_NDEF) {
                                    useCaseViewModel.navigateBack()
                                }
                            }
                        )
                    }
                    
                    // QR Scanner Dialog for Write VC
                    if (writeVcState.showQrScanner && currentUseCase == UseCase.WRITE_VC_TO_NDEF) {
                        QrScannerDialog(
                            activity = this@MainActivity,
                            jwtInput = writeVcState.jwtInput,
                            onJwtInputChange = { useCaseViewModel.writeVcViewModel.updateJwtInput(it) },
                            onScanResult = { qrText ->
                                useCaseViewModel.writeVcViewModel.handleQrScanned(qrText)
                            },
                            onDismiss = { useCaseViewModel.writeVcViewModel.dismissQrScanner() }
                        )
                    }
                    
                    // Enable reader mode when VC is ready for writing
                    LaunchedEffect(writeVcState.pendingVcJwt, writeVcState.validatingVc) {
                        if (currentUseCase == UseCase.WRITE_VC_TO_NDEF && 
                            writeVcState.pendingVcJwt != null && 
                            !writeVcState.validatingVc) {
                            enableReaderMode("write VC NDEF") { tag ->
                                handleTagForWriteVc(tag, useCaseViewModel.writeVcViewModel)
                            }
                        }
                    }
                    
                }
            }
        }
    }
    

    override fun onResume() {
        super.onResume()
        nfcManager.enableForegroundDispatch()
        Log.d("MainActivity", "Foreground dispatch enabled")
    }

    override fun onPause() {
        super.onPause()
        nfcManager.disableForegroundDispatch()
    }

		override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d("MainActivity", "New NFC Intent Received!")

        val tag = nfcManager.handleIntent(intent)
        if (tag != null) {
            // Route to ViewModels based on current use case
            // Note: This requires ViewModels to be accessible, but they're in Compose scope
            // For now, we'll handle this in the UI composables via onTagDiscovered callbacks
            // NFC events will be handled through reader mode callbacks
        }
    }

    private fun enableReaderMode(reason: String, onTagDiscovered: (Tag) -> Unit) {
        nfcManager.enableReaderMode(reason) { tag ->
            Log.d("MainActivity", "ReaderMode tag discovered ($reason)")
            onTagDiscovered(tag)
        }
        Log.d("MainActivity", "ReaderMode enabled: $reason")
    }

    private fun disableReaderMode() {
        Log.d("MainActivity", "ReaderMode disabled")
        nfcManager.disableReaderMode()
    }

    /**
     * Handle tag for Write URL use case.
     */
    private fun handleTagForWriteUrl(tag: Tag, viewModel: WriteUrlViewModel) {
        val state = viewModel.state.value
        
        // Check if we need to verify PIN
        if (state.pendingPin != null) {
            viewModel.verifyPin(tag) { 
                // Disable reader mode after operation completes
                        disableReaderMode()
                    }
            // Don't disable reader mode immediately - wait for operation to complete
            return
        }

        // Check if we need to write URL
        if (state.pendingUrl != null) {
            viewModel.writeUrl(tag) {
                // Disable reader mode after operation completes
                            disableReaderMode()
                        }
            // Don't disable reader mode immediately - wait for operation to complete
            return
        }
    }

    /**
     * Handle tag for Write VC use case.
     */
    private fun handleTagForWriteVc(tag: Tag, viewModel: WriteVcViewModel) {
        val state = viewModel.state.value
        
        // Check if we need to verify PIN
        if (state.pendingPin != null) {
            viewModel.verifyPin(tag) {
                // Disable reader mode after operation completes
                disableReaderMode()
            }
            // Don't disable reader mode immediately - wait for operation to complete
            return
        }
        
        // Check if we need to write VC
        if (state.pendingVcJwt != null) {
            viewModel.writeVc(tag) { shouldKeepEnabled ->
                // Disable reader mode after operation completes, unless retry is needed
                if (!shouldKeepEnabled) {
                    disableReaderMode()
                }
            }
            // Don't disable reader mode immediately - wait for operation to complete
            return
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
    onBack: () -> Unit,
    onTagDiscovered: (Tag) -> Unit
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
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
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
    onScanQr: () -> Unit,
    onTagDiscovered: (Tag) -> Unit
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
// buildUriNdef moved to WriteUrlUseCase
// toHex moved to WriteUrlUseCase and WriteVcUseCase

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

// writeNdefViaKeycard moved to KeycardRepositoryImpl
// toHex moved to WriteUrlUseCase and WriteVcUseCase