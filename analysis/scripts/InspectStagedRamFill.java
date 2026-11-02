// Original ARM interval calculation and fill descriptor construction only.
// Starts at an interior slice with declared register/frame fixtures.
// No controller/MMIO, storage load, actual RAM fill or live camera execution.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.AddressSpace;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectStagedRamFill extends GhidraScript {
    final long BASE=0x40000000L, TABLE=0x40000b00L, SP=0x42010000L;
    byte[] image;
    long word(long a){int p=(int)(a-BASE);return (image[p]&255L)|((image[p+1]&255L)<<8)|((image[p+2]&255L)<<16)|((image[p+3]&255L)<<24);}
    long align(long v){return (v+63)&~63L;}
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        PcodeThread<byte[]> t=machine.newThread();
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        boolean mapped=false;
        Arm(int n,long previous) {
            bytes(BASE,image);bytes(SP-0x200,new byte[0x400]);
            bytes(0x4136b664L,Utils.longToBytes(0xdecafbadL,4,false));
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String name:new String[]{"CY","OV","ZR","NG"})reg(name,0);
            reg("sp",SP);reg("r5",TABLE+n*16);reg("r8",previous);
            t.overrideCounter(toAddr(0x4000f168L));
        }
        void bytes(long a,byte[] b){machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n){return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        long val(long a){return Utils.bytesToLong(bytes(a,4),4,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long value){reg("r0",value);t.overrideCounter(toAddr(reg("lr")));}
        void run(long start,long end,boolean expectFill) throws Exception {
            for(int step=0;step<300;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x4000f1c8L) {
                    if(expectFill)throw new Exception("Expected fill descriptor");
                    if(mapped)throw new Exception("Unexpected mapping");return;
                }
                if(pc==0x4002a7bcL) {
                    if(!expectFill||reg("r0")!=BASE+start||reg("r1")!=end-start)throw new Exception("Mapping arguments");
                    mapped=true;ret(start);continue; // Explicit modeled successful translation.
                }
                if(pc==0x4000cdf0L) {
                    if(reg("r0")!=SP-0x24||reg("r1")!=0||reg("r2")!=20)throw new Exception("Stack memset boundary");
                    bytes(reg("r0"),new byte[20]);ret(SP-0x24);continue;
                }
                if(pc==0x4000e870L) {
                    if(!expectFill||!mapped)throw new Exception("Unexpected DMA setup");
                    long d=reg("r0");
                    long[] got={val(d),val(d+4),val(d+8),val(d+12),val(d+16)};
                    if(!Arrays.equals(got,new long[]{start,0xc0000000L,end-start,0,0xffffffffL})||reg("r5")!=0)
                        throw new Exception("Fill descriptor "+Arrays.toString(got));
                    if(val(0x4136b664L)!=0xdecafbadL)throw new Exception("Callback sentinel changed");
                    return; // Stop before hardware channel setup.
                }
                if(!((pc>=0x4000f168L&&pc<0x4000f1c8L)||(pc>=0x4002ab54L&&pc<0x4002abd0L)))
                    throw new Exception("Unexpected PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!digest.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Image SHA-256: "+digest);
            out.println("Original slice f168..f1c8 and fill wrapper 2ab54..2abd0; supplied loop/frame state. Successful address translation and stack-only memset modeled. Stops before 4000e870; no DMA, zero fill, storage or live camera execution.");
            long previous=align(word(TABLE+44));
            for(int n=1;n<6;n++) {
                long end=align(word(TABLE+n*16+44));Arm a=new Arm(n,previous);a.run(previous,end,true);
                out.printf("PASS ordinal=%d: CPU interval [%08x,%08x), length=%x; physical destination=%08x; fill argument=0; descriptor source=c0000000; callback sentinel unchanged.%n",n,BASE+previous,BASE+end,end-previous,previous);
                previous=end;
            }
            Arm noFill=new Arm(3,align(word(TABLE+3*16+44)));
            noFill.run(0,0,false);out.println("PASS equal-bound control: no mapping or fill descriptor requested.");
        }
    }
}
