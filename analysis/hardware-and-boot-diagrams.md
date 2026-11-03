# Fujifilm X half hardware, boot, and storage diagrams

These diagrams are a living visual index of the evidence collected from X half
firmware 2.00. They do not represent a manufacturer schematic. A block can mean
"code for this hardware exists" without proving that the production camera
populates or wires every supported peripheral.

Artifact identities:

- `FPUPDATE.DAT` SHA-256:
  `3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68`
- extracted `DRAM CODE` SHA-256:
  `80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923`
- complete card updater SHA-256:
  `4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`

Consolidation refresh: 2026-11-03. The updater hash previously listed here
(`e65576...df51`) identifies a historical shortened extract that omitted
`0x340` bytes. Use the [complete updater layout](updater-image-layout.md) for
current coordinates; old evidence retains its historical identity.
See the [architecture guide](architecture-guide.md) for the software model and
[RAM/recovery roadmap](ram-launch-and-recovery-roadmap.md) for current priorities.

## Legend

The labels remain authoritative if a renderer does not display colors:

| Appearance | Meaning |
| --- | --- |
| Green, solid | observed directly in the shipped artifacts or passive hardware enumeration |
| Yellow, dashed | derived or inferred from multiple observations |
| Gray, dotted | unresolved, absent from the package, or physical use unconfirmed |
| Red | updater-writable or otherwise boot-critical; not safe to treat as ordinary data |

"Observed" still means **static observation** unless a box explicitly says
passively observed on the physical camera. It does not mean we executed the
path.

## 1. Hardware-oriented block diagram

```mermaid
flowchart LR
  classDef observed fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px,color:#111;
  classDef inferred fill:#fff8e1,stroke:#f9a825,stroke-width:2px,stroke-dasharray:6 4,color:#111;
  classDef unknown fill:#eeeeee,stroke:#616161,stroke-width:2px,stroke-dasharray:2 4,color:#111;
  classDef critical fill:#ffebee,stroke:#c62828,stroke-width:3px,color:#111;

  sensor["Image sensor<br/>exact part and electrical interface unknown"]:::unknown
  displays["Main + secondary displays<br/>physically observed; interface mapping unconfirmed"]:::inferred
  sdcard["Removable SD card"]:::observed
  usbplug["USB connector<br/>normal mode: 04CB:030A PTP"]:::observed
  nand["Internal NAND flash<br/>capacity / part identity unresolved"]:::critical
  wifi["Broadcom BCM43456C5 support<br/>physical population and bus inferred"]:::inferred
  uartpads["UART pads / service console<br/>not located"]:::unknown
  audioio["Microphone / speaker / ADC wiring<br/>not mapped"]:::unknown

  subgraph soc["SPCA6500 SoC — observed platform identity"]
    cpu["ARMv7 application cores<br/>multiprocessor startup; core count unresolved"]:::observed
    gic["GICv2<br/>0x10401000 / 0x10402000"]:::inferred
    sram["On-chip SRAM<br/>0x3f000000..0x3f00ffff"]:::inferred
    sys["System / GPIO<br/>0x10000000"]:::observed
    storage["NAND / SD / SDIO / UART complex<br/>0x10004000"]:::observed
    usb["USB device + OHCI host<br/>0x10005000"]:::observed
    audio["Audio + ADC<br/>0x10006000"]:::observed
    jpeg["JPEG codec<br/>0x10008000"]:::observed
    front["Sensor front end / LVDS<br/>0x10009000"]:::observed
    display["Display controller<br/>0x1000a000"]:::observed
    ime["IME image engine<br/>0x1000b000"]:::observed
    h264["H.264 codec<br/>0x1000c000"]:::observed
    cdsp["CDSP image pipeline<br/>0x10002000 / 0x1000d000 / 0x10011000"]:::observed
    gpu["GPU / HWC<br/>0x10018000"]:::observed
    misc["Unknown blocks<br/>0x10001000, 0x10016000, 0x10017000"]:::unknown
  end

  dram["External DRAM<br/>initialized image at 0x40000000<br/>total size unresolved"]:::observed
  cameraos["iCatch / SP5K camera application<br/>DRAM CODE"]:::observed
  linux["Linux 3.18.11 + OpenWrt<br/>separate packaged environment"]:::observed

  sensor -. "physical route inferred" .-> front
  front --> cdsp
  cdsp --> ime
  cdsp --> jpeg
  cdsp --> h264
  ime --> gpu
  gpu -. "display path inferred" .-> display
  display -. "exact outputs unknown" .-> displays

  cpu <--> gic
  cpu <--> sram
  cpu <--> dram
  cpu <--> sys
  cpu -->|"executes"| cameraos
  cpu -. "launch relationship not fully traced" .-> linux

  storage <--> nand
  storage <--> sdcard
  storage -. "probable SDIO" .-> wifi
  storage -. "UART controller; pads unknown" .-> uartpads
  usb <--> usbplug
  audio -. "physical wiring unknown" .-> audioio
```

