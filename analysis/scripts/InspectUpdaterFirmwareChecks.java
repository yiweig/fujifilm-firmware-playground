// Original ARM metadata getters and late Firmware2 read/checksum gate only.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectUpdaterFirmwareChecks extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    final long MAP=0x42100000L, GEOMETRY=0x42101000L, BUFFER=0x42102000L;
    byte[] image;
    class Arm {
        PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        PcodeThread<byte[]> t=m.newThread();
        ghidra.program.model.address.AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        Arm(){
            bytes(BASE,image);bytes(SP-256,new byte[512]);bytes(MAP,new byte[512]);
            bytes(GEOMETRY,new byte[512]);t.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",SP);reg("lr",END);
        }
        void bytes(long a,byte[] b){m.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n){return m.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        long run(long entry,Set<Long> stops,boolean stateStub) throws Exception {
            t.overrideCounter(toAddr(entry));
            for(int n=0;n<100000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(stops.contains(pc))return pc;
                if(stateStub&&pc==0x400301d8L){
                    long index=reg("r0");
                    if(index!=1&&index!=0xffffffffL)throw new Exception("Unexpected state request");
                    reg("r0",index==1?GEOMETRY:MAP);t.overrideCounter(toAddr(reg("lr")));continue;
                }
                boolean allowed=(pc>=0x400264f8L&&pc<0x4002661cL)||
                    (pc>=0x4002dee0L&&pc<0x4002e01cL)||
                    (pc>=0x4006b108L&&pc<0x4006b130L)||
                    (pc>=0x4000fad0L&&pc<0x4000fb24L);
                if(!allowed)throw new Exception("Unexpected PC "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
        void unchanged() throws Exception {
            if(!Arrays.equals(image,bytes(BASE,image.length)))throw new Exception("Executable changed");
        }
    }
    public void run() throws Exception {
        image=new byte[0xa2a00];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"))throw new Exception("Wrong updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("Complete updater SHA256 "+hash);
            out.println("Offline original ARM; synthetic metadata/data. NAND getter state helper modeled. No storage I/O, MMIO, task creation, image handoff or camera access.");
            for(long entry:new long[]{0x400264f8L,0x4002dee0L}){
                for(int index=12;index<=15;index++){
                    Arm a=new Arm();a.put(0x400a3c94L,MAP,4);
                    a.put(MAP+0x1c,0x12345,4);a.put(MAP+0x20,0x6789a,4);
                    a.put(MAP+0x42,0xfffe,2);a.put(MAP+0x44,5,2);
                    a.put(MAP+0x46,0x1234,2);a.put(MAP+0x48,0x5678,2);
                    a.reg("r0",index);a.run(entry,Set.of(END),true);
                    long expected=new long[]{0x12345,0x6789a,3,0x68ac}[index-12];
                    if(a.reg("r0")!=expected)throw new Exception("Metadata getter mismatch");a.unchanged();
                    out.printf("PASS getter %08x index=%x -> %x; supplied map lengths and halfwords, including 16-bit sum wrap.%n",entry,index,expected);
                }
            }
            // Enter immediately after storage returns: flags, read return, expected sum, stop.
            long[][] cases={{0x02000000,0,261,0x4000fb24L},{0x02000000,0,262,0x4000fafcL},{0x02000000,1,261,0x4000fb14L}};
            for(long[] c:cases){
                Arm a=new Arm();a.bytes(BUFFER,new byte[]{1,2,(byte)255,3});
                a.put(0x42103010L,BUFFER,4);a.put(SP+0x10,c[2],4);
                a.reg("r6",0x42103000L);a.reg("r4",c[0]);a.reg("r0",c[1]);a.reg("r5",4);
                long stop=a.run(0x4000fad0L,Set.of(0x4000fb24L,0x4000fafcL,0x4000fb14L),false);
                if(stop!=c[3])throw new Exception("Checksum gate mismatch");a.unchanged();
                out.printf("PASS Firmware2 late gate read-return=%d expected-sum=%d -> stop %08x; actual byte sum=261. Stop before status write or error log.%n",c[1],c[2],stop);
            }
            out.println("11 controls passed. No installed image, successful boot, cold selector or whole-loader safety established.");
        }
    }
}
