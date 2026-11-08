// Print Ghidra references to each address supplied on the command line.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

public class FindRefsArgs extends GhidraScript {
    public void run() throws Exception {
        for (String arg : getScriptArgs()) {
            long value = Long.parseUnsignedLong(arg.replace("0x", ""), 16);
            Address a = toAddr(value);
            println("refs to " + a);
            ReferenceIterator it = currentProgram.getReferenceManager().getReferencesTo(a);
            int n = 0;
            while (it.hasNext()) {
                Reference r = it.next();
                println("  " + r.getFromAddress() + " type=" + r.getReferenceType());
                n++;
            }
            println("  count=" + n);
        }
    }
}
