#!/bin/bash
# Read standard device information through a normal ImageCaptureCore session.
# The framework may enumerate media. No uploads or vendor operations.
set -euo pipefail
umask 077
if [[ $# != 1 || ( $1 != --read-device-info && $1 != --build ) ]]; then
    echo 'Usage: bash analysis/run-imagecapture-device-info.sh {--build|--read-device-info}' >&2
    exit 2
fi
script_dir=$(cd "$(dirname "$0")" && pwd)
build_dir="$script_dir/output/macos-device-info"
app_dir="$build_dir/XHalfDeviceInfo.app"
source_hash=$(cat "$script_dir/imagecapture_device_info.m" "$0" | shasum -a 256 | cut -d ' ' -f 1)
previous_hash=$(cat "$build_dir/source.sha256" 2>/dev/null || true)
if [[ $source_hash != "$previous_hash" || ! -x "$app_dir/Contents/MacOS/XHalfDeviceInfo" ]]; then
mkdir -p "$app_dir/Contents/MacOS"
clang -fobjc-arc -Wall -Wextra -Werror \
    -framework Foundation -framework AppKit -framework ImageCaptureCore \
    "$script_dir/imagecapture_device_info.m" \
    -o "$app_dir/Contents/MacOS/XHalfDeviceInfo"
python3 - "$app_dir/Contents/Info.plist" <<'PY'
import plistlib
from pathlib import Path
import sys
Path(sys.argv[1]).write_bytes(plistlib.dumps({
    'CFBundleIdentifier': 'org.xhalf.research.deviceinfo',
    'CFBundleExecutable': 'XHalfDeviceInfo',
    'CFBundleName': 'XHalfDeviceInfo',
    'CFBundlePackageType': 'APPL',
    'CFBundleVersion': '1',
    'LSUIElement': True,
    'NSCameraUsageDescription': 'Read standard camera device information through Image Capture.',
}))
PY
printf '%s\n' "$source_hash" > "$build_dir/source.sha256"
fi
if [[ $1 == --build ]]; then
    echo "Built application without launching: $app_dir"
    exit 0
fi
capture_dir=$(mktemp -d /private/tmp/xhalf-device-info.XXXXXX)
trap 'rm -rf "$capture_dir"' EXIT
/usr/bin/open -W -n -g --stdout "$capture_dir/session.json" \
    --stderr "$capture_dir/stderr.txt" "$app_dir" --args \
    --read-device-info "$capture_dir/device-info.dataset"
if [[ -s "$capture_dir/stderr.txt" ]]; then
    echo 'ImageCaptureCore emitted diagnostics; not printing potentially identifying text.' >&2
fi
python3 - "$script_dir" "$capture_dir" <<'PY'
import json
from pathlib import Path
import sys
sys.path.insert(0, sys.argv[1])
from ptp_device_info import decode_device_info
root = Path(sys.argv[2])
report = json.loads((root / 'session.json').read_text())
report['raw_dataset_retained'] = False
if report.get('status') == 'ok':
    try:
        with (root / 'device-info.dataset').open('rb') as source:
            report['device_info'] = decode_device_info(source.read(65537))
    except (ValueError, OSError):
        report['status'] = 'dataset_decode_failed'
print(json.dumps(report, indent=2))
sys.exit(0 if report.get('status') == 'ok' else 1)
PY
