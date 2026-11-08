# Runtime task inventory and RTOS

Date: 2026-11-08. Ghidra 12.1.4 decompilation of the official 2.00
main image (`firmware-main.bin`, SHA-256 `80350bd2…`, base
`0x40000000`). No camera access in this pass.

## RTOS

The image contains the banner:

```text
Copyright (c) 1996-2015 Express Logic Inc. * ThreadX Cortex-A7/GNU
Version G5.7.5.2 SN: 4142-271-1301
```

So the main firmware runs on **ThreadX** (Cortex-A7, GNU build
G5.7.5.2), not a bare-metal loop. The shell exposes live RTOS
introspection commands:

- `os task` — task info
- `os stack` — thread/system stack info
- `os nice <thread-name> <lvl>` — change thread priority

## Task-creation APIs

Three layers are visible:

| API | Address | Role |
| --- | --- | --- |
| `FUN_4000c378` | `0x4000c378` | low-level thread create |
| `FUN_4004118c` | `0x4004118c` | wrapper: `(name, entry, arg, prio, prio2, flags, stack, ...)`; enforces a 0x1000 minimum stack |
| `FUN_405f1f04` | `0x405f1f04` | named wrapper; logs `Creating thread: %s, called from [%s][%d]` |

`FUN_4004118c` calls
`FUN_4000c378(name, prio, entry, arg, flags, stack-0x100, ...)`.
The first argument is the **name string**, the second is the **entry
function**.

## Direct task table (`FUN_4004118c` call sites)

| Task name | Entry | Priority | Stack |
| --- | --- | --- | --- |
| `AutoFocus Process` | `0x402039e0` | 4 | 8000 |
| `FastPBEnterViewGroupThread` | `0x40407e1c` | 0xb | 0x22d0 |
| `PB Effect Thread` | `0x404ec0bc` | 0xe | 6000 |
| `SlideWindowThread` | `0x404ef224` | 0xe | 8000 |
| `pLoadMainImageThread` | `0x40554774` | — | — |
| `PB_NxNLoadThread` | `0x405cccb8` | 0xd | 8000 |
| `PB_ListDateLoadThread` | `0x405cca74` | 0xd | 8000 |
| `PB_NxNDrawThread` | `0x405d06e0` | 0xe | — |
| `PB_NxNMoveThread` | `0x405cf4c4` | 0xe | — |
| `PB_FadeEffectThread` | `0x405d2038` | 0xe | 8000 |
| `uvctestcmd` | `0x405e67e4` | 0x14 | 0x1000 |
| dynamic IPC task | `0x40619594` | — | — |
| dynamic IPC task | `0x4064460c` | — | — |
| dynamic IPC task | `0x406444f0` | — | — |

## Named-wrapper tasks (`FUN_405f1f04`)

| Task name | Entry | Priority | Stack |
| --- | --- | --- | --- |
| `Ability WiFi Message Queue Thread` (x2) | `0x405b6b9c` | 0x19 / 0x1a | 0x4000 |
| `Ability WiFi EMI Test` | `0x40615d5c` | 0x19 | 0x4000 |
| `create_rtos_ipc` / `WiFiMonitor_IPC` | `0x4064527c` | 0x1b | 0x4000 |

## Task families from the name inventory

The image contains ~700 thread/task/process strings. The main
families:

### Boot and power

- `App_PreBootProc`, `PreBootProc_1/2/3`, `FastBootProcessLens`,
  `FastBoot_IrisInit Process`, `FastBoot_Focus Process`,
  `FastLens Process`, `MotorICInitProc`, `Motor_Control_Init_By_Thread`
- `PowerOnCtrlThread`, `ProcessPowerOnEvent`, `ThreadPowerOnEvent`
- `ProcessPowerOffCloseLensThread`, `ProcessPowerOffCloseLensProc`
- `System Timer Thread`

### Sensor and 3A

- `sensorThread0..3`, `aaStatThread0/1`, `AfThread0/1`
- `AutoFocus Process`, `ViewAutoFocus Process`, `Fast3AInitProcess`
- `Ae_IrisProc`, `Ae_IrisProc Process`, `IrisShutter Process`
- `5kmodeswTask`, `pvTestProc`

### Preview / capture pipeline

- `ISR Task`, `Cap Task`, `Cdsp Task`, `IQ Task`
- `Capture Task`, `CDSP Task`, `Jpg Task`, `Store Task`
- `pv_stateprocess.c`, `snap_taskinit.c`, `pv_taskinit.c`
- `YUV Process`, `VR Process`, `internal yuvCB@task`

### RAW / YUV save (directly relevant to RAW work)

- `saveRaw_SaveFrm_Task_Sensor0..3`
- `saveRaw_SaveFile_Task_Sensor0..3`
- `saveYuv_SaveFrm_Task_Sensor0..3`
- `saveYuv_SaveFile_Task_Sensor0..3`
- `savePrvFrm_SaveFrmTask`, `savePrvFrm_SaveFileTask`
- `savePrvYuvSaveFrmTask_Sen0..3`,
  `savePrvYuvSaveFileTask_Sen0..3`
- `savePrvFrmSaveFrmTask_Sen0..3`,
  `savePrvFrmSaveFileTask_Sen0..3`

These are the stock **frame/file save task family**; the `SaveRaw`
path we decompiled (`FUN_402cdad8`) is one consumer of this family.

### USB

- `usbControlTask`, `usbTargetTask`, `usbDPCTask`, `usbMsdcThread`
- `usbUvcYuvParserTask`, `UsbLineTask`
- `halUsbEp1/2/8 Yuv/Mjpg/H264 SendTask`
- `pusbControlThread`, `pusbTargetThread`, `pusbMsdcThread`,
  `pusbDPCThread`

