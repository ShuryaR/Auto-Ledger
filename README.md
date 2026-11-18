# AutoLedger (Android — Kotlin & Jetpack Compose)

Native Android application (`app.onebook.autoledger`) that reads configured bank SMS alerts on-device, classifies and groups transactions, and prepares draft entries in a live ERPNext server (`docstatus = 0`).

## Tech Stack

- **UI**: Kotlin + Jetpack Compose + Material 3 (`AutoLedgerTheme` with Light/Dark Ledger color tokens and tabular numerals) + Navigation Compose
- **Persistence**: Room (`AutoLedgerDatabase`) with transactional outbox + Android Keystore-encrypted session storage (`SessionStore`)
- **Networking**: OkHttp (`ErpClient`, `ErpAuth`, `ErpDocs`, `ErpReports`, `ErpOps`) against live ERPNext v16 REST and whitelisted APIs (HTTPS in release; HTTP permitted in debug builds only)
- **SMS Engine**: Native `SmsReceiver` (`BROADCAST_SMS`), `READ_SMS` ContentResolver backfill, regex template engine (`app/src/main/assets/templates/idfc.json`), file-backed crash-safe queue (`noBackupFilesDir/sms-ingest`)
- **Concurrency**: Kotlin Coroutines & `StateFlow`

## Requirements

- **Android Studio** (Meerkat / Narwhal or newer) or CLI Gradle **9.3.1**
- **JDK 21**
- **Android SDK 36** (minimum SDK 26)

## Build & Test

Open the repository root in **Android Studio** and sync Gradle, or run via CLI with Gradle 9.3.1 + JDK 21 installed:

```bash
# If using an installed Gradle CLI to generate the wrapper jar first:
gradle wrapper --gradle-version 9.3.1

# Build debug APK
./gradlew assembleDebug

# Run JVM unit tests
./gradlew testDebugUnitTest

# Install on a connected Android device / emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Automated GitHub Actions APK Tag & Release

`.github/workflows/release-apk.yml` automatically runs unit tests, builds debug & release APKs, creates & pushes a git tag (`v<versionName>`, or `v<versionName>-b<run_number>` if that tag already exists), and publishes a GitHub Release with attached APKs and SHA-256 checksums on pushes to `main`/`master`, `v*` tags, or manual workflow dispatch.

Optional repository secrets for signed release APKs:
- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD` (optional if same as keystore password)

## Architecture

- `app/src/main/java/app/onebook/autoledger/core/` — Domain types, integer-paise money arithmetic, classification, correlation, booking plan engine, suggestion & similarity matching, trust status
- `app/src/main/java/app/onebook/autoledger/data/` — Room entities, DAO, `AutoLedgerDatabase`, `Repo`, Android Keystore `SessionStore`
- `app/src/main/java/app/onebook/autoledger/erp/` & `outbox/` — OkHttp ERPNext client, auth (password or API key/secret), doctype & report queries, transactional outbox runner
- `app/src/main/java/app/onebook/autoledger/sms/` — Native `SmsReceiver`, `SmsIngestManager`, ContentResolver backfill, template engine, file-backed queue & notifications
- `app/src/main/java/app/onebook/autoledger/ingest/` — Ingestion pipeline, template updates, outbox drainer, ERPNext status poller, duplicate checker, auto-apply rules
- `app/src/main/java/app/onebook/autoledger/inbox/` — Transaction action picker, form state (`FlowStore`), description builder, duplicate/foreign-rate helpers, draft submission, bulk group apply
- `app/src/main/java/app/onebook/autoledger/home/` & `state/` — Balance aggregation, login/setup helpers, activity list model, foreground cycle runner, `AppContainer`
- `app/src/main/java/app/onebook/autoledger/ui/` — Theme tokens, reusable components, Navigation graph, Login, 4-step Setup wizard, Home/Inbox/Activity/Settings tabs, `/txn/*` flow screens, Rules, and debug-only SMS receiver test
