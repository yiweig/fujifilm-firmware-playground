// Offline semantics check for the loader handler trampoline.
// Verifies: counter at 0x41100100 increments, r0-r3 restored, control reaches
// the original handler 0x401e1280 with a balanced stack. No camera access.
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

public class ReproduceTrampoline extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    static final int LENGTH=0x01400000;
    static final long TRAMP=0x41100040L, COUNTER=0x41100100L, HANDLER=0x401e1280L;
    // push{r0,r1}; movw/movt r0,COUNTER; ldr/add/str; pop{r0,r1}; movw/movt ip,HANDLER; bx ip
    static final int[] WORDS={0xe92d0003,0xe3000100,0xe3440110,0xe5901000,0xe2811001,
        0xe5801000,0xe8bd0003,0xe301c280,0xe344c01e,0xe12fff1c};
    public void run() throws Exception {
        byte[] image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        machine.getSharedState().setVar(ram,BASE,LENGTH,true,new byte[LENGTH]);
        machine.getSharedState().setVar(ram,BASE,image.length,true,image);
        byte[] code=new byte[WORDS.length*4];
        for(int i=0;i<WORDS.length;i++){int v=WORDS[i];code[i*4]=(byte)v;code[i*4+1]=(byte)(v>>8);code[i*4+2]=(byte)(v>>16);code[i*4+3]=(byte)(v>>24);}
        machine.getSharedState().setVar(ram,TRAMP,code.length,true,code);
        machine.getSharedState().setVar(ram,SP-0x4000,0x8000,true,new byte[0x8000]);
        var thread=machine.newThread();thread.overrideContextWithDefault();
        java.util.function.BiConsumer<String,Long> set=(n,v)->{var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));};
        java.util.function.Function<String,Long> get=n->{var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);};
        set.accept("sp",SP);set.accept("lr",END);
        set.accept("r0",0x11111111L);set.accept("r1",0x22222222L);set.accept("r2",0x33333333L);set.accept("r3",0x44444444L);
        for(String n:new String[]{"CY","OV","ZR","NG"})set.accept(n,0L);
        thread.overrideCounter(toAddr(TRAMP));
        long pc=0;
        for(int i=0;i<1000;i++){ pc=thread.getCounter().getOffset(); if(pc==HANDLER)break; thread.stepInstruction(); }
        long counter=Utils.bytesToLong(machine.getSharedState().getVar(ram,COUNTER,4,true,Reason.INSPECT),4,false);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("reached handler: "+(pc==HANDLER)+" pc=0x"+Long.toHexString(pc));
            out.println("counter after 1 invocation: "+counter);
            out.printf("r0=%08x r1=%08x r2=%08x r3=%08x sp_balanced=%s%n",
                get.apply("r0"),get.apply("r1"),get.apply("r2"),get.apply("r3"),get.apply("sp")==SP);
            boolean pass=pc==HANDLER&&counter==1&&get.apply("r0")==0x11111111L&&get.apply("r1")==0x22222222L
                &&get.apply("r2")==0x33333333L&&get.apply("r3")==0x44444444L&&get.apply("sp")==SP;
            out.println(pass?"TRAMPOLINE PASS":"TRAMPOLINE FAIL");
            out.flush();
            if(!pass)throw new Exception("Trampoline semantics failed");
        }
    }
}
