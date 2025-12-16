package es.upm.testsmells;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "detect-smells", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public class DetectSmellsMojo extends AbstractMojo {

    private static final Pattern TEST_BLOCK = Pattern.compile(
            "@Test\\b[\\s\\S]*?\\n\\s*(public|protected|private)?\\s*[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{",
            Pattern.MULTILINE
    );

    @Override
    public void execute() {
        Path root = Paths.get("src/test/java");

        List<Path> files = new ArrayList<>();
        try {
            Files.walk(root).filter(p -> p.toString().endsWith(".java")).forEach(files::add);
        } catch (IOException e) {
            getLog().error("Error leyendo tests", e);
            return;
        }

        Map<String, List<String>> report = new LinkedHashMap<>();

        for (Path file : files) {
            String code;
            try {
                code = Files.readString(file);
            } catch (IOException e) {
                continue;
            }

            List<String> smells = new ArrayList<>();

            if (code.contains("@Disabled")) smells.add("Ignored Test");
            if (Pattern.compile("\\bpublic\\s+\\w+Test\\s*\\([^)]*\\)\\s*\\{\\s*[^}]+\\}", Pattern.DOTALL).matcher(code).find())
                smells.add("Constructor Initialization");

            if (hasAny(code, "Files.", "Paths.", "FileInputStream", "FileOutputStream", "Socket", "URL(", "DriverManager", "Connection"))
                smells.add("Mystery Guest");

            if (hasAny(code, "Files.read", "Files.newInputStream", "new FileInputStream", "getResourceAsStream"))
                smells.add("Resource Optimism");

            if (hasBigBeforeEach(code)) smells.add("General Fixture");

            for (TestInfo t : getTests(code)) {
                String b = t.body;

                if (b.trim().isEmpty()) smells.add("Empty Test: " + t.name);

                if (!hasAny(b, "assert", "Assertions.assert", "assertThrows", "fail(", "Assertions.fail"))
                    smells.add("Unknown Test: " + t.name);

                if (hasAny(b, "Thread.sleep", "TimeUnit.SECONDS.sleep", "TimeUnit.MILLISECONDS.sleep"))
                    smells.add("Sleepy Test: " + t.name);

                if (hasAny(b, "System.out.print", "System.err.print"))
                    smells.add("Redundant Print: " + t.name);

                if (hasAny(b, "if (", "for (", "while (", "switch (", "?:"))
                    smells.add("Conditional Test Logic: " + t.name);

                if (b.contains("try {") && b.contains("catch ("))
                    smells.add("Exception Handling: " + t.name);

                if (isAssertionRoulette(b))
                    smells.add("Assertion Roulette: " + t.name);

                if (hasDuplicateAssertLines(b))
                    smells.add("Duplicate Assert: " + t.name);

                if (Pattern.compile("\\b(assert\\w*|Assertions\\.assert\\w*)\\s*\\([^\\)]*\\b\\d+\\b[^\\)]*\\)").matcher(b).find())
                    smells.add("Magic Number Test: " + t.name);

                if (Pattern.compile("\\b(assertEquals|Assertions\\.assertEquals)\\s*\\(\\s*\\d+\\.\\d+\\s*,\\s*[^,\\)]+\\)").matcher(b).find())
                    smells.add("Sensitive Equality: " + t.name);

                String flat = b.replaceAll("\\s+", "");
                if (flat.contains("assertTrue(true)") || flat.contains("Assertions.assertTrue(true)")
                        || flat.contains("assertFalse(false)") || flat.contains("Assertions.assertFalse(false)"))
                    smells.add("Redundant Assertion: " + t.name);

                if (hasAny(b, "fail(\"Not yet implemented", "TODO", "throw new UnsupportedOperationException"))
                    smells.add("Default Test: " + t.name);

                if (isLazy(b)) smells.add("Lazy Test: " + t.name);
                if (isEager(b)) smells.add("Eager Test: " + t.name);
            }

            if (!smells.isEmpty()) {
                report.put(root.relativize(file).toString(), new ArrayList<>(new LinkedHashSet<>(smells)));
            }
        }

        if (report.isEmpty()) {
            getLog().info("No se detectaron test smells.");
            return;
        }

        int total = 0;
        getLog().info("=== Test Smells (JUnit 5) ===");
        for (var entry : report.entrySet()) {
            getLog().info(entry.getKey());
            for (String s : entry.getValue()) {
                getLog().info("  - " + s);
                total++;
            }
        }
        getLog().info("Total: " + total);
    }

    private boolean hasAny(String text, String... keys) {
        for (String k : keys) if (text.contains(k)) return true;
        return false;
    }

    private static class TestInfo {
        String name;
        String body;
        TestInfo(String name, String body) {
            this.name = name;
            this.body = body;
        }
    }

    private List<TestInfo> getTests(String code) {
        List<TestInfo> list = new ArrayList<>();
        Matcher m = TEST_BLOCK.matcher(code);

        while (m.find()) {
            String name = m.group(2);
            int open = m.end() - 1;
            int close = matchBrace(code, open);
            if (close > open) {
                String body = code.substring(open + 1, close);
                list.add(new TestInfo(name, body));
            }
        }
        return list;
    }

    private int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            if (s.charAt(i) == '{') depth++;
            if (s.charAt(i) == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private boolean hasBigBeforeEach(String code) {
        int i = code.indexOf("@BeforeEach");
        if (i < 0) return false;
        int open = code.indexOf("{", i);
        if (open < 0) return false;
        int close = matchBrace(code, open);
        if (close < 0) return false;
        String body = code.substring(open + 1, close);
        return count(body, ";") >= 10;
    }

    private int count(String s, String token) {
        int c = 0, i = 0;
        while ((i = s.indexOf(token, i)) != -1) {
            c++;
            i += token.length();
        }
        return c;
    }

    private int countRegex(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        int c = 0;
        while (m.find()) c++;
        return c;
    }

    private boolean isAssertionRoulette(String body) {
        int asserts = countRegex(body, "\\bassert\\w*\\s*\\(") + countRegex(body, "\\bAssertions\\.assert\\w*\\s*\\(");
        if (asserts < 4) return false;

        int withMsg = countRegex(body, "assert\\w*\\s*\\([^;]*\"[^\"]+\"[^;]*\\);")
                + countRegex(body, "Assertions\\.assert\\w*\\s*\\([^;]*\"[^\"]+\"[^;]*\\);");

        return withMsg == 0;
    }

    private boolean hasDuplicateAssertLines(String body) {
        String[] lines = body.split("\\R");
        Map<String, Integer> freq = new HashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("assert") || t.startsWith("Assertions.assert")) {
                freq.put(t, freq.getOrDefault(t, 0) + 1);
            }
        }
        for (int v : freq.values()) if (v >= 2) return true;
        return false;
    }

    private boolean isLazy(String body) {
        int stmts = count(body, ";");
        int asserts = countRegex(body, "\\bassert\\w*\\s*\\(") + countRegex(body, "\\bAssertions\\.assert\\w*\\s*\\(");
        return stmts <= 2 && asserts <= 1;
    }

    private boolean isEager(String body) {
        int calls = countRegex(body, "\\b\\w+\\s*\\(");
        int asserts = countRegex(body, "\\bassert\\w*\\s*\\(") + countRegex(body, "\\bAssertions\\.assert\\w*\\s*\\(");
        return (calls - asserts) >= 12;
    }
}
