// Original NAND header parser; stop before erase-capable table construction.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterNandHeader extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        final List<String> posts=new ArrayList<>();int reads=0,logs=0;byte[] expectedImage;
        Arm(){
            bytes(BASE,image);bytes(SP-256,new byte[512]);bytes(RECORD,new byte[256]);
            bytes(0x400a39d0L,new byte[96]);bytes(0x400a3a64L,new byte[8]);
            
            expectedImage=bytes(BASE,image.length);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",SP);reg("lr",END);
        }
        void bytes(long a,byte[] b){m.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] bytes(long a,int n){return m.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        int allocations=0,dumps=0;
        long parse(int signature,int tag,int total,int reserved) throws Exception {
            bytes(0x400a3d28L,new byte[0x230]);bytes(RECORD,new byte[0x200]);
            bytes(0x42101000L,new byte[0x200]);
            put(0x400a3d28L+0x34,0x4003f740L,4); // resolved erase pointer supplied as state, never called
            put(0x400a3d28L+0x80,1024,4);put(0x400a3d28L+0x8c,6,4);
            put(0x400a3d28L+0x228,0x42101000L,4); // existing RAM buffer for default header copy
            put(RECORD+8,signature,2);put(RECORD+0x40,tag,1);put(RECORD+0x24,total,4);put(RECORD+0x34,reserved,4);
            reg("r0",RECORD);t.overrideCounter(toAddr(0x4002f834L));
            for(int n=0;n<2000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END||pc==0x4002a248L)return pc;
                if(pc==0x400881b4L){int count=(int)reg("r2");if(count!=512&&count!=44&&count!=8)throw new Exception("Unexpected copy");bytes(reg("r0"),bytes(reg("r1"),count));ret(reg("r0"));continue;}
                if(pc==0x4007ac04L){logs++;ret(0);continue;}
                if(pc==0x4007bf4cL){dumps++;ret(0);continue;}
                if(pc==0x4007f6dcL){allocations++;if(reg("r0")!=40)throw new Exception("Unexpected table buffer size "+reg("r0"));bytes(0x42102000L,new byte[40]);ret(0x42102000L);continue;}
                if(!((pc>=0x4002f834L&&pc<0x4002fba8L)||(pc>=0x400301d8L&&pc<0x4003024cL)||(pc>=0x4002f824L&&pc<0x4002f834L)))throw new Exception("Unexpected parser PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Parser instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Original NAND header parser, synthetic geometry/header/buffers only. No low initializer, NAND read, table builder, erase, program, MMIO or camera execution.");
            out.println("Copy/allocation/log/dump helpers modeled. Original state getter executes with preexisting default-header RAM buffer; loaded image bytes remain unchanged.");
            int[][] cases={{0,0x32,1024,4},{0xbeef,0,1024,4},{0xbeef,0x32,1023,4},{0xbeef,0x32,1024,0},{0xbeef,0x32,1024,4}};
            for(int i=0;i<cases.length;i++){
                Arm a=new Arm();int[] c=cases[i];long stop=a.parse(c[0],c[1],c[2],c[3]);
                if(stop!=(i==4?0x4002a248L:END)||a.allocations!=(i==4?1:0)||a.dumps!=(i<3?1:0))throw new Exception("Header control mismatch");
                if(i<3&&a.reg("r0")!=1)throw new Exception("Invalid header accepted");
                if(i==3&&a.reg("r0")!=0)throw new Exception("Valid zero-region header failed");
                if(i==4&&(a.reg("r0")!=0x400a3ed0L||a.val(0x400a3ed0L+0x34,4)!=0x4003f740L||a.val(0x400a3ed0L+0x58,4)!=4||a.val(0x400a3ed0L+0x74,4)!=0x42102000L||a.val(0x400a3ed0L+0x70,4)!=0x42102020L))throw new Exception("Table arguments/buffer mismatch");
                a.unchanged();out.printf("PASS signature=%04x tag=%02x total=%d reservedC=%d -> boundary=%08x allocations=%d dumps=%d; table builder never executes.%n",c[0],c[1],c[2],c[3],stop,a.allocations,a.dumps);
            }
            out.println("5 controls passed. No real NAND metadata observed; chosen headers only demonstrate guarded reachability. No end-to-end startup or recovery proof.");
        }
    }
}
