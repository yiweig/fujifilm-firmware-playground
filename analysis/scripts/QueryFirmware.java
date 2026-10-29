// Export references and decompilations for addresses supplied as script arguments.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;
public class QueryFirmware extends GhidraScript {
    private LinkedHashSet<Function> functions = new LinkedHashSet<>();
    private PrintWriter out;
    private boolean includeCallers = true;
    private void refs(Address a, int depth) throws Exception {
        for (Reference r : currentProgram.getReferenceManager().getReferencesTo(a)) {
            Address from = r.getFromAddress();
            Function f = getFunctionContaining(from);
            out.println("  ".repeat(depth) + "REF " + from + " " + r.getReferenceType()
                + " function=" + (f == null ? "none" : f.getEntryPoint()+" "+f.getName()));
            if (f != null && includeCallers) functions.add(f);
            if (depth < 2 && f == null) refs(from, depth+1);
        }
    }
    public void run() throws Exception {
        String[] args = getScriptArgs();
        out = new PrintWriter(args[0]);
        out.println("PROGRAM " + currentProgram.getName() + " functions="
            + currentProgram.getFunctionManager().getFunctionCount());
        for (int n=1; n<args.length; n++) {
            if (args[n].equals("--only")) { includeCallers = false; continue; }
            Address a = toAddr(Long.parseUnsignedLong(args[n].replace("0x", ""),16));
            out.println("\nADDRESS " + a);
            Data d = getDataContaining(a);
            out.println("DATA " + d);
            Function f = getFunctionContaining(a);
            if (f != null) functions.add(f);
            refs(a,0);
        }
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        for (Function f : functions) {
            out.println("\nFUNCTION " + f.getEntryPoint()+" "+f.getName());
            DecompileResults result=decomp.decompileFunction(f,90,monitor);
            if(result.decompileCompleted()) out.println(result.getDecompiledFunction().getC());
            else out.println("DECOMPILE FAILED " + result.getErrorMessage());
            out.flush();
        }
        decomp.dispose();out.close();
        println("Wrote " + args[0] + " (" + functions.size() + " functions)");
    }
}
