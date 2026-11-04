#!/usr/bin/env python3
"""Inventory selected pinned FRM/driver members without extraction or execution."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
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
