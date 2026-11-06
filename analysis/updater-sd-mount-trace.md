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

## New lead: `0x10000058` bit 2 is an id-1 media latch, not the id-2 path

`0x4006f938` is a media detect used by the id-1/disk path. It reads
the halfword at `0x10000058`, masks bit 2, and branches:

```text
4006f968: ldrh r3, [0x10000000 + 0x58]   ; 0x10000058
4006f96c: and  r3, r3, #4
4006f974: cmp  r3, #0
4006f978: bne  0x4006f984 -> 0x4006f89c  ; set state/power bits
4006f97c: bl   0x4006f8e4                ; clear state/power bits
```

The live calibration read was `0x10000058 = 0x0000`, so bit 2 is
clear. However, the id-2 SD path does **not** use this detect:

- The id-2 detect state machine is `0x40022b40`; its `sdDetect` body
  is at `0x40022e60` (`0x40022d58` is its literal pool).
- `0x40022e60` calls `0x4006ee80`, which reads the **driver state
  field** at `0x400ba140 + id*4 + 0x120`, not `0x10000058`.
- The card-status bit it tests (`tst r0, #0x40`) is a driver state
  flag, and the actual SD command/transfer path tail-calls
  `0x40071474`/`0x400718b4` for id 2.

So the `0x10000058` bit-2 latch is an id-1/disk media flag, not the
id-2 SD card-present source. It does not explain the FAT16 failure.
The id-2 source is the SD controller's command/response state.

## Remaining suspect: SD controller state / CMD timing

The id-2 `sdDetect` path reads a driver state field and runs the SD
command/transfer functions; it does not consult the `0x10000058`
latch. The mount path (`0x40021d78`) does an SD power/init
(`0x4006de3c` -> `0x40070d18`) but the power callback is null on a
cold boot too, so that early-return is likely benign. The remaining
difference is the controller state left by the main firmware: the
updater's `sdDetect` may not reset the clock/bus-width/power state
that the main firmware's SD driver established.

The `sdReMount`/`sdReDetect` path at `0x4001f1d0` is the candidate
reset: it calls `0x4006de3c`, `0x4006eea8`, and `0x4001fbc8`, and is
reachable from the SD shell commands but not from the plain-file
mount. If that path performs the controller reset the mount path
lacks, a RAM-only payload patch that calls `0x4001f1d0(2)` before
the consumer's `func_0x4000e5a4(2)` is the test. Emulator-qualify
the call first.

Signature: `0x4001f1d0(r0 = id, r1 = flags)`. The SD shell command
calls it with `r0 = 2` and `r1 = [command arg]`; `flags` bit 0
selects a branch. A call with `r0 = 2, r1 = 0` is the re-mount/
re-detect path. `func_0x4000e5a4` is only a 4-byte tail branch to
`0x4001c594`, so the patch shape is a small stub in the payload's
code padding:

```text
push {lr}
mov  r0, #2
mov  r1, #0
bl   0x4001f1d0
mov  r0, #2
pop  {lr}
b    0x4001c594
```

The stub preserves the mount wrapper's `r0 = 2` contract. The SD
effect is live-only; the emulator can qualify only the register and
stack behavior. The patch is RAM-only; internal storage is untouched.

The hardware control remains a true 2 GB SDSC card: the current
card is a 31.9 GB SDHC, and repartitioning does not change the SD
protocol. If the controller state is the issue, the card type will
not matter; if the SDHC detect is the issue, it will.

The signature is now known (`r0 = id, r1 = flags`); the
controller-reset effect is the remaining live/emulator question.

## What the cold boot does differently

The `.brn` path calls `0x40009ca0` (command task plus storage-worker
setup) before the update wrapper. The plain-file path calls
`FUN_4000f270` directly and never calls `0x40009ca0`. If the SD
driver init is tied to the command/storage-worker setup rather than
the mount state machine, the plain-file path is missing it. That is
a candidate explanation for why the same mount may work on a cold
boot but not after the RAM handoff.

## Next steps

1. `0x4006ed88` is resolved: zero references; the power-callback
   hypothesis is eliminated.
2. The `0x10000058` bit-2 latch is id-1 only; eliminated for id-2.
3. The id-2 `sdDetect` uses the SD controller state/CMD response.
   The `0x4001f1d0` re-mount/re-detect is the candidate reset the
   plain-file mount lacks.
4. Next test: emulator-qualify the stub's register/stack behavior,
   then RAM-push the patched updater with the FAT16 card. If the
   mount succeeds, the controller-reset hypothesis is confirmed;
   if it still goes to service mode, the controller state is not
   recoverable this way and the true 2 GB SDSC card is the next
   hardware control.

## Patch built (2026-11-06)

The client now has `--ram-push --sd-remount-first`. It:

- patches the updater payload at `0x4000e5a4` from `b 0x4001c594`
  to `b 0x411f0000` (`ea478695`);
- writes a 32-byte stub to `0x411f0000` (scratch gap, inside the
  loader write allowlist) after the payload read-back and before
  `0x04f7`;
- reads the stub back before the handoff.

The stub disassembles as:

```text
411f0000: push {lr}
411f0004: mov  r0, #2
411f0008: mov  r1, #0
411f000c: bl   0x4001f1d0
411f0010: mov  r0, #2
411f0014: pop  {lr}
411f0018: b    0x4001c594
411f001c: nop
```

The live command is:

```sh
sudo python3 ~/xhalf_loader_linux.py --set-mailbox MAIN BIN
sudo python3 ~/xhalf_loader_linux.py --ram-push ~/updater.bin \
    --sd-remount-first --drain-log
```

The stub is in the loader's scratch gap; the updater's heap could
in principle overwrite it before the mount, so a hang/crash is
possible. Power cycle recovers; internal storage is untouched.

## Live patch result (2026-11-06): negative

The patched payload (`sha256 6f11c808…`, read-back verified) was
RAM-pushed with the FAT16 card and the mailbox set. The stub was
written to `0x411f0000` and read back; `0x04f7` was accepted. The
device re-enumerated with the updater's 3-interface service
personality. After 90 s there was still no main-image boot
(`04cb`) and no second handoff; the updater was in service mode.

The branch/stub clearly executed (the patched mount wrapper was the
only mount path, and the updater reached service mode rather than
crashing), so the `sdReMount(2,0)` pre-call returned but did not
make the SD mount succeed. **The re-mount hypothesis is not
sufficient.** The remaining suspects are the deeper SD controller/
CMD state after the RAM handoff, or the SDHC card protocol (the
current card is a 31.9 GB SDHC; the 1.5 GB FAT16 volume does not
change that).

## Boundary

Original-byte disassembly, a Ghidra reference query, and call-graph
tracing. No emulator execution of the proposed patch, no camera
access, no hardware register writes. The patch is proposed, not
built.
