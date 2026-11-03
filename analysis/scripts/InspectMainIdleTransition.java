// Original idle guards and waiter slices; stop before sensor or delay execution.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectMainIdleTransition extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        byte[] expectedImage;
        Arm(){
            bytes(BASE,image);bytes(SP-256,new byte[512]);bytes(RECORD,new byte[256]);
            expectedImage=bytes(BASE,image.length);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",SP);reg("lr",END);
        }
        void bytes(long a,byte[] b){m.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] bytes(long a,int n){return m.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        void fixture(int index,int ready){bytes(0x40f80d28L,new byte[0x38]);put(0x40f80d40L,index,4);put(0x40f80d5cL,ready,4);}
        long execute(long entry) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",0x88);t.overrideCounter(toAddr(entry));
            for(int n=0;n<1000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END||pc==0x4010e11cL||pc==0x4000c450L)return pc;
                if(pc==0x40004064L||pc==0x400410ccL){ret(0);continue;}
                if(pc==0x4018323cL){ret(0x44000000L);continue;} // mode lock modeled
                if(pc==0x4018331cL){ret(0);continue;} // mode unlock modeled
                boolean allowed=(pc>=0x4015bf04L&&pc<0x4015bf68L)||(pc>=0x4015be34L&&pc<0x4015be70L)
                    ||(pc>=0x4015bbd8L&&pc<0x4015bbecL)||(pc>=0x40183744L&&pc<0x40183760L)
                    ||(pc>=0x40183768L&&pc<0x401837e0L)||(pc>=0x40183710L&&pc<0x4018373cL)
                    ||(pc>=0x40184de4L&&pc<0x40184e9cL)||(pc>=0x401ad30cL&&pc<0x401ad3a0L);
                if(!allowed)throw new Exception("Unexpected idle PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Idle instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Main image SHA256 "+hash);
            out.println("Original idle wrapper, mode-ID getter, sensor-config dispatch and waiter. Mode state/readiness supplied. Logging/lock/unlock modeled. Stop before sensor setter or wait delay; no mode worker executes.");
            for(int ready:new int[]{0,1}){
                Arm a=new Arm();a.fixture(0,ready);
                if(a.execute(0x4015bf04L)!=END||a.reg("r0")!=1)throw new Exception("Already-idle control");a.unchanged();
                out.printf("PASS idle wrapper mode=88 ready=%d -> immediate return 1; waiter and transition not invoked.%n",ready);
            }
            Arm other=new Arm();other.fixture(1,0);
            if(other.execute(0x4015bf04L)!=0x4010e11cL||other.reg("r0")!=0||other.reg("r1")!=100)throw new Exception("Non-idle sensor boundary");other.unchanged();
            out.println("PASS idle wrapper mode=41 ready=0 -> sensor setter boundary 4010e11c(0,100), stopped before setter; no mode request yet.");
            for(int ready:new int[]{0,1}){
                Arm a=new Arm();a.fixture(0,ready);long stop=a.execute(0x40183768L);
                if(stop!=(ready==0?0x4000c450L:END)||(ready==0&&a.reg("r0")!=10))throw new Exception("Wait readiness control");a.unchanged();
                out.printf("PASS standalone waiter mode=88 ready=%d -> boundary=%08x; no delay executes.%n",ready,stop);
            }
            out.println("5 controls passed. No mode task, sensor write, clock switch, arbitrary callback, real sleep, USB/NAND/SD, persistent write, payload or camera executes. Not a complete idle transition or readiness guarantee.");
        }
    }
}
