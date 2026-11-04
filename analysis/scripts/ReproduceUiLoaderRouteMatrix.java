// Original ARM UI route inventory and exhaustive byte-gate checks. Synthetic state only.
// Stops before mode selection or normal USB work. Never accesses the camera.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public class ReproduceUiLoaderRouteMatrix extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        PcodeThread<byte[]> thread;
        Arm() {
            machine.getSharedState().setVar(ram,BASE,0x1400000,true,new byte[0x1400000]);
            machine.getSharedState().setVar(ram,BASE,image.length,true,image);
            machine.getSharedState().setVar(ram,0x40e4df02L,0x400,true,new byte[0x400]);
            machine.getSharedState().setVar(ram,0x40e65950L,0x1100,true,new byte[0x1100]);
            machine.getSharedState().setVar(ram,SP-0x1000,0x2000,true,new byte[0x2000]);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int n=0;n<13;n++)reg("r"+n,0);
            for(String n:new String[]{"CY","ZR","NG","OV"})reg(n,0);
            reg("sp",SP);reg("lr",END);
        }
        void put(long a,int size,long value) {
            machine.getSharedState().setVar(ram,a,size,true,Utils.longToBytes(value,size,false));
        }
        void reg(String n,long value) {
            var r=currentProgram.getRegister(n);
            thread.getState().setVar(r,Utils.longToBytes(value,r.getMinimumByteSize(),false));
        }
        long reg(String n) {
            var r=currentProgram.getRegister(n);
            return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);
        }
        void pc(long a) {thread.overrideCounter(toAddr(a));}
        void returned(long value) {long lr=reg("lr");reg("r0",value);pc(lr);}
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Synthetic cold data, UI state and cable; no hardware accessed.");
            for(int state:new int[]{0x1000,0x2000,0x3000,0x8000,0xa000,0xc000,0xe000})
            for(int flag=0;flag<2;flag++)for(int cached=0;cached<2;cached++)for(int cable=0;cable<2;cable++) {
                Arm a=new Arm();a.put(0x40e66958L,2,state);
                a.put(0x40e6696bL,1,cached);a.put(0x40e6696dL,1,flag);
                a.reg("r0",0xf0000044L);a.pc(0x404db31cL);
                String result=null;
                for(int n=0;n<3000;n++) {
                    monitor.checkCancelled();long pc=a.thread.getCounter().getOffset();
                    if(pc==END){result="returned";break;}
                    if(pc==0x4038d6d0L){if(a.reg("r0")!=4)throw new Exception("Unexpected USB mode");result="mode4";break;}
                    if(pc==0x40004064L || pc==0x405cbb84L){a.returned(0);continue;}
                    if(pc==0x40123bf8L){a.returned(cable);continue;}
                    if(!((pc>=0x404d4f9cL && pc<0x404db3dcL) || (pc>=0x40583b30L && pc<0x40583b3cL))) {
                        result="stopped-before-"+Long.toHexString(pc);break;
                    }
                    a.thread.stepInstruction();
                }
                out.printf("OBS state=%04x flag=%d cached=%d cable=%d result=%s%n",state,flag,cached,cable,result);out.flush();
                if(result==null)throw new Exception("Instruction limit");
                if(flag==1 && state!=0xe000 && result.equals("mode4"))throw new Exception("Potential alternative route; investigate");
            }
            for(long[] gate:new long[][]{
                {0x404d605cL,0x404d61f4L,5,4},
                {0x404d73f8L,0x404d7544L,5,4},
                {0x404d82c8L,0x404d87f8L,6,5},
                {0x404d9abcL,0x404d9c70L,4,5},
                {0x404da960L,0x404daaa8L,4,5}}) {
                Arm a=new Arm();
                for(int value=0;value<256;value++) {
                    a.put(0x40e6696dL,1,value);
                    a.reg("r"+gate[2],0x40e65950L);a.reg("r"+gate[3],0x101d);
                    a.pc(gate[0]);
                    byte[] before=a.machine.getSharedState().getVar(a.ram,0x40e65950L,0x1100,true,Reason.INSPECT);
                    for(int step=0;step<3;step++) {
                        if(a.thread.getCounter().getOffset()!=gate[0]+4*step)throw new Exception("Gate instruction sequence escaped");
                        a.thread.stepInstruction();
                    }
                    long expected=value==0?gate[1]:gate[0]+12;
                    if(a.thread.getCounter().getOffset()!=expected || !java.util.Arrays.equals(before,
                        a.machine.getSharedState().getVar(a.ram,0x40e65950L,0x1100,true,Reason.INSPECT)))
                        throw new Exception("Gate mismatch at "+Long.toHexString(gate[0])+" value="+value);
                }
                out.printf("PASS gate=%08x target=%08x: all 256 byte values; only zero branches toward loader; UI RAM unchanged; no callees executed.%n",gate[0],gate[1]);
            }
            out.println("PASS 1280 narrow gate cases; 56 full-group prefix observations are inventories, not whole-handler success proofs.");
            out.println("Scope: supplied event/state/cable; stops before every unknown callee or USB mode setter. Does not establish retail reachability or effects after the boundary.");
        }
    }
}
