// Offline composition only: factory RAM staging, supplied flag-refresh slice,
// supplied UI event. No live transport, storage, camera or full UI transition.
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

public class ReproduceTemporaryUsbSetting extends GhidraScript {
    static final long BASE=0x40000000L,COLD=0x40e4df02L,FLAG=0x40e6696dL;
    static final long SP=0x42010000L,REQUEST=0x42020000L,END=0x43000000L;
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        PcodeThread<byte[]> thread;
        Arm() {
            bytes(BASE,image);bytes(COLD,new byte[1024]);
            bytes(0x40e65950L,new byte[0x1100]);bytes(0x413e32d8L,new byte[0x80]);
            bytes(SP-0x1000,new byte[0x2000]);bytes(REQUEST,new byte[0x40]);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","ZR","NG","OV"})reg(n,0);
        }
        void bytes(long a,byte[] b) {machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n) {return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v) {bytes(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n) {return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v) {
            var r=currentProgram.getRegister(n);
            thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));
        }
        long reg(String n) {
            var r=currentProgram.getRegister(n);
            return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);
        }
        void pc(long a) {thread.overrideCounter(toAddr(a));}
        void start(long entry,long arg) {reg("sp",SP);reg("lr",END);reg("r0",arg);pc(entry);}
        void returned() {long lr=reg("lr");reg("r0",0);pc(lr);}
        void stage(byte[] block) throws Exception {
            if(block.length!=16)throw new Exception("Invalid synthetic block");
            put(REQUEST,4,32);put(REQUEST+4,2,1);put(REQUEST+6,2,0x1027);
            put(REQUEST+8,4,1);put(REQUEST+12,4,0x10060);
            bytes(REQUEST+16,block);start(0x405ca67cL,REQUEST);
            boolean response=false;
            for(int n=0;n<1200;n++) {
                monitor.checkCancelled();long p=thread.getCounter().getOffset();
                if(p==END) {if(!response)throw new Exception("No response");return;}
                if(p==0x40004064L) {returned();continue;}
                if(p==0x4000cdf0L) {
                    long dst=reg("r0"),size=reg("r2");
                    if(size!=20 || dst<SP-0x100 || dst+size>SP)throw new Exception("Unexpected zero");
                    bytes(dst,new byte[(int)size]);returned();continue;
                }
                if(p==0x40008b8cL) {
                    if(reg("r0")!=COLD+0x60 || reg("r1")!=0x413e3310L || reg("r2")!=16)
                        throw new Exception("Unexpected staging copy");
                    bytes(reg("r0"),bytes(reg("r1"),16));returned();continue;
                }
                if(p==0x405e6ac0L) {
                    if(reg("r0")!=0x2001 || reg("r1")!=0)throw new Exception("Bad response");
                    response=true;returned();continue;
                }
                if(p<0x405ca67cL || p>=0x405cad84L)throw new Exception("Unexpected PTP callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("PTP instruction limit");
        }
        void refresh() throws Exception {
            start(0x404d53b8L,0);
            for(int n=0;n<100;n++) {
                long p=thread.getCounter().getOffset();
                if(p==0x404d53d0L)return;
                if(!((p>=0x404d53b8L && p<0x404d53d0L) || (p>=0x40125284L && p<0x401252b0L)))
                    throw new Exception("Unexpected refresh callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("Refresh instruction limit");
        }
        boolean loaderBoundary() throws Exception {
            put(0x40e66958L,2,0x8000);put(0x40e6696bL,1,1);
            start(0x404db31cL,0xf0000044L);
            for(int n=0;n<600;n++) {
                long p=thread.getCounter().getOffset();
                if(p==END)return false;
                if(p==0x4038d6d0L) {
                    if(reg("r0")!=4)throw new Exception("Wrong mode");return true;
                }
                if(p==0x40004064L || p==0x405cbb84L) {returned();continue;}
                if(p==0x40123bf8L) {long lr=reg("lr");reg("r0",1);pc(lr);continue;}
                if(!((p>=0x404db31cL && p<0x404db3dcL) || (p>=0x404d665cL && p<0x404d6f90L)))
                    throw new Exception("Unexpected UI callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("UI instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong firmware");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Offline synthetic RAM composition; refresh slice and event delivery explicitly supplied.");
            Arm a=new Arm();byte[] original=new byte[1024];
            for(int i=0;i<original.length;i++)original[i]=(byte)(i*37+11);
            original[0x6a]=0x18;original[0x6b]=1;a.bytes(COLD,original);a.put(FLAG,1,1);
            byte[] block=Arrays.copyOfRange(original,0x60,0x70);block[10]=0x14;
            byte[] expected=original.clone();expected[0x6a]=0x14;
            a.stage(block);
            if(!Arrays.equals(expected,a.bytes(COLD,1024)) || a.get(FLAG,1)!=1)throw new Exception("Unexpected staging effect");
            if(a.loaderBoundary())throw new Exception("Staging alone changed active gate");
            out.println("PASS: RAM staging changes only synthetic cold-data byte +6a; cached USB flag stays 1; supplied event returns without loader.");
            a.refresh();if(a.get(FLAG,1)!=0 || !a.loaderBoundary())throw new Exception("Refresh did not select loader branch");
            out.println("PASS: supplied original refresh slice changes cached flag to 0; supplied USB event reaches mode-4 boundary.");
            a.stage(Arrays.copyOfRange(original,0x60,0x70));a.refresh();
            if(!Arrays.equals(original,a.bytes(COLD,1024)) || a.get(FLAG,1)!=1 || a.loaderBoundary())throw new Exception("Restore failed");
            out.println("PASS: restoring synthetic RAM block and refreshing restores original cold-data bytes and ordinary event result.");
            out.println("No file read/write, RTC write, mode setter, USB lifecycle or calibration code executed.");
            out.println("Stubs: log, memcpy/memset, PTP response, cable getter and descriptor setup.");
            out.println("Does not prove live registration, freshness, safe refresh reachability, buffer lifetime, later saves, or recovery.");
        }
    }
}
