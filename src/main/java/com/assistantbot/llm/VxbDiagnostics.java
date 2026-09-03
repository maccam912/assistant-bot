package com.assistantbot.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic reporting shared by the compiler, the repair loop and the chat commands.
 *
 * <p>VXB-2 has a single compilation path, so this class no longer re-implements a
 * text-level checker: {@link #run(String)} compiles the draft and reports whatever
 * the compiler found. Keeping one implementation means a draft can never pass the
 * linter and then fail the build, which was a real failure mode when the two
 * disagreed.
 */
public class VxbDiagnostics {

    public enum Severity {
        BLOCKER,
        WARNING
    }

    public record Diagnostic(Severity severity, String checkName, String message, Integer lineNum) {
        @Override
        public String toString() {
            String location = lineNum == null ? "" : " (line " + lineNum + ")";
            return "[" + severity + "] " + checkName + location + ": " + message;
        }

        public String toLlmReportString() {
            String location = lineNum == null ? "" : " [line " + lineNum + "]";
            return "- " + severity + location + " " + checkName + ": " + message;
        }
    }

    public static class DiagnosticResult {
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private boolean hasBlockers;
        private boolean hasWarnings;

        public void add(Severity severity, String checkName, String message, Integer lineNum) {
            diagnostics.add(new Diagnostic(severity, checkName, message, lineNum));
            if (severity == Severity.BLOCKER) hasBlockers = true;
            else hasWarnings = true;
        }

        public List<Diagnostic> getDiagnostics() { return diagnostics; }

        public boolean hasBlockers() { return hasBlockers; }

        public boolean hasWarnings() { return hasWarnings; }

        public List<Diagnostic> getBlockers() {
            return diagnostics.stream().filter(d -> d.severity() == Severity.BLOCKER).toList();
        }

        public List<Diagnostic> getWarnings() {
            return diagnostics.stream().filter(d -> d.severity() == Severity.WARNING).toList();
        }

        public String getLlmReport() {
            if (diagnostics.isEmpty()) return "No issues found.";
            StringBuilder report = new StringBuilder();
            for (Diagnostic diagnostic : diagnostics) {
                report.append(diagnostic.toLlmReportString()).append('\n');
            }
            return report.toString().trim();
        }
    }

    /** Compiles a draft purely to collect diagnostics; never throws. */
    public static DiagnosticResult run(String vxb) {
        try {
            return VxbCompiler.compile(vxb).diagnostics();
        } catch (VxbCompiler.CompilationException e) {
            return e.diagnostics();
        } catch (RuntimeException e) {
            DiagnosticResult result = new DiagnosticResult();
            result.add(Severity.BLOCKER, "VXB-2 Compile", String.valueOf(e.getMessage()), null);
            return result;
        }
    }

    /** Pulls the {@code Line N:} prefix off a parser message so repairs can address it. */
    public static Integer lineNumber(String message) {
        if (message == null || !message.startsWith("Line ")) return null;
        int colon = message.indexOf(':');
        if (colon < 0) return null;
        try {
            return Integer.parseInt(message.substring(5, colon).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