### Reading this diagram

- The image-processing route is a working subsystem model, not a recovered bus
  schematic. The MMIO block names are corroborated by matching HAL source-path
  strings and register accesses; the arrows between engines are partly inferred.
- The firmware contains drivers for more devices than the production body must
  necessarily contain.
- The Broadcom Wi-Fi files and drivers are observed in the root filesystem.
  The exact chip population and SDIO wiring have not been verified on hardware.
- `0x10007000` is intentionally omitted from the named pipeline. The firmware
  calls it `MAC`, but expanding that to “Ethernet MAC” would be speculation.

## 2. Boot, update, and recovery flow

```mermaid
flowchart TD
  classDef observed fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px,color:#111;
  classDef inferred fill:#fff8e1,stroke:#f9a825,stroke-width:2px,stroke-dasharray:6 4,color:#111;
  classDef unknown fill:#eeeeee,stroke:#616161,stroke-width:2px,stroke-dasharray:2 4,color:#111;
  classDef critical fill:#ffebee,stroke:#c62828,stroke-width:3px,color:#111;

  power["Power-on / battery insertion"]:::observed
  rom["SPCA6500 mask boot ROM<br/>bytes and behavior not captured"]:::unknown
  source["Boot-source selection<br/>internal NAND vs possible SD mode"]:::unknown
  early["Early loader / reserved NAND code<br/>location and validation unresolved"]:::unknown
  dramparam["DRAM parameters / DRAMPAR3<br/>updater-writable"]:::critical
  draminit["DRAM initialization"]:::inferred
  choose["Firmware1 / Firmware2 selection<br/>fallback semantics unknown"]:::unknown
  main["SPCA6500 DRAM CODE<br/>loaded at 0x40000000<br/>reset vector -> 0x40000ec0"]:::observed
  camera["Camera OS / HAL / UI tasks"]:::observed
  linuxboot["Linux boot preparation<br/>boot-argument builder observed"]:::observed
  linux["Linux 3.18.11 + OpenWrt rootfs"]:::observed

  power --> rom
  rom -. "selection mechanism unknown" .-> source
  source -.-> early
  early -.-> dramparam
  dramparam -.-> draminit
  draminit -.-> choose
  choose -. "partial staged-load contract traced; earlier selection unknown" .-> main
  main --> camera
  main --> linuxboot
  linuxboot -. "exact activation path incomplete" .-> linux

  updatecard["SD card: official FPUPDATE.DAT"]:::observed
  updater["Resident update engine in main firmware<br/>normal file-update call traced"]:::observed
  packaged["Packaged ARMv7 updater executable<br/>separate boot consumer unresolved"]:::observed
  checks["Package size / checksum / storage checks"]:::observed
  backup["Keep Firmware2 + invalidate FW1 checksum before write<br/>restore only on success"]:::observed
  writes["Write + read-back verify<br/>ordinary path: FW1 + package resources<br/>generic updater can also write FW2"]:::critical
  finish["Success: restore FW1 checksum<br/>failure: leave FW1 invalid"]:::observed

  camera -->|"normal update UI / file parser"| updater
  updatecard -->|"package read as data"| updater
  updatecard -. "contains executable; no normal-path launch established" .-> packaged
  early -. "independent launch unknown" .-> packaged
  updater --> checks
  checks --> backup
  backup --> writes
  writes --> finish
  writes -. "changes boot inputs" .-> choose
  writes -.-> dramparam
  writes -. "reserved metadata may also be affected" .-> early

  sdrecovery["Cold SD recovery / RAM OS image<br/>not demonstrated on X half"]:::unknown
  rom -. "possible but unproven" .-> sdrecovery
  sdrecovery -. "desired independent recovery route" .-> main

  usbisp["iCatch USB ISP / mask-boot tool family<br/>documented on comparable V50 devices<br/>X half entry and ROM independence unproven"]:::inferred
  rom -. "chipset-family lead only" .-> usbisp
  usbisp -. "desired RAM loader / flash repair route" .-> early
```

