// Offline live-path fixtures for the updater's SD consumer: status 0x0100,
// zero button bytes, storage readiness 0/1, backend selector 0x10000058 & 0xe0,
// and drive-2/drive-5 probe order. Original consumer instructions execute;
// registration/init/log calls are modeled as returning. No hardware access.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceUpdaterSdPath extends GhidraScript {
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        String checked=null;
        final List<Integer> probes=new ArrayList<>();
        final int status,backend,readiness,drive2,drive5,fileCheck;
        Arm(int status,int backend,int readiness,int drive2,int drive5,int fileCheck,
            String name,String extension) throws Exception {
            this.status=status;this.backend=backend;this.readiness=readiness;
            this.drive2=drive2;this.drive5=drive5;this.fileCheck=fileCheck;
            mem(0x40000000L,image);mem(0x10000000L,new byte[0x6000]);mem(0x4200e000L,new byte[0x3000]);
            put(0x10001300L,4,1);                 // gate nonzero (live 0x12345678)
            put(0x10004054L,2,status);            // live calibration: 0x0100
            put(0x10000058L,2,backend);           // storage backend selector
            put(0x100040b4L,1,0);put(0x100040b5L,1,0);put(0x100040b6L,1,0);
            put(0x100040b7L,1,0);put(0x100040b8L,1,0); // live: all zero
            if(name.length()>8 || extension.length()>3)throw new Exception("Invalid mailbox fixture");
            mem(0x100040b9L,name.getBytes("US-ASCII"));mem(0x100040c1L,extension.getBytes("US-ASCII"));
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        String string(long a) throws Exception {ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<64;i++){int b=mem(a+i,1)[0]&255;if(b==0)return out.toString("US-ASCII");out.write(b);}throw new Exception("Unbounded pathname");}
        long run() throws Exception {
            reg("sp",0x42010000L);reg("lr",0x43000000L);
            t.overrideCounter(toAddr(0x400009c4L));
            for(int step=0;step<10000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x40009ca0L || pc==0x4000f270L || pc==0x40069374L || pc==0x40075ea4L)return pc;
                if(pc==0x400881b4L){long n=reg("r2");if(n!=16)throw new Exception("Unexpected copy");mem(reg("r0"),mem(reg("r1"),(int)n));ret(reg("r0"));continue;}
                if(pc==0x400165d0L || pc==0x400165e0L || pc==0x400166b8L || pc==0x4007ac04L
                   || pc==0x4007ac44L || pc==0x4007aa54L){ret(0);continue;}
                if(pc==0x4001ad68L){ret(readiness);continue;}
                if(pc==0x4000e5a4L){int d=(int)reg("r0");probes.add(d);
                    if(d==2)ret(drive2); else if(d==5)ret(drive5);
                    else throw new Exception("Unexpected drive probe "+d);continue;}
                if(pc==0x40041728L){checked=string(reg("r0"));ret(fileCheck);continue;}
                // Storage registration/initialization calls in the live path:
                if(pc==0x4000e558L || pc==0x4001aaacL || pc==0x4002bde8L || pc==0x4001be6cL
                   || pc==0x4001b4e4L || pc==0x4001b744L || pc==0x4001baa8L
                   || pc==0x4001d7f0L || pc==0x4001dbe8L){ret(0);continue;}
                if(!((pc>=0x400009c4L && pc<0x400010ecL)||(pc>=0x4006b194L && pc<0x4006b1a0L)))
                    throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong full updater image");
        // status, backend, readiness, drive2, drive5, fileCheck, expected pathname, expected boundary
        Object[][] cases={
            {0x0100,0x00,1,0,0,0,"D:\\MAIN.BIN",0x4000f270L},   // primary drive 2
            {0x0100,0x00,1,-1,0,0,"G:\\MAIN.BIN",0x4000f270L},  // drive 2 fails -> drive 5 G:
            {0x0100,0x00,1,-1,-1,0,null,0x40075ea4L},           // both mounts fail -> cleanup/service
            {0x0100,0x80,1,0,0,0,"G:\\MAIN.BIN",0x4000f270L},   // backend 0x80 -> drive 5 first
            {0x0100,0x80,1,0,-1,0,null,0x40075ea4L},            // drive 5 fails -> cleanup/service, no drive 2
            {0x0104,0x80,1,0,0,0,"D:\\MAIN.BIN",0x4000f270L},   // bit2 set -> drive 2 first
            {0x0100,0x40,1,0,0,0,"D:\\MAIN.BIN",0x4000f270L},   // NAND backend -> drive 2
            {0x0100,0x80,1,0,0,1,"G:\\MAIN.BIN",0x40075ea4L},   // G: exists? no -> cleanup/service
            {0x0100,0x00,0,0,0,0,"D:\\MAIN.BIN",0x4000f270L},   // readiness 0 -> live init path
            {0x0100,0x80,0,0,0,0,"G:\\MAIN.BIN",0x4000f270L},   // live init + backend 0x80
        };
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Full updater SHA-256: "+hash);
            out.println("Original consumer 400009c4; MMIO fixtures only; no hardware access.");
            for(Object[] c:cases) {
                Arm a=new Arm((Integer)c[0],(Integer)c[1],(Integer)c[2],(Integer)c[3],
                              (Integer)c[4],(Integer)c[5],"MAIN","BIN");
                long boundary=a.run();
                out.printf("CASE status=%04x backend=%02x ready=%d d2=%d d5=%d file=%d probes=%s -> %s, boundary=%08x%n",
                    c[0],c[1],c[2],c[3],c[4],c[5],a.probes,a.checked,boundary);
                if(boundary!=(Long)c[7] || !Objects.equals(a.checked,c[6])
                   || !Arrays.equals(image,a.mem(0x40000000L,image.length)))
                    throw new Exception("SD path control");
                out.printf("PASS status=%04x backend=%02x ready=%d d2=%d d5=%d file=%d probes=%s -> %s, boundary=%08x%n",
                    c[0],c[1],c[2],c[3],c[4],c[5],a.probes,a.checked,boundary);
            }
            out.println("10 controls passed. The live calibration fixture (status 0100, zero buttons) takes the button/init branch; the backend 0x80 cases model drive 5 first.");
            out.println("Modeled: registration/init/log calls return 0; drive probes and file-check results are supplied. No mount, FAT, file read, boot task or USB startup executes.");
        }
    }
}
