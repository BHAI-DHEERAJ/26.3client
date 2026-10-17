# HTML Backup

A small, deliberately narrow Android app that sends the device owner's photos,
contacts, and a device/app report to a Discord webhook — **one message per
item**, only after an explicit review-and-confirm step.

The visible screen is a local WebView that shows only **Hello World**. All
permission handling, MediaStore discovery, contacts reading, compression, and
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
2. A native dialog explains what the app sends and why each permission is
   requested. If the build has no valid webhook, the dialog adds a note and Send
   stays disabled; the permission and preparation flow can still be reviewed.
3. Missing permissions are requested using the correct runtime permission for the
   Android version. Photo access can be **full** or **selected photos only** on
   Android 14+.
4. If at least one category is allowed, the app enumerates accessible photos,
   reads real contacts, and collects device/app metadata. Nothing is uploaded.
5. A review dialog shows the destination host, the phone name, model, Android
   version, app version, photo/contact counts, the total number of Discord
   messages, and the compression policy.
6. The user must tap **Send to Discord**. Cancelling sends nothing.
7. Sending is sequential, one Discord message per item:

   | # | Message | Contents |
   |---|---------|----------|
   | 1 | Device/app report | phone name, manufacturer, brand, model, Android version, SDK, security patch, build ID, codename, product, ABI, locale, time zone, connection type + local IP + VPN, carrier + SIM state + modem count + roaming, own phone number when permitted, app package/version, photo and contact counts |
   | 2 | Contacts (optional) | `contacts.json` with names, phone numbers, email addresses |
   | 3..n | Photos (one each) | each accessible JPEG/PNG/WebP image, streamed directly from the gallery |

8. No ZIP is created. Photos are streamed from MediaStore; the only temporary
   files are compressed copies, deleted immediately after each attempt.

### Image failure and compression chain

Originals are uploaded byte-for-byte when they fit. For each photo the sender
tries, in order:

1. the original file (skipped when it is already over Discord's default 20 MiB
   limit);
2. one immediate retry for transient connection errors;
3. a compressed JPEG at normal resolution (long edge up to 2048 px, progressively
   lower quality, target ≤ 8 MiB);
4. **a quarter-resolution JPEG** (long edge `2048 / 4 = 512 px`, target ≤ 4 MiB)
   when normal compression cannot produce or deliver a file;
5. skip that photo and continue with the remaining photos.

A skipped photo never stops the run. The final result dialog reports exactly how
many photos were sent, how many were compressed, how many were skipped, and
whether contacts and the device report were delivered.

If three network-level failures happen in a row (connectivity, timeout, TLS), the
run stops early rather than hammering a broken connection; items already
delivered stay delivered and are not re-sent automatically. A fatal webhook
error (invalid configuration, revoked/unknown webhook, or a redirect) stops the
run immediately instead of repeating pointless compression attempts for every
remaining photo.

---

## Configure the webhook

The webhook URL is **not stored in the public source tree**. It is injected at
build time.

### Build-time injection (recommended)

```bash
# Environment variable
HTML_BACKUP_WEBHOOK_URL='https://discord.com/api/webhooks/<id>/<token>' ./gradlew assembleDebug

# Or a Gradle property
./gradlew assembleDebug -PhtmlBackup.webhookUrl='https://discord.com/api/webhooks/<id>/<token>'
```

For Android Studio builds, put the property in your **user-level**
`~/.gradle/gradle.properties` (outside the repository):

```properties
htmlBackup.webhookUrl=https://discord.com/api/webhooks/<id>/<token>
```

`app/build.gradle.kts` writes the value into `BuildConfig.WEBHOOK_URL`, and
`WebhookConfig.kt` reads it. With no value, the placeholder is used,
`WebhookConfig.isConfigured` is false, and Send is disabled.

### CI secret

1. Open the repository on GitHub.
2. **Settings -> Secrets and variables -> Actions -> New repository secret**.
3. Name it `DISCORD_WEBHOOK_URL` and paste the webhook URL.

