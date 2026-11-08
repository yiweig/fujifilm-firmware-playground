#!/bin/bash
# Read-only dump of the X half SD card's first and last 64 MiB.
# Usage: sudo bash analysis/read_sd_card.sh [/dev/rdisk6]
#
# Safety checks: the target must be the ~31.9 GB removable SD card.
# The script only reads; it never writes to the card. It unmounts the
# FAT partition for a consistent raw read and remounts it afterwards.

set -euo pipefail

DEV="${1:-/dev/rdisk6}"
DISK="${DEV/rdisk/disk}"

if [ ! -c "$DEV" ] && [ ! -b "$DEV" ]; then
  echo "not a device node: $DEV" >&2
  exit 1
fi

INFO="$(diskutil info "$DISK")"
echo "$INFO" | grep -q "31.9 GB" || {
  echo "refusing: $DISK is not the 31.9 GB SD card" >&2
  echo "$INFO" | grep -E "Disk Size|Device Node|Removable" >&2
  exit 1
}
echo "$INFO" | grep -q "Removable Media:.*Removable" || {
  echo "refusing: $DISK is not removable" >&2
  exit 1
}

TOTAL_SECTORS=62333952
FIRST_MIB=64
LAST_MIB=64
FIRST_BYTES=$((FIRST_MIB * 1024 * 1024))
LAST_SECTORS=$((LAST_MIB * 1024 * 1024 / 512))
LAST_SKIP=$((TOTAL_SECTORS - LAST_SECTORS))

OUT_FIRST=/private/tmp/xhalf-sd-first-64m.bin
OUT_LAST=/private/tmp/xhalf-sd-last-64m.bin
OUT_BOUNDARY=/private/tmp/xhalf-sd-partend-64m.bin
# Partition ends at LBA 2048 + 2,929,688 = 2,931,736. Read 32 MiB
# before and 32 MiB after the boundary.
BOUNDARY_START=$((2931736 - 65536))
BOUNDARY_SECTORS=$((64 * 1024 * 1024 / 512))

echo "--- unmounting ${DISK}s1 (if mounted)"
diskutil unmount "${DISK}s1" 2>/dev/null || true

echo "--- reading first ${FIRST_MIB} MiB"
dd if="$DEV" of="$OUT_FIRST" bs=1048576 count="$FIRST_MIB" status=progress

echo "--- reading last ${LAST_MIB} MiB (sector ${LAST_SKIP}, ${LAST_SECTORS} sectors)"
dd if="$DEV" of="$OUT_LAST" bs=512 skip="$LAST_SKIP" count="$LAST_SECTORS" status=progress

echo "--- reading partition-boundary 64 MiB (sector ${BOUNDARY_START}, ${BOUNDARY_SECTORS} sectors)"
dd if="$DEV" of="$OUT_BOUNDARY" bs=512 skip="$BOUNDARY_START" count="$BOUNDARY_SECTORS" status=progress

echo "--- remounting ${DISK}s1"
diskutil mount "${DISK}s1" 2>/dev/null || true

echo "--- results"
ls -lh "$OUT_FIRST" "$OUT_LAST" "$OUT_BOUNDARY"
shasum -a 256 "$OUT_FIRST" "$OUT_LAST" "$OUT_BOUNDARY"
