// Original termination synchronization paths; IRQ instructions modeled, stop before scheduler.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectTerminationSynchronization extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        byte[] expectedImage;
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
        int modeledIrq=0; final Set<Long> visited=new HashSet<>();
        void snapshot(){expectedImage=bytes(BASE,image.length);}
        void expected(long a,long v,int n){if(a>=BASE&&a+n<=BASE+image.length)System.arraycopy(Utils.longToBytes(v,n,false),0,expectedImage,(int)(a-BASE),n);}
        void fixture(){
            bytes(RECORD,new byte[0x2000]);put(0x40e38118L,7,4);put(0x40e3811cL,0x4008c1d8L,4);
            put(0x40e3807cL,RECORD,4);put(0x40e4af00L,0,4);put(0x40e4aefcL,0,4);snapshot();
        }
        long execute(long entry,long arg) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",arg);t.overrideCounter(toAddr(entry));
            for(int n=0;n<5000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END||pc==0x40049e20L||pc==0x40049fc8L||pc==0x4004a180L)return pc;
                boolean allowed=(pc>=0x40079cdcL&&pc<0x40079d60L)||(pc>=0x4008c1d8L&&pc<0x4008c238L)
                    ||(pc>=0x40079ee0L&&pc<0x4007a1f8L)||(pc>=0x40026e38L&&pc<0x40026ef8L)
                    ||(pc>=0x40003fecL&&pc<0x40003ff8L)||(pc>=0x40057220L&&pc<0x40057278L)
                    ||(pc>=0x40079740L&&pc<0x400797f4L)||(pc>=0x400797fcL&&pc<0x400798b0L);
                if(!allowed)throw new Exception("Unexpected synchronization PC "+Long.toHexString(pc));
                visited.add(pc);long w=val(pc,4);
                // These operations have no host/device effect. Model saved IRQ state as zero;
                // skip IRQ masking/restoration. Do not imply concurrency or real CPSR coverage.
                if((w&0xffff0fffL)==0xe10f0000L){reg("r"+((w>>12)&15),0);modeledIrq++;t.overrideCounter(toAddr(pc+4));continue;}
                if(w==0xf10c0080L||(w&0xfffffff0L)==0xe121f000L){modeledIrq++;t.overrideCounter(toAddr(pc+4));continue;}
                t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Unexpected loaded-image data change");}
    }
    void require(boolean v,String label) throws Exception {if(!v)throw new Exception(label);}
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        require(hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"),"Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("MAIN SHA256 "+hash);
            out.println("Original mutex constructor, termination hook, mutex release, task terminator and queue/event wait cleanup. Synthetic RAM; MRS/CPSID/MSR IRQ operations modeled as saved zero/no-op. Stop before scheduler/resume helpers. No concurrency, physical interrupts or camera execution.");
            for(int existing:new int[]{0,1}){
                Arm a=new Arm();a.fixture();a.put(0x40e3811cL,existing==0?0:RECORD+0x1800,4);
                a.put(0x40e4af00L,existing,4);if(existing!=0){a.put(0x40e4aefcL,RECORD+0x800,4);a.put(RECORD+0x824,RECORD+0x800,4);}
                a.snapshot();a.expected(0x40e3811cL,0x4008c1d8L,4);a.reg("r1",RECORD+0x1a00);a.reg("r2",0);
                require(a.execute(0x40079cdcL,RECORD+0x400)==END&&a.reg("r0")==0,"Mutex constructor");
                require(a.val(0x40e3811cL,4)==0x4008c1d8L&&a.val(RECORD+0x400,4)==0x4d555445L&&a.val(0x40e4af00L,4)==existing+1,"Mutex registration");a.unchanged();
                out.printf("PASS mutex constructor existing=%d -> termination hook=4008c1d8; mutex magic/count set; modeledIRQ=%d%n",existing,a.modeledIrq);
            }
            for(int mode=0;mode<3;mode++){
                Arm a=new Arm();a.fixture();long mutex=RECORD+0x400, waiter=RECORD+0x800;
                if(mode!=0){a.put(RECORD+0x1ac,mutex,4);a.put(RECORD+0x1a8,1,4);a.put(mutex+8,7,4);a.put(mutex+0xc,RECORD,4);}
                if(mode==2){a.put(mutex+0x18,waiter,4);a.put(mutex+0x1c,1,4);a.put(waiter+0x30,6,4);a.put(waiter+0x68,0x4008c100L,4);a.expected(0x40e38118L,9,4);}
                long stop=a.execute(0x4008c1d8L,RECORD);
                require(stop==(mode==2?0x40049e20L:END),"Hook boundary");
                if(mode!=0)require(a.val(RECORD+0x1ac,4)==0&&a.val(RECORD+0x1a8,4)==0,"Owner list detached");
                if(mode==1)require(a.val(mutex+8,4)==0&&a.val(mutex+0xc,4)==0,"Uncontended mutex released");
                if(mode==2)require(a.reg("r0")==waiter&&a.val(mutex+0xc,4)==waiter&&a.val(waiter+0x1ac,4)==mutex&&a.val(waiter+0x68,4)==0&&a.val(mutex+0x1c,4)==0,"Mutex ownership transfer");
                a.unchanged();out.printf("PASS termination hook ownedCase=%d -> stop=%08x counter=%d; modeledIRQ=%d%n",mode,stop,a.val(0x40e38118L,4),a.modeledIrq);
            }
            for(int state:new int[]{0,1,2,5,7}){
                Arm a=new Arm();a.fixture();a.put(RECORD+0x30,state,4);long object=RECORD+0x400;
                if(state==5){a.put(RECORD+0x68,0x40079740L,4);a.put(RECORD+0x6c,object,4);a.put(object,0x51554555L,4);a.put(object+0x28,RECORD,4);a.put(object+0x2c,1,4);}
                if(state==7){a.put(RECORD+0x68,0x400797fcL,4);a.put(RECORD+0x6c,object,4);a.put(object,0x4456444eL,4);a.put(object+0x10,RECORD,4);a.put(object+0x14,1,4);}
                if(state==0)a.expected(0x40e38118L,8,4);
                long stop=a.execute(0x40026e38L,RECORD);
                require(stop==(state==0?0x4004a180L:0x40049fc8L),"Termination boundary");
                require(a.visited.contains(0x4008c1d8L),"Global hook executes");
                require(a.val(RECORD+0x30,4)==(state==1?1:2),"Terminated state");
                if(state==5||state==7){require(a.visited.contains(state==5?0x40079740L:0x400797fcL),"Wait cleanup executes");require(a.val(RECORD+0x68,4)==0&&a.val(object+(state==5?0x2c:0x14),4)==0&&a.val(object+(state==5?0x28:0x10),4)==0,"Wait list detached");}
                a.unchanged();out.printf("PASS original terminator initialState=%d -> stop=%08x global hook executed%s; modeledIRQ=%d%n",state,stop,state>=5?", installed queue/event wait cleanup cleared callback and wait list":"",a.modeledIrq);
            }
            out.println("10 controls passed. Only expected loaded-RAM data changes; source bytes unchanged. No scheduler/resume helper, task worker, physical IRQ/FPU operation, USB/NAND/SD, payload or camera executes. Contended transfer stops before resume and does not complete the hook. Actual installed hooks, mutexes and concurrent side effects remain unobserved.");
        }
    }
}
