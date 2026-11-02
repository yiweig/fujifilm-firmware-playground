// Offline calibration-init save boundary and RTC-wakeup-disable intent.
// No actual storage, RTC register access, USB or camera execution.
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

public class ReproduceServiceEntryWrites extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, COLD=0x40e4df02L;
    static final int LENGTH=0x01400000;
    static final Set<Long> DISPLAY=Set.of(0x40584e20L,0x401f6fd0L,0x401fd800L,0x402a5790L,0x4023db2cL,0x40584e28L);
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();PcodeThread<byte[]> thread;
        byte[] file;boolean readSuccess;int reads=0;List<String> rtcWrites=new ArrayList<>();
        Arm() {
            bytes(BASE,new byte[LENGTH]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void bytes(long a,byte[] b) {machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n) {return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n) {bytes(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n) {return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v) {var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n) {var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void returned(long value) {reg("r0",value);thread.overrideCounter(toAddr(reg("lr")));}
        void run(long entry,long boundary,boolean rtc) throws Exception {
            reg("sp",SP);reg("lr",END);thread.overrideCounter(toAddr(entry));
            for(int i=0;i<30000;i++) {
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==boundary)return;
                if(pc==0x40004064L) {returned(0);continue;}
                if(!rtc && DISPLAY.contains(pc)) {returned(0);continue;}
                if(!rtc && pc==0x40122460L) {returned(0x1234);continue;}
                if(!rtc && pc==0x4000cdf0L) {
                    long address=reg("r0"),length=reg("r2");
                    if(reg("r1")!=0 || length!=30 || address<SP-0x4000 || address+length>SP)
                        throw new Exception("Unexpected memset");
                    bytes(address,new byte[(int)length]);returned(address);continue;
                }
                if(!rtc && pc==0x40125d94L) {
                    if(reg("r1")!=0 || reg("r2")!=1024 || reg("r3")!=COLD ||
                       !new String(bytes(reg("r0"),22),java.nio.charset.StandardCharsets.US_ASCII).startsWith("A:\\RO_RES\\COLDDATA.BIN"))
                        throw new Exception("Unexpected reload args");
                    reads++;if(readSuccess)bytes(COLD,file);returned(readSuccess?0:1);continue;
                }
                if(rtc && (pc==0x400573c0L || pc==0x40003dd4L || pc==0x40057460L)) {returned(0);continue;}
                if(rtc && pc==0x40057484L) {
                    rtcWrites.add(String.format("%02x=%02x",reg("r0"),reg("r1")));returned(0);continue;
                }
                boolean admitted=rtc?
                    ((pc>=0x40126800L && pc<0x40126880L) || (pc>=0x40041d14L && pc<0x40041da4L) ||
                     (pc>=0x40041550L && pc<0x40041704L) || (pc>=0x40041384L && pc<0x400413b0L)):
                    ((pc>=0x405da858L && pc<0x405dad90L) || (pc>=0x4051d918L && pc<0x4051d934L) ||
                     (pc>=0x400de164L && pc<0x400de17cL) || (pc>=0x40518c10L && pc<0x40518c30L) ||
                     (pc>=0x40125d98L && pc<0x40125dacL));
                if(!admitted)throw new Exception("Unexpected PC "+Long.toHexString(pc));
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("InitClbtData starts at actual entry 405daa2c; standalone fixture, not full service/button/USB startup.");
            out.println("Modeled display/capture callees: "+DISPLAY.stream().map(Long::toHexString).sorted().toList());
            out.println("Also modeled: printf, battery query 40122460, stack-only memset, cold-file read 40125d94; stop BEFORE save helper 40125e4c.");
            for(int auto:new int[]{0,8})for(boolean success:new boolean[]{true,false}) {
                Arm a=new Arm();a.file=new byte[1024];a.file[0x6c]=(byte)auto;
                a.file[0x48]=4;a.file[0x49]=1;a.file[0x300]=(byte)0xaa;
                byte[] initial=a.file.clone();initial[0x300]=0x55;
                a.bytes(COLD,initial);a.put(0x40e6696dL,1,1);a.readSuccess=success;
                a.run(0x405daa2cL,0x40125e4cL,false);
                if(a.reads!=1 || a.reg("r1")!=0 || a.reg("r2")!=1024 || a.reg("r3")!=COLD ||
                   !new String(a.bytes(a.reg("r0"),22),java.nio.charset.StandardCharsets.US_ASCII).startsWith("A:\\RO_RES\\COLDDATA.BIN"))
                    throw new Exception("Unexpected save boundary");
                byte[] expected=(success?a.file:initial).clone();
                for(int i=4;i<10;i++)expected[i]=0;expected[4]=2;expected[8]=6;
                if(!Arrays.equals(expected,a.bytes(COLD,1024)) || a.get(0x40e6696dL,1)!=1)
                    throw new Exception("Unexpected cold/USB flag state");
                out.printf("PASS: FgAutoClbt=%d modeled-read=%s -> save boundary; version fields 2,0,6; byte+300=%02x; full cold comparison passed.%n",
                    auto,success?"success":"failure",a.get(COLD+0x300,1));out.flush();
            }
            out.println("RTC controls start at 40126800 with r0=0 and supplied init-ready byte; lock/IRQ/unlock helpers modeled.");
            out.println("RTC writer 40057484 is intercepted to record intent and return success; no RTC register writes executed.");
            for(int seconds:new int[]{0,255}) {
                Arm a=new Arm();a.put(0x40e4ccc9L,1,1);a.put(0x40e4ccc8L,1,1);a.put(0x40e6bc24L,1,1);
                byte[] expected=a.bytes(BASE,LENGTH);expected[(int)(0x40e4ccc8L-BASE)]=0;expected[(int)(0x40e6bc24L-BASE)]=0;
                a.reg("r0",0);a.reg("r1",seconds);a.run(0x40126800L,END,true);
                if(!a.rtcWrites.equals(List.of("d0=00","c0=00")) || !Arrays.equals(expected,a.bytes(BASE,LENGTH)))
                    throw new Exception("RTC disable mismatch");
                out.printf("PASS: wakeup-disable delay=%d -> modeled RTC intents %s; exact global comparison passed.%n",seconds,a.rtcWrites);
            }
            out.println("Six controls passed. Save attempt is not successful persistence; read-failure leaves stale RAM available to the attempted whole-buffer save.");
            out.println("No camera commands, live RTC/file writes, calibration workers or actual USB transition executed.");
        }
    }
}
