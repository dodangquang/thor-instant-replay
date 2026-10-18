# Preview payment wording and updater audit

Audit date: 18 October 2026. Documentation-only audit; no app code, APK, package, preferences, device state or repository visibility was changed.

## Account preview

The installed preview configuration contains empty `apiBase`, `keyId` and `publicKeySpki`, with `enforce: false`. `LicenseConfig.ready()` therefore returns false. `AccountActivity.render()` enters its first `!ready` branch regardless of a locally cached account.

Both languages explicitly say that the account server and payment verification are not connected, existing app features remain available, and paid access is not enabled. The heading says **ĐANG CHUẨN BỊ MỞ BÁN / PREPARING FOR LAUNCH**. The Ko-fi sentence uses future tense: **Đăng nhập sẽ dùng… / Sign-in will use…**. It describes the intended flow rather than claiming a live shop or completed payment integration.

The preview branch renders only **Quay lại ứng dụng / Back to app** and **Đóng / Close** actions. It does not render email / code inputs, a sign-in action or a shop button. Consequently, the configured Ko-fi URL is not opened by the preview account screen. No application-code change is needed for the current non-live preview while the Ko-fi appeal is pending.

Provider references remain in the later configured branches: Ko-fi purchase-email labels and the **Xem combo trên Ko-fi / View bundle on Ko-fi** action for an authenticated account without a purchase. These branches assume a live Ko-fi purchase flow and must be reviewed if the payment provider changes. `ready()` checks API / key configuration, not Ko-fi account or appeal status; deployment must keep the preview configuration until the provider and real payment flow are verified. `enforce: false` by itself does not keep the account screen in the preview branch once API / key configuration becomes complete.

The `$25 / lifetime / 2 devices` header and combo description are present as planned product terms inside that clearly labeled preview. They do not provide a checkout action or claim that a payment has been accepted.

## APK updater gap

The original `1e01b28` source and the current app source were searched for APK manifests / release URLs, `DownloadManager`, `PackageInstaller`, archive verification and `REQUEST_INSTALL_PACKAGES`. No application updater or update channel exists. The original manifest also lacks install-package permission. Current `update` references concern audio-volume changes, UI refresh or the shared content provider.

`BootReceiver` handles `MY_PACKAGE_REPLACED` only to restart the existing recording flow after Android has already installed an app replacement. It does not check for, download, verify or install updates. `FileProvider` is used to open recorded MP4 clips, not APKs.

The renamed app retains package `com.thor.instantreplay` and the original signing certificate, so a manually delivered APK can still be installed over the current app without uninstalling or clearing its capture preferences / clips. Automatic updates through the new combo distribution remain an implementation gap. No updater was added during this audit.

## Recorded Android boundary result

The parent task reported installing the final cleanup APK with SHA-256 `34f94168cd44293316d194750e53a98005304529672f6f7573799081913745ec` and running the actual Android policy suite: **PASS 23 checks, `symlinkTested=true`**. See the version validation report for the earlier UI / controller / recording checks and preserved-data fingerprints.
