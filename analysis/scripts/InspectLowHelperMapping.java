// Original low-map table/copy slices; CP15 and barriers recorded/modelled, never hardware translation.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.plugin.processors.sleigh.SleighLanguage;
import ghidra.pcode.emu.*;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.utils.Utils;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

public class InspectLowHelperMapping extends GhidraScript {
    final long BASE=0x40000000L, END=0x43000000L, SP=0x42010000L, RECORD=0x42100000L;
    byte[] image;
    class Arm {
        final PcodeEmulator m=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        final PcodeThread<byte[]> t=m.newThread();
        byte[] expectedImage;
        Arm(){
            bytes(BASE,image);bytes(0x3f000000L,new byte[0x10000]);bytes(SP-256,new byte[512]);bytes(RECORD,new byte[256]);
            expectedImage=bytes(BASE,image.length);
            t.overrideContextWithDefault();for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","OV","ZR","NG"})reg(n,0);
            reg("sp",SP);reg("lr",END);
        }
        void bytes(long a,byte[] b){m.getSharedState().setVar(currentProgram.getLanguage().getDefaultSpace(),a,b.length,true,b);}
        byte[] bytes(long a,int n){return m.getSharedState().getVar(currentProgram.getLanguage().getDefaultSpace(),a,n,true,Reason.INSPECT);}
        void put(long a,long v,int n){bytes(a,Utils.longToBytes(v,n,false));}
        long val(long a,int n){return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v){var r=currentProgram.getRegister(n);t.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));}
        long reg(String n){var r=currentProgram.getRegister(n);return Utils.bytesToLong(t.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);}
        void ret(long v){reg("r0",v);t.overrideCounter(toAddr(reg("lr")));}
        final List<String> maintenance=new ArrayList<>();
        final Set<Long> stops=new HashSet<>();
        void snapshot(){expectedImage=bytes(BASE,image.length);}
        long execute(long entry,long stop) throws Exception {
            reg("sp",SP);reg("lr",END);t.overrideCounter(toAddr(entry));
            for(int n=0;n<10000;n++){
                monitor.checkCancelled();long pc=t.getCounter().getOffset();if(pc==END||pc==stop)return pc;
                boolean allowed=(pc>=0x400010a0L&&pc<0x4000112cL)||(pc>=0x40001840L&&pc<0x40001898L)
                    ||(pc>=0x400012b4L&&pc<0x400012c0L)||(pc>=0x400005f0L&&pc<0x40000640L)
                    ||(pc>=0x400007c0L&&pc<0x400007ecL);
                if(!allowed)throw new Exception("Unexpected mapping PC "+Long.toHexString(pc));
                long w=val(pc,4);
                if(w==0xf57ff05fL||w==0xf57ff04fL||w==0xf57ff06fL){maintenance.add("barrier:"+Long.toHexString(pc));t.overrideCounter(toAddr(pc+4));continue;}
                if(w==0xee083f37L||w==0xee073fd5L||w==0xee023f50L){maintenance.add(String.format("CP15:%08x value=%08x",pc,reg("r3")));t.overrideCounter(toAddr(pc+4));continue;}
                t.stepInstruction();
            }
            throw new Exception("Instruction bound");
        }
        // Address arithmetic only: ignores permissions, TLBs, FCSE, security and stage 2.
        long translated(long va) throws Exception {
            long d=val(0x3f004000L+((va>>>20)*4),4);
            if((d&3)==0)return -1;
            if((d&3)==2&&(d&(1L<<18))==0)return (d&0xfff00000L)|(va&0xfffffL);
            if((d&3)==1){long q=val((d&0xfffffc00L)+((va>>>12)&255)*4,4);if((q&3)==1)return (q&0xffff0000L)|(va&65535);}
            throw new Exception("Unsupported descriptor geometry");
        }
        void unchanged() throws Exception {if(!Arrays.equals(expectedImage,bytes(BASE,image.length)))throw new Exception("Loaded image changed");}
    }
    void require(boolean b,String label) throws Exception {if(!b)throw new Exception(label);}
    String hash(byte[] b) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String h=hash(image);require(h.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"),"Wrong main image");
        byte[] main=image,updater=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(getScriptArgs()[1]));
        require(hash(updater).equals("4bdcdb060c89dcaa18b77d9d7b5f1a4e337627137f0b7c8d9a79a9dcf4048976"),"Wrong complete updater");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])){
            out.println("MAIN SHA256 "+h);out.println("UPDATER SHA256 "+hash(updater));
            out.println("Original table construction and selected mapping/copy slices. CP15/barriers recorded and skipped. Conditional ARM short-descriptor address arithmetic only, not MMU/hardware execution or a memory dump.");
            for(int mode=0;mode<3;mode++){
                Arm a=new Arm();require(a.execute(0x400010a0L,-1)==END,"Table constructor");
                require(a.val(0x3f004000L,4)==0x3f006001L&&a.val(0x3f006000L,4)==0x5615L&&a.val(0x3f00603cL,4)==0x5615L,"Low coarse/large-page entries");
                if(mode==1)require(a.execute(0x40001840L,-1)==END,"Clear low entry");
                if(mode==2)require(a.execute(0x4000186cL,-1)==END,"Enable low section");
                require(a.translated(0xb34)==(mode==1?-1:0xb34),"Low mapping");
                require(a.translated(0x3f000b34L)==0x3f000b34L,"SRAM mapping stays distinct");a.unchanged();
                out.printf("PASS main map stage=%d L1[0]=%08x low-b34=%s SRAM-3f000b34=%08x maintenance=%s%n",mode,a.val(0x3f004000L,4),mode==1?"fault-entry":"physical-00000b34",a.translated(0x3f000b34L),a.maintenance);
            }
            {
                Arm a=new Arm();require(a.execute(0x400012b4L,0x400012c0L)==0x400012c0L&&a.reg("r3")==0x21,"TTBCR slice");a.unchanged();
                out.println("PASS main original MOV/MCR slice supplies TTBCR=00000021; EAE=0 N=1 PD0=0 PD1=1 under architectural interpretation; write recorded, not applied.");
            }
            {
                Arm a=new Arm();byte[] guard=new byte[0x4000];Arrays.fill(guard,(byte)0x55);a.bytes(0x3f000000L,guard);
                require(a.execute(0x400005f0L,0xb34L)==0xb34L,"Early copy helper stop");
                byte[] copied=Arrays.copyOfRange(main,0xe37c00,0xe38274);
                require(Arrays.equals(copied,a.bytes(0x3f000000L,0x674)),"Early copy bytes");
                require(a.val(0x3f000b34L,4)==0x55555555L&&a.reg("r0")==0x3f000000L&&a.reg("r1")==0x674,"No helper at SRAM offset b34");a.unchanged();
                out.println("PASS early original copy writes [3f000000,3f000674), stops before low helper, leaves SRAM+0xb34 sentinel unchanged; copied SHA256="+hash(copied));
            }
            {
                image=updater;Arm a=new Arm();require(a.execute(0x400007c0L,-1)==END&&a.val(0x3f004000L,4)==0x1d406L&&a.translated(0xb34L)==0xb34L,"Updater low map");a.unchanged();
                out.println("PASS complete updater low-map helper stores 0001d406 -> conditional physical 00000b34; barriers/CP15 recorded only.");
            }
            out.println("6 controls passed. No low-address code bytes recovered or synthesized. Source binaries unchanged; no physical MMU/cache maintenance, low-helper instruction, storage/USB operation, payload or camera executes. Descriptor interpretation does not identify SoC physical backing or prove live translation/security/remap state.");
        }
    }
}
