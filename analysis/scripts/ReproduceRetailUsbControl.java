// Original control classification, registration, queue-copy and target dispatch.
// Offline only: allocator, queue delivery, printf and reset ZLP are modeled.
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

public class ReproduceRetailUsbControl extends GhidraScript {
    static final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L;
    static final long INPUT=0x42020000L, MSG=0x42020100L, PAYLOAD=0x42020200L, TABLE=0x40fc7b78L;
    static final int LENGTH=0x01400000;
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        PcodeThread<byte[]> thread;
        int allocations=0,queues=0,transfers=0; boolean modelReset=false;
        Arm() {
            bytes(BASE,new byte[LENGTH]);bytes(BASE,image);bytes(SP-0x4000,new byte[0x8000]);bytes(INPUT,new byte[0x400]);
            bytes(TABLE,new byte[0x90]);put(MSG+4,PAYLOAD,4);
            put(0x40e6696dL,1,1);
            byte[] cold=new byte[1024];for(int i=0;i<cold.length;i++)cold[i]=(byte)(i*37+11);
            cold[0x6a]=0x14;cold[0x6b]=1;bytes(0x40e4df02L,cold);
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
        void run(long entry,long arg,long boundary) throws Exception {
            reg("sp",SP);reg("lr",END);reg("r0",arg);thread.overrideCounter(toAddr(entry));
            for(int i=0;i<4000;i++) {
                monitor.checkCancelled();long pc=thread.getCounter().getOffset();
                if(pc==boundary)return;
                if(pc==0x401bd1fcL) {
                    if(reg("r0")!=8 || ++allocations!=1)throw new Exception("Unexpected allocation");
                    returned(MSG);continue;
                }
                if(pc==0x401e0090L) {
                    if(reg("r0")!=1 || reg("r1")!=MSG || ++queues!=1)throw new Exception("Unexpected queue request");
                    returned(0);continue;
                }
                if(pc==0x40004064L) {returned(0);continue;}
                if(pc==0x401e0ea0L && modelReset) {
                    if(reg("r0")!=1 || reg("r1")!=0 || reg("r2")!=0 || ++transfers!=1)
                        throw new Exception("Unexpected reset status transfer");
                    returned(0);continue;
                }
                if(!((pc>=0x401e2104L && pc<0x401e2138L) ||
                     (pc>=0x402e6314L && pc<0x402e6350L) ||
                     (pc>=0x401ba258L && pc<0x401ba290L) ||
                     (pc>=0x401e941cL && pc<0x401e9550L) ||
                     (pc>=0x401b9ae8L && pc<0x401b9b90L) ||
                     (pc>=0x401ba030L && pc<0x401ba144L) ||
                     (pc>=0x401b9ebcL && pc<0x401b9f44L) ||
                     (pc>=0x4033555cL && pc<0x40335690L) ||
                     (pc>=0x401dfcccL && pc<0x401dfcd8L) ||
                     (pc>=0x402e62d8L && pc<0x402e6308L) ||
                     (pc>=0x401e1010L && pc<0x401e1020L)))
                    throw new Exception("Unexpected PC "+Long.toHexString(pc));
                thread.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
        void register(int target) throws Exception {
            put(TABLE+0x14,target,1); // selected personality supplied BEFORE registration
            run(0x401e2104L,0,END);run(0x402e6314L,0,END);
            if(get(TABLE+0x54,4)!=0x401e1280L || get(TABLE+0x54+8,4)!=0 ||
               get(TABLE+0x40+8,4)!=0x4033555cL || get(TABLE+8,4)!=0x402e4f80L ||
               get(TABLE+0x14,1)!=target)
                throw new Exception("Unexpected original registration result");
        }
        void classify(int type,int request) throws Exception {
            byte[] packet={(byte)type,(byte)request,0x34,0x12,(byte)0xf7,4,3,0};bytes(INPUT,packet);
            run(0x401e941cL,INPUT,END);
            int expected=(type&0x60)==0x40?15:14;
            if(allocations!=1 || queues!=1 || get(MSG,2)!=expected || !Arrays.equals(packet,bytes(PAYLOAD,8)))
                throw new Exception("Wrong classified queue message");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image hash");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Original registrations 401e2104 and 402e6314 executed; selected target byte supplied, initializers/mode switch NOT executed.");
            out.println("Original setup classifier and 8-byte queue copy execute; allocator and enqueue modeled; queue consumer delivery supplied.");
            for(int target:new int[]{0,2})for(boolean registered:new boolean[]{false,true}) {
                Arm a=new Arm();a.put(TABLE+0x14,target,1);
                if(registered)a.register(target);
                a.put(MSG,0,2);byte[] expected=a.bytes(BASE,LENGTH);
                long boundary=registered?(target==0?0x401e122cL:0x402e4f80L):0x401bd278L;
                a.run(0x401ba030L,MSG,boundary);
                if(!Arrays.equals(expected,a.bytes(BASE,LENGTH)) || a.allocations!=0 || a.queues!=0 ||
                    (!registered && a.reg("r2")!=0xb3))throw new Exception("Initializer dependency control");
                out.printf("PASS: reset/init event 0, target=%d registered=%s -> boundary=%08x; table/globals unchanged, no fallback registration.%n",
                    target,registered,boundary);
            }
            for(int target:new int[]{2,0})for(int type:new int[]{0x40,0xc0})for(int request:new int[]{0x70,0xfa,0xfd}) {
                Arm a=new Arm();a.register(target);byte[] expected=a.bytes(BASE,LENGTH);
                a.classify(type,request);a.run(0x401ba030L,MSG,target==2?END:0x401e1280L);
                if(!Arrays.equals(expected,a.bytes(BASE,LENGTH)))throw new Exception("Vendor route changed globals");
                out.printf("PASS: target=%d type=%02x request=%02x event=15 -> %s; image/globals unchanged.%n",
                    target,type,request,target==2?"null callback return":"loader entry boundary 401e1280 (not executed)");
            }
            for(int request:new int[]{0x64,0x66,0x67,0xfd}) {
                Arm a=new Arm();a.register(2);a.put(0x40fd0d0dL,0,1);
                a.put(0x40df78ecL,7,1);a.put(0x4136b674L,0x11223344L,4);
                byte[] expected=a.bytes(BASE,LENGTH);
                a.classify(request==0x67?0xa1:0x21,request);
                long boundary=request==0x64?0x4005714cL:request==0x66?0x400560a0L:request==0x67?0x401cafa0L:0x401bd278L;
                if(request==0x66) {
                    a.modelReset=true;expected[(int)(0x40df78ecL-BASE)]=2;
                    Arrays.fill(expected,(int)(0x4136b674L-BASE),(int)(0x4136b678L-BASE),(byte)0);
                }
                a.run(0x401ba030L,MSG,boundary);
                if(request==0x66 && (a.transfers!=1 || a.reg("r0")!=3 || a.reg("r1")!=1))throw new Exception("Reset boundary mismatch");
                if(!Arrays.equals(expected,a.bytes(BASE,LENGTH)))throw new Exception("Unexpected class route global changes");
                out.printf("PASS: target=2 class request=%02x event=14 -> boundary=%08x; %s; USB selection/cache/cold RAM preserved.%n",
                    request,boundary,request==0x66?"only protocol-state byte and session word change":"image/globals unchanged");
            }
            out.println("Reset case models zero-length status transfer and stops BEFORE USB MMIO helper 400560a0(3,1).");
            out.println("Other class controls stop BEFORE cancellation helper, status encoding, or unknown-request diagnostic.");
            out.println("No camera I/O, loader, shell, RTC/file calls, hardware setup/reset, real queue/allocator or response transfer executed.");
        }
    }
}
