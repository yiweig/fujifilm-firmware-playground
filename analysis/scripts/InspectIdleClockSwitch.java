// Original idle clock/API paths; modeled synchronization and stop before hardware core.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectIdleClockSwitch extends GhidraScript {
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
        final List<String> calls=new ArrayList<>();int waitResult=0,coreResult=0;boolean modelCore=false,modelPre=false;
        void snapshot(){expectedImage=bytes(BASE,image.length);}
        void table(){
            bytes(0x40d5dda0L,new byte[0x84]);
            for(int i=0;i<2;i++){long a=0x40d5dda0L+16*i;put(a,RECORD+0x800+0x100*i,4);put(a+4,12+i,2);put(a+8,i==0?200:400,4);put(a+12,i+1,2);put(a+14,1,2);}
            put(0x40d5de20L,200,4);bytes(0x1000a000L,new byte[0x30]);bytes(0x40f80d28L,new byte[0x38]);
            put(0x40f80d28L,100,4);put(0x40f80d2cL,400,4);put(0x40fa58f0L,0,4);snapshot();
        }
        long execute(long entry,long r0,long r1,long r2) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",r0);reg("r1",r1);reg("r2",r2);t.overrideCounter(toAddr(entry));
            for(int n=0;n<4000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END||pc==0x400070d8L||pc==0x40007148L||pc==POST)return pc;
                if(pc==PRE){if(!modelPre)return pc;calls.add("pre");ret(0);continue;}
                if(pc==0x4001e7e4L){calls.add("core:"+reg("r0")+","+reg("r1"));if(!modelCore)return pc;ret(coreResult);continue;}
                if(pc==0x4000e164L){ret(0);continue;}
                if(pc==0x4018323cL||pc==0x4018331cL){ret(0);continue;}
                if(pc==0x400d3accL){ret(0);continue;}
                if(pc==0x4001d8f0L){calls.add("guard");ret(1);continue;}
                if(pc==0x4001da60L){calls.add("unguard");ret(0);continue;}
                if(pc==0x40036440L){calls.add("semCreate");ret(RECORD+0x1000);continue;}
                if(pc==0x400bd9f8L){calls.add("eventRemove:"+reg("r0"));ret(calls.stream().filter(v->v.startsWith("eventRemove:")).count()==1?OLD:0x401a7c98L);continue;}
                if(pc==0x400bd860L){calls.add("eventInstall:"+reg("r0")+":"+Long.toHexString(reg("r1")));ret(0);continue;}
                if(pc==0x4003648cL){if(reg("r0")!=RECORD+0x1000||reg("r1")!=1000)throw new Exception("Wait arguments");calls.add("semWait:"+waitResult);ret(waitResult);continue;}
                if(pc==0x400364c0L){calls.add("semDelete");ret(0);continue;}
                boolean allowed=(pc>=0x40183944L&&pc<0x40183a68L)||(pc>=0x40183db8L&&pc<0x40183dccL)
                    ||(pc>=0x40182e9cL&&pc<0x40182ff0L)||(pc>=0x401a7ca8L&&pc<0x401a7e2cL)
                    ||(pc>=0x4000dd58L&&pc<0x4000dde4L)||(pc>=0x4000dde8L&&pc<0x4000ddf4L);
                if(!allowed)throw new Exception("Unexpected idle-clock PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Unexpected loaded-image change");}
    }
    final long PRE=0x42101800L,POST=0x42101900L,OLD=0x42101a00L;
    void require(boolean b,String label) throws Exception {if(!b)throw new Exception(label);}
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        require(hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"),"Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("MAIN SHA256 "+hash);
            out.println("Original variadic API selector 6, idle entry, table lookup and clock wrapper. Synthetic table/rates/MMIO bytes; synchronization/event calls modeled. Stop before hardware clock core or assertion/callback boundaries unless explicitly modeled. No physical clock/IRQ/display/storage/camera execution.");
            for(boolean clear:new boolean[]{false,true}){
                Arm a=new Arm();a.table();a.put(0x40f80d30L,clear?PRE:0,4);a.put(0x40f80d34L,clear?POST:0,4);
                require(a.execute(0x40183944L,6,clear?0:PRE,clear?0:POST)==END&&a.reg("r0")==0,"API return");
                require(a.val(0x40f80d30L,4)==(clear?0:PRE)&&a.val(0x40f80d34L,4)==(clear?0:POST),"API slots");a.unchanged();
                out.printf("PASS API selector 6 clear=%s -> both callback slots match supplied arguments%n",clear);
            }
            for(int target:new int[]{0,100,200}){
                Arm a=new Arm();a.table();a.put(0x40f80d2cL,target,4);a.put(0x40f80d30L,PRE,4);a.put(0x40f80d34L,POST,4);
                require(a.execute(0x40182e9cL,RECORD,0,0)==END&&a.calls.isEmpty(),"Idle skip");a.unchanged();
                out.printf("PASS idle requested=%d cached=100 current=200 -> skips callbacks/core%n",target);
            }
            for(boolean after:new boolean[]{false,true}){
                Arm a=new Arm();a.table();a.put(0x40f80d30L,PRE,4);a.put(0x40f80d34L,POST,4);a.modelPre=after;a.modelCore=after;
                long stop=a.execute(0x40182e9cL,RECORD,0,0);
                require(stop==(after?POST:PRE)&&a.reg("r0")==100&&a.reg("r1")==400,"Callback boundary");
                require(a.val(0x40f80d28L,4)==100,"Cache not committed before post callback");a.unchanged();
                out.printf("PASS idle callback boundary=%08x args=(100,400) cache still 100; modeledCalls=%s%n",stop,a.calls);
            }
            {
                Arm a=new Arm();a.table();require(a.execute(0x401a7ca8L,999,1,0)==END&&a.reg("r0")==1&&a.calls.isEmpty(),"Missing clock table entry");a.unchanged();out.println("PASS missing synthetic target table entry -> return 1 before event/core calls");
            }
            for(int channel:new int[]{0,3,11}){
                Arm a=new Arm();a.table();if(channel==3)a.put(0x1000a020L,1,1);if(channel==11)a.put(0x1000a021L,1,1);
                require(a.execute(0x401a7ca8L,400,1,0)==0x4001e7e4L&&a.reg("r0")==400&&a.reg("r1")==1,"Core boundary");
                if(channel==0)require(a.calls.equals(List.of("core:400,1")),"No sync calls");
                else require(a.calls.contains("eventInstall:"+channel+":401a7c98")&&a.calls.contains("semWait:0"),"Event synchronization");a.unchanged();
                out.printf("PASS clock wrapper selected channel=%d -> stop before core(400,1); modeledCalls=%s%n",channel,a.calls);
            }
            {
                Arm a=new Arm();a.table();a.put(0x1000a020L,1,1);a.waitResult=17;
                require(a.execute(0x401a7ca8L,400,1,0)==0x400070d8L&&!a.calls.contains("core:400,1")&&a.val(0x40fa58f0L,4)==RECORD+0x1000,"Wait failure boundary");a.unchanged();out.printf("PASS modeled wait failure -> assertion boundary before core; handle retained; modeledCalls=%s%n",a.calls);
            }
            for(int result:new int[]{0,1}){
                Arm a=new Arm();a.table();a.put(0x1000a020L,1,1);a.modelCore=true;a.coreResult=result;
                require(a.execute(0x401a7ca8L,400,1,0)==END&&a.reg("r0")==result,"Modeled core result");
                require(a.val(0x40fa58f0L,4)==0&&a.calls.contains("eventInstall:3:"+Long.toHexString(OLD))&&a.calls.get(a.calls.size()-1).equals("semDelete"),"Sync cleanup");a.unchanged();
                out.printf("PASS modeled core result=%d -> prior event restored and semaphore slot cleared; modeledCalls=%s%n",result,a.calls);
            }
            {
                Arm a=new Arm();a.table();a.modelCore=true;a.coreResult=1;a.put(0x40f80d34L,POST,4);
                require(a.execute(0x40182e9cL,RECORD,0,0)==0x400070d8L&&a.val(0x40f80d28L,4)==100,"Idle failure before post/cache commit");a.unchanged();
                out.printf("PASS idle modeled core failure -> assertion before post callback/cache update; modeledCalls=%s%n",a.calls);
            }
            out.println("15 controls passed. Loaded-image bytes unchanged after fixture setup; source binaries unchanged. Real callbacks, display event, semaphore behavior, assertion, hardware clock core, IRQ/cache/SRAM routines, USB/NAND/SD, payload and camera remain unexecuted. Rate values/table addresses are synthetic, not supported camera clock settings.");
        }
    }
}
