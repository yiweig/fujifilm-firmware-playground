#!/bin/bash
# Restore the X half loader/test card: MBR + 1.5 GB FAT16 XHALF,
# stock MAIN.BIN, and the EnableTest.txt flag.
#
# Usage:
#   sudo bash analysis/restore_loader_card.sh /dev/disk6
#
# The disk is erased. Only pass the 32 GB SD card; the script refuses
# disks whose size is not in the 30-34 GB range.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FW="$REPO_ROOT/firmware-main.bin"
EXPECTED_FW_SHA="80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"
VOLUME="/Volumes/XHALF"

DISK="${1:-}"
if [[ -z "$DISK" ]]; then
    echo "usage: sudo bash $0 /dev/disk6" >&2
    echo "Find the card with: diskutil list" >&2
    exit 1
fi
if [[ "$DISK" != /dev/disk[0-9]* ]]; then
    echo "refusing: expected a whole-disk device like /dev/disk6, got $DISK" >&2
    exit 1
fi
if [[ ! -f "$FW" ]]; then
    echo "missing $FW" >&2
    exit 1
fi

actual_sha="$(shasum -a 256 "$FW" | awk '{print $1}')"
if [[ "$actual_sha" != "$EXPECTED_FW_SHA" ]]; then
    echo "refusing: $FW is not the stock 2.00 image" >&2
    echo "  expected $EXPECTED_FW_SHA" >&2
    echo "  actual   $actual_sha" >&2
    exit 1
fi

size="$(diskutil info -plist "$DISK" | python3 -c 'import plistlib,sys; print(plistlib.loads(sys.stdin.buffer.read()).get("TotalSize", 0))')"
if (( size < 30000000000 || size > 34000000000 )); then
    echo "refusing: $DISK is $size bytes, not a 30-34 GB SD card" >&2
    diskutil info "$DISK" | sed -n '1,20p' >&2
    exit 1
fi

echo "About to erase:"
diskutil info "$DISK" | grep -E "Device / Media Name|Disk Size|Media Name|Volume Name" || true
echo
read -r -p "Type YES to erase $DISK and build the XHALF loader card: " answer
if [[ "$answer" != "YES" ]]; then
    echo "cancelled" >&2
    exit 1
fi

echo "--- unmounting"
diskutil unmountDisk "$DISK" >/dev/null 2>&1 || true

echo "--- partitioning (MBR, FAT16, 1500 MB)"
diskutil partitionDisk "$DISK" MBR MS-DOS XHALF 1500M >/dev/null

echo "--- waiting for $VOLUME"
for _ in $(seq 1 30); do
    if [[ -d "$VOLUME" ]]; then
        break
    fi
    sleep 1
done
if [[ ! -d "$VOLUME" ]]; then
    echo "error: $VOLUME did not mount" >&2
    exit 1
fi

echo "--- copying stock MAIN.BIN (14.9 MB)"
cp "$FW" "$VOLUME/MAIN.BIN"
printf 'x' > "$VOLUME/EnableTest.txt"

echo "--- verifying"
disk_sha="$(shasum -a 256 "$VOLUME/MAIN.BIN" | awk '{print $1}')"
if [[ "$disk_sha" != "$EXPECTED_FW_SHA" ]]; then
    echo "error: MAIN.BIN hash mismatch on card: $disk_sha" >&2
    exit 1
fi
ls -l "$VOLUME/MAIN.BIN" "$VOLUME/EnableTest.txt"
echo "MAIN.BIN sha256 $disk_sha"

sync
diskutil eject "$DISK" >/dev/null 2>&1 || true

echo
echo "Done. Insert the card into the camera and boot loader mode:"
echo "  MOVIE, flash ON, hold PLAY through power-on (~15 s)."
echo "Then say 'ready'."
