// Offline save/flush failure controls; physical storage is a byte array.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceColdSave extends GhidraScript {
    static final long END=0x43000000L, BASE=0x40e63594L, ARC=0x42030000L, DATA=0x42020000L;
    byte[] image, archive;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        final byte[] disk=archive.clone(), incoming=new byte[1024];
        int outcome, opens=0, writes=0, closes=0, modeCalls=0;
        Arm(int mode,int failure) {
            outcome=failure;mem(0x40000000L,image);mem(ARC,archive);
            mem(BASE,new byte[0x300]);mem(0x40f12ba8L,new byte[16]);
            put(BASE,4,ARC+128);put(BASE+4,4,1024); // member handle 0xf0000000
            mem(BASE+0x100,"A:\\RO_RES\\COLD.BIN\0".getBytes());
            put(BASE+0x120,4,ARC);put(BASE+0x124,1,9);put(BASE+0x290,4,mode);
            for(int i=0;i<1024;i++)incoming[i]=(byte)(i*37+11);
            int sum=0;for(int i=0;i<1022;i++)sum=(sum+(incoming[i]&255))&255;
            incoming[1022]=(byte)sum;incoming[1023]=0;
            mem(DATA,incoming);mem(0x4200e000L,new byte[0x3000]);t.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b) {emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n) {return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v) {mem(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n) {return Utils.bytesToLong(mem(a,n),n,false);}
        void reg(String n,long v) {var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n) {var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v) {reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        long run(long entry,long r0,long r1,long r2,long r3) throws Exception {
            reg("r0",r0);reg("r1",r1);reg("r2",r2);reg("r3",r3);
            reg("sp",0x42010000L);reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int step=0;step<15000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset(),a=reg("r0"),b=reg("r1"),c=reg("r2");
                if(pc==END)return a;
                if(pc==0x40004064L || pc==0x4006a074L || pc==0x4015b620L) {ret(0);continue;}
                if(pc==0x400bc690L) {
                    if(a==20 && b==0) {modeCalls++;reg("r0",b);t.overrideCounter(toAddr(0x4006ab18L));continue;}
                    if(a!=10)throw new Exception("Unexpected environment selector");ret(0);continue;
                }
                if(pc==0x4015bb4cL) {
                    if(a!=0x40b000b5L || b!=a)throw new Exception("Path compare");ret(0);continue;
                }
                if(pc==0x4013f4acL) {
                    if(a!=0x40b000b5L || b!=32)throw new Exception("Logical open");ret(0xf0000000L);continue;
                }
                if(pc==0x40150e20L) {
                    if(a!=0xf0000000L || c!=0 || reg("r3")!=0 || val(reg("sp"),4)!=0)throw new Exception("Seek");
                    put(BASE+8,4,0);ret(0);continue;
                }
                if(pc==0x40080490L) {if(a!=0xf0000000L)throw new Exception("Classification");ret(0);continue;}
                if(pc==0x4013f604L) {if(a!=0xf0000000L)throw new Exception("Logical close");ret(0);continue;}
                if(pc==0x40008b8cL) {
                    if(a!=ARC+128 || b!=DATA || c!=1024)throw new Exception("Member copy");mem(a,mem(b,1024));ret(a);continue;
                }
                if(pc==0x4001019cL) {
                    if(a!=BASE+0x100 || b!=0x100 || c!=0)throw new Exception("Physical open");
                    opens++;ret(outcome==1?0:0x1234);continue;
                }
                if(pc==0x40011380L) {if(a!=0x1234)throw new Exception("Size");ret(archive.length);continue;}
                if(pc==0x40010330L) {
                    if(a!=0x1234 || b!=ARC || c!=archive.length)throw new Exception("Physical write");
                    int count=outcome==2?200:archive.length;
                    System.arraycopy(mem(ARC,count),0,disk,0,count);writes++;ret(count);continue;
                }
                if(pc==0x40010294L) {if(a!=0x1234)throw new Exception("Physical close");closes++;ret(0);continue;}
                boolean allowed=(pc>=0x40125e4cL && pc<0x40125ef8L)
                    || (pc>=0x4015bd58L && pc<0x4015bd80L)
                    || (pc>=0x4015b840L && pc<0x4015b91cL) || pc==0x4015b928L
                    || (pc>=0x401408e8L && pc<0x40140948L)
                    || (pc>=0x4006ae08L && pc<0x4006af14L)
                    || (pc>=0x4006a540L && pc<0x4006a5b8L)
                    || (pc>=0x4006aaacL && pc<0x4006ab14L)
                    || (pc>=0x4006ab18L && pc<0x4006ab44L);
                if(!allowed)throw new Exception("Unexpected PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        archive=Files.readAllBytes(Path.of(getScriptArgs()[1]));
        if(!sha(image).equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923")
            || !sha(archive).equals("b878272f4cb92ded7aa31850d3f17fd8be16304b85982fbd0b03acbfe04f4f69"))throw new Exception("Wrong artifact");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main-image SHA-256: "+sha(image));out.println("Packaged archive SHA-256: "+sha(archive));
            out.println("Offline original checksum/save wrapper, write adapter, archive write/flush and physical-write wrapper; lower I/O is modeled.");
            for(int mode=0;mode<=1;mode++)for(int failure=0;failure<3;failure++) {
                Arm a=new Arm(mode,failure);long status=a.run(0x40125e4cL,0x40b000b5L,0,1024,DATA);
                byte[] expected=a.incoming.clone();int sum=0;for(int i=0;i<1022;i++)sum=(sum+(expected[i]&255))&255;
                expected[1022]=(byte)sum;expected[1023]=0;
                byte[] expectedArc=archive.clone();System.arraycopy(expected,0,expectedArc,128,1024);
                if(status!=0 || !Arrays.equals(expected,a.mem(DATA,1024)) || !Arrays.equals(expectedArc,a.mem(ARC,archive.length))
                    || a.val(BASE+0x125,1)!=0 || a.val(BASE+0x290,4)!=0 || a.modeCalls!=1)
                    throw new Exception("Save or mode mismatch");
                int before=a.opens;
                a.run(0x4006aaacL,0,0,0,0);
                if(a.opens!=before)throw new Exception("Unexpected immediate-mode flush retry");
                byte[] expectedDisk=archive.clone();int count=failure==1?0:failure==2?200:archive.length;
                System.arraycopy(expectedArc,0,expectedDisk,0,count);
                if(!Arrays.equals(expectedDisk,a.disk) || a.val(BASE+0x125,1)!=0
                    || a.opens!=1 || a.writes!=(failure==1?0:1) || a.closes!=a.writes)throw new Exception("Persistence model mismatch");
                out.printf("PASS wrapper initial-mode=%d outcome=%d: save returned 0; forced mode 0; opens=%d writes=%d closes=%d; no dirty retry; disk matches new archive=%s.%n",
                    mode,failure,a.opens,a.writes,a.closes,Arrays.equals(expectedArc,a.disk));
            }
            for(int failure=0;failure<3;failure++) {
                Arm a=new Arm(1,failure);
                long count=a.run(0x401408e8L,0xf0000000L,DATA,1024,0);
                if(count!=1024 || a.opens!=0 || a.val(BASE+0x125,1)!=1 || !Arrays.equals(archive,a.disk))
                    throw new Exception("Deferred lower write mismatch");
                byte[] expectedArc=archive.clone();System.arraycopy(a.incoming,0,expectedArc,128,1024);
                a.run(0x4006aaacL,0,0,0,0);
                byte[] expectedDisk=archive.clone();int copied=failure==1?0:failure==2?200:archive.length;
                System.arraycopy(expectedArc,0,expectedDisk,0,copied);
                int dirty=failure==0?0:1;
                if(!Arrays.equals(expectedArc,a.mem(ARC,archive.length)) || !Arrays.equals(expectedDisk,a.disk)
                    || a.val(BASE+0x125,1)!=dirty || a.opens!=1 || a.writes!=(failure==1?0:1) || a.closes!=a.writes)
                    throw new Exception("Deferred flush mismatch");
                out.printf("PASS lower adapter mode=1 outcome=%d: returned 1024 before I/O; after flush dirty=%d; disk matches new archive=%s.%n",
                    failure,dirty,Arrays.equals(expectedArc,a.disk));
            }
            Arm dirty=new Arm(1,1);dirty.put(BASE+0x125,1,1);
            long saved=dirty.run(0x40125e4cL,0x40b000b5L,0,1024,DATA);
            if(saved!=0 || dirty.opens!=2 || dirty.writes!=0 || dirty.val(BASE+0x290,4)!=0
                || dirty.val(BASE+0x125,1)!=1 || !Arrays.equals(archive,dirty.disk))throw new Exception("Failed preflush control");
            dirty.run(0x4006aaacL,0,0,0,0);
            if(dirty.opens!=2)throw new Exception("Disabled mode retried dirty archive");
            out.println("PASS initially dirty mode 1/open failures: preflush and save both attempted open; save returned 0, mode became 0, dirty remains 1, later flush does not retry.");
            out.println("Outcomes: 0=full write, 1=physical open failure, 2=200-byte short write of 1216-byte archive.");
            out.println("The cold save wrapper forces mode 0 through environment selector 20; physical-write errors are masked and no dirty retry is recorded.");
            out.println("Separate lower-adapter controls bypass that wrapper: mode 1 defers writes, then flush preserves dirty on failure.");
            out.println("Modeled: selector-20 dispatch to original mode setter; other environment calls, delay, init, path compare, logical open/seek/close, handle classification, copy, raw open/size/write/close and logging.");
            out.println("No camera/storage access. Conditional archive-backed path only; no live reachability, durability, recovery, or normal-mode selection proof.");
        }
    }
    String sha(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
