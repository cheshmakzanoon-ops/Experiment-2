import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Builds a "mockable android.jar" from the Robolectric android-all jar, the way AGP's MockableJarGenerator does for
 * `unitTests.isReturnDefaultValues = true`: every method body is replaced by "return the default value" (0 / false /
 * null), constructors only call their super constructor, static initialisers are emptied, and the `final` modifier is
 * removed from classes, methods and fields so that Mockito can mock them.
 *
 * usage: java -cp asm.jar:asm-tree.jar Mockable.java android-all.jar mockable-android.jar
 */
public class Mockable implements Opcodes {
    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File out = new File(args[1]);
        int ok = 0;
        int skipped = 0;
        try (ZipFile zf = new ZipFile(in);
             ZipOutputStream zo = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 20))) {
            zo.setLevel(1);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".class") || excluded(n)) {
                    continue;
                }
                byte[] data;
                try (InputStream is = zf.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                byte[] t;
                try {
                    t = transform(data);
                } catch (Throwable ex) {
                    skipped++;
                    System.err.println("skipped " + n + ": " + ex);
                    continue;
                }
                zo.putNextEntry(new ZipEntry(n));
                zo.write(t);
                zo.closeEntry();
                ok++;
            }
        }
        System.out.println("mockable: " + ok + " classes written, " + skipped + " skipped");
    }

    /** JDK / libcore classes must come from the JDK; module-info and multi-release folders are irrelevant. */
    static boolean excluded(String n) {
        return n.startsWith("java/") || n.startsWith("libcore/") || n.startsWith("sun/") || n.startsWith("jdk/")
            || n.startsWith("META-INF/") || n.equals("module-info.class")
            || (n.startsWith("javax/") && !n.startsWith("javax/microedition/"));
    }

    static byte[] transform(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        boolean isInterface = (cn.access & ACC_INTERFACE) != 0;
        boolean isEnum = (cn.access & ACC_ENUM) != 0;
        cn.access &= ~ACC_FINAL;
        if (cn.innerClasses != null) {
            cn.innerClasses.forEach(ic -> {
                if (ic.name.equals(cn.name)) {
                    ic.access &= ~ACC_FINAL;
                }
            });
        }
        if (!isInterface) {
            for (FieldNode f : cn.fields) {
                f.access &= ~ACC_FINAL;
            }
        }
        for (MethodNode m : cn.methods) {
            m.access &= ~ACC_FINAL;
            boolean wasNative = (m.access & ACC_NATIVE) != 0;
            if ((m.access & ACC_ABSTRACT) != 0) {
                continue;
            }
            m.access &= ~ACC_NATIVE;
            if (m.name.equals("<clinit>")) {
                if (isEnum) {
                    continue; // keep enum constant creation (constructors below are trivial)
                }
                replaceBody(m, Type.VOID_TYPE);
            } else if (m.name.equals("<init>")) {
                if (wasNative) {
                    continue;
                }
                truncateConstructor(m);
            } else {
                if (isEnum && (m.name.equals("values") || m.name.equals("valueOf") || m.name.equals("$values"))) {
                    continue;
                }
                replaceBody(m, Type.getReturnType(m.desc));
            }
        }
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** Keeps everything up to and including the super()/this() call, then returns. */
    static void truncateConstructor(MethodNode m) {
        int depth = 0;
        AbstractInsnNode cut = null;
        for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op == NEW) {
                depth++;
            } else if (op == INVOKESPECIAL && ((MethodInsnNode) insn).name.equals("<init>")) {
                if (depth > 0) {
                    depth--;
                } else {
                    cut = insn;
                    break;
                }
            }
        }
        if (cut == null) {
            return; // no explicit super call found: leave untouched
        }
        AbstractInsnNode next = cut.getNext();
        while (next != null) {
            AbstractInsnNode after = next.getNext();
            m.instructions.remove(next);
            next = after;
        }
        m.instructions.add(new InsnNode(RETURN));
        m.tryCatchBlocks = new ArrayList<>();
        m.localVariables = null;
        m.visibleLocalVariableAnnotations = null;
        m.invisibleLocalVariableAnnotations = null;
    }

    static void replaceBody(MethodNode m, Type ret) {
        m.instructions.clear();
        m.tryCatchBlocks = new ArrayList<>();
        m.localVariables = null;
        m.visibleLocalVariableAnnotations = null;
        m.invisibleLocalVariableAnnotations = null;
        switch (ret.getSort()) {
            case Type.VOID:
                m.instructions.add(new InsnNode(RETURN));
                break;
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                m.instructions.add(new InsnNode(ICONST_0));
                m.instructions.add(new InsnNode(IRETURN));
                break;
            case Type.LONG:
                m.instructions.add(new InsnNode(LCONST_0));
                m.instructions.add(new InsnNode(LRETURN));
                break;
            case Type.FLOAT:
                m.instructions.add(new InsnNode(FCONST_0));
                m.instructions.add(new InsnNode(FRETURN));
                break;
            case Type.DOUBLE:
                m.instructions.add(new InsnNode(DCONST_0));
                m.instructions.add(new InsnNode(DRETURN));
                break;
            default:
                m.instructions.add(new InsnNode(ACONST_NULL));
                m.instructions.add(new InsnNode(ARETURN));
        }
    }
}
