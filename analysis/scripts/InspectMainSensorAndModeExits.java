// Original sensor timeout setter and mode exits; stop before unaudited callees.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectMainSensorAndModeExits extends GhidraScript {
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
        final List<Long> calls=new ArrayList<>();
        boolean modelWait=false;
        void sensor(int status,int mode,int active){
            put(0x40afbe88L,RECORD+0x100,4);bytes(RECORD+0x100,new byte[0x100]);
            put(RECORD+0x1ac,mode,1);put(0x40e785c4L,status,1);put(0x40e7844cL,active,1);
            expectedImage=bytes(BASE,image.length);
            int offset=(int)(0x40d70890L-BASE);byte[] v=Utils.longToBytes(100,4,false);
            System.arraycopy(v,0,expectedImage,offset,4);
        }
        long execute(long entry,long r0,long r1) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",r0);reg("r1",r1);t.overrideCounter(toAddr(entry));
            for(int n=0;n<2000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END)return pc;
                if(pc==0x4000e164L){ret(0);continue;} // logging modeled
                if(pc==0x40113960L && modelWait){calls.add(pc);ret(0);continue;} // modeled satisfied wait
                if(pc==0x4000c30cL && modelWait){calls.add(pc);ret(0);continue;} // IRQ operation modeled
                if(pc==0x40113960L||pc==0x40114504L||pc==0x4010de08L||pc==0x401a519cL||pc==0x400d3aecL)return pc;
                boolean allowed=(pc>=0x4010e11cL&&pc<0x4010e178L)||(pc>=0x4010df60L&&pc<0x4010e104L)
                    ||(pc>=0x402dbc80L&&pc<0x402dbc9cL)||(pc>=0x402dbbc0L&&pc<0x402dbbdcL)
                    ||(pc>=0x402db8fcL&&pc<0x402db9f4L)||(pc>=0x402dbad8L&&pc<0x402dbae0L)
                    ||(pc>=0x402dbd68L&&pc<0x402dbd94L)||(pc>=0x402dbdc8L&&pc<0x402dbdd4L)
                    ||(pc>=0x40182e8cL&&pc<0x40182e94L);
                if(!allowed)throw new Exception("Unexpected sensor/exit PC "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image bytes changed");}
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong main image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("MAIN SHA256 "+hash);
            out.println("Original sensor timeout setter and candidate mode exits. Synthetic state; logging modeled. Stop boundaries execute no callee instructions. Two sensor controls additionally model wait/IRQ calls as returning.");
            int[][] cases={{2,0},{2,16},{3,0},{3,16},{4,0}};
            for(int[] c:cases){
                Arm a=new Arm();a.sensor(c[0],c[1],0);long stop=a.execute(0x4010e11cL,0,100);
                long want=c[0]==3&&c[1]==0?0x40113960L:END;
                if(stop!=want||a.val(0x40d70890L,4)!=100)throw new Exception("Sensor guard");
                if(stop!=END&&(a.reg("r0")!=0||a.reg("r1")!=2))throw new Exception("Sensor wait arguments");a.unchanged();
                out.printf("PASS sensor status=%d current=%02x -> timeout RAM word=100; stop=%08x%s%n",c[0],c[1],stop,stop==END?"":" wait(0,2), before wait executes");
            }
            for(int active:new int[]{0,1}){
                Arm a=new Arm();a.sensor(3,0,active);a.modelWait=true;long stop=a.execute(0x4010e11cL,0,100);
                if(stop!=(active==0?0x4010de08L:0x40114504L)||a.reg("r0")!=0||!a.calls.equals(List.of(0x40113960L,0x4000c30cL)))throw new Exception("Sensor deeper boundary");
                if(a.val(RECORD+0x1ad,1)!=0)throw new Exception("Sensor requested mode");a.unchanged();
                out.printf("PASS sensor status=3 current=00 activeMask=%x with modeled wait/IRQ -> stop=%08x r0=0; requested mode byte=0%n",active,stop);
            }
            long[][] exits={
                {0,0x40182e8cL,0,END},{1,0x402dbc80L,0,0x401a519cL},
                {2,0x402dbbc0L,0,0x401a519cL},{3,0x402db8fcL,0,0x400d3aecL},
                {3,0x402db8fcL,1,END},{4,0x402dbad8L,0,END},
                {5,0x402dbd68L,0,0x400d3aecL},{6,0x402dbdc8L,0,END}};
            for(long[] c:exits){
                Arm a=new Arm();a.put(0x40f80d58L,c[2],4);long stop=a.execute(c[1],RECORD,0);
                if(stop!=c[3]||(stop==END&&a.reg("r0")!=0))throw new Exception("Mode exit "+c[0]);
                if(stop==0x400d3aecL&&(a.reg("r0")!=1||a.reg("r1")!=4||a.reg("r2")!=32))throw new Exception("Exit clock arguments");a.unchanged();
                out.printf("PASS candidate mode index=%d exit=%08x targetIndex=0 state+30=%d -> stop=%08x%s%n",c[0],c[1],c[2],stop,stop==END?" return 0":" before callee executes");
            }
            out.println("15 controls passed. Sensor timeout word is the sole permitted loaded-image data change; source binaries unchanged. No physical wait, IRQ change, sensor reconfiguration, cleanup callee, mode worker, clock switch, USB/NAND/SD, payload or camera executes. Actual provider installation/state and transitive side effects remain unverified.");
        }
    }
}
