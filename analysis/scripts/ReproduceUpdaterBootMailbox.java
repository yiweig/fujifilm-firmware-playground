// Offline supplied boot-register fixtures. Stops before file boot, ISP setup or shutdown.
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
        long run() throws Exception {
            reg("sp",0x42010000L);reg("lr",0x43000000L);t.overrideCounter(toAddr(0x400009c4L));
            for(int step=0;step<3000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x40009ca0L || pc==0x4000f270L || pc==0x40069374L || pc==0x40075ea4L)return pc;
                if(pc==0x400881b4L){long n=reg("r2");if(n!=16)throw new Exception("Unexpected copy");mem(reg("r0"),mem(reg("r1"),(int)n));ret(reg("r0"));continue;}
                if(pc==0x400165d0L || pc==0x400165e0L || pc==0x400166b8L || pc==0x4007ac04L){ret(0);continue;}
                if(pc==0x4001ad68L){ret(1);continue;} // supplied storage readiness avoids unaudited initialization
                if(pc==0x4000e5a4L){if(reg("r0")!=2)throw new Exception("Unexpected drive probe");ret(0);continue;}
                if(pc==0x40041728L){checked=string(reg("r0"));ret(checkResult);continue;}
                if(!((pc>=0x400009c4L && pc<0x400010ecL)||(pc>=0x4006b194L && pc<0x4006b1a0L)))throw new Exception("Unexpected execution "+Long.toHexString(pc));
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
            out.println("Stops before ISP task/setup 40009ca0, file/rawC boot 4000f270, shutdown 40075ea4 or continuation 40069374. None executes.");
            out.println("Not full updater boot, a physical button recipe, a demonstrated register writer, immutable recovery, file-format validation or a card-preparation instruction.");
        }
    }
}
