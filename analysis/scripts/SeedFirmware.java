// Seed known ARM entry points in the extracted firmware image.
// @category Fuji
import ghidra.app.script.GhidraScript;
public class SeedFirmware extends GhidraScript {
    public void run() throws Exception {
        currentProgram.getMemory().getBlock(toAddr(0x40000000L)).setExecute(true);
        for (long a : new long[]{0x4043b580L, 0x4049eee4L, 0x404a14e4L,
                0x404a0904L, 0x404a04f8L, 0x40445250L, 0x404be688L}) {
            disassemble(toAddr(a));
            if (getFunctionAt(toAddr(a)) == null)
                createFunction(toAddr(a), null);
        }
    }
}