Or use the helper (hidden input, nothing written to disk):

```bash
./scripts/set-webhook-secret.sh
```

The workflow maps that secret to `HTML_BACKUP_WEBHOOK_URL`. It is masked in logs,
and failure logs/annotations are additionally redacted before they are published.
When a real webhook is injected, the workflow does **not** publish the APK
artifact by default because the APK would contain the secret.

### Device report contents

The first Discord message is a report built by `DeviceInfo`, `NetworkReporter`,
and `TelephonyReporter`, and it is fully listed in the review dialog before
sending.

| Category | Fields | Permission |
|---|---|---|
| Phone | device name (`Settings.Global.DEVICE_NAME`, else manufacturer + model) | none |
| Hardware | manufacturer, brand, model, device codename, product, ABI | none |
| OS | Android release, SDK level, security patch, build ID, locale, time zone | none |
| App | package, version name, version code | none |
| Network | connection type (Wi-Fi/mobile/Ethernet/VPN/offline), local IP, VPN active, metered | `ACCESS_NETWORK_STATE` |
| SIM / carrier | carrier name, SIM state, modem count, roaming | none |
| Phone number (SIM) | the number the carrier provisioned on the SIM | `READ_PHONE_NUMBERS` (optional) |
| Phone number (own contact card) | numbers stored on the "me" card in Contacts | `READ_CONTACTS` (already required) |

`ReportConfig.kt` has three switches (`INCLUDE_NETWORK_INFO`,
`INCLUDE_TELEPHONY_INFO`, `INCLUDE_PHONE_NUMBER`) if you want to omit a section.
Denying `READ_PHONE_NUMBERS` never blocks the photo/contact backup; the report
simply says "not permitted".

**Own phone number caveat:** there is no reliable "current mobile number" API on
modern Android. The app tries two sources and labels each one honestly:

1. **SIM** — `TelephonyManager.line1Number` with `READ_PHONE_NUMBERS`. Most
   carriers never provision a number on the SIM, so this is usually blank.
2. **Own contact card** — the phone number on the "me" profile in the Contacts
   app (`ContactsContract.Profile`), read with `READ_CONTACTS`, which the
   contact backup already needs. This often works on Indian SIMs because the
   number was saved to Contacts, but it is the stored value, not a live SIM
   query, and may be out of date.

If neither source has a number, the report says
`not available (carrier did not provision it and no own contact card number)`
instead of guessing. The review dialog shows the same combined line before
anything is sent. The "me" card numbers are separate from the
`contacts.json` export: they appear only in the report field, never merged into
the contacts file.

**Not collected, at any setting:**

- SMS/MMS contents or message metadata (`READ_SMS` is reserved for the default
  SMS app, Google Play forbids it for this use, and bulk message extraction is
  the surveillance pattern this project refuses to build).
- IMSI, ICCID, SIM serial number, subscriber IDs (persistent identity
  identifiers, restricted by Android, unnecessary for a backup).
- Wi-Fi SSID or anything requiring location permission.
- Public IP (the receiver already sees the connection's public address; looking
  it up would mean an extra third-party request).
- Camera images or any scheduled capture.

No privileged identifiers require root, and the app does not attempt to work
around permission denials.

### Validation and secrecy

`WebhookUrlValidator` requires:

- scheme exactly `https`
- host `discord.com`, `canary.discord.com`, or `ptb.discord.com`
- port 443
- no query string, fragment, user info, or percent-encoded path
- path `/api/webhooks/<17-20 digit id>/<token>`
- placeholders and all-zero ids rejected

The UI shows only `Discord (discord.com)`, never the webhook URL. OkHttp logging
interceptors are not installed. A Discord webhook URL is a bearer secret: anyone
who has it can post to that channel. Any APK built with it contains it and the
URL can be extracted, so keep such builds private, never commit the URL, and
delete/rotate the webhook if it leaks.

---

## Requirements

