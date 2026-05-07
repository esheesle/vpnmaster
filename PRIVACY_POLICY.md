# VPNMaster — Privacy Policy & Publishing Directions

## Privacy Policy Text

Copy the section below verbatim onto your Google Sites page.

---

**VPNMaster — Privacy Policy**
*Last updated: May 4, 2026*

### Overview

VPNMaster is a WireGuard VPN client for Android. This policy describes what data the app collects, how it is used, and your rights as a user.

**Short version:** VPNMaster does not collect, transmit, or share any personal data. All configuration and settings live entirely on your device.

---

### Data We Collect

**We collect no data.**

VPNMaster does not operate any servers, analytics pipelines, or cloud services. The developer has no access to your VPN configuration, traffic, usage, or device information.

---

### Data Stored on Your Device

All data the app stores remains on your device only:

- **VPN profiles** — WireGuard configuration (private keys, peer endpoints, DNS settings). Stored in an encrypted SQLite database (SQLCipher) in the app's private storage.
- **Settings** — preferences such as watchdog interval, auto-connect on boot, and notification options. Stored in Android DataStore in the app's private storage.
- **Automation token** — an optional random token used to authenticate Tasker/automation commands. Generated locally, never transmitted.
- **Diagnostic logs** — optional in-app logs of connection events. Stored in the app's private storage and never transmitted automatically.

None of this data leaves your device unless you explicitly export a backup or share a configuration file using Android's share functionality.

---

### VPN Traffic

VPNMaster establishes WireGuard tunnels to servers **you configure**. The app itself does not inspect, log, or forward your network traffic. Traffic routing is governed entirely by the WireGuard configuration you provide. The operator of any VPN server you connect to may have their own privacy policy.

---

### Permissions

| Permission | Purpose |
|---|---|
| `BIND_VPN_SERVICE` | Required by Android to create a VPN tunnel |
| `CAMERA` | Optional — used only for scanning WireGuard QR codes |
| `RECEIVE_BOOT_COMPLETED` | Auto-connect on device startup (if enabled) |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Keeps background reconnect reliable |
| `POST_NOTIFICATIONS` | Connection status notifications |
| `FOREGROUND_SERVICE` | Keeps VPN tunnel active in the background |

---

### Third-Party SDKs

VPNMaster does not include any advertising, analytics, or tracking SDKs. The only third-party library that performs network activity is the WireGuard Go library, which handles the WireGuard protocol itself.

---

### Children's Privacy

VPNMaster is not directed at children under 13 and does not knowingly collect any information from children.

---

### Changes to This Policy

If this policy changes materially, the updated version will be posted at this URL with a revised "Last updated" date.

---

### Contact

Questions about this policy: **apps@swlr.net**

---

## Publishing on Google Sites

### Create the site

1. Go to https://sites.google.com and sign in with your Google account.
2. Click **+ Blank** to create a new site. Name it something like "VPNMaster Support".
3. In the **Pages** tab (left sidebar), click **Add page** and name it "Privacy Policy".

### Add the policy content

4. Click into the page body. Paste the policy text from the section above.
5. For the Permissions table, use **Insert → Table** (3 columns × 7 rows) and fill it in manually, or paste the table and reformat using the toolbar.

### Publish

6. Click **Publish** (top right corner).
7. Choose a URL slug (e.g. `tunnelwarden`) and click **Publish**.
8. Your privacy policy URL will be:
   ```
   https://sites.google.com/view/tunnelwarden/privacy-policy
   ```
   Confirm the URL by visiting it before submitting to Play Store.

### Register the URL

9. **Google Play Console:** App content → Privacy policy → paste the URL → Save.
10. **Store listing (optional but recommended):** mention the policy URL in your app description.

### Google Play VPN requirement checklist

Google requires VPN apps to have a privacy policy that covers the following — all are addressed in the draft above:

- [ ] States that the app does not collect personal data (or discloses what it does collect)
- [ ] Explains what the VPN connection is used for
- [ ] Discloses that network traffic passes through the tunnel to a server the user controls
- [ ] Does not misrepresent data practices
- [ ] Provides a contact address

### Maintenance

Update the "Last updated" date at the top of the policy whenever you make material changes. Google may re-review the policy URL during app updates.
