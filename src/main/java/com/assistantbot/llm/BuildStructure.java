package com.assistantbot.llm;

import java.util.*;

/**
 * A compiled structure: block entries in coordinates local to the build origin,
 * plus the placement groups and excavation cells the executor needs.
 *
 * <p>This is the format-independent result. VXB-2 source reaches it through
 * {@link Vxb2Parser} and {@link Vxb2Inference}; everything downstream — plan
 * storage, build execution, preview rendering — works from this class alone.
 *
 * @see <a href="../../minecraft_vxb_spec.md">VXB-2 specification</a>
 */
public class BuildStructure {

    /**
     * A single block in the structure, with coordinates relative to the build origin.
     */
    public record BlockEntry(int x, int y, int z, String blockId) {}

    public record Cell(int x, int y, int z) {}

    /** One placement operation. Atomic groups are installed before neighbor updates run. */
    public record PlacementGroup(String id, String kind, List<BlockEntry> blocks,
                                 List<Cell> requiredSupports, boolean atomic) {
        public PlacementGroup {
            blocks = List.copyOf(blocks);
            requiredSupports = List.copyOf(requiredSupports);
        }
    }

    private final List<BlockEntry> blocks;
    private final Map<String, Integer> materials;
    private final List<PlacementGroup> featureGroups;
    private final Set<Cell> clearCells;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final boolean allowFloating;
    private final boolean preserveTerrain;
    private VxbDiagnostics.DiagnosticResult diagnostics;
    private String name = "structure";
    private List<String> notes = List.of();
    private Map<String, Character> glyphs = Map.of();

    public BuildStructure(List<BlockEntry> blocks, Map<String, Integer> materials) {
        this(blocks, materials, List.of(), Set.of(), -1, -1, -1, false, false);
    }

    public BuildStructure(List<BlockEntry> blocks, Map<String, Integer> materials,
                          List<PlacementGroup> featureGroups, Set<Cell> clearCells,
                          int sizeX, int sizeY, int sizeZ, boolean allowFloating,
                          boolean preserveTerrain) {
        this.blocks = blocks;
        this.materials = materials;
        this.featureGroups = List.copyOf(featureGroups);
        this.clearCells = Set.copyOf(clearCells);
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.allowFloating = allowFloating;
        this.preserveTerrain = preserveTerrain;
        this.diagnostics = new VxbDiagnostics.DiagnosticResult();
    }

    public List<BlockEntry> getBlocks() { return blocks; }
    public Map<String, Integer> getMaterials() { return materials; }
    public VxbDiagnostics.DiagnosticResult getDiagnostics() { return diagnostics; }
    public List<PlacementGroup> getFeatureGroups() { return featureGroups; }
    public Set<Cell> getClearCells() { return clearCells; }
    public int getSizeX() { return sizeX; }
    public int getSizeY() { return sizeY; }
    public int getSizeZ() { return sizeZ; }
    public boolean isFloatingAllowed() { return allowFloating; }
    public boolean shouldPreserveTerrain() { return preserveTerrain; }
    public void setDiagnostics(VxbDiagnostics.DiagnosticResult diagnostics) { this.diagnostics = diagnostics; }
    public String getName() { return name; }
    /** Non-fatal things the compiler decided for the author, surfaced as warnings. */
    public List<String> getNotes() { return notes; }
    /** Palette symbol per base block ID, so a compiled build can be echoed back as VXB-2 slices. */
    public Map<String, Character> getGlyphs() { return glyphs; }

    /**
     * Returns all unique block IDs used in the structure.
     */
    public Set<String> getUniqueBlockIds() {
        Set<String> ids = new HashSet<>();
        for (BlockEntry entry : blocks) {
            ids.add(entry.blockId());
        }
        return ids;
    }

    /**
     * Replaces all occurrences of a block ID with a new one.
     * Since BlockEntry is a record (immutable), this rebuilds
     * affected entries in the list.
     */
    public void replaceBlockId(String oldId, String newId) {
        for (int i = 0; i < blocks.size(); i++) {
            BlockEntry entry = blocks.get(i);
            if (entry.blockId().equals(oldId)) {
                blocks.set(i, new BlockEntry(entry.x(), entry.y(), entry.z(), newId));
            }
        }
        // Update materials map
        if (materials.containsKey(oldId)) {
            int count = materials.remove(oldId);
            materials.merge(newId, count, Integer::sum);
        }
    }