- Android Studio with Android Gradle Plugin 8.7.x support
- JDK 17
- Android SDK Platform 35
- Android 8.0 (API 26) or newer on the device

| Component | Version |
|---|---|
| Gradle wrapper | 8.10.2 |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Java target | 17 |
| OkHttp | 4.12.0 |

---

## Build and install

```bash
HTML_BACKUP_WEBHOOK_URL='https://discord.com/api/webhooks/<id>/<token>' ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK is also produced by Android Studio's **Run** action (set the URL in
`~/.gradle/gradle.properties` for Studio builds). The app label is **HTML
Backup** and it shows only **Hello World** behind the native dialogs.

### Unit tests

```bash
./gradlew testDebugUnitTest
```

The tests cover URL validation, photo naming (including compressed and
quarter-resolution names), resolution/sample-size math, contact JSON shape and
missing fields, byte formatting, device-name sanitization, and the Discord
webhook client with MockWebServer (JSON message, one-file multipart, 429
retry/exhaustion, 413 and Discord 40005, 404, 503, redirect rejection, invalid
configuration).

---

## Permissions by Android version

| Android version | Photos | Contacts |
|---|---|---|
| 8.0-9 (API 26-28) | `READ_EXTERNAL_STORAGE` (scoped MediaStore reads) | `READ_CONTACTS` |
| 10-12 (API 29-32) | `READ_EXTERNAL_STORAGE`; MediaStore only; no broad filesystem access | `READ_CONTACTS` |
| 13 (API 33) | `READ_MEDIA_IMAGES` | `READ_CONTACTS` |
| 14-16 (API 34-35) | `READ_MEDIA_IMAGES` + `READ_MEDIA_VISUAL_USER_SELECTED` | `READ_CONTACTS` |

The device report additionally uses two permissions:

- `ACCESS_NETWORK_STATE` — normal permission, no prompt (connection type and
  local IP).
- `READ_PHONE_NUMBERS` — runtime permission, optional; only used to read this
  device's own number for the report.

- No `MANAGE_EXTERNAL_STORAGE`, no write-storage permission, no filesystem walks.
- On Android 14+, selected-photos access sends exactly the items Android exposes
  and is labelled "selected photos only" in the review dialog and the report.
- Denying one category backs up only the other. Denying both sends nothing.

---

## Contacts format

`contacts.json` is sent as its own Discord message:

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
    { "name": "Example Contact", "phones": ["+1 555 0100"], "emails": [] }
  ]
}
```

Contacts without a phone number or email are kept with empty arrays. Contact
values are never logged.

---

## Discord upload details

- Each request is `POST` over HTTPS with `wait=true`, so a 2xx response is a
  confirmed delivered message.
- The info message is JSON (`content` + one embed).
- Files use Discord's multipart format: `payload_json` plus exactly one
  `files[0]` part. No ZIP and no batched attachments.
- Redirects (including SSL redirects) are disabled, so the webhook token cannot
  be forwarded to another host.
- 429 responses are retried up to three times using `retry_after` (max 60 s per
  wait); the UI shows the wait and cancellation interrupts it.
- 401/403, 404, 413, Discord error `40005`, 5xx, malformed responses, timeouts,
  TLS errors, and connectivity failures map to explicit, safe result messages.
- Requests are serialized with a small delay to stay friendly to Discord's
  per-webhook rate limit.
- Temporary compressed files are deleted after each attempt and on cancel.

Discord's free per-file upload limit can change; the app assumes 20 MiB. Larger
photos are compressed before upload. Large photo collections produce many
messages, which is intentional: one file per message.

The upload does not delete originals. Uploading means Discord and anyone with
access to that channel can download the data; transport is HTTPS only.

---

## Manual real-device test plan

Do **not** use emulator or mocked data for the final acceptance test. Use a
device you own with real photos and contacts, and a test Discord channel. These
steps have not been executed in this development workspace; run them before
trusting a build.

