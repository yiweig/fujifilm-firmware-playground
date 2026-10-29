// Identify call boundaries before decompiling a small set of ARM functions.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.util.*;
public class PrepareTargets extends GhidraScript {
    public void run() throws Exception {
        FunctionManager fm=currentProgram.getFunctionManager();
        LinkedHashSet<Address> entries=new LinkedHashSet<>();
        for(Function f:fm.getFunctions(true)) entries.add(f.getEntryPoint());
        for(String arg:getScriptArgs()) {
            Address a=toAddr(Long.parseUnsignedLong(arg,16));
            DisassembleCommand cmd=new DisassembleCommand(a,null,true);
            cmd.enableCodeAnalysis(false);cmd.applyTo(currentProgram,monitor);
            entries.add(a);
        }
        for(Instruction inst:currentProgram.getListing().getInstructions(true)) {
            if(inst.getFlowType().isCall()) for(Address a:inst.getFlows())
                if(a.getOffset()>=0x40000000L && a.getOffset()<0x40ab0000L) entries.add(a);
            if(inst.getFlowType().isJump()) for(Address a:inst.getFlows())
                if(a.getOffset()>=0x40000000L && a.getOffset()<0x40ab0000L &&
                   (getInt(a)&0xffff4000)==0xe92d4000) entries.add(a);
        }
        List<Address> old=new ArrayList<>();
        for(Function f:fm.getFunctions(true)) old.add(f.getEntryPoint());
        for(Address a:old) fm.removeFunction(a);
        for(Address a:entries) fm.createFunction(null,a,new AddressSet(a),SourceType.ANALYSIS);
        for(Address a:entries) if(getInstructionAt(a)!=null)
            CreateFunctionCmd.fixupFunctionBody(currentProgram,fm.getFunctionAt(a),monitor);
        println("Prepared "+entries.size()+" function boundaries");
    }
}
