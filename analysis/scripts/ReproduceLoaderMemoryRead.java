// Offline loader request parsing and transfer-message creation. Never runs USB DMA.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class ReproduceLoaderMemoryRead extends GhidraScript {
    static final long END=0x43000000L, STATE=0x40fd0d50L, PHASE=0x40d79b21L;
    static final long SETUP=0x42020000L, CMD=0x42030000L, SOURCE=0x42050000L;
    byte[] image;
    class Arm {
        final PcodeEmulator emu=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=emu.newThread();
        List<long[]> queued=new ArrayList<>();List<Long> messages=new ArrayList<>();int allocations=0;
        Arm(int index,int payloadLength,boolean workspace) {
            mem(0x40000000L,image);mem(STATE,new byte[0x80]);put(0x40fd0d4cL,4,0);put(PHASE,1,2);
            mem(SETUP,new byte[8]);put(SETUP,1,0x40);put(SETUP+1,1,0xfd);
            put(SETUP+4,2,index);put(SETUP+6,2,payloadLength);
            mem(CMD,new byte[64]);put(CMD+4,4,64); // explicit old word for the short-command control
            if(workspace)put(STATE,4,CMD);
            byte[] fixture=new byte[64];Arrays.fill(fixture,(byte)0xa5);mem(SOURCE,fixture);
            mem(0x4200e000L,new byte[0x3000]);t.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
        }
        void mem(long a,byte[] b){emu.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] mem(long a,int n){return emu.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v){mem(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(mem(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        long run() throws Exception {return execute(0x401e1280L,SETUP);}
        long execute(long entry,long argument) throws Exception {
            reg("r0",argument);reg("sp",0x42010000L);reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int step=0;step<5000;step++) {
                monitor.checkCancelled();long pc=t.getCounter().getOffset();
                if(pc==END || pc==0x401e020cL || pc==0x40056ac0L || pc==0x40056798L)return pc;
                if(pc==0x40004064L){ret(0);continue;}
                if(pc==0x401e09e8L){
                    if(reg("r0")!=12)throw new Exception("Unexpected message allocation");
                    long ptr=0x42040000L+0x100L*allocations++;mem(ptr,new byte[12]);ret(ptr);continue;
                }
                if(pc==0x401e0090L){
                    if(reg("r0")!=0)throw new Exception("Unexpected queue");
                    long message=reg("r1"),data=val(message+4,4);
                    if(val(message+8,4)!=12)throw new Exception("Message payload size");
                    queued.add(new long[]{val(message,2),val(data,4),val(data+4,4),val(data+8,4)});
                    messages.add(message);
                    ret(0);continue;
                }
                boolean allowed=(pc>=0x401e1280L && pc<0x401e2000L)
                    || (pc>=0x401e0ea0L && pc<0x401e0f7cL)
                    || (pc>=0x401bd1fcL && pc<0x401bd244L)
                    || (pc>=0x4002116cL && pc<0x40021194L)
                    || (pc>=0x401e0b98L && pc<0x401e0e0cL)
                    || (pc>=0x401dfaecL && pc<0x401dfb64L)
                    || (pc>=0x401df9ccL && pc<0x401dfa7cL);
                if(!allowed)throw new Exception("Unexpected execution "+Long.toHexString(pc));t.stepInstruction();
            }
            throw new Exception("Instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong image");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Main-image SHA-256: "+hash);
            out.println("Offline original loader callback, LE32 parser, transfer-message helper and message constructor. No USB controller/DMA executes.");
            long[][] cases={{0x4f2,SOURCE,16,8},{0x4f4,SOURCE,16,8},{0x4f5,SOURCE,16,8},
                {0x4f2,0x10005000L,16,8},{0x4f2,SOURCE,0,8},{0x4f2,0xfffffff0L,64,8},
                {0x4f2,SOURCE,64,4}};
            for(long[] x:cases) {
                int index=(int)x[0],declared=(int)x[3];Arm a=new Arm(index,declared,true);
                byte[] source=a.mem(SOURCE,64);
                if(a.run()!=END || a.val(PHASE,1)!=3 || a.val(STATE+4,1)!=1 || a.queued.size()!=1
                    || !Arrays.equals(a.queued.get(0),new long[]{6,CMD,CMD,declared}))throw new Exception("Setup phase");
                // Model only the declared control payload being received into the queued buffer.
                boolean reversed=index==0x4f4 || index==0x4f5;
                a.put(CMD,4,reversed?x[2]:x[1]);if(declared==8)a.put(CMD+4,4,reversed?x[1]:x[2]);
                int expectedPhase=index==0x4f5?5:4,expectedEvent=index==0x4f5?9:11;
                if(a.run()!=END || a.val(PHASE,1)!=expectedPhase || a.queued.size()!=2
                    || !Arrays.equals(a.queued.get(1),new long[]{expectedEvent,x[1],x[1],x[2]}))throw new Exception("Transfer metadata");
                long boundary=a.execute(0x401e0b98L,a.messages.get(1));
                if(boundary!=(index==0x4f5?0x40056798L:0x40056ac0L) || a.reg("r0")!=x[1] || a.reg("r1")!=x[2])
                    throw new Exception("Worker direction/arguments");
                if(a.run()!=END || a.val(PHASE,1)!=2 || a.val(STATE+4,1)!=0 || a.queued.size()!=2
                    || !Arrays.equals(source,a.mem(SOURCE,64)))throw new Exception("Completion control");
                out.printf("PASS index=%04x address=%08x length=%d declared-payload=%d: queued event=%d; phase 2->3->%d->2; synthetic source unchanged.%n",
                    index,x[1],x[2],declared,expectedEvent,expectedPhase);
            }
            Arm fresh=new Arm(0x4f2,8,false);
            if(fresh.run()!=0x401e020cL || fresh.reg("r0")!=0x80000 || !fresh.queued.isEmpty())throw new Exception("Workspace boundary");
            out.println("PASS fresh workspace: stops before 0x80000-byte allocation at 401e020c, even for the memory-read request.");
            out.println("Original queue-0 consumer also runs to boundary: event 11 reaches 40056ac0 (memory to USB); event 9 reaches 40056798 (USB to memory), with exact address/length. Stops before either executes.");
            out.println("The MMIO/overflow/zero-length cases only create metadata; no referenced bytes are read, written or sent. Four-byte command uses supplied stale second word.");
            out.println("Modeled: message allocation, queue submission, log calls, control-payload delivery and transfer-completion scheduling.");
            out.println("Not live reachability, a safe memory allowlist, coherent snapshot, physical storage read, transport recipe or recovery proof.");
        }
    }
}
