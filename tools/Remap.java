import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Remaps a 1.7.10 notch-obfuscated jar to SRG names using FalsePatternLib's CSV mappings.
 *  Compile-time use only: member references inside method bodies may be left un-remapped
 *  where the call site owner differs from the declaring class, which javac does not care about. */
public class Remap {
    public static void main(String[] a) throws Exception {
        Map<String,String> map = new HashMap<>();
        // classes.csv: notch,srg,mcp
        for (String l : lines(a[0] + "/classes.csv")) {
            String[] c = l.split(",");
            map.put(c[0], c[1]);
        }
        // fields.csv: notch,srg,mcp   (notch = owner/name)
        for (String l : lines(a[0] + "/fields.csv")) {
            String[] c = l.split(",");
            map.put(c[0].replace('/', '.'), c[1].substring(c[1].lastIndexOf('/') + 1));
        }
        // methods.csv: notch,notchdesc,srg,srgdesc,mcp,mcpdesc
        for (String l : lines(a[0] + "/methods.csv")) {
            String[] c = l.split(",");
            map.put(c[0].replace('/', '.') + c[1], c[2].substring(c[2].lastIndexOf('/') + 1));
        }
        Remapper r = new SimpleRemapper(map);
        try (ZipFile in = new ZipFile(a[1]);
             ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(Paths.get(a[2]))))) {
            Enumeration<? extends ZipEntry> en = in.entries();
            Set<String> written = new HashSet<>();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                byte[] data;
                String name = e.getName();
                if (name.endsWith(".class")) {
                    ClassReader cr = new ClassReader(readAll(in.getInputStream(e)));
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, r), ClassReader.EXPAND_FRAMES);
                    data = cw.toByteArray();
                    String owner = name.substring(0, name.length() - 6);
                    String mapped = map.get(owner);
                    name = (mapped != null ? mapped : owner) + ".class";
                } else {
                    data = readAll(in.getInputStream(e));
                }
                if (!written.add(name)) continue;
                out.putNextEntry(new ZipEntry(name));
                out.write(data);
                out.closeEntry();
            }
        }
        System.out.println("wrote " + a[2]);
    }
    static List<String> lines(String p) throws IOException {
        List<String> l = new ArrayList<>(Files.readAllLines(Paths.get(p)));
        l.remove(0); // header
        l.removeIf(String::isEmpty);
        return l;
    }
    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while ((n = is.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
