# How to Check Logs with ADB Logcat

## 📱 What is ADB?

**ADB (Android Debug Bridge)** is a command-line tool that lets you communicate with an Android device connected to your computer. It's part of the Android SDK Platform Tools.

---

## 🔧 Setup ADB

### Option 1: Android Studio (Recommended)
ADB is automatically installed with Android Studio. It's located at:
- **Windows**: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
- **Mac/Linux**: `~/Library/Android/sdk/platform-tools/adb`

### Option 2: Standalone ADB
Download from: https://developer.android.com/tools/releases/platform-tools

### Add to PATH (Optional)
Add platform-tools to your system PATH so you can run `adb` from anywhere.

---

## 🚀 Quick Start

### Step 1: Connect Your Device

1. **Enable USB Debugging** on your Android device:
   - Settings → About Phone → Tap "Build Number" 7 times
   - Settings → Developer Options → Enable "USB Debugging"

2. **Connect device** to computer via USB

3. **Verify connection:**
   ```bash
   adb devices
   ```
   
   Should show:
   ```
   List of devices attached
   <device-id>    device
   ```

### Step 2: Check Logs

**Basic command:**
```bash
adb logcat -s NfcManager MainActivity
```

This filters logs to show only:
- `NfcManager` tag (all logs from NfcManager class)
- `MainActivity` tag (all logs from MainActivity class)

---

## 📊 Useful Logcat Commands

### 1. Filter by Tag (Your Use Case)
```bash
# Filter to specific tags only
adb logcat -s NfcManager MainActivity

# Filter to single tag
adb logcat -s NfcManager

# Filter to multiple tags
adb logcat -s NfcManager MainActivity Keycard PIN VC
```

### 2. Filter by Log Level
```bash
# Show only errors
adb logcat *:E

# Show warnings and errors
adb logcat *:W

# Show debug, info, warnings, errors
adb logcat *:D
```

### 3. Combined Filtering
```bash
# Specific tags + log level
adb logcat -s NfcManager:D MainActivity:D

# Filter by tag and level
adb logcat -s NfcManager MainActivity *:S
```

### 4. Clear Logs and Start Fresh
```bash
# Clear all logs first
adb logcat -c

# Then start logging
adb logcat -s NfcManager MainActivity
```

### 5. Save Logs to File
```bash
# Save to file
adb logcat -s NfcManager MainActivity > nfc_logs.txt

# Save with timestamp
adb logcat -v time -s NfcManager MainActivity > nfc_logs.txt

# Save and view at same time (Windows PowerShell)
adb logcat -s NfcManager MainActivity | Tee-Object -FilePath nfc_logs.txt
```

### 6. Filter by Text (Search)
```bash
# Search for specific text
adb logcat | grep -i "nfc\|keycard\|tag"

# Windows PowerShell
adb logcat | Select-String -Pattern "NFC|Keycard|Tag"
```

### 7. Show with Timestamps
```bash
# Show time
adb logcat -v time -s NfcManager MainActivity

# Show thread time
adb logcat -v threadtime -s NfcManager MainActivity
```

---

## 🎯 For Your App - Specific Commands

### Watch NFC Operations
```bash
# All NFC-related logs
adb logcat -s NfcManager MainActivity Keycard PIN VC

# With timestamps
adb logcat -v time -s NfcManager MainActivity Keycard PIN VC

# Only errors and warnings
adb logcat -s NfcManager MainActivity *:E *:W
```

### Watch Specific Use Case
```bash
# Write URL to NDEF flow
adb logcat -s NfcManager MainActivity PIN Keycard UI

# Write VC to NDEF flow
adb logcat -s NfcManager MainActivity VC PIN Keycard
```

### Watch Everything (No Filter)
```bash
# All logs (may be overwhelming)
adb logcat

# Clear first, then all logs
adb logcat -c && adb logcat
```

---

## 💻 Running Commands

