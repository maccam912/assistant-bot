package com.assistantbot.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Applies compact, line-addressed VXP-1 repairs without regenerating a draft. */
public final class VxbPatcher {
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

    public static String numbered(String source) {
        String[] lines = source.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            out.append(i + 1).append(": ").append(lines[i]).append('\n');
        }
        return out.toString();
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
        for (String raw : lines) {
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
                    if (p.length < 3) throw new IllegalArgumentException("replace-line requires replacement text");
                    result.add(new Edit("replace", number, p[2]));
                }
                case "delete-line" -> result.add(new Edit("delete", number, ""));
                case "insert-after" -> {
                    if (p.length < 3) throw new IllegalArgumentException("insert-after requires inserted text");
                    result.add(new Edit("insert", number, p[2]));
                }
                default -> throw new IllegalArgumentException("Unknown VXP-1 command: " + p[0]);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("VXP-1 patch contains no edits");
        return result;
    }

    private record Edit(String kind, int line, String text) {}
}
