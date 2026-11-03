// Original reset message exchange, manually delivered; no RTOS or hardware.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterUsbResetExchange extends GhidraScript {
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
        int allocations=0;List<Long> released=new ArrayList<>();List<Long> sentPointers=new ArrayList<>();
        List<String> sent=new ArrayList<>();
        void fixture(boolean oldScratch,int target) {
            bytes(0x400a39ccL,new byte[4]);bytes(0x400a2ea8L,new byte[0x80]);
            bytes(0x400a2cf8L,new byte[0x1b0]);bytes(0x400a2efcL,new byte[0x40]);
            bytes(0x400ba378L,new byte[0x20]);bytes(0x10005000L,new byte[0x400]);
            put(0x400a2ea8L,target,1);put(0x400a2eacL,0x400115ecL,4);
            put(0x400a2eacL+8,0x43001000L,4); // target 2 initializer sentinel
            put(0x400a2ee4L,0x42200000L,4);put(0x400a2ee8L,0x42200100L,4);
            if(oldScratch)put(0x400a2efcL,RECORD+0x2000,4);
        }
        long invoke(long entry,long message) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",message);t.overrideCounter(toAddr(entry));
            for(int n=0;n<3000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END||pc==0x43001000L)return pc;
                if(pc==0x4007ac04L){logs++;ret(0);continue;}
                if(pc==0x400110dcL){if(reg("r0")!=12)throw new Exception("Unexpected message allocation");long p=RECORD+0x100+allocations++*0x100;bytes(p,new byte[12]);ret(p);continue;}
                if(pc==0x400826b4L){long p=val(reg("r1"),4),q=reg("r0");sent.add(String.format("queue=%08x type=%x",q,val(p,2)));sentPointers.add(p);ret(0);continue;}
                if(pc==0x4007f830L){released.add(reg("r0"));ret(0);continue;}
                boolean allowed=(pc>=0x4001a338L&&pc<0x4001a594L+4)||(pc>=0x40010cc0L&&pc<0x40010e3cL)
                    ||(pc>=0x400115ecL&&pc<0x40011628L)||(pc>=0x40011378L&&pc<0x400113a8L)
                    ||(pc>=0x40011520L&&pc<0x40011568L)||(pc>=0x40010f60L&&pc<0x40010ff4L)
                    ||(pc>=0x40010aa8L&&pc<0x40010bf4L)||(pc>=0x40010758L&&pc<0x40010790L)
                    ||(pc>=0x400107a4L&&pc<0x40010820L)||(pc>=0x40075470L&&pc<0x40075484L)
                    ||(pc>=0x4007574cL&&pc<0x40075764L)||(pc>=0x40074610L&&pc<0x40074640L)
                    ||(pc>=0x40075768L&&pc<0x40075778L);
                if(!allowed)throw new Exception("Unexpected reset exchange PC "+Long.toHexString(pc)+" LR="+Long.toHexString(reg("lr"))+" r2="+Long.toHexString(reg("r2")));t.stepInstruction();
            }
            throw new Exception("Reset exchange instruction bound");
        }
        void sourceBytes() throws Exception {
            byte[] expected=image.clone();expected[0x9dd84]=2;
            if(!Arrays.equals(expected,bytes(BASE,image.length)))throw new Exception("Unexpected loaded-byte change outside declared receiver-state byte");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Original control/target dispatch and download initializer; messages manually delivered, not a scheduler. Queue/allocation/free/log helpers modeled; MMIO is synthetic memory.");
            out.println("One original loaded-data byte 4009dd84 changes from receiver state 1 to 2; all other loaded bytes must stay unchanged. Source file is untouched.");
            for(boolean scratch:new boolean[]{false,true}){
                Arm a=new Arm();a.fixture(scratch,0);a.put(RECORD,0,2);
                if(a.invoke(0x4001a338L,RECORD)!=END||a.val(0x400a39ccL,1)!=1||a.sent.size()!=1)throw new Exception("Control reset did not gate");
                long reset=a.sentPointers.get(0);
                if(a.invoke(0x40010cc0L,reset)!=END||a.val(0x400a39ccL,1)!=1||a.val(0x4009dd84L,1)!=2||a.val(0x400a2efcL,4)!=0||a.sent.size()!=2)throw new Exception("Target reset mismatch");
                long ack=a.sentPointers.get(1);
                if(a.invoke(0x4001a338L,ack)!=END||a.val(0x400a39ccL,1)!=0||a.val(0x400ba388L,1)!=1||a.val(0x10005008L,1)!=7||a.val(0x1000500aL,1)!=7||a.val(0x1000500bL,1)!=0x27)throw new Exception("Reset acknowledgement mismatch");
                if(!a.sent.equals(List.of("queue=42200100 type=0","queue=42200000 type=1"))||a.allocations!=2||!a.released.equals(scratch?List.of(RECORD+0x2000):List.of()))throw new Exception("Exchange effects mismatch");
                a.sourceBytes();out.printf("PASS full reset exchange old-scratch=%s -> control gate 1 then 0; receiver=2; posts=%s frees=%s; no storage calls.%n",scratch,a.sent,a.released);
            }
            Arm gated=new Arm();gated.fixture(false,0);gated.put(0x400a39ccL,1,1);gated.put(RECORD,4,2);
            if(gated.invoke(0x4001a338L,RECORD)!=END||gated.allocations!=0||!gated.sent.isEmpty()||gated.logs!=0)throw new Exception("Gated setup request not ignored");gated.unchanged();
            out.println("PASS type-4 setup request while control gate=1 -> return without parsing setup or posting target work.");
            Arm other=new Arm();other.fixture(false,2);other.put(RECORD,0,2);
            if(other.invoke(0x40010cc0L,RECORD)!=0x43001000L||!other.sent.isEmpty())throw new Exception("Selected initializer mismatch");other.unchanged();
            out.println("PASS target=2 -> supplied target-2 initializer boundary, stopped before callback; target-0 initializer never executes.");
            out.println("4 controls passed. Explicit serial delivery and supplied state; no real queues, scheduling, task loops, controller readiness, USB enumeration, NAND/SD I/O, safe live entry or recovery established.");
        }
    }
}
