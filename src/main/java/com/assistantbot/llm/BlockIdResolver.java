package com.assistantbot.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

/**
 * Shared source of truth for Minecraft block IDs accepted by this server.
 *
 * Minecraft exposes the authoritative runtime block registry through
 * BuiltInRegistries.BLOCK. Keep prompt instructions, diagnostics, and mechanical
 * correction tied to that same registry so the LLM cannot drift from what the
 * game will actually accept.
 */
public final class BlockIdResolver {
    private BlockIdResolver() {
    }

    public static boolean isValidBlockId(String blockId) {
        String baseId = normalizeBaseId(blockId);
        Identifier id = Identifier.tryParse(baseId);
        return id != null && BuiltInRegistries.BLOCK.containsKey(id);
    }

    public static String closestValidBlockId(String blockId) {
        String rawBase = stripBlockState(blockId).toLowerCase();
        String comparableInput = comparableName(rawBase);

        String best = "minecraft:stone";
        int bestDistance = Integer.MAX_VALUE;

        for (Identifier candidateId : sortedRegistryIds()) {
            String fullCandidate = candidateId.toString();
            String comparableCandidate = comparableName(fullCandidate);
            int distance = levenshtein(comparableInput, comparableCandidate);

            if (distance < bestDistance || (distance == bestDistance && fullCandidate.compareTo(best) < 0)) {
                bestDistance = distance;
                best = fullCandidate;
            }
        }

        return best;
    }

    public static Map<String, String> mechanicalReplacements(Set<String> blockIds) {
        Map<String, String> replacements = new LinkedHashMap<>();
        List<String> sortedIds = new ArrayList<>(blockIds);
        sortedIds.sort(Comparator.naturalOrder());

        for (String blockId : sortedIds) {
            if (!isValidBlockId(blockId)) {
                replacements.put(blockId, closestValidBlockId(blockId));
            }
        }

        return replacements;
    }

    public static int replaceInvalidBlocksWithClosest(BuildStructure structure) {
        Map<String, String> replacements = mechanicalReplacements(structure.getUniqueBlockIds());
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            structure.replaceBlockId(entry.getKey(), entry.getValue());
        }
        return replacements.size();
    }

    public static String normalizeBaseId(String blockId) {
        return ensureNamespace(stripBlockState(blockId).trim().toLowerCase());
    }

    public static String stripBlockState(String blockId) {
        int bracketIdx = blockId.indexOf('[');
        return bracketIdx >= 0 ? blockId.substring(0, bracketIdx) : blockId;
    }

    private static String ensureNamespace(String name) {
        if (name.contains(":")) {
            return name;
        }
        return "minecraft:" + name;
    }

    private static List<Identifier> sortedRegistryIds() {
        List<Identifier> ids = new ArrayList<>(BuiltInRegistries.BLOCK.keySet());
        ids.sort(Comparator.comparing(Identifier::toString));
        return ids;
    }

    private static String comparableName(String fullOrShortId) {
        String normalized = ensureNamespace(fullOrShortId);
        Identifier id = Identifier.tryParse(normalized);
        if (id != null && id.getNamespace().equals("minecraft")) {
            return id.getPath();
        }
        return normalized;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];

        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitutionCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + substitutionCost
                );
            }

            int[] swap = previous;
            previous = current;
            current = swap;
        }

        return previous[right.length()];
    }
}
