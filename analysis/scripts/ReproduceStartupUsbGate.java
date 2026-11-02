// Offline early UI gate, startup flag refresh and application-queue filter controls.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceStartupUsbGate extends GhidraScript {
    static final long UI=0x40e65950L, COLD=0x40e4df02L, END=0x43000000L;
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();int bootPredicate=0, queueCalls=0, earlyCalls=0;
        Arm() {
            mem(0x40000000L,image);mem(UI,new byte[0x1100]);mem(COLD,new byte[1024]);
            mem(0x4200e000L,new byte[0x3000]);t.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(mem(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        void run(long entry,long event,long stop,int kind) throws Exception {
            reg("r0",event);reg("r1",0);reg("r2",0);reg("r3",0);reg("sp",0x42010000L);reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int step=0;step<2000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==stop)return;
                if(kind==1 && (pc==0x4000cd00L || pc==0x40004064L || pc==0x400410ccL || pc==0x4006f3e8L)) {
                    earlyCalls++;ret(0);continue;
                }
                if(kind==1 && pc==0x40045970L){ret(bootPredicate);continue;}
                if(kind==2 && pc==0x4002a3c4L){
                    if(reg("r0")!=0x42020000L || val(reg("r1"),4)!=event || val(reg("r1")+4,4)!=0
                        || val(reg("r1")+8,4)!=0 || val(reg("r1")+12,4)!=0)throw new Exception("Queue payload");
                    queueCalls++;ret(0);continue;
                }
                boolean allowed=kind==0?((pc>=0x40400d50L && pc<0x40402574L)
                    ||(pc>=0x404db31cL && pc<0x404db3dcL)||(pc>=0x404d51b0L && pc<0x404d55bcL)):
                    kind==1?((pc>=0x40517454L && pc<0x405174c4L)||(pc>=0x40125284L && pc<0x401252b0L)):
                    ((pc>=0x4006f330L && pc<0x4006f34cL)||(pc>=0x4006993cL && pc<0x40069a3cL)
                    ||(pc>=0x405b9434L && pc<0x405b946cL));
                if(!allowed)throw new Exception("Unexpected execution "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main-image SHA-256: "+hash);
            out.println("Offline original ARM. Independent supplied-state controls, not a full boot or timing experiment.");
            for(int flag=0;flag<2;flag++)for(long event:new long[]{0xf0000044L,0xf2000000L}) {
                Arm a=new Arm();a.put(UI+0x101d,1,flag);byte[] before=a.mem(UI,0x1100);
                a.run(0x40400d50L,event,END,0);
                if(!Arrays.equals(before,a.mem(UI,0x1100)) || a.earlyCalls!=0)throw new Exception("State-zero UI gate");
                out.printf("PASS UI-state=0 flag=%d event=%08x: full app dispatcher returns, UI unchanged, no modeled callees.%n",flag,event);
            }
            for(int nibble=0;nibble<16;nibble++)for(int old=0;old<2;old++)for(int predicate=0;predicate<2;predicate++) {
                Arm a=new Arm();a.bootPredicate=predicate;a.put(COLD+0x6a,2,0x100|nibble);a.put(UI+0x101d,1,old);
                byte[] before=a.mem(COLD,1024);a.run(0x40517454L,0,0x405174c4L,1);
                if(a.val(UI+0x1008,2)!=1 || a.val(UI+0x101d,1)!=(nibble==8?1:0)
                    || !Arrays.equals(before,a.mem(COLD,1024)))throw new Exception("Startup refresh");
            }
            out.println("PASS 64 startup-prefix controls: 16 saved low nibbles x 2 old flags x 2 boot-predicate results; state becomes 1, flag becomes (nibble==8), supplied cold bytes unchanged.");
            long[] events={0xf0000044L,0xf2000000L,0x6251000eL,0x6251000fL,0x6251001aL,0x6251001bL};
            for(int filter=0;filter<2;filter++)for(long event:events) {
                Arm a=new Arm();a.put(0x40e63314L,4,0x42020000L); // class-1 queue handle
                a.put(0x40e6354cL,4,filter==0?0:0x405b9434L);
                a.run(0x4006f330L,event,END,2);
                int expected=filter!=0 && event!=0xf0000044L && event!=0xf2000000L?0:1;
                if(a.queueCalls!=expected || a.reg("r0")!=0)throw new Exception("Filter/queue result");
                out.printf("PASS filter=%d event=%08x: queue submissions=%d; no UI dispatch.%n",filter,event,a.queueCalls);
            }
            out.println("80 controls passed. No USB mode setter, connection API, filesystem or RTC helper executes.");
            out.println("Modeled: startup clock/log/profile calls, boot predicate, conditional 4006f3e8 side effects and successful RTOS enqueue. Original queue wrapper, known filter, getter, flag assignment and early dispatch execute.");
            out.println("Unknown callbacks, other tasks/interrupts, all intervening initialization, full event-loop timing and live loader/recovery reachability remain unqualified.");
        }
    }
}
