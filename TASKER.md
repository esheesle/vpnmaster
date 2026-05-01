# VPN Master - Tasker / Automation Integration Guide

VPN Master supports external control via broadcast intents, making it compatible with Tasker, Automate, MacroDroid, and any other Android automation app that can send intents.

## Finding Your Profile IDs

You can find your profile IDs in the app:

1. Open VPN Master
2. Go to **Settings** (bottom navigation)
3. Scroll to the **Automation (Tasker)** section
4. Your profiles and their IDs are listed there
5. Tap a profile to copy its ID to your clipboard

## Actions

### Start a Tunnel

Connects to a specific VPN profile.

| Field       | Value |
|-------------|-------|
| Action      | `net.swlr.vpnmaster.action.START_TUNNEL` |
| Package     | `net.swlr.vpnmaster` |
| Target      | Broadcast Receiver |

You must include **one** of the following extras:

| Extra Key      | Value | Description |
|----------------|-------|-------------|
| `profile_id`   | UUID string | The profile's unique ID (most reliable) |
| `profile_name` | Text | The profile's display name (case-insensitive match) |

If both are provided, `profile_id` takes priority.

### Stop the Tunnel

Disconnects the active VPN tunnel.

| Field       | Value |
|-------------|-------|
| Action      | `net.swlr.vpnmaster.action.STOP_TUNNEL` |
| Package     | `net.swlr.vpnmaster` |
| Target      | Broadcast Receiver |

No extras are required. This stops whatever tunnel is currently active.

### List Profiles

Requests a list of all configured profiles. The result is sent back as a broadcast.

| Field       | Value |
|-------------|-------|
| Action      | `net.swlr.vpnmaster.action.LIST_PROFILES` |
| Package     | `net.swlr.vpnmaster` |
| Target      | Broadcast Receiver |

**Response broadcast:**

| Field       | Value |
|-------------|-------|
| Action      | `net.swlr.vpnmaster.action.PROFILE_LIST` |
| Extra       | `profiles` - newline-separated list in format `id|name|type` |

Example response extra value:
```
a1b2c3d4-e5f6-7890-abcd-ef1234567890|Home Server|WireGuard
f9e8d7c6-b5a4-3210-fedc-ba0987654321|Work VPN|IKEv2
```

## Tasker Setup

### "Send Intent" Action

1. In Tasker, create a new **Task**
2. Add action: **System > Send Intent**
3. Fill in the fields:

**To start a tunnel:**
- Action: `net.swlr.vpnmaster.action.START_TUNNEL`
- Extra: `profile_id:a1b2c3d4-e5f6-7890-abcd-ef1234567890`
- Package: `net.swlr.vpnmaster`
- Target: **Broadcast Receiver**

**To stop the tunnel:**
- Action: `net.swlr.vpnmaster.action.STOP_TUNNEL`
- Package: `net.swlr.vpnmaster`
- Target: **Broadcast Receiver**

### Example: Connect on Wi-Fi, Disconnect on Mobile

**Profile:** State > Net > Wifi Connected, SSID: `YourHomeSSID`

**Entry Task (Connect):**
1. Send Intent
   - Action: `net.swlr.vpnmaster.action.START_TUNNEL`
   - Extra: `profile_name:Home Server`
   - Package: `net.swlr.vpnmaster`
   - Target: Broadcast Receiver

**Exit Task (Disconnect):**
1. Send Intent
   - Action: `net.swlr.vpnmaster.action.STOP_TUNNEL`
   - Package: `net.swlr.vpnmaster`
   - Target: Broadcast Receiver

### Example: Toggle VPN on Schedule

**Profile:** Time > From 08:00 to 17:00, Every Day

**Entry Task (Start work VPN):**
1. Send Intent
   - Action: `net.swlr.vpnmaster.action.START_TUNNEL`
   - Extra: `profile_name:Work VPN`
   - Package: `net.swlr.vpnmaster`
   - Target: Broadcast Receiver

**Exit Task (Stop):**
1. Send Intent
   - Action: `net.swlr.vpnmaster.action.STOP_TUNNEL`
   - Package: `net.swlr.vpnmaster`
   - Target: Broadcast Receiver

## ADB / Command Line

You can also trigger these actions via `adb` for testing or scripting:

```bash
# Start a tunnel by profile ID
adb shell am broadcast -a net.swlr.vpnmaster.action.START_TUNNEL \
  -n net.swlr.vpnmaster/.service.TaskerReceiver \
  --es profile_id "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

# Start a tunnel by profile name
adb shell am broadcast -a net.swlr.vpnmaster.action.START_TUNNEL \
  -n net.swlr.vpnmaster/.service.TaskerReceiver \
  --es profile_name "Home Server"

# Stop the tunnel
adb shell am broadcast -a net.swlr.vpnmaster.action.STOP_TUNNEL \
  -n net.swlr.vpnmaster/.service.TaskerReceiver

# List profiles
adb shell am broadcast -a net.swlr.vpnmaster.action.LIST_PROFILES \
  -n net.swlr.vpnmaster/.service.TaskerReceiver
```

## Notes

- VPN permission must have been granted at least once through the app's UI before Tasker can start a tunnel. Android requires the user to approve VPN access interactively the first time.
- Starting a tunnel while another is active will disconnect the current tunnel and connect to the new one.
- Profile name matching is case-insensitive. If multiple profiles share the same name, use `profile_id` instead.
- The Quick Settings tile in the notification shade also provides one-tap toggle access without needing Tasker.
