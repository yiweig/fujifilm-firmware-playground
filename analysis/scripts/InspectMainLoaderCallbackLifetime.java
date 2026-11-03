// Original loader registration and repeated reset initialization; no camera or RTOS.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectMainLoaderCallbackLifetime extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        final List<String> posts=new ArrayList<>();int reads=0,logs=0;byte[] expectedImage;
        Arm(){
            bytes(BASE,image);bytes(SP-256,new byte[512]);bytes(RECORD,new byte[256]);
            
            
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
        List<Long> released=new ArrayList<>();
        void fixture(long callback,boolean scratch) {
            bytes(0x40fd0d50L,new byte[0x44]);bytes(0x40fc7b78L,new byte[0x90]);bytes(0x40fc8270L,new byte[0x1c]);
            put(0x40fd0d90L,callback,4);put(0x40fd0d54L,0xaa,1);
            if(scratch)put(0x40fd0d50L,0x42002000L,4);
        }
        long execute(long entry) throws Exception {
            reg("sp",SP);reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int n=0;n<1000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END||pc==0x401e1f58L||pc==0x405da53cL)return pc;
                if(pc==0x40020eccL){released.add(reg("r0"));ret(0);continue;}
                if(pc==0x4000c30cL){ret(0);continue;}
                boolean allowed=(pc>=0x401e2104L&&pc<0x401e2138L)||(pc>=0x401ba258L&&pc<0x401ba290L)
                    ||(pc>=0x401e122cL&&pc<0x401e1270L)||(pc>=0x401e0718L&&pc<0x401e0748L)
                    ||(pc>=0x401c1314L&&pc<0x401c13e8L)||(pc>=0x401e1f40L&&pc<0x401e1f58L);
                if(!allowed)throw new Exception("Unexpected lifetime PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Lifetime instruction bound");
        }
        void imageAfterInit() throws Exception {
            byte[] expected=image.clone();expected[0xd79b21]=2;
            if(!Arrays.equals(expected,bytes(BASE,image.length)))throw new Exception("Unexpected loaded-byte change beyond receiver-state data byte");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Main image SHA256 "+hash);
            out.println("Original loader registration and initializer, including cleanup with supplied empty 40fc8270 state. IRQ and allocator release helpers modeled; no USB/controller/RTOS or storage runs.");
            out.println("Initializer changes original loaded RAM-data byte 40d79b21 to receiver state 2; other loaded bytes remain unchanged. Source file is untouched.");
            for(long callback:new long[]{0,0x405da53cL})for(boolean scratch:new boolean[]{false,true}){
                Arm a=new Arm();a.fixture(callback,scratch);
                if(a.execute(0x401e2104L)!=END||a.val(0x40fc7b78L,4)!=0x401e122cL||a.val(0x40fc7bccL,4)!=0x401e1280L||a.val(0x40fd0d90L,4)!=callback)throw new Exception("Registration lifetime mismatch");a.unchanged();
                for(int i=0;i<2;i++){
                    if(a.execute(0x401e122cL)!=END||a.val(0x40fd0d90L,4)!=callback||a.val(0x40fd0d50L,4)!=0||a.val(0x40fd0d54L,1)!=0||a.val(0x40d79b21L,1)!=2)throw new Exception("Initializer lifetime mismatch");a.imageAfterInit();
                }
                if(!a.released.equals(scratch?List.of(0x42002000L):List.of()))throw new Exception("Unexpected releases");
                long boundary=a.execute(0x401e1f40L);
                if(boundary!=(callback==0?0x401e1f58L:callback))throw new Exception("Post-reset optional dispatch");a.imageAfterInit();
                out.printf("PASS initial-callback=%08x old-scratch=%s -> registration plus two initializations preserve callback=%08x; freed=%s; optional-dispatch-boundary=%08x.%n",callback,scratch,a.val(0x40fd0d90L,4),a.released,boundary);
            }
            out.println("4 controls passed. No hardware fill, complete boot, active-state cleanup branches, real bus reset, task scheduling, callback body, NAND/SD, payload or camera executes. Actual callback value remains unobserved.");
        }
    }
}
