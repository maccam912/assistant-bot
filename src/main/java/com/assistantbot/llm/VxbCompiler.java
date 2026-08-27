package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.PlacementGroup;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One deterministic entry point for imports, LLM drafts, diagnostics, and previews. */
public final class VxbCompiler {
    public record Compilation(BuildStructure structure, VxbDiagnostics.DiagnosticResult diagnostics,
                              int automaticCorrections) {}

    private VxbCompiler() {}

    public static Compilation compile(String source) {
        VxbDiagnostics.DiagnosticResult syntax = VxbDiagnostics.run(source);
        if (hasNonRegistryBlockers(syntax)) {
            throw new CompilationException(syntax.getLlmReport(), syntax);
        }

        BuildStructure structure;
        try {
            structure = BuildStructure.parse(source);
        } catch (IllegalArgumentException e) {
            VxbDiagnostics.DiagnosticResult failed = copyWithoutRegistryBlockers(syntax);
            failed.add(VxbDiagnostics.Severity.BLOCKER, "VXB Parse", e.getMessage(), null);
            throw new CompilationException(failed.getLlmReport(), failed);
        }

        Set<String> semanticStates = new HashSet<>();
        for (PlacementGroup group : structure.getFeatureGroups()) {
            for (BlockEntry block : group.blocks()) semanticStates.add(block.blockId());
        }

        int corrected = 0;
        Map<String, String> replacements = BlockIdResolver.mechanicalReplacements(structure.getUniqueBlockIds());
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            if (semanticStates.contains(replacement.getKey())) continue;
            structure.replaceBlockId(replacement.getKey(), replacement.getValue());
            corrected++;
        }

        VxbDiagnostics.DiagnosticResult effective = copyWithoutRegistryBlockers(syntax);
        for (String blockId : structure.getUniqueBlockIds()) {
            if (!BlockIdResolver.isValidBlockId(blockId)) {
                effective.add(VxbDiagnostics.Severity.BLOCKER, "Invalid Minecraft Block ID",
                        "Semantic or palette block '" + blockId + "' is not in the server registry.", null);
            }
        }
        if (corrected > 0) {
            effective.add(VxbDiagnostics.Severity.WARNING, "Mechanical Block ID Correction",
                    "Corrected " + corrected + " invalid ordinary palette block ID(s) to the closest server registry IDs.", null);
        }
        VxbStructureValidator.validate(structure, effective);
        structure.setDiagnostics(effective);
        if (effective.hasBlockers()) throw new CompilationException(effective.getLlmReport(), effective);
        return new Compilation(structure, effective, corrected);
    }

    private static boolean hasNonRegistryBlockers(VxbDiagnostics.DiagnosticResult result) {
        for (VxbDiagnostics.Diagnostic diagnostic : result.getBlockers()) {
            if (!diagnostic.checkName().equals("Invalid Minecraft Block ID")) return true;
        }
        return false;
    }

    private static VxbDiagnostics.DiagnosticResult copyWithoutRegistryBlockers(VxbDiagnostics.DiagnosticResult result) {
        VxbDiagnostics.DiagnosticResult copy = new VxbDiagnostics.DiagnosticResult();
        for (VxbDiagnostics.Diagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.severity() == VxbDiagnostics.Severity.BLOCKER
                    && diagnostic.checkName().equals("Invalid Minecraft Block ID")) continue;
            copy.add(diagnostic.severity(), diagnostic.checkName(), diagnostic.message(), diagnostic.lineNum());
        }
        return copy;
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