The [staged storage handoff](staged-loader-storage-handoff.md) now identifies
application-side section loading/checks and an earlier-stage SRAM descriptor.
That narrows the loading arrow; it does not recover the earlier selector or
establish a successful physical cold boot.

### Safety boundary shown by the flow

The updater has verification and retry machinery, but it also reaches inputs
used before the main camera application starts. A successful package check is
therefore not equivalent to a recoverable update. The most important unresolved
arrows are:

1. how mask ROM selects and validates NAND or SD boot;
2. where the early/update loader resides and whether normal updating can damage
   it;
3. whether invalid Firmware1 automatically selects Firmware2; and
4. whether a cold SD image can run without the installed main firmware.

## 3. Three coordinate systems: package, NAND, and CPU addresses

These address spaces must never be mixed. Arrows below show only mappings that
have evidence; absence of an arrow means the mapping is unresolved.

```mermaid
flowchart LR
  classDef observed fill:#e8f5e9,stroke:#2e7d32,stroke-width:2px,color:#111;
  classDef inferred fill:#fff8e1,stroke:#f9a825,stroke-width:2px,stroke-dasharray:6 4,color:#111;
  classDef unknown fill:#eeeeee,stroke:#616161,stroke-width:2px,stroke-dasharray:2 4,color:#111;
  classDef critical fill:#ffebee,stroke:#c62828,stroke-width:3px,color:#111;

  subgraph pkg["FPUPDATE.DAT file offsets — not runtime addresses"]
    p0["0x00000000..0x000a2bff<br/>header + card updater"]:::observed
    p1["0x000a2c00..0x000a3bff<br/>SUNP BURN HDR 1"]:::observed
    p2h["0x000a3c00..0x000a3d1f<br/>HDR2 control header"]:::observed
    p2a["0x000a3d20..0x078a3d1f<br/>FAT16 resource image A — RO_RES"]:::observed
    p2b["0x078a3d20..0x082a3d1f<br/>FAT16 resource image B — USER_RES"]:::observed
    p3["0x082a3d20..0x082c3d1f<br/>firstburn manifest / padding"]:::observed
    p4["0x082c3d20..0x084c0d8f<br/>Linux zImage"]:::observed
    p5["0x086c3d20..0x08fd281f<br/>SquashFS rootfs"]:::observed
    p6["0x08fe3d20..0x09e1c11f<br/>SPCA6500 DRAM CODE"]:::observed
    p7["0x09e1c120..0x09e1ca6f<br/>SUNP BURN HDR 3"]:::observed
    p8["0x09e1ca70..0x09e1d169<br/>DRAMPAR3 / declared tail"]:::critical
    p9["0x09e1d16a..0x09e1d669<br/>outer trailer; meaning unresolved"]:::unknown
  end

  subgraph flash["Constructed NAND byte offsets — partition capacity, not package layout"]
    n0["0x00000000..0x0001ffff<br/>reserved / header area"]:::critical
    n1["0x00020000..0x0041ffff<br/>kernelImg — 4 MiB"]:::critical
    n2["0x00420000..0x0241ffff<br/>rootfsImg — 32 MiB"]:::critical
    n3["0x02420000..0x0341ffff<br/>fastboot — 16 MiB"]:::critical
    n4["0x03420000 onward<br/>ubifs — size derived at runtime"]:::critical
    n5["Firmware1 / Firmware2 / rawC / DRAM parameter placement<br/>physical offsets unresolved"]:::unknown
  end

  subgraph cpuaddr["CPU-visible address space"]
    c0["0x10000000..0x1001efff sparse<br/>SPCA6500 peripheral MMIO"]:::observed
    c1["0x10201000..0x1023cfff sparse<br/>CoreSight debug / trace"]:::inferred
    c2["0x10401000..0x10402fff<br/>GICv2"]:::inferred
    c3["0x3f000000..0x3f00ffff<br/>on-chip SRAM"]:::inferred
    c4["0x40000000..0x40e383ff<br/>initialized DRAM CODE image"]:::observed
    c5["0x40e38400..0x414fb77f<br/>runtime RAM below allocator ceiling<br/>ownership unresolved"]:::unknown
    c6["Above 0x414fb780<br/>heap / frame buffers / aliases / DRAM limit unknown"]:::unknown
  end

  p4 -->|"payload size matches firstburn manifest"| n1
  p5 -->|"payload size matches firstburn manifest"| n2
  p6 -->|"runtime image mapping"| c4
  p2a -. "resource destination mapping unresolved" .-> n5
  p2b -. "resource destination mapping unresolved" .-> n5
  p8 -. "updater destination unresolved" .-> n5
  n5 -. "early loader mapping unresolved" .-> c4
```