    // --- VXB-2 parsing ---

    /**
     * Compiles VXB-2 source into a concrete block list.
     *
     * <p>The work splits in two: {@link Vxb2Parser} turns the drawn slices into a
     * glyph grid, and {@link Vxb2Inference} turns that grid into exact block
     * states. Between the two, palette block IDs are repaired against the live
     * server registry so that a near-miss name never reaches state inference.
     */
    public static BuildStructure parse(String vxb) {
        Vxb2Parser.Parsed parsed = Vxb2Parser.parse(vxb);
        List<String> notes = new ArrayList<>(parsed.notes());

        Map<String, String> repairs = BlockIdResolver.mechanicalReplacements(new HashSet<>(parsed.palette().values()));
        Map<Character, String> palette = new LinkedHashMap<>();
        for (Map.Entry<Character, String> entry : parsed.palette().entrySet()) {
            String repaired = repairs.get(entry.getValue());
            if (repaired == null) {
                palette.put(entry.getKey(), entry.getValue());
            } else {
                palette.put(entry.getKey(), repaired);
                notes.add("Palette '" + entry.getKey() + "' was corrected from " + entry.getValue()
                        + " to the closest registry block " + repaired + ".");
            }
        }

        Vxb2Parser.Parsed resolved = new Vxb2Parser.Parsed(parsed.name(), parsed.sizeX(), parsed.sizeY(),
                parsed.sizeZ(), parsed.ground(), parsed.preserveTerrain(), palette, parsed.hints(), parsed.grid(), notes);
        Vxb2Inference.Result inferred = Vxb2Inference.infer(resolved);

        if (inferred.blocks().isEmpty() && inferred.clearCells().isEmpty()) {
            throw new IllegalArgumentException("VXB-2 parsed but every drawn cell was air.");
        }

        Map<String, Integer> materials = new HashMap<>();
        for (BlockEntry block : inferred.blocks()) materials.merge(block.blockId(), 1, Integer::sum);

        BuildStructure structure = new BuildStructure(new ArrayList<>(inferred.blocks()), materials,
                inferred.groups(), inferred.clearCells(), parsed.sizeX(), parsed.sizeY(), parsed.sizeZ(),
                !parsed.ground(), parsed.preserveTerrain());
        structure.name = parsed.name();
        structure.notes = List.copyOf(inferred.notes());
        Map<String, Character> glyphs = new LinkedHashMap<>();
        for (Map.Entry<Character, String> entry : palette.entrySet()) {
            glyphs.putIfAbsent(BlockIdResolver.normalizeBaseId(entry.getValue()), entry.getKey());
        }
        structure.glyphs = Map.copyOf(glyphs);
        return structure;
    }

