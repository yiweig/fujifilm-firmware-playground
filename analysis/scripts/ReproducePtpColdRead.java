// Offline verification of the original factory PTP callback, never USB I/O.
// Baseline stubs the file wrapper; freshness controls execute that wrapper
// with modeled lower file APIs. Log, memcpy/memset and response calls are stubbed.
// Stops before any unexpected external call, including persistent writes.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import ghidra.program.model.address.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproducePtpColdRead extends GhidraScript {
    static final long COLD=0x40e4df02L, REQUEST=0x42020000L, SP=0x42010000L, END=0x43000000L;
    byte[] code, fixture=new byte[1024];
    class Arm {
        final PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        final PcodeThread<byte[]> thread;
        int reads=0; long responseCode=-1; List<Long> response=new ArrayList<>();
        boolean realReader=false; int fileCase=0, lowerReads=0, closes=0;
        long readStatus=-1; byte[] disk;
        List<Long> readErrorFormats=new ArrayList<>(), noticeFormats=new ArrayList<>();
        Arm() {
            machine.getSharedState().setVar(ram,0x40000000L,code.length,true,code);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++) reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"}) reg(n,0);
            bytes(SP-0x1000,new byte[0x2000]);bytes(REQUEST,new byte[0x40]);
            bytes(0x413e32d8L,new byte[0x80]);bytes(COLD,new byte[1024]);
        }
        void bytes(long a,byte[] b) {machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n) {return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v) {bytes(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n) {return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v) {
            var r=currentProgram.getRegister(n);
            thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));
            if(n.equals("pc")) thread.overrideCounter(toAddr(v));
        }
        long reg(String n) {
            var r=currentProgram.getRegister(n);
            return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);
        }
        long request(int opcode,long param) throws Exception {
            put(REQUEST,4,16);put(REQUEST+4,2,1);put(REQUEST+6,2,opcode);
            put(REQUEST+8,4,1);put(REQUEST+12,4,param);
            for(int i=1;i<5;i++) put(REQUEST+12+4*i,4,0);
            reg("r0",REQUEST);reg("sp",SP);reg("lr",END);reg("pc",0x405ca67cL);
            responseCode=-1;response.clear();
            for(int i=0;i<4000;i++) {
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==END)return pc;
                long r0=reg("r0"),r1=reg("r1"),r2=reg("r2"),r3=reg("r3");
                if(pc==0x40004064L) {
                    if(realReader && reg("lr")>=0x4015ba48L && reg("lr")<0x4015bb38L)readErrorFormats.add(r0);
                    reg("pc",reg("lr"));continue;
                }
                if(pc==0x40125d94L) {
                    if(r1!=0 || r2!=1024 || r3!=COLD || get(r0,4)!=0x525c3a41L)
                        throw new Exception("Unexpected file read arguments");
                    if(realReader) {reads++;thread.stepInstruction();continue;}
                    bytes(COLD,fixture);reads++;reg("r0",0);reg("pc",reg("lr"));continue;
                }
                if(realReader && pc==0x405cac48L)readStatus=r0;
                if(realReader && (pc==0x400410ccL || pc==0x4015b5f8L)) {
                    if(pc==0x400410ccL) {
                        if(r0!=0)throw new Exception("Unexpected notice channel");
                        noticeFormats.add(r1);
                    }
                    reg("r0",0);reg("pc",reg("lr"));continue;
                }
                if(realReader && pc==0x4013f4acL) {
                    if(r1!=16)throw new Exception("Unexpected open flags");
                    reg("r0",fileCase==1?0:0x1234);reg("pc",reg("lr"));continue;
                }
                if(realReader && pc==0x4013f500L) {
                    if(r0!=0x1234)throw new Exception("Unexpected size handle");
                    reg("r0",fileCase==2?1023:1024);reg("pc",reg("lr"));continue;
                }
                if(realReader && pc==0x40150e20L) {
                    if(r0!=0x1234 || r2!=0 || r3!=0 || get(reg("sp"),4)!=0)
                        throw new Exception("Unexpected seek arguments");
                    reg("r0",0);reg("pc",reg("lr"));continue;
                }
                if(realReader && pc==0x4013f598L) {
                    if(r0!=0x1234 || r1!=COLD || r2!=1024)throw new Exception("Unexpected lower read");
                    int count=fileCase==3?16:1024;
                    bytes(COLD,Arrays.copyOf(disk,count));lowerReads++;
                    reg("r0",count);reg("pc",reg("lr"));continue;
                }
                if(realReader && pc==0x4013f604L) {
                    if(r0!=0x1234)throw new Exception("Unexpected close handle");
                    closes++;reg("r0",0);reg("pc",reg("lr"));continue;
                }
                if(pc==0x4000cdf0L || pc==0x40008b8cL) {
                    if(r2>1024)throw new Exception("Unexpected copy size");
                    bytes(r0,pc==0x40008b8cL?bytes(r1,(int)r2):new byte[(int)r2]);
                    reg("pc",reg("lr"));continue;
                }
                if(pc==0x405e6ac0L) {
                    responseCode=r0;
                    for(int j=0;j<r1;j++)response.add(j==0?r2:j==1?r3:get(reg("sp")+4*(j-2),4));
                    reg("r0",0);reg("pc",reg("lr"));continue;
                }
                // The callback has no other permitted external callees in this test.
                // Return the unexpected callee without executing it.
                if((pc<0x405ca67cL || pc>=0x405cad84L)
                    && !(realReader && pc>=0x4015ba48L && pc<0x4015bb38L))return pc;
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit at "+thread.getCounter());
        }
    }
    public void run() throws Exception {
        code=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),code);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))
            throw new Exception("Wrong firmware hash");
        for(int i=0;i<fixture.length;i++)fixture[i]=(byte)(i*37+11);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original 2.00 SHA-256: "+hash);
            out.println("Offline callback test; synthetic file, request, RAM; no real USB or storage.");
            out.println("Baseline stubs: 40004064 log, 40125d94 read, 4000cdf0 zero, 40008b8c copy, 405e6ac0 response.");
            Arm probe=new Arm();
            probe.bytes(COLD,fixture);
            if(probe.request(0x1027,0)!=END || probe.responseCode!=0x2001
                || probe.response.size()!=5 || probe.response.get(0)!=0 || probe.reads!=0
                || !Arrays.equals(fixture,probe.bytes(COLD,1024)))
                throw new Exception("Standalone chunk probe changed buffer or invoked file reader");
            for(int w=0;w<4;w++)
                if(probe.response.get(w+1)!=Utils.bytesToLong(Arrays.copyOfRange(fixture,4*w,4*w+4),4,false))
                    throw new Exception("Standalone probe returned wrong cached bytes");
            out.println("PASS: standalone 1027(0), without 1025 load, returns exactly first 16 cached bytes; no file read and cold buffer unchanged.");
            Arm a=new Arm();
            if(a.request(0x1025,0)!=END || a.responseCode!=0x2001 || !a.response.isEmpty() || a.reads!=1)
                throw new Exception("Unexpected read initialization");
            byte[] result=new byte[1024];
            for(int off=0;off<1024;off+=16) {
                if(a.request(0x1027,off)!=END || a.responseCode!=0x2001 || a.response.size()!=5 || a.response.get(0)!=0)
                    throw new Exception("Unexpected chunk response at "+off);
                for(int w=0;w<4;w++)System.arraycopy(Utils.longToBytes(a.response.get(w+1),4,false),0,result,off+4*w,4);
            }
            if(!Arrays.equals(fixture,result) || !Arrays.equals(fixture,a.bytes(COLD,1024)))
                throw new Exception("Read changed data or reconstruction failed");
            out.println("PASS: 1025 parameter 0 reads 1024 bytes; 64 x 1027 read chunks reconstruct fixture exactly; cold RAM unchanged by chunk reads.");
            Arm write=new Arm();long stopped=write.request(0x1025,1);
            if(stopped!=0x40125e4cL)throw new Exception("Write control did not reach expected writer");
            out.println("PASS: 1025 parameter 1 reaches persistent writer 40125e4c; stopped before execution.");
            freshness(out);
            out.println("Does not verify normal-mode reachability, marker lifecycle, USB transport, actual file-read success, or camera state.");
        }
    }

    void freshness(PrintWriter out) throws Exception {
        byte[] old=fixture.clone();seal(old);
        byte[] changed=old.clone();changed[0]++;changed[1]--;changed[0x300]++;changed[0x301]--;seal(changed);
        String successfulOld=null;
        for(int kind=0;kind<5;kind++) {
            // 0: successful old; 1: failed open; 2: short file; 3: short read;
            // 4: successful changed. In 1..3, disk contents differ from old RAM.
            Arm a=new Arm();a.realReader=true;a.fileCase=kind;a.disk=kind==0?old:changed;
            a.bytes(COLD,old);byte[] expected=old.clone();
            if(kind==3)System.arraycopy(changed,0,expected,0,16);
            if(kind==4)expected=changed.clone();
            StringBuilder wire=new StringBuilder();
            for(int pass=0;pass<2;pass++) {
                if(a.request(0x1025,0)!=END || a.responseCode!=0x2001 || !a.response.isEmpty()
                    || a.readStatus!=(kind>=1 && kind<=3?1:0))throw new Exception("Load/status mismatch "+kind);
                wire.append(a.responseCode).append(a.response);
                byte[] reconstructed=new byte[1024];
                for(int off=0;off<1024;off+=16) {
                    if(a.request(0x1027,off)!=END || a.responseCode!=0x2001 || a.response.size()!=5 || a.response.get(0)!=0)
                        throw new Exception("Chunk mismatch");
                    wire.append(a.responseCode).append(a.response);
                    for(int w=0;w<4;w++)System.arraycopy(Utils.longToBytes(a.response.get(w+1),4,false),0,reconstructed,off+4*w,4);
                }
                if(!Arrays.equals(expected,reconstructed) || !Arrays.equals(expected,a.bytes(COLD,1024)))
                    throw new Exception("Record mismatch "+kind);
                int sum=0;for(int i=0;i<1022;i++)sum=(sum+(reconstructed[i]&255))&255;
                if((reconstructed[1022]&255)!=sum || reconstructed[1023]!=0)throw new Exception("Invalid fixture checksum");
            }
            int expectedReads=kind==1 || kind==2?0:2;
            if(a.reads!=2 || a.lowerReads!=expectedReads || a.closes!=(kind==1?0:2))throw new Exception("API counts mismatch");
            if(!a.noticeFormats.equals(List.of(0x40b06dddL,0x40b06df8L,0x40b06dddL,0x40b06df8L)))
                throw new Exception("Start/end notice mismatch");
            long error=kind==1?0x40b06d16L:kind==2?0x40b06cf8L:kind==3?0x40b06ce6L:0;
            if(!a.readErrorFormats.equals(error==0?List.of():List.of(error,error)))throw new Exception("Error log mismatch");
            if(kind==0)successfulOld=wire.toString();
            if((kind==1 || kind==2) && !successfulOld.equals(wire.toString()))throw new Exception("Unexpected failure signal");
            if(kind==3 && (Arrays.equals(expected,old) || Arrays.equals(expected,changed)))throw new Exception("Not a mixed record");
            out.printf("PASS freshness case=%d: wrapper status=%d; 2 matching checksum-valid records; lower reads=%d closes=%d.%n",
                kind,a.readStatus,a.lowerReads,a.closes);
            out.printf("  Log calls: same start/end notices on both reads; error format=%08x (0 means no error call).%n",error);
        }
        out.println("Cases: 0=success(old), 1=open failure, 2=short file, 3=16-byte short read, 4=success(changed).");
        out.println("Cases 1/2 return exactly the same response codes/parameters as case 0 despite different modeled disk data.");
        out.println("Case 3 returns a checksum-valid mixture of new prefix and old suffix, distinct from either full record.");
        out.println("Freshness controls execute original 40125d94/4015ba48 wrapper and callback; lower file APIs, logs, copy/zero and response are modeled.");
        out.println("Log-call formats/channels are checked; actual formatting, output and retention are not executed here. First-use helper 4015b5f8 remains stubbed.");
    }
    void seal(byte[] data) {
        int sum=0;for(int i=0;i<1022;i++)sum=(sum+(data[i]&255))&255;
        data[1022]=(byte)sum;data[1023]=0;
    }
}
