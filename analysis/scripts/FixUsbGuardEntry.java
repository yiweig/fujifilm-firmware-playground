// One-time local metadata repair; no firmware bytes changed.
import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.function.CreateFunctionCmd;
public class FixUsbGuardEntry extends GhidraScript {
    public void run() throws Exception {
        var bad=getFunctionAt(toAddr(0x4038d698L));
        if(bad!=null)removeFunction(bad);
        var good=getFunctionAt(toAddr(0x4038d694L));
        if(good==null){disassemble(toAddr(0x4038d694L));good=createFunction(toAddr(0x4038d694L),null);}
        if(good==null)throw new Exception("Missing verified guard entry");
        CreateFunctionCmd.fixupFunctionBody(currentProgram,good,monitor);
        println("Verified guard entry: "+good.getEntryPoint()+" body "+good.getBody());
    }
}
