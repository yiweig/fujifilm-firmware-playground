// Original checksum leaf, offline only; no file writer or camera access.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceColdChecksum extends GhidraScript {
    public void run() throws Exception {
        byte[] image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Execute checksum leaf 4015bd58 with length 1022; no stubs, files, USB or writer calls.");
            for(int vector=0;vector<4;vector++) {
                byte[] data=new byte[1024];
                if(vector==1)Arrays.fill(data,0,1022,(byte)0xff);
                if(vector==2)for(int i=0;i<1022;i++)data[i]=(byte)i;
                if(vector==3)data[0]=(byte)0x80;
                data[1022]=(byte)0xa5;data[1023]=(byte)0x5a; // must be excluded
                int expected=new int[]{0,2,3,128}[vector];
                var machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
                var ram=currentProgram.getLanguage().getDefaultSpace();
                machine.getSharedState().setVar(ram,0x40000000L,image.length,true,image);
                machine.getSharedState().setVar(ram,0x42020000L,1024,true,data);
                var thread=machine.newThread();thread.overrideContextWithDefault();
                for(int i=0;i<13;i++)set(thread,"r"+i,0);
                for(String n:new String[]{"CY","OV","ZR","NG"})set(thread,n,0);
                set(thread,"r0",0x42020000L);set(thread,"r1",1022);set(thread,"lr",0x43000000L);
                thread.overrideCounter(toAddr(0x4015bd58L));boolean returned=false;
                for(int steps=0;steps<10000;steps++) {
                    monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                    if(pc==0x43000000L) {returned=true;break;}
                    if(pc<0x4015bd58L || pc>=0x4015bd80L)throw new Exception("Unexpected execution");
                    thread.stepInstruction();
                }
                long actual=Utils.bytesToLong(thread.getState().getVar(currentProgram.getRegister("r0"),Reason.INSPECT),4,false);
                if(!returned || actual!=expected || !Arrays.equals(data,machine.getSharedState().getVar(ram,0x42020000L,1024,true,Reason.INSPECT)))
                    throw new Exception("Checksum or memory mismatch");
                out.printf("PASS: vector=%d sum=%d; excluded tail a5/5a; input unchanged.%n",vector,actual);
            }
            out.println("A matching byte sum is weak integrity evidence; it does not establish device identity or freshness.");
        }
    }
    void set(PcodeThread<byte[]> thread,String name,long value) {
        var r=currentProgram.getRegister(name);
        thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
    }
}
