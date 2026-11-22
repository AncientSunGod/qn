# QuickNovel Upstream Sync Guide

## Repositories

- **Customized Working Repository (`origin`)**: `https://github.com/AncientSunGod/qn`
- **Official Upstream Source (`upstream`)**: `https://github.com/AncientSunGod/QuickNovel` (tracking `LagradOst/QuickNovel`)
- **Current Synced Upstream Baseline**: QuickNovel **v3.9.1** (`versionCode 57`, `versionName "3.9.1"`)

Always make custom development changes inside `AncientSunGod/qn`. Never treat `AncientSunGod/QuickNovel` as the destination repository.

---

## Preserved Custom Modules & Features

When integrating a future upstream QuickNovel release, keep these custom files and sections intact:

1. **LNReader Extensions & JS Compatibility Layer** (`com.lagradost.quicknovel.extensions.*`):
   - `ExtensionRepositoryManager.kt`
   - `ExtensionJsRuntime.kt` (Rhino 1.7.15 + ES6 regex/Unicode normalization + `@libs/fetch`, `fetchViaWebview`, `@libs/aes`, `cheerio`, `htmlparser2`, `dayjs`, Next.js static asset caching)
   - `ExtensionHttpClient.kt` (WebView cookie/UA sync, cross-origin `Origin`/`Referer` normalization, `//` path recovery, `CloudflareChallengeException` propagation)
   - `ExtensionMainAPI.kt`, `ExtensionResponseMapper.kt`, `ExtensionProviderRegistry.kt`, `ExtensionSettingsManager.kt`
   - `ExtensionPreferenceStore.kt` (in `com.lagradost.quicknovel.providers`)
2. **Custom Native Providers & Session Helpers**:
   - `NovelArrowProvider.kt`
   - `QuickStreamSessionStore.kt`
   - `CrashLog.kt`
3. **Interactive Cloudflare Verification & Failure Propagation**:
   - `network/CloudflareChallengeManager.kt` (challenge inspection + per-host prompt cooldown)
   - `network/CloudflareVerificationActivity.kt` (in-app interactive WebView screen capturing `cf_clearance`, cookies, and User-Agent)
   - `network/CloudflareKiller.kt` (throws `CloudflareChallengeException` on persistent challenge)
   - `APIRepository.kt`, `BookDownloader.kt`, `BookDownloader2.kt`, `ReadActivityViewModel.kt`, `ui/TextAdapter.kt`, `ui/mainpage/MainScreen.kt`, `ui/result/ResultScreen.kt` (structured failure propagation and `"Open verification"` actions)
4. **Custom Reader & UI Enhancements**:
   - NeoQN-style reading progress bar & spatial glass reader controls (`ReadActivity2.kt`, `BookReader.kt`)
   - Auto-scroll, reading timer, and text replacement rules
   - `"Don't disturb me about updates"` (`InAppUpdater.kt`, `DevicePreferenceStore.kt`, `SettingScreen.kt`)
   - Preview server tooling (`preview-server.mjs`)

---

## Step-by-Step Procedure for Future Upstream Syncs

1. Fetch the latest tag from upstream without modifying custom branches:
   ```bash
   git fetch upstream --tags
   ```
2. Compare the previous synced tag (`v3.9.1`) against the new upstream release tag (e.g., `v3.9.2`) to list modified upstream files:
   ```bash
   git diff --stat v3.9.1..v3.9.2
   ```
3. Merge or cherry-pick upstream changes onto your working branch in `AncientSunGod/qn`:
   ```bash
   git merge v3.9.2
   ```
4. Resolve conflicts carefully:
   - Keep `com.lagradost.quicknovel.extensions.*`, `CloudflareChallengeManager`, `CloudflareVerificationActivity`, custom reader controls, `NovelArrowProvider`, and `UpdatePreferences.dontDisturbUpdates`.
   - Accept upstream provider fixes, dependency upgrades, and reader bugfixes while keeping Rhino (`org.mozilla:rhino`) and custom extension dependencies in `app/build.gradle`.
5. Run the unit tests and full APK build:
   ```bash
   ./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --console=plain
   ```
6. Verify the generated APK (`app/build/outputs/apk/debug/app-debug.apk`) and smoke-test:
   - Native providers & search
   - LNReader repo install/refresh + WTR-Lab & Fenrir Realm chapter loading
   - Reader controls, progress bar, and `"Open verification"` on a Cloudflare-protected source
   - `"Don't disturb me about updates"` toggle in Settings
