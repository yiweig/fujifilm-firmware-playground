// Offline original-ARM controls: mode selection versus connection requests.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceMassStorageExit extends GhidraScript {
    static final long UI=0x40e65950L, END=0x43000000L;
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        List<String> calls=new ArrayList<>();int mode=2, cable, connections=0, configs=0;
        Arm(int cached,int flag,int cable,int gpio) {
            this.cable=cable;mem(0x40000000L,image);mem(UI,new byte[0x1100]);
            put(UI+0x1008,2,0x7000);put(UI+0x101b,1,cached);put(UI+0x101d,1,flag);
            put(0x1000904cL,1,gpio);put(0x413d3c90L,2,0x7000);
            mem(0x4200e000L,new byte[0x3000]);t.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        void run(long entry,long event) throws Exception {
            reg("r0",event);reg("sp",0x42010000L);reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int step=0;step<2000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END)return;
                if(pc==0x4038d6d0L){mode=(int)reg("r0");calls.add("mode("+mode+")");ret(0);continue;}
                if(pc==0x4038d764L){
                    long connect=reg("r0");calls.add("connect("+connect+",mode="+mode+")");
                    if(connect==1){if(mode!=2)throw new Exception("Connection outside mass-storage mode");connections++;}
                    else if(connect!=0)throw new Exception("Unexpected connection argument");ret(0);continue;
                }
                if(pc==0x40123bf8L){ret(cable);continue;}
                if(pc==0x4038ddb0L){if(reg("r0")!=0x300)throw new Exception("Unexpected USB configuration");configs++;ret(0);continue;}
                if(pc==0x4006f330L){if(reg("r0")!=0xf2000030L)throw new Exception("Unexpected event");calls.add("event(f2000030)");ret(0);continue;}
                if(pc==0x40004064L || pc==0x4000cd00L || pc==0x4006a074L){ret(0);continue;}
                if(pc<0x405c88c4L || pc>=0x405c8a74L)throw new Exception("Unexpected execution "+Long.toHexString(pc));
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
            out.println("Offline original ARM: exit helper 405c88c4, then explicitly supplied callback 405c8918/event fb000001. Not a demonstrated UI transition.");
            for(int cached=0;cached<2;cached++)for(int flag=0;flag<2;flag++)for(int cable=0;cable<2;cable++)for(int gpio:new int[]{0,32}) {
                Arm a=new Arm(cached,flag,cable,gpio);a.run(0x405c88c4L,0);
                List<String> expected=new ArrayList<>();if(cached!=0)expected.add("connect(0,mode=2)");expected.add("mode(4)");
                if(!a.calls.equals(expected) || a.connections!=0 || a.val(UI+0x101b,1)!=0 || a.val(UI+0x101c,1)!=4
                    || a.val(UI+0x101d,1)!=flag)throw new Exception("Exit control");
                a.run(0x405c8918L,0xfb000001L);
                if(cable!=0){expected.add("mode(2)");expected.add("connect(1,mode=2)");expected.add("connect(1,mode=2)");}
                else expected.add("event(f2000030)");
                if(!a.calls.equals(expected) || a.connections!=2*cable || a.configs!=(cable==0?0:(gpio==0?4:3))
                    || a.val(UI+0x1008,2)!=0x7000 || a.val(UI+0x101b,1)!=cable
                    || a.val(UI+0x101c,1)!=(cable==0?4:5) || a.val(UI+0x101d,1)!=flag)throw new Exception("Create control");
                out.printf("PASS cached=%d flag=%d cable=%d gpio-bit5=%d: %s%n",cached,flag,cable,gpio==0?0:1,a.calls);
            }
            out.println("16 controls passed. Mode 4 is selected by exit, but no connect(1) request occurs until mode 2 is selected by the supplied mass-create callback.");
            out.println("Modeled: mode setter, connect/disconnect, cable getter, USB configuration, environment helper 4000cd00, logging, delays and event posting. GPIO byte supplied; no MMIO executes.");
            out.println("No full startup, event delivery, filesystem behavior, USB transfer, physical disconnect, temporary loader entry or recovery proof.");
        }
    }
}
