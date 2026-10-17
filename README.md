# HTML Backup

A small, deliberately narrow Android app that prepares a local ZIP of the
device owner's photos and contacts and uploads it to a Discord webhook **only
after an explicit review-and-confirm step**.

The visible screen is a local WebView that shows only **Hello World**. All
permission handling, MediaStore discovery, contacts reading, ZIP creation, and
Discord upload logic is native Kotlin and is completely separate from the
WebView. The WebView has JavaScript, file access, content access, and network
loads disabled; it never receives personal data.

> **Consent and scope:** use this app only on a device you own or are explicitly
> authorized to back up. Never install it on someone else's device to collect
> their data. The permission prompts and the review dialog are intentional
> safeguards and must not be bypassed.

---

## What happens on launch

1. The local `Hello World` WebView is displayed.
2. A native dialog explains what the app can back up and why each permission is
   requested. If the build has no valid webhook (so `WebhookConfig.kt` falls
   back to its placeholder), the dialog adds a note and the Send button stays
   disabled; the permission and local preparation flow can still be reviewed.
3. Missing permissions are requested using the correct runtime permission for
   the Android version. Photo access can be **full** or **selected photos only**
   on Android 14+.
4. If at least one category is allowed, the ZIP is prepared automatically in the
   app cache. If the user denies everything, nothing is read or prepared.
5. Preparation does **not** transmit anything. A second dialog shows:
   - the destination (Discord host only, never the webhook token),
   - the archive name and size,
   - photo and contact counts and categories,
   - sample entry names from the ZIP,
   - a warning when the archive exceeds Discord's default 20 MiB limit.
6. The user must tap **Send to Discord** to start the HTTPS upload. Cancelling
   deletes the temporary archive.
7. Upload progress is shown, including a visible rate-limit wait. The result
   dialog reports the actual HTTP success or a sanitized failure. The temporary
   archive is deleted whether the upload succeeds, fails, or is cancelled.

---

## Configure the webhook

The webhook URL is **not stored in the public source tree**. It is injected at
build time.

### Build-time injection (recommended)

Supply the URL in one of these ways:

```bash
# Environment variable
HTML_BACKUP_WEBHOOK_URL='https://discord.com/api/webhooks/<id>/<token>' ./gradlew assembleDebug

# Or a Gradle property
./gradlew assembleDebug -PhtmlBackup.webhookUrl='https://discord.com/api/webhooks/<id>/<token>'
```

`app/build.gradle.kts` writes the value into `BuildConfig.WEBHOOK_URL`, and
`WebhookConfig.kt` reads it. If no value is supplied, the placeholder in
`WebhookConfig.kt` is used, `WebhookConfig.isConfigured` is false, and Send is
disabled.

For CI, add the URL as a GitHub Actions secret:

1. Open the repository on GitHub.
2. **Settings -> Secrets and variables -> Actions -> New repository secret**.
3. Name it `DISCORD_WEBHOOK_URL` and paste the webhook URL.

The workflow maps that secret to `HTML_BACKUP_WEBHOOK_URL` for the Gradle build.
The secret is masked in logs, and failure logs/annotations are additionally
redacted before they are published.

### Changing the webhook UI name

Every upload sets the Discord message's **username** to the phone's name, so a
backup arriving in the channel is labelled by device:

- `Settings.Global.DEVICE_NAME` (the name configured in Android Settings);
- otherwise `Build.MANUFACTURER` + `Build.MODEL`;
- fallback: `Android phone`.

`DeviceInfo.sanitize` collapses whitespace/control characters and caps the name
at 80 characters. The device name is sent only as the Discord username; it is
not added to the ZIP or its manifest. If you also want the Discord webhook
itself renamed, edit the webhook name in Discord's channel settings.

### Optional: hardcode for a private build

If you build only for yourself from a private checkout, you may replace the
`PLACEHOLDER_URL` inside `WebhookConfig.kt` with your URL. Never commit a real
webhook to a public repository.

Rules enforced by `WebhookUrlValidator`:

