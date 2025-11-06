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
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.example.keycardapp.ui.theme.KeycardappTheme
import kotlinx.coroutines.delay

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import com.example.keycardapp.viewmodel.UseCaseViewModel
import com.example.keycardapp.viewmodel.WriteUrlViewModel
import com.example.keycardapp.viewmodel.WriteVcViewModel
import com.example.keycardapp.viewmodel.ReadVcViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // NFC Manager - handles all NFC operations
    private lateinit var nfcManager: NfcManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ensure BouncyCastle provider is present for secure channel crypto
        try { Crypto.addBouncyCastleProvider() } catch (_: Exception) {}

        // Initialize NFC Manager
        nfcManager = NfcManager(this)
        if (!nfcManager.initialize()) {
            Log.w("MainActivity", "NFC is not available on this device.")
        }

		setContent {
            val useCaseViewModel: UseCaseViewModel = hiltViewModel()
            val writeUrlViewModel: WriteUrlViewModel = hiltViewModel()
            val writeVcViewModel: WriteVcViewModel = hiltViewModel()
            val readVcViewModel: ReadVcViewModel = hiltViewModel()
            
            val currentUseCase by useCaseViewModel.currentUseCase.collectAsState()
            val writeUrlState by writeUrlViewModel.state.collectAsState()
            val writeVcState by writeVcViewModel.state.collectAsState()
            val readVcState by readVcViewModel.state.collectAsState()
            
            // Trigger haptic when connection is established
            // Use a key to track previous status to avoid multiple triggers
            var previousWriteUrlStatus by remember { mutableStateOf("") }
            LaunchedEffect(writeUrlState.status) {
                if (writeUrlState.status.contains("Connection established", ignoreCase = true) &&
                    !previousWriteUrlStatus.contains("Connection established", ignoreCase = true)) {
                    Log.d("MainActivity", "Connection established detected for Write URL, triggering haptic")
                    triggerHaptic()
                }
                previousWriteUrlStatus = writeUrlState.status
            }
            
            var previousWriteVcStatus by remember { mutableStateOf("") }
            LaunchedEffect(writeVcState.status) {
                if (writeVcState.status.contains("Connection established", ignoreCase = true) &&
                    !previousWriteVcStatus.contains("Connection established", ignoreCase = true)) {
                    Log.d("MainActivity", "Connection established detected for Write VC, triggering haptic")
                    triggerHaptic()
                }
                previousWriteVcStatus = writeVcState.status
            }
            
            // Handle use case navigation and initialization
            LaunchedEffect(currentUseCase) {
                when (currentUseCase) {
                    UseCase.WRITE_URL_TO_NDEF -> {
                        writeUrlViewModel.reset()
                        writeUrlViewModel.showPinDialog()
                    }
                    UseCase.WRITE_VC_TO_NDEF -> {
                        writeVcViewModel.reset()
                        writeVcViewModel.showPinDialog()
                    }
                    UseCase.READ_VC_FROM_NDEF -> {
                        readVcViewModel.reset()
                        // No PIN required for reading NDEF
                    }
                    else -> {
                        // Coming soon
                    }
                }
            }
            
            KeycardappTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (currentUseCase) {
                        null -> UseCaseListScreen(
                            onUseCaseSelected = { selectedUseCase ->
                                useCaseViewModel.navigateToUseCase(selectedUseCase)
                                when (selectedUseCase) {
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
                                writeUrlViewModel.reset()
                                useCaseViewModel.navigateBack()
                            },
                            onTagDiscovered = { tag ->
                                handleTagForWriteUrl(tag, writeUrlViewModel)
                            }
                        )
                        UseCase.WRITE_VC_TO_NDEF -> WriteVcToNdefScreen(
                            vcStatus = writeVcState.status,
                            logs = writeVcState.logs,
                            writtenHex = writeVcState.writtenHex,
                            validatingVc = writeVcState.validatingVc,
                            validationError = writeVcState.validationError,
                            onBack = {
                                writeVcViewModel.reset()
                                useCaseViewModel.navigateBack()
                            },
                            onScanQr = {
                                writeVcViewModel.showQrScanner()
                            },
                            onTagDiscovered = { tag ->
                                handleTagForWriteVc(tag, writeVcViewModel)
                            }
                        )
                        UseCase.READ_VC_FROM_NDEF -> ReadVcFromNdefScreen(
                            vcStatus = readVcState.status,
                            logs = readVcState.logs,
                            readingVc = readVcState.readingVc,
                            verifyingProof = readVcState.verifyingProof,
                            jwtVc = readVcState.jwtVc,
                            decodedPayload = readVcState.decodedPayload,
                            issuer = readVcState.issuer,
                            subject = readVcState.subject,
                            vcClaims = readVcState.vcClaims,
                            verificationError = readVcState.verificationError,
                            onBack = {
                                readVcViewModel.reset()
                                useCaseViewModel.navigateBack()
                            },
                            onTagDiscovered = { tag ->
                                handleTagForReadVc(tag, readVcViewModel)
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
                            onPinChange = { writeUrlViewModel.updatePinInput(it) },
                            onConfirm = {
                                writeUrlViewModel.confirmPin()
                                enableReaderMode("verify PIN", { tag ->
                                    handleTagForWriteUrl(tag, writeUrlViewModel)
                                })
                            },
                            onDismiss = { 
                                writeUrlViewModel.dismissPinDialog()
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
                            onUrlChange = { writeUrlViewModel.updateUrlInput(it) },
                            onConfirm = {
                                writeUrlViewModel.confirmUrl()
                                enableReaderMode("write NDEF", { tag ->
                                    handleTagForWriteUrl(tag, writeUrlViewModel)
                                })
                            },
                            onDismiss = { writeUrlViewModel.dismissUrlDialog() }
                        )
                    }
                    
                    // PIN Dialog for Write VC
                    if (writeVcState.showPinDialog && currentUseCase == UseCase.WRITE_VC_TO_NDEF) {
                        PinDialog(
                            pin = writeVcState.pinInput,
                            onPinChange = { writeVcViewModel.updatePinInput(it) },
                            onConfirm = {
                                writeVcViewModel.confirmPin()
                                enableReaderMode("verify PIN", { tag ->
                                    handleTagForWriteVc(tag, writeVcViewModel)
                                })
                            },
                            onDismiss = {
                                writeVcViewModel.dismissPinDialog()
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
                            onJwtInputChange = { writeVcViewModel.updateJwtInput(it) },
                            onScanResult = { qrText ->
                                writeVcViewModel.handleQrScanned(qrText)
                            },
                            onDismiss = { writeVcViewModel.dismissQrScanner() }
                        )
                    }
                    
                    // Enable reader mode when VC is ready for writing
                    LaunchedEffect(currentUseCase, writeVcState.pendingVcJwt, writeVcState.validatingVc, writeVcState.showQrScanner) {
                        if (currentUseCase == UseCase.WRITE_VC_TO_NDEF && 
                            writeVcState.pendingVcJwt != null && 
                            !writeVcState.validatingVc &&
                            !writeVcState.showQrScanner) {
                            try {
                                enableReaderMode("write VC NDEF", { tag ->
                                    try {
                                        handleTagForWriteVc(tag, writeVcViewModel)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error handling tag for Write VC: ${e.message}", e)
                                    }
                                })
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error enabling reader mode for Write VC: ${e.message}", e)
                            }
                        } else if (currentUseCase != UseCase.WRITE_VC_TO_NDEF || 
                                   writeVcState.pendingVcJwt == null ||
                                   writeVcState.showQrScanner) {
                            // Disable reader mode if conditions are no longer met
                            disableReaderMode()
                        }
                    }
                    
                    // Enable reader mode for Read VC (no PIN required, don't skip NDEF check)
                    LaunchedEffect(currentUseCase, readVcState.readingVc) {
                        if (currentUseCase == UseCase.READ_VC_FROM_NDEF && 
                            !readVcState.readingVc) {
                            try {
                                nfcManager.enableReaderMode("read VC NDEF", { tag ->
                                    try {
                                        handleTagForReadVc(tag, readVcViewModel)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error handling tag for Read VC: ${e.message}", e)
                                    }
                                }, skipNdefCheck = false) // Don't skip NDEF check for reading
                            } catch (e: Exception) {
                                Log.e("MainActivity", "Error enabling reader mode for Read VC: ${e.message}", e)
                            }
                        } else if (currentUseCase != UseCase.READ_VC_FROM_NDEF) {
                            // Disable reader mode if not in Read VC use case
                            disableReaderMode()
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

    private fun enableReaderMode(reason: String, onTagDiscovered: (Tag) -> Unit, skipNdefCheck: Boolean = true) {
        nfcManager.enableReaderMode(reason, { tag ->
            Log.d("MainActivity", "ReaderMode tag discovered ($reason)")
            onTagDiscovered(tag)
        }, skipNdefCheck)
        Log.d("MainActivity", "ReaderMode enabled: $reason (skipNdefCheck=$skipNdefCheck)")
    }

    private fun disableReaderMode() {
        Log.d("MainActivity", "ReaderMode disabled")
        nfcManager.disableReaderMode()
    }

    /**
     * Trigger haptic feedback.
     */
    private fun triggerHaptic() {
        try {
            Log.d("MainActivity", "Triggering haptic feedback")
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            
            if (!vibrator.hasVibrator()) {
                Log.w("MainActivity", "Device does not have a vibrator")
                return
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
            Log.d("MainActivity", "Haptic feedback triggered successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to trigger haptic: ${e.message}", e)
        }
    }
    
    /**
     * Handle tag for Write URL use case.
     */
    private fun handleTagForWriteUrl(tag: Tag, viewModel: WriteUrlViewModel) {
        // Trigger haptic when tag is discovered
        Log.d("MainActivity", "Tag discovered for Write URL, triggering haptic")
        triggerHaptic()
        
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
            viewModel.writeUrl(tag) { shouldKeepEnabled ->
                // Disable reader mode after operation completes, unless retry is needed
                if (!shouldKeepEnabled) {
                    disableReaderMode()
                }
            }
            // Don't disable reader mode immediately - wait for operation to complete
            return
        }
    }

    /**
     * Handle tag for Write VC use case.
     */
    private fun handleTagForReadVc(tag: Tag, viewModel: ReadVcViewModel) {
        try {
            // Trigger haptic when tag is discovered
            triggerHaptic()
            
            // Read VC directly (no PIN required for NDEF reading)
            viewModel.readVc(tag) {
                // Disable reader mode after operation completes
                try {
                    disableReaderMode()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error disabling reader mode after VC read: ${e.message}", e)
                }
            }
            // Don't disable reader mode immediately - wait for operation to complete
        } catch (e: Exception) {
            Log.e("MainActivity", "Error handling tag for Read VC: ${e.message}", e)
        }
    }
    
    private fun handleTagForWriteVc(tag: Tag, viewModel: WriteVcViewModel) {
        try {
            // Trigger haptic when tag is discovered
            triggerHaptic()
            
            val state = viewModel.state.value
            
            // Check if we need to verify PIN
            if (state.pendingPin != null) {
                viewModel.verifyPin(tag) {
                    // Disable reader mode after operation completes
                    try {
                        disableReaderMode()
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error disabling reader mode after PIN verification: ${e.message}", e)
                    }
                }
                // Don't disable reader mode immediately - wait for operation to complete
                return
            }
            
            // Check if we need to write VC
            if (state.pendingVcJwt != null) {
                viewModel.writeVc(tag) { shouldKeepEnabled ->
                    // Disable reader mode after operation completes, unless retry is needed
                    if (!shouldKeepEnabled) {
                        try {
                            disableReaderMode()
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error disabling reader mode after VC write: ${e.message}", e)
                        }
                    }
                }
                // Don't disable reader mode immediately - wait for operation to complete
                return
            }
            
            Log.d("MainActivity", "Tag discovered but no pending operation (pendingPin=${state.pendingPin}, pendingVcJwt=${state.pendingVcJwt})")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in handleTagForWriteVc: ${e.message}", e)
            // Try to disable reader mode on error
            try {
                disableReaderMode()
            } catch (disableEx: Exception) {
                Log.e("MainActivity", "Error disabling reader mode after error: ${disableEx.message}", disableEx)
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
            description = "Write any URL to NDEF record on Keycard",
            onClick = { onUseCaseSelected(UseCase.WRITE_URL_TO_NDEF) },
            isReady = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        UseCaseCard(
            title = "2. Write VC to NDEF",
            description = "Write Verifiable Credential to NDEF",
            onClick = { onUseCaseSelected(UseCase.WRITE_VC_TO_NDEF) },
            isReady = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        UseCaseCard(
            title = "3. Read VC from NDEF",
            description = "Read and verify Verifiable Credential from NDEF",
            onClick = { onUseCaseSelected(UseCase.READ_VC_FROM_NDEF) },
            isReady = true
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
    val focusRequester = remember { FocusRequester() }
    
    // Auto-focus the PIN input when dialog appears
    // Add a small delay to ensure the dialog is fully visible before requesting focus
    LaunchedEffect(Unit) {
        delay(100) // Small delay to ensure dialog is fully visible
        focusRequester.requestFocus()
    }
    
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
                modifier = Modifier
                    .padding(top = 8.dp)
                    .focusRequester(focusRequester),
                keyboardActions = KeyboardActions(
                    onDone = { onConfirm() }
                )
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
fun ReadVcFromNdefScreen(
    vcStatus: String,
    logs: List<String>,
    readingVc: Boolean,
    verifyingProof: Boolean,
    jwtVc: String?,
    decodedPayload: String?,
    issuer: String?,
    subject: String?,
    vcClaims: String?,
    verificationError: String?,
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
        
        StatusText(status = vcStatus)
        
        if (readingVc) {
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
                    Text("Reading VC from Keycard...")
                }
            }
        }
        
        if (verifyingProof) {
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
                    Text("Verifying cryptographic proof...")
                }
            }
        }
        
        if (verificationError != null) {
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
                        text = verificationError,
                        fontSize = 14.sp,
                        color = Color(0xFF424242)
                    )
                }
            }
        }
        
        if (decodedPayload != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE8F5E9)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Decoded VC",
                        fontSize = 18.sp,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF2E7D32)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (issuer != null) {
                        Text(
                            text = "Issuer: $issuer",
                            fontSize = 14.sp,
                            color = Color(0xFF424242)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    if (subject != null) {
                        Text(
                            text = "Subject: $subject",
                            fontSize = 14.sp,
                            color = Color(0xFF424242)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Text(
                        text = "Payload:",
                        fontSize = 14.sp,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF424242)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = decodedPayload,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = Color(0xFF424242)
                    )
                }
            }
        }
        
        if (vcClaims != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "VC Claims",
                        fontSize = 18.sp,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF1565C0)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = vcClaims,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = Color(0xFF424242)
                    )
                }
            }
        }
        
        if (jwtVc != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "JWT-VC (Raw)",
                        fontSize = 18.sp,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = jwtVc,
                        fontSize = 10.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LogsList(logs = logs, writtenHex = null)
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