package artflow.build;

import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.JdkClassFileProvider;
import com.android.tools.r8.Version;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.PackageReference;
import com.android.tools.r8.tracereferences.TraceReferences;
import com.android.tools.r8.tracereferences.TraceReferencesCommand;
import com.android.tools.r8.tracereferences.TraceReferencesConsumer;
import com.android.tools.r8.tracereferences.TraceReferencesKeepRules;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Derive only the application ABI called by the separately packaged instrumentation code. */
public final class TestAbiRules {
    private TestAbiRules() {}

    public static void main(String[] arguments) throws Exception {
        List<String> args = Arrays.asList(arguments);
        if (args.size() == 2 && args.get(0).equals("--arguments")) {
            args = Files.readAllLines(Path.of(args.get(1)), StandardCharsets.UTF_8);
        }
        Map<String, List<Path>> paths = new LinkedHashMap<>();
        Set<String> options = Set.of("--output", "--app-input", "--test-input", "--library");
        if (args.size() % 2 != 0) throw new IllegalArgumentException("Expected option/path pairs");
        for (int i = 0; i < args.size(); i += 2) {
            String option = args.get(i);
            if (!options.contains(option)) throw new IllegalArgumentException("Unknown option: " + option);
            paths.computeIfAbsent(option, ignored -> new ArrayList<>()).add(Path.of(args.get(i + 1)));
        }
        for (String option : options) {
            if (!paths.containsKey(option)) throw new IllegalArgumentException("Missing " + option);
        }
        if (paths.get("--output").size() != 1) throw new IllegalArgumentException("Expected one output directory");
        Path output = paths.get("--output").get(0);
        Files.createDirectories(output);
        Path rules = output.resolve("keep-rules.pro");
        Path report = output.resolve("report.json");
        Files.deleteIfExists(rules);
        Files.deleteIfExists(report);

        Map<String, byte[]> app = readClasses(paths.get("--app-input"), Set.of());
        Map<String, byte[]> tests = readClasses(paths.get("--test-input"), app.keySet());
        if (app.isEmpty() || tests.isEmpty()) throw new IllegalArgumentException("App and test bytecode must be nonempty");
        Path appJar = output.resolve("app-input.jar");
        Path testJar = output.resolve("test-input.jar");
        writeJar(appJar, app);
        writeJar(testJar, tests);

        Boundary boundary = new Boundary(app.keySet(), TraceReferencesKeepRules.builder()
                .setAllowObfuscation(true).setOutputPath(rules).build());
        TraceReferencesCommand.Builder command = TraceReferencesCommand.builder()
                .addTargetFiles(appJar).addSourceFiles(testJar).setConsumer(boundary);
        for (Path library : paths.get("--library")) {
            if (Files.isDirectory(library)) command.addLibraryResourceProvider(JdkClassFileProvider.fromJdkHome(library));
            else command.addLibraryFiles(library);
        }
        TraceReferences.run(command.build());
        if (!boundary.missingApp.isEmpty()) {
            Files.deleteIfExists(rules);
            throw new IllegalStateException("Tests reference missing application members: " + boundary.missingApp);
        }
        if (boundary.classes.isEmpty() || !Files.isRegularFile(rules) || Files.size(rules) == 0) {
            throw new IllegalStateException("No application/test ABI was traced");
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("generator", "R8 TraceReferences");
        evidence.put("r8Version", Version.getVersionString());
        evidence.put("r8Tool", fileRecord(Path.of(TraceReferences.class.getProtectionDomain().getCodeSource().getLocation().toURI())));
        evidence.put("scope", "benchmark-instrumentation-boundary");
        evidence.put("allowObfuscation", true);
        evidence.put("publisherReleaseRulesModified", false);
        evidence.put("appInputClasses", app.size());
        evidence.put("testInputClasses", tests.size());
        evidence.put("files", Map.of("appInput", fileRecord(appJar), "testInput", fileRecord(testJar), "rules", fileRecord(rules)));
        evidence.put("boundary", Map.of("classes", boundary.classes, "fields", boundary.fields,
                "methods", boundary.methods, "packages", boundary.packages));
        // Extraction is not an Android platform link checker. Optional framework/compiler-only
        // references outside the app are retained for audit; normal app/test R8 diagnostics still run.
        evidence.put("externalUnresolvedReferences", boundary.externalMissing);
        Files.writeString(report, json(evidence) + "\n", StandardCharsets.UTF_8);
        System.out.println("Traced " + boundary.classes.size() + " app classes, " + boundary.fields.size()
                + " fields and " + boundary.methods.size() + " methods from " + tests.size() + " test classes");
    }

    private static Map<String, byte[]> readClasses(List<Path> inputs, Set<String> appOwned) throws IOException {
        Map<String, byte[]> classes = new TreeMap<>();
        for (Path input : inputs) {
            if (Files.isDirectory(input)) {
                try (var files = Files.walk(input)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                        addClass(classes, input.relativize(file).toString().replace('\\', '/'),
                                () -> Files.readAllBytes(file), appOwned, input);
                    }
                }
            } else if (Files.isRegularFile(input)) {
                try (ZipFile archive = new ZipFile(input.toFile())) {
                    for (ZipEntry entry : archive.stream().sorted(java.util.Comparator.comparing(ZipEntry::getName)).toList()) {
                        if (!entry.isDirectory()) {
                            addClass(classes, entry.getName(), () -> {
                                try (InputStream stream = archive.getInputStream(entry)) { return stream.readAllBytes(); }
                            }, appOwned, input);
                        }
                    }
                }
            } else {
                throw new IOException("Missing bytecode input: " + input);
            }
        }
        return classes;
    }