- scheme must be exactly `https`
- host must be `discord.com`, `canary.discord.com`, or `ptb.discord.com`
- port must be 443
- no query string, fragment, user info, or percent-encoded path
- path must be `/api/webhooks/<17-20 digit id>/<token>`
- placeholders and all-zero ids are rejected

The app displays only `Discord (discord.com)` (the host); it never shows the
webhook URL in the UI, dialogs, toasts, or logs. OkHttp logging interceptors are
not installed.

### Webhook secrecy

A Discord webhook URL is a bearer secret: anyone who has it can post to that
channel.

- Keep it out of Git, screenshots, issues, chat logs, and build logs.
- Any APK built with the URL contains it and it can be extracted from the APK.
  Keep such APKs private.
- Because the repository is public, the included workflow does **not** publish
  an APK artifact when a real webhook is injected. Only a manual
  `workflow_dispatch` run with `publish_apk=yes` overrides that guard, and it
  warns that the artifact will be public.
- If the URL leaks, delete the webhook in Discord and create a new one.

## Requirements

- Android Studio (a version that supports Android Gradle Plugin 8.7.x)
- JDK 17
- Android SDK Platform 35
- Android 8.0 (API 26) or newer on the device

Project toolchain:

| Component | Version |
|---|---|
| Gradle wrapper | 8.10.2 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Java target | 17 |
| OkHttp | 4.12.0 |

If you use a different Gradle or AGP release, update the wrapper and root
`build.gradle.kts` together.

---

## Build and install

Open the project in Android Studio, let it sync, then either press **Run** or
use the command line:

```bash
./gradlew assembleDebug
```

The debug APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected device with USB debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or in Android Studio, select the device and run the `app` configuration. The
app label is **HTML Backup** and it shows only **Hello World** after launch
behind the native consent/permission dialogs.

### Unit tests

```bash
./gradlew testDebugUnitTest
```

The tests cover URL validation, photo entry naming/sanitization, contact JSON
shape and missing fields, archive naming/byte formatting, and the Discord
multipart uploader (using MockWebServer for success, 429 retry/exhaustion, 413,
404, redirect rejection, missing archive, and invalid configuration).

---

## Permissions by Android version

| Android version | Photos | Contacts |
|---|---|---|
| 8.0-9 (API 26-28) | `READ_EXTERNAL_STORAGE` (scoped MediaStore reads) | `READ_CONTACTS` |
| 10-12 (API 29-32) | `READ_EXTERNAL_STORAGE`; MediaStore only; no broad filesystem access | `READ_CONTACTS` |
| 13 (API 33) | `READ_MEDIA_IMAGES` | `READ_CONTACTS` |
| 14-16 (API 34-35) | `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED`; full library or selected photos | `READ_CONTACTS` |

Additional details:

- No `MANAGE_EXTERNAL_STORAGE` and no write-storage permission are requested.
- The app uses `MediaStore.Images.Media` and `ContentResolver.openInputStream`;
  it never enumerates the filesystem.
- On Android 14+, if the user grants selected photos only, the app backs up
  exactly the items Android makes visible and marks the archive as partial in
  `manifest.json`.
- If one category is denied but the other is granted, only the granted category
  is prepared. The review dialog explicitly says which category is not included.
- If both are denied, the app offers **Try again** when Android permits another
  prompt, or **Open settings** when the permission is permanently blocked.

---

## What is backed up

The ZIP is created in the app-specific cache directory
(`cache/backups/html-backup-<UTC timestamp>.zip`) and contains:

```
manifest.json            counts, timestamp, categories, photo-access mode
photos/00001_name.jpg    one entry per accessible JPEG, PNG, or WebP image
photos/00002_name.png
contacts.json            names, phone numbers, and email addresses
```

### Photos

- Discovered through MediaStore's image collection, which is the gallery the
  system exposes: DCIM, Pictures, screenshots, Downloads, and similar
  collections. The app never walks the filesystem, so it sees exactly the
  images the user authorized.
- Supports `image/jpeg`, `image/png`, and `image/webp`.
- Filters out pending MediaStore rows. Rows whose stored size is not populated
  are still included and are measured while copying.
