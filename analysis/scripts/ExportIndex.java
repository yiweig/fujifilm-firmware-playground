// Export discovered functions for mapping binary references offline.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import java.io.*;
public class ExportIndex extends GhidraScript {
    public void run() throws Exception {
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            for(Function f:currentProgram.getFunctionManager().getFunctions(true)) {
                out.println(f.getEntryPoint()+"\t"+f.getBody().getMaxAddress()+"\t"+f.getName());
            }
        }
    }
}
