// Offline composition only: factory RAM staging, supplied flag-refresh slice,
// supplied UI event. No live transport, storage, camera or full UI transition.
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

public class ReproduceTemporaryUsbSetting extends GhidraScript {
    static final long BASE=0x40000000L,COLD=0x40e4df02L,FLAG=0x40e6696dL;
    static final long SP=0x42010000L,REQUEST=0x42020000L,END=0x43000000L;
    byte[] image;
    class Arm {
        PcodeEmulator machine=new PcodeEmulator((SleighLanguage)currentProgram.getLanguage());
        AddressSpace ram=currentProgram.getLanguage().getDefaultSpace();
        PcodeThread<byte[]> thread;
        boolean chargingBoundary;
        int modeledChargeWrites, modeledIndicatorCalls;
        Arm() {
            bytes(BASE,image);bytes(COLD,new byte[1024]);
            bytes(0x40e65950L,new byte[0x1100]);bytes(0x413e32d8L,new byte[0x80]);
            bytes(SP-0x1000,new byte[0x2000]);bytes(REQUEST,new byte[0x40]);
            thread=machine.newThread();thread.overrideContextWithDefault();
            for(int i=0;i<13;i++)reg("r"+i,0);
            for(String n:new String[]{"CY","ZR","NG","OV"})reg(n,0);
        }
        void bytes(long a,byte[] b) {machine.getSharedState().setVar(ram,a,b.length,true,b);}
        byte[] bytes(long a,int n) {return machine.getSharedState().getVar(ram,a,n,true,Reason.INSPECT);}
        void put(long a,int n,long v) {bytes(a,Utils.longToBytes(v,n,false));}
        long get(long a,int n) {return Utils.bytesToLong(bytes(a,n),n,false);}
        void reg(String n,long v) {
            var r=currentProgram.getRegister(n);
            thread.getState().setVar(r,Utils.longToBytes(v,r.getMinimumByteSize(),false));
        }
        long reg(String n) {
            var r=currentProgram.getRegister(n);
            return Utils.bytesToLong(thread.getState().getVar(r,Reason.INSPECT),r.getMinimumByteSize(),false);
        }
        void pc(long a) {thread.overrideCounter(toAddr(a));}
        void start(long entry,long arg) {reg("sp",SP);reg("lr",END);reg("r0",arg);pc(entry);}
        void returned() {long lr=reg("lr");reg("r0",0);pc(lr);}
        void stage(byte[] block) throws Exception {
            if(block.length!=16)throw new Exception("Invalid synthetic block");
            put(REQUEST,4,32);put(REQUEST+4,2,1);put(REQUEST+6,2,0x1027);
            put(REQUEST+8,4,1);put(REQUEST+12,4,0x10060);
            bytes(REQUEST+16,block);start(0x405ca67cL,REQUEST);
            boolean response=false;
            for(int n=0;n<1200;n++) {
                monitor.checkCancelled();long p=thread.getCounter().getOffset();
                if(p==END) {if(!response)throw new Exception("No response");return;}
                if(p==0x40004064L) {returned();continue;}
                if(p==0x4000cdf0L) {
                    long dst=reg("r0"),size=reg("r2");
                    if(size!=20 || dst<SP-0x100 || dst+size>SP)throw new Exception("Unexpected zero");
                    bytes(dst,new byte[(int)size]);returned();continue;
                }
                if(p==0x40008b8cL) {
                    if(reg("r0")!=COLD+0x60 || reg("r1")!=0x413e3310L || reg("r2")!=16)
                        throw new Exception("Unexpected staging copy");
                    bytes(reg("r0"),bytes(reg("r1"),16));returned();continue;
                }
                if(p==0x405e6ac0L) {
                    if(reg("r0")!=0x2001 || reg("r1")!=0)throw new Exception("Bad response");
                    response=true;returned();continue;
                }
                if(p<0x405ca67cL || p>=0x405cad84L)throw new Exception("Unexpected PTP callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("PTP instruction limit");
        }
        void refresh() throws Exception {
            start(0x404d53b8L,0);
            for(int n=0;n<100;n++) {
                long p=thread.getCounter().getOffset();
                if(p==0x404d53d0L)return;
                if(!((p>=0x404d53b8L && p<0x404d53d0L) || (p>=0x40125284L && p<0x401252b0L)))
                    throw new Exception("Unexpected refresh callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("Refresh instruction limit");
        }
        int alternateRefresh(boolean displayHidden, boolean ready, int uiState) throws Exception {
            // Supply the prerequisite substate and readiness; neither is a live entry recipe.
            put(0x40e66958L,2,uiState);put(0x40f129c8L,1,ready?1:0);
            put(0x40e66975L,1,1); // +1025: route the 0300 control to the broader cleanup.
            bytes(0x412c2000L,new byte[0x2000]);
            put(0x412c281cL,1,displayHidden?1:0);
            put(0x412c26e4L,4,0x10000000);
            start(0x404db31cL,0xf2000084L);int objectCalls=0;
            for(int n=0;n<1000;n++) {
                monitor.checkCancelled();long p=thread.getCounter().getOffset();
                if(p==END)return objectCalls;
                if(!ready && p==0x4000f458L)return -2;
                if(uiState==0x300 && p==0x405c7ff8L)return -3;
                // Display-active control stops at its first unqualified callee.
                if(!displayHidden && p==0x40004064L)return -1;
                if(p==0x401d86b0L) {
                    if(reg("r0")!=0x10000014L-objectCalls || reg("r1")!=0)
                        throw new Exception("Unexpected display/object call");
                    objectCalls++;returned();continue;
                }
                if(!((p>=0x404db31cL && p<0x404db3dcL) ||
                     (p>=0x404d51b0L && p<0x404d55bcL) ||
                     (p>=0x401575a4L && p<0x401575e4L) ||
                     (p>=0x40530b50L && p<0x40530b68L) ||
                     (p>=0x401fe6bcL && p<0x401fe718L) ||
                     (p>=0x40200a34L && p<0x40200a64L) ||
                     (p>=0x401fcf64L && p<0x401fcfc0L) ||
                     (p>=0x40125284L && p<0x401252b0L)))
                    throw new Exception("Unqualified alternate-refresh callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("Alternate refresh instruction limit");
        }
        long idleTimeoutMessage() throws Exception {
            // Capture a queue post; this does not emulate the real queue or its producer.
            start(0x404db31cL,0xf0000071L);long posted=0;
            for(int n=0;n<200;n++) {
                long p=thread.getCounter().getOffset();
                if(p==END)return posted;
                if(p==0x40004064L) {returned();continue;}
                if(p==0x4006f330L) {
                    if(posted!=0 || reg("r1")!=0 || reg("r2")!=0 || reg("r3")!=0)
                        throw new Exception("Unexpected idle queue post");
                    posted=reg("r0");returned();continue;
                }
                if(!((p>=0x404db31cL && p<0x404db3dcL) ||
                     (p>=0x404d51b0L && p<0x404d55bcL)))
                    throw new Exception("Unexpected idle callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("Idle instruction limit");
        }
        boolean startMainBoundary(boolean runtimeUsb) throws Exception {
            // Preserve the current UI state and USB flag; supply only runtime prerequisites.
            long runtime=0x42030000L;
            bytes(runtime,new byte[0x40]);put(runtime,1,runtimeUsb?1:0);
            put(0x40d6d014L,4,runtime);
            put(0x40e6bc0cL,1,0); // gbPwrOnToViewMode, read for logging in this prefix.
            put(0x40e4ae64L,1,0); // Avoid the separate low-two-bits == 1 charging branch.
            put(0x40e7af10L,4,0); // No optional callback table for 40126930.
            start(0x404db31cL,0xf2000000L);boolean queued=false,resource=false;
            for(int n=0;n<600;n++) {
                monitor.checkCancelled();long p=thread.getCounter().getOffset();
                if(p==END) {if(!queued)throw new Exception("Missing StartMain continuation");return false;}
                if(p==0x4038d6d0L) {
                    if(reg("r0")!=4 || !resource || queued)throw new Exception("Unexpected StartMain mode boundary");
                    return true;
                }
                if(p==0x405cbb84L) {
                    if(reg("r0")!=1 || resource)throw new Exception("Unexpected StartMain resource selection");
                    resource=true;returned();continue;
                }
                if(p==0x400410bcL) {
                    if(reg("r0")!=0 || reg("r1")!=0x40bc4ad4L)throw new Exception("Unexpected StartMain prefix API");
                    returned();continue;
                }
                if(p==0x4000cd00L || p==0x40004064L) {returned();continue;}
                if(p==0x4006f330L) {
                    if(queued || resource || reg("r0")!=0xf2000010L ||
                       reg("r1")!=0 || reg("r2")!=0 || reg("r3")!=0)
                        throw new Exception("Unexpected StartMain continuation");
                    queued=true;returned();continue;
                }
                if(!((p>=0x404db31cL && p<0x404db3dcL) ||
                     (p>=0x404d51b0L && p<0x404d55bcL) ||
                     (p>=0x40517b18L && p<0x40517b2cL) ||
                     (p>=0x405180f0L && p<0x405181ecL) ||
                     (p>=0x40518538L && p<0x4051854cL) ||
                     (p>=0x40126930L && p<0x40126a24L)))
                    throw new Exception("Unqualified StartMain callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("StartMain instruction limit");
        }
        boolean loaderBoundary() throws Exception {
            put(0x40e66958L,2,0x8000);put(0x40e6696bL,1,1);
            return usbEventAtCurrentState();
        }
        boolean usbEventAtCurrentState() throws Exception {
            return usbEventAtCurrentState(false);
        }
        boolean usbEventAtCurrentState(boolean modelCharge) throws Exception {
            chargingBoundary=false;
            modeledChargeWrites=0;modeledIndicatorCalls=0;
            start(0x404db31cL,0xf0000044L);
            for(int n=0;n<600;n++) {
                long p=thread.getCounter().getOffset();
                if(p==END)return false;
                if(p==0x40124b74L && !modelCharge) {chargingBoundary=true;return false;}
                if(modelCharge && p==0x40043220L) {
                    if(reg("r0")!=6)throw new Exception("Unexpected modeled Power IC register");
                    put(reg("r1"),1,8);returned();continue;
                }
                if(modelCharge && p==0x40043318L) {
                    if(reg("r0")!=6 || reg("r1")!=8 || reg("r2")!=0 || reg("r3")!=32)
                        throw new Exception("Unexpected charging-control write");
                    modeledChargeWrites++;returned();continue;
                }
                if(modelCharge && p==0x40124e94L) {
                    if(reg("r0")!=1)throw new Exception("Unexpected indicator request");
                    modeledIndicatorCalls++;returned();continue;
                }
                if(p==0x4006a074L) {
                    if(reg("r0")!=3 || !(reg("r1")==300 || (modelCharge && reg("r1")==1)))
                        throw new Exception("Unexpected connection delay");
                    returned();continue;
                }
                if(p==0x4038d6d0L) {
                    if(reg("r0")!=4)throw new Exception("Wrong mode");return true;
                }
                if(p==0x40004064L || p==0x405cbb84L) {returned();continue;}
                if(p==0x40123bf8L) {long lr=reg("lr");reg("r0",1);pc(lr);continue;}
                if(!((p>=0x404db31cL && p<0x404db3dcL) ||
                     (p>=0x404d665cL && p<0x404d6f90L) ||
                     (p>=0x404d51b0L && p<0x404d55bcL) ||
                     (p>=0x40517b18L && p<0x4051858cL) ||
                     (p>=0x40123d44L && p<0x40123d54L) ||
                     (p>=0x401250e4L && p<0x401250ecL) ||
                     (p>=0x40124b08L && p<0x40124b28L) ||
                     (modelCharge && p>=0x40124b74L && p<0x40124d7cL) ||
                     (p>=0x40124eecL && p<0x40124f34L)))
                    throw new Exception("Unexpected UI callee "+Long.toHexString(p));
                thread.stepInstruction();
            }
            throw new Exception("UI instruction limit");
        }
    }
    public void run() throws Exception {
        image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(BASE),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong firmware");
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("Original main-image SHA-256: "+hash);
            out.println("Offline synthetic RAM composition; refresh slice and event delivery explicitly supplied.");
            Arm a=new Arm();byte[] original=new byte[1024];
            for(int i=0;i<original.length;i++)original[i]=(byte)(i*37+11);
            original[0x6a]=0x18;original[0x6b]=1;a.bytes(COLD,original);a.put(FLAG,1,1);
            byte[] block=Arrays.copyOfRange(original,0x60,0x70);block[10]=0x14;
            byte[] expected=original.clone();expected[0x6a]=0x14;
            a.stage(block);
            if(!Arrays.equals(expected,a.bytes(COLD,1024)) || a.get(FLAG,1)!=1)throw new Exception("Unexpected staging effect");
            if(a.loaderBoundary())throw new Exception("Staging alone changed active gate");
            out.println("PASS: RAM staging changes only synthetic cold-data byte +6a; cached USB flag stays 1; supplied event returns without loader.");
            a.refresh();if(a.get(FLAG,1)!=0 || !a.loaderBoundary())throw new Exception("Refresh did not select loader branch");
            out.println("PASS: supplied original refresh slice changes cached flag to 0; supplied USB event reaches mode-4 boundary.");
            a.stage(Arrays.copyOfRange(original,0x60,0x70));a.refresh();
            if(!Arrays.equals(original,a.bytes(COLD,1024)) || a.get(FLAG,1)!=1 || a.loaderBoundary())throw new Exception("Restore failed");
            out.println("PASS: restoring synthetic RAM block and refreshing restores original cold-data bytes and ordinary event result.");
            a.stage(block);
            if(a.alternateRefresh(true,true,0x301)!=5 || a.get(FLAG,1)!=0 || a.get(0x40e66958L,2)!=1 ||
               !Arrays.equals(expected,a.bytes(COLD,1024)))throw new Exception("Alternate refresh mismatch");
            out.println("PASS: full supplied 0301 UI event path returns, refreshes flag to 0 and preserves staged cold RAM; only five display/object calls stubbed.");
            a.put(0x1000517aL,1,1);a.put(0x40e7ae40L,1,0);
            if(a.usbEventAtCurrentState() || !a.chargingBoundary || a.get(0x40e66958L,2)!=1 || a.get(FLAG,1)!=0 ||
               !Arrays.equals(expected,a.bytes(COLD,1024)))throw new Exception("Continuous event control mismatch");
            out.println("PASS: subsequent supplied USB event in resulting state 0001 reaches charging-control boundary 40124b74, not direct loader selection; stopped before control, cold RAM and cached flag unchanged.");
            a.put(0x40e7ae41L,1,1);
            if(a.usbEventAtCurrentState(true) || a.modeledChargeWrites!=1 || a.modeledIndicatorCalls!=1 ||
               a.get(0x40e7ae41L,1)!=0 || a.get(0x40e66958L,2)!=1 || a.get(FLAG,1)!=0 ||
               !Arrays.equals(expected,a.bytes(COLD,1024)))throw new Exception("Modeled charging completion mismatch");
            out.println("PASS: modeled charging path returns to caller without loader; exactly one Power IC masked-write and one indicator request; staged cold RAM, USB flag and UI state preserved.");
            a.put(0x1000517aL,1,0);
            if(a.usbEventAtCurrentState() || a.chargingBoundary)throw new Exception("Disconnected continuity control mismatch");
            out.println("PASS: USB-presence-zero control in state 0001 returns before charging control.");
            if(a.alternateRefresh(false,true,0x301)!=-1)throw new Exception("Display-active control did not reach unaudited display path");
            out.println("PASS: display-active control reaches display cleanup; stopped at log before unqualified display callees.");
            if(a.alternateRefresh(true,false,0x301)!=-2)throw new Exception("Readiness control failed");
            out.println("PASS: resource-not-ready control reaches initialization/wait path; stopped before execution.");
            if(a.alternateRefresh(true,true,0x300)!=-3)throw new Exception("0300 control failed");
            out.println("PASS: 0300 control with +1025 set reaches broader cleanup 405c7ff8; stopped before execution.");
            Arm simple=new Arm();simple.bytes(COLD,expected);simple.put(FLAG,1,1);
            simple.put(0x40e66958L,2,1);simple.put(0x40e6697dL,1,1);
            if(simple.idleTimeoutMessage()!=0 || simple.get(FLAG,1)!=1)
                throw new Exception("Idle suppression control mismatch");
            out.println("PASS: +102d=1 control suppresses the idle-timeout queue post and leaves the USB flag unchanged.");
            simple.put(0x40e6697dL,1,0);
            if(simple.idleTimeoutMessage()!=0xf2000084L || simple.get(FLAG,1)!=1 ||
               simple.get(0x40e66958L,2)!=1 || !Arrays.equals(expected,simple.bytes(COLD,1024)))
                throw new Exception("Idle event producer mismatch");
            out.println("PASS: supplied MSG_KEY_IDLE_TIME_OUT in UI state 0001 and +102d=0 posts F2000084 with three zero arguments; state, cold RAM and flag unchanged.");
            if(simple.alternateRefresh(false,true,1)!=0 || simple.get(FLAG,1)!=0 ||
               simple.get(0x40e66958L,2)!=1 || simple.get(0x412c281cL,1)!=0 ||
               !Arrays.equals(expected,simple.bytes(COLD,1024)))
                throw new Exception("State-0001 direct refresh mismatch");
            out.println("PASS: supplied F2000084 in UI state 0001 refreshes flag with zero display/object calls; display-active input and staged cold RAM preserved, state remains 0001.");
            simple.put(0x1000517aL,1,1);simple.put(0x40e7ae40L,1,1);
            if(simple.usbEventAtCurrentState() || simple.chargingBoundary || simple.get(FLAG,1)!=0 ||
               simple.get(0x40e66958L,2)!=1 || !Arrays.equals(expected,simple.bytes(COLD,1024)))
                throw new Exception("State-0001 direct refresh continuity mismatch");
            out.println("PASS: subsequent USB event with presence=1 and status=1 returns without loader or charging-control entry; state, cold RAM and refreshed flag unchanged.");
            if(!simple.startMainBoundary(true) || simple.get(FLAG,1)!=0 ||
               simple.get(0x40e66958L,2)!=1 || !Arrays.equals(expected,simple.bytes(COLD,1024)))
                throw new Exception("Continuous StartMain loader boundary mismatch");
            out.println("PASS: supplied StartMain after idle refresh reaches mode-4 boundary in unchanged UI state 0001, with runtime bit 0 set; cold RAM and flag unchanged.");
            for(int flag=0;flag<2;flag++)for(int bit=0;bit<2;bit++) {
                Arm control=new Arm();control.bytes(COLD,expected);control.put(FLAG,1,flag);
                control.put(0x40e66958L,2,1);
                if(control.startMainBoundary(bit!=0)!=(flag==0 && bit==1) ||
                   control.get(FLAG,1)!=flag || control.get(0x40e66958L,2)!=1 ||
                   !Arrays.equals(expected,control.bytes(COLD,1024)))
                    throw new Exception("StartMain Boolean control mismatch");
            }
            out.println("PASS: four StartMain flag/runtime-bit controls select mode 4 only for flag=0 and runtime bit=1; other cases post F2000010 and return.");
            out.println("No file read/write, RTC write, mode setter, USB lifecycle or calibration code executed.");
            out.println("Stubs: log, memcpy/memset, PTP response, cable getter and descriptor setup.");
            out.println("Full alternate path additionally supplies UI=0301, resource-ready=1, display-already-hidden=1, and stubs 401d86b0 five times.");
            out.println("Continuity test supplies USB-presence MMIO and connection/charging status; 300-unit delay stubbed; stops before charging control.");
            out.println("Additional completion test models Power IC read/write and indicator API; delays stubbed; no physical peripheral effects or asynchronous callbacks validated.");
            out.println("State-0001 test captures the idle-timeout queue post, then explicitly supplies its event to the dispatcher; actual timer, queue scheduling and readiness remain unverified.");
            out.println("StartMain test explicitly delivers F2000000; supplies runtime pointer/bit, power-class low bits 0 and null optional callback table; stubs 400410bc prefix, clock/log and resource setup; stops before mode setter.");
            out.println("Does not prove live registration, freshness, safe refresh reachability, buffer lifetime, later saves, or recovery.");
        }
    }
}