### Confirmed NAND partition boundaries

| Partition | Capacity | NAND offset | Evidence |
| --- | ---: | ---: | --- |
| reserved/header | 128 KiB | `0x00000000` | kernel begins at fixed `0x20000` |
| `kernelImg` | 4 MiB | `0x00020000` | `firstburn` and boot-argument assembly |
| `rootfsImg` | 32 MiB | `0x00420000` | `firstburn` and boot-argument assembly |
| `fastboot` | 16 MiB | `0x02420000` | fixed boot-argument constant |
| `ubifs` | runtime-derived | `0x03420000` | follows the fixed partitions |

The zImage and SquashFS files are smaller than their partition capacities. The
diagram omits package padding regions between them for readability; the exact
file ranges remain in `update-package-and-linux.md` and
`output/firmware-regions.json`.

## 4. Current evidence and unknowns ledger

| Area | Established | Still unknown |
| --- | --- | --- |
| CPU startup | ARMv7 little-endian DRAM image, reset vector, secondary-core entry, SRAM stacks, VBAR and GIC setup | core count; pre-DRAM execution stages |
| Memory | initialized image extent; on-chip SRAM role; allocator ceiling `0x414fb780` | complete DRAM size; BSS ownership; safe upload ranges; framebuffer locations |
| Imaging | sensor front end, CDSP, IME, JPEG, H.264, GPU/HWC and display MMIO blocks | exact sensor part; engine interconnect; main/secondary display routing |
| Storage | active NAND/SD code; Linux partition construction; package zImage/rootfs | NAND part/capacity; FW1/FW2/rawC physical placement; bad-block metadata |
| USB | normal physical camera enumerates as PTP `04CB:030A`; separate UVC/EUC code exists | safe service-mode entry; UVC selector source; read-only debug transport |
| Linux | exact kernel and rootfs; OpenWrt inventory; Broadcom support files | normal runtime role; actual network interfaces; exposed UART pads |
| Update safety | package checksum algorithm, shared FW1/FW2 writer, read-back comparison, retries, backup strings, FW1 checksum messages; HDR2 FAT A/B layout | outer-trailer validation; automatic fallback; power-loss recovery |
| Recovery | FujiMod proves SD recovery on other processor families; comparable V50 tools distinguish mask/ISP boot and USB bulk/null modes | X half SD/USB entry chord, boot-ROM contract, ROM independence, and recovery image |

## Source reports

- [`architecture-guide.md`](architecture-guide.md)
- [`ram-launch-and-recovery-roadmap.md`](ram-launch-and-recovery-roadmap.md)
- [`updater-image-layout.md`](updater-image-layout.md)
- [`staged-loader-storage-handoff.md`](staged-loader-storage-handoff.md)
- [`memory-and-hardware-map.md`](memory-and-hardware-map.md)
- [`hardware-register-blocks.tsv`](hardware-register-blocks.tsv)
- [`update-package-and-linux.md`](update-package-and-linux.md)
- [`output/firmware-regions.json`](output/firmware-regions.json)
- [`brick-risk-and-recovery.md`](brick-risk-and-recovery.md)
- [`spca6500-package-and-recovery-comparison.md`](spca6500-package-and-recovery-comparison.md)
- [`xhalf-usb-identity.md`](xhalf-usb-identity.md)
- [`ptp-and-service-command-paths.md`](ptp-and-service-command-paths.md)
- [`uvc-service-mode-activation.md`](uvc-service-mode-activation.md)

## Maintenance rule

When a new fact changes a box or arrow:

1. update the detailed source report first;
2. change the diagram's label and confidence style;
3. add or remove the corresponding unknown in the ledger; and
4. preserve the artifact hash and distinction among package offsets, NAND
   offsets, and CPU addresses.
