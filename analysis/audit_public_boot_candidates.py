"""Read-only, bounded audit of the previously retained V50 comparison corpus.

Does not download, extract archives, execute vendor software, or access a camera.
Offsets are file offsets, never inferred runtime addresses.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import zipfile


def occurrences(data, needle):
    result, start = [], 0
    while (pos := data.find(needle, start)) >= 0:
        result.append(f"0x{pos:x}")
        start = pos + 1
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("corpus", type=Path)
    args = parser.parse_args()
    result = {
        "repository": "https://github.com/Linouth/iCatch-V50-Playground",
        "retained_revision": "dc437ced8fdc9564e39ca550af6a1d53b1037714",
        "scope": "Selected retained files only; offsets are file coordinates. Exact byte absence is not implementation absence.",
        "files": [],
    }
    names = [
        "reversing/v50ProSE/winbond_dump.bin",
        "reversing/v50ProSE/AKASO_V50 Pro SE_190315_V1/SPHOST.BRN",
        "drivers/Reflash_V2.6.zip",
        "fw/RunCam5_Firmware_V1.2.2.zip",
        "reversing/offset3.gzf",
    ]
    for name in names:
        data = (args.corpus / name).read_bytes()
        item = {"path": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        if data.startswith(b"version https://git-lfs.github.com/spec/v1\n"):
            item["kind"] = "Git LFS pointer; payload not present or searched"
            item["pointer_text"] = data.decode("ascii").strip()
        elif name.endswith(".zip"):
            with zipfile.ZipFile(args.corpus / name) as archive:
                item["soc_isp_binary_members"] = [
                    {"name": entry.filename, "size": entry.file_size}
                    for entry in archive.infolist()
                    if re.search(r"(?:^|/)(?:spca\d+|v\d+)isp\.bin$", entry.filename, re.IGNORECASE)
                ]
            item["kind"] = "ZIP inventory only; members not executed"
        else:
            item["kind"] = "Raw file byte search; no ROM or load-address classification"
            item["prefix_hex"] = data[:32].hex()
            item["little_endian_pointer_occurrences"] = {
                f"0x{value:x}": occurrences(data, struct.pack("<I", value))
                for value in (0xb24, 0xb34, 0x4e34, 0x1d406)
            }
            item["marker_occurrences"] = {
                text: occurrences(data, text.encode("ascii"))
                for text in ("NAND RSV", "DRAM CODE", "SPCA6500", "SPBoot", "SP-Boot", "cpuinv", "cpuwb")
            }
        result["files"].append(item)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
