#!/bin/bash
# Build a temporary application and launch only the discovery helper.
set -euo pipefail
if [[ $# != 1 || $1 != --browse ]]; then
    echo 'Usage: bash analysis/run-imagecapture-inventory.sh --browse' >&2
    exit 2
fi
script_dir=$(cd "$(dirname "$0")" && pwd)
build_dir=$(mktemp -d /private/tmp/xhalf-discovery.XXXXXX)
trap 'rm -rf "$build_dir"' EXIT
app_dir="$build_dir/XHalfDiscovery.app"
mkdir -p "$app_dir/Contents/MacOS"
clang -fobjc-arc -Wall -Wextra -Werror \
    -framework Foundation -framework AppKit -framework ImageCaptureCore \
    "$script_dir/imagecapture_inventory.m" \
    -o "$app_dir/Contents/MacOS/XHalfDiscovery"
python3 - "$app_dir/Contents/Info.plist" <<'PY'
import plistlib
from pathlib import Path
import sys
Path(sys.argv[1]).write_bytes(plistlib.dumps({
    'CFBundleIdentifier': 'org.xhalf.research.discovery',
    'CFBundleExecutable': 'XHalfDiscovery',
    'CFBundleName': 'XHalfDiscovery',
    'CFBundlePackageType': 'APPL',
    'CFBundleVersion': '1',
    'LSUIElement': True,
    'NSCameraUsageDescription': 'Discover the connected X half through Image Capture; no camera session or PTP commands.',
}))
PY
/usr/bin/open -W -n -g --stdout "$build_dir/result.json" \
    --stderr "$build_dir/stderr.txt" "$app_dir" --args --browse
cat "$build_dir/result.json"
if [[ -s "$build_dir/stderr.txt" ]]; then
    echo 'ImageCaptureCore emitted diagnostics; not printing potentially identifying text.' >&2
fi
python3 - "$build_dir/result.json" <<'PY'
import json
import sys
with open(sys.argv[1]) as source:
    report = json.load(source)
sys.exit(0 if report.get('unique_match') else 1)
PY
