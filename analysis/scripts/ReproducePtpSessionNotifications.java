// Execute session notifications and refresh candidates in ordinary USB UI state.
// Queue delivery itself is supplied; only printf is stubbed. No camera access.
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

public class ReproducePtpSessionNotifications extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    static final int LENGTH=0x01400000;
    public void run() throws Exception {
        byte[] image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Synthetic UI state 8000, staged cold RAM, both USB flag values; supplied delivery; only log function 40004064 stubbed.");
            for(long event:new long[]{0x62510018L,0x62510019L,0xf0000071L,0xf2000084L,0xf2000000L,0xf0000045L})for(int flag=0;flag<2;flag++)for(int present=0;present<(event==0xf0000045L?2:1);present++) {
                int cachedPresence=event==0xf0000045L?present:1;
                PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
                AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
                machine.getSharedState().setVar(ram,BASE,LENGTH,true,new byte[LENGTH]);
                machine.getSharedState().setVar(ram,BASE,image.length,true,image);
                machine.getSharedState().setVar(ram,SP-0x4000,0x8000,true,new byte[0x8000]);
                machine.getSharedState().setVar(ram,0x40e66958L,2,true,Utils.longToBytes(0x8000,2,false));
                machine.getSharedState().setVar(ram,0x40e6696dL,1,true,new byte[]{(byte)flag});
                machine.getSharedState().setVar(ram,0x40e7ab8cL,1,true,new byte[]{(byte)(cachedPresence<<5)});
                machine.getSharedState().setVar(ram,0x40e6696bL,1,true,new byte[]{1});
                byte[] cold=new byte[1024];
                for(int i=0;i<cold.length;i++)cold[i]=(byte)(i*37+11);
                cold[0x6a]=0x14;cold[0x6b]=1;
                machine.getSharedState().setVar(ram,0x40e4df02L,1024,true,cold);
                byte[] before=machine.getSharedState().getVar(ram,BASE,LENGTH,true,Reason.INSPECT);
                PcodeThread<byte[]> thread=machine.newThread();thread.overrideContextWithDefault();
                for(int i=0;i<13;i++)set(thread,"r"+i,0);
                for(String n:new String[]{"CY","OV","ZR","NG"})set(thread,n,0);
                set(thread,"r0",event);set(thread,"sp",SP);set(thread,"lr",END);
                thread.overrideCounter(toAddr(0x40400d50L));
                int logs=0;boolean returned=false,teardownBoundary=false;
                for(int steps=0;steps<6000;steps++) {
                    monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                    if(pc==END) {returned=true;break;}
                    if(pc==0x4038d764L && event==0xf0000045L && present==0) {
                        var r0=currentProgram.getRegister("r0");
                        if(Utils.bytesToLong(thread.getState().getVar(r0,Reason.INSPECT),4,false)!=0)
                            throw new Exception("Wrong USB disconnect argument");
                        teardownBoundary=true;break;
                    }
                    if(pc==0x40004064L) {
                        logs++;set(thread,"r0",0);
                        var lr=currentProgram.getRegister("lr");
                        long target=Utils.bytesToLong(thread.getState().getVar(lr,Reason.INSPECT),4,false);
                        thread.overrideCounter(toAddr(target));continue;
                    }
                    if(!((pc>=0x40400d50L && pc<0x40402574L) ||
                         (pc>=0x404db31cL && pc<0x404db3dcL) ||
                         (pc>=0x404d665cL && pc<0x404d6f90L) ||
                         (pc>=0x40123bf8L && pc<0x40123c08L) ||
                         (pc>=0x405c9718L && pc<0x405c9b0cL)))
                        throw new Exception("Unexpected execution at "+Long.toHexString(pc)+
                            " event="+Long.toHexString(event)+" flag="+flag);
                    thread.stepInstruction();
                }
                if(!returned && !teardownBoundary)throw new Exception("Instruction limit");
                byte[] after=machine.getSharedState().getVar(ram,BASE,LENGTH,true,Reason.INSPECT);
                if(!Arrays.equals(before,after))throw new Exception("Image/global RAM changed");
                if(teardownBoundary)
                    out.printf("PASS: USB-out flag=%d cached-presence=0 stops before 4038d764(0); state, flag, cold RAM and all image/globals unchanged; log calls=%d.%n",flag,logs);
                else
                    out.printf("PASS: event=%08x flag=%d cached-presence=%d returned; log calls=%d; image/global RAM unchanged over 40000000..413fffff.%n",event,flag,cachedPresence,logs);
                out.flush();
            }
            out.println("Excludes queue/filter execution, diagnostic wrappers, other UI substates, peripherals and physical session/USB lifetime.");
            out.println("USB-out disconnected cases stop before disconnect API; teardown and re-entry are not modeled as successful returns.");
        }
    }
    void set(PcodeThread<byte[]> thread,String name,long value) {
        var r=currentProgram.getRegister(name);
        thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
    }
}
