# VPN Master — Google Play Publishing Guide

Complete step-by-step guide to registering and publishing VPN Master (`net.swlr.vpnmaster`) on Google Play.

---

## Prerequisites

- Google Play Developer account (verified, one-time $25 fee paid)
- Signed release AAB (see `BUILD.md` → "Release Bundle")
- Keystore file: `vpnmaster-release.keystore` (already generated)
- Privacy policy URL (required for all VPN apps — see `PRIVACY_POLICY.md`)

---

## Part 1 — App Signing Keys (Read This First)

Google Play uses **two separate keys**: a **signing key** (the real one) and an **upload key** (what you submit). Understanding the difference is critical before you upload anything.

### Signing Key vs. Upload Key

| | Signing Key | Upload Key |
|---|---|---|
| **What it is** | The key Google uses to sign APKs delivered to users | The key you use to sign AABs you upload |
| **Who holds it** | Google (under Play App Signing) or you | You |
| **If lost** | App is dead — cannot ship updates | Recoverable — contact Google to rotate |
| **In this project** | `vpnmaster-release.keystore` will become your upload key | Same file, repurposed |

**Recommendation:** Enroll in **Play App Signing** (Google manages the signing key). You upload AABs signed with your upload key; Google re-signs the delivered APK with its managed key. This makes your upload key rotatable if compromised.

---

## Part 2 — Extract Key Information (Before Enrolling)

You will need the **SHA-1 and SHA-256 fingerprints** of your keystore certificate when setting up Play App Signing and for any future API integrations.

### 2a. Get Certificate Fingerprints

Run from the project root (replace passwords with your actual values):

```bash
keytool -list -v \
  -keystore vpnmaster-release.keystore \
  -alias vpnmaster \
  -storepass <your-store-password>
```

**Record the output** — specifically these lines:

```
Owner: CN=VPN Master, ...
SHA1:  XX:XX:XX:XX:...
SHA256: XX:XX:XX:XX:...
```

Save these in a secure password manager. You will reference the SHA-1 and SHA-256 values in the Play Console and potentially in any third-party service integrations.

### 2b. Export the Upload Certificate (PEM format)

The Play Console asks for your **upload key certificate** (the "eligible public key") during Play App Signing enrollment. This is the public certificate only — your private key never leaves the keystore.

```bash
# Export as PEM — this is what Play Console expects
keytool -export -rfc \
  -keystore vpnmaster-release.keystore \
  -alias vpnmaster \
  -storepass <your-store-password> \
  -file vpnmaster-upload-cert.pem
```

Upload `vpnmaster-upload-cert.pem` when Play Console asks for the "eligible public key" or "upload key certificate". The file will look like:

```
-----BEGIN CERTIFICATE-----
MIIFHz...
-----END CERTIFICATE-----
```

If Play Console specifically asks for a DER (binary) file instead:

```bash
keytool -export \
  -keystore vpnmaster-release.keystore \
  -alias vpnmaster \
  -storepass <your-store-password> \
  -file vpnmaster-upload-cert.der
```

Keep both files — the Play Console enrollment step may ask for either format.

### 2c. (Optional) Export Private Key via PEPK for Key Migration

If you ever want to **migrate an existing signing key into Play App Signing** (i.e., make Google adopt your current private key as the signing key rather than generating a new one), use the PEPK tool:

```bash
# Download PEPK tool from Play Console > App Signing > "Export and upload a key from Java keystore"
# Then run:
java -jar pepk.jar \
  --keystore=vpnmaster-release.keystore \
  --alias=vpnmaster \
  --output=output.zip \
  --encryptionkey=<encryption-key-shown-in-play-console>
```

Upload `output.zip` during enrollment. The encryption key is a one-time value shown in the Play Console enrollment UI.

> **Note:** PEPK migration is optional. If you are publishing for the first time, let Google generate a new signing key. Your `vpnmaster-release.keystore` becomes the upload key only.

