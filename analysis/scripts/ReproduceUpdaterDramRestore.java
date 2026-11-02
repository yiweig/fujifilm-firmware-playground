// Offline startup slice only: supplied SRAM/MMIO/RAM, stop before further initialization.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceUpdaterDramRestore extends GhidraScript {
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        boolean copied=false, dumped=false;
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        Arm(long gate,boolean existing,long source,int size,boolean magic) throws Exception {
            mem(0x40000000L,image);mem(0x10001300L,new byte[4]);mem(0x3f00d000L,new byte[20]);
            byte[] initial=new byte[0x3e00];Arrays.fill(initial,(byte)0x55);mem(0x3f000200L,initial);
            if(existing)mem(0x3f000200L,"DRAMPAR3".getBytes("US-ASCII"));
            byte[] payload=new byte[64];Arrays.fill(payload,(byte)0x66);
            if(magic)System.arraycopy("DRAMPAR3".getBytes("US-ASCII"),0,payload,0,8);
            mem(source,payload);put(0x10001300L,4,gate);put(0x3f00d00cL,4,source);put(0x3f00d010L,4,size);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",0x42010000L);reg("lr",0x43000000L);t.overrideCounter(toAddr(0x40001298L));
        }
        void runSlice() throws Exception {
            for(int step=0;step<300;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==0x40001388L)return;
                if(pc==0x4007ac04L){ret(0);continue;}
                if(pc==0x4007bf4cL){dumped=true;ret(0);continue;}
                if(pc==0x40088178L){if(reg("r2")!=8)throw new Exception("Unexpected compare length");ret(Arrays.equals(mem(reg("r0"),8),mem(reg("r1"),8))?0:1);continue;}
                if(pc==0x400881b4L){int n=(int)reg("r2");if(n<1||n>64||reg("r0")!=0x3f000200L)throw new Exception("Unexpected copy");mem(reg("r0"),mem(reg("r1"),n));copied=true;ret(reg("r0"));continue;}
                if(pc==0x40088230L){int n=(int)reg("r2");if(n<0||n>0x3e00||reg("r1")!=0)throw new Exception("Unexpected clear");mem(reg("r0"),new byte[n]);ret(reg("r0"));continue;}
                if(pc<0x40001298L||pc>=0x40001388L)throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater image");
        Object[][] cases={
            {"gate nonzero",1L,false,0x80010000L,32,true,false,false},
            {"existing header",0L,true,0x80010000L,32,true,false,false},
            {"restore 8 range",0L,false,0x80010000L,32,true,true,true},
            {"restore a range",0L,false,0xa0010000L,32,true,true,true},
            {"zero size",0L,false,0x80010000L,0,true,false,true},
            {"oversize",0L,false,0x80010000L,0x3e01,true,false,true},
            {"unaligned",0L,false,0x80010001L,32,true,false,true},
            {"wrong address range",0L,false,0x44010000L,32,true,false,true},
            {"wrong magic",0L,false,0x80010000L,32,false,false,true}};
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Full updater SHA-256: "+hash);
            out.println("Original ARM slice 40001298..40001388; synthetic memory only. Starts after preceding startup; stops before 40068d7c.");
            for(Object[] c:cases) {
                Arm a=new Arm((Long)c[1],(Boolean)c[2],(Long)c[3],(Integer)c[4],(Boolean)c[5]);
                byte[] before=a.mem(0x3f000200L,0x3e00);a.runSlice();
                if(a.copied!=(Boolean)c[6]||a.dumped!=(Boolean)c[7]||!Arrays.equals(image,a.mem(0x40000000L,image.length)))throw new Exception("Control failed: "+c[0]);
                byte[] expected=before;
                if(a.copied){expected=new byte[0x3e00];System.arraycopy(a.mem((Long)c[3],(Integer)c[4]),0,expected,0,(Integer)c[4]);}
                if(!Arrays.equals(expected,a.mem(0x3f000200L,0x3e00)))throw new Exception("SRAM mismatch: "+c[0]);
                out.printf("PASS %s: copied=%s diagnostic-dump=%s; SRAM bytes verified, executable image unchanged.%n",c[0],a.copied,a.dumped);
            }
            out.println("9 controls passed. Compare/copy/clear/log/dump helpers modeled. Original gate, header/size/address/alignment branches and helper arguments execute.");
            out.println("Not physical RAM alias validation, DRAM register programming, full startup, USB reachability, payload safety or recovery evidence. No camera access.");
        }
    }
}
