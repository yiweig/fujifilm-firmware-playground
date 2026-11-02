// Offline profile-log enqueue controls, no UART, USB or actual clock access.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceProfileLog extends GhidraScript {
    static final long BUFFER=0x42030000L,TEXT=0x42020000L,ENTRY=0x40d5dd3cL,END=0x43000000L;
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();int copies=0;
        Arm(int kind) {
            mem(0x40000000L,image);mem(0x4200e000L,new byte[0x3000]);mem(ENTRY,new byte[20]);
            byte[] blank=new byte[256];Arrays.fill(blank,(byte)0xaa);mem(BUFFER,blank);mem(TEXT,"READ-END\0".getBytes());
            if(kind!=0){put(ENTRY,4,256);put(ENTRY+5,1,kind==2?0:1);put(ENTRY+8,4,BUFFER);}
            if(kind==2)put(ENTRY+12,4,248);if(kind==3)put(ENTRY+6,1,2);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("r0",6);reg("r1",TEXT);reg("r2",8);reg("sp",0x42010000L);reg("lr",END);
            t.overrideCounter(toAddr(0x4000459cL));
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(mem(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        long run() throws Exception {
            for(int step=0;step<2000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END)return reg("r0");
                if(pc==0x4000c30cL){ret(0);continue;}
                if(pc==0x4000cc48L){ret(12345);continue;}
                if(pc==0x40008b8cL){
                    if(reg("r0")!=BUFFER+8 || reg("r1")!=TEXT || reg("r2")!=8)throw new Exception("Copy args");
                    mem(BUFFER+8,mem(TEXT,8));copies++;ret(BUFFER+8);continue;
                }
                if(pc<0x4000459cL || pc>=0x400047e0L)throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main-image SHA-256: "+hash);
            out.println("Offline original channel writer 4000459c; supplied channel-6 state, text and buffer. Modeled lock, timestamp and copy only.");
            for(int kind=0;kind<4;kind++) {
                Arm a=new Arm(kind);byte[] before=a.mem(BUFFER,256);long result=a.run();
                if(kind==1) {
                    byte[] expected=before.clone();System.arraycopy(Utils.longToBytes(12345,4,false),0,expected,0,4);
                    System.arraycopy(Utils.longToBytes(8,4,false),0,expected,4,4);
                    System.arraycopy("READ-END".getBytes(),0,expected,8,8);expected[16]=0;
                    if(result!=0 || a.copies!=1 || a.val(ENTRY+12,4)!=20 || !Arrays.equals(expected,a.mem(BUFFER,256)))
                        throw new Exception("Enqueue mismatch");
                } else if(result!=1 || a.copies!=0 || !Arrays.equals(before,a.mem(BUFFER,256)))throw new Exception("Drop mismatch");
                if(kind==2 && a.val(ENTRY+16,4)!=248)throw new Exception("Full indicator");
                out.printf("PASS case=%d result=%d payload copies=%d.%n",kind,result,a.copies);
            }
            out.println("Cases: 0=unconfigured, 1=configured ring with room, 2=full non-overwrite buffer, 3=busy channel.");
            out.println("Not actual camera log state, a clock observation, retention guarantee, log-drain test or USB transport proof.");
        }
    }
}
