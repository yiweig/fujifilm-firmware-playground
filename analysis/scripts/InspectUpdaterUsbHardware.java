// Original updater USB platform helper with synthetic MMIO; no hardware access.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterUsbHardware extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        final List<Long> delays=new ArrayList<>();
        final List<String> sequence=new ArrayList<>();
        Arm(int pll,int clocks){
            bytes(BASE,image);bytes(0x10000000L,new byte[0x6000]);
            bytes(0x4200f000L,new byte[0x2000]);bytes(0x400b5d70L,new byte[96]);
            put(0x400ba450L,pll,4);put(0x400b5d79L,clocks,1);put(0x400b5d83L,clocks,1);
            put(0x10000064L,0xa5a5a5a5L,4);put(0x10000044L,0x5a5a5a5aL,4);
            put(0x10000380L,0xaa,1);put(0x10000383L,0xbb,1);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",0x42010000L);reg("lr",END);
        }
        void bytes(long a,byte[] b){m.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] bytes(long a,int n){return m.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(){reg("r0",0);t.overrideCounter(toAddr(reg("lr")));}
        void run(long entry) throws Exception {
            reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int n=0;n<10000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END)return;
                if(pc==0x4007da98L){ret();continue;} // interrupt-mask state modeled
                if(pc==0x4007a730L){delays.add(reg("r0"));ret();continue;} // no waiting
                if(!((pc>=0x40075dd8L&&pc<0x40075ef0L)||(pc>=0x40066134L&&pc<0x40066240L)||(pc>=0x400662b0L&&pc<0x400662c4L)))
                    throw new Exception("Unexpected PC "+Long.toHexString(pc));
                t.stepInstruction();
                if(Set.of(0x40075e0cL,0x40075e1cL,0x40075e2cL,0x40075e40L,0x40075e50L).contains(pc))sequence.add(String.format("10000380=%02x",val(0x10000380L,1)));
                if(pc==0x40075e58L)sequence.add(String.format("10000383=%02x",val(0x10000383L,1)));
            }
            throw new Exception("Instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(image,bytes(BASE,image.length)))throw new Exception("Executable changed");}
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Offline original 40075ea4/40075dd8/400662b0/40066134 and 40075ed4; synthetic MMIO and counters. Only delay and IRQ helpers modeled. No camera, real clocks/PHY/USB or flash.");
            for(int pll:new int[]{0,2})for(int clocks:new int[]{0,2}){
                Arm a=new Arm(pll,clocks);a.run(0x40075ea4L);
                List<String> expected=List.of("10000380=10","10000380=12","10000380=13","10000380=03","10000380=01","10000383=03");
                if(!a.sequence.equals(pll==0?expected:List.of())||!a.delays.equals(pll==0?List.of(2L,2L,5L,17L,56L,56L):List.of()))throw new Exception("PLL sequence");
                if(a.val(0x400ba450L,4)!=pll+1||a.val(0x400b5d79L,1)!=clocks+1||a.val(0x400b5d83L,1)!=clocks+1||a.val(0x10005021L,1)!=0x3f||a.val(0x10000064L,4)!=(clocks==0?0x80200:0xa5a5a5a5L))throw new Exception("Acquire result");
                if(pll!=0&&(a.val(0x10000380L,1)!=0xaa||a.val(0x10000383L,1)!=0xbb))throw new Exception("Unexpected repeated PLL programming");
                a.unchanged();out.printf("PASS acquire PLL-ref=%d resource-refs=%d -> refs %d/%d, USB+21=3f, sequence=%s, delay-arguments=%s.%n",pll,clocks,pll+1,clocks+1,a.sequence,a.delays);
                a.run(0x40075ed4L);
                if(a.val(0x10005021L,1)!=0||a.val(0x400b5d79L,1)!=clocks||a.val(0x400b5d83L,1)!=clocks||a.val(0x400ba450L,4)!=pll+1||a.val(0x10000044L,4)!=(clocks==0?0x80200:0x5a5a5a5aL))throw new Exception("Companion release result");
                a.unchanged();out.printf("PASS companion resource release -> resource-refs=%d, PLL-ref still=%d, USB+21=00; executable unchanged.%n",clocks,pll+1);
            }
            out.println("8 controls passed. Software effects only; delay units/timing, MMIO electrical semantics, hardware lock, host attachment and recovery are not established.");
        }
    }
}