1. Create a dedicated Discord test channel and webhook. Build with
   `HTML_BACKUP_WEBHOOK_URL='...' ./gradlew assembleDebug`, then install.
2. Confirm the screen shows only **Hello World** and the native explanation
   appears over it.
3. Grant full photo and contact access. Verify preparation starts without
   per-photo selection, and the review dialog shows the real phone name, model,
   Android/app version, counts, and number of messages.
4. Tap Cancel. Verify nothing appears in Discord.
5. Repeat and tap Send. Verify Discord receives, in order: the device report, a
   `contacts.json`, then one message per photo.
6. Open several received photos and confirm they open and match the originals.
7. Force a failure path: temporarily point the app at a webhook whose channel is
   deleted (or revoke it mid-run). Confirm failures are reported honestly,
   remaining photos continue, and skipped counts are shown.
8. Test a very large photo (over 20 MiB) and confirm a compressed copy arrives.
9. On Android 14+, choose **Select photos**, grant a few items, and confirm only
   those are sent and the dialog says **selected photos only**.
10. Rotate during preparation, review, and upload; the operation and correct
    dialog must survive.
11. Enable airplane mode and send: a connectivity failure should be reported and
    no success claimed.
12. Confirm no webhook URL appears in logcat, dialogs, toasts, or the UI. Search
    the source for the URL before sharing a build.
13. Check the app cache after a run:
    `adb shell run-as com.example.htmlbackup ls cache/compressed` (debug build)
    should be empty or absent.

---

## Project layout

```
app/src/main/java/com/example/htmlbackup/
├── MainActivity.kt          # WebView + consent/permission/dialog flow
├── BackupViewModel.kt       # lifecycle, preparation, review gate, cleanup
├── BackupModels.kt          # plan, progress, report, and safe exception types
├── BackupPreparer.kt        # MediaStore + contacts discovery (no upload)
├── BackupSender.kt          # sequential one-item-per-message upload + fallbacks
├── PermissionSupport.kt     # Android-version-specific permission logic
├── PhotoBackupManager.kt    # MediaStore image discovery and naming
├── ContactExporter.kt       # Contacts Provider query + contacts.json
├── DeviceInfo.kt            # phone name + device/app report payload
├── MediaCompressor.kt       # normal and quarter-resolution JPEG fallbacks
├── UploadBodies.kt          # streaming request bodies (no full-file buffers)
├── WebhookUploader.kt       # one-message HTTPS client, retries, cancellation
├── WebhookConfig.kt         # build-time webhook value + placeholder
├── WebhookUrlValidator.kt   # strict HTTPS/destination validation
└── Formats.kt               # human-readable byte sizes

app/src/main/assets/index.html          # local Hello World page
app/src/main/res/...                    # layout, theme, icons, strings, XML rules
app/src/test/java/...                   # JVM unit tests (no device needed)
```

There is no background service and no periodic upload; the only network requests
are the confirmed webhook messages.

---

## Verification status (important)

**CI build verified.** GitHub Actions ran on a clean `ubuntu-latest` runner for
commit `4f4837e`:

- `./gradlew testDebugUnitTest` — **passed** (60 JVM unit tests, including the
  network-address and phone-number formatting suites).
- `./gradlew assembleDebug` — **passed**; the debug APK was assembled.
- Run:
  https://github.com/BHAI-DHEERAJ/26.3client/actions/runs/39041689895

**Not verified from this environment:** on-device behavior, real Discord
delivery, and real photo/contact contents. The sandbox cannot reach
`discord.com` and has no Android device, so the manual test plan above is still
required.

- The workflow publishes the APK artifact only when no real webhook is injected
  (or when a manual `publish_apk=yes` dispatch explicitly accepts the exposure).

Additional notes:

- CI signs with the Android debug key; create your own release key before
  distribution.
- Workflow artifacts expire after 14 days.
- Failing Gradle logs are attached as a commit comment to make CI failures
  diagnosable from the repository timeline (`contents: write`).
