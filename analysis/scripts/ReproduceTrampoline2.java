// Offline semantics check for trampoline v2 (SRAM/low staging copier).
// Args: [0]=output, [1]=trampoline .bin. Simulates CMD=1 then CMD=2
// invocations with fixture source bytes; verifies staging contents, DONE
// transitions, register/stack preservation, and arrival at the handler.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceTrampoline2 extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    static final int LENGTH=0x01400000;
    static final long TRAMP=0x41100040L, CTRL=0x41100100L, HANDLER=0x401e1280L;
    byte[] image; PcodeEmulator machine; PcodeThread<byte[]> thread; AddressSpace ram;
    void bytes(long a,byte[] b){machine.getSharedState().setVar(ram,a,b.length,true,b);}
    byte[] bytes(long a,int n){return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
    void put(long a,long v){bytes(a,Utils.longToBytes(v,4,false));}
    long get(long a){return Utils.bytesToLong(bytes(a,4),4,false);}
    void reg(String n,long v){var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
    long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
    void invoke() throws Exception {
        reg("sp",SP);reg("lr",END);
        reg("r0",0x11111111L);reg("r1",0x22222222L);reg("r2",0x33333333L);reg("r3",0x44444444L);
        thread.overrideCounter(toAddr(TRAMP));
        for(int i=0;i<200000;i++){ long pc=thread.getCounter().getOffset(); if(pc==HANDLER)return; thread.stepInstruction(); }
        throw new Exception("did not reach handler");
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        byte[] code=Files.readAllBytes(Path.of(getScriptArgs()[1]));
        machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        ram=currentProgram.getLanguage().getDefaultSpace();
        bytes(BASE,new byte[LENGTH]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);
        bytes(TRAMP,code);
        thread=machine.newThread();thread.overrideContextWithDefault();
        for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        // Fixtures
        byte[] sram=new byte[0x10000]; for(int i=0;i<sram.length;i++)sram[i]=(byte)(i*31+7);
        bytes(0x3f000000L,sram);
        byte[] low=new byte[0x100]; for(int i=0;i<low.length;i++)low[i]=(byte)(i*17+3);
        bytes(0xb00L,low);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            // CMD=1: SRAM copy
            put(CTRL,0);put(CTRL+4,0);put(CTRL+8,1);
            invoke();
            boolean ok1=get(CTRL+4)==2 && Arrays.equals(sram,bytes(0x41110000L,0x10000))
                && reg("r0")==0x11111111L && reg("r3")==0x44444444L && reg("sp")==SP;
            out.println("CMD=1 SRAM: DONE="+get(CTRL+4)+" staging match="+Arrays.equals(sram,bytes(0x41110000L,0x10000))+" regs/stack ok="+(reg("r0")==0x11111111L&&reg("sp")==SP));
            // Re-invoke with DONE=2: must not recopy (corrupt staging first byte, expect unchanged)
            byte[] st=bytes(0x41110000L,0x10000); st[0]^=0xff; bytes(0x41110000L,st);
            invoke();
            boolean okIdle=bytes(0x41110000L,1)[0]==st[0];
            out.println("DONE!=0 re-invoke: staging untouched="+okIdle+" counter="+get(CTRL));
            // CMD=2: low copy
            put(CTRL+4,0);put(CTRL+8,2);
            invoke();
            boolean ok2=get(CTRL+4)==2 && Arrays.equals(low,bytes(0x41121000L,0x100));
            out.println("CMD=2 low: DONE="+get(CTRL+4)+" staging match="+Arrays.equals(low,bytes(0x41121000L,0x100)));
            out.println("counter total="+get(CTRL));
            boolean pass=ok1&&okIdle&&ok2&&get(CTRL)==3;
            out.println(pass?"TRAMPOLINE2 PASS":"TRAMPOLINE2 FAIL");
            out.flush();
            if(!pass)throw new Exception("v2 semantics failed");
        }
    }
}
