#!/system/bin/sh
# Start the Shizuku server as shell uid from the device side (POC automation: no human tapping
# around in the Shizuku app). Pushed to /data/local/tmp/start-shizuku.sh by tools/ex/03-shizuku.ps1
# when the device does not have it yet.
#
# Known limitation (docs/poc-findings.md, ticket #6/#7): a server started this way runs as bare
# shell uid, so the Shizuku manager permission handshake never happens and client apps keep
# `pingBinder() == false`. Starting it once from inside the Shizuku app is the human-only way to
# get a usable fallback channel.
set -e
D=/data/local/tmp/shizuku_starter
rm -rf "$D"
mkdir -p "$D"
cd "$D"
APP_PATH=$(pm path moe.shizuku.privileged.api | head -1 | cut -d: -f2)
unzip -o "$APP_PATH" 'lib/arm64-v8a/*' >/dev/null
chmod 700 lib/arm64-v8a/*.so
ln -sf lib/arm64-v8a/libshizuku.so shizuku_starter
echo "APP_PATH=$APP_PATH"
LD_LIBRARY_PATH="$D/lib/arm64-v8a" ./shizuku_starter "$APP_PATH"
