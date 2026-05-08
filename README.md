# VPN Master

A clean, modern WireGuard VPN client for Android. Manage multiple VPN profiles, automate connections with Tasker, and keep your tunnel alive with a built-in watchdog — all from a Material You interface.

## Features

- **WireGuard tunnels** — powered by the official WireGuard Go backend
- **Multiple profiles** — store and switch between any number of VPN configurations
- **QR code import** — scan a WireGuard config QR code to add a profile instantly
- **File import** — open `.conf` files directly from any file manager or share sheet
- **Split tunneling** — route only selected apps (or exclude them) through the VPN
- **Watchdog** — automatically detects and recovers a stalled tunnel
- **Always-on VPN** — integrates with Android's built-in always-on VPN system setting
- **Boot autoconnect** — optionally reconnect to your default profile on device startup
- **Quick Settings tile** — toggle the VPN without opening the app
- **Tasker / automation** — start, stop, and query profiles via broadcast intents
- **Encrypted backup** — export and import all profiles with password-protected backups
- **Diagnostic logs** — in-app event log for troubleshooting connection issues

## Requirements

- Android 8.0 (API 26) or higher
- VPN permission granted on first connect (Android prompts automatically)

## Installation

### From Google Play

Search for **VPN Master** on the Google Play Store.

### Sideload / Build from source

See [BUILD.md](BUILD.md) for full instructions. Quick summary:

1. Install JDK 17 and the Android SDK (API 35) with NDK r27
2. Configure signing keys in `~/.gradle/gradle.properties`
3. Run `./gradlew assembleRelease`
4. Install: `adb install app/build/outputs/apk/release/app-release.apk`

## Getting Started

1. Open the app and navigate to **Profiles**
2. Add a profile by scanning a QR code, opening a `.conf` file, or entering details manually
3. Return to **Home**, select your profile, and tap **Connect**

Set a profile as your default so the home screen connects to it with a single tap.

## Automation (Tasker / Automate / MacroDroid)

VPN Master exposes broadcast intents so any Android automation app can control it.

| Action | Intent |
|--------|--------|
| Start tunnel | `net.swlr.vpnmaster.action.START_TUNNEL` |
| Stop tunnel | `net.swlr.vpnmaster.action.STOP_TUNNEL` |
| List profiles | `net.swlr.vpnmaster.action.LIST_PROFILES` |

Start a tunnel by passing `profile_id` (UUID) or `profile_name` as an extra. Enable **Require auth token** in Settings → Automation to restrict which apps can send these intents.

See [TASKER.md](TASKER.md) for full setup instructions and ADB examples.

## Watchdog

The watchdog periodically probes the tunnel and triggers a reconnect if traffic has stalled. Configure the probe interval (10–120 s) and failure threshold in Settings. Disable the threshold (set to 0) to rely on WireGuard handshake age alone.

## Backup & Restore

Settings → Backup → **Export** saves all profiles to an AES-256-GCM encrypted file. Use **Import** on a new device to restore them. The backup password is required for decryption — there is no recovery mechanism if it is lost.

## Build

See [BUILD.md](BUILD.md) for detailed build, signing, and Google Play publishing instructions.

## Privacy

VPN Master does not collect, transmit, or store any personal data. See [PRIVACY_POLICY.md](PRIVACY_POLICY.md) for the full policy.

## License

VPN Master integrates the [WireGuard](https://www.wireguard.com/) tunnel library, which is licensed under GPLv2. The combined application is therefore distributed under the **GNU General Public License v2.0**.

```
Copyright (C) 2024 SWLR

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU General Public License for more details.
```
