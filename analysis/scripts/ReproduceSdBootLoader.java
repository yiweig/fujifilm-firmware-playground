// SD-boot loader rehearsal: execute updater FUN_4000f270("D:\MAIN.BIN", 0)
// with a mocked filesystem and storage-interface vtable.
// Cases:
//  A) tiny synthetic file present   -> expect boot-task creation boundary
//  B) real firmware-main.bin present -> expect boot-task creation boundary,
//     state size/dest recorded, read buffer spot-checked
//  C) file absent                   -> expect loader return (open error)
// Boundaries: task create FUN_40082474 (success), return to LR sentinel,
// any unexpected PC (failure). Mocks only at function entries; the loader,
// name parse, and state setup run as original instructions.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceSdBootLoader extends GhidraScript {
    byte[] image;
    static final long SENT_OP = 0x4200e000L, SENT_CLASSFN = 0x4200e100L;
    static final long IFACE = 0x4200d000L, STATE = 0x400a2cb8L;

    class Arm {
        final PcodeEmulator emu = new PcodeEmulator((SleighLanguage) currentProgram.getLanguage());
        final PcodeThread<byte[]> t = emu.newThread();
        byte[] file; boolean filePresent; int fdOff = 0;
        long mallocPtr = 0x42100000L;
        List<String> logs = new ArrayList<>(), ops = new ArrayList<>();
        boolean taskCreated = false; String taskName = null;
        long stateSize = -1, stateDest = -1; long fileBuf = -1;
        String openedPath = null;

        Arm(byte[] file, boolean present) throws Exception {
            this.file = file; this.filePresent = present;
            mem(0x40000000L, image);
            mem(0x400a0000L, new byte[0x10000]);            // BSS/state
            mem(0x10000000L, new byte[0x6000]);             // boot-info regs
            mem(0x42000000L, new byte[0x20000]);            // scratch/sem
            mem(IFACE, new byte[0x100]);
            put(IFACE + 0x60, 4, SENT_OP);                  // vtable op fn
            mem(0x42010000L, "D:\\MAIN.BIN\0".getBytes("US-ASCII"));
            t.overrideContextWithDefault();
            for (int i = 0; i < 13; i++) reg("r" + i, 0);
            for (String n : new String[]{"CY","OV","ZR","NG"}) reg(n, 0);
        }
        void mem(long a, byte[] b) { emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(), a, b.length, true, b); }
        byte[] mem(long a, int n) { return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(), a, n, true, Reason.INSPECT); }
        void put(long a, int n, long v) { mem(a, Utils.longToBytes(v, n, false)); }
        long get(long a, int n) { return Utils.bytesToLong(mem(a, n), n, false); }
        void reg(String n, long v) { var r = currentProgram.getRegister(n); t.getState().setVar(r, Utils.longToBytes(v, r.getMinimumByteSize(), false)); }
        long reg(String n) { var r = currentProgram.getRegister(n); return Utils.bytesToLong(t.getState().getVar(r, Reason.INSPECT), r.getMinimumByteSize(), false); }
        void ret(long v) { reg("r0", v); t.overrideCounter(toAddr(reg("lr"))); }
        String string(long a) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < 256; i++) { int b = mem(a + i, 1)[0] & 255; if (b == 0) return out.toString("US-ASCII"); out.write(b); }
            throw new Exception("Unbounded string");
        }
        String run() throws Exception {
            reg("sp", 0x42018000L); reg("lr", 0x43000000L);
            reg("r0", 0x42010000L); reg("r1", 0);
            t.overrideCounter(toAddr(0x4000f270L));
            for (int step = 0; step < 400000; step++) {
                monitor.checkCancelled();
                long pc = t.getCounter().getOffset();
                if (pc == 0x43000000L) { stateSize = get(STATE + 0x14, 4); stateDest = get(STATE + 0x18, 4); return "returned"; }
                if (pc == SENT_OP) { long op = reg("r0"); ops.add("op" + op); if (op == 10) mem(reg("r1"), new byte[0x4000]); ret(0); continue; }
                if (pc == SENT_CLASSFN) { ops.add("classfn"); ret(0); continue; }
                if (pc == 0x40088324L) { ret(string(reg("r0")).equals(string(reg("r1"))) ? 0 : 1); continue; }       // strcmp
                if (pc == 0x40088178L) { String a = string(reg("r0")), b = string(reg("r1")); int n = (int) reg("r2");
                    ret(a.regionMatches(0, b, 0, Math.min(n, Math.min(a.length(), b.length()))) && a.length() >= n && b.length() >= n || a.substring(0, Math.min(n, a.length())).equals(b.substring(0, Math.min(n, b.length()))) ? 0 : 1); continue; } // strncmp approx
                if (pc == 0x400821acL) { ret(string(reg("r0")).length()); continue; }                                // strlen
                if (pc == 0x400882f4L) { String s = string(reg("r0")); int i = s.indexOf((char) reg("r1")); ret(i < 0 ? 0 : reg("r0") + i); continue; } // strchr
                if (pc == 0x4007acb0L) { ret(0); continue; }
                if (pc == 0x4004061cL) { openedPath = string(reg("r0")); ret(filePresent ? 3 : 0); continue; }       // fopen
                if (pc == 0x4004151cL) { ret(filePresent ? file.length : 0); continue; }                             // fsize
                if (pc == 0x40040820L) { fdOff = (int) reg("r2"); ret(0); continue; }                               // seek approx
                if (pc == 0x4004073cL) { int n = (int) reg("r2");                                                  // fread
                    if (!filePresent || fdOff + n > file.length) { ret(-1); continue; }
                    mem(reg("r1"), Arrays.copyOfRange(file, fdOff, fdOff + n)); fileBuf = reg("r1"); fdOff += n; ret(n); continue; }
                if (pc == 0x400406fcL) { ret(0); continue; }                                                        // fclose
                if (pc == 0x4007f6dcL) { long p = mallocPtr; mallocPtr += (reg("r0") + 0xfff) & ~0xfffL; ret(p); continue; } // malloc
                if (pc == 0x4007f830L) { ret(0); continue; }                                                        // free
                if (pc == 0x4007ac04L) { try { logs.add(string(reg("r0")).trim()); } catch (Exception e) { logs.add("?"); } ret(0); continue; } // log
                if (pc == 0x4007d854L) { logs.add("assert-log"); ret(0); continue; }
                if (pc == 0x4001a874L) { ret(IFACE); continue; }                                                    // interface state
                if (pc == 0x4001c78cL) { ops.add("rawcbootget"); ret(0); continue; }
                if (pc == 0x4001c708L) { ret(SENT_CLASSFN); continue; }
                if (pc == 0x40066558L) { ret(5); continue; }
                if (pc == 0x40067a4cL) { ret(5); continue; }
                if (pc == 0x40066b34L || pc == 0x4006a340L || pc == 0x400662b0L || pc == 0x400007c0L) { ret(0); continue; }
                if (pc == 0x4006b1a4L) { ops.add("bootstatus " + reg("r0") + "/" + reg("r1")); ret(0); continue; }
                if (pc == 0x4008281cL) { ret(0x4200e180L); continue; }                                              // sem create
                if (pc == 0x40082474L) { taskCreated = true; taskName = string(reg("r0"));                          // task create
                    stateSize = get(STATE + 0x14, 4); stateDest = get(STATE + 0x18, 4); return "task-created"; }
                if (pc == 0x40082868L || pc == 0x400828acL) { ret(0); continue; }
                if (pc == 0x40087568L || pc == 0x4007ac44L || pc == 0x4007aa54L) { ret(0); continue; }
                if (pc == 0x400881b4L) { long n = reg("r2"); mem(reg("r0"), mem(reg("r1"), (int) n)); ret(reg("r0")); continue; } // memcpy
                if (pc == 0x40088230L) { long n = reg("r2"); byte[] z = new byte[(int) n]; Arrays.fill(z, (byte) reg("r1")); mem(reg("r0"), z); ret(reg("r0")); continue; } // memset
                if (pc == 0x4000f270L || (pc >= 0x4000f270L && pc < 0x4000fc70L)) { t.stepInstruction(); continue; }
                throw new Exception("Unexpected execution " + Long.toHexString(pc));
            }
            throw new Exception("Instruction limit");
        }
    }

    public void run() throws Exception {
        image = new byte[0xa2a00];
        currentProgram.getMemory().getBytes(toAddr(0x40000000L), image);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if (!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976")) throw new Exception("Wrong full updater image");
        String[] args = getScriptArgs();
        byte[] mainImage = Files.readAllBytes(Path.of(args[1]));
        try (PrintWriter out = new PrintWriter(args[0])) {
            out.println("Full updater SHA-256: " + hash);
            boolean pass = true;
            // A: tiny file
            { Arm a = new Arm(new byte[0x2000], true); String b = a.run();
              boolean ok = b.equals("task-created") && a.stateDest == 0x40000000L;
              pass &= ok; out.printf("A tiny file: boundary=%s task=%s size=%x dest=%08x ops=%s logs=%s -> %s%n", b, a.taskName, a.stateSize, a.stateDest, a.ops, a.logs, ok ? "PASS" : "FAIL"); }
            // B: real main image
            { Arm a = new Arm(mainImage, true); String b = a.run();
              boolean spot = a.fileBuf > 0 && Arrays.equals(a.mem(a.fileBuf, 16), Arrays.copyOfRange(mainImage, 0, 16))
                  && Arrays.equals(a.mem(a.fileBuf + mainImage.length - 16, 16), Arrays.copyOfRange(mainImage, mainImage.length - 16, mainImage.length));
              boolean ok = b.equals("task-created") && a.stateSize == mainImage.length && a.stateDest == 0x40000000L && spot;
              pass &= ok; out.printf("B firmware-main.bin (%d bytes): boundary=%s task=%s size=%x dest=%08x bufspot=%s ops=%s -> %s%n", mainImage.length, b, a.taskName, a.stateSize, a.stateDest, spot, a.ops, ok ? "PASS" : "FAIL"); }
            // C: absent
            { Arm a = new Arm(new byte[0], false); String b = a.run();
              boolean ok = b.equals("returned") && !a.taskCreated;
              pass &= ok; out.printf("C file absent: boundary=%s taskCreated=%s logs=%s -> %s%n", b, a.taskCreated, a.logs, ok ? "PASS" : "FAIL"); }
            out.println(pass ? "SD-BOOT LOADER PASS" : "SD-BOOT LOADER FAIL");
            out.flush();
            if (!pass) throw new Exception("SD-boot loader controls failed");
        }
    }
}
