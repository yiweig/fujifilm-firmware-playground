// Offline original ARM: initial RAM clear, primitive-pointer translation,
// and checksum acceptance. Stops before hardware; no entire boot emulation.
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

public class InspectLoaderStorageBoundaries extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    byte[] image;
    class Arm {
        PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        PcodeThread<byte[]> t=m.newThread();
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        int logs=0;
        Arm(){
            bytes(BASE,image);bytes(SP-0x100,new byte[0x200]);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String name:new String[]{"CY","OV","ZR","NG"})reg(name,0);
            reg("sp",SP);reg("lr",END);
        }
        void bytes(long a,byte[] b){m.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n){return m.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void run(long entry,long stop,long low,long high,boolean logStub) throws Exception {
            t.overrideCounter(toAddr(entry));
            for(int n=0;n<200000;n++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==stop)return;
                if(pc==0x40004064L&&logStub){logs++;reg("r0",0);t.overrideCounter(toAddr(reg("lr")));continue;}
                if(!((pc>=low&&pc<high)||(logStub&&pc>=0x40057dccL&&pc<0x40057dd8L)))
                    throw new Exception("Unexpected PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Image SHA-256: "+hash);
            out.println("Offline original slices only; fixtures and stop boundaries below. No storage primitive, actual MMIO, DMA, flash, low-address provider or complete boot executes.");
            Arm clear=new Arm();long start=0x40e37c00L,end=0x40e68880L;
            byte[] guard=new byte[(int)(end-start)+32];Arrays.fill(guard,(byte)0xa5);clear.bytes(start-16,guard);
            clear.put(0x4136b664L,0xdecafbadL,4);
            clear.run(0x40000684L,0x40000724L,0x40000684L,0x40000724L,false);
            if(!Arrays.equals(clear.bytes(start,(int)(end-start)),new byte[(int)(end-start)]))throw new Exception("Initial clear interval");
            byte[] sentinels=new byte[16];Arrays.fill(sentinels,(byte)0xa5);
            if(!Arrays.equals(clear.bytes(start-16,16),sentinels)||!Arrays.equals(clear.bytes(end,16),sentinels)||clear.val(0x4136b664L,4)!=0xdecafbadL)
                throw new Exception("Clear escaped interval");
            out.printf("PASS initial CPU clear: [%08x,%08x), length=%x, guards preserved; starts after prior SRAM code copy; stops before storage-register action at 40000724.%n",start,end,end-start);
            long[][] read={{0x3cd0,0x40028dc4L},{0x4aac,0x400299d0L},{0x4654,0x40029578L}};
            long[][] probe={{0x37e8,0x400294f0L},{0x3a30,0x40029468L},{0x421c,0x40029b9cL}};
            for(long[] a:read)for(long[] b:probe){
                Arm x=new Arm();x.put(0x40000e9cL,a[0],4);x.put(0x40000ea0L,b[0],4);
                x.run(0x40029314L,0x400293c8L,0x40029314L,0x400293c8L,false);
                if(x.val(0x40000e9cL,4)!=a[1]||x.val(0x40000ea0L,4)!=b[1])throw new Exception("Primitive pointer mapping");
                out.printf("PASS pointer mapping: %08x->%08x, %08x->%08x; stop before MMIO branch 400293c8.%n",a[0],a[1],b[0],b[1]);
            }
            // input, current checksum word, total word, boot-status bit1, expected return
            long[][] cases={{0x1234,0xabcd1234L,0x7777,0,0},{0x1234,0x1235,0x7777,0,1},
                {0x88888888L,0x9999,0x7777,0,0},{0xffffffffL,0x9999,0x4321,0,0},
                {0xffffffffL,0x9999,0x7654,2,0},{0xffffffffL,0x9999,0x4321,2,1}};
            for(long[] c:cases){
                Arm x=new Arm();x.put(0x40000c00L,c[1],4);x.put(0x40000c40L,c[2],4);
                x.put(0x40000cc4L,0x4321,2);x.put(0x40000cc8L,0x7654,2);x.put(0x10004054L,c[3],2);
                x.reg("r0",c[0]);x.run(0x4002a174L,END,0x4002a174L,0x4002a1f0L,true);
                if(x.reg("r0")!=c[4]||x.val(0x40000c40L,4)!=c[2]||x.val(0x40000c00L,4)!=(c[0]==0xffffffffL?c[1]:0))
                    throw new Exception("Checksum result/state");
                if(x.logs!=(int)c[4])throw new Exception("Unexpected log branch");
                out.printf("PASS checksum: input=%08x current=%08x total=%08x supplied-boot-status=%x -> return=%d; only log modeled; MMIO read served by fixture.%n",c[0],c[1],c[2],c[3],c[4]);
            }
            out.println("16 controls passed. Sentinel acceptance is local callback behavior, not evidence about package authentication, a safe patch, a boot selector or independent recovery.");
        }
    }
}
