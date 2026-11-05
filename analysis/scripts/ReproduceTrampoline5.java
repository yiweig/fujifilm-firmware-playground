// Offline semantics check for trampoline v4 (copier + direct helper call).
// Args: [0]=output, [1]=trampoline .bin, [2]=recording-stub .bin.
// Control block 0x41100100: +0 counter, +4 DONE, +8 CMD (1=copy, 2=call),
// +0xc SRC/arg0, +0x10 DST/arg1, +0x14 LEN/arg2, +0x18 arg3, +0x1c stack arg.
// The stub at the helper address 0x401eb1ec records r0-r3 and the stack
// argument to 0x41100300 and returns; the real helper never executes.
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

public class ReproduceTrampoline5 extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    static final int LENGTH=0x01400000;
    static final long TRAMP=0x41100040L, CTRL=0x41100100L, HANDLER=0x401e1280L, HELPER=0x401eb1ecL, REC=0x41100300L;
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
    boolean regsOk(){return reg("r0")==0x11111111L&&reg("r1")==0x22222222L&&reg("r2")==0x33333333L&&reg("r3")==0x44444444L&&reg("sp")==SP;}
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        byte[] code=Files.readAllBytes(Path.of(getScriptArgs()[1]));
        byte[] stub=Files.readAllBytes(Path.of(getScriptArgs()[2]));
        machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        ram=currentProgram.getLanguage().getDefaultSpace();
        bytes(BASE,new byte[LENGTH]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);
        bytes(TRAMP,code);bytes(HELPER,stub);bytes(0x4015bf04L,stub);
        thread=machine.newThread();thread.overrideContextWithDefault();
        for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        byte[] src=new byte[0x100]; for(int i=0;i<src.length;i++)src[i]=(byte)(i*17+3);
        bytes(0x10004000L,src);
        put(CTRL,0);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            // 1) CMD=0: forward only, stub untouched
            put(CTRL+4,0);put(CTRL+8,0);
            invoke();
            boolean okIdle=get(REC+20)==0 && regsOk();
            out.println("CMD=0: stub-called="+(get(REC+20)!=0)+" regs ok="+regsOk()+" counter="+get(CTRL));
            // 2) CMD=2: direct call with recorded arguments (target from +0x20)
            put(CTRL+4,0);put(CTRL+8,2);
            put(CTRL+0xc,0xAAAA0001L);put(CTRL+0x10,0xBBBB0002L);put(CTRL+0x14,0x12340L);
            put(CTRL+0x18,0x40000000L);put(CTRL+0x1c,2);put(CTRL+0x20,HELPER);
            invoke();
            boolean okCall=get(REC)==0xAAAA0001L&&get(REC+4)==0xBBBB0002L&&get(REC+8)==0x12340L
                &&get(REC+12)==0x40000000L&&get(REC+16)==2&&get(REC+20)==0xcafe0001L&&get(CTRL+4)==3&&regsOk();
            out.println("CMD=2 call: args=["+Long.toHexString(get(REC))+","+Long.toHexString(get(REC+4))+","+Long.toHexString(get(REC+8))+","+Long.toHexString(get(REC+12))+",stack="+get(REC+16)+"] DONE="+get(CTRL+4)+" regs ok="+regsOk());
            // 2b) CMD=2 with a different target (park-utility address)
            put(REC+20,0);put(CTRL+4,0);put(CTRL+8,2);
            put(CTRL+0xc,0);put(CTRL+0x10,0);put(CTRL+0x14,0);put(CTRL+0x18,0);put(CTRL+0x1c,0);put(CTRL+0x20,0x4015bf04L);
            invoke();
            boolean okCall2=get(REC+20)==0xcafe0001L&&get(CTRL+4)==3&&regsOk();
            out.println("CMD=2 target=0x4015bf04: stub-called="+(get(REC+20)!=0)+" DONE="+get(CTRL+4)+" regs ok="+regsOk());
            // 3) DONE!=0 re-invoke: stub must not be called again
            put(REC+20,0);
            invoke();
            boolean okBusy=get(REC+20)==0;
            out.println("DONE!=0 re-invoke: stub untouched="+okBusy+" counter="+get(CTRL));
            // 4) CMD=1 copy regression
            put(CTRL+4,0);put(CTRL+8,1);put(CTRL+0xc,0x10004000L);put(CTRL+0x10,0x41122000L);put(CTRL+0x14,0x100);
            invoke();
            boolean okCopy=get(CTRL+4)==2 && Arrays.equals(src,bytes(0x41122000L,0x100)) && regsOk();
            out.println("CMD=1 copy: DONE="+get(CTRL+4)+" staging match="+Arrays.equals(src,bytes(0x41122000L,0x100))+" regs ok="+regsOk());
            out.println("counter total="+get(CTRL));
            boolean pass=okIdle&&okCall&&okCall2&&okBusy&&okCopy&&get(CTRL)==5;
            out.println(pass?"TRAMPOLINE5 PASS":"TRAMPOLINE5 FAIL");
            out.flush();
            if(!pass)throw new Exception("v4 semantics failed");
        }
    }
}