    @FunctionalInterface
    private interface Bytes { byte[] read() throws IOException; }

    private static void addClass(Map<String, byte[]> classes, String name, Bytes data,
            Set<String> appOwned, Path origin) throws IOException {
        if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) return;
        if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw new IOException("Invalid class entry: " + name);
        // The app class loader owns overlaps. Tracing a duplicate in the source would hide its ABI.
        if (appOwned.contains(name)) return;
        byte[] value = data.read();
        byte[] previous = classes.putIfAbsent(name, value);
        if (previous != null && !Arrays.equals(previous, value)) {
            throw new IOException("Conflicting bytecode definitions for " + name + " in " + origin);
        }
    }

    private static void writeJar(Path file, Map<String, byte[]> classes) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : classes.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                item.setTime(0);
                output.putNextEntry(item);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static Map<String, Object> fileRecord(Path file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return Map.of("file", file.getFileName().toString(), "sizeBytes", Files.size(file), "sha256", HexFormat.of().formatHex(hash.digest()));
    }

    private static final class Boundary extends TraceReferencesConsumer.ForwardingConsumer {
        private final Set<String> appClasses;
        private final Set<String> classes = new ConcurrentSkipListSet<>();
        private final Set<String> fields = new ConcurrentSkipListSet<>();
        private final Set<String> methods = new ConcurrentSkipListSet<>();
        private final Set<String> packages = new ConcurrentSkipListSet<>();
        private final Set<String> externalMissing = new ConcurrentSkipListSet<>();
        private final Set<String> missingApp = new ConcurrentSkipListSet<>();

        private Boundary(Set<String> appClasses, TraceReferencesConsumer consumer) {
            super(consumer);
            this.appClasses = appClasses;
        }

        private boolean accept(ClassReference holder, boolean missing, String symbol) {
            boolean owned = appClasses.contains(holder.getBinaryName() + ".class");
            if (missing) {
                (owned ? missingApp : externalMissing).add(symbol);
                return false;
            }
            if (!owned) throw new IllegalStateException("Trace escaped the application boundary: " + symbol);
            return true;
        }

        @Override
        public void acceptType(TracedClass type, DiagnosticsHandler handler) {
            String name = type.getReference().getBinaryName().replace('/', '.');
            if (accept(type.getReference(), type.isMissingDefinition(), name)) {
                classes.add(name);
                super.acceptType(type, handler);
            }
        }

        @Override
        public void acceptField(TracedField field, DiagnosticsHandler handler) {
            String name = field.getReference().toSourceString();
            if (accept(field.getReference().getHolderClass(), field.isMissingDefinition(), name)) {
                fields.add(name);
                super.acceptField(field, handler);
            }
        }

        @Override
        public void acceptMethod(TracedMethod method, DiagnosticsHandler handler) {
            String name = method.getReference().toSourceString();
            if (accept(method.getReference().getHolderClass(), method.isMissingDefinition(), name)) {
                methods.add(name);
                super.acceptMethod(method, handler);
            }
        }

        @Override
        public void acceptPackage(PackageReference value, DiagnosticsHandler handler) {
            packages.add(value.getPackageName());
            super.acceptPackage(value, handler);
        }
    }

    private static String json(Object value) {
        if (value instanceof String text) {
            StringBuilder result = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '"' -> result.append("\\\"");
                    case '\\' -> result.append("\\\\");
                    case '\n' -> result.append("\\n");
                    case '\r' -> result.append("\\r");
                    case '\t' -> result.append("\\t");
                    default -> { if (c < 32) result.append(String.format("\\u%04x", (int) c)); else result.append(c); }
                }
            }
            return result.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            return "{" + String.join(",", map.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                    .map(entry -> json(entry.getKey()) + ":" + json(entry.getValue())).toList()) + "}";
        }
        if (value instanceof Collection<?> items) return "[" + String.join(",", items.stream().map(TestAbiRules::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported JSON value: " + value);
    }
}
