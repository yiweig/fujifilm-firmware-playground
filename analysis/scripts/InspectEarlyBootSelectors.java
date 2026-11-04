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
    public void run()throws Exception{
        String[][] cases={{"1.01","analysis/output/firmware-main-1.01.bin","64ca08d346075907bbec2dde2db50bc3a5507a93e5d7b2e5024d4a59599125f9"},
            {"1.20","analysis/output/firmware-main-1.20.bin","85d6d280605148d131c28f71b3560742310878340c6de5d1c05faf00d02a2c8f"},
            {"1.30","analysis/output/firmware-main-1.30.bin","34034d7d1314485fbbd8167669a83dbea4d3dc80cc9ba3c87c7ded2c96aa9df6"},
            {"2.00","firmware-main.bin","80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"}};
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
