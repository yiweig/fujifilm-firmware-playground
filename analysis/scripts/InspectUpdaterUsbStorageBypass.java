// Original USB continuation and constructor; stop before controller setup.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterUsbStorageBypass extends GhidraScript {
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
        int commands=0,allocations=0,queues=0;
        List<Long> events=new ArrayList<>();
        List<Long> tasks=new ArrayList<>();
        long start(int registry,int flag) throws Exception {
            bytes(0x400a2ea8L,new byte[0x54]);bytes(0x400a3a28L,new byte[16]);
            bytes(0x10005000L,new byte[0x200]);
            put(0x400a3a30L,registry,4);put(0x400a2ea8L,2,1);put(0x10005104L,flag,1);
            t.overrideCounter(toAddr(0x400007f4L));
            for(int n=0;n<2000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x4000ffa0L)return pc;
                if(pc==0x40009ca0L){commands++;ret(1);continue;} // no scheduler or Cmd task executes
                if(pc==0x400110dcL){
                    if(reg("r0")!=0x11b4)throw new Exception("Unexpected task allocation");
                    long ptr=RECORD+allocations++*0x2000;bytes(ptr,new byte[0x11b4]);ret(ptr);continue;
                }
                if(pc==0x4007dab4L){tasks.add(reg("r2"));ret(0);continue;}
                if(pc==0x400825a0L){if(reg("r1")!=1||(reg("r2")!=12&&reg("r2")!=16))throw new Exception("Unexpected queue layout");ret(0x42200000L+0x100*queues++);continue;}
                if(pc==0x4007599cL){events.add(reg("r0"));ret(0);continue;}
                boolean allowed=(pc>=0x400007f4L&&pc<0x40000840L)||(pc>=0x40010c70L&&pc<0x40010ca0L)
                    ||pc==0x4000f1acL||(pc>=0x40012100L&&pc<0x40012134L)
                    ||(pc>=0x40010e68L&&pc<0x40010e84L)||pc==0x4000f028L
                    ||(pc>=0x4000ff24L&&pc<0x4000ff48L)||(pc>=0x4001111cL&&pc<0x40011310L);
                if(!allowed)throw new Exception("Unexpected USB PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("USB instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Original updater USB continuation, target registration and task/queue constructor; stop before 4000ffa0 controller bring-up.");
            out.println("Cmd setup, allocation, task creation, queue creation and event registration modeled. Tasks do not execute. MMIO addresses contain synthetic memory only.");
            for(int registry:new int[]{0,3})for(int flag:new int[]{0,0x10}){
                Arm a=new Arm();long stop=a.start(registry,flag);
                if(stop!=0x4000ffa0L||a.commands!=1||a.allocations!=2||a.queues!=3||!a.tasks.equals(List.of(0x4001a5ecL,0x40010e88L)))throw new Exception("Constructor mismatch");
                if(!a.events.equals(List.of(16L,17L,18L,0L,1L,2L,9L,10L,3L,4L)))throw new Exception("Event registration mismatch");
                if(a.val(0x400a2ea8L,1)!=0||a.val(0x400a2eacL,4)!=0x400115ecL||a.val(0x400a2ebcL,4)!=0x40011634L||a.val(0x400a3a30L,4)!=registry)throw new Exception("Registration mismatch");
                a.unchanged();out.printf("PASS registry-count=%d initial USB byte=%02x -> target=0 initializer=400115ec receiver=40011634 task-entries=%s queues=3 events=10 boundary=%08x; registry unchanged.%n",registry,flag,a.tasks,stop);
            }
            out.println("4 controls passed. Direct supplied entry at 400007f4, not normal startup or demonstrated means to reach it. No controller, task body, USB enumeration, NAND/SD I/O or camera executes.");
        }
    }
}
