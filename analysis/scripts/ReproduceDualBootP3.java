// P3 controls: updater boot-register consumer with a mailbox filename.
// Cases (gate=0x88888888, status=0x0100, button bytes 0, backend 0x00):
//  A) mailbox MAIN/BIN, file exists  -> loader 0x4000f270 called with
//     pathname "D:\MAIN.BIN" (load-and-boot branch; dest from r1).
//  B) mailbox empty (default firmware.brn), file exists -> command-task
//     setup 0x40009ca0 boundary (update branch), loader NOT called.
//  C) mailbox MAIN/BIN, file absent  -> continuation 0x40069374.
// Modeled as in ReproduceRamPushP1. Original consumer instructions run.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceDualBootP3 extends GhidraScript {
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        String checked=null; String loaderPath=null; long loaderDest=-1; int checkResult;
        Arm(String name,String ext,int checkResult) throws Exception {
            this.checkResult=checkResult;
            mem(0x40000000L,image);mem(0x10000000L,new byte[0x6000]);mem(0x4200e000L,new byte[0x3000]);
            put(0x10001300L,4,0x88888888L);put(0x10004054L,2,0x0100);
            if(name.length()>8 || ext.length()>3)throw new Exception("Invalid mailbox fixture");
            mem(0x100040b9L,name.getBytes("US-ASCII"));mem(0x100040c1L,ext.getBytes("US-ASCII"));
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
        long run() throws Exception {
            reg("sp",0x42010000L);reg("lr",0x43000000L);
            t.overrideCounter(toAddr(0x400009c4L));
            for(int step=0;step<6000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x40069374L || pc==0x40009ca0L)return pc;
                if(pc==0x4000f270L){loaderPath=string(reg("r0"));loaderDest=reg("r1");return pc;}
                if(pc==0x4000fc70L)throw new Exception("Update wrapper reached");
                if(pc==0x4007ac04L){ret(0);continue;}
                if(pc==0x400165d0L || pc==0x400165e0L || pc==0x400166b8L){ret(0);continue;}
                if(pc==0x4000e558L || pc==0x4001aaacL || pc==0x4001b4e4L || pc==0x4001b744L || pc==0x4002bde8L || pc==0x4001be6cL){ret(0);continue;}
                if(pc==0x4001ad68L){ret(1);continue;}
                if(pc==0x4000e5a4L){if(reg("r0")!=2)throw new Exception("Unexpected drive probe");ret(0);continue;}
                if(pc==0x40041728L){checked=string(reg("r0"));ret(checkResult);continue;}
                if(pc==0x40075ea4L || pc==0x400874e8L){ret(0);continue;}
                if(pc==0x400881b4L){long n=reg("r2");mem(reg("r0"),mem(reg("r1"),(int)n));ret(reg("r0"));continue;}
                if((pc>=0x400009c4L && pc<0x400010ecL)||(pc>=0x4006b194L && pc<0x4006b1a0L)){t.stepInstruction();continue;}
                throw new Exception("Unexpected execution "+Long.toHexString(pc));
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong full updater image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Full updater SHA-256: "+hash);
            boolean pass=true;
            // A: MAIN.BIN exists -> loader load-and-boot
            { Arm a=new Arm("MAIN","BIN",0); long b=a.run();
              boolean ok=b==0x4000f270L && "D:\\MAIN.BIN".equals(a.loaderPath) && Arrays.equals(image,a.mem(0x40000000L,image.length));
              pass&=ok; out.printf("A mailbox=MAIN.BIN exists: boundary=%08x loaderPath=%s dest=%08x -> %s%n",b,a.loaderPath,a.loaderDest,ok?"PASS":"FAIL"); }
            // B: default (empty mailbox) firmware.brn exists -> loader is
            // called with the default pathname (observed; matches the
            // earlier mailbox harness). We never rely on this case.
            { Arm a=new Arm("","",0); long b=a.run();
              boolean ok=b==0x4000f270L && "D:\\firmware.brn".equals(a.loaderPath) && "D:\\firmware.brn".equals(a.checked);
              pass&=ok; out.printf("B default firmware.brn exists: boundary=%08x checked=%s loader=%s -> %s%n",b,a.checked,a.loaderPath,ok?"PASS":"FAIL"); }
            // D: explicit mailbox firmware/brn exists -> update-branch
            // setup 0x40009ca0. This is the branch our MAIN.BIN naming avoids.
            { Arm a=new Arm("firmware","brn",0); long b=a.run();
              boolean ok=b==0x40009ca0L && a.loaderPath==null;
              pass&=ok; out.printf("D explicit firmware/brn exists: boundary=%08x loader=%s -> %s%n",b,a.loaderPath,ok?"PASS":"FAIL"); }
            // C: MAIN.BIN absent -> continuation (regression vs P1)
            { Arm a=new Arm("MAIN","BIN",1); long b=a.run();
              boolean ok=b==0x40069374L && a.loaderPath==null;
              pass&=ok; out.printf("C mailbox=MAIN.BIN absent: boundary=%08x loader=%s -> %s%n",b,a.loaderPath,ok?"PASS":"FAIL"); }
            out.println(pass?"DUAL-BOOT P3 PASS":"DUAL-BOOT P3 FAIL");
            out.flush();
            if(!pass)throw new Exception("P3 controls failed");
        }
    }
}