---

## Part 3 — Create the App in Play Console

1. Go to [play.google.com/console](https://play.google.com/console) and sign in.
2. Click **"Create app"** (top right).
3. Fill in:
   - **App name:** VPN Master
   - **Default language:** English (United States)
   - **App or game:** App
   - **Free or paid:** choose accordingly
4. Accept the developer policies and click **"Create app"**.

You now have an app shell with package name not yet assigned. The package name (`net.swlr.vpnmaster`) is locked in permanently on your **first upload** — it cannot be changed afterwards.

---

## Part 4 — Set Up Play App Signing

Do this **before your first upload**.

1. In Play Console, select your app → left sidebar → **"Release"** → **"Setup"** → **"App signing"**.
2. Choose **"Use Google-generated key"** (recommended) OR **"Export and upload a key from Java keystore"** if you want to migrate your existing key as the signing key.
3. If enrolling with a new Google-generated key:
   - Click **"Continue"**.
   - Download and record the **App signing key certificate** (SHA-1 / SHA-256) shown on screen — this is the key Google will use to sign APKs for users.
4. Register your **upload certificate** so Google knows to accept your AAB:
   - Go to **"Upload key certificate"** section.
   - Upload `vpnmaster-upload-cert.der` from Part 2b, OR
   - The console may auto-register it on your first AAB upload.
5. **Save all displayed fingerprints** in your password manager:
   - App signing key certificate (Google-managed) — SHA-1, SHA-256
   - Upload key certificate (your keystore) — SHA-1, SHA-256

---

## Part 5 — Build the Release AAB

```bash
cd /Users/esheesle/Development/claude/vpnmaster
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

Ensure `~/.gradle/gradle.properties` has your signing config (see `BUILD.md`).

---

## Part 6 — Create an Internal Test Release

Upload to **Internal Testing** first to validate the AAB before broader release.

1. Play Console → your app → **"Release"** → **"Testing"** → **"Internal testing"**.
2. Click **"Create new release"**.
3. Under **"App bundles"**, click **"Upload"** and select `app-release.aab`.
   - If this is your first upload, the package name is now permanently set to `net.swlr.vpnmaster`.
   - Play App Signing enrollment completes automatically on first upload if you chose a Google-generated key.
4. Fill in **"Release notes"** (what's new — required).
5. Click **"Save"** then **"Review release"** then **"Start rollout to Internal testing"**.
6. Add internal testers: **"Testers"** tab → add Gmail addresses or a Google Group.

---

## Part 7 — Complete the Store Listing

Navigate to **"Store presence"** → **"Main store listing"**.

### Required Fields

| Field | Value |
|---|---|
| App name | VPN Master |
| Short description | ≤ 80 characters |
| Full description | ≤ 4000 characters |

### Required Graphics

| Asset | Size | Notes |
|---|---|---|
| App icon | 512×512 PNG | Must match the app's launcher icon |
| Feature graphic | 1024×500 PNG/JPG | Shown at top of store listing |
| Phone screenshots | Min 2, max 8 | At least 1 required |
| 7-inch tablet screenshots | Optional but recommended | |
| 10-inch tablet screenshots | Optional but recommended | |

---

## Part 8 — VPN-Specific Requirements

VPN apps face extra scrutiny. Complete all of the following or your app will be rejected.

### 8a. Privacy Policy

- Must be hosted at a public URL.
- Must specifically address VPN data practices: what is logged, retention, third-party sharing.
- Enter the URL in Play Console → **"Store presence"** → **"Store settings"** → **"Privacy Policy"**.
- The URL is already prepared in `PRIVACY_POLICY.md` — host it publicly before submitting.

### 8b. App Content Declaration — Permissions

1. Play Console → **"Policy"** → **"App content"**.
2. Under **"Sensitive app permissions"** → **"VPN service"**:
   - Declare your use of `android.permission.BIND_VPN_SERVICE`.
   - Confirm the app is a full VPN client and explain its purpose.
   - Do NOT claim to anonymize users if the server logs traffic.
3. Answer all questions truthfully — false declarations lead to permanent removal.

### 8c. Content Rating

1. Play Console → **"Policy"** → **"App content"** → **"Content rating"**.
2. Click **"Start questionnaire"**, select **"Utilities"** category.
3. Answer "No" to violence/adult content questions.
4. Submit — you will receive a rating (likely **Everyone**).

### 8d. Target Audience

1. Play Console → **"Policy"** → **"App content"** → **"Target audience and content"**.
2. Set minimum age to **18+** if the VPN may access adult content, OR **All ages** if it is a business/privacy tool.
3. VPN apps must **not** target children (under 13) under COPPA.

### 8e. Data Safety

1. Play Console → **"Policy"** → **"App content"** → **"Data safety"**.
2. Declare every data type the app collects or shares:
   - Device or other IDs (Android ID): likely yes
   - Network info: yes (for VPN diagnostics)
   - IP address: note whether it is shared with VPN servers
3. Answer whether data is encrypted in transit (yes — WireGuard encrypts all traffic).
4. Provide a link to your privacy policy here as well.

---

## Part 9 — App Access (If Credentials Are Required)

If testers need an account or credentials to use the VPN:

1. Play Console → **"Release"** → **"Setup"** → **"App access"**.
2. Select **"All or some functionality is restricted"**.
3. Add instructions or demo credentials Google reviewers can use.

If the VPN server is freely accessible with no login, select **"All functionality is available without special access"**.

---

## Part 10 — Promote to Production

After internal testing passes:

1. **Closed Testing (optional):** broader beta group.
2. **Open Testing (optional):** public opt-in beta.
3. **Production:**
   - Play Console → **"Release"** → **"Production"** → **"Create new release"**.
   - Promote the tested AAB from internal testing (no re-upload needed).
   - Set rollout percentage (start with 10–20% for a staged rollout).
   - Click **"Start rollout to Production"**.

VPN apps typically take **3–7 days** for initial review (longer than non-VPN apps).

---

## Part 11 — Key Backup and Security Checklist

- [ ] `vpnmaster-release.keystore` backed up to encrypted offline storage
- [ ] Keystore passwords stored in a password manager
- [ ] `vpnmaster-upload-cert.der` backed up alongside the keystore
- [ ] SHA-1 and SHA-256 fingerprints (both upload key and Play signing key) recorded
- [ ] Keystore file added to `.gitignore` — **never commit it**
- [ ] Signing passwords NOT in `gradle.properties` inside the repo — only in `~/.gradle/gradle.properties`

---

## Part 12 — Subsequent Releases

For every update:

1. Increment `versionCode` (must be strictly greater than previous) and `versionName` in `app/build.gradle.kts`.
2. Run `./gradlew bundleRelease`.
3. Upload the new AAB to Internal Testing, test, then promote to Production.
4. The same upload key (`vpnmaster-release.keystore`) must be used for every release.

---

## Troubleshooting

### "Package name already exists"
Another app owns `net.swlr.vpnmaster`. Since this is your first upload this shouldn't occur — if it does, you likely have a duplicate account or a previous test upload.

### "Upload certificate mismatch"
The AAB was signed with a different keystore than what Google has on file. Ensure `~/.gradle/gradle.properties` points to the same `vpnmaster-release.keystore` used for enrollment.

### "App rejected — VPN policy violation"
Read the rejection reason carefully. Common causes:
- Privacy policy URL not reachable
- Missing or incomplete VPN permission declaration
- App description implies anonymity guarantees the VPN doesn't provide

### "Cannot rotate upload key"
Contact Google Play Developer Support at [support.google.com/googleplay/android-developer](https://support.google.com/googleplay/android-developer). With Play App Signing enrolled, they can rotate the upload key using your identity verification.
