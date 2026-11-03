// Original worker/repair slices; stop before storage initialization or erase.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterMountBoundaries extends GhidraScript {
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
        long worker(int type,boolean mapped) throws Exception {
            if(mapped){put(0x400a39d2L,1,1);put(0x400a39d3L,0,1);} // logical id 1 -> record index 0
            t.overrideCounter(toAddr(0x4001c23cL));
            for(int n=0;n<1000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0x4001a89cL||pc==0x4002bc1cL)return pc;
                if(pc==0x40065f94L){
                    if(reads++!=0)return pc;
                    if(reg("r0")!=14)throw new Exception("Wrong receive channel");
                    put(reg("r1"),type,4);put(reg("r1")+4,type==4?0xffffffffL:1,4);ret(0);continue;
                }
                if(pc==0x40065ea4L){long p=reg("r1");posts.add(String.format("channel=%d type=%x id=%x",reg("r0"),val(p,4),val(p+4,4)));ret(0);continue;}
                if(pc==0x4007ac04L){logs++;ret(0);continue;}
                if(!((pc>=0x4001c23cL&&pc<0x4001c3b4L)||(pc>=0x4001a844L&&pc<0x4001a870L)||(pc>=0x4001adc8L&&pc<0x4001addcL)))throw new Exception("Unexpected worker PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Worker instruction bound");
        }
        long repair(int result,long erase) throws Exception {
            reg("r4",RECORD);reg("r10",0x800);reg("r6",RECORD+0x100);
            put(SP+0x30,0x43001000L,4);put(SP+0x24,erase,4);
            t.overrideCounter(toAddr(0x4002a37cL));
            for(int n=0;n<200;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==erase||pc==0x4002a398L)return pc;
                if(pc==0x43001000L){reads++;ret(result);continue;}
                if(pc==0x4007ac04L){logs++;ret(0);continue;}
                if(pc<0x4002a37cL||pc>=0x4002a408L)throw new Exception("Unexpected repair PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Repair instruction bound");
        }
        long profile() throws Exception {
            reg("r0",RECORD);t.overrideCounter(toAddr(0x4003ffd8L));
            for(int n=0;n<100;n++){
                long pc=t.getCounter().getOffset();if(pc==END)return val(RECORD+0x34,4);
                if(pc<0x4003ffd8L||pc>=0x40040020L)throw new Exception("Unexpected profile PC");t.stepInstruction();
            }
            throw new Exception("Profile instruction bound");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded bytes changed beyond supplied global fixture");}
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Offline supplied messages, mapping and read results. No driver initialization, mount callback, erase callback or actual I/O executes.");
            out.println("RAM fixture overrides record-base global 4009de2c before execution; loaded bytes must remain unchanged thereafter. Original source file is untouched.");
            int[][] cases={{4,0},{1,1},{2,1},{2,0}};
            long[] stops={0x40065f94L,0x4001a89cL,0x4002bc1cL,0x40065f94L};
            for(int i=0;i<cases.length;i++){
                Arm a=new Arm();long stop=a.worker(cases[i][0],cases[i][1]!=0);
                List<String> expected=cases[i][0]==2?List.of(cases[i][1]!=0?"channel=1 type=202 id=1":"channel=1 type=204 id=1"):List.of();
                if(stop!=stops[i]||!a.posts.equals(expected)||a.logs!=(cases[i][0]==2&&cases[i][1]==0?1:0)||a.val(0x400a3a64L,4)!=2)throw new Exception("Worker control mismatch");
                a.unchanged();out.printf("PASS worker type=%d mapping=%d -> boundary=%08x posts=%s; stop before storage or next queue receive.%n",cases[i][0],cases[i][1],stop,a.posts);
            }
            Arm profile=new Arm();long erase=profile.profile();if(erase!=0x4003f740L)throw new Exception("Erase slot mismatch");profile.unchanged();
            out.println("PASS original NAND profile installer places 4003f740 in record +34. No installed driver state or actual erase.");
            for(int result:new int[]{0,1,2}){
                Arm a=new Arm();long stop=a.repair(result,erase);
                if(stop!=(result==1?erase:0x4002a398L)||a.reads!=1||a.logs!=(result==1?2:0))throw new Exception("Repair control mismatch");
                if(result==1&&(a.reg("r0")!=RECORD||a.reg("r1")!=0x800))throw new Exception("Erase arguments");
                a.unchanged();out.printf("PASS modeled spare-read result=%d -> boundary=%08x logs=%d; erase target never executes.%n",result,stop,a.logs);
            }
            out.println("8 controls passed. No complete startup/mount, flash, real storage, scheduler or recovery behavior established.");
        }
    }
}
