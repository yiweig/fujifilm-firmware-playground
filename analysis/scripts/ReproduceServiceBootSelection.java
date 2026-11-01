// Execute original ARM input sampling and calibration-gate instructions offline.
// All peripheral values and runtime RAM are synthetic. No camera access.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public class ReproduceServiceBootSelection extends GhidraScript {
    private static final long SP=0x42010000L;
    private byte[] code;
    private class Arm {
        final PcodeEmulator machine;
        final PcodeThread<byte[]> thread;
        final AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        Arm() throws Exception {
            machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
            machine.getSharedState().setVar(ram,0x40000000L,code.length,true,code);
            thread=machine.newThread();
            thread.overrideContextWithDefault();
            for(int i=0;i<13;i++) reg("r"+i,0);
            for(String name:new String[]{"CY","OV","ZR","NG"}) reg(name,0);
            reg("sp",SP);
            zero(SP-0x1000,0x2000);
            zero(0x40e7a2a4L,0x1000);
            zero(0x40e6bc84L,0x20);
            zero(0x40e4df02L,0x400);
        }
        void reg(String name,long value) {
            var r=currentProgram.getRegister(name);
            thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
            if(name.equals("pc")) thread.overrideCounter(toAddr(value));
        }
        long reg(String name) {
            var r=currentProgram.getRegister(name);
            return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);
        }
        void put(long address,int size,long value) {
            machine.getSharedState().setVar(ram,address,size,true,Utils.longToBytes(value,size,false));
        }
        void zero(long address,int size) {
            machine.getSharedState().setVar(ram,address,size,true,new byte[size]);
        }
        long word(long address) {
            return Utils.bytesToLong(machine.getSharedState().getVar(ram,address,4,true,Reason.INSPECT),4,false);
        }
        void until(long... stops) throws Exception {
            for(int i=0;i<2000;i++) {
                monitor.checkCancelled();
                long pc=thread.getCounter().getOffset();
                for(long stop:stops) if(pc==stop) return;
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit exceeded at "+thread.getCounter());
        }
    }
    public void run() throws Exception {
        code=new byte[0xe38400];
        currentProgram.getMemory().getBytes(toAddr(0x40000000L),code);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Expected the original 2.00 main image, got "+hash);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Offline ARM emulation; synthetic peripheral registers and runtime RAM.");
            out.println("Executes 0x400df3f0 through input sampling to 0x400df474, then gate 0x405174e0.");
            out.println("Earlier power-source selection is excluded; base boot reason is supplied.");
            out.println("DisableTest/file checks and calibration code are excluded; no hardware or calls are stubbed in the executed slices.");
            for(int base:new int[]{1,4}) for(int bits=0;bits<8;bits++) {
                boolean play=(bits&1)!=0, movie=(bits&2)!=0, flash=(bits&4)!=0;
                Arm a=new Arm();
                a.put(0x1000a10eL,1,3); // release both shutter stages
                a.put(0x1000a10dL,1,flash?0x20:0);
                a.put(0x100009d0L,4,movie?0:0x4000);
                a.put(0x1000903cL,1,play?0:0x20);
                a.put(0x100003a3L,1,1);
                a.reg("r4",base);
                a.reg("pc",0x400df3f0L);
                a.until(0x400df474L);
                long sampled=a.word(SP), boot=a.reg("r4");
                long expected=base|(play?2:0)|(movie?0x100000:0)|(flash?0x200000:0);
                if(boot!=expected) throw new Exception("Unexpected boot mask "+Long.toHexString(boot));
                a.put(0x40e6bc8cL,4,boot);
                a.put(0x40e4df6aL,2,8); // FgBootType: reject alternate factory-settings route
                a.put(0x40e4df6cL,2,8); // FgUsbMode
                a.reg("pc",0x405174e0L);
                a.until(0x40517500L,0x4051760cL,0x40517618L);
                boolean accepted=a.thread.getCounter().getOffset()==0x40517500L;
                out.printf("base=%x play=%s movie=%s flash=%s sampled=%08x boot=%08x outer_gate=%s%n",
                    base,play,movie,flash,sampled,boot,accepted?"accepted":"rejected");
                if(accepted!=(bits==7)) throw new Exception("Unexpected calibration outer-gate decision");
                out.flush();
            }
            out.println("PASS: 16 executions; all three inputs are required for this gate with these synthetic factory settings.");
            out.println("This validates instruction/dataflow mapping, not physical wiring, timing, full startup, or live safety.");
        }
    }
}