- Streams each image directly into the ZIP in 64 KiB chunks. It does not decode
  bitmaps or hold whole images in memory, and progress/cancellation checks run
  while copying.
- Display names are sanitized and prefixed with an index to avoid ZIP path
  traversal and duplicate names. Extensions are derived from the validated MIME
  type.
- Unreadable individual photos are counted as skipped and listed in the review
  dialog; they do not stop the rest of the backup.

### Contacts

- Read only after `READ_CONTACTS` is granted, using the Android Contacts
  Provider. No mock data and no SMS/call-log access.
- Exports display name plus all phone and email rows for each contact.
- Contacts with no phone number and/or no email address are exported with empty
  arrays instead of being dropped or failing.
- The JSON schema is:

```json
{
  "schemaVersion": 1,
  "app": "HTML Backup",
  "exportedAt": "2026-10-17T12:00:00Z",
  "source": "Android Contacts Provider",
  "contactCount": 2,
  "phoneCount": 3,
  "emailCount": 1,
  "contacts": [
    {
      "name": "Example Contact",
      "phones": ["+1 555 0100"],
      "emails": []
    }
  ]
}
```

- Contact values are never logged.

---

## Discord upload details

`WebhookUploader.kt` uses Discord's multipart webhook format:

- `payload_json` part with a short status message, an allowed-mentions guard, and
  attachment metadata
- `files[0]` part containing the ZIP as `application/zip`
- `wait=true` so Discord returns the created message and a 2xx response confirms
  the upload

Behavior:

- HTTPS only; redirects (including SSL redirects) are disabled, so the webhook
  token cannot be forwarded to another host.
- 429 responses are retried up to two times with the server's `retry_after`
  delay (maximum 30 seconds per wait). The UI shows the wait; cancellation
  interrupts the delay.
- Progress reflects the bytes streamed to the connection. After the body is
  sent, the dialog switches to "waiting for Discord to confirm" until the HTTP
  response arrives, so a completed progress bar is not mistaken for success.
- 401/403, 404, 413, 5xx, malformed responses, connectivity errors, timeouts,
  TLS errors, redirects, and cancellation each map to a safe, explicit failure
  message.
- Success is reported only for HTTP 2xx. The server response body is not shown
  because it is not needed and must not leak webhook metadata.
- No archive is retained after the upload attempt finishes.
- Discord's free upload limit can change; the app currently warns above the
  default 20 MiB per-file limit and relies on Discord's 413 response as the
  authority. Large photo collections may need a higher limit or a smaller
  selection.

The upload does not delete the originals. Uploading to Discord means Discord
and anyone with access to that channel can download the archive. The ZIP is not
encrypted beyond HTTPS in transit.

---

## Manual real-device test plan

Do **not** use emulator or mocked data for the final acceptance test. Use a
device you own with actual photos and contacts, and a test Discord channel.
These steps have not been executed in this development workspace; they must be
run by you before trusting a build.

1. Create a dedicated Discord test channel and webhook. Build with
   `HTML_BACKUP_WEBHOOK_URL='...' ./gradlew assembleDebug` (or add the
   `DISCORD_WEBHOOK_URL` Actions secret and run the workflow), then install.
2. Confirm the launcher screen shows only **Hello World** and the native
   explanation appears over it.
3. Grant full photo and contact access. Verify that preparation starts without
   per-photo selection and that the review dialog lists actual counts, sample
   ZIP entry names, and the Discord host.
4. Tap Cancel in the review dialog. Verify the archive is gone:
   `adb shell run-as com.example.htmlbackup ls cache/backups` (debug build).
5. Repeat and tap Send. Verify Discord receives a ZIP whose `contacts.json`
   contains the real contacts (including a contact with no phone/email) and
   whose `photos/` entries open as valid images.
6. Test denial paths:
   - deny both: no archive is prepared;
   - grant only contacts: photos are marked not included;
   - grant only photos: contacts are marked not included;
   - permanently deny one and confirm **Open settings** appears when nothing is
     accessible.
