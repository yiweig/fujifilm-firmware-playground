// Original ARM gate tests, offline only. Not an unplug/replug lifecycle test.
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

public class ReproduceUsbReentryGates extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    static final int LENGTH=0x01400000;
    public void run() throws Exception {
        byte[] image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Shooting: supplied USB_IN f0000044 via application dispatcher, state 2000, no active Wi-Fi/UI cleanup.");
            out.println("Playback: interior gate slice 404d9a6c, synthetic state 1006; preceding cleanup/guards NOT executed.");
            out.println("Only printf 40004064 and delay 4006a074 modeled as returns; stop BEFORE first selected downstream call.");
            for(boolean playback:new boolean[]{false,true})for(int flag=0;flag<2;flag++)for(int coldLow:new int[]{0x14,0x18})
                check(out,image,playback,flag,coldLow,1);
            for(int flag=0;flag<2;flag++)check(out,image,false,flag,0x14,0);
            out.println("No USB connection, filesystem, COLD getter/refresh, peripheral configuration, mode setter or loader executed.");
            out.println("These independent gate fixtures do not prove staged settings survive USB exit or qualify a live activation route.");
        }
    }
    void check(PrintWriter out,byte[] image,boolean playback,int flag,int coldLow,int present) throws Exception {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        machine.getSharedState().setVar(ram,BASE,LENGTH,true,new byte[LENGTH]);
        machine.getSharedState().setVar(ram,BASE,image.length,true,image);
        machine.getSharedState().setVar(ram,SP-0x4000,0x8000,true,new byte[0x8000]);
        put(machine,ram,0x40e66958L,playback?0x1006:0x2000,2);
        put(machine,ram,0x40e6696dL,flag,1);
        put(machine,ram,0x40e7ab8cL,present<<5,1);
        put(machine,ram,0x413cb560L,0,1); // prior-mode structure +b68, no Wi-Fi guard
        put(machine,ram,0x413ca9faL,0,1); // skip optional UI cleanup, no device-state claim
        byte[] cold=new byte[1024];for(int i=0;i<cold.length;i++)cold[i]=(byte)(i*37+11);
        cold[0x6a]=(byte)coldLow;cold[0x6b]=1;
        machine.getSharedState().setVar(ram,0x40e4df02L,1024,true,cold);
        byte[] before=machine.getSharedState().getVar(ram,BASE,LENGTH,true,Reason.INSPECT);
        PcodeThread<byte[]> thread=machine.newThread();thread.overrideContextWithDefault();
        for(int i=0;i<13;i++)set(thread,"r"+i,0);
        for(String n:new String[]{"CY","OV","ZR","NG"})set(thread,n,0);
        set(thread,"r0",0xf0000044L);set(thread,"sp",SP);set(thread,"lr",END);
        thread.overrideCounter(toAddr(playback?0x404d9a6cL:0x40400d50L));
        long expected=present==0?END:(flag==0?0x405cbb84L:0x4000f9d0L);
        boolean reached=false;int logs=0,delays=0;
        for(int steps=0;steps<6000;steps++) {
            monitor.checkCancelled();long pc=thread.getCounter().getOffset();
            if(pc==expected) {reached=true;break;}
            if(pc==0x405cbb84L || pc==0x4000f9d0L || pc==END)
                throw new Exception("Wrong branch at "+Long.toHexString(pc));
            if(pc==0x40004064L || pc==0x4006a074L) {
                if(pc==0x40004064L)logs++;else delays++;
                set(thread,"r0",0);
                var lr=currentProgram.getRegister("lr");
                thread.overrideCounter(toAddr(Utils.bytesToLong(thread.getState().getVar(lr,Reason.INSPECT),4,false)));
                continue;
            }
            if(!((pc>=0x40400d50L && pc<0x40402574L) ||
                 (pc>=0x404db31cL && pc<0x404db3dcL) ||
                 (pc>=0x404d7e7cL && pc<0x404d8f98L) ||
                 (pc>=0x404d9a6cL && pc<0x404d9ad8L) ||
                 (pc>=0x404d9c70L && pc<0x404d9c78L) ||
                 (pc>=0x40123bf8L && pc<0x40123c08L) ||
                 (pc>=0x40530b50L && pc<0x40530b58L)))
                throw new Exception("Unexpected execution at "+Long.toHexString(pc));
            thread.stepInstruction();
        }
        if(!reached)throw new Exception("Instruction limit");
        if(!Arrays.equals(before,machine.getSharedState().getVar(ram,BASE,LENGTH,true,Reason.INSPECT)))
            throw new Exception("Image/global RAM changed");
        out.printf("PASS: %s flag=%d FgUsbMode=01%02x cached-presence=%d -> %08x; image/globals unchanged; logs=%d delays=%d.%n",
            playback?"playback gate slice":"shooting USB_IN",flag,coldLow,present,expected,logs,delays);
        out.flush();
    }
    void put(PcodeEmulator machine,AddressSpace ram,long address,long value,int size) {
        machine.getSharedState().setVar(ram,address,size,true,Utils.longToBytes(value,size,false));
    }
    void set(PcodeThread<byte[]> thread,String name,long value) {
        var r=currentProgram.getRegister(name);
        thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
    }
}
