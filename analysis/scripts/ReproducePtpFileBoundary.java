// Offline normal-PTP storage, name-filter and object-handle controls.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproducePtpFileBoundary extends GhidraScript {
    static final long END=0x43000000L, NAME=0x42020000L, PARENT=0x42021000L;
    static final long ROOT=0x42050000L, QUEUE=0x42060000L, OUT=0x42040000L;
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();long response=-1;int allocations=0,frees=0;
        Arm() {
            mem(0x40000000L,image);mem(0x4200e000L,new byte[0x3000]);
            mem(NAME,new byte[256]);mem(PARENT,new byte[256]);mem(ROOT,new byte[0x120]);mem(OUT,new byte[0x1000]);
            mem(0x4136b650L,new byte[0x50]);mem(0x4136bd14L,new byte[0x40]);mem(0x4136c79cL,new byte[0x80]);
            put(0x4136bd14L,4,OUT);put(0x4136bd18L,4,0x1000);put(0x4136bd1cL,4,NAME);put(0x4136bd20L,4,4);
            put(0x4136bd28L,4,ROOT);put(ROOT+4,4,77);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b) {emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n) {return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v) {mem(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n) {return Utils.bytesToLong(mem(a,n),n,false);}
        void reg(String n,long v) {var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n) {var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v) {reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        long run(long entry,long r0,long r1,long r2) throws Exception {
            reg("r0",r0);reg("r1",r1);reg("r2",r2);reg("sp",0x42010000L);reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int step=0;step<10000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset(),a=reg("r0"),b=reg("r1"),c=reg("r2");
                if(pc==END || pc==0x402eab4cL)return pc;
                if(pc==0x40008cacL) {int n=0;while(n<256 && val(a+n,1)!=0)n++;if(n==256)throw new Exception("Unterminated");ret(n);continue;}
                if(pc==0x401b8958L) {if(a!=1 && a!=2 && a!=6)throw new Exception("Unexpected media slot");ret(0);continue;}
                if(pc==0x40334f34L) {
                    if(b!=0 || c!=0 || reg("r3")!=0 || val(reg("sp"),4)!=0)throw new Exception("Unexpected response args");
                    response=a;ret(0);continue;
                }
                if(pc==0x40020d28L) {if(a!=0x200c)throw new Exception("Allocation size");mem(QUEUE,new byte[0x200c]);allocations++;ret(QUEUE);continue;}
                if(pc==0x4000cdf0L) {if(a!=QUEUE || b!=0 || c!=0x200c)throw new Exception("Zero args");mem(a,new byte[(int)c]);ret(a);continue;}
                if(pc==0x40020eccL) {if(a!=QUEUE)throw new Exception("Free args");frees++;ret(0);continue;}
                boolean allowed=(pc>=0x405c9c80L && pc<0x405c9dfcL)
                    || (pc>=0x40534b30L && pc<0x40534bd8L)
                    || (pc>=0x40021768L && pc<0x40021784L) || (pc>=0x40059228L && pc<0x40059290L)
                    || (pc>=0x402e8f30L && pc<0x402e9070L) || (pc>=0x401b892cL && pc<0x401b8958L)
                    || (pc>=0x403350f0L && pc<0x40335120L) || (pc>=0x401cafb8L && pc<0x401cafe8L)
                    || (pc>=0x402eae10L && pc<0x402eaf80L) || (pc>=0x402e84a8L && pc<0x402e853cL)
                    || (pc>=0x402e7e9cL && pc<0x402e7eccL) || (pc>=0x402e7ed4L && pc<0x402e7ef0L)
                    || (pc>=0x402e7efcL && pc<0x402e7f60L) || (pc>=0x402e7f6cL && pc<0x402e7fb4L);
                if(!allowed)throw new Exception("Unexpected PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main-image SHA-256: "+hash);out.println("Offline original normal-PTP code; no USB, camera or actual filesystem access.");
            Arm storage=new Arm();storage.put(0x4136b670L,4,1);storage.put(0x4136b674L,4,1);
            for(int slot:new int[]{0,1,2,6})storage.put(0x4138ded4L+4*slot,4,0x10001L+slot*0x10000L);
            if(storage.run(0x402e8f30L,0,0,0)!=END || storage.response!=0x2001 || storage.val(OUT,4)!=3
                || storage.val(OUT+4,4)!=0x20001 || storage.val(OUT+8,4)!=0x30001 || storage.val(OUT+12,4)!=0x70001
                || storage.val(0x4136b680L,4)!=16)throw new Exception("Storage IDs");
            out.println("PASS StorageIDs: original enumerator supplies [0,1,2,6]; output includes 1/2/6 and excludes slot 0 (A:) despite its supplied ID.");
            String[][] cases={{"1","RO_RES","A:","1"},{"1","USER_RES","B:","1"},
                {"1","DCIM","D:","0"},{"1","DCIM","A:","0"},{"1","FPATR","D:","0"},
                {"1","100_FUJI","DCIM","0"},{"0","COLD.BIN","RO_RES","1"},
                {"0","COLDDATA.BIN","RO_RES","1"},{"0","COLD.BIN","DCIM","1"},
                {"0","DSCF0001.JPG","100_FUJI","0"},{"0","DSCF0001.AVI","100_FUJI","1"},
                {"0","NOTDCF.BIN","100_FUJI","1"}};
            for(String[] x:cases) {
                Arm a=new Arm();a.mem(NAME,(x[1]+"\0").getBytes());a.mem(PARENT,(x[2]+"\0").getBytes());
                if(a.run(0x405c9c80L,Integer.parseInt(x[0]),NAME,PARENT)!=END || a.reg("r0")!=Integer.parseInt(x[3]))throw new Exception("Filter "+Arrays.toString(x));
                out.printf("PASS filter directory=%s name=%s parent=%s returned=%s (0 permits, 1 excludes).%n",x[0],x[1],x[2],x[3]);
            }
            for(long handle:new long[]{0x40e4df02L,0x444c4f43L,123,77}) {
                Arm a=new Arm();a.put(NAME,4,handle);
                long stop=a.run(0x402eae10L,PARENT,0,0);
                if(a.allocations!=1 || a.frees!=1)throw new Exception("Lookup queue lifecycle");
                if(handle==77) {
                    if(stop!=0x402eab4cL || a.response!=-1 || a.reg("r0")!=ROOT)throw new Exception("Known node");
                    out.println("PASS GetObject known synthetic ID 77: resolves database node; stopped before file preparation.");
                } else {
                    if(stop!=END || a.response!=0x2009 || a.val(0x4136b680L,4)!=0)throw new Exception("Unknown handle");
                    out.printf("PASS GetObject handle=%08x absent from synthetic database: response 2009, zero bytes, no file preparation.%n",handle);
                }
            }
            out.println("Modeled: strlen, media availability, allocation/zero/free and response setter. Filter string comparisons/name validators and lookup queue execute original instructions.");
            out.println("Not a complete scanner/transfer emulation, actual device enumeration, reachability proof, exhaustive security audit or recovery qualification.");
        }
    }
}
