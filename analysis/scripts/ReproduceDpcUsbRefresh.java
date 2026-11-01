// Test a supplied internal-shell handler invocation, not shell accessibility.
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

public class ReproduceDpcUsbRefresh extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, ARGS=0x42020000L;
    static final int LENGTH=0x01400000;
    public void run() throws Exception {
        byte[] image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Supplied DPC handler 4051d24c and parsed-argument pointer r2; shell parser and transport NOT executed.");
            out.println("Only diagnostic print 40004064 modeled as return; strict original-code PC allowlist; no camera access.");
            check(out,image,31,0x14,1);
            check(out,image,31,0x18,0);
            check(out,image,30,0x14,1);
            check(out,image,32,0x14,1);
            out.println("For selector 31 only global changes allowed: cached USB flag 40e6696d and getter side-effect byte 40e7ae50.");
            out.println("All cases preserve the full 1024-byte cold fixture; selector 32 stops before its file-read helper.");
            out.println("This supplies a shell invocation; it does not establish retail USB access or recovery.");
        }
    }
    void check(PrintWriter out,byte[] image,int selector,int coldLow,int oldFlag) throws Exception {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        machine.getSharedState().setVar(ram,BASE,LENGTH,true,new byte[LENGTH]);
        machine.getSharedState().setVar(ram,BASE,image.length,true,image);
        machine.getSharedState().setVar(ram,SP-0x4000,0x8000,true,new byte[0x8000]);
        machine.getSharedState().setVar(ram,ARGS,16,true,new byte[16]);
        put(machine,ram,ARGS+4,selector,4);
        put(machine,ram,0x40e6696dL,oldFlag,1);put(machine,ram,0x40e7ae50L,0,1);
        byte[] cold=new byte[1024];for(int i=0;i<cold.length;i++)cold[i]=(byte)(i*37+11);
        cold[0x6a]=(byte)coldLow;cold[0x6b]=1;
        machine.getSharedState().setVar(ram,0x40e4df02L,1024,true,cold);
        byte[] expected=machine.getSharedState().getVar(ram,BASE,LENGTH,true,Reason.INSPECT);
        int newFlag=selector==31?(coldLow==0x18?1:0):oldFlag;
        if(selector==31) {
            expected[(int)(0x40e6696dL-BASE)]=(byte)newFlag;
            expected[(int)(0x40e7ae50L-BASE)]=1;
        }
        PcodeThread<byte[]> thread=machine.newThread();thread.overrideContextWithDefault();
        for(int i=0;i<13;i++)set(thread,"r"+i,0);
        for(String n:new String[]{"CY","OV","ZR","NG"})set(thread,n,0);
        set(thread,"r2",ARGS);set(thread,"sp",SP);set(thread,"lr",END);
        thread.overrideCounter(toAddr(0x4051d24cL));
        long boundary=selector==32?0x40125d94L:END;int logs=0;boolean reached=false;
        for(int steps=0;steps<2000;steps++) {
            monitor.checkCancelled();long pc=thread.getCounter().getOffset();
            if(pc==boundary) {reached=true;break;}
            if(pc==0x40004064L) {
                logs++;set(thread,"r0",0);
                thread.overrideCounter(toAddr(Utils.bytesToLong(thread.getState().getVar(currentProgram.getRegister("lr"),Reason.INSPECT),4,false)));
                continue;
            }
            if(!((pc>=0x4051d24cL && pc<0x4051d26cL) ||
                 (pc>=0x4051d5d4L && pc<0x4051d62cL) ||
                 (pc>=0x4051d868L && pc<0x4051d878L) ||
                 (pc>=0x40125284L && pc<0x401252b0L)))
                throw new Exception("Unexpected execution at "+Long.toHexString(pc));
            thread.stepInstruction();
        }
        if(!reached)throw new Exception("Instruction limit");
        if(!Arrays.equals(expected,machine.getSharedState().getVar(ram,BASE,LENGTH,true,Reason.INSPECT)))
            throw new Exception("Unexpected image/global RAM change");
        out.printf("PASS: selector=%d FgUsbMode=01%02x flag=%d->%d boundary=%08x logs=%d; exact global RAM comparison passed.%n",
            selector,coldLow,oldFlag,newFlag,boundary,logs);out.flush();
    }
    void put(PcodeEmulator machine,AddressSpace ram,long address,long value,int size) {
        machine.getSharedState().setVar(ram,address,size,true,Utils.longToBytes(value,size,false));
    }
    void set(PcodeThread<byte[]> thread,String name,long value) {
        var r=currentProgram.getRegister(name);
        thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
    }
}
