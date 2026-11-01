// Offline selected application callbacks; no USB or complete PTP transaction.
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

public class ReproduceRetailPtpBoundaries extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, INPUT=0x42020000L;
    static final int LENGTH=0x01400000;
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        PcodeThread<byte[]> thread;
        Arm(int flag) {
            bytes(BASE,new byte[LENGTH]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);bytes(INPUT,new byte[64]);
            put(0x40e66958L,0x8000,2);put(0x40e6696dL,flag,1);
            byte[] cold=new byte[1024];for(int i=0;i<cold.length;i++)cold[i]=(byte)(i*37+11);
            cold[0x6a]=0x14;cold[0x6b]=1;bytes(0x40e4df02L,cold);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",SP);reg("lr",END);
        }
        void bytes(long a,byte[] b) {machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n) {return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n) {bytes(a,Utils.longToBytes(v,n,false));}
        void reg(String n,long v) {var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n) {var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void execute(long entry,long boundary,boolean notification) throws Exception {
            thread.overrideCounter(toAddr(entry));
            for(int i=0;i<4000;i++) {
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==boundary)return;
                if(pc==0x40004064L && notification) {reg("r0",0);thread.overrideCounter(toAddr(reg("lr")));continue;}
                boolean admitted=notification?
                    ((pc>=0x40400d50L && pc<0x40402574L) || (pc>=0x404db31cL && pc<0x404db3dcL) || (pc>=0x404d665cL && pc<0x404d6f90L)):
                    (pc>=0x405c9efcL && pc<0x405ca0a0L);
                if(!admitted)throw new Exception("Unexpected execution at "+Long.toHexString(pc));
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Property tests enter callback 405c9efc directly; supplied input, not full PTP acceptance/transaction.");
            for(int property:new int[]{31,0x101d,0xd608,0x114,0x500c,0x5011}) {
                Arm a=new Arm(1);a.put(INPUT+4,2,2);
                a.reg("r0",property);a.reg("r1",INPUT);
                byte[] expected=a.bytes(BASE,LENGTH);
                long boundary=property==0x500c?0x405ca018L:property==0x5011?0x40008b8cL:END;
                a.execute(0x405c9efcL,boundary,false);
                if(!Arrays.equals(expected,a.bytes(BASE,LENGTH)))throw new Exception("Property prefix changed global RAM");
                out.printf("PASS: property=%04x boundary=%08x; all image/globals unchanged; no external call executed.%n",property,boundary);
            }
            out.println("Notification tests supply event 6251001a via application dispatcher in state 8000; only print modeled as return.");
            out.println("Fixtures: resume latch 40e7ae4a=1; runtime word 40e065b0=0; no physical device state inferred.");
            out.println("Parameters f2000023/f2000084 are synthetic internal stress controls, not encodable 16-bit PTP opcodes.");
            for(long parameter:new long[]{31,0xf2000023L,0xf2000084L,0x1010})for(int flag=0;flag<2;flag++) {
                Arm a=new Arm(flag);a.put(0x40e7ae4aL,1,1);a.put(0x40e065b0L,0,4);
                a.reg("r0",0x6251001aL);a.reg("r1",parameter);
                byte[] expected=a.bytes(BASE,LENGTH);
                byte[] v=Utils.longToBytes(parameter,4,false);
                System.arraycopy(v,0,expected,(int)(0x413cb55cL-BASE),4);
                a.execute(0x40400d50L,END,true);
                if(!Arrays.equals(expected,a.bytes(BASE,LENGTH)))throw new Exception("Unexpected notification global changes");
                out.printf("PASS: notification parameter=%08x flag=%d returned; only last-operation word 413cb55c changes; cache and cold RAM preserved.%n",parameter,flag);
            }
            out.println("Known-property controls stop before white-balance mutation or datetime memcpy; neither is executed to completion.");
            out.println("No live requests, shell, USB mode setter, file or RTC calls; reset opcode support is a separate static finding.");
        }
    }
}
