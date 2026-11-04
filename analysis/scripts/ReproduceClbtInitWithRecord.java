// InitClbtData with a real acquired 1024-byte cold-data record as fixture.
// Reports the auto-selected calibration item and the exact cold-buffer diff
// at the save boundary. Offline only: stops BEFORE save helper 40125e4c.
// Args: [0]=output path, [1]=path to 1024-byte record (outside git).
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceClbtInitWithRecord extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, COLD=0x40e4df02L;
    static final int LENGTH=0x01400000;
    static final Set<Long> DISPLAY=Set.of(0x40584e20L,0x401f6fd0L,0x401fd800L,0x402a5790L,0x4023db2cL,0x40584e28L);
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();PcodeThread<byte[]> thread;
        byte[] file;int reads=0,selectorCalls=0;boolean selectorFound=false,selectorNone=false,item7Shortcut=false;
        Arm() {
            bytes(BASE,new byte[LENGTH]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void bytes(long a,byte[] b) {machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n) {return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n) {bytes(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n) {return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v) {var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n) {var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void returned(long value) {reg("r0",value);thread.overrideCounter(toAddr(reg("lr")));}
        void run(long entry,long boundary) throws Exception {
            reg("sp",SP);reg("lr",END);thread.overrideCounter(toAddr(entry));
            for(int i=0;i<30000;i++) {
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==boundary)return;
                if(pc==0x405da930L)selectorCalls++;
                // 405da9e8 (mov r0,#0) executes only on the no-candidate exit;
                // the found exit branches from 405da9d8 straight to 405da9ec.
                if(pc==0x405da9e8L)selectorNone=true;
                if(pc==0x40004064L) {returned(0);continue;}
                if(DISPLAY.contains(pc)) {returned(0);continue;}
                if(pc==0x40122460L) {returned(0x1234);continue;}
                if(pc==0x4000cdf0L) {
                    long address=reg("r0"),length=reg("r2");
                    if(reg("r1")!=0 || length!=30 || address<SP-0x4000 || address+length>SP)
                        throw new Exception("Unexpected memset");
                    bytes(address,new byte[(int)length]);returned(address);continue;
                }
                if(pc==0x40125d94L) {
                    if(reg("r1")!=0 || reg("r2")!=1024 || reg("r3")!=COLD ||
                       !new String(bytes(reg("r0"),22),java.nio.charset.StandardCharsets.US_ASCII).startsWith("A:\\RO_RES\\COLDDATA.BIN"))
                        throw new Exception("Unexpected reload args");
                    reads++;bytes(COLD,file);returned(0);continue;
                }
                boolean admitted=((pc>=0x405da858L && pc<0x405dad90L) || (pc>=0x4051d918L && pc<0x4051d934L) ||
                     (pc>=0x400de164L && pc<0x400de17cL) || (pc>=0x40518c10L && pc<0x40518c30L) ||
                     (pc>=0x40125d98L && pc<0x40125dacL));
                if(!admitted)throw new Exception("Unexpected PC "+Long.toHexString(pc));
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    static int hw(byte[] b,int off) {return (b[off]&0xff) | ((b[off+1]&0xff)<<8);}
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        byte[] record=Files.readAllBytes(Path.of(getScriptArgs()[1]));
        if(record.length!=1024)throw new Exception("Record must be 1024 bytes");
        String recHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(record));
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Fixture record SHA-256: "+recHash+" (live-acquired 2026-11-04, freshness caveat applies)");
            out.printf("Fixture fields: DisableTest=%d FgBootType=%04x FgUsbMode=%04x FgAutoClbt=%04x field168=%04x version=%d,%d,%d%n",
                record[0x0a]&0xff, hw(record,0x68), hw(record,0x6a), hw(record,0x6c), hw(record,0x168),
                hw(record,4), hw(record,6), hw(record,8));
            Arm a=new Arm();a.file=record.clone();
            a.bytes(COLD,record.clone());a.put(0x40e6696dL,1,1);
            a.run(0x405daa2cL,0x40125e4cL);
            if(a.reads!=1 || a.reg("r1")!=0 || a.reg("r2")!=1024 || a.reg("r3")!=COLD ||
               !new String(a.bytes(a.reg("r0"),22),java.nio.charset.StandardCharsets.US_ASCII).startsWith("A:\\RO_RES\\COLDDATA.BIN"))
                throw new Exception("Unexpected save boundary");
            long selected=a.get(0x413e3d9cL,4);
            long choice=a.get(0x413e3da0L,4);
            out.printf("Selector 405da930 calls: %d; no-candidate exit (mov r0,#0 at 405da9e8): %s%n",
                a.selectorCalls, a.selectorNone);
            out.printf("Item7 shortcut taken (only if selected==7 with no selector call): %s%n",
                selected==7 && a.selectorCalls==0);
            out.printf("Selected calibration item [413e3d98+4]: %d%n",selected);
            out.printf("Confirmation choice state [413e3d98+8]: %d%n",choice);
            out.printf("Runtime table +0xabc (from record +0x168): %04x%n", a.get(0x40e13decL+0xabc,2));
            out.printf("Item-20 (AF LAMP) exception applies: %s%n", selected==20?"YES":"no");
            byte[] fin=a.bytes(COLD,1024);
            StringBuilder diff=new StringBuilder();
            for(int i=0;i<1024;i++) if(fin[i]!=record[i])
                diff.append(String.format(" +%02x:%02x->%02x", i, record[i]&0xff, fin[i]&0xff));
            out.println("Cold-buffer RAM diff at save boundary (before writer checksum):" +
                (diff.length()==0?" <none>":diff.toString()));
            out.printf("Cached USB-selection flag 40e6696d after init: %d%n", a.get(0x40e6696dL,1));
            out.println("Stopped before save helper 40125e4c; no file/USB/worker executed. " +
                "Writer would additionally refresh the byte-sum checksum stored at +0x3fe in the saved file.");
            out.flush();
        }
    }
}
