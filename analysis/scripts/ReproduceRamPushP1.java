// P1 controls for the RAM-push plan (analysis/ram-push-sd-boot-plan.md).
// Fixture = the predicted live state after a 0x04f7 handoff of the stock
// updater on this unit: gate stamped 0x88888888 by the updater +0x200
// entry, boot status 0x0100 (FW1 selected, bit7 clear, measured live),
// button bytes zero, filename mailbox empty, no firmware.brn on the card.
// Control 1: boot-register consumer 0x400009c4 must reach the
//   continuation call 0x40069374 via the file-not-exist path, having
//   installed the selection callback, without reaching the loader
//   0x4000f270, the update wrapper 0x4000fc70, or any storage write.
// Control 2: selection callback 0x40000888 with the RawCBoot getter
//   failing for every record must end in exactly one loader call,
//   with (":DEF_FW2", 0x40000000), which the live loader rejects
//   (zero length). No drive selector call may occur.
// Modeled: logs, delay/timer, callback installer, storage-init and
// NAND-registration entry points, drive probe, file check, cleanup.
// Original consumer/callback/status-getter instructions execute.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceRamPushP1 extends GhidraScript {
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        String checked=null; long loaderName=0, loaderDest=0; int loaderCalls=0;
        boolean installerCalled=false; boolean nandRegCalled=false; boolean driveSelectorCalled=false;
        List<Long> modeled=new ArrayList<>();
        Arm(int backendByte) throws Exception {
            mem(0x40000000L,image);mem(0x10000000L,new byte[0x6000]);mem(0x4200e000L,new byte[0x3000]);
            put(0x10001300L,4,0x88888888L);            // gate stamped by updater +0x200
            put(0x10004054L,2,0x0100);                // live-measured boot status
            put(0x10000058L,1,backendByte);           // storage backend select byte
            // button bytes 0x100040b4..b8 and mailbox b9..c3 stay zero (live-measured)
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        String string(long a) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<64;i++){int b=mem(a+i,1)[0]&255;if(b==0)return out.toString("US-ASCII");out.write(b);}throw new Exception("Unbounded string");}
        long runConsumer() throws Exception {
            reg("sp",0x42010000L);reg("lr",0x43000000L);
            t.overrideCounter(toAddr(0x400009c4L));
            for(int step=0;step<6000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x40069374L)return pc;                       // continuation boundary
                if(pc==0x4000f270L || pc==0x4000fc70L || pc==0x40009ca0L)
                    throw new Exception("Forbidden target reached: "+Long.toHexString(pc));
                if(pc==0x4007ac04L){ret(0);continue;}               // log print
                if(pc==0x400165d0L || pc==0x400165e0L || pc==0x400166b8L){modeled.add(pc);ret(0);continue;} // DRAM config setters
                if(pc==0x4000e558L){installerCalled=(reg("r0")==0x40000888L);modeled.add(pc);ret(0);continue;}
                if(pc==0x4001aaacL || pc==0x4001b4e4L || pc==0x4001b744L){modeled.add(pc);ret(0);continue;}
                if(pc==0x4002bde8L){modeled.add(pc);ret(0);continue;}
                if(pc==0x4001be6cL){nandRegCalled=true;modeled.add(pc);ret(0);continue;}
                if(pc==0x4001ad68L){ret(1);continue;}               // storage ready (modeled, as in mailbox controls)
                if(pc==0x4000e5a4L){if(reg("r0")!=2)throw new Exception("Unexpected drive probe "+reg("r0"));ret(0);continue;}
                if(pc==0x40041728L){checked=string(reg("r0"));ret(1);continue;} // file does NOT exist
                if(pc==0x40075ea4L || pc==0x400874e8L){modeled.add(pc);ret(0);continue;}
                if(pc==0x400881b4L){long n=reg("r2");mem(reg("r0"),mem(reg("r1"),(int)n));ret(reg("r0"));continue;}
                if((pc>=0x400009c4L && pc<0x400010ecL)||(pc>=0x4006b194L && pc<0x4006b1a0L)){t.stepInstruction();continue;}
                throw new Exception("Unexpected execution "+Long.toHexString(pc));
            }
            throw new Exception("Instruction limit (consumer)");
        }
        long runCallback() throws Exception {
            reg("sp",0x42010000L);reg("lr",0x43000000L);
            t.overrideCounter(toAddr(0x40000888L));
            for(int step=0;step<6000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x4000f270L){loaderCalls++;loaderName=reg("r0");loaderDest=reg("r1");return pc;}
                if(pc==0x4007ac04L || pc==0x40001ab8L || pc==0x4000e568L){ret(0);continue;}
                if(pc==0x4000ed70L){ret(1);continue;}               // RawCBoot record lookup fails
                if(pc==0x4000e564L){ret(0);continue;}               // context free
                if(pc==0x4000e5a4L){driveSelectorCalled=true;ret(0);continue;}
                if(pc==0x40088324L || pc==0x40088178L){throw new Exception("Record-name compare reached despite absent RawCBoot");}
                if(pc>=0x40000888L && pc<0x400009c4L){t.stepInstruction();continue;}
                throw new Exception("Unexpected execution "+Long.toHexString(pc));
            }
            throw new Exception("Instruction limit (callback)");
        }
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong full updater image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Full updater SHA-256: "+hash);
            out.println("P1 fixture: gate=0x88888888, status=0x0100, button bytes 0, mailbox empty, file absent. MMIO is synthetic emulator memory.");
            boolean pass=true;
            for(int backend:new int[]{0x00,0x40}) {
                Arm a=new Arm(backend);
                long boundary=a.runConsumer();
                boolean ok=boundary==0x40069374L && "D:\\firmware.brn".equals(a.checked) && a.installerCalled && a.nandRegCalled
                    && Arrays.equals(image,a.mem(0x40000000L,image.length));
                pass&=ok;
                out.printf("consumer backend=0x%02x: boundary=%08x pathname=%s installer=%s nandReg=%s image-unchanged=%s -> %s%n",
                    backend,boundary,a.checked,a.installerCalled,a.nandRegCalled,Arrays.equals(image,a.mem(0x40000000L,image.length)),ok?"PASS":"FAIL");
            }
            {
                Arm a=new Arm(0x00);
                long boundary=a.runCallback();
                String name=a.string(a.loaderName);
                boolean ok=boundary==0x4000f270L && a.loaderCalls==1 && ":DEF_FW2".equals(name) && a.loaderDest==0x40000000L
                    && !a.driveSelectorCalled && Arrays.equals(image,a.mem(0x40000000L,image.length));
                pass&=ok;
                out.printf("callback RawCBoot-absent: boundary=%08x loader-calls=%d name=%s dest=%08x driveSelector=%s -> %s%n",
                    boundary,a.loaderCalls,name,a.loaderDest,a.driveSelectorCalled,ok?"PASS":"FAIL");
            }
            out.println("Modeled (not executed): log prints, callback installer, storage-init/NAND-registration entries, drive probe, file check, cleanup. Registration's real callback invocation and all USB/storage hardware effects are outside this control.");
            out.println(pass?"RAM-PUSH P1 PASS":"RAM-PUSH P1 FAIL");
            out.flush();
            if(!pass)throw new Exception("P1 controls failed");
        }
    }
}
