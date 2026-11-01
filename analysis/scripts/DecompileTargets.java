// Define only explicitly supplied function entries, then export pseudocode.
// Changes analysis metadata in the local Ghidra database, not firmware bytes.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import java.io.PrintWriter;

public class DecompileTargets extends GhidraScript {
    public void run() throws Exception {
        String[] args = getScriptArgs();
        DecompInterface decomp = new DecompInterface();
        decomp.openProgram(currentProgram);
        try (PrintWriter out = new PrintWriter(args[0])) {
            out.println("PROGRAM " + currentProgram.getName());
            for (int i = 1; i < args.length; i++) {
                Address a = toAddr(Long.parseUnsignedLong(args[i], 16));
                Function f = getFunctionAt(a);
                if (f == null) {
                    Function containing = getFunctionContaining(a);
                    if (containing != null) {
                        out.println("SKIP " + a + " inside " + containing.getEntryPoint());
                        continue;
                    }
                    disassemble(a);
                    f = createFunction(a, null);
                    if (f != null) CreateFunctionCmd.fixupFunctionBody(currentProgram, f, monitor);
                }
                out.println("\nFUNCTION " + a);
                if (f == null) { out.println("NO FUNCTION"); continue; }
                decomp.flushCache();
                DecompileResults r = decomp.decompileFunction(f, 90, monitor);
                out.println(r.decompileCompleted() ? r.getDecompiledFunction().getC() : r.getErrorMessage());
                out.flush();
            }
        } finally { decomp.dispose(); }
    }
}