    /**
     * Pack 3 ints into a long for HashMap key.
     * Supports coordinates from -1048576 to 1048575 (21 bits each).
     */
    private static long packPos(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /**
     * Unpack a long back into [x, y, z] coordinates.
     */
    private static int[] unpackPos(long packed) {
        int x = (int) ((packed >> 42) & 0x1FFFFF);
        int y = (int) ((packed >> 21) & 0x1FFFFF);
        int z = (int) (packed & 0x1FFFFF);
        // Sign-extend from 21 bits
        if ((x & 0x100000) != 0) x |= ~0x1FFFFF;
        if ((y & 0x100000) != 0) y |= ~0x1FFFFF;
        if ((z & 0x100000) != 0) z |= ~0x1FFFFF;
        return new int[]{x, y, z};
    }

    // --- Build-order sort ---

    /**
     * Sort structural blocks so each one is placed only when it has a
     * face-adjacent neighbor that's already placed (or is on the ground,
     * y=0), then append support-, connection-, or orientation-sensitive
     * finishing blocks.
     *
     * Deferring windows, doors, fences, and similar details prevents them
     * from being temporarily unsupported or having later structural layers
     * overwrite/update them into an invalid shape. Standard doors are placed
     * last of all, ordered bottom-to-top within each column so their two
     * halves are installed together.
     */
    public static List<BlockEntry> sortBlocksBFS(List<BlockEntry> blocks) {
        List<BlockEntry> structural = new ArrayList<>();
        List<BlockEntry> finishing = new ArrayList<>();
        List<BlockEntry> doors = new ArrayList<>();

        for (BlockEntry entry : blocks) {
            if (isStandardDoor(entry.blockId())) {
                doors.add(entry);
            } else if (isFinishingBlock(entry.blockId())) {
                finishing.add(entry);
            } else {
                structural.add(entry);
            }
        }

        List<BlockEntry> sorted = sortConnectedBlocksBFS(structural);
        finishing.sort(Comparator.comparingInt(BlockEntry::y)
                .thenComparingInt(BlockEntry::x)
                .thenComparingInt(BlockEntry::z));
        doors.sort(Comparator.comparingInt(BlockEntry::x)
                .thenComparingInt(BlockEntry::z)
                .thenComparingInt(BlockEntry::y));
        sorted.addAll(finishing);
        sorted.addAll(doors);
        return sorted;
    }

    /**
     * Compile flattened voxels into executable operations while preserving semantic fixtures.
     * Plain door blocks are paired here too, so any door reaches the world atomically.
     */
    public static List<PlacementGroup> planPlacementGroups(BuildStructure structure) {
        Set<Long> semanticCells = new HashSet<>();
        for (PlacementGroup group : structure.featureGroups) {
            for (BlockEntry block : group.blocks()) semanticCells.add(packPos(block.x(), block.y(), block.z()));
        }

        List<BlockEntry> ordinary = new ArrayList<>();
        List<BlockEntry> legacyDoors = new ArrayList<>();
        for (BlockEntry block : structure.blocks) {
            if (semanticCells.contains(packPos(block.x(), block.y(), block.z()))) continue;
            if (isStandardDoor(block.blockId())) legacyDoors.add(block);
            else ordinary.add(block);
        }

        List<PlacementGroup> operations = new ArrayList<>();
        int opIndex = 0;
        for (BlockEntry block : sortBlocksBFS(ordinary)) {
            operations.add(new PlacementGroup("c" + (++opIndex), "block", List.of(block), List.of(), false));
        }

        Map<String, List<BlockEntry>> doorsByColumn = new TreeMap<>();
        for (BlockEntry door : legacyDoors) {
            String key = door.x() + "," + door.z();
            doorsByColumn.computeIfAbsent(key, ignored -> new ArrayList<>()).add(door);
        }
        for (List<BlockEntry> door : doorsByColumn.values()) {
            door.sort(Comparator.comparingInt(BlockEntry::y));
            int minY = door.getFirst().y();
            operations.add(new PlacementGroup("d" + (++opIndex), "door", door,
                    List.of(new Cell(door.getFirst().x(), minY - 1, door.getFirst().z())), door.size() > 1));
        }

        operations.addAll(sortSemanticDependencies(structure.featureGroups));
        return List.copyOf(operations);
    }

    private static List<PlacementGroup> sortSemanticDependencies(List<PlacementGroup> groups) {
        Comparator<PlacementGroup> order = Comparator.comparingInt(BuildStructure::minimumGroupY)
                .thenComparing(PlacementGroup::id);
        Map<Cell, PlacementGroup> ownerByCell = new HashMap<>();
        for (PlacementGroup group : groups) {
            for (BlockEntry block : group.blocks()) ownerByCell.put(new Cell(block.x(), block.y(), block.z()), group);
        }
        Map<PlacementGroup, Set<PlacementGroup>> outgoing = new HashMap<>();
        Map<PlacementGroup, Integer> indegree = new HashMap<>();
        for (PlacementGroup group : groups) indegree.put(group, 0);
        for (PlacementGroup dependent : groups) {
            Set<PlacementGroup> dependencies = new HashSet<>();
            for (Cell support : dependent.requiredSupports()) {
                PlacementGroup owner = ownerByCell.get(support);
                if (owner != null && owner != dependent) dependencies.add(owner);
            }
            for (PlacementGroup dependency : dependencies) {
                outgoing.computeIfAbsent(dependency, ignored -> new HashSet<>()).add(dependent);
                indegree.merge(dependent, 1, Integer::sum);
            }
        }
        Queue<PlacementGroup> ready = new PriorityQueue<>(order);
        for (PlacementGroup group : groups) if (indegree.get(group) == 0) ready.add(group);
        List<PlacementGroup> sorted = new ArrayList<>();
        while (!ready.isEmpty()) {
            PlacementGroup group = ready.remove();
            sorted.add(group);
            for (PlacementGroup dependent : outgoing.getOrDefault(group, Set.of())) {
                int remaining = indegree.merge(dependent, -1, Integer::sum);
                if (remaining == 0) ready.add(dependent);
            }
        }
        if (sorted.size() != groups.size()) {
            List<PlacementGroup> cyclic = new ArrayList<>(groups);
            cyclic.removeAll(sorted);
            cyclic.sort(order);
            sorted.addAll(cyclic);
        }
        return sorted;
    }

    private static int minimumGroupY(PlacementGroup group) {
        int result = Integer.MAX_VALUE;
        for (BlockEntry block : group.blocks()) result = Math.min(result, block.y());
        return result;
    }

    private static List<BlockEntry> sortConnectedBlocksBFS(List<BlockEntry> blocks) {
        Map<Long, BlockEntry> posMap = new HashMap<>();
        for (BlockEntry entry : blocks) {
            posMap.put(packPos(entry.x(), entry.y(), entry.z()), entry);
        }

        Set<Long> placed = new HashSet<>();
        List<BlockEntry> sorted = new ArrayList<>();
        Queue<BlockEntry> ready = new LinkedList<>();

        for (BlockEntry entry : blocks) {
            if (entry.y() == 0) {
                long key = packPos(entry.x(), entry.y(), entry.z());
                if (placed.add(key)) {
                    ready.add(entry);
                }
            }
        }

        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!ready.isEmpty()) {
            BlockEntry current = ready.poll();
            sorted.add(current);

            for (int[] dir : directions) {
                int nx = current.x() + dir[0];
                int ny = current.y() + dir[1];
                int nz = current.z() + dir[2];
                long nKey = packPos(nx, ny, nz);

                if (posMap.containsKey(nKey) && placed.add(nKey)) {
                    ready.add(posMap.get(nKey));
                }
            }
        }

        List<BlockEntry> orphans = new ArrayList<>();
        for (BlockEntry entry : blocks) {
            long key = packPos(entry.x(), entry.y(), entry.z());
            if (!placed.contains(key)) {
                orphans.add(entry);
            }
        }
        orphans.sort(Comparator.comparingInt(BlockEntry::y)
                .thenComparingInt(BlockEntry::x)
                .thenComparingInt(BlockEntry::z));
        sorted.addAll(orphans);

        return sorted;
    }

