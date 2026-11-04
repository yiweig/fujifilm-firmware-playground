#!/usr/bin/env python3
"""Inspect pinned FRM/driver members and selected event tables without execution."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile

ARCHIVES = {
    'Reflash_V2.6.zip': '88fdb53627ff7c347f2a060e50a4de04b43b78516e83144c072de0d926a64969',
    'iCatch PC Driver.zip': '0e6d79b2c3e957d097678d03d316bd2cb754f05dd3ab375b9c700761b9414014',
}
MEMBERS = {
    'Reflash_V2.6.zip': ['Reflash_V2.6/Download/' + n for n in
        ['FRM.exe', 'USB_CAM.dll', 'USB_CAM5000.ini', 'frm_user.ini',
         'Profiles/spca6350_profile.ini']],
    'iCatch PC Driver.zip': ['iCatch PC Driver/Icatchai.inf'],
}


def event_tables(data):
    """Decode selected pinned PE jump/message tables, not arbitrary UI behavior."""
    if hashlib.sha256(data).hexdigest() != 'c5be8958bc694fe0b95a505d05fc475d8279df5f5a065570090aa3a78b392365':
        raise ValueError('wrong pinned FRM executable')
    pe = struct.unpack_from('<I', data, 0x3c)[0]
    count = struct.unpack_from('<H', data, pe + 6)[0]
    opt_size = struct.unpack_from('<H', data, pe + 20)[0]
    opt = pe + 24
    if (data[pe:pe + 4] != b'PE\0\0'
            or struct.unpack_from('<H', data, opt)[0] != 0x10b
            or struct.unpack_from('<I', data, opt + 28)[0] != 0x400000):
        raise ValueError('unexpected pinned FRM PE mapping')
    sections = [struct.unpack_from('<IIII', data, opt + opt_size + 40 * i + 8)
                for i in range(count)]

    def read(address, size):
        for _, rva, raw_size, raw in sections:
            delta = address - 0x400000 - rva
            if 0 <= delta and delta + size <= raw_size and raw + delta + size <= len(data):
                return data[raw + delta:raw + delta + size]
        raise ValueError('selected PE bytes are not file-backed')

    def call_target(address):
        encoded = read(address, 5)
        if encoded[0] != 0xe8:
            raise ValueError('expected selected direct x86 CALL')
        return hex(address + 5 + struct.unpack_from('<i', encoded, 1)[0])

    dispatch = []
    for indices, targets in [(0x42e9f0, 0x42e9e0), (0x433a1c, 0x433a08)]:
        keys = []
        for key, label in [(0x0d, 'Enter'), (0x1b, 'Escape'), (0x74, 'F5'), (0x75, 'F6')]:
            selector = read(indices + key - 0x0d, 1)[0]
            target = struct.unpack('<I', read(targets + 4 * selector, 4))[0]
            keys.append({'key': label, 'virtual_key': hex(key), 'selector': selector,
                         'branch_address': hex(target)})
        dispatch.append({'index_table': hex(indices), 'target_table': hex(targets), 'keys': keys})
    return {'coordinate_system': 'FRM PE32 analysis VA, image base 0x00400000',
            'key_dispatch_tables': dispatch,
            'selected_direct_calls': {hex(a): call_target(a) for a in
                [0x42e999, 0x42e9c4, 0x4339a0, 0x4339d2, 0x42a2da]},
            'selected_timer_message_entries': [{'address': hex(a),
                'words': [hex(v) for v in struct.unpack('<6I', read(a, 24))]}
                for a in [0x4478d8, 0x448a28, 0x448bd0]],
            'limit': 'Selected jump/message tables and direct CALL encodings only; no complete UI/event reachability or live behavior.'}


def inventory(root):
    records = []
    for name, expected in ARCHIVES.items():
        path = root / 'drivers' / name
        if not path.is_file():
            raise ValueError('input must be a retained regular archive')
        raw = path.read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected:
            raise ValueError('wrong retained archive SHA256: ' + name)
        with zipfile.ZipFile(io.BytesIO(raw)) as archive:
            members = []
            for member in MEMBERS[name]:
                info = archive.getinfo(member)
                if info.file_size > 1024 * 1024:
                    raise ValueError('selected member exceeds inventory limit')
                data = archive.read(member)
                item = {'member': member, 'bytes': len(data),
                        'sha256': hashlib.sha256(data).hexdigest()}
                if member.endswith('/FRM.exe'):
                    item['selected_event_tables'] = event_tables(data)
                if member.lower().endswith(('.ini', '.inf')):
                    encoding = 'utf-16' if data[:2] in (b'\xff\xfe', b'\xfe\xff') else 'gb18030'
                    text = data.decode(encoding)
                    if member.endswith('USB_CAM5000.ini'):
                        pattern = r'^(?:IC_ID|VendorID|ProductID|ID_NUMBER|BULK_ISP_SIZE|BulkDnload_\w+|ISP_Dnload_\w+|FRMDnloadRunISP_\w+|GetBoot\w*|Boot\w*)\s*='
                    elif member.endswith('frm_user.ini'):
                        pattern = r'^\s*(?:ISPName|PlatformName|SettingFilename|DramBootFlag|SettingFileFlag)\s*='
                    elif member.endswith('profile.ini'):
                        pattern = r'^\s*(?:PlatformId|StorageType|DefISPName|ulCoreAddr|NormalIspParamAddr|DramBootParFunc|SettingAddr)\s*='
                    else:
                        pattern = r'VID_2AAD&PID_6500&MI_05|^\s*CAMERA.DeviceDesc\s*='
                    item['selected_lines'] = [dict(line=i, text=line)
                        for i, line in enumerate(text.splitlines(), 1)
                        if re.search(pattern, line, re.I)]
                members.append(item)
        records.append({'archive': name, 'sha256': expected, 'bytes': len(raw),
                        'members': members})
    return {'source_commit': 'dc437ced8fdc9564e39ca550af6a1d53b1037714',
            'scope': 'Selected archive metadata/configuration only; no driver install, binary execution or device access.',
            'archives': records,
            'limits': ['Names/configuration do not prove runtime selection, protocol compatibility, hardware identity or safe entry.',
                       'Use companion REA caller report for code evidence; no disassembly is produced by this script.']}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('corpus', type=Path)
    args = ap.parse_args()
    try:
        print(json.dumps(inventory(args.corpus), indent=2))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        ap.error(str(error))


if __name__ == '__main__':
    main()
