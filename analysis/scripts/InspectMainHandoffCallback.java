// Original callback registration and optional dispatch; body calls modeled only.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectMainHandoffCallback extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        final List<String> posts=new ArrayList<>();int reads=0,logs=0;byte[] expectedImage;
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
        List<Long> calls=new ArrayList<>();
        long execute(long entry,long r0,long r1,boolean body) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",r0);reg("r1",r1);t.overrideCounter(toAddr(entry));
            for(int n=0;n<500;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END||pc==0x401e1f58L||(!body&&pc==0x405da53cL))return pc;
                if(body&&Set.of(0x40205fc4L,0x405e6fa0L,0x4015bf04L).contains(pc)){
                    if(pc==0x40205fc4L&&reg("r0")!=0)throw new Exception("First callback helper argument");calls.add(pc);ret(0);continue;
                }
                boolean allowed=(pc>=0x405dd4e0L&&pc<0x405dd4ecL)||(pc>=0x4038ddb0L&&pc<0x4038e204L)
                    ||(pc>=0x401e20f4L&&pc<0x401e2100L)||(pc>=0x401e1f40L&&pc<0x401e1f58L)
                    ||(body&&pc>=0x405da53cL&&pc<0x405da554L);
                if(!allowed)throw new Exception("Unexpected callback PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Callback instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Main image SHA256 "+hash);
            out.println("Original main calibration registration wrapper, complete variadic API entry, setter and optional receiver-call branch. No calibration startup, USB transport or callback subhelper runs.");
            Arm a=new Arm();a.put(0x40fd0d90L,0,4);
            if(a.execute(0x405dd4e0L,0,0,false)!=END||a.val(0x40fd0d90L,4)!=0x405da53cL)throw new Exception("Registration control");a.unchanged();
            out.println("PASS registration wrapper -> API selector 600 -> callback RAM 40fd0d90 = 405da53c.");
            if(a.execute(0x401e1f40L,0,0,false)!=0x405da53cL)throw new Exception("Installed callback dispatch");a.unchanged();
            out.println("PASS optional receiver branch -> installed callback boundary; stop before callback instruction.");
            if(a.execute(0x4038ddb0L,0x600,0,false)!=END||a.val(0x40fd0d90L,4)!=0)throw new Exception("Clear control");a.unchanged();
            out.println("PASS API selector 600 argument 0 -> callback RAM cleared (emulator only).");
            if(a.execute(0x401e1f40L,0,0,false)!=0x401e1f58L)throw new Exception("Null callback skip");a.unchanged();
            out.println("PASS null optional callback -> skip to 401e1f58; no callback runs.");
            if(a.execute(0x405da53cL,0,0,true)!=END||!a.calls.equals(List.of(0x40205fc4L,0x405e6fa0L,0x4015bf04L)))throw new Exception("Callback call order");a.unchanged();
            out.println("PASS callback wrapper calls 40205fc4(0), 405e6fa0, then tail-calls 4015bf04, with all three modeled as returning.");
            out.println("5 controls passed. No actual pre-handoff helper, lens operation, idle transition, controller, NAND/SD, firmware write, scheduler, payload or camera executes. Production callback presence and safe entry remain unknown.");
        }
    }
}
