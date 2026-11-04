// Original ARM early guards/selectors with synthetic input state, offline only.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectEarlyBootSelectors extends GhidraScript {
    static final long BASE=0x40000000L, SP=0x42010000L, END=0x43000000L;
    long word(byte[] b,int p){return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24);}
    long target(byte[] b,int p)throws Exception{long w=word(b,p);if((w>>>24)!=0xeb)throw new Exception("Expected BL");int d=(int)(w&0xffffff);if((d&0x800000)!=0)d-=0x1000000;return BASE+p+8+4L*d;}
    String hash(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){mem(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n){return Utils.bytesToLong(mem(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        Arm(){t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);reg("sp",SP);reg("lr",END);mem(SP-0x100,new byte[0x200]);}
    }
    int selector(byte[] image,int entry,boolean newer,int power,int saved,int h1,int h2,boolean readFails)throws Exception{
        int len=newer?0xd8:0xcc;
        byte[] fragment=Arrays.copyOfRange(image,entry,entry+len+8);
        long state=word(image,entry+len),rtc=target(image,entry+0x1c),getter=target(image,entry+0x28);
        Arm a=new Arm();a.mem(BASE+entry,fragment);
        byte[] sentinel=new byte[0x40];Arrays.fill(sentinel,(byte)0x55);a.mem(state,sentinel);
        a.put(state+0x14,saved,4);a.put(0x100003a3L,0xa5,1);a.put(0x1000517aL,h1,1);
        a.t.overrideCounter(toAddr(BASE+entry));int rtcCalls=0,powerCalls=0;
        for(int step=0;step<200;step++){
            monitor.checkCancelled();long pc=a.t.getCounter().getOffset();
            if(pc==END){
                int result=(int)a.reg("r0");
                if(rtcCalls!=1||powerCalls!=1||a.reg("sp")!=SP||!Arrays.equals(fragment,a.mem(BASE+entry,fragment.length)))throw new Exception("Selector call/stack/image mismatch");
                if(a.get(state+0x18,1)!=result||a.get(state+(newer&&entry==0x1d0dc?0x0d:0x19),1)!=(result==1?1:0x55)||a.get(state+0x21,1)!=(result==1?1:0x55))throw new Exception("Selector state mismatch");
                return result;
            }
            if(pc==rtc){if(a.reg("r0")!=state+0x14||++rtcCalls!=1)throw new Exception("RTC argument");if(!readFails)a.put(state+0x14,saved,4);a.ret(readFails?1:0);continue;}
            if(pc==getter){if(a.reg("r0")!=0xffffffffL||++powerCalls!=1)throw new Exception("Power argument");a.put(a.reg("r1"),power,4);a.ret(0);continue;}
            if(pc==BASE+entry+0x78)a.put(0x1000517aL,h2,1); // change supplied sample before the second original load
            if(pc<BASE+entry||pc>=BASE+entry+len)throw new Exception("Unexpected selector PC "+Long.toHexString(pc));
            a.t.stepInstruction();
        }
        throw new Exception("Selector instruction bound");
    }
    boolean expected(boolean newer,int power,int saved,int h1,int h2){
        boolean a=(power&0x1000000)!=0,b=(power&0x8000)!=0;int mode=saved&3;
        if(a&&(mode==1||mode==2||(saved&0x800)!=0))return false;
        boolean candidate=h1!=0&&!b || h2!=0&&a&&mode==3;
        return candidate&&(!newer||(saved&0x6000)==0);
    }
    boolean guard(byte[] image,int bit,int other)throws Exception{
        Arm a=new Arm();byte[] code=Arrays.copyOfRange(image,0xf7c,0xfb8);a.mem(BASE+0xf7c,code);
        a.put(0x10401d18L,bit,1);a.put(0x100003a2L,other,1);a.put(0x100003a0L,0x55,1);a.t.overrideCounter(toAddr(BASE+0xf7c));int loop=0;
        for(int step=0;step<60;step++){
            long pc=a.t.getCounter().getOffset();
            if(pc==BASE+0xfb8){if(a.get(0x100003a0L,1)!=1)throw new Exception("Continue value");return true;}
            if(pc==BASE+0xfa4&&++loop==2){if(a.get(0x100003a0L,1)!=4)throw new Exception("Loop value");return false;}
            if(pc<BASE+0xf7c||pc>=BASE+0xfb8)throw new Exception("Guard PC");a.t.stepInstruction();
        }
        throw new Exception("Guard instruction bound");
    }
    long rebootRecord(byte[] image,int mode,long incoming)throws Exception{
        Arm a=new Arm();byte[] code=Arrays.copyOfRange(image,0x523e94,0x523f9c);a.mem(0x40523e94L,code);
        a.put(0x413d3990L,mode,4);a.put(0x40e4ae64L,incoming,4);a.t.overrideCounter(toAddr(0x40523e94L));int reads=0;
        for(int step=0;step<120;step++){
            monitor.checkCancelled();long pc=a.t.getCounter().getOffset();
            if(pc==END){if(mode!=0||reads!=0||a.get(0x40e4ae64L,4)!=incoming||a.reg("sp")!=SP)throw new Exception("Disabled record path");return incoming;}
            if(pc==0x40041c44L){if(a.reg("r0")!=0x40e4ae64L||++reads!=1)throw new Exception("Record-read argument");a.put(a.reg("r0"),incoming,4);a.ret(0);continue;}
            if(pc==0x40041be8L){
                if(mode==0||reads!=1||a.reg("r0")!=a.get(0x40e4ae64L,4)||!Arrays.equals(code,a.mem(0x40523e94L,code.length)))throw new Exception("Record-write boundary");
                return a.reg("r0"); // STOP: original writer and all later wakeup/power work remain unexecuted
            }
            if(!((pc>=0x40523e94L&&pc<0x40523f3cL)||pc==0x40523f80L))throw new Exception("Unexpected record PC "+Long.toHexString(pc));
            a.t.stepInstruction();
        }
        throw new Exception("Record instruction bound");
    }
    void records(String[][] cases)throws Exception{
        Map<String,byte[]> images=new LinkedHashMap<>();
        for(String[] c:cases){byte[] b=Files.readAllBytes(Path.of(getScriptArgs()[1],c[1]));if(!hash(b).equals(c[2]))throw new Exception("Wrong image");images.put(c[0],b);}
        byte[] main=images.get("2.00");int controls=0;
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Original 2.00 factory reboot worker to BEFORE retained-record writer; supplied successful record read. No real RTC write, wakeup, reset, shell, USB or camera access.");
            out.println("Its prepared word is then supplied to the original selector in each version, with power=0 and USB-presence samples=1. This is software composition, not full boot or proof the same producer exists in older images.");
            for(long incoming:new long[]{0,0xffffffffL}){
                if(rebootRecord(main,0,incoming)!=incoming)throw new Exception("Disabled producer");controls++;
                out.printf("PASS mode=0 incoming=%08x -> no record read/write boundary reached.%n",incoming);
                for(int mode=1;mode<=4;mode++){
                    long expected=mode==1?incoming|0x200L:mode==2?(incoming&~0x6200L)|0x2000L:mode==3?(incoming&~0x6200L)|0x4000L:(incoming&~0x200L)|0x6000L;
                    expected=(expected&~0x803L)&0xffffffffL;long actual=rebootRecord(main,mode,incoming);
                    if(actual!=expected)throw new Exception("Prepared record mismatch");controls++;
                    StringBuilder results=new StringBuilder();
                    for(String[] c:cases){byte[] b=images.get(c[0]);boolean newer=c[0].equals("1.30")||c[0].equals("2.00");int entry=(int)(target(b,0x39ac)-BASE);
                        int result=selector(b,entry,newer,0,(int)actual,1,1,false);
                        if(result!=(newer&&(actual&0x6000L)!=0?0:1))throw new Exception("Producer/selector composition");controls++;results.append(c[0]).append('=').append(result).append(' ');
                    }
                    out.printf("PASS mode=%d incoming=%08x -> prepared=%08x at writer boundary; selector results %s%n",mode,incoming,actual,results);
                }
            }
            for(int value:new int[]{0,1,0xfe,0xff}){
                Arm a=new Arm();a.mem(0x40123d44L,Arrays.copyOfRange(main,0x123d44,0x123d58));a.put(0x1000517aL,value,1);a.t.overrideCounter(toAddr(0x40123d44L));
                for(int step=0;step<8&&a.t.getCounter().getOffset()!=END;step++){long pc=a.t.getCounter().getOffset();if(pc<0x40123d44L||pc>=0x40123d54L)throw new Exception("Presence getter PC");a.t.stepInstruction();}
                if(a.t.getCounter().getOffset()!=END||a.reg("r0")!=(value&1))throw new Exception("Presence getter result");controls++;
            }
            out.println("PASS 4 original application USB-presence getter cases: returns byte 1000517a bit 0. No physical USB measurement.");
            out.printf("%d controls passed. Masks and sampled input corroborated; no safe state setter or independent recovery entry established.%n",controls);
        }
    }
    public void run()throws Exception{
        String[][] cases={{"1.01","analysis/output/firmware-main-1.01.bin","64ca08d346075907bbec2dde2db50bc3a5507a93e5d7b2e5024d4a59599125f9"},
            {"1.20","analysis/output/firmware-main-1.20.bin","85d6d280605148d131c28f71b3560742310878340c6de5d1c05faf00d02a2c8f"},
            {"1.30","analysis/output/firmware-main-1.30.bin","34034d7d1314485fbbd8167669a83dbea4d3dc80cc9ba3c87c7ded2c96aa9df6"},
            {"2.00","firmware-main.bin","80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"}};
        if(getScriptArgs().length>2&&getScriptArgs()[2].equals("reboot-record")){records(cases);return;}
        int total=0;
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Original ARM guard and early-shell selector only. Synthetic MMIO/power/retained word; record-reader and power getter modeled. No live hardware, USB, persistent writes, shell or whole boot.");
            for(String[] c:cases){
                byte[] b=Files.readAllBytes(Path.of(getScriptArgs()[1],c[1]));if(!hash(b).equals(c[2]))throw new Exception("Wrong image");
                int entry=(int)(target(b,0x39ac)-BASE);boolean newer=c[0].equals("1.30")||c[0].equals("2.00");int passed=0,accepted=0;
                for(int power:new int[]{0,0x8000,0x1000000,0x1008000})for(int mode=0;mode<4;mode++)for(int flag:new int[]{0,0x800})for(int extra:new int[]{0,0x2000,0x4000,0x6000})for(int h=0;h<2;h++){
                    int saved=mode|flag|extra;int actual=selector(b,entry,newer,power,saved,h,h,false);
                    if(actual!=(expected(newer,power,saved,h,h)?1:0))throw new Exception("Stable condition mismatch "+c[0]);passed++;accepted+=actual;
                }
                out.printf("PASS %s hash=%s selector=%08x: %d stable cases, %d accepted; saved bits 13/14 filter=%s.%n",c[0],c[2],BASE+entry,passed,accepted,newer);
                int[][] dynamic={{0x1008000,3,0,1},{0x1008000,3,1,0},{0,0,1,0}};
                for(int[] d:dynamic){int actual=selector(b,entry,newer,d[0],d[1],d[2],d[3],false);if(actual!=(expected(newer,d[0],d[1],d[2],d[3])?1:0))throw new Exception("Changed sample mismatch");passed++;}
                if(selector(b,entry,newer,0,0,1,1,true)!=1)throw new Exception("Ignored record status case");passed++;
                out.println("PASS 3 changed-sample cases and 1 modeled failed-record case with supplied retained saved=0; read failure is not a live RAM prediction.");
                for(int bit:new int[]{0,0x40})for(int other:new int[]{0,1}){boolean actual=guard(b,bit,other);if(actual!=(bit!=0||other!=0))throw new Exception("Reset guard mismatch");passed++;}
                out.println("PASS 4 reset guard cases: bit 10401d18.6 or nonzero 100003a2 -> store 1; otherwise repeated store 4 at 100003a0. Stop before later startup.");
                total+=passed;
            }
            out.printf("%d controls passed. No physical bit/button names, retail reachability, ROM contents, USB initialization or recovery established.%n",total);
        }
    }
}
