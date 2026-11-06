// Print Ghidra references to the SD init/power targets.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;

public class FindRefs extends GhidraScript {
    public void run() throws Exception {
        long[] targets = {
            0x4006ed88L, 0x4006de3cL, 0x40070d18L,
            0x400ba2a0L, 0x40070ee4L, 0x40021d78L
        };
        for (long t : targets) {
            Address a = toAddr(t);
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