7. On Android 14+, choose **Select photos** and grant only a few items. Verify
   only those photos are included and the review dialog says
   **selected photos only**.
8. Rotate the device while the explanation, preparation progress, review, and
   upload result dialogs are visible. The ViewModel must preserve the operation
   and restore the correct dialog.
9. Enable airplane mode and send. Verify a connectivity failure result, no
   success claim, and cache cleanup.
10. Test a large collection (or a large ZIP) and confirm either a valid upload or
    a clear HTTP 413 failure, with the archive removed afterward.
11. Confirm no webhook URL appears in logcat, dialogs, toasts, or UI. Search the
    source for the URL before sharing a build.

---

## Project layout

```
app/src/main/java/com/example/htmlbackup/
├── MainActivity.kt          # WebView + consent/permission/dialog flow
├── BackupViewModel.kt       # lifecycle, preparation, review gate, cleanup
├── BackupModels.kt          # state models, archive summary, safe exception
├── PermissionSupport.kt     # Android-version-specific permission logic
├── PhotoBackupManager.kt    # MediaStore discovery + streamed ZIP photo entries
├── ContactExporter.kt       # Contacts Provider query + contacts.json
├── ZipBackupManager.kt      # on-device ZIP assembly and cleanup
├── WebhookUploader.kt       # HTTP multipart upload, retries, cancellation
├── WebhookConfig.kt         # build-time webhook value + placeholder
├── WebhookUrlValidator.kt   # strict HTTPS/destination validation
├── DeviceInfo.kt            # phone name used as the Discord username
└── Formats.kt               # archive names and human-readable sizes

app/src/main/assets/index.html          # local Hello World page
app/src/main/res/...                    # layout, theme, icons, strings, XML rules
app/src/test/java/...                   # JVM unit tests (no device needed)
```

The root Gradle files, wrapper, manifest, and resource files are included.
There is no background service and no periodic upload; the only network request
is the confirmed webhook upload.

---

## Verification status (important)

**CI build verified.** The GitHub Actions workflow in
`.github/workflows/android.yml` ran on a clean `ubuntu-latest` runner for commit
`de70d85`:

- `./gradlew testDebugUnitTest` — **passed** (38 JVM unit tests, including the
  device-name tests).
- `./gradlew assembleDebug` — **passed**; a debug APK was assembled and uploaded
  as the `html-backup-debug-apk` workflow artifact.
- Run:
  https://github.com/BHAI-DHEERAJ/26.3client/actions/runs/39035682468

This run used the **placeholder** webhook because the
`DISCORD_WEBHOOK_URL` Actions secret is not set in the repository. The GitHub
token available in this environment cannot create Actions secrets (HTTP 403),
so the secret must be added by the repository owner (see
**Configure the webhook -> Build-time injection**). The build-time injection code
itself compiled; the real-URL value path will be exercised on the first build
after the secret is added. The supplied URL's syntax was checked offline against
`WebhookUrlValidator` (scheme, host, path shape, id, and token format), but
Discord delivery could not be tested here because the sandbox has no network
route to `discord.com`.

**Not yet verified: on-device behavior.** The APK has not been installed on a
physical device from this environment, and real photos, real contacts, and a real
Discord transfer have not been exercised. That part of the proof still requires
the manual real-device test plan above, using a webhook and device you control.

Additional notes:

- CI signs the APK with the Android debug key. Generate your own release key
  before distributing a build.
- Workflow artifacts expire after 14 days; re-run the workflow or build locally
  after that.
- The workflow also publishes the truncated Gradle log as a commit comment when
  a build fails, which makes CI failures diagnosable from the repository
  timeline. It requires `contents: write` for that reason.
- The generated APK embeds the webhook supplied at build time. Never distribute a
  build containing a real webhook, and never commit one to the repository.
- When the `DISCORD_WEBHOOK_URL` secret is set, the workflow skips the public APK
  artifact by default because the APK would contain the secret. Build locally or
  use the manual `publish_apk=yes` dispatch only if you accept the exposure.

