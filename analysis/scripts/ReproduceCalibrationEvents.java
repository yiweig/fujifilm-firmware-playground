// Bounded offline execution of original calibration scheduling/dispatch code.
// Hardware, display, logging and file-read calls listed below are stubbed.
// Stops before calibration worker execution. No camera access.
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

public class ReproduceCalibrationEvents extends GhidraScript {
    static final long SP=0x42010000L, RETURN=0x43000000L;
    static final long ITEM=0x413e3d98L, UI=0x40e66958L;
    static final long DISPATCH=0x404d4f9cL, WORKER=0x405d5b34L;
    // Only these external callees may be skipped. Unexpected calls fail.
    static final Set<Long> STUBS=Set.of(
        0x40004064L,0x40584e28L,0x40584ad8L,0x405851c0L,0x40585024L,
        0x40200a34L,0x401fcf64L,0x4006a074L,0x401fd950L,0x401fd800L,
        0x40125d94L,0x4006f330L,0x4020545cL,0x402c4bc8L,
        0x40205448L,0x402c4bb8L);
    static final Set<Long> ENTRIES=Set.of(
        DISPATCH,0x405daec8L,0x405db640L,0x405da76cL,0x4051d918L);
    byte[] code;
    class Arm {
        final PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> thread;
        final AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        final List<Long> events=new ArrayList<>();
        Arm() {
            machine.getSharedState().setVar(ram,0x40000000L,code.length,true,code);
            thread=machine.newThread(); thread.overrideContextWithDefault();
            for(int i=0;i<13;i++) reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"}) reg(n,0);
            zero(SP-0x2000,0x4000); zero(0x40e65950L,0x2000);
            zero(ITEM,0x100); zero(0x40e4df02L,0x400); zero(0x413d3984L,1);
            put(UI,2,0xe000); put(ITEM+4,4,1);
            put(0x40e4df4aL,2,4); // synthetic FOCUS flag; prerequisite mask is zero
        }
        void zero(long a,int n) {machine.getSharedState().setVar(ram,a,n,true,new byte[n]);}
        void put(long a,int n,long v) {machine.getSharedState().setVar(ram,a,n,true,Utils.longToBytes(v,n,false));}
        long get(long a,int n) {return Utils.bytesToLong(machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT),n,false);}
        void reg(String n,long v) {
            var r=currentProgram.getRegister(n);
            thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));
            if(n.equals("pc")) thread.overrideCounter(toAddr(v));
        }
        long reg(String n) {
            var r=currentProgram.getRegister(n);
            return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);
        }
        void start(long pc) {reg("sp",SP);reg("lr",RETURN);reg("pc",pc);}
        long until(long stop) throws Exception {
            for(int i=0;i<12000;i++) {
                monitor.checkCancelled();
                long pc=thread.getCounter().getOffset();
                if(pc==stop || pc==RETURN || pc==WORKER) return pc;
                if(STUBS.contains(pc)) {
                    if(pc==0x4006f330L) events.add(reg("r0"));
                    reg("r0",0);reg("pc",reg("lr"));continue;
                }
                long inst=get(pc,4);
                if((inst&0x0f000000L)==0x0b000000L) {
                    long off=inst&0xffffffL;
                    if((off&0x800000L)!=0) off-=0x1000000L;
                    long target=(pc+8+off*4)&0xffffffffL;
                    if(target!=WORKER && !STUBS.contains(target) && !ENTRIES.contains(target))
                        throw new Exception("Unexpected call "+Long.toHexString(pc)+" -> "+Long.toHexString(target));
                }
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit at "+thread.getCounter());
        }
    }
    public void run() throws Exception {
        code=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),code);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Wrong main image: "+hash);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original 2.00 SHA-256: "+hash);
            out.println("Offline only; synthetic saved settings and RAM. Not a full startup or hardware test.");
            out.println("Stub addresses: "+STUBS.stream().map(Long::toHexString).sorted().toList());
            for(int flag:new int[]{0,4,8,0x18}) for(int enable:new int[]{0,1}) {
                Arm a=new Arm();a.put(0x40e4df6eL,2,flag);
                a.put(0x40e148a8L,2,enable); // row 15 +0x30 = runtime table +0xabc
                a.start(0x405dd964L);a.until(0x405dd9ecL);
                List<Long> expected=(flag&15)!=8?List.of():enable==0?
                    List.of(0xf0000007L):List.of(0xf0000007L,0xf0000005L,0xf0000007L);
                if(!a.events.equals(expected)) throw new Exception("Unexpected event list "+a.events);
                boolean worker=false;
                for(long event:a.events) {
                    a.reg("r0",event);a.start(DISPATCH);
                    if(a.until(RETURN)==WORKER) {worker=true;break;}
                }
                if(worker!=((flag&15)==8 && enable==1)) throw new Exception("Unexpected worker decision");
                out.printf("FgAutoClbt=%04x confirmation_enable=%d events=%s ui=%04x choice=%d worker_reached=%s%n",
                    flag,enable,a.events.stream().map(Long::toHexString).toList(),a.get(UI,2),a.get(ITEM+8,2),worker);
                out.flush();
            }
            out.println("PASS: 8 synthetic cases. Worker is stopped before executing its first instruction.");
            out.println("File read stub preserves synthetic RAM; this does not measure the camera's saved values.");
        }
    }
}
