package com.assistantbot.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Applies compact, line-addressed VXP-1 repairs without regenerating a draft. */
public final class VxbPatcher {
    static final String FORMAT_HELP = "One edit per line, for example: replace-line 59 z4 ....  |  "
            + "insert-after 59 z5 ....  |  delete-line 60";

    private VxbPatcher() {}

    public static String apply(String source, String patch) {
        List<String> lines = new ArrayList<>(List.of(source.split("\\r?\\n", -1)));
        List<Edit> edits = parse(patch);
        edits.sort(Comparator.comparingInt(Edit::line).reversed().thenComparing(Edit::kind));
        for (Edit edit : edits) {
            int index = edit.line() - 1;
            if (index < 0 || index >= lines.size()) throw new IllegalArgumentException("Patch line out of range: " + edit.line());
            switch (edit.kind()) {
                case "replace" -> lines.set(index, edit.text());
                case "delete" -> lines.remove(index);
                case "insert" -> lines.add(index + 1, edit.text());
                default -> throw new IllegalArgumentException("Unknown patch edit " + edit.kind());
            }
        }
        return String.join("\n", lines);
    }

    /** Only the current source rows named by diagnostics, avoiding a duplicate copy of a large draft. */
    public static String repairContext(String source, VxbDiagnostics.DiagnosticResult diagnostics) {
        String[] lines = source.split("\\r?\\n", -1);
        Set<Integer> requested = new TreeSet<>();
        for (VxbDiagnostics.Diagnostic diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.lineNum() != null) requested.add(diagnostic.lineNum());
        }
        StringBuilder out = new StringBuilder();
        for (int line : requested) {
            if (line < 1 || line > lines.length) continue;
            out.append(line).append(": ").append(lines[line - 1]).append('\n');
        }
        return out.toString().trim();
    }

    private static List<Edit> parse(String patch) {
        String cleaned = patch.trim();
        if (cleaned.startsWith("```")) {
            int newline = cleaned.indexOf('\n');
            cleaned = newline < 0 ? "" : cleaned.substring(newline + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
        }
        String[] lines = cleaned.split("\\r?\\n");
        List<Edit> result = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.equals("VXP-1") || line.equals("end")) continue;
            String[] p = line.split("\\s+", 3);
            if (p.length < 2) throw new IllegalArgumentException("Bad VXP-1 edit: " + line);
            int number;
            try {
                number = Integer.parseInt(p[1]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Bad VXP-1 line number: " + p[1]);
            }
            switch (p[0]) {
                case "replace-line" -> {
                    String replacement = p.length == 3 ? p[2] : followingText(lines, ++i, "replace-line");
                    result.add(new Edit("replace", number, replacement));
                }
                case "delete-line" -> result.add(new Edit("delete", number, ""));
                case "insert-after" -> {
                    String inserted = p.length == 3 ? p[2] : followingText(lines, ++i, "insert-after");
                    result.add(new Edit("insert", number, inserted));
                }
                default -> throw new IllegalArgumentException("Unknown VXP-1 command: " + p[0] + ". " + FORMAT_HELP);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("VXP-1 patch contains no edits");
        return result;
    }

    private static String followingText(String[] lines, int index, String command) {
        if (index >= lines.length) {
            throw new IllegalArgumentException(command + " requires replacement text. " + FORMAT_HELP);
        }
        String text = lines[index].trim();
        if (text.isEmpty() || text.equals("VXP-1") || text.equals("end") || isCommand(text)) {
            throw new IllegalArgumentException(command + " requires replacement text. " + FORMAT_HELP);
        }
        return text;
    }

    private static boolean isCommand(String line) {
        return line.startsWith("replace-line ") || line.startsWith("insert-after ")
                || line.startsWith("delete-line ");
    }

    private record Edit(String kind, int line, String text) {}
}
