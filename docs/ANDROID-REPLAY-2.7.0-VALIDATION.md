# Android Replay 2.7.0 preview — device validation

Baseline UI / recording build validated on AYN Thor `c2b670a0`, Android 13, 18 October 2026. The subsequent cleanup build and the parent-reported Android boundary result are described separately below.

- Package remains `com.thor.instantreplay`; versionCode 9, versionName 2.7.0. Installed over versionCode 8 / 2.6-NVIDIA with `adb install -r` after verifying the same signing certificate.
- Certificate SHA-256: `06c96a505c22eeec42ec8a62f2b822f527667fbdc9ad62f95f2f0590e93b099b`.
- Device-tested baseline APK: 13,244,390 bytes; SHA-256 `17b9d9b539fc2d1019c5f088c353f3e4a1f27ae0b4183b1f7b440ee4d4a4abbc`.
- `:app:assembleRelease` including release vital lint and `:app:assembleDebugAndroidTest`: passed with JDK 17, SDK 35, Gradle 9.4.1 / AGP 9.2.1.
- Real instrumentation checked both VI / EN resource sets, landscape orientation and the original capture-preference fingerprint after the APK upgrade. All original capture settings remained unchanged.
- Real input dispatch checked 60 repeated A-down events: no activation until release, exactly one activation on release, and no activation for a second orphan release.
- 100 same-direction analog events produced one initial move; no repeat before the 330 ms hold delay; one repeat after the delay; centering canceled further repeats. Normal repeat period is 150 ms.
- Touch testing opened the language dialog and selected VI / EN using one tap for each control. Controller focus uses `requestFocusFromTouch()` to preserve normal first-tap behavior.
- Native screenshots reviewed: main VI / EN; audio, clips and landscape Wi-Fi share VI; shared account preview VI / EN. All primary controls and the controller footer remained visible.
- Wi-Fi share was reached at the actual LAN address and returned HTTP 200, the Android Replay brand and Vietnamese watch / download actions. The QR dialog uses a horizontal layout so the stop-sharing button fits the landscape screen.
- Recording regression produced a 39.2273-second replay MP4 (1,849,832 bytes) and an 11.7549-second continuous-recording MP4 (893,257 bytes). Both contained valid `mdat` / `moov` structures, a 1920×1080 video track and an audio track.
- SHA-256 hashes of all 32 pre-existing MP4 files matched before / after testing. The two exact QA clips were copied to the local QA workspace, checked by hash, then removed from the device. No original clip was deleted or modified. The selected storage quota remained 50 clips throughout; the test did not exceed that quota.
- Test package `com.thor.instantreplay.test` was removed. Vietnamese was restored, and Replay buffering was running when the device was handed back.

## Scope and remaining integration

The supplied logo is used for the hero panel and adaptive launcher icon. Existing service action names, preference file `thor_replay_prefs` and `Movies/ThorClips` remain compatible.

The account activity, secure account store, signed-grant verifier and exact-package / certificate device-identity provider are copied from the shared ThorQOL client. Preview configuration has no API endpoint or public verification key, and `enforce` is false. The account screen explicitly states that payment validation is not connected. Live payment / email / hosting validation is handled in the parent task and was not claimed as completed by these app tests.

The original encoder captures the main screen only. The previously advertised dual-screen option was hidden because its source implementation always returns single-screen mode. No dual-screen claim is made in the new feature list. The original application did not have an online APK updater.

No repository visibility was changed, no commits were pushed, and Kho Phim was not modified.

## Latest cleanup build

- Release APK: 13,245,646 bytes; SHA-256 `34f94168cd44293316d194750e53a98005304529672f6f7573799081913745ec`. Package / version remain `com.thor.instantreplay` / 2.7.0 / code 9; the signing certificate remains identical to the baseline.
- All seven shared Java files match the latest ThorQOL source by SHA-256. This includes the corrected Vietnamese device-count description, preservation of the chosen device name when replacing a device, and controller focus after touch input without requiring two taps.
- The LAN dashboard and both video / download routes now share a read-only resolver: direct MP4 children only, exact canonical parent containment, no directory paths, control characters or residual encoded path separators / extra encoding layers. Symlinks escaping the clip directory are rejected by canonical containment.
- Generated links encode the complete filename, so Unicode, spaces, `+`, `&` and normal literal `%` names round-trip through the existing query decoder. Filename display and quoted response-header filenames are escaped.
- Standalone file-policy tests: **22 passed**. The Windows host denied symbolic-link creation, so that one case was explicitly skipped rather than counted as passed.
- HTTP integration harness: **26 passed** against the actual Gradle-compiled `ThorWebServer`, with Android environment / logging adapters pointing to an owned temporary directory. Both routes preserved valid clip bytes and returned 404 for parent, absolute, encoded-slash / backslash, double / triple encoded and control-character paths. Original temporary source files remained unchanged.
- `:app:assembleRelease` including vital lint and `:app:assembleDebugAndroidTest`: passed. The child agent performed no device input or installation for this cleanup build because the parent task owned Thor.
- The parent task subsequently reported installing this final cleanup APK on Thor and running the Android `clipBoundary` mode: **23 checks passed; `symlinkTested=true`**. This closes the symlink-test gap left by the Windows host. This result is recorded from the parent task's report; the child did not repeat ADB operations.

The updated QA APK supports the same policy suite on Android, including an actual symbolic-link escape where the filesystem permits it:

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e mode clipBoundary com.thor.instantreplay.test/com.thor.instantreplay.ReplayUiInstrumentation
```

The mode creates and removes only its own temporary cache files; it does not read or modify `Movies/ThorClips`. The parent-reported Android run returned **23 checks; symlinkTested=true**. The existing UI / locale / controller mode remains available without the `mode` argument.

## Final buffering restoration after installation

After the parent installed the final APK, buffering was restored on Thor and verified directly. The Vietnamese screen displayed **ĐANG GHI ĐỆM**; the RAM buffer increased from 0 MB to 1 MB with the original 60-second setting. Both the active `com.thor.instantreplay` media projection and foreground service 1001 remained present after removing the agent's `com.thor.instantreplay.test` package. No replay clip or continuous recording was saved during this restoration.

All 32 existing MP4 files had identical SHA-256 hashes before and after restoration. Before restarting capture, the existing device regression passed its expected original capture-preference hash assertion: `2fcf50dd2c29e7f924e93389e10693db16ceceb2e17ea08185d4caa9575eb961`. No capture/audio setting or permission was changed afterward. Vietnamese was selected through the native language menu and remained displayed at handoff.

One restoration attempt encountered a ROM `system_server` watchdog while `uiautomator` registered its accessibility service concurrently with virtual-display creation. The captured watchdog stacks showed a DisplayManager/AccessibilityManager lock inversion; Android restarted itself without an agent reboot command. This is evidence of a system lock conflict during that attempt, not proof that every possible app contribution is excluded. The successful retry avoided `uiautomator` during projection startup and used read-only screenshots/service checks afterward. No source or APK changes were made for restoration.

Evidence is saved outside the repository under `outputs/combo-qa`: `android-replay-restored-buffering-vi.png`, `android-replay-restore-report.json`, and the before/after clip hash manifests. All hanging read commands ended with the system restart; only the normal ADB server remained at handoff.