### Windows PowerShell
```powershell
# Navigate to project (if needed)
cd C:\Users\alisher\AndroidStudioProjects\keycardapp

# Run logcat
adb logcat -s NfcManager MainActivity

# Or if adb is in PATH
cd C:\Users\alisher\AndroidStudioProjects
adb logcat -s NfcManager MainActivity
```

### Windows Command Prompt (CMD)
```cmd
cd C:\Users\alisher\AndroidStudioProjects
adb logcat -s NfcManager MainActivity
```

### Mac/Linux Terminal
```bash
cd ~/AndroidStudioProjects/keycardapp
adb logcat -s NfcManager MainActivity
```

---

## 🔍 What You'll See

### Example Output:
```
D/NfcManager: NFC adapter initialized
D/MainActivity: New NFC Intent Received!
D/NfcManager: Handling NFC intent
D/NfcManager: ReaderMode enabled: verify PIN
D/MainActivity: ReaderMode enabled: verify PIN
D/NfcManager: ReaderMode tag discovered (verify PIN)
D/PIN: Connecting IsoDep...
D/PIN: IsoDep connected; channel ready
D/Keycard: === Starting writeNdefViaKeycard ===
D/Keycard: NDEF write completed successfully
D/NfcManager: ReaderMode disabled
```

### Log Format:
```
<Log Level>/<Tag>: <Message>
```

**Log Levels:**
- `V` = Verbose (most detailed)
- `D` = Debug
- `I` = Info
- `W` = Warning
- `E` = Error
- `F` = Fatal

---

## 🛠️ Troubleshooting

### "adb: command not found"
- **Solution**: Add platform-tools to PATH, or use full path:
  ```powershell
  # Windows
  & "C:\Users\<username>\AppData\Local\Android\Sdk\platform-tools\adb.exe" logcat -s NfcManager MainActivity
  ```

### "no devices/emulators found"
- **Solution**: 
  1. Check USB connection
  2. Enable USB debugging on device
  3. Accept USB debugging authorization popup on device
  4. Run `adb devices` to verify

### "No logs showing"
- **Solution**: 
  1. Make sure app is running
  2. Check if tags are correct (case-sensitive)
  3. Try `adb logcat` (no filter) to see all logs
  4. Clear logs first: `adb logcat -c`

### Logs too noisy
- **Solution**: Use more specific filters:
  ```bash
  # Only specific tags
  adb logcat -s NfcManager MainActivity
  
  # Only errors
  adb logcat *:E
  ```

---

## 📝 Quick Reference Card

```bash
# Most common commands
adb devices                          # List connected devices
adb logcat -c                       # Clear logs
adb logcat -s NfcManager MainActivity  # Filter by tags
adb logcat -v time -s NfcManager    # With timestamps
adb logcat -c && adb logcat -s NfcManager MainActivity  # Clear + filter
```

---

## 🎯 For Your Testing

### Recommended Command for Phase 1 Testing:
```bash
# Clear old logs
adb logcat -c

# Watch NFC operations with timestamps
adb logcat -v time -s NfcManager MainActivity Keycard PIN VC UI
```

This will show:
- ✅ NFC adapter initialization
- ✅ Reader mode enable/disable
- ✅ Tag discovery events
- ✅ PIN verification
- ✅ NDEF write operations
- ✅ All UI status updates

---

## 💡 Pro Tips

1. **Start logcat before testing** - Capture everything from the start
2. **Use timestamps** - Helps track operation sequence
3. **Clear logs first** - Start fresh each test session
4. **Save important logs** - Use `> logs.txt` to save for later
5. **Use multiple terminals** - One for logcat, one for commands

---

## 🚀 Next Steps

After checking logs, you can:
1. Verify NFC operations are working
2. Debug any issues you find
3. Share logs if you need help debugging

---

## 📚 Additional Resources

- [Android Logcat Documentation](https://developer.android.com/studio/command-line/logcat)
- [ADB Documentation](https://developer.android.com/studio/command-line/adb)
- [Android Debug Bridge](https://developer.android.com/tools/adb)

