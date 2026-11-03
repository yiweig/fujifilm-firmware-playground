// Original registration and synchronous routing; no backend initialization or I/O.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterStorageRouting extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        final List<String> posts=new ArrayList<>();int reads=0,logs=0;byte[] expectedImage;
        Arm(){
            bytes(BASE,image);bytes(SP-256,new byte[512]);bytes(RECORD,new byte[256]);
            bytes(0x400a39d0L,new byte[96]);bytes(0x400a3a64L,new byte[8]);
            put(0x4009de2cL,RECORD,4);put(RECORD,1,4);put(RECORD+0x3c,0x4002bc1cL,4);
            expectedImage=bytes(BASE,image.length); // explicit RAM fixture for the record-base global
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
        long execute(long entry,long argument,Set<Long> stops) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",argument);t.overrideCounter(toAddr(entry));
            for(int n=0;n<2000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END||stops.contains(pc))return pc;
                if(pc==0x400881b4L){
                    if(reg("r2")!=100)throw new Exception("Unexpected registration copy");
                    bytes(reg("r0"),bytes(reg("r1"),100));ret(reg("r0"));continue;
                }
                boolean allowed=(pc>=0x4001b4e4L&&pc<0x4001b584L)||(pc>=0x4001b744L&&pc<0x4001b7e4L)
                    ||(pc>=0x4001be6cL&&pc<0x4001bf18L)||(pc>=0x4001aba0L&&pc<0x4001ac4cL)
                    ||(pc>=0x4001a89cL&&pc<0x4001a9b0L)||(pc>=0x4001a7c8L&&pc<0x4001a7fcL)||(pc>=0x4001a844L&&pc<0x4001a870L)
                    ||(pc>=0x4001adc8L&&pc<0x4001addcL)||(pc>=0x4001a9b4L&&pc<0x4001a9d0L)
                    ||(pc>=0x4001c594L&&pc<0x4001c69cL);
                if(!allowed)throw new Exception("Unexpected routing PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Routing instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded bytes changed beyond supplied global fixture");}
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Offline original registration/routing with supplied three-slot RAM table. No backend initialization, mount, MMIO or storage I/O executes.");
            out.println("Record-base global 4009de2c overridden before execution; image bytes unchanged thereafter. Copy helper modeled for 100-byte records only.");
            Arm a=new Arm();a.bytes(RECORD,new byte[300]);a.bytes(0x400a39d0L,new byte[96]);
            a.bytes(0x400a3a28L,new byte[16]);a.put(0x400a3a2cL,3,4);
            for(int i=0;i<3;i++)a.put(RECORD+100*i,0xffffffffL,4);
            long[][] registrations={{0x4001b4e4L,2,0x4001b354L,0x4001b4dcL,END},
                                  {0x4001b744L,5,0x4001b5b4L,0x4001b73cL,END},
                                  {0x4001be6cL,1,0x4001bd44L,0x4002bc1cL,0x4001bd44L}};
            for(int i=0;i<registrations.length;i++){
                long[] c=registrations[i];long stop=a.execute(c[0],0,Set.of(0x4001bd44L));
                long record=RECORD+100*i;
                if(stop!=c[4]||a.val(record,4)!=c[1]||a.val(record+0x38,4)!=c[2]||a.val(record+0x3c,4)!=c[3]
                   ||a.val(0x400a39d0L+c[1]*2,1)!=1||a.val(0x400a39d1L+c[1]*2,1)!=i)throw new Exception("Registration mismatch");
                a.unchanged();out.printf("PASS register logical=%d index=%d init=%08x mount=%08x boundary=%08x%n",c[1],i,c[2],c[3],stop);
            }
            out.println("NAND registration stopped before its immediate initializer call. Its record/mapping were already installed; later controls reuse only that RAM state, not a completed NAND initialization.");
            int[][] routes={{1,0,2},{2,0,0},{5,0,1},{2,1,0},{5,1,1}};
            for(int[] c:routes){
                a.bytes(0x400a39ecL,new byte[12]);a.put(0x400a39ecL+c[2]*4,c[1],4);
                long target=c[0]==1?0x4002bc1cL:c[1]==0?0x4001a89cL:c[0]==2?0x4001b4dcL:0x4001b73cL;
                long stop=a.execute(0x4001c594L,c[0],c[0]==1?Set.of(0x4002bc1cL,0x4001bd44L):Set.of(0x4001a89cL,0x4002bc1cL,0x4001b4dcL,0x4001b73cL));
                if(stop!=target)throw new Exception("Route mismatch");
                a.unchanged();out.printf("PASS synchronous logical=%d initialized=%d -> boundary=%08x; backend callback never executes; logical 1 may execute generic initializer bookkeeping.%n",c[0],c[1],stop);
            }
            out.println("8 controls passed. Synthetic registration order and status; not complete startup, production camera state, safe RAM launch or recovery.");
        }
    }
}
