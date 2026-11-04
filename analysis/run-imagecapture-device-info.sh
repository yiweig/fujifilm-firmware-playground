#!/bin/bash
# Read one fixed standard metadata operation through ImageCaptureCore.
# The framework may enumerate media. No uploads or vendor operations.
set -euo pipefail
umask 077
if [[ $# != 1 || ( $1 != --read-device-info && $1 != --read-storage-ids && $1 != --read-storage-info-known && $1 != --build && $1 != --self-test ) ]]; then
    echo 'Usage: bash analysis/run-imagecapture-device-info.sh {--build|--self-test|--read-device-info|--read-storage-ids|--read-storage-info-known}' >&2
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
    'NSCameraUsageDescription': 'Read standard camera or storage metadata through Image Capture.',
}))
PY
printf '%s\n' "$source_hash" > "$build_dir/source.sha256"
fi
if [[ $1 == --build ]]; then
    echo "Built application without launching: $app_dir"
    exit 0
fi
if [[ $1 == --self-test ]]; then
    exec "$app_dir/Contents/MacOS/XHalfDeviceInfo" --self-test
fi
capture_dir=$(mktemp -d /private/tmp/xhalf-device-info.XXXXXX)
trap 'rm -rf "$capture_dir"' EXIT
/usr/bin/open -W -n -g --stdout "$capture_dir/session.json" \
    --stderr "$capture_dir/stderr.txt" "$app_dir" --args \
    "$1" "$capture_dir/device-info.dataset"
if [[ -s "$capture_dir/stderr.txt" ]]; then
    echo 'ImageCaptureCore emitted diagnostics; not printing potentially identifying text.' >&2
fi
python3 - "$script_dir" "$capture_dir" "$1" <<'PY'
import json
from pathlib import Path
import sys
sys.path.insert(0, sys.argv[1])
from ptp_device_info import decode_device_info, decode_storage_ids, decode_storage_info
root = Path(sys.argv[2])
report = json.loads((root / 'session.json').read_text())
report['raw_dataset_retained'] = False
if report.get('status') == 'ok':
    try:
        with (root / 'device-info.dataset').open('rb') as source:
            data = source.read(65537)
            if sys.argv[3] == '--read-storage-ids':
                report['storage_ids'] = decode_storage_ids(data)
            elif sys.argv[3] == '--read-storage-info-known':
                report['storage_info'] = decode_storage_info(data)
            else:
                report['device_info'] = decode_device_info(data)
    except (ValueError, OSError):
        report['status'] = 'dataset_decode_failed'
print(json.dumps(report, indent=2))
sys.exit(0 if report.get('status') == 'ok' else 1)
PY
