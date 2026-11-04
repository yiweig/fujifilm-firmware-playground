# Factory PTP: live marker activation, cold-data read, and deactivation

Date: 2026-11-04. First live use of the factory PTP candidate reviewed in
[cold-data read assessment](ptp-cold-data-read-assessment.md) and the
[marker activation review](ptpap-marker-activation-review.md). Installed
firmware confirmed by the user as 2.00; analyzed image SHA-256
`80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`.
No firmware, calibration, serial, or settings write was performed. The only
operations the live client can encode are `OpenSession`, `CloseSession`,
`1025(0)` (file load into RAM) and `1027` chunk **reads**; no write-direction
or persistent-write operation exists in its allowlist.

## Transport

macOS cannot provide exclusive, auditable access (`ptpcamerad` owns the
interface; ImageCapture framework traffic is unmeasured). The camera was
therefore passed through to a disposable Ubuntu 24.04.3 ARM64 VM (UTM 5.0.8,
QEMU backend, "Share USB devices from host"), where
[`xhalf_ptp_linux.py`](xhalf_ptp_linux.py) (pyusb, endpoints bulk OUT `0x03` /
IN `0x82`) was the only client. Linux `usbmon` on the camera's bus recorded
every transfer. Docker Desktop was ruled out: it has no USB passthrough on
macOS.

## Sequence and wire evidence

1. **No-marker baseline (VM):** `--probe` returned
   `NOT ACTIVATED: 1027(0) -> 2005`, session closed, exit 10. usbmon: one
   kernel control pair plus exactly three bulk-OUT/bulk-IN pairs
   (OpenSession, `1027(0)`, CloseSession). This replicates the earlier
   macOS ImageCapture baseline through a fully controlled transport.
2. **Marker creation:** camera off, card removed. Root contained only
   `ACTIVITY/`, `DCIM/`, `FPATR/` (no pre-existing marker, no
   `FPUPDATE.DAT`). `EnablePTPAP.txt` was created with a single ASCII byte
   `1`; card ejected cleanly and reinserted.
3. **Activation probe:** `--probe` returned
   `ACTIVATED: 1027(0) -> 2001, 5 params`, session closed, exit 0. Same
   14-line usbmon profile as the baseline. **Marker activation is now a live
   fact, not only an emulator result.**
4. **Record acquisition:** `--acquire` completed the double read
   (`1025(0)` + 64 chunk reads, twice, byte-identical) and the record passed
   the reader's weak byte-sum checksum validation. usbmon: 530 lines, all
   from the allowlisted sequence.
5. **Deactivation:** marker deleted in the card reader, card restored to its
   original three directories, fresh normal boot, same probe returned
   `NOT ACTIVATED (2005)` with the same wire profile. **Ordinary restart
   revokes the factory registrations**, consistent with the intended
   stage-28 table zero-fill. Camera powered off and released afterwards.

## Acquired record (kept outside git)

1,024 bytes, SHA-256
`58efa22442bbb7524116d4d43fcd68e916c92951cc2f94aae6ab94a0947169af`.
Copies: VM `~/colddata.bin`, Mac `/private/tmp/xhalf-colddata-2026-11-04.bin`.
Checksum: valid (weak byte sum only; not authenticity). Freshness caveat
unchanged in principle — the firmware ignores file-read errors — but the
buffer is the loaded factory record and two independent acquisitions within
the session matched byte-for-byte.

Decoded fields that gate the remaining entry routes:

| Field | Offset | Live value | Consequence |
| --- | --- | --- | --- |
| `DisableTest` (low byte) | `0x0a` | `1` | Calibration button combo alone is blocked; it would also need the `EnableTest.txt` SD override |
| `FgBootType` | `0x68` | `0x18` | Recorded; no entry conclusion drawn here |
| `FgUsbMode` | `0x6a` | `0x18` (low nibble 8) | **Normal-UI loader branch is closed on this unit.** Ordinary USB cable events will not select API mode 4 (getter returns 0 → selection flag 1; see [normal UI entry](normal-usb-loader-entry.md)) |
| `FgAutoClbt` | `0x6c` | `0x18` (low nibble 8) | **Automatic calibration scheduling is enabled.** On calibration entry, event `0xf0000007` (open selected item) is queued |
| confirmation field | `0x168` | `0` | The extra self-confirming event pair (`5`,`7`) is *not* queued via this field; the item-20 (AF LAMP) exception and the auto-selected item remain to be determined from this record |

## Interpretation

- R1 narrows concretely: the "maybe the saved USB setting already permits
  the loader" hypothesis is dead for this camera. Remaining retail entry
  candidates are calibration entry (gated, stateful) or a route not yet
  found (e.g. the UVC debug-event producers).
- Calibration entry is **not** cleared by this read. It still: needs
  `EnableTest.txt` (reversible), can attempt a whole-buffer cold-data save
  during initialization, and queues an item-open event under the live
  `FgAutoClbt` value. Two offline questions now decide its risk profile:
  (1) which calibration item `InitClbtData` auto-selects from this exact
  record (especially whether it is item 20), and (2) what bytes the
  initialization save would change relative to this acquired record.
  Both are answerable offline with the record as emulator fixture.
- This interface is a factory-data read path only. It does not provide
  arbitrary memory access, RAM execution, or cold recovery; the record is
  not a flash backup and must not be treated as one.

## Reproduction (VM)

```sh
# inside the passthrough VM, camera on the USB screen
sudo sh -c 'cat /sys/kernel/debug/usb/usbmon/3u > /tmp/cap.txt &'
sudo python3 ~/xhalf_ptp_linux.py --probe     # 0=activated 10=not 2=stop
sudo python3 ~/xhalf_ptp_linux.py --acquire ~/colddata.bin
```

Raw usbmon captures and the record remain outside git.
