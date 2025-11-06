package com.example.keycardapp.data.nfc

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.util.Log

/**
 * Manages NFC adapter lifecycle, reader mode, and tag discovery.
 * Handles NFC adapter initialization, reader mode enable/disable,
 * foreground dispatch, and intent handling.
 */
class NfcManager(private val activity: Activity) {
    
    private var nfcAdapter: NfcAdapter? = null
    private lateinit var pendingIntent: PendingIntent
    private var readerModeEnabled: Boolean = false
    private var onTagDiscoveredCallback: ((Tag) -> Unit)? = null
    
    /**
     * Callback interface for tag discovery events
     */
    interface TagDiscoveredCallback {
        fun onTagDiscovered(tag: Tag)
    }
    
    /**
     * Initialize NFC adapter and pending intent.
     * Call this in Activity.onCreate()
     * 
     * @return true if NFC is available, false otherwise
     */
    fun initialize(): Boolean {
        nfcAdapter = NfcAdapter.getDefaultAdapter(activity)
        
        if (nfcAdapter == null) {
            Log.w("NfcManager", "NFC is not available on this device")
            return false
        }
        
        val intent = Intent(activity, activity.javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        pendingIntent = PendingIntent.getActivity(activity, 0, intent, pendingIntentFlags)
        
        Log.d("NfcManager", "NFC adapter initialized")
        return true
    }
    
    /**
     * Check if NFC is available on this device
     */
    fun isNfcAvailable(): Boolean = nfcAdapter != null
    
    /**
     * Enable reader mode for active tag discovery.
     * Reader mode is more reliable than intent-based discovery.
     * 
     * @param reason Reason string for logging
     * @param onTagDiscovered Callback when a tag is discovered
     * @param skipNdefCheck If true, skip NDEF check (for writing). If false, include NDEF check (for reading).
     */
    fun enableReaderMode(reason: String, onTagDiscovered: (Tag) -> Unit, skipNdefCheck: Boolean = true) {
        val adapter = nfcAdapter ?: run {
            Log.w("NfcManager", "Cannot enable reader mode: NFC adapter not available")
            return
        }
        
        if (readerModeEnabled) {
            disableReaderMode()
        }
        
        onTagDiscoveredCallback = onTagDiscovered
        
        val flags = if (skipNdefCheck) {
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        } else {
            NfcAdapter.FLAG_READER_NFC_A
        }
        adapter.enableReaderMode(activity, { tag ->
            Log.d("NfcManager", "ReaderMode tag discovered ($reason, skipNdefCheck=$skipNdefCheck)")
            onTagDiscovered(tag)
        }, flags, null)
        
        readerModeEnabled = true
        Log.d("NfcManager", "ReaderMode enabled: $reason (skipNdefCheck=$skipNdefCheck)")
    }
    
    /**
     * Disable reader mode.
     */
    fun disableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!readerModeEnabled) return
        
        adapter.disableReaderMode(activity)
        readerModeEnabled = false
        onTagDiscoveredCallback = null
        Log.d("NfcManager", "ReaderMode disabled")
    }
    
    /**
     * Check if reader mode is currently enabled
     */
    fun isReaderModeEnabled(): Boolean = readerModeEnabled
    
    /**
     * Enable foreground dispatch for intent-based tag discovery.
     * Call this in Activity.onResume()
     */
    fun enableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        if (readerModeEnabled) {
            // Don't enable foreground dispatch if reader mode is active
            return
        }
        
        adapter.enableForegroundDispatch(activity, pendingIntent, null, null)
        Log.d("NfcManager", "Foreground dispatch enabled")
    }
    
    /**
     * Disable foreground dispatch.
     * Call this in Activity.onPause()
     */
    fun disableForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        adapter.disableForegroundDispatch(activity)
        Log.d("NfcManager", "Foreground dispatch disabled")
    }
    
    /**
     * Handle NFC intent from onNewIntent().
     * Extracts Tag from intent and calls the callback if set.
     * 
     * @param intent Intent from onNewIntent()
     * @return Tag if found, null otherwise
     */
    fun handleIntent(intent: Intent): Tag? {
        Log.d("NfcManager", "Handling NFC intent")
        
        val tag: Tag? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        
        if (tag == null) {
            Log.w("NfcManager", "No tag found in intent")
            return null
        }
        
        // If reader mode callback is set, use it
        onTagDiscoveredCallback?.invoke(tag)
        
        return tag
    }
    
    /**
     * Clean up resources.
     * Call this in Activity.onDestroy() if needed.
     */
    fun cleanup() {
        disableReaderMode()
        disableForegroundDispatch()
        nfcAdapter = null
        Log.d("NfcManager", "NFC manager cleaned up")
    }
}

