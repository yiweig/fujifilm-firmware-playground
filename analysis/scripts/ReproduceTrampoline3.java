// Offline semantics check for trampoline v3 (parameterized staging copier).
// Args: [0]=output, [1]=trampoline .bin. Control block at 0x41100100:
// +0 counter, +4 DONE, +8 CMD (nonzero = run), +0xc SRC, +0x10 DST, +0x14 LEN.
// Verifies boot-info (0x10004000) and gate (0x10001000) staging fixtures,
// DONE transitions, idle behavior, register/stack preservation, handler tail.
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

public class ReproduceTrampoline3 extends GhidraScript {
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
    void setCmd(long src,long dst,long len){
        put(CTRL+4,0); put(CTRL+8,1); put(CTRL+0xc,src); put(CTRL+0x10,dst); put(CTRL+0x14,len);
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
        byte[] bootinfo=new byte[0x100]; for(int i=0;i<bootinfo.length;i++)bootinfo[i]=(byte)(i*13+5);
        bytes(0x10004000L,bootinfo);
        byte[] gate=new byte[0x304]; for(int i=0;i<gate.length;i++)gate[i]=(byte)(i*7+11);
        bytes(0x10001000L,gate);
        put(CTRL,0);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            // CMD run 1: boot-info staging
            setCmd(0x10004000L,0x41122000L,0x100);
            invoke();
            boolean ok1=get(CTRL+4)==2 && Arrays.equals(bootinfo,bytes(0x41122000L,0x100))
                && reg("r0")==0x11111111L && reg("r3")==0x44444444L && reg("sp")==SP;
            out.println("run1 bootinfo: DONE="+get(CTRL+4)+" staging match="+Arrays.equals(bootinfo,bytes(0x41122000L,0x100))+" regs/stack ok="+(reg("r0")==0x11111111L&&reg("sp")==SP));
            // Re-invoke with DONE=2: must not recopy
            byte[] st=bytes(0x41122000L,0x100); st[0]^=0xff; bytes(0x41122000L,st);
            invoke();
            boolean okIdle=bytes(0x41122000L,1)[0]==st[0];
            out.println("DONE!=0 re-invoke: staging untouched="+okIdle+" counter="+get(CTRL));
            // CMD run 2: gate staging (longer, unaligned-end length 0x304)
            setCmd(0x10001000L,0x41123000L,0x304);
            invoke();
            boolean ok2=get(CTRL+4)==2 && Arrays.equals(gate,bytes(0x41123000L,0x304));
            out.println("run2 gate: DONE="+get(CTRL+4)+" staging match="+Arrays.equals(gate,bytes(0x41123000L,0x304)));
            // CMD=0: no copy even with params set
            put(CTRL+4,0); put(CTRL+8,0);
            byte[] before=bytes(0x41122000L,0x100);
            invoke();
            boolean okCmd0=Arrays.equals(before,bytes(0x41122000L,0x100)) && get(CTRL+4)==0;
            out.println("CMD=0: staging untouched="+okCmd0);
            out.println("counter total="+get(CTRL));
            boolean pass=ok1&&okIdle&&ok2&&okCmd0&&get(CTRL)==4;
            out.println(pass?"TRAMPOLINE3 PASS":"TRAMPOLINE3 FAIL");
            out.flush();
            if(!pass)throw new Exception("v3 semantics failed");
        }
    }
}
