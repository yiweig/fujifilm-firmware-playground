// Original ARM marker/registration controls. No camera, real filesystem or USB.
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

public class ReproducePtpMarkerLifecycle extends GhidraScript {
    static final long BASE=0x40000000L, SP=0x42010000L, END=0x43000000L;
    static final long FLAG=0x40e139d8L, TABLE=0x4136b684L, COLD=0x40e4df02L;
    static final int GLOBALS=0x1400000, TABLE_BYTES=1600;
    byte[] image;
    class Arm {
        final PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        final PcodeThread<byte[]> thread;
        int opens=0,sizes=0,closes=0; boolean filePresent=true;long fileSize=1;
        List<String> common=new ArrayList<>();
        Arm() {
            bytes(BASE,new byte[GLOBALS]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);
            byte[] cold=new byte[1024];for(int i=0;i<1024;i++)cold[i]=(byte)(i*37+11);bytes(COLD,cold);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void bytes(long a,byte[] b){machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n){return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n){return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);thread.overrideCounter(toAddr(reg("lr")));}
        boolean in(long pc,long a,long b){return pc>=a && pc<b;}
        void execute(long entry,long boundary) throws Exception {
            reg("sp",SP);reg("lr",END);thread.overrideCounter(toAddr(entry));
            for(int step=0;step<30000;step++) {
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==boundary)return;
                long r0=reg("r0"),r1=reg("r1"),r2=reg("r2"),r3=reg("r3");
                if(pc==0x4013f4acL) {
                    byte[] name="D:\\EnablePTPAP.txt\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if(r1!=0x10 || !Arrays.equals(name,bytes(r0,name.length)))throw new Exception("Wrong marker open");
                    opens++;ret(filePresent?0x1234:0);continue;
                }
                if(pc==0x4013f500L){if(r0!=0x1234)throw new Exception("Wrong size handle");sizes++;ret(fileSize);continue;}
                if(pc==0x4013f604L){if(r0!=0x1234)throw new Exception("Wrong close handle");closes++;ret(0);continue;}
                if(pc==0x40004064L){ret(0);continue;}
                if(pc==0x405e6868L && r0!=0x100) {
                    int count=(r0==0x20f || r0==0x208)?1:2;
                    if(!Set.of(0x20fL,0x101L,0x203L,0x209L,0x20cL,0x208L).contains(r0))throw new Exception("Unexpected common selector");
                    common.add(String.format("config:%x:%x%s",r0,r1,count==1?"":":"+Long.toHexString(r2)));
                    ret(1);continue;
                }
                if(pc==0x405e6bc4L) {
                    if(!Set.of(7L,8L,9L,12L).contains(r0) || r2!=0 || r3!=0 || get(reg("sp"),4)!=0)
                        throw new Exception("Unexpected callback configuration");
                    common.add(String.format("callback:%x:%x",r0,r1));ret(0);continue;
                }
                if(pc==0x40334f34L){if(r0!=0x2001)throw new Exception("Bad close response");ret(0);continue;}
                boolean admitted=in(pc,0x405219ccL,0x405219e4L)||in(pc,0x4015c52cL,0x4015c560L)
                    ||in(pc,0x405c9030L,0x405c9044L)||in(pc,0x405ca428L,0x405ca5b8L)
                    ||in(pc,0x405e6868L,0x405e6a20L)||in(pc,0x402e753cL,0x402e7598L)
                    ||in(pc,0x402e8e80L,0x402e8f1cL);
                if(!admitted)throw new Exception("Unexpected PC; not executed: "+Long.toHexString(pc));
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
        void gate() throws Exception {execute(0x405c9030L,0x405c9044L);}
    }
    void expect(boolean condition,String message)throws Exception{if(!condition)throw new Exception(message);}
    void patch(byte[] b,long a,long v,int n){System.arraycopy(Utils.longToBytes(v,n,false),0,b,(int)(a-BASE),n);}
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        expect(hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"),"Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original 2.00 main SHA-256: "+hash);
            out.println("Original ARM marker helper, size helper, entry gate, Ptp_Init, registration wrapper/table writer and selected CloseSession handler.");
            out.println("Modeled: marker filesystem open/size/close, logging, common configuration/callback installation, final PTP response. No USB, real storage, hardware fill or full entry/exit execution.");
            for(long size:new long[]{0,1,4096,0xffffffffL}) {
                Arm a=new Arm();a.fileSize=size;byte[] before=a.bytes(BASE,GLOBALS);
                a.execute(0x405219ccL,END);
                expect(a.reg("r0")== (size==0?0:1) && a.opens==1 && a.sizes==1 && a.closes==1,"Marker size result");
                expect(Arrays.equals(before,a.bytes(BASE,GLOBALS)),"Marker helper changed globals");
                out.printf("PASS marker: modeled size=%08x -> %d; open flags 10, size, close only; image/globals unchanged.%n",size,a.reg("r0"));
            }
            Arm absent=new Arm();absent.filePresent=false;byte[] before=absent.bytes(BASE,GLOBALS);absent.execute(0x405219ccL,END);
            expect(absent.reg("r0")==0 && absent.opens==1 && absent.sizes==0 && absent.closes==0 && Arrays.equals(before,absent.bytes(BASE,GLOBALS)),"Missing marker");
            out.println("PASS missing marker: failed open -> zero; no size/read/close call; globals unchanged.");
            Arm off=new Arm();off.filePresent=false;off.gate();expect(Arrays.equals(off.bytes(TABLE,TABLE_BYTES),new byte[TABLE_BYTES]),"Disabled gate registers commands");
            out.println("PASS disabled gate on empty table: flag zero; no factory registrations.");
            Arm on=new Arm();on.put(FLAG,0,1);before=on.bytes(BASE,GLOBALS);on.gate();byte[] expected=before.clone();patch(expected,FLAG,1,1);
            for(int i=0;i<7;i++)for(int w=0;w<4;w++)patch(expected,TABLE+16*i+4*w,new long[]{0x1021+i,1,0x405ca67cL,1}[w],4);
            expect(Arrays.equals(expected,on.bytes(BASE,GLOBALS)),"Enabled gate changed unexpected globals/entries");
            expect(on.common.equals(off.common),"Shared configuration differs");
            out.println("PASS enabled gate: flag one plus exactly seven 16-byte table entries 1021..1027; callback 405ca67c; other image/globals unchanged under common-call models.");
            out.println("PASS enabled/disabled common configuration calls identical: "+on.common);
            before=on.bytes(BASE,GLOBALS);on.filePresent=false;on.gate();expected=before.clone();patch(expected,FLAG,0,1);
            expect(Arrays.equals(expected,on.bytes(BASE,GLOBALS)),"False gate revoked or changed table");
            out.println("PASS removal/re-entry control: marker open fails, flag becomes zero, existing seven registrations preserved; no removal routine invoked.");
            on.filePresent=true;on.gate();expect(on.get(TABLE+7*16,4)==0x1021 && on.get(TABLE+13*16,4)==0x1027,"Repeated registration not appended");
            out.println("PASS repeated enabled initialization: second seven entries appended; existing opcodes not deduplicated.");
            byte[] table=on.bytes(TABLE,TABLE_BYTES),cold=on.bytes(COLD,1024);on.put(0x4136b674L,17,4);on.put(0x4136b680L,23,4);
            on.execute(0x402e8e80L,END);
            expect(on.get(0x4136b674L,4)==0 && on.get(0x4136b680L,4)==0 && Arrays.equals(table,on.bytes(TABLE,TABLE_BYTES)) && Arrays.equals(cold,on.bytes(COLD,1024)),"Close changed table/cold or failed clearing session");
            out.println("PASS selected CloseSession with null session allocation: clears session/length words, preserves registration table and factory buffer; response modeled.");
            out.println("Not proved: real marker activation/physical exit, all common callees, allocated-session cleanup, cold boot completion, persistence, or independent recovery.");
        }
    }
}
