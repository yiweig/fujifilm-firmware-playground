// Original DCL reset and callback-registration slices; synthetic MMIO only.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterUsbResetCallbacks extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        final List<String> posts=new ArrayList<>();int reads=0,logs=0;byte[] expectedImage;
        Arm(){
            bytes(BASE,image);bytes(SP-256,new byte[512]);bytes(RECORD,new byte[256]);
            bytes(0x400a39d0L,new byte[96]);bytes(0x400a3a64L,new byte[8]);
            
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
        int removes=0,allocations=0,interrupts=0,locks=0;
        List<String> sent=new ArrayList<>();
        void reset(boolean full) throws Exception {
            bytes(0x400a2cdcL,new byte[32]);bytes(0x400a2edcL,new byte[32]);bytes(0x400ba378L,new byte[0x100]);bytes(0x10005000L,new byte[0x400]);
            put(0x400a2cdcL,0x42200000L,4);put(0x400a2ee4L,0x42200100L,4);
            reg("r0",0);t.overrideCounter(toAddr(0x4000ffa0L));
            for(int n=0;n<2000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END)return;
                if(pc==0x40083560L){ret(0);continue;} // logs only
                if(pc==0x40082788L){ret(0);continue;} // queue flush/reset modeled
                if(pc==0x400826b4L){long q=reg("r0"),p=reg("r1"),type=val(p,q==0x42200000L?4:2);sent.add(String.format("queue=%08x type=%x",q,type));if(full&&sent.size()==1)ret(11);else ret(0);continue;}
                if(pc==0x400826bcL){removes++;ret(0);continue;}
                if(pc==0x400110dcL){if(reg("r0")!=12)throw new Exception("Unexpected reset allocation");allocations++;bytes(RECORD,new byte[12]);ret(RECORD);continue;}
                boolean allowed=(pc>=0x4000ffa0L&&pc<0x40010034L)||(pc>=0x4007574cL&&pc<0x40075764L)
                    ||(pc>=0x400744d8L&&pc<0x40074544L)||(pc>=0x4000fe84L&&pc<0x4000fed8L)
                    ||(pc>=0x40011520L&&pc<0x40011568L)||(pc>=0x40010f60L&&pc<0x40010ff4L);
                if(!allowed)throw new Exception("Unexpected reset PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Reset instruction bound");
        }
        void register(int count,boolean occupied) throws Exception {
            bytes(0x400ba38cL,new byte[0x100]);bytes(0x10005000L,new byte[0x200]);
            put(0x400ba38cL,0x43002000L,4);put(0x400ba38cL+0xbc,count,4);
            if(occupied)put(0x400ba38cL+8,0x43001000L,4);
            reg("r0",0);reg("r1",0x400100b0L);t.overrideCounter(toAddr(0x4007599cL));
            for(int n=0;n<1000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END)return;
                if(pc==0x4007da98L){locks++;ret(0x80);continue;}
                if(pc==0x4007f288L){if(reg("r0")!=0x83||reg("r1")!=0x400757b0L)throw new Exception("IRQ registration mismatch");interrupts++;ret(0);continue;}
                if(pc<0x4007599cL||pc>=0x40075b38L)throw new Exception("Unexpected registrar PC");t.stepInstruction();
            }
            throw new Exception("Registrar instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Offline original DCL bus reset and event registrar. MMIO is synthetic memory. Queue/log/allocation/critical-section/IRQ-registration helpers modeled; no task or interrupt executes.");
            for(boolean full:new boolean[]{false,true}){
                Arm a=new Arm();a.reset(full);
                List<String> wanted=full?List.of("queue=42200000 type=3","queue=42200000 type=3","queue=42200100 type=0"):List.of("queue=42200000 type=3","queue=42200100 type=0");
                if(!a.sent.equals(wanted)||a.removes!=(full?1:0)||a.allocations!=1||a.val(RECORD,2)!=0||a.val(0x400ba384L,4)!=1||a.val(0x400a2ce4L,4)!=0x400a2ce8L)throw new Exception("Reset control mismatch");
                a.unchanged();out.printf("PASS bus reset queue-full=%s posts=%s remove=%d; returned without backend calls.%n",full,a.sent,a.removes);
            }
            for(int mode=0;mode<3;mode++){
                int count=mode==0?0:3;boolean occupied=mode==2;Arm a=new Arm();a.register(count,occupied);
                long want=occupied?0x43001000L:0x400100b0L;
                if(a.val(0x400ba394L,4)!=want||a.val(0x400ba448L,4)!=(occupied?count:count+1)||a.interrupts!=(mode==0?1:0)||a.locks!=2||a.reg("r0")!=(occupied?0x43001000L:0))throw new Exception("Registrar control mismatch");
                if(a.val(0x400ba38cL,4)!=(mode==0?0x400757e4L:0x43002000L))throw new Exception("Top dispatcher mismatch");
                if(occupied&&(a.val(0x10005000L,1)!=0||a.val(0x10005008L,1)!=0))throw new Exception("Occupied slot touched masks");
                a.unchanged();out.printf("PASS event0 count=%d occupied=%s -> slot=%08x returned=%08x IRQ-registrations=%d; callbacks never execute.%n",count,occupied,want,a.reg("r0"),a.interrupts);
            }
            out.println("5 controls passed. No controller readiness, real queue semantics, concurrent tasks, physical USB, NAND/SD I/O, safe live entry or recovery established.");
        }
    }
}
