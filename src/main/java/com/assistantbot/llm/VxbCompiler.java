package com.assistantbot.llm;

/** One deterministic entry point for imports, LLM drafts, diagnostics, and previews. */
public final class VxbCompiler {
    public record Compilation(BuildStructure structure, VxbDiagnostics.DiagnosticResult diagnostics,
                              int automaticCorrections) {}

    private VxbCompiler() {}

    public static Compilation compile(String source) {
        BuildStructure structure;
        try {
            structure = BuildStructure.parse(source);
        } catch (IllegalArgumentException e) {
            VxbDiagnostics.DiagnosticResult failed = new VxbDiagnostics.DiagnosticResult();
            failed.add(VxbDiagnostics.Severity.BLOCKER, "VXB-2 Syntax", e.getMessage(),
                    VxbDiagnostics.lineNumber(e.getMessage()));
            throw new CompilationException(failed.getLlmReport(), failed);
        }

        VxbDiagnostics.DiagnosticResult result = new VxbDiagnostics.DiagnosticResult();
        for (String note : structure.getNotes()) {
            result.add(VxbDiagnostics.Severity.WARNING, "Compiler Correction", note, null);
        }
        for (String blockId : structure.getUniqueBlockIds()) {
            if (!BlockIdResolver.isValidBlockId(blockId)) {
                result.add(VxbDiagnostics.Severity.BLOCKER, "Invalid Minecraft Block ID",
                        "Block '" + blockId + "' is not in the server registry.", null);
            }
        }
        VxbStructureValidator.validate(structure, result);
        structure.setDiagnostics(result);
        if (result.hasBlockers()) throw new CompilationException(result.getLlmReport(), result);
        return new Compilation(structure, result, structure.getNotes().size());
    }

    public static final class CompilationException extends IllegalArgumentException {
        private final VxbDiagnostics.DiagnosticResult diagnostics;

        public CompilationException(String message, VxbDiagnostics.DiagnosticResult diagnostics) {
            super(message);
            this.diagnostics = diagnostics;
        }

        public VxbDiagnostics.DiagnosticResult diagnostics() { return diagnostics; }
    }
}
