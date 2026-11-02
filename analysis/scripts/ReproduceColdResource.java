// Offline COLD.BIN loader and member lookup controls; no camera or native file APIs.
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

public class ReproduceColdResource extends GhidraScript {
    static final long END=0x43000000L, CACHE=0x42030000L, ALLOC=0x42040000L;
    byte[] image, archive;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        int kind, opens=0, reads=0, closes=0, initCalls=0; long initResult=0;
        Arm(int k) {
            kind=k;mem(0x40000000L,image);mem(CACHE,archive);
            byte[] blank=new byte[archive.length];Arrays.fill(blank,(byte)0xa5);mem(ALLOC,blank);
            mem(0x4200e000L,new byte[0x3000]);mem(0x42020000L,"COLDDATA.BIN\0".getBytes());
            mem(0x40f12bb0L,new byte[]{0});t.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            if(k==1)mem(CACHE+2,new byte[]{0,0});
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
            for(int step=0;step<5000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset(),a=reg("r0"),b=reg("r1"),c=reg("r2");
                if(pc==END)return a;
                if(pc==0x40004064L) {ret(0);continue;}
                if(entry==0x4015b5f8L && pc==0x4006a9b0L) {initCalls++;ret(initResult);continue;}
                if(pc==0x4008086cL) {
                    if(a!=0x40b06c1aL)throw new Exception("Unexpected lookup path");
                    put(b,4,archive.length);ret(kind<2?CACHE:0);continue;
                }
                if(pc==0x4001019cL) {
                    if(a!=0x40b06c1aL || b!=16 || c!=0)throw new Exception("Unexpected open");
                    opens++;ret(kind==3?0:0x1234);continue;
                }
                if(pc==0x40011380L) {if(a!=0x1234)throw new Exception("Size handle");ret(archive.length);continue;}
                if(pc==0x40020d28L) {if(a!=archive.length)throw new Exception("Allocation size");ret(ALLOC);continue;}
                if(pc==0x400102d4L) {
                    if(a!=0x1234 || b!=ALLOC || c!=archive.length)throw new Exception("Read args");
                    int count=kind==4?64:archive.length;mem(ALLOC,Arrays.copyOf(archive,count));reads++;ret(count);continue;
                }
                if(pc==0x40010294L) {if(a!=0x1234)throw new Exception("Close handle");closes++;ret(0);continue;}
                if(pc==0x40059228L) {
                    if(c!=12)throw new Exception("Compare length");
                    byte[] x=mem(a,12),y=mem(b,12);int result=0;
                    for(int i=0;i<12;i++){result=(x[i]&255)-(y[i]&255);if(result!=0 || x[i]==0)break;}
                    ret(result);continue;
                }
                boolean allowed=(pc>=0x4006a668L && pc<0x4006a6f4L)
                    || (pc>=0x4006a6f8L && pc<0x4006a74cL)
                    || (pc>=0x4006a750L && pc<0x4006a844L)
                    || (pc>=0x4015b5f8L && pc<0x4015b618L);
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
            out.println("Main-image SHA-256: "+sha(image));out.println("Packaged COLD.BIN SHA-256: "+sha(archive));
            out.println("Offline original loader/header/member instructions; modeled cache lookup, file APIs, allocation, log and string comparison.");
            for(int kind=0;kind<5;kind++) {
                Arm a=new Arm(kind);long result=a.run(0x4006a750L,0x40b06c1aL,0,0);
                long expected=kind==0?CACHE:kind==1 || kind==3?0:ALLOC;
                if(result!=expected || a.opens!=(kind>=2?1:0) || a.reads!=(kind==2 || kind==4?1:0)
                    || a.closes!=a.reads)throw new Exception("Loader case "+kind);
                if(result!=0) {
                    long member=a.run(0x4006a668L,result,0x42020000L,0x42021000L);
                    if(member!=result+128 || a.val(0x42021000L,4)!=1024)throw new Exception("Member lookup");
                    byte[] expectedMember=Arrays.copyOfRange(archive,128,1152);
                    if(kind==4)Arrays.fill(expectedMember,(byte)0xa5);
                    if(!Arrays.equals(expectedMember,a.mem(member,1024)))throw new Exception("Member data");
                }
                out.printf("PASS loader case=%d result=%08x opens=%d reads=%d closes=%d.%n",kind,result,a.opens,a.reads,a.closes);
            }
            for(long outcome:new long[]{0,0xf0000000L}) {
                Arm a=new Arm(0);a.initResult=outcome;
                a.run(0x4015b5f8L,0,0,0);a.run(0x4015b5f8L,0,0,0);
                if(a.initCalls!=1 || a.val(0x40f12bb0L,1)!=1)throw new Exception("Init latch");
                out.printf("PASS first-use resource-open result=%08x: latch remains 1, second call skips opener.%n",outcome);
            }
            out.println("Cases: 0=cached valid, 1=cached invalid, 2=cache miss/full file read, 3=file open fails, 4=cache miss/64-byte short read.");
            out.println("Case 4 accepts the header/table despite a short read; member points to untouched a5 allocation bytes.");
            out.println("Not camera evidence, persistence proof, default/live identity proof, or full resource-manager/USB lifecycle qualification.");
        }
    }
    String sha(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
}
