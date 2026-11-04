// Emulates rcaraw handler 4031a820 with synthetic argv fixtures and records
// the shell strings it dispatches. Determines a safe live invocation syntax
// offline (a missing-argument invocation crashes the real handler).
// Args: [0]=output path.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceRcarawArgs extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    static final int LENGTH=0x01400000;
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();PcodeThread<byte[]> thread;
        List<String> dispatched=new ArrayList<>(), modeCalls=new ArrayList<>();
        Arm() {
            bytes(BASE,new byte[LENGTH]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void bytes(long a,byte[] b){machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n){return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void returned(long v){reg("r0",v);thread.overrideCounter(toAddr(reg("lr")));}
        String cstr(long a){ byte[] b=new byte[256]; int n=0; while(n<255){ byte c=bytes(a+n,1)[0]; if(c==0)break; b[n++]=(byte)c; } return new String(b,0,n,java.nio.charset.StandardCharsets.US_ASCII); }
        long putStr(long a,String s){ byte[] b=(s+"\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII); bytes(a,b); return a; }
        void run(String[] args) throws Exception {
            // argv strings + array in stack region
            long strBase=SP-0x2000, arrBase=SP-0x1000;
            for(int i=0;i<args.length;i++){ putStr(strBase+i*64,args[i]);
                bytes(arrBase+i*4,Utils.longToBytes(strBase+i*64,4,false)); }
            reg("sp",SP);reg("lr",END);reg("r0",args.length);reg("r1",arrBase);
            thread.overrideCounter(toAddr(0x4031a820L));
            for(int i=0;i<60000;i++){
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==END)return;
                if(pc==0x40004064L){returned(0);continue;}                    // log
                if(pc==0x401c0624L){returned(Long.parseLong(cstr(reg("r0")).trim()));continue;} // atoi
                if(pc==0x4008088cL){ String s=cstr(reg("r0")); int idx=s.indexOf((char)reg("r1"));
                    returned(idx<0?0:reg("r0")+idx); continue; }              // strchr
                if(pc==0x40021768L){ int c=cstr(reg("r0")).compareTo(cstr(reg("r1")));
                    returned(c); continue; }                                  // strcmp
                if(pc==0x4018381cL||pc==0x40183768L){ modeCalls.add(String.format("%x(%d)",pc,reg("r0"))); returned(0); continue; }
                if(pc==0x4000c450L){returned(0);continue;}                    // delay
                if(pc==0x4000ceb0L){ String s=cstr(reg("r1")); putStr(reg("r0"),s); returned(reg("r0")); continue; } // strcpy-like
                if(pc==0x401efaccL){ dispatched.add(cstr(reg("r0"))); returned(0); continue; } // shell dispatch
                boolean admitted=(pc>=0x4031a820L&&pc<0x4031aa44L)||(pc>=0x4016b290L&&pc<0x4016b318L)
                    ||(pc>=0x40008d10L&&pc<0x40008d28L);
                if(!admitted)throw new Exception("Unexpected PC "+Long.toHexString(pc));
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        String[][] cases={
            {"17","D:","x"},
            {"17","D","x"},
            {"19","D:","x"},
            {"17","D:"},
        };
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("rcaraw handler argument emulation; dispatch/mode/delay/log stubbed, no camera.");
            for(String[] c: cases){
                Arm a=new Arm();
                try { a.run(c); out.printf("args=%s -> dispatched=%s modeCalls=%s%n", Arrays.toString(c), a.dispatched, a.modeCalls); }
                catch(Exception e){ out.printf("args=%s -> STOPPED: %s (dispatched so far=%s)%n", Arrays.toString(c), e.getMessage(), a.dispatched); }
            }
            out.flush();
        }
    }
}
