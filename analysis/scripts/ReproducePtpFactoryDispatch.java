// Connect original ARM marker, registration, dispatcher, factory reader and response state.
// Offline fixtures only; stops before any unexpected callee, including persistent writers.
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

public class ReproducePtpFactoryDispatch extends GhidraScript {
    static final long BASE=0x40000000L, SP=0x42010000L, END=0x43000000L;
    static final long FLAG=0x40e139d8L, TABLE=0x4136b684L, COLD=0x40e4df02L;
    static final int GLOBALS=0x1400000, TABLE_BYTES=1600;
    static final long REQUEST=0x42020000L, DATA=0x42030000L, OUT=0x42040000L;
    byte[] image, disk;
    class Arm {
        final PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        final PcodeThread<byte[]> thread;
        boolean requestPhase=false, factoryOpenOK=true;
        int callbacks=0, factoryReads=0, notifications=0;
        long fileStatus=-1;
        int opens=0,sizes=0,closes=0; boolean filePresent=true;long fileSize=1;
        List<String> common=new ArrayList<>();
        Arm() {
            bytes(BASE,new byte[GLOBALS]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);bytes(REQUEST,new byte[64]);bytes(DATA,new byte[65536]);bytes(OUT,new byte[64]);
            byte[] cold=new byte[1024];for(int i=0;i<1024;i++)cold[i]=(byte)(i*37+11);bytes(COLD,cold);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void bytes(long a,byte[] b){machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n){return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n){return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);thread.overrideCounter(toAddr(reg("lr")));}
        boolean in(long pc,long a,long b){return pc>=a && pc<b;}
        void execute(long entry,long boundary) throws Exception {
            reg("sp",SP);reg("lr",END);thread.overrideCounter(toAddr(entry));
            for(int step=0;step<30000;step++) {
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==boundary)return;
                long r0=reg("r0"),r1=reg("r1"),r2=reg("r2"),r3=reg("r3");
                if(requestPhase && pc==0x405ca67cL)callbacks++;
                if(requestPhase && pc==0x405cac48L)fileStatus=r0;
                if(requestPhase && pc==0x4013f4acL) {
                    byte[] path="A:\\RO_RES\\COLDDATA.BIN\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if(r1!=0x10 || !Arrays.equals(path,bytes(r0,path.length)))throw new Exception("Wrong factory path/open flags");
                    ret(factoryOpenOK?0x2345:0);continue;
                }
                if(requestPhase && pc==0x4013f500L){expect(r0==0x2345,"Factory size handle");ret(1024);continue;}
                if(requestPhase && pc==0x4013f604L){expect(r0==0x2345,"Factory close handle");ret(0);continue;}
                if(requestPhase && pc==0x40150e20L){expect(r0==0x2345 && r2==0 && r3==0 && get(reg("sp"),4)==0,"Factory seek");ret(0);continue;}
                if(requestPhase && pc==0x4013f598L){expect(r0==0x2345 && r1==COLD && r2==1024,"Factory read range");bytes(COLD,disk);factoryReads++;ret(1024);continue;}
                if(requestPhase && (pc==0x400410ccL || pc==0x4015b5f8L)){ret(0);continue;}
                if(requestPhase && (pc==0x4000cdf0L || pc==0x40008b8cL)) {
                    expect(r2<=1024,"Oversize copy/clear");bytes(r0,pc==0x40008b8cL?bytes(r1,(int)r2):new byte[(int)r2]);ret(r0);continue;
                }
                if(requestPhase && pc==0x4006993cL){expect(r0==1 && get(r1,4)==0x6251001aL,"Unexpected notification");notifications++;ret(0);continue;}
                if(pc==0x4013f4acL) {
                    byte[] name="D:\\EnablePTPAP.txt\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                    if(r1!=0x10 || !Arrays.equals(name,bytes(r0,name.length)))throw new Exception("Wrong marker open");
                    opens++;ret(filePresent?0x1234:0);continue;
                }
                if(pc==0x4013f500L){if(r0!=0x1234)throw new Exception("Wrong size handle");sizes++;ret(fileSize);continue;}
                if(pc==0x4013f604L){if(r0!=0x1234)throw new Exception("Wrong close handle");closes++;ret(0);continue;}
                if(pc==0x40004064L){ret(0);continue;}
                if(pc==0x405e6868L && r0!=0x100) {
                    int count=(r0==0x20f || r0==0x208)?1:2;
                    if(!Set.of(0x20fL,0x101L,0x203L,0x209L,0x20cL,0x208L).contains(r0))throw new Exception("Unexpected common selector");
                    common.add(String.format("config:%x:%x%s",r0,r1,count==1?"":":"+Long.toHexString(r2)));
                    ret(1);continue;
                }
                if(pc==0x405e6bc4L) {
                    if(!Set.of(7L,8L,9L,12L).contains(r0) || r2!=0 || r3!=0 || get(reg("sp"),4)!=0)
                        throw new Exception("Unexpected callback configuration");
                    common.add(String.format("callback:%x:%x",r0,r1));ret(0);continue;
                }
                if(pc==0x40334f34L && !requestPhase){if(r0!=0x2001)throw new Exception("Bad close response");ret(0);continue;}
                boolean admitted=in(pc,0x405219ccL,0x405219e4L)||in(pc,0x4015c52cL,0x4015c560L)
                    ||in(pc,0x405c9030L,0x405c9044L)||in(pc,0x405ca428L,0x405ca5b8L)
                    ||in(pc,0x405e6868L,0x405e6a20L)||in(pc,0x402e753cL,0x402e7598L)
                    ||in(pc,0x402e8e80L,0x402e8f1cL);
                if(requestPhase)admitted |= in(pc,0x402e75e8L,0x402e76a8L)||in(pc,0x402e7744L,0x402e7dd0L)
                    ||in(pc,0x402ed66cL,0x402ed734L)||in(pc,0x405ca67cL,0x405cad84L)
                    ||in(pc,0x405e6ac0L,0x405e6b58L)||in(pc,0x40334f34L,0x40334f94L)
                    ||in(pc,0x40334f9cL,0x40335004L)||pc==0x40125d94L||in(pc,0x4015ba48L,0x4015bb38L);
                if(!admitted)throw new Exception("Unexpected PC; not executed: "+Long.toHexString(pc));
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
        void gate() throws Exception {requestPhase=false;execute(0x405c9030L,0x405c9044L);}
        long[] dispatch(int opcode,long parameter) throws Exception {
            requestPhase=true;fileStatus=-1;
            bytes(REQUEST,new byte[64]);put(REQUEST,16,4);put(REQUEST+4,1,2);put(REQUEST+6,opcode,2);put(REQUEST+8,1,4);put(REQUEST+12,parameter,4);
            put(SP,DATA,4);put(SP+4,65536,4);put(SP+8,OUT,4);put(SP+12,OUT+4,4);
            reg("r0",opcode);reg("r1",1);reg("r2",REQUEST+12);reg("r3",4);
            execute(0x402e778cL,END);
            // Original response extractor returns code, parameters and byte count.
            reg("r0",OUT+8);reg("r1",OUT+16);reg("r2",OUT+12);execute(0x40334f9cL,END);
            int count=(int)get(OUT+12,4)/4;expect(count<=5,"Response parameter limit");
            long[] result=new long[count+1];result[0]=get(OUT+8,2);
            for(int i=0;i<count;i++)result[i+1]=get(OUT+16+4*i,4);
            return result;
        }
    }
    void expect(boolean condition,String message)throws Exception{if(!condition)throw new Exception(message);}
    void checkChunk(long[] reply,byte[] expected,int offset)throws Exception {
        expect(reply.length==6 && reply[0]==0x2001 && reply[1]==0,"Chunk schema");
        for(int w=0;w<4;w++)expect(reply[w+2]==Utils.bytesToLong(Arrays.copyOfRange(expected,offset+4*w,offset+4*w+4),4,false),"Chunk mismatch");
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        expect(hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"),"Wrong main image");
        disk=new byte[1024];for(int i=0;i<1022;i++)disk[i]=(byte)(i*17+3);
        int sum=0;for(int i=0;i<1022;i++)sum=(sum+(disk[i]&255))&255;disk[1022]=(byte)sum;
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original 2.00 SHA-256: "+hash);
            out.println("Integrated software path: marker gate -> actual registration -> normal dispatcher -> factory callback -> original file wrapper -> actual response setter/extractor.");
            out.println("Modeled: filesystem primitives, logging/first-use helper, memcpy/memset, shared initialization calls and notification delivery. Synthetic RAM/session/request/disk; no real USB/storage/boot.");
            Arm off=new Arm();off.filePresent=false;off.gate();byte[] cached=off.bytes(COLD,1024);
            long[] reply=off.dispatch(0x1027,0);
            expect(Arrays.equals(reply,new long[]{0x2005}) && off.callbacks==0 && off.factoryReads==0 && Arrays.equals(cached,off.bytes(COLD,1024)),"Disabled dispatch");
            out.println("PASS absent marker: actual default dispatcher returns 2005, no factory callback/file read; cached buffer preserved.");
            Arm on=new Arm();on.gate();cached=on.bytes(COLD,1024);byte[] table=on.bytes(TABLE,TABLE_BYTES);
            checkChunk(on.dispatch(0x1027,0),cached,0);expect(on.callbacks==1 && on.factoryReads==0 && Arrays.equals(cached,on.bytes(COLD,1024)),"Enabled cached probe");
            out.println("PASS present marker: actual dynamic lookup calls 405ca67c; 1027(0) returns five parameters containing first cached chunk, no file read.");
            reply=on.dispatch(0x1025,0);expect(Arrays.equals(reply,new long[]{0x2001}) && on.fileStatus==0 && on.factoryReads==1 && Arrays.equals(disk,on.bytes(COLD,1024)),"Load through dispatcher");
            out.println("PASS 1025(0): dispatcher reaches actual file wrapper; modeled successful lower read fills all 1024 bytes; response 2001 with zero parameters.");
            byte[] reconstructed=new byte[1024];
            for(int offBytes=0;offBytes<1024;offBytes+=16) {
                reply=on.dispatch(0x1027,offBytes);checkChunk(reply,disk,offBytes);
                for(int w=0;w<4;w++)System.arraycopy(Utils.longToBytes(reply[w+2],4,false),0,reconstructed,offBytes+w*4,4);
                expect(Arrays.equals(disk,on.bytes(COLD,1024)),"Chunk changed factory buffer");
            }
            expect(Arrays.equals(disk,reconstructed) && Arrays.equals(table,on.bytes(TABLE,TABLE_BYTES)) && on.factoryReads==1,"Acquisition mismatch");
            out.println("PASS 64 bounded chunk requests through dispatcher reconstruct synthetic disk exactly; factory buffer/table preserved; no additional file reads.");
            on.filePresent=false;on.gate();checkChunk(on.dispatch(0x1027,0),disk,0);expect(on.get(FLAG,1)==0,"Marker not cleared");
            out.println("PASS marker removed/re-entry: flag zero but actual dispatcher still reaches cached-read callback; registration survives.");
            on.requestPhase=false;on.execute(0x402e8e80L,END);checkChunk(on.dispatch(0x1027,0),disk,0);
            out.println("PASS selected CloseSession does not revoke callback; subsequent direct dispatcher control still reaches it. This is not a host-session acceptance test.");
            Arm failed=new Arm();failed.gate();failed.factoryOpenOK=false;cached=failed.bytes(COLD,1024);
            reply=failed.dispatch(0x1025,0);expect(Arrays.equals(reply,new long[]{0x2001}) && failed.fileStatus==1 && failed.factoryReads==0,"Ignored open failure");
            checkChunk(failed.dispatch(0x1027,0),cached,0);expect(Arrays.equals(cached,failed.bytes(COLD,1024)),"Failed load changed cache");
            out.println("PASS failed file open through integrated path: wrapper reports error 1 internally, PTP still 2001; next chunk returns old cached bytes.");
            Arm write=new Arm();write.gate();
            try {write.dispatch(0x1025,1);throw new Exception("Writer boundary not reached");}
            catch(Exception e){expect(e.getMessage().equals("Unexpected PC; not executed: 40125e4c"),"Unexpected write-control boundary: "+e);}
            out.println("PASS synthetic forbidden-write control: dispatcher routes 1025(1) to persistent writer 40125e4c; stopped BEFORE writer executes.");
            out.println("No physical camera data or commands. Does not prove storage freshness, all shared initialization effects, automatic host traffic, USB/session transport, cold restart, RAM boot or recovery.");
        }
    }
}
