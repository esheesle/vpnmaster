# VPN Master - Build Instructions

Complete guide to building a release APK on macOS.

## Prerequisites

### 1. Install JDK 17

```bash
brew install openjdk@17
# Add to shell profile:
export JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo "/opt/homebrew/opt/openjdk@17")
export PATH="$JAVA_HOME/bin:$PATH"
```

### 2. Install Android SDK & NDK

**Option A: Via Android Studio (recommended)**
1. Download and install [Android Studio](https://developer.android.com/studio)
2. Open SDK Manager (Settings > Languages & Frameworks > Android SDK)
3. Install:
   - SDK Platforms: Android 14 (API 35)
   - SDK Tools: Android SDK Build-Tools 35, NDK (Side by side) r25c+, CMake, Android SDK Command-line Tools

**Option B: Command-line only**
```bash
brew install --cask android-commandlinetools

# Accept licenses
yes | sdkmanager --licenses

# Install required components
sdkmanager "platform-tools" \
           "platforms;android-35" \
           "build-tools;35.0.0" \
           "ndk;25.2.9519653" \
           "cmake;3.22.1"

# Set environment variables (add to ~/.zshrc or ~/.bash_profile)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/25.2.9519653"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

### 3. Install Native Build Tools (for strongSwan)

```bash
brew install autoconf automake libtool pkg-config gettext
# Ensure gettext is linked:
brew link --force gettext
```

### 4. Install Gradle Wrapper

The project includes Gradle wrapper configuration. On first build, Gradle 8.7 is downloaded automatically. No manual Gradle install needed.

Generate the wrapper scripts:
```bash
cd vpnmaster
gradle wrapper --gradle-version 8.7
# Or if gradle is not installed:
# Download gradlew manually from https://services.gradle.org/distributions/gradle-8.7-bin.zip
```

Alternatively, install Gradle and generate the wrapper:
```bash
brew install gradle
cd vpnmaster
gradle wrapper
```

---

## Project Setup

### 1. Clone and Initialize

```bash
cd /Users/esheesle/Development/claude/vpnmaster

# Initialize git repo
git init
git add .
git commit -m "Initial project structure"

# Add strongSwan submodule
git submodule add https://github.com/strongswan/strongswan.git external/strongswan
git submodule update --init --recursive
```

### 2. Create local.properties

```bash
cat > local.properties << EOF
sdk.dir=$HOME/Library/Android/sdk
ndk.dir=$HOME/Library/Android/sdk/ndk/25.2.9519653
EOF
```

---

## Build strongSwan Native Libraries

The IKEv2 functionality requires building strongSwan's native C libraries. **This step is only needed for IKEv2 support** — WireGuard works without it.

### Automated Build

```bash
./scripts/build-strongswan.sh
```

This builds for all four ABIs (armeabi-v7a, arm64-v8a, x86, x86_64) and places the `.so` files in `app/src/main/jniLibs/`.

### Build for Specific ABIs

To save time, build only for your target device:
```bash
# Most modern phones:
./scripts/build-strongswan.sh --abi arm64-v8a

# Emulator testing:
./scripts/build-strongswan.sh --abi x86_64
```

### Manual Build (if script fails)

See `scripts/build-strongswan.sh` for the detailed steps. The key process is:

1. Run `autogen.sh` in the strongSwan source directory
2. `./configure` with Android-specific flags and NDK toolchain
3. `make && make install`
4. Build the JNI bridge with `ndk-build`
5. Copy resulting `.so` files to `app/src/main/jniLibs/<abi>/`

### WireGuard-Only Build

If you only need WireGuard (no IKEv2), you can skip the native build entirely. The WireGuard tunnel library is pulled from Maven Central automatically. The app will detect if strongSwan native libraries are missing and disable IKEv2 options gracefully.

---

## Updating Dependencies

### Update WireGuard

The WireGuard tunnel library is a Maven dependency. To update:

1. Check latest version at the [WireGuard Android repository](https://github.com/WireGuard/wireguard-android)
2. Update `wireguardTunnel` version in `gradle/libs.versions.toml`
3. Rebuild

### Update strongSwan

```bash
cd external/strongswan
git fetch origin
git checkout <desired-tag-or-commit>
cd ../..
git add external/strongswan
git commit -m "Update strongSwan to <version>"

# Rebuild native libraries
./scripts/build-strongswan.sh
```

---

## Generate Release Signing Key

A release keystore is required for distribution. **Store this securely — losing it means you cannot update your app on Google Play.**

### Create Keystore

```bash
keytool -genkey -v \
  -keystore vpnmaster-release.keystore \
  -alias vpnmaster \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storepass <your-store-password> \
  -keypass <your-key-password> \
  -dname "CN=VPN Master, OU=Development, O=SWLR, L=City, ST=State, C=US"
```

### Configure Signing

Create or edit `~/.gradle/gradle.properties` (NOT the project's gradle.properties — keep secrets out of version control):

```properties
RELEASE_KEYSTORE_PATH=/absolute/path/to/vpnmaster-release.keystore
RELEASE_KEYSTORE_PASSWORD=your-store-password
RELEASE_KEY_ALIAS=vpnmaster
RELEASE_KEY_PASSWORD=your-key-password
```

Alternatively, create a `keystore.properties` file in the project root (add to `.gitignore`):
```properties
storeFile=/absolute/path/to/vpnmaster-release.keystore
storePassword=your-store-password
keyAlias=vpnmaster
keyPassword=your-key-password
```

**Security notes:**
- Never commit the keystore or passwords to version control
- Back up the keystore file securely (e.g., encrypted cloud storage)
- The keystore password and key password can be different

---

## Build Release APK

### Debug Build (for testing)

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build

```bash
# Ensure signing is configured (see above)
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Release Bundle (for Google Play)

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

### Clean Build

```bash
./gradlew clean assembleRelease
```

---

## Install / Sideload

### Via ADB (USB debugging enabled)

```bash
# List connected devices
adb devices

# Install debug build
adb install app/build/outputs/apk/debug/app-debug.apk

# Install release build
adb install app/build/outputs/apk/release/app-release.apk

# Replace existing install
adb install -r app/build/outputs/apk/release/app-release.apk
```

### Transfer APK to Device

1. Copy `app-release.apk` to the device via USB, AirDrop, or cloud storage
2. On the device, enable "Install from Unknown Sources" in Settings
3. Open the APK file to install

---

## Google Play Distribution

### Prepare for Upload

1. **Build an AAB** (Android App Bundle):
   ```bash
   ./gradlew bundleRelease
   ```

2. **Test the release build** thoroughly on multiple devices/API levels

3. **Create a Google Play Developer account** at https://play.google.com/console

4. **Create a new app** in the Play Console

5. **Upload the AAB** to the internal testing track first

6. **Complete store listing:**
   - App name: VPN Master
   - Short description
   - Full description
   - Screenshots (phone, tablet)
   - Feature graphic (1024x500)
   - Privacy policy URL (required for VPN apps)

7. **VPN-specific requirements:**
   - Google Play requires a privacy policy for VPN apps
   - You must declare `VPN_SERVICE` usage in the app content declaration
   - Your app will undergo additional review as a VPN app
   - Complete the "App access" section if the VPN requires credentials

8. **Promote** from internal testing → closed testing → open testing → production

### Play App Signing

Google Play manages app signing keys. When uploading for the first time:
- Select "Let Google manage and protect your app signing key"
- Upload your AAB signed with your upload key (the release keystore above)

---

## Troubleshooting

### Build fails: "SDK location not found"
Create `local.properties` with your SDK path (see Project Setup).

### Build fails: "NDK not configured"
Install NDK via SDK Manager or set `ndk.dir` in `local.properties`.

### strongSwan build fails: "autoconf not found"
```bash
brew install autoconf automake libtool
```

### strongSwan build fails: configure errors
Ensure the submodule is fully initialized:
```bash
git submodule update --init --recursive
cd external/strongswan && ./autogen.sh && cd ../..
```

### APK too large
Build for specific ABIs only:
```bash
# In app/build.gradle.kts, modify ndk.abiFilters:
# abiFilters += listOf("arm64-v8a")  // Most modern devices
```

Or enable APK splits in `app/build.gradle.kts`:
```kotlin
splits {
    abi {
        isEnable = true
        reset()
        include("armeabi-v7a", "arm64-v8a", "x86_64")
        isUniversalApk = false
    }
}
```

### Permission denied: VPN service
The user must grant VPN permission on first connect. The app requests this automatically via `VpnService.prepare()`.

### Always-on VPN not available
Always-on VPN requires Android 8.0+ and the service must declare `SUPPORTS_ALWAYS_ON` metadata (already configured in the manifest).

---

## License

This project integrates:
- **WireGuard** — GPLv2 (https://www.wireguard.com/)
- **strongSwan** — GPLv2 (https://www.strongswan.org/)

The combined application is distributed under the **GNU General Public License v2.0**.
Source code must be made available to recipients of the binary.