    private static boolean isStandardDoor(String blockId) {
        String path = blockPath(blockId);
        return path.endsWith("_door") && !path.endsWith("_trapdoor");
    }

    private static boolean isFinishingBlock(String blockId) {
        String path = blockPath(blockId);
        return path.contains("glass")
                || path.endsWith("_trapdoor")
                || path.endsWith("_fence")
                || path.endsWith("_fence_gate")
                || path.endsWith("_wall")
                || path.endsWith("_stairs")
                || path.endsWith("_slab")
                || path.endsWith("_button")
                || path.endsWith("_pressure_plate")
                || path.endsWith("_sign")
                || path.endsWith("_hanging_sign")
                || path.endsWith("_banner")
                || path.endsWith("_wall_banner")
                || path.endsWith("_torch")
                || path.endsWith("_wall_torch")
                || path.endsWith("_bed")
                || path.endsWith("_rail")
                || path.equals("iron_bars")
                || path.equals("chain")
                || path.equals("ladder")
                || path.equals("lever")
                || path.equals("lantern")
                || path.equals("soul_lantern");
    }

    private static String blockPath(String blockId) {
        String baseId = blockId;
        int stateStart = baseId.indexOf('[');
        if (stateStart >= 0) {
            baseId = baseId.substring(0, stateStart);
        }
        int namespaceSeparator = baseId.indexOf(':');
        if (namespaceSeparator >= 0) {
            baseId = baseId.substring(namespaceSeparator + 1);
        }
        return baseId.trim().toLowerCase(Locale.ROOT);
    }
}
