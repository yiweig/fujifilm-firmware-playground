// Offline original early-loader copy slice across four official main images.
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

public class ReproduceEarlySramCopy extends GhidraScript {
    String hash(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        long word(long a){return Utils.bytesToLong(mem(a,4),4,false);}
        Arm(byte[] image) {
            mem(0x40000000L,image);byte[] guard=new byte[0x700];Arrays.fill(guard,(byte)0x55);mem(0x3efffff0L,guard);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            t.overrideCounter(toAddr(0x400005f0L));
        }
        void runSlice() throws Exception {
            for(int step=0;step<5000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==0xb34L)return;
                if(pc<0x400005f0L||pc>=0x40000640L)throw new Exception("Unexpected execution "+Long.toHexString(pc));
                t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        String[][] cases={
            {"1.01","analysis/output/firmware-main-1.01.bin","64ca08d346075907bbec2dde2db50bc3a5507a93e5d7b2e5024d4a59599125f9"},
            {"1.20","analysis/output/firmware-main-1.20.bin","85d6d280605148d131c28f71b3560742310878340c6de5d1c05faf00d02a2c8f"},
            {"1.30","analysis/output/firmware-main-1.30.bin","34034d7d1314485fbbd8167669a83dbea4d3dc80cc9ba3c87c7ded2c96aa9df6"},
            {"2.00","firmware-main.bin","80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"}};
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Offline original ARM slice 400005f0..4000063c; starts after earlier setup/validation, stops before low helper 00000b34.");
            for(String[] c:cases) {
                byte[] image=Files.readAllBytes(Path.of(getScriptArgs()[1],c[1]));
                if(!hash(image).equals(c[2]))throw new Exception("Wrong image "+c[0]);
                if(!hash(Arrays.copyOfRange(image,0x338,0x790)).equals("d545a151267c4916addd3e73dc7bee8d53fe0671cb13c689ef6a3cf4bf4dbc65"))throw new Exception("Changed loader body");
                Arm a=new Arm(image);long source=a.word(0x400007d0L),start=a.word(0x400007acL),end=a.word(0x400007a4L);
                if(start!=0x3f000000L||end-start!=0x674)throw new Exception("Unexpected copy bounds");
                byte[] expected=Arrays.copyOfRange(image,(int)(source-0x40000000L),(int)(source-0x40000000L+0x674));
                if(!hash(expected).equals("d84750d2654065b9908e6d7651af2bc1aa9ac517e7561b563e5cb97008b79a6b"))throw new Exception("Changed SRAM blob");
                a.runSlice();
                if(a.reg("r0")!=start||a.reg("r1")!=0x674||!Arrays.equals(expected,a.mem(start,0x674))||!Arrays.equals(image,a.mem(0x40000000L,image.length)))throw new Exception("Copy boundary mismatch");
                for(byte v:a.mem(start-16,16))if(v!=0x55)throw new Exception("Lower guard changed");
                for(byte v:a.mem(end,16))if(v!=0x55)throw new Exception("Upper guard changed");
                out.printf("PASS %s SHA-256=%s; source=%08x -> 3f000000 length=674; helper arguments r0=3f000000 r1=674; destination/guards/image verified.%n",c[0],c[2],source);
            }
            out.println("Shared loader body [338,790) SHA-256: d545a151267c4916addd3e73dc7bee8d53fe0671cb13c689ef6a3cf4bf4dbc65");
            out.println("Shared SRAM blob SHA-256: d84750d2654065b9908e6d7651af2bc1aa9ac517e7561b563e5cb97008b79a6b");
            out.println("4 controls passed. No helpers modeled or executed, no copied SRAM code executed, no hardware accesses in this slice.");
            out.println("Does not identify low-helper bytes/semantics, prove earlier-stage reachability, establish immutable ROM or validate a recovery procedure.");
        }
    }
}
