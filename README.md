# QuestSongBridge

Lightweight Android app for Meta Quest 2 / Quest 3 that hosts a local HTTP server so you can upload custom Beat Saber song ZIPs from a Windows PC over Wi-Fi. No USB required for routine uploads. No cloud, no account.

## Supported devices
- Meta Quest 2
- Meta Quest 3
(sideloaded APK; no root required)

## Prerequisites on Quest
- Modded Beat Saber installed.
- Mods managed by ModsBeforeFriday (MBF) with SongCore enabled.
- An in-game control that refreshes the custom-song list.

## Install APK
1. Enable Developer Mode on the headset.
2. Install `app/build/outputs/apk/debug/app-debug.apk` via SideQuest, Meta Quest Developer Hub, or `adb install`.
3. Launch QuestSongBridge from the headset.

## Configure destination
1. Open QuestSongBridge.
2. Tap "Select Destination Directory".
3. Pick the SongCore custom levels folder (commonly under `ModData/com.beatgames.beatsaber/Mods/SongCore/CustomLevels`).
4. Tap "Test Write" to verify.
5. If access fails, reselect or check MBF/SongCore layout.

## Start server
1. Configure storage first (Start Server disabled until storage selected).
2. Tap "Start Server".
3. Note the URL and token shown.
4. Server runs as a foreground service with an ongoing notification.

## Upload from Windows
1. Ensure PC and Quest are on the same Wi-Fi (client isolation disabled).
2. Open the URL in Chrome/Edge.
3. Enter the token and click Save.
4. Drag & drop one or more song ZIPs, or use the file picker.
5. Wait for per-file results.
6. In Beat Saber press Refresh; play the new song.

## Authentication
- A random token is generated on first run and stored in encrypted preferences.
- Token required in `X-Auth-Token` header for uploads and recent list.
- Regenerate token in the app at any time.

## Settings
- Listening port (default 8080).
- Max upload size (MB, default 200).
- Duplicate policy: skip (default), replace, cancel.
- Retain uploaded ZIP after extraction (default off).
- Logging level.

## Can the server run while Beat Saber is playing?
QuestSongBridge uses an Android foreground service (`dataSync` type) with an ongoing notification to host the HTTP server. The service is started while the app is in the foreground and is intended to keep listening after the activity loses focus and while a VR game is foregrounded.

What is implemented:
- Foreground service with notification.
- Service independent of activity.
- Wi-Fi lock.

What is verified:
- Automated build and unit tests (compile, basic tests).
- Not verified on a physical Quest headset.

What is NOT verified:
- That the foreground service survives while Beat Saber is in the foreground on Quest OS.
- That Quest OS does not kill/suspend the service under memory pressure.
- Background upload during active gameplay.

You must test on a real device. See "Real-headset acceptance test".

## Real-headset acceptance test
1. Install APK.
2. Configure custom-song directory and Test Write.
3. Start HTTP server.
4. Open upload page from Windows.
5. Upload a valid song ZIP.
6. Verify song appears in destination.
7. Launch Beat Saber.
8. Confirm HTTP server still reachable from PC.
9. Upload a second song while Beat Saber is running.
10. Confirm Beat Saber not closed/restarted.
11. Press Refresh in Beat Saber; new song appears.
12. Play the song.
13. Repeat with duplicate and invalid ZIP; verify no corruption.
14. Stop server; verify further uploads rejected.

## Classroom / multiple headsets
- Each headset gets a unique default name like `QUEST1234` (derived from its Android ID).
- Rename it in Settings if desired.
- The app registers an mDNS service (`_http._tcp`) so headsets may be reachable as `http://QUEST1234.local:8080` on compatible networks.
- The app shows a QR code encoding the upload URL + token for easy scanning.
- The web UI can save multiple headsets (name/URL/token) and upload one ZIP to several at once.
- Uploads are serialized per headset (queue of 1) to avoid overloading the Quest.
- Default duplicate policy is `replace` (overwrite existing songs). Change in Settings if needed.

## Known limitations
- Background operation during gameplay is not verified on physical hardware.
- SAF providers may not support atomic rename; installation is staged then copied.
- Multiple songs in one archive are installed individually; duplicates handled per policy.
- No resumable uploads.
- No HTTPS (local network only).
- Quest OS may kill the service under memory pressure.

## Build from source
Requirements: JDK 17, Android SDK (platform 35, build-tools 35.0.0).

```
gradle assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Troubleshooting
- Connectivity: same Wi-Fi, disable client isolation, check firewall.
- Storage: reselect directory, Test Write.
- Port conflict: change port in settings.
- Service killed: restart server; avoid heavy background tasks.
- Upload fails: check ZIP contains `info.dat`; size limits.

## Project layout
- `app/src/main/java/.../service/UploadService.kt` foreground service
- `app/src/main/java/.../http/HttpServer.kt` NanoHTTPD server
- `app/src/main/java/.../ZipProcessor.kt` safe extraction
- `app/src/main/java/.../StorageManager.kt` SAF handling
- `app/src/main/assets/index.html` web UI
