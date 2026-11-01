// Original ARM getter and UI event gate; all RAM/cable values are synthetic.
// Stops before mode selection or normal USB work. Never accesses the camera.
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

public class ReproduceNormalUsbLoaderGate extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        PcodeThread<byte[]> thread;
        Arm() {
            machine.getSharedState().setVar(ram,BASE,image.length,true,image);
            machine.getSharedState().setVar(ram,0x40e4df02L,0x400,true,new byte[0x400]);
            machine.getSharedState().setVar(ram,0x40e65950L,0x1100,true,new byte[0x1100]);
            machine.getSharedState().setVar(ram,SP-0x1000,0x2000,true,new byte[0x2000]);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int n=0;n<13;n++)reg("r"+n,0);
            for(String n:new String[]{"CY","ZR","NG","OV"})reg(n,0);
            reg("sp",SP);reg("lr",END);
        }
        void put(long a,int size,long value) {
            machine.getSharedState().setVar(ram,a,size,true,Utils.longToBytes(value,size,false));
        }
        void reg(String n,long value) {
            var r=currentProgram.getRegister(n);
            thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
        }
        long reg(String n) {
            var r=currentProgram.getRegister(n);
            return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);
        }
        void pc(long a) {thread.overrideCounter(toAddr(a));}
        void returned(long value) {long lr=reg("lr");reg("r0",value);pc(lr);}
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Synthetic cold data, UI state and cable; no hardware accessed.");
            for(int nibble=0;nibble<16;nibble++) {
                Arm a=new Arm();a.put(0x40e4df6cL,2,0x100|nibble);a.pc(0x40125284L);
                boolean done=false;
                for(int n=0;n<60;n++) {
                    long pc=a.thread.getCounter().getOffset();
                    if(pc==END) {done=true;break;}
                    if(pc<0x40125284L || pc>=0x401252b0L)throw new Exception("Getter escaped");
                    a.thread.stepInstruction();
                }
                if(!done || a.reg("r0")!=(nibble==8?0:1))throw new Exception("Getter mismatch");
            }
            out.println("PASS: getter over all 16 low nibbles with high flag 0x100; only nibble 8 returns zero.");
            out.println("Runtime flag is !getter at inspected initialization sites.");
            for(int flag=0;flag<2;flag++)for(int cached=0;cached<2;cached++)for(int cable=0;cable<2;cable++) {
                Arm a=new Arm();a.put(0x40e66958L,2,0x8000);
                a.put(0x40e6696bL,1,cached);a.put(0x40e6696dL,1,flag);
                a.reg("r0",0xf0000044L);a.pc(0x404db31cL);
                String result=null;
                for(int n=0;n<600;n++) {
                    monitor.checkCancelled();long pc=a.thread.getCounter().getOffset();
                    if(pc==END) {result="returned";break;}
                    if(pc==0x4038d6d0L) {
                        if(a.reg("r0")!=4)throw new Exception("Unexpected mode");
                        result="mode4 boundary";break;
                    }
                    if(pc==0x4000f9d0L) {result="normal USB branch boundary";break;}
                    if(pc==0x40004064L || pc==0x405cbb84L) {a.returned(0);continue;}
                    if(pc==0x40123bf8L) {a.returned(cable);continue;}
                    if(!((pc>=0x404db31cL && pc<0x404db3dcL) ||
                         (pc>=0x404d665cL && pc<0x404d6f90L)))
                        throw new Exception("Unexpected execution at "+Long.toHexString(pc));
                    a.thread.stepInstruction();
                }
                String expected=flag==0?"mode4 boundary":cached==cable?"returned":"normal USB branch boundary";
                if(!expected.equals(result))throw new Exception("Wrong event branch: "+result);
                out.printf("PASS: flag=%d cached=%d cable=%d -> %s%n",flag,cached,cable,result);
                out.flush();
            }
            out.println("Event delivery supplied; logging, cable getter and descriptor setup stubbed.");
            out.println("Stops before mode setter/normal USB work; no calibration, USB lifecycle or entry timing validated.");
        }
    }
}
