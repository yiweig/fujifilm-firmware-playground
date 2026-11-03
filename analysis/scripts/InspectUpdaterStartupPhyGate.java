// Original startup consumer and PHY resource acquire, stopping before platform continuation.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterStartupPhyGate extends GhidraScript {
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();String checked=null;int checkResult;
        int acquisitions=0;List<Long> delays=new ArrayList<>();
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
        long val(long a,int n){return Utils.bytesToLong(mem(a,n),n,false);}
        long run(int pll) throws Exception {
            mem(0x400b5d70L,new byte[96]);put(0x400ba450L,4,pll);
            mem(0x400a3a28L,new byte[16]);put(0x400a3a30L,4,1); // supplied existing registry, not readiness
            put(0x10000380L,1,0xaa);put(0x10000383L,1,0xbb);put(0x10005021L,1,0xcc);put(0x10000064L,4,0xa5a5a5a5L);
            reg("sp",0x42010000L);reg("lr",0x43000000L);t.overrideCounter(toAddr(0x400009c4L));
            for(int step=0;step<10000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x40069374L)return pc;
                if(pc==0x40075ea4L)acquisitions++;
                if(pc==0x4007da98L){ret(0);continue;}
                if(pc==0x4007a730L){delays.add(reg("r0"));ret(0);continue;}
                if(pc==0x400874e8L){ret(0);continue;}
                if(pc==0x400881b4L){if(reg("r2")!=16)throw new Exception("Unexpected copy");mem(reg("r0"),mem(reg("r1"),16));ret(reg("r0"));continue;}
                if(pc==0x400165d0L||pc==0x400165e0L||pc==0x400166b8L||pc==0x4007ac04L){ret(0);continue;}
                if(pc==0x4000e5a4L){if(reg("r0")!=2)throw new Exception("Unexpected logical drive");ret(0);continue;}
                if(pc==0x40041728L){checked=string(reg("r0"));ret(1);continue;} // supplied missing file
                boolean allowed=(pc>=0x400009c4L&&pc<0x400010ecL)||(pc>=0x4006b194L&&pc<0x4006b1a0L)
                    ||(pc>=0x4001ad68L&&pc<0x4001ad74L)||(pc>=0x40075dd8L&&pc<0x40075ed4L)
                    ||(pc>=0x40066134L&&pc<0x40066240L)||(pc>=0x400662b0L&&pc<0x400662c4L);
                if(!allowed)throw new Exception("Unexpected startup/PHY PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Startup/PHY instruction bound");
        }

    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong full updater image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Complete updater SHA256 "+hash);
            out.println("Original automatic startup consumer plus original USB PHY/resource acquire. Synthetic gate, status, registry, file failure, MMIO and counters; stop before 40069374. No camera or real I/O.");
            out.println("Modeled configuration setters, drive activation, file check, copy, logging, IRQ/delay and 400874e8. Original registry-count getter executes. No backend initialization or mount executes.");
            for(boolean enabled:new boolean[]{false,true})for(int pll:new int[]{0,2}){
                Arm a=new Arm(enabled,"TEST","bin",1);long stop=a.run(pll);
                if(stop!=0x40069374L||a.acquisitions!=(enabled?1:0)||a.val(0x400ba450L,4)!=pll+(enabled?1:0)||a.val(0x400b5d79L,1)!=(enabled?1:0)||a.val(0x400b5d83L,1)!=(enabled?1:0))throw new Exception("Acquire path mismatch");
                List<Long> expected=enabled&&pll==0?List.of(2L,2L,5L,17L,56L,56L):List.of();
                if(!a.delays.equals(expected)||a.val(0x10005021L,1)!=(enabled?0x3f:0xcc)||a.val(0x10000064L,4)!=(enabled?0x80200:0xa5a5a5a5L))throw new Exception("PHY/resource effects mismatch");
                if(a.val(0x10000380L,1)!=(enabled&&pll==0?1:0xaa)||a.val(0x10000383L,1)!=(enabled&&pll==0?3:0xbb)||!Objects.equals(a.checked,enabled?"D:\\TEST.bin":null)||!Arrays.equals(image,a.mem(0x40000000L,image.length)))throw new Exception("Unexpected state/image change");
                out.printf("PASS gate=%d initial-PHY-ref=%d -> acquire-calls=%d PHY-ref=%d delay-arguments=%s file-check=%s boundary=%08x; source image unchanged.%n",enabled?1:0,pll,a.acquisitions,a.val(0x400ba450L,4),a.delays,a.checked,stop);
            }
            out.println("4 controls passed. Not full startup, clock readiness, safe skip-storage launch, physical USB, an on-camera gate setter or recovery proof.");
        }
    }
}
