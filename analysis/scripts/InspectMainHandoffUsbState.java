// Main-image handoff preparation slices; synthetic MMIO, stop before MMU/copy work.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectMainHandoffUsbState extends GhidraScript {
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
        void fixture(int resource,int usb) {
            bytes(0x10000000L,new byte[0x6000]);bytes(0x40e48e2dL,new byte[96]);
            put(0x10004071L,0xab,1);put(0x40e4ae94L,0x1234,4);put(0x10004054L,8,2);
            put(0x40e48e2dL+17,resource,1);put(0x40e48e2dL+9,1,1);put(0x40e48e2dL+19,1,1);put(0x40e4b220L,1,4);
            bytes(0x10000380L,new byte[]{1,2,3,4,5,6,7,8});put(0x10005021L,0x3f,1);put(0x10005104L,usb,1);
            put(0x10000064L,0xa5a5a5a5L,4);put(0x10000044L,0x5a5a5a5aL,4);
        }
        long execute(long entry) throws Exception {
            reg("sp",SP);reg("lr",END);put(SP,2,4);reg("r0",entry==0x40055e88L?1:0x41000000L);
            reg("r1",0x42000000L);reg("r2",0x1000);reg("r3",0x40000000L);t.overrideCounter(toAddr(entry));
            for(int n=0;n<2000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==0x4000186cL||pc==END)return pc;
                if(pc==0x40004064L||pc==0x4000c30cL){ret(0);continue;}
                boolean allowed=(pc>=0x401eb1ecL&&pc<0x401eb300L)||(pc>=0x40046ca0L&&pc<0x40046cc8L)
                    ||(pc>=0x4001d45cL&&pc<0x4001d468L)||(pc>=0x40057dccL&&pc<0x40057dd8L)
                    ||(pc>=0x40046c44L&&pc<0x40046c64L)||(pc>=0x4000b608L&&pc<0x4000b61cL)
                    ||(pc>=0x4000b48cL&&pc<0x4000b598L)||(pc>=0x40055e88L&&pc<0x40055eacL);
                if(!allowed)throw new Exception("Unexpected main handoff PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Handoff instruction bound");
        }
        void preserved() throws Exception {
            if(!Arrays.equals(new byte[]{1,2,3,4,5,6,7,8},bytes(0x10000380L,8))||val(0x10005021L,1)!=0x3f
                ||val(0x40e48e2dL+9,1)!=1||val(0x40e48e2dL+19,1)!=1||val(0x40e4b220L,4)!=1)throw new Exception("USB PHY/resource state changed");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Main image SHA256 "+hash);
            out.println("Original main handoff prefix -> stop before 4000186c MMU helper; separate later USB-control helper slice. Logging and IRQ helper modeled. Synthetic MMIO and counters only.");
            for(int count:new int[]{0,2})for(int usb:new int[]{8,255}){
                Arm a=new Arm();a.fixture(count,usb);
                if(a.execute(0x401eb1ecL)!=0x4000186cL||a.val(0x10004071L,1)!=3||a.val(0x10004070L,1)!=0x34||a.val(0x1000407bL,1)!=2||a.val(0x40e48e2dL+17,1)!=count+1)throw new Exception("Prefix control mismatch");
                if(a.val(0x10000064L,4)!=(count==0?0x20000:0xa5a5a5a5L)||a.val(0x10000044L,4)!=0x5a5a5a5aL)throw new Exception("Resource command mismatch");
                a.preserved();a.unchanged();
                if(a.execute(0x40055e88L)!=END||a.val(0x10005104L,1)!=((usb&0x8f)|0x30))throw new Exception("USB control byte mismatch");
                a.preserved();a.unchanged();out.printf("PASS resource17 initial=%d -> %d; prefix writes boot fields 03/34/02, USB refs/PHY preserved; separate USB+104 %02x -> %02x.%n",count,count+1,usb,a.val(0x10005104L,1));
            }
            out.println("4 controls passed. Two separate slices, not full handoff. No actual IRQ/MMU/cache shutdown, DMA, loader callback, copied payload, low helper, USB hardware, flash or camera executes. Initial enabled USB state is supplied, not observed.");
        }
    }
}