### Storage / mount

- `diskMountTask`, `MountThread`, `StartMountThread`,
  `ViewFastInitThread`, `ProcMountDcf`, `DCFFSThread`

### UI / display

- `ViewDrawOsdThread`, `VideoDrawWarnInfoThread`, `OsdTask`,
  `ThreadAnimation`, `MenuScrollThread`, `ShutterSoundThread`,
  `UIMusic_AFSuccess Thread`, `SandGlassAmineThread`,
  `ViewRealTimeThread`, `WaitFocus_Thread`, `txSnpa1DetectThread`

### Playback

- `PB Effect Thread`, `SlideWindowThread`, `PB_NxNLoadThread`,
  `PB_ListDateLoadThread`, `PB_NxNDrawThread`, `PB_NxNMoveThread`,
  `PB_FadeEffectThread`, `pLoadMainImageThread`,
  `FastPBEnterViewGroupThread`, `PB_LoadMain_Thread`

### WiFi / BT / dual-OS

- `Ability WiFi Message Queue Thread`, `WiFiMsgQueue_ThreadEntry`
- `Ability WiFi EMI Test`, `WiFiEMI_TestThreadEntry`
- `ThreadX WiFiMain IPC channel`, `ThreadX BTMain IPC channel`,
  `ThreadX WiFiEMI IPC channel`, `ThreadX WiFiMonitor IPC channel`
- `DualOsChannel_Server_Thread`, `DualOsTunnel_Server_Thread`,
  `LinuxCtrl_MonitorThread`, `LinuxCtrl_InitThread`

### OS / shell

- `os task`, `os stack`, `os nice thread-name lvl`
- `ros_thread.c`, `ThreadX Cortex-A7/GNU G5.7.5.2`

## The `os` introspection shell

The shell has a full RTOS introspection command group at table
`0x40df8b4c`:

| Command | Handler | Help |
| --- | --- | --- |
| `os task` | `0x402fa248` | task info |
| `os mem` | `0x402fa294` | memory info |
| `os que` | `0x402fa240` | queue info |
| `os sem` | `0x402fa238` | semaphore info |
| `os evt` | `0x402fa230` | event-flag info |
| `os mut` | `0x402fa228` | mutex info |
| `os tmr` | `0x402fa220` | timer info |
| `os stack` | `0x402fa1e8` | thread/system stack info |
| `os all` | `0x402fa1d4` | all info |
| `os nice <thread-name> <lvl>` | `0x402fa25c` | change thread priority |
| `os` | `0x402fa188` | OS information / help |
| `os prof` | `0x402f9fd0` | profiling |
| `os proc` | `0x402f9e54` | `proc en ms` |
| `os dir` | `0x402fab1c` | object directory |

`os task` (`FUN_402fa248`) walks the ThreadX task list:

- list head at `DAT_4000628c` (`*DAT_4000628c` is the first task)
- next task at `task + 0x88`
- task name at `task + 0x28`
- priority at `task + 0x2c`
- state index at `task + 0x30`
- timeout at `task + 0x4c`
- the state-name table is the packed string table at `0x40adae5e`

The task print format is:

```text
[%p](pri%2u) %-16s[%5s]timeout%ld
```

State names, in index order:

| Index | State |
| --- | --- |
| 0 | `Ready` |
| 1 | `Comp` |
| 2 | `Term` |
| 3 | `Susp` |
| 4 | `Slee` |
| 5 | `WQu` |
| 6 | `WSem` |
| 7 | `WEvt` |
| 8 | `WBlk` |
| 9 | `WByte` |
| 10 | `IO` |
| 11 | `File` |
| 12 | `TCP` |
| 13 | `WMut` |
| 14 | `???` |

`os stack` (`FUN_40006a58`) walks the same task list and prints
stack usage via `FUN_4000582c(task, filter, 1)`. `os nice` resolves
the thread name with `FUN_40021768(task+0x28, name)` and changes the
priority with `FUN_40026f00(task, level, ...)`.

This is a complete **live task introspection path**: in the debug
shell, `os task`, `os stack`, `os all`, and `os nice` operate on the
real ThreadX lists. The next live session can dump the actual task
table (names, priorities, states, stack usage) without any storage
writes.

## What this gives us

- A concrete **runtime ownership map**: which task family owns
  boot/power, sensor/3A, preview/capture, RAW/YUV save, USB, storage,
  UI, playback, WiFi/BT, and the RTOS.
- The **task-creation API** (`FUN_4004118c`/`FUN_405f1f04`/
  `FUN_4000c378`) to extend the table and to understand priorities
  and stacks.
- The shell `os task` / `os stack` commands can give the **live task
  list** in the debug shell; that is the next confirmation step.
- The RAW/YUV save task family is much broader than the single
  `SaveRaw` path; it includes per-sensor frame and file tasks.

## Open questions

1. Full task table: many tasks are created by other APIs or by the
   boot/OS layer; the ~700 names include menus/handlers, not just
   tasks.
2. Priorities/stacks for the tasks whose call-site arguments were not
   recovered.
3. The live `os task` output would confirm names, states, priorities
   and stack usage.
4. Which task owns the `SaveRaw` writer and the AWB callbacks at
   runtime (the `saveRaw_SaveFile_Task_Sensor*` family is a strong
   candidate).
5. The `os nice` command can change priorities; that is a read/write
   runtime control, not a storage write.

## Boundary

The RTOS banner, task names, creation call sites and API wrappers are
observed in the official image. The live task list, task states, and
stack usage are not yet observed. The task names include UI/menu
handlers and process functions, not all of which are RTOS tasks.
