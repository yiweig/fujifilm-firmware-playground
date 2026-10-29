// Execute the original ARM instructions for the lever-review label selection.
// Synthetic RAM only; no firmware/program writes, no camera connection.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.*;
import java.math.BigInteger;
import java.io.*;
import java.util.*;

public class ReproduceLeverSelection extends GhidraScript {
    private static final long SP=0x42010000L, CAP=0x42100000L,
        PB=0x42110000L, META=0x42120000L;
    private class ArmExecution {
        final PcodeEmulator machine;
        final PcodeThread<byte[]> thread;
        final AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        ArmExecution() throws Exception {
            machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
            byte[] code=new byte[0xe38400];
            currentProgram.getMemory().getBytes(toAddr(0x40000000L),code);
            machine.getSharedState().setVar(ram,0x40000000L,code.length,true,code);
            thread=machine.newThread();
            thread.overrideContextWithDefault();
        }
        void writeRegister(String name,long value) {
            var r=currentProgram.getRegister(name);
            thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
            if(name.equals("pc")) thread.overrideCounter(toAddr(value));
        }
        void writeRegister(String name,BigInteger value) { writeRegister(name,value.longValue()); }
        BigInteger readRegister(String name) {
            var r=currentProgram.getRegister(name);
            return BigInteger.valueOf(Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false));
        }
        void writeMemory(Address a,byte[] bytes) { machine.getSharedState().setVar(ram,a.getOffset(),bytes.length,true,bytes); }
        void writeMemoryValue(Address a,int size,long v) { writeMemory(a,Utils.longToBytes(v,size,false)); }
        byte readMemoryByte(Address a) { return machine.getSharedState().getVar(ram,a.getOffset(),1,true,Reason.INSPECT)[0]; }
        String readNullTerminatedString(Address a,int max) {
            StringBuilder s=new StringBuilder();
            for(int i=0;i<max;i++) { int c=readMemoryByte(a.add(i))&255; if(c==0)break; s.append((char)c); }
            return s.toString();
        }
        Address getExecutionAddress() { return thread.getCounter(); }
        boolean step(ghidra.util.task.TaskMonitor m) throws Exception { m.checkCancelled(); thread.stepInstruction(); return true; }
        String getLastError() { return "See exception"; }
        void dispose() {}
    }
    private void put(ArmExecution e,long a,int size,long v) {
        e.writeMemoryValue(toAddr(a),size,v);
    }
    private void one(PrintWriter out,String label,int colour,int film,
                     boolean sentinel,int expected) throws Exception {
        ArmExecution e=new ArmExecution();
        try {
            for(int r=0;r<13;r++) e.writeRegister("r"+r,0);
            for(String flag:new String[]{"CY","OV","ZR","NG"}) e.writeRegister(flag,0);
            e.writeRegister("sp",SP);
            e.writeRegister("r4",0x40e0f514L);
            e.writeRegister("r5",0x40d65424L);
            e.writeRegister("r6",0);
            e.writeRegister("r7",0);
            e.writeRegister("pc",0x4041cd28L);
            byte[] stack=new byte[0x2000]; Arrays.fill(stack,(byte)0xa5);
            e.writeMemory(toAddr(SP-0x1000),stack);
            e.writeMemory(toAddr(CAP),new byte[0x200]);
            e.writeMemory(toAddr(PB),new byte[0x1000]);
            e.writeMemory(toAddr(META),new byte[0x100]);
            put(e,0x40d65424L,4,CAP);
            put(e,0x40e0f514L,4,PB);
            put(e,0x40e0f51cL,4,META);
            put(e,0x412c2944L,1,0); // disable logging in the draw routine
            put(e,META+6,2,colour);
            put(e,META+0x5e,2,film);
            e.writeMemory(toAddr(CAP+0xca),"SYNTHETIC_LAST_IMAGE.JPG\0".getBytes("US-ASCII"));
            int observed=-1;
            boolean reachedHelper=false, patched=false;
            for(int steps=0;steps<10000;steps++) {
                long pc=e.getExecutionAddress().getOffset();
                if(pc==0x405621d4L) {
                    reachedHelper=true;
                    long record=e.readRegister("r1").longValue();
                    int field=e.readMemoryByte(toAddr(record+0x1a0))&255;
                    if(field!=0) throw new Exception("Original caller did not zero selector");
                    if(sentinel) { put(e,record+0x1a0,1,0x29); patched=true; }
                }
                if(pc==0x402294d4L) observed=e.readRegister("r1").intValue();
                if(pc==0x40229628L) {
                    String asset=e.readNullTerminatedString(
                        toAddr(e.readRegister("r4").longValue()),160);
                    out.printf("%s | RAM experiment=%s | input field=0 | selected=%d | asset=%s | steps=%d%n",
                        label,sentinel?"set field to 0x29":"none",observed,asset,steps);
                    if(!reachedHelper || (sentinel&&!patched) || observed!=expected)
                        throw new Exception("Unexpected selection for "+label);
                    out.flush(); return;
                }
                // Log and hardware-facing drawing setup are outside this bounded check.
                if(pc==0x40004064L || pc==0x401ff9d4L || pc==0x402a4458L || pc==0x401fb8b0L) {
                    e.writeRegister("r0",0);
                    e.writeRegister("pc",e.readRegister("lr"));
                    continue;
                }
                if(!e.step(monitor))
                    throw new Exception("Emulation failed at "+Long.toHexString(pc)+": "+e.getLastError());
            }
            throw new Exception("Step limit exceeded");
        } finally { e.dispose(); }
    }
    public void run() throws Exception {
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Bounded execution: 0x4041cd28 through asset selection at 0x40229628.");
            out.println("Original ARM code, synthetic capture/playback/metadata RAM. Logging and drawing setup calls stubbed.");
            out.println("This reproduces label selection, not hardware behavior or metadata freshness.");
            String[] names={"Provia","Velvia","Classic Chrome","Acros"};
            int[] colours={0x8000,0x8000,0x8000,0x500};
            int[] films={0,0x200,0x600,0};
            int[] selectors={0,1,3,8};
            for(int i=0;i<names.length;i++) {
                one(out,names[i],colours[i],films[i],false,0);
                one(out,names[i],colours[i],films[i],true,selectors[i]);
            }
            out.println("PASS: all eight bounded executions matched the expected selectors.");
        }
    }
}
