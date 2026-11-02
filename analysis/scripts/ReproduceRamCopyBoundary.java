// Offline original relocated stub; synthetic registers only. Never executes low helper or payload.
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

public class ReproduceRamCopyBoundary extends GhidraScript {
    byte[] main,updater;
    final long WORK=0x42000000L, STOP=0x43000000L;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        final List<String> commands=new ArrayList<>();
        final List<Long> counts=new ArrayList<>();
        Arm(byte[] image) {
            mem(0x40000000L,image);mem(0x10000000L,new byte[0x16000]);mem(0x10401000L,new byte[0x2000]);
            mem(0x3f00d000L,new byte[20]);mem(0x4210e000L,new byte[0x3000]);
            put(0x10015872L,1,4); // supplied helper capacity field: 4 << 6 = 256 MiB
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",0x42110000L);reg("lr",STOP);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        long word(long a){return Utils.bytesToLong(mem(a,4),4,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(){t.overrideCounter(toAddr(reg("lr")));}
        long copy(long source,long size,long dest) throws Exception {return copy(source,size,dest,false);}
        long copy(long source,long size,long dest,boolean small) throws Exception {
            byte[] image=small?updater:main;int offset=small?0x123dc:0x1ed070;
            mem(WORK,Arrays.copyOfRange(image,offset,offset+0x800));
            put(0x3f00d000L,4,source);put(0x3f00d004L,4,size);put(0x3f00d008L,4,dest);
            t.overrideCounter(toAddr(WORK));
            for(int step=0;step<3000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==0xb34L||pc==0x400070d8L||pc==0x40007148L||pc==0x40083f20L||pc==0x40083f90L)return pc;
                // Model cache helpers, not address checks or their return values.
                if(pc==0x400029c8L||pc==0x400029c0L){ret();continue;}
                if(small&&(pc==0x40082014L||pc==0x4008201cL)){ret();continue;}
                if(pc==WORK+0x8c){ // original 401ed0fc, immediately before completion polling
                    long n=word(0x10003050L)+1;counts.add(n);
                    commands.add(String.format("%08x->%08x:%x",word(0x10003048L),word(0x1000304cL),n));
                    // Supplied completion. No DMA implementation or payload copy is modeled.
                    put(0x10003000L,1,1);
                }
                if(!((pc>=WORK&&pc<WORK+0x100)||(!small&&((pc>=0x4002a7bcL&&pc<0x4002a95cL)||(pc>=0x40002b0cL&&pc<0x40002b20L)))||(small&&pc>=0x4007e840L&&pc<0x4007e954L)))throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
        void gate(boolean small) throws Exception {
            put(0x10401d18L,1,0x40);t.overrideCounter(toAddr(0x40000200L));
            long boundary=small?STOP:0x40000338L;
            for(int step=0;step<60;step++) {
                long pc=t.getCounter().getOffset();if(pc==boundary)return;
                if(pc<0x40000200L||pc>=0x40000248L)throw new Exception("Unexpected gate execution");
                t.stepInstruction();
            }
            throw new Exception("Gate instruction limit");
        }
    }
    String hash(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    public void run() throws Exception {
        main=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),main);
        updater=Files.readAllBytes(Path.of(getScriptArgs()[1]));
        if(!hash(main).equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923")||!hash(updater).equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong images");
        Object[][] cases={
            {"zero at base",0x41000000L,0L,0x40000000L,0x400070d8L,new long[]{}},
            {"zero interior",0x41000000L,0L,0x40010000L,0xb34L,new long[]{}},
            {"one",0x41000000L,1L,0x40000000L,0xb34L,new long[]{1}},
            {"one MiB",0x41000000L,0x100000L,0x40000000L,0xb34L,new long[]{0x100000}},
            {"MiB plus two",0x41000000L,0x100002L,0x40000000L,0xb34L,new long[]{0x100000,2}},
            {"unaligned source",0x41000001L,32L,0x40000000L,0x400070d8L,new long[]{}},
            {"source at capacity",0x50000000L,32L,0x40000000L,0x400070d8L,new long[]{}},
            {"unaligned destination",0x41000000L,32L,0x40000001L,0x400070d8L,new long[]{}},
            {"A-range source",0xa1000000L,32L,0x40000000L,0x400070d8L,new long[]{}}};
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main SHA-256: "+hash(main));out.println("Updater SHA-256: "+hash(updater));
            out.println("Relocated original stub at synthetic 42000000; original address guards and capacity-register getter execute. Capacity fixture 256 MiB, cache helpers modeled.");
            for(Object[] c:cases) {
                Arm a=new Arm(main);long stop=a.copy((Long)c[1],(Long)c[2],(Long)c[3]);long[] counts=(long[])c[5];
                if(stop!=(Long)c[4]||a.counts.size()!=counts.length)throw new Exception("Control failed "+c[0]+" stop="+Long.toHexString(stop)+" commands="+a.commands+" r0="+Long.toHexString(a.reg("r0"))+" r1="+Long.toHexString(a.reg("r1")));
                long src=(Long)c[1]&~0x40000000L,dst=(Long)c[3]&~0x40000000L;
                for(int i=0;i<counts.length;i++) {
                    String expected=String.format("%08x->%08x:%x",src,dst,counts[i]);
                    if(!a.commands.get(i).equals(expected)||a.counts.get(i)!=counts[i])throw new Exception("Copy command mismatch");
                    src+=counts[i];dst+=counts[i];
                }
                if(stop==0xb34L&&(a.reg("r0")!=(Long)c[3]||a.reg("r1")!=(Long)c[2]))throw new Exception("Low helper arguments");
                if(!Arrays.equals(main,a.mem(0x40000000L,main.length)))throw new Exception("Executable image changed");
                out.printf("PASS %s -> stop=%08x commands=%s; loaded image unchanged.%n",c[0],stop,a.commands);
            }
            Arm a=new Arm(main);a.gate(false);if(a.word(0x10001300L)!=0x12345678L)throw new Exception("Main gate marker");
            out.println("PASS main +200 entry writes gate 12345678, stops before 40000338.");
            a=new Arm(updater);a.gate(true);if(a.word(0x10001300L)!=0x88888888L)throw new Exception("Updater gate marker");
            out.println("PASS updater +200 entry writes gate 88888888 and returns, with supplied readiness bit.");
            out.println("11 original main-copy/special-entry controls passed.");
            out.println("Updater-specific relocated stub and original top-two-bit guards follow. The supplied capacity register is NOT consumed by these updater guards.");
            for(Object[] c:cases){
                a=new Arm(updater);a.put(0x10003044L,4,0xa5a55a5aL);
                long stop=a.copy((Long)c[1],(Long)c[2],(Long)c[3],true);
                boolean capacity=c[0].equals("source at capacity");
                long expectedStop=capacity?0xb34L:((Long)c[4]==0xb34L?0xb34L:0x40083f20L);
                long[] counts=capacity?new long[]{32}:(long[])c[5];
                if(stop!=expectedStop||a.counts.size()!=counts.length)throw new Exception("Updater control failed "+c[0]);
                long src=(Long)c[1]&~0x40000000L,dst=(Long)c[3]&~0x40000000L;
                for(int i=0;i<counts.length;i++){
                    if(!a.commands.get(i).equals(String.format("%08x->%08x:%x",src,dst,counts[i])))throw new Exception("Updater command mismatch");
                    src+=counts[i];dst+=counts[i];
                }
                if(stop==0xb34L&&(a.reg("r0")!=(Long)c[3]||a.reg("r1")!=(Long)c[2]))throw new Exception("Updater low helper arguments");
                if(!Arrays.equals(updater,a.mem(0x40000000L,updater.length)))throw new Exception("Updater executable changed");
                // Updater clears only a halfword at +44, unlike the main stub's word write.
                if(a.word(0x10003044L)!=(stop==0xb34L?0xa5a50000L:0xa5a55a5aL))throw new Exception("Updater control-register access width");
                out.printf("PASS updater %s -> stop=%08x commands=%s; loaded updater unchanged, +44 halfword/upper guard checked.%n",c[0],stop,a.commands);
            }
            for(long size:new long[]{32,33}){
                a=new Arm(updater);a.put(0x400a2f20L,4,0x81234000L);a.put(0x400a2f24L,4,0x1234);
                a.reg("r8",0x41000000L);a.reg("r5",size);a.reg("r7",0x40000000L);a.reg("r6",WORK);
                a.t.overrideCounter(toAddr(0x40012328L));
                for(int n=0;n<30&&a.t.getCounter().getOffset()!=0x40012358L;n++)a.t.stepInstruction();
                if(a.t.getCounter().getOffset()!=0x40012358L)throw new Exception("Descriptor instruction bound");
                long[] expected={0x41000000L,(size+1)&~1L,0x40000000L,0x81234000L,0x1234};
                for(int i=0;i<5;i++)if(a.word(0x3f00d000L+4*i)!=expected[i])throw new Exception("Updater SRAM descriptor");
                if(!Arrays.equals(updater,a.mem(0x40000000L,updater.length)))throw new Exception("Updater descriptor executable changed");
                out.printf("PASS updater descriptor size=%d -> %d, five SRAM words match; stops before workspace call 40012358.%n",size,expected[1]);
            }
            out.println("22 controls passed. DMA completion supplied; no data-copy engine, cache/MMU hardware, low helper 00000b34, payload entry or persistent operation executes.");
            out.println("Not an actual RAM launch or recovery path. Zero-length acceptance is a supplied-state boundary observation, not permission to issue requests.");
        }
    }
}
