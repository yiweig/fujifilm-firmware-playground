// Original file-update wrapper and package parser. Stops BEFORE resident update engine.
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

public class ReproduceUpdaterFilePath extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, PATH=0x42020000L, CORE=0x405efddcL;
    byte[] image, packageBytes;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        long next=0x44000000L, filePosition=0, dataBuffer=0;int capacity, closes=0, opens=0;
        String fault;List<String> reads=new ArrayList<>();
        Arm(int capacity,String fault) {
            this.capacity=capacity;this.fault=fault;mem(BASE,image);mem(0x4200e000L,new byte[0x3000]);
            mem(PATH,"D:\\FPUPDATE.DAT\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));put(0x40e15398L,1,0x3f);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(mem(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        String string(long a) throws Exception {
            ByteArrayOutputStream out=new ByteArrayOutputStream();for(int i=0;i<256;i++){int b=(int)val(a+i,1);if(b==0)return out.toString("US-ASCII");out.write(b);}
            throw new Exception("Unbounded string");
        }
        long run() throws Exception {
            reg("r0",PATH);reg("sp",0x42010000L);reg("lr",END);t.overrideCounter(toAddr(0x405f9794L));
            for(int step=0;step<15000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END || pc==CORE)return pc;
                long a=reg("r0"),b=reg("r1"),c=reg("r2");
                if(pc==0x40004064L){ret(0);continue;}
                if(pc==0x4001019cL){if(a!=PATH || b!=0x10 || c!=0 || ++opens!=1)throw new Exception("Unexpected open");ret(fault.equals("open")?0:17);continue;}
                if(pc==0x40011380L){ret(packageBytes.length);continue;}
                if(pc==0x40020d28L){
                    if(a>capacity){ret(0);continue;}if(a<=0 || a>0x400000)throw new Exception("Allocation size");
                    long ptr=next;next+=(a+0xfff)&~0xfffL;mem(ptr,new byte[(int)a]);
                    if(a>0x100000 && dataBuffer==0)dataBuffer=ptr; // the probe is replaced below at read
                    ret(ptr);continue;
                }
                if(pc==0x40020eccL){ret(0);continue;}
                if(pc==0x4000cdf0L){if(c>0x10000)throw new Exception("Zero size");byte[] z=new byte[(int)c];Arrays.fill(z,(byte)b);mem(a,z);ret(a);continue;}
                if(pc==0x40008b8cL){if(c>0x10000)throw new Exception("Copy size");mem(a,mem(b,(int)c));ret(a);continue;}
                if(pc==0x40021768L){ret(string(a).compareTo(string(b)));continue;}
                if(pc==0x4000ceb0L){if(!string(b).equals("%04x.BIN"))throw new Exception("Unexpected formatting");mem(a,(String.format("%04x.BIN",c)+"\0").getBytes("US-ASCII"));ret(8);continue;}
                if(pc==0x400103b8L){
                    // 64-bit offset is aligned to r2:r3; r1 is padding, origin is on stack.
                    if(a!=17 || reg("r3")!=0 || val(reg("sp"),4)!=0)throw new Exception("Unexpected seek");
                    filePosition=c;ret(c);continue;
                }
                if(pc==0x400102d4L){
                    if(a!=17 || filePosition+c>packageBytes.length || c>capacity)throw new Exception("Unexpected read");
                    int n=(int)c;if(fault.equals("short") && filePosition==0)n--;
                    byte[] bytes=Arrays.copyOfRange(packageBytes,(int)filePosition,(int)filePosition+n);
                    if(fault.equals("hdr1") && filePosition<=0xa2c00 && filePosition+n>0xa2c00)bytes[(int)(0xa2c00-filePosition)]^=1;
                    mem(b,bytes);reads.add(String.format("offset=%08x count=%x",filePosition,n));
                    if(filePosition==0)dataBuffer=b;filePosition+=n;ret(n);continue;
                }
                if(pc==0x40010294L){if(a!=17)throw new Exception("Unexpected close");closes++;ret(0);continue;}
                if(!((pc>=0x405f9794L && pc<0x405f9aa8L)||(pc>=0x405f8f9cL && pc<0x405f945cL)
                    ||(pc>=0x40022528L && pc<0x40022564L)||(pc>=0x40008cacL && pc<0x40008d10L)
                    ||(pc>=0x405f19e0L && pc<0x405f19fcL)))throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        packageBytes=Files.readAllBytes(Path.of(getScriptArgs()[1]));
        String mainHash=hash(image),pkgHash=hash(packageBytes);
        if(!mainHash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923")
            || !pkgHash.equals("3c1c662957003e5bab4f2a8fcb184180d72a92dbdec4fbeea030dac0df755d68"))throw new Exception("Wrong artifacts");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main-image SHA-256: "+mainHash);out.println("Official package SHA-256: "+pkgHash);
            for(String fault:new String[]{"none","open","short","hdr1"})for(int cap:new int[]{0x200000,0x400000}) {
                if(!fault.equals("none") && cap==0x400000)continue;
                Arm a=new Arm(cap,fault);long stop=a.run();byte[] expected=image.clone();expected[(int)(0x40e15398L-BASE)]=(byte)(fault.equals("none")?'D':0x3f);
                if(!Arrays.equals(expected,a.mem(BASE,image.length)))throw new Exception("Unexpected image/global changes");
                if(fault.equals("none")) {
                    long state=a.reg("r1");
                    if(stop!=CORE || a.reg("r0")!=a.dataBuffer || a.val(state+0x1c,4)!=0x08fe3d20L || a.val(state+0x268,4)!=17
                        || a.val(state+0x234,4)!=0 || a.val(state+0x274,4)>cap || a.closes!=0
                        || a.val(a.dataBuffer+0x200,4)!=0xea0000ceL)throw new Exception("Resident-engine boundary");
                    out.printf("PASS cap=%x: resident engine boundary %08x, package-buffer=%08x parser-state=%08x window=%x; FW1 offset=08fe3d20, FW2 enable=0; reads=%s%n",cap,stop,a.dataBuffer,state,a.val(state+0x274,4),a.reads);
                } else {
                    if(stop!=END || a.reg("r0")!=0xfffffffeL || a.closes!=(fault.equals("open")?0:1))throw new Exception("Failure control "+fault);
                    out.printf("PASS fault=%s: returns fffffffe, closes=%d, resident engine not reached.%n",fault,a.closes);
                }
            }
            if(!hash(packageBytes).equals(pkgHash))throw new Exception("Host package changed");
            out.println("5 controls passed. Original file wrapper and package parser execute; packaged updater bytes are read as data, never executed on these paths.");
            out.println("Modeled: read-only file operations, allocator policy, memory helpers, formatting and logs. Actual RAM drive getter/setter execute.");
            out.println("Stops BEFORE 405efddc: no update-engine checksum pass, storage interface, flash/RTC/hardware operation, packaged reset vector or updater startup executes.");
            out.println("Not a full UI update simulation, independently bootable updater, live recovery procedure, or proof about every indirect callee of the update engine.");
        }
    }
    String hash(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
