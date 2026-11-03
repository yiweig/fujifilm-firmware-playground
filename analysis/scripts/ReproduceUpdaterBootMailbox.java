// Offline supplied boot-register fixtures; optional modeled returns to USB-startup boundary.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceUpdaterBootMailbox extends GhidraScript {
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();String checked=null;int checkResult;
        int modeledBootFailures=0,modeledCleanupReturns=0,modeledContinuationReturns=0;
        Arm(boolean enabled,String name,String extension,int checkResult) throws Exception {
            this.checkResult=checkResult;mem(0x40000000L,image);mem(0x10000000L,new byte[0x6000]);mem(0x4200e000L,new byte[0x3000]);
            put(0x10001300L,4,enabled?1:0);put(0x10004054L,2,0x80);put(0x100040b8L,1,1);
            if(name.length()>8 || extension.length()>3)throw new Exception("Invalid mailbox fixture");
            mem(0x100040b9L,name.getBytes("US-ASCII"));mem(0x100040c1L,extension.getBytes("US-ASCII"));
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        String string(long a) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<64;i++){int b=mem(a+i,1)[0]&255;if(b==0)return out.toString("US-ASCII");out.write(b);}throw new Exception("Unbounded pathname");}
        long run() throws Exception {return run(false);}
        long run(boolean followReturn) throws Exception {
            reg("sp",0x42010000L);reg("lr",0x43000000L);
            // At 40001390 the startup routine's four-word saved frame is already on stack.
            if(followReturn)put(0x4201000cL,4,0x43000000L);
            t.overrideCounter(toAddr(followReturn?0x40001390L:0x400009c4L));
            for(int step=0;step<3000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(followReturn&&pc==0x400007f4L)return pc;
                if(followReturn&&pc==0x4000f270L){modeledBootFailures++;ret(1);continue;}
                if(followReturn&&pc==0x40075ea4L){modeledCleanupReturns++;ret(0);continue;}
                if(followReturn&&pc==0x40069374L){modeledContinuationReturns++;ret(0);continue;}
                if(followReturn&&pc==0x400874e8L){ret(0);continue;}
                if(pc==0x40009ca0L || pc==0x4000f270L || pc==0x40069374L || pc==0x40075ea4L)return pc;
                if(pc==0x400881b4L){long n=reg("r2");if(n!=16)throw new Exception("Unexpected copy");mem(reg("r0"),mem(reg("r1"),(int)n));ret(reg("r0"));continue;}
                if(pc==0x400165d0L || pc==0x400165e0L || pc==0x400166b8L || pc==0x4007ac04L){ret(0);continue;}
                if(pc==0x4001ad68L){ret(1);continue;} // supplied storage readiness avoids unaudited initialization
                if(pc==0x4000e5a4L){if(reg("r0")!=2)throw new Exception("Unexpected drive probe");ret(0);continue;}
                if(pc==0x40041728L){checked=string(reg("r0"));ret(checkResult);continue;}
                if(!((pc>=0x400009c4L && pc<0x400010ecL)||(pc>=0x4006b194L && pc<0x4006b1a0L)||(followReturn&&pc>=0x40001390L&&pc<0x4000139cL)))throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong full updater image");
        Object[][] cases={{false,"","",0,null,0x40069374L},{true,"","",0,"D:\\firmware.brn",0x4000f270L},
            {true,"TEST","",0,"D:\\TEST.brn",0x40009ca0L},{true,"TEST","bin",0,"D:\\TEST.bin",0x4000f270L},
            {true,"TEST","BRN",0,"D:\\TEST.BRN",0x4000f270L},{true,"FPUPDATE","DAT",0,"D:\\FPUPDATE.DAT",0x4000f270L},
            {true,"TEST","brn",1,"D:\\TEST.brn",0x40075ea4L},{true,"firmware","brn",0,"D:\\firmware.brn",0x40009ca0L}};
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Full updater SHA-256: "+hash);
            out.println("Offline original startup 400009c4; MMIO addresses contain supplied emulator bytes, no hardware access.");
            for(Object[] c:cases) {
                Arm a=new Arm((Boolean)c[0],(String)c[1],(String)c[2],(Integer)c[3]);long boundary=a.run();
                if(boundary!=(Long)c[5] || !Objects.equals(a.checked,c[4]) || !Arrays.equals(image,a.mem(0x40000000L,image.length)))throw new Exception("Mailbox branch control");
                out.printf("PASS enabled=%s name=%s ext=%s file-check=%s -> pathname=%s, boundary=%08x; loaded image unchanged.%n",c[0],c[1],c[2],c[3],a.checked,boundary);
            }
            out.println("8 controls passed. Same default/custom pathname can take different branches because basename-length context also participates.");
            out.println("Modeled: copy, configuration setters, storage readiness, drive probe, file-check result and logs. Original boot-status getter and path/branch logic execute.");
            out.println("Original eight controls stop before command-task setup 40009ca0, file/rawC boot 4000f270, platform helper 40075ea4 or continuation 40069374. None executes.");
            out.println("Not full updater boot, a physical button recipe, a demonstrated register writer, immutable recovery, file-format validation or a card-preparation instruction.");
            Object[][] returns={{false,"","",0,null,0,0},{true,"TEST","bin",1,"D:\\TEST.bin",0,1},{true,"TEST","bin",0,"D:\\TEST.bin",1,1}};
            out.println("Follow-return controls start at original startup call 40001390. Loader returns failure; platform cleanup/continuation are modeled as returning, not executed. Registers are synthetic memory.");
            for(Object[] c:returns){
                Arm a=new Arm((Boolean)c[0],(String)c[1],(String)c[2],(Integer)c[3]);
                long boundary=a.run(true);
                if(boundary!=0x400007f4L||!Objects.equals(a.checked,c[4])||a.modeledBootFailures!=(Integer)c[5]||a.modeledCleanupReturns!=(Integer)c[6]||a.modeledContinuationReturns!=1||!Arrays.equals(image,a.mem(0x40000000L,image.length)))throw new Exception("Startup return control");
                if((Integer)c[6]!=0&&(a.mem(0x10005105L,1)[0]!=0||a.mem(0x10005100L,1)[0]!=2||Utils.bytesToLong(a.mem(0x10000030L,4),4,false)!=8||Utils.bytesToLong(a.mem(0x10000034L,4),4,false)!=8))throw new Exception("Post-cleanup register sequence");
                out.printf("PASS startup gate=%s file-check=%s -> boundary=400007f4, modeled-boot-errors=%d modeled-cleanup-returns=%d; executable unchanged. USB startup not executed.%n",c[0],c[3],a.modeledBootFailures,a.modeledCleanupReturns);
            }
            out.println("11 controls passed in total. Return-flow controls are conditional on modeled platform returns; no real USB availability, cleanup semantics, persistent effects or recovery established.");
        }
    }
}
