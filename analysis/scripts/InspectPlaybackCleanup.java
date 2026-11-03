// Original playback readiness/task-close/cleanup paths; RTOS boundaries modeled or stopped.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectPlaybackCleanup extends GhidraScript {
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
        final List<String> events=new ArrayList<>();
        int deleteIndex=0,failIndex=-1;boolean stopKernel=false;
        void expected(long a,long v,int n){if(a>=BASE&&a+n<=BASE+image.length)System.arraycopy(Utils.longToBytes(v,n,false),0,expectedImage,(int)(a-BASE),n);}
        void snapshot(){expectedImage=bytes(BASE,image.length);}
        void ready(int bad){for(int i=0;i<5;i++)put(STATES[i]+8,i==bad?0:CODES[i],4);}
        void fixture(boolean resources){
            ready(-1);
            for(int i=0;i<5;i++)put(DESCS[i]+4,RECORD+0x200+i*0x200,4);
            bytes(0x40d74284L,new byte[0x284]);bytes(0x40fd05d8L,new byte[0x110]);
            bytes(0x40fc7800L,new byte[0x60]);bytes(0x40fd0754L,new byte[0x20]);
            bytes(0x40fd0774L,new byte[0x30]);bytes(0x40fd07a4L,new byte[0x20]);
            bytes(0x40fa5894L,new byte[8]);ready(-1);
            put(0x40d74504L,0x7f,1);
            if(resources){
                put(0x40d7429cL,RECORD+0xd00,4);put(RECORD+0xe54,RECORD+0xe00,4);put(0x40d742a0L,0x55,4);
                int resourceIndex=0;
                for(long a:new long[]{0x40fd05d8L,0x40fd05dcL,0x40fd06e4L,0x40fc7838L,0x40fd076cL,0x40fd07a0L,0x40fd07bcL,0x40fc7858L,0x40fa5894L})put(a,RECORD+0x2000+(resourceIndex++)*0x100,4);
            }
            snapshot();
        }
        void expectCloses(){for(int i=0;i<5;i++)if(i!=failIndex)expected(DESCS[i]+4,0,4);}
        long execute(long entry,long arg) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",arg);t.overrideCounter(toAddr(entry));
            for(int n=0;n<5000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END||pc==0x4000c450L||pc==RECORD+0xf00||pc==0x40026e5cL)return pc;
                if(pc==0x40004064L){events.add("log");ret(0);continue;}
                if(pc==0x40026e38L&&!stopKernel){events.add("terminate:"+Long.toHexString(reg("r0")));ret(0);continue;}
                if(pc==0x40026ca0L){int code=deleteIndex++==failIndex?17:0;events.add("delete:"+code);ret(code);continue;}
                if(pc==0x40020eccL){events.add("free:"+Long.toHexString(reg("r0")));ret(0);continue;}
                if(pc==0x400364c0L||pc==0x4002a444L){events.add("release:"+Long.toHexString(reg("r0")));ret(0);continue;}
                boolean allowed=(pc>=0x401deb70L&&pc<0x401debdcL)||(pc>=0x401d8648L&&pc<0x401d86b0L)
                    ||(pc>=0x401d8480L&&pc<0x401d84ecL)||(pc>=0x401a519cL&&pc<0x401a521cL)
                    ||(pc>=0x401d46bcL&&pc<0x401d470cL)||(pc>=0x401b61e8L&&pc<0x401b61fcL)
                    ||(pc>=0x401d6940L&&pc<0x401d6964L)||(pc>=0x401d6cc4L&&pc<0x401d6cd8L)
                    ||(pc>=0x401d8438L&&pc<0x401d844cL)||(pc>=0x401b6844L&&pc<0x401b686cL)
                    ||(pc>=0x40026e38L&&pc<0x40026e5cL)||(pc>=0x40003fecL&&pc<0x40003ff8L);
                for(long getter:GETTERS)allowed|=(pc==getter||pc==getter+4);
                for(long getter:DESCGETTERS)allowed|=(pc==getter||pc==getter+4);
                if(!allowed)throw new Exception("Unexpected cleanup PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Unexpected loaded-image change");}
    }
    final long[] STATES={0x40fd06f4L,0x40fc781cL,0x40fd0758L,0x40fd078cL,0x40fd07acL};
    final long[] CODES={0x502,0x900,0x602,0x800,0x700};
    final long[] GETTERS={0x401d3e1cL,0x401b6188L,0x401d6418L,0x401d6ac8L,0x401d6e10L};
    final long[] DESCS={0x40d79cf0L,0x40d79d14L,0x40d79d38L,0x40d79d5cL,0x40d79d80L};
    final long[] DESCGETTERS={0x401e6870L,0x401e689cL,0x401e68c8L,0x401e68f4L,0x401e6924L};
    void require(boolean v,String label) throws Exception {if(!v)throw new Exception(label);}
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        require(hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"),"Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("MAIN SHA256 "+hash);
            out.println("Original playback readiness, task-close and shared cleanup instructions. Synthetic RAM. RTOS terminate/delete, allocator and object release modeled; logging modeled. Separate termination-prefix controls stop before runtime callback or privileged kernel continuation.");
            for(int bad=-1;bad<5;bad++){
                Arm a=new Arm();a.ready(bad);a.snapshot();require(a.execute(0x401deb70L,0)==END&&a.reg("r0")==(bad<0?1:0),"Readiness "+bad);a.unchanged();
                out.printf("PASS readiness badIndex=%d -> %d%n",bad,a.reg("r0"));
            }
            for(int result:new int[]{0,17}){
                Arm a=new Arm();a.fixture(false);a.failIndex=result==0?-1:0;
                if(result==0)a.expected(DESCS[2]+4,0,4);
                require(a.execute(0x401d8480L,DESCS[2])==END,"Single close return");
                require(a.reg("r0")== (result==0?0:0x87000203L),"Single close error code");
                require(a.val(DESCS[2]+4,4)==(result==0?0:RECORD+0x600),"Single close retained pointer");a.unchanged();
                out.printf("PASS single QVLOAD close deleteResult=%d -> return=%08x pointer=%08x events=%s%n",result,a.reg("r0"),a.val(DESCS[2]+4,4),a.events);
            }
            for(int fail:new int[]{-1,2}){
                Arm a=new Arm();a.fixture(false);a.failIndex=fail;a.expectCloses();
                require(a.execute(0x401d8648L,1)==END&&a.reg("r0")==0&&a.deleteIndex==5,"Batch close");
                for(int i=0;i<5;i++)require(a.val(DESCS[i]+4,4)==(i==fail?RECORD+0x200+i*0x200:0),"Close pointer");a.unchanged();
                out.printf("PASS batch close failIndex=%d -> return 0; five deletes attempted; events=%s%n",fail,a.events);
            }
            for(int wait:new int[]{0,1}){
                Arm a=new Arm();a.ready(0);a.snapshot();long stop=a.execute(0x401d8648L,wait);
                require(stop==(wait==0?END:0x4000c450L),"Not-ready close");require(wait==0?a.reg("r0")==1:a.reg("r0")==10,"Not-ready arguments");a.unchanged();
                out.printf("PASS not-ready close wait=%d -> stop=%08x r0=%d; no real delay%n",wait,stop,a.reg("r0"));
            }
            for(boolean resources:new boolean[]{false,true}){
                Arm a=new Arm();a.fixture(resources);a.failIndex=resources?2:-1;a.expectCloses();
                a.expected(0x40d74504L,0,1);if(resources){a.expected(0x40d7429cL,0,4);a.expected(0x40d742a0L,0,4);}
                require(a.execute(0x401a519cL,0)==END&&a.reg("r0")==0&&a.deleteIndex==5,"Shared cleanup");
                require(a.val(0x40d74504L,1)==0&&a.val(0x40d7429cL,4)==0&&a.val(0x40fa5894L,4)==0,"Cleanup fields");
                if(resources)require(a.val(DESCS[2]+4,4)!=0,"Failed task retained");a.unchanged();
                out.printf("PASS full shared cleanup resources=%s failIndex=%d -> return 0; events=%s%n",resources,a.failIndex,a.events);
            }
            for(int hook:new int[]{0,1}){
                Arm a=new Arm();a.stopKernel=true;a.put(0x40e3811cL,hook==0?0:RECORD+0xf00,4);a.snapshot();
                long stop=a.execute(0x40026e38L,RECORD);
                require(stop==(hook==0?0x40026e5cL:RECORD+0xf00)&&a.reg("r0")==RECORD,"Termination hook");a.unchanged();
                out.printf("PASS termination prefix hook=%d -> stop=%08x before callback/kernel body%n",hook,stop);
            }
            out.println("16 controls passed. Expected loaded-RAM data changes only; original source bytes unchanged. No actual RTOS shutdown, allocator release, hook body, privileged continuation, task worker, USB/NAND/SD, payload or camera executes. Failure injection is a model, not a live error observation; write-free handoff and recovery remain unqualified.");
        }
    }
}
