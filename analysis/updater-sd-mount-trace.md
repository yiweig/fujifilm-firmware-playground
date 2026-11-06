# Updater SD mount trace: does the file path initialize the controller?

Date: 2026-11-06. Offline original-instruction trace of the
official 2.00 complete updater, base `0x40000000`, SHA-256
`4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976`.
No camera access. Follow-up to the 1.5 GB FAT16 card test in
[sdboot-diagnosis.md](sdboot-diagnosis.md), which eliminated the
card format/volume and left the updater's SD driver/controller
state as the leading suspect.

## Question

The plain-file consumer calls `func_0x4000e5a4(2)` to mount drive 2
(SD1). Does that path reset/initialize the SD controller, or does
it assume a cold-boot state that the RAM-pushed updater never
established?

## Call chain found

```text
FUN_4000e5a4(2)                 mount wrapper (tail-call)
  -> FUN_4001c594               synchronous activation
     -> 0x4001a844              resolve RAM mapping record
     -> 0x4001adc8              record lookup
     -> 0x4001a9b4              "already initialized?" check
        -> 0x4001a89c           generic initializer (when not initialized)
           -> record+0x38       drive-2 initializer 0x4001b354
              -> 0x40021d78     disk/SD init-and-detect state machine
                 -> 0x4001df30  power/wait state
                    -> 0x4001deb0(1,1)
                       -> 0x4006deb0(1,1)   power state / delay
                 -> 0x4006de3c  SD power state
                    -> 0x40070d18          SD power/GPIO helper
                 -> 0x4006f14c  disk state swap
                 -> 0x4006b194  boot-status getter
                 -> 0x4006f14c / 0x4006a0d0
     -> record+0x3c              mount callback 0x4001b4c8
        -> 0x4001e048           unmount/clear
        -> 0x4001dfb4           sdVfsMount
           -> 0x40042e40        VFS mount
           -> 0x4004044c / 0x40040288  VFS handle operations
```

The mount path **does** attempt an SD power/init step; it is not a
pure VFS mount that assumes the controller is already up. The
detect/re-mount paths also call the same power state:

- `0x40022b78` in the `0x40022b40` SD detection state machine calls
  `0x4006de3c` (record+0x70 state, log, then power state).
- `0x4001f580` in the `0x4001f1d0` `sdReMount`/`sdReDetect` path
  calls `0x4006de3c` and sets record+0x70 = 3.

## The concrete dependency: `0x400ba2a0`

`0x40070d18` is the SD power/GPIO helper. Its first action is:

```text
r3 = [0x400ba2a0]
if r3 == 0: return            ; no power callback registered -> no-op
if r3 == -2: ... direct register path ...
if r3 == -1: skip
else: call r3 with the GPIO bit arguments
```

The callback table at `0x400ba2a0` is set by `0x40070ee4`. The only
direct callers of `0x40070ee4` in the image are:

- `0x4006edac` inside `0x4006ed88`, the SD init/configure function.
  With `r0 == 0` it calls `0x40070f14` ("already initialized?"), and
  if not initialized registers the callback via
  `0x40070ee4(-1, 0, 1, 0)`, then calls `0x4007155c(0)` and
  `0x4006e248(0, 0)`.
- `0x4000ea64` inside the `0x4000e7dc` SD shell-command dispatcher
  (the `sd...`/`sdio...` commands).

`0x4006ed88` has **no direct BL/B callers** in the image. A
Ghidra reference query after auto-analysis confirms **zero
references** to `0x4006ed88` (not a direct call, not an absolute
pointer, not a data reference). The only write to `0x400ba2a0` is
inside `0x40070ee4`, whose only callers are `0x4006edac` (inside
the unreferenced `0x4006ed88`) and `0x4000ea64` (the SD shell
command). So the power-callback hypothesis is **weakened**: the
cold boot also leaves the callback null, and the `0x40070d18`
early-return is likely benign because the SD power is already on.

That makes the callback-null theory a dead end. The next lead is
the card-detect path.

## New lead: `0x10000058` bit 2 is a media-present latch

`0x4006f938` is a media detect used by the id-1/disk path. It reads
the halfword at `0x10000058`, masks bit 2, and branches:

```text
4006f968: ldrh r3, [0x10000000 + 0x58]   ; 0x10000058
4006f96c: and  r3, r3, #4
4006f974: cmp  r3, #0
4006f978: bne  0x4006f984 -> 0x4006f89c  ; set state=1 / power bits
4006f97c: bl   0x4006f8e4                ; clear state / power bits
```

The live calibration read was `0x10000058 = 0x0000`, so bit 2 is
clear. If the id-2 SD path uses the same latch, the updater would
report "no card" before it ever reaches the FAT stack. The id-2
path's detect state machine is `0x40022b40` (called from the
`0x40021d78` init); the next question is whether it reads
`0x10000058` bit 2 or uses the SD controller's CMD response. The
`0x40022b40` snippet seen so far reads record+0x70 and calls
`0x4006de3c`/`0x4006f89c`; the card-present source is not yet
identified.

If `0x10000058` bit 2 is the source, the fix is a pre-handoff latch
write (via the loader trampoline) or a payload patch to ignore the
latch. If the source is the SD controller response, the failure is
controller state/CMD timing after the RAM handoff.

This is not yet proven: the id-2 detect state machine
(`0x40022b40` and the `sdDetect` body at `0x40022e60`; `0x40022d58`
is its literal pool, not the entry) has not been fully traced to
its card-present source. The Ghidra zero-reference result makes the
`0x4006ed88(0)` init an unlikely explanation; the `0x10000058`
bit-2 latch and the SD controller's CMD response are the remaining
candidates. Resolve that source before proposing a payload patch.

## What the cold boot does differently

The `.brn` path calls `0x40009ca0` (command task plus storage-worker
setup) before the update wrapper. The plain-file path calls
`FUN_4000f270` directly and never calls `0x40009ca0`. If the SD
driver init is tied to the command/storage-worker setup rather than
the mount state machine, the plain-file path is missing it. That is
a candidate explanation for why the same mount may work on a cold
boot but not after the RAM handoff.

## Next steps

1. Resolve the `0x4006ed88` caller: inspect the `0x40021d78` call
   graph and any function-pointer table; check whether the updater's
   reset/startup path calls `0x4006ed88(0)`. If it does, the callback
   is set and the failure is downstream (card detect/CMD timing).
2. If `0x4006ed88(0)` is not called before the mount, the test is a
   RAM-only payload patch that calls it (or pre-registers the
   `0x400ba2a0` callback) before `func_0x4000e5a4(2)`. Emulator-
   qualify the instruction sequence first; the patch changes only
   the RAM payload, not internal storage.
3. In parallel, a true 2 GB SDSC card is the hardware control: the
   current card is a 31.9 GB SDHC, and repartitioning does not
   change the SD protocol. If the power callback is the issue, the
   card type will not matter; if the SDHC detect is the issue, it
   will.

## Boundary

This is original-byte disassembly and call-graph tracing. No
emulator execution, no camera access, no hardware register writes.
The `0x400ba2a0` early-return is a concrete code path; whether the
live updater reaches the registering init is still unresolved.
