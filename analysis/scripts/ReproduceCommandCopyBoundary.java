// Offline original command-wrapper copy; stop before shell parsing/dispatch.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceCommandCopyBoundary extends GhidraScript {
    byte[] image;
    final long SP=0x42010000L, SOURCE=0x43010000L, RETURN=0x4300007cL;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        Arm(byte[] text) {
            mem(0x40000000L,image);mem(SOURCE,text);byte[] stack=new byte[0x3000];Arrays.fill(stack,(byte)0x55);mem(SP-0x2000,stack);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",SP);reg("lr",RETURN);reg("r0",SOURCE);t.overrideCounter(toAddr(0x401efd50L));
        }
        void runSlice() throws Exception {
            for(int step=0;step<2000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==0x401efaccL)return;
                if(!((pc>=0x401efd50L&&pc<0x401efd6cL)||(pc>=0x40008d10L&&pc<0x40008d28L)))throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main SHA-256: "+hash);
            out.println("Original command wrapper 401efd50 and copy helper 40008d10; stop before parser 401efacc. Synthetic dummy strings, no command executes.");
            for(int n:new int[]{0,7,131,132}) {
                byte[] text=new byte[n+1];Arrays.fill(text,0,n,(byte)'X');Arm a=new Arm(text);a.runSlice();
                byte[] saved=Utils.longToBytes(RETURN,4,false);if(n==132)saved[0]=0;
                if(a.reg("r0")!=SP-136||!Arrays.equals(text,a.mem(SP-136,text.length))||!Arrays.equals(saved,a.mem(SP-4,4)))throw new Exception("Copy boundary mismatch");
                if(!Arrays.equals(image,a.mem(0x40000000L,image.length)))throw new Exception("Executable changed");
                for(byte v:a.mem(SP-152,16))if(v!=0x55)throw new Exception("Lower guard changed");
                for(byte v:a.mem(SP,16))if(v!=0x55)throw new Exception("Upper guard changed");
                out.printf("PASS dummy text length=%d: complete NUL-terminated copy verified; saved return slot %s; stopped before parser; image unchanged.%n",n,n==132?"changed by terminating NUL":"unchanged");
            }
            out.println("4 controls passed. No helpers modeled, no file operations, no shell parser, command handler, corrupted return or camera access executes.");
            int outside=0,lowMov=0,pointers=0;long start=0x40475d8cL,end=0x40475fe0L;
            for(int p=0;p+4<=image.length;p+=4){
                long w=Utils.bytesToLong(Arrays.copyOfRange(image,p,p+4),4,false),a=0x40000000L+p;
                if(w==start||w==start+1)pointers++;
                if((w&0x0e000000L)==0x0a000000L&&(w>>>28)!=15){
                    int off=(int)(w&0xffffff);if((off&0x800000)!=0)off-=0x1000000;
                    long target=(a+8+4L*off)&0xffffffffL;
                    if(target>=start&&target<end&&(a<start||a>=end))outside++;
                }
                if((w&0x0ff00000L)==0x03000000L){long imm=((w>>>4)&0xf000)|(w&0xfff);if(imm==0x5d8c||imm==0x5d8d||imm==0x5d90)lowMov++;}
            }
            out.printf("Whole-image bounded raw scan: outside aligned ARM B/BL candidates into reader body=%d; aligned entry/entry+1 pointer words=%d; MOVW low-address candidates=%d.%n",outside,pointers,lowMov);
            out.println("Negative scans do not exclude computed/interior pointers, Thumb calls, transformed tables, earlier stages or other indirect reachability. No retail SD autoexec entry established.");
        }
    }
}
