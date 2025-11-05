# Phase 1 Testing Guide - NfcManager Extraction

## ✅ What Was Implemented

Phase 1 successfully extracted all NFC infrastructure into a dedicated `NfcManager` class:

### Created Files:
- `app/src/main/java/com/example/keycardapp/data/nfc/NfcManager.kt`

### Extracted Functionality:
1. ✅ NFC adapter initialization
2. ✅ PendingIntent creation
3. ✅ Reader mode enable/disable
4. ✅ Foreground dispatch enable/disable
5. ✅ Intent handling (onNewIntent)
6. ✅ Tag discovery callbacks

### MainActivity Changes:
- ✅ Removed direct `NfcAdapter` access
- ✅ Removed `pendingIntent` management
- ✅ All NFC operations now go through `NfcManager`
- ✅ Maintained backward compatibility (same functionality)

---

## 🧪 Testing Instructions

### Prerequisites
1. **Physical Android device with NFC** (emulators don't support NFC)
2. **NFC enabled** on device (Settings → NFC)
3. **Keycard** (or any NFC tag for basic testing)
4. **USB debugging enabled** (for logcat)

### Test 1: App Initialization ✅

**Test:** Verify NFC adapter is initialized correctly

**Steps:**
1. Launch the app
2. Check logcat for:
   ```
   D/NfcManager: NFC adapter initialized
   ```

**Expected Result:**
- App launches without errors
- No "NFC is not available" message (on NFC-capable devices)
- Log shows successful initialization

**If it fails:**
- Check device has NFC hardware
- Verify NFC is enabled in device settings
- Check logcat for error messages

---

### Test 2: Use Case Selection ✅

**Test:** Verify app can navigate to use cases

**Steps:**
1. Launch app
2. Select "1. Write URL to NDEF" or "2. Write VC to NDEF"
3. Check logcat for:
   ```
   D/MainActivity: Use case selected
   ```

**Expected Result:**
- App navigates to selected use case screen
- No crashes
- PIN dialog appears (for Write URL/VC)

---

### Test 3: Reader Mode - PIN Verification ✅

**Test:** Verify reader mode enables and detects NFC tags

**Steps:**
1. Select "1. Write URL to NDEF" or "2. Write VC to NDEF"
2. Enter PIN: `123456`
3. Click "Verify"
4. **Before tapping card**, check logcat for:
   ```
   D/NfcManager: ReaderMode enabled: verify PIN
   D/MainActivity: ReaderMode enabled: verify PIN
   ```
5. Tap Keycard to device
6. Check logcat for:
   ```
   D/NfcManager: ReaderMode tag discovered (verify PIN)
   D/MainActivity: ReaderMode tag discovered (verify PIN)
   D/PIN: Connecting IsoDep...
   D/PIN: IsoDep connected; channel ready
   ```

**Expected Result:**
- Reader mode enables successfully
- Tag is detected when tapped
- PIN verification completes
- Status message shows "✅ PIN verified"
- Reader mode disables after verification

**If it fails:**
- Check NFC is enabled on device
- Verify card is properly positioned
- Check logcat for error messages
- Try tapping card multiple times

---

### Test 4: Reader Mode - NDEF Write ✅

**Test:** Verify reader mode works for NDEF writing

**Steps:**
1. Complete PIN verification (Test 3)
2. Enter a URL (e.g., `https://example.com`)
3. Click "Write"
4. **Before tapping card**, check logcat for:
   ```
   D/NfcManager: ReaderMode enabled: write NDEF
   D/MainActivity: ReaderMode enabled: write NDEF
   ```
5. Tap Keycard to device
6. Check logcat for:
   ```
   D/NfcManager: ReaderMode tag discovered (write NDEF)
   D/Keycard: === Starting writeNdefViaKeycard ===
   D/Keycard: NDEF write completed successfully
   ```

**Expected Result:**
- Reader mode enables successfully
- Tag is detected when tapped
- NDEF write completes successfully
- Status message shows "✅ NDEF written"
- NDEF hex is displayed
- Reader mode disables after write

**If it fails:**
- Check card has sufficient capacity
- Verify card is not read-only
- Check logcat for specific error messages
- Try retrying the write operation

---

### Test 5: Foreground Dispatch ✅

**Test:** Verify foreground dispatch works when reader mode is disabled

**Steps:**
1. Launch app (don't select any use case)
2. Tap an NFC tag to device
3. Check logcat for:
   ```
   D/NfcManager: Foreground dispatch enabled
   D/MainActivity: New NFC Intent Received!
   D/NfcManager: Handling NFC intent
   ```

**Expected Result:**
- Foreground dispatch is enabled in `onResume()`
- Intent is received when tag is tapped
- Tag is extracted from intent
- No crashes

**Note:** This is a fallback mechanism. Reader mode is preferred for active tag discovery.

---

### Test 6: Lifecycle Management ✅

**Test:** Verify NFC manager handles activity lifecycle correctly

**Steps:**
1. Launch app and select a use case
2. Enable reader mode (start PIN verification flow)
3. **Put app in background** (press home button)
4. Check logcat for:
   ```
   D/NfcManager: Foreground dispatch disabled
   ```
5. **Bring app to foreground**
6. Check logcat for:
   ```
   D/NfcManager: Foreground dispatch enabled
   ```

**Expected Result:**
- Foreground dispatch disables when app goes to background
- Foreground dispatch enables when app returns to foreground
- Reader mode remains active (if enabled)
- No memory leaks

---

### Test 7: Error Handling ✅

**Test:** Verify graceful handling when NFC is unavailable

**Steps:**
1. **On a device without NFC** (or emulator):
   - Launch app
   - Check logcat for:
     ```
     W/NfcManager: NFC is not available on this device
     ```

**Expected Result:**
- App doesn't crash
- Status shows "NFC is not available on this device"
- App remains functional (just without NFC features)

---

## 📊 Test Checklist

Print this checklist and mark off as you test:

```
[ ] Test 1: App Initialization
[ ] Test 2: Use Case Selection
[ ] Test 3: Reader Mode - PIN Verification
[ ] Test 4: Reader Mode - NDEF Write
[ ] Test 5: Foreground Dispatch
[ ] Test 6: Lifecycle Management
[ ] Test 7: Error Handling
```

---

## 🔍 Debugging Tips

### View Logs
```bash
# Filter NfcManager logs
adb logcat -s NfcManager

# Filter MainActivity logs
adb logcat -s MainActivity

# Filter all NFC-related logs
adb logcat | grep -i "nfc\|keycard\|tag"
```

### Common Issues

1. **"NFC is not available"**
   - Check device has NFC hardware
   - Enable NFC in device settings
   - Restart device

2. **"ReaderMode tag discovered" but no action**
   - Check `handleTag()` is being called
   - Verify pending operations (PIN, URL, etc.)
   - Check logcat for errors

3. **Tag not detected**
   - Ensure card is properly positioned
   - Try different card positions
   - Check NFC is enabled
   - Verify card is not damaged

4. **Reader mode not enabling**
   - Check NFC adapter is initialized
   - Verify no other NFC app is active
   - Restart app

---

## ✅ Success Criteria

Phase 1 is successful if:
1. ✅ All NFC operations work as before
2. ✅ No direct `NfcAdapter` access in MainActivity
3. ✅ Code compiles without errors
4. ✅ All tests pass
5. ✅ No regressions in existing functionality

---

## 📝 Notes

- MainActivity still has wrapper functions `enableReaderMode()` and `disableReaderMode()` that add use-case-specific logging. This is intentional for debugging and can be removed in later phases if desired.

- The `NfcManager` class is now reusable across the entire app and can be easily tested in isolation.

---

## 🚀 Next Steps

After Phase 1 testing is complete:
- **Phase 2**: Extract Keycard operations into `KeycardRepository`
- **Phase 3**: Create use cases
- **Phase 4**: Create ViewModels
- **Phase 5**: Extract UI components
- **Phase 6**: Simplify MainActivity

