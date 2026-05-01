#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

echo "==> Sanity check: keystore / secrets must not be tracked"
if git ls-files | grep -iE "keystore|\.jks$|local\.properties|secret|password"; then
  echo "ABORT: sensitive file(s) tracked. Resolve before continuing."
  exit 1
fi

echo "==> Unstaging stale strongSwan submodule gitlink"
git rm --cached external/strongswan 2>/dev/null || true

echo
echo "==> Commit 1: Remove strongSwan/IKEv2 backend"
git add .gitmodules .gitignore BUILD.md
git add app/build.gradle.kts app/proguard-rules.pro
git add app/src/main/java/net/swlr/vpnmaster/vpn/ikev2/
git add app/src/main/java/net/swlr/vpnmaster/vpn/VpnOrchestrator.kt
git add app/src/main/java/net/swlr/vpnmaster/config/ConfigImporter.kt
git add gradle.properties gradle/libs.versions.toml gradle/wrapper/gradle-wrapper.properties

# Deletions of submodule dir + build script
git add -u scripts/ external/ 2>/dev/null || true
[ -d scripts ] && git add scripts/ 2>/dev/null || true

git commit -m "Remove strongSwan/IKEv2 backend, keep WireGuard only"

echo
echo "==> Commit 2: Tasker, QS Tile, Logs, About, and related refactors"
git add -A
git commit -m "Add Tasker integration, QS Tile, Logs and About screens"

echo
echo "==> Done. Current status:"
git status
echo
echo "==> Last two commits:"
git log --oneline -5
