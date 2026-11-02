// Read-only metadata/pseudocode export of functions referencing UI base and bulk helpers.
// Candidate selection is database-dependent, not complete code coverage.
// @category Fuji
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;
import java.security.MessageDigest;

public class InspectUiBulkCalls extends GhidraScript {
    Set<Function> ui=new HashSet<>(), bulk=new HashSet<>();
    long word(byte[] b,int p){return (b[p]&255L)|((b[p+1]&255L)<<8)|((b[p+2]&255L)<<16)|((b[p+3]&255L)<<24);}
    void collect(long address,Set<Function> found,int depth) {
        for(Reference r:currentProgram.getReferenceManager().getReferencesTo(toAddr(address))) {
            Function f=getFunctionContaining(r.getFromAddress());
            if(f!=null)found.add(f);
            else if(depth>0)collect(r.getFromAddress().getOffset(),found,depth-1);
        }
    }
    public void run() throws Exception {
        byte[] image=new byte[0xe38400];currentProgram.getMemory().getBytes(toAddr(0x40000000L),image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(image));
        if(!hash.equals("80350bd2bc37b4ce985742ff85a747f7c7ad1ed9be401534ba57ac3262f1d923"))throw new Exception("Wrong main image");
        collect(0x40e65950L,ui,1);collect(0x40008b8cL,bulk,1);collect(0x4000cdf0L,bulk,1);
        int unownedUi=0,unownedBulk=0;
        // Supplement incomplete reference metadata with original ARM encodings.
        for(int p=0;p<0xab0000;p+=4) {
            long w=word(image,p);boolean isUi=false,isBulk=false;
            if((w&0x0f7f0000L)==0x051f0000L){
                int q=p+8+((w&0x800000L)!=0?(int)(w&0xfff):-(int)(w&0xfff));
                isUi=q>=0&&q+4<=image.length&&word(image,q)==0x40e65950L;
            }
            if((w&0x0e000000L)==0x0a000000L){
                int off=(int)(w&0xffffff);if((off&0x800000)!=0)off-=0x1000000;
                long target=(0x40000000L+p+8+off*4)&0xffffffffL;
                isBulk=target==0x40008b8cL||target==0x4000cdf0L;
            }
            if(!isUi&&!isBulk)continue;
            Function f=getFunctionContaining(toAddr(0x40000000L+p));
            if(f==null){if(isUi)unownedUi++;if(isBulk)unownedBulk++;}
            else {if(isUi)ui.add(f);if(isBulk)bulk.add(f);}
        }
        List<Function> selected=new ArrayList<>();for(Function f:ui)if(bulk.contains(f))selected.add(f);
        selected.sort(Comparator.comparing(f->f.getEntryPoint()));
        DecompInterface d=new DecompInterface();d.openProgram(currentProgram);
        try(PrintWriter out=new PrintWriter(getScriptArgs()[0])) {
            out.println("PROGRAM "+currentProgram.getName()+" UI-reference-functions="+ui.size()+" bulk-reference-functions="+bulk.size()+" intersection="+selected.size());
            out.println("SHA-256 "+hash+"; unowned original-byte UI-load candidates="+unownedUi+" bulk-branch candidates="+unownedBulk);
            for(Function f:selected) {
                monitor.checkCancelled();long size=f.getBody().getNumAddresses();
                out.println("\nFUNCTION "+f.getEntryPoint()+" bytes="+size);
                if(size>0x8000){out.println("SKIP oversized body; not audited");continue;}
                DecompileResults result=d.decompileFunction(f,20,monitor);
                out.println(result.decompileCompleted()?result.getDecompiledFunction().getC():"DECOMPILE FAILED "+result.getErrorMessage());out.flush();
            }
        }finally{d.dispose();}
    }
}
