import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.RunWith;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * Minimal JUnit 4 launcher for the harness.
 *
 * usage: RunTests CLASSES_DIR [-v] [-k SUBSTRING]... [FULLY.QUALIFIED.TestClass[#method]]...
 *   scans CLASSES_DIR for top-level classes that contain @Test methods (or @RunWith); -k keeps classes whose name
 *   contains SUBSTRING; explicit class names run exactly those.  Prints failures concisely, exit status 1 on failure.
 */
public class RunTests {
    public static void main(String[] args) throws Exception {
        Path dir = new File(args[0]).toPath();
        boolean verbose = false;
        List<String> filters = new ArrayList<>();
        List<String> explicit = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("-v")) {
                verbose = true;
            } else if (args[i].equals("-k") && i + 1 < args.length) {
                filters.add(args[++i]);
            } else {
                explicit.add(args[i]);
            }
        }
        List<Request> requests = new ArrayList<>();
        int classCount = 0;
        if (!explicit.isEmpty()) {
            for (String e : explicit) {
                String cls = e.contains("#") ? e.substring(0, e.indexOf('#')) : e;
                Class<?> c = Class.forName(cls);
                requests.add(e.contains("#") ? Request.method(c, e.substring(e.indexOf('#') + 1)) : Request.aClass(c));
                classCount++;
            }
        } else {
            List<String> names = new ArrayList<>();
            try (Stream<Path> s = Files.walk(dir)) {
                s.filter(p -> p.toString().endsWith(".class") && !p.getFileName().toString().contains("$"))
                    .forEach(p -> {
                        String rel = dir.relativize(p).toString();
                        names.add(rel.substring(0, rel.length() - 6).replace(File.separatorChar, '.'));
                    });
            }
            names.sort(null);
            for (String n : names) {
                if (!filters.isEmpty() && filters.stream().noneMatch(n::contains)) {
                    continue;
                }
                Class<?> c;
                try {
                    c = Class.forName(n, false, RunTests.class.getClassLoader());
                } catch (Throwable t) {
                    continue;
                }
                if (isTestClass(c)) {
                    requests.add(Request.aClass(c));
                    classCount++;
                }
            }
        }
        final boolean v = verbose;
        Map<String, Failure> failures = new LinkedHashMap<>();
        long[] counts = new long[3]; // started, ignored, assumption failures
        JUnitCore core = new JUnitCore();
        core.addListener(new RunListener() {
            String currentClass = "";

            @Override
            public void testStarted(Description d) {
                counts[0]++;
                if (v && !d.getClassName().equals(currentClass)) {
                    currentClass = d.getClassName();
                    System.out.println("  running " + currentClass);
                }
            }

            @Override
            public void testIgnored(Description d) {
                counts[1]++;
            }

            @Override
            public void testAssumptionFailure(Failure f) {
                counts[2]++;
            }

            @Override
            public void testFailure(Failure f) {
                failures.put(f.getDescription().getDisplayName(), f);
            }
        });
        long t0 = System.nanoTime();
        int failed = 0;
        long run = 0;
        long runtimeMs = 0;
        for (Request r : requests) {
            Result res = core.run(r);
            run += res.getRunCount();
            failed += res.getFailureCount();
        }
        runtimeMs = (System.nanoTime() - t0) / 1_000_000;
        int harness = 0;
        for (Failure f : failures.values()) {
            if (isHarnessLimitation(f.getException())) {
                harness++;
                System.out.println("SKIP (harness limitation, androidx.collection is not available offline) " + f.getDescription().getDisplayName());
                continue;
            }
            System.out.println("FAIL " + f.getDescription().getDisplayName());
            Throwable t = f.getException();
            String msg = String.valueOf(t);
            if (msg.length() > 600) {
                msg = msg.substring(0, 600) + "...";
            }
            System.out.println("     " + msg.replace("\n", "\n     "));
            int shown = 0;
            for (StackTraceElement e : t.getStackTrace()) {
                String c = e.getClassName();
                if (c.startsWith("com.artflow") || (shown < 2 && !c.startsWith("org.junit") && !c.startsWith("jdk.internal") && !c.startsWith("java.base"))) {
                    System.out.println("       at " + e);
                    if (++shown >= 6) {
                        break;
                    }
                }
            }
            Throwable cause = t.getCause();
            if (cause != null && cause != t) {
                System.out.println("     caused by " + cause);
            }
        }
        int realFailures = failed - harness;
        System.out.println("tests: " + run + " run in " + classCount + " class(es), " + realFailures + " failed, " + harness
            + " skipped (harness limitation), " + counts[1] + " ignored, " + counts[2] + " skipped by assumption, " + (runtimeMs / 1000) + "s");
        System.exit(realFailures == 0 ? 0 : 1);
    }

    /** The Compose runtime needs androidx.collection, which only exists on Google Maven (unreachable from the sandbox). */
    static boolean isHarnessLimitation(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if ((x instanceof NoClassDefFoundError || x instanceof ClassNotFoundException) && String.valueOf(x.getMessage()).replace('/', '.').startsWith("androidx.collection.")) {
                return true;
            }
            if (x.getCause() == x) {
                break;
            }
        }
        return false;
    }

    static boolean isTestClass(Class<?> c) {
        if (java.lang.reflect.Modifier.isAbstract(c.getModifiers()) || c.isInterface()) {
            return false;
        }
        if (c.isAnnotationPresent(RunWith.class)) {
            return true;
        }
        try {
            for (java.lang.reflect.Method m : c.getMethods()) {
                if (m.isAnnotationPresent(Test.class)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }
}
