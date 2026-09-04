package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.Cell;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses VXB-2 into a glyph grid.
 *
 * <p>VXB-2 has exactly one way to place a block: draw it as a character in a 2D
 * slice. There are no volume primitives and no coordinate commands, so the source
 * text is a literal picture of the finished structure. Slices may be taken on any
 * axis — {@code plan y=N} is a top-down level, {@code face south z=N} is an
 * elevation viewed from outside — which lets walls, gables and facades be drawn
 * the way a builder would draw them rather than reconstructed from stacked
 * top-down levels.
 *
 * <p>Every slice is authoritative for the cells it covers, so file order is
 * irrelevant and two views that disagree about a shared cell are a compile error
 * rather than a silent overwrite. That cross-view agreement check is the
 * mechanical test of whether the author actually held a consistent 3D shape in
 * mind.
 *
 * <p>Block states are deliberately absent from this layer: the parser only
 * records which palette symbol occupies which cell. {@link Vxb2Inference} derives
 * facings, axes, halves and hinges from the drawn shape afterwards.
 */
public final class Vxb2Parser {

    /** Result of a successful parse: a bounded glyph grid plus its header. */
    public record Parsed(String name, int sizeX, int sizeY, int sizeZ,
                         boolean ground, boolean preserveTerrain,
                         Map<Character, String> palette,
                         Map<Character, Map<String, String>> hints,
                         Map<Cell, Character> grid,
                         Map<Cell, Integer> sourceLines,
                         List<String> notes) {}

    private enum View { PLAN, SOUTH, NORTH, EAST, WEST }

    private enum Axis { X, Y, Z }

    private Vxb2Parser() {}

    public static Parsed parse(String source) {
        Ctx ctx = new Ctx(splitLines(source));
        ctx.skipToHeader();
        while (ctx.hasMore()) {
            int lineNumber = ctx.lineNumber();
            String line = ctx.next();
            if (line == null) continue;
            if (!directive(ctx, line, lineNumber, ctx.grid, 0, 0, 0, ctx.sizeX, ctx.sizeY, ctx.sizeZ, false)) {
                throw error(lineNumber, "Unrecognized VXB-2 line '" + line
                        + "'. Expected name, size, ground, terrain, pal, plan, face, part or at.");
            }
        }
        for (Placement placement : ctx.placements) {
            placePart(ctx, placement.line(), placement.lineNumber());
        }
        if (ctx.sizeX < 1) throw new IllegalArgumentException("VXB-2 requires a 'size X Y Z' line.");
        if (ctx.palette.isEmpty()) throw new IllegalArgumentException("VXB-2 requires a 'pal' section with at least one symbol.");
        if (ctx.grid.isEmpty()) throw new IllegalArgumentException("VXB-2 produced no cells: no plan or face slices were drawn.");
        return new Parsed(ctx.name, ctx.sizeX, ctx.sizeY, ctx.sizeZ, ctx.ground, ctx.preserveTerrain,
                Map.copyOf(ctx.palette), Map.copyOf(ctx.hints), ctx.grid, Map.copyOf(ctx.sourceLines),
                List.copyOf(ctx.notes));
    }

    /**
     * Handles one non-blank source line. Slices write into {@code target} using the
     * supplied local bounds so that part bodies reuse the identical slice grammar.
     */
    private static boolean directive(Ctx ctx, String line, int lineNumber, Map<Cell, Character> target,
                                     int baseX, int baseY, int baseZ, int boundX, int boundY, int boundZ,
                                     boolean insidePart) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name ")) {
            if (!insidePart) ctx.name = line.substring(5).trim();
            return true;
        }
        if (lower.startsWith("size ")) {
            if (insidePart) throw error(lineNumber, "'size' belongs in the file header; a part declares its own size.");
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 4) throw error(lineNumber, "size needs three numbers: size X Y Z");
            ctx.sizeX = positive(parts[1], lineNumber, "size X");
            ctx.sizeY = positive(parts[2], lineNumber, "size Y");
            ctx.sizeZ = positive(parts[3], lineNumber, "size Z");
            return true;
        }
        if (lower.startsWith("ground ")) {
            ctx.ground = flag(lower.substring(7).trim(), lineNumber, "ground");
            return true;
        }
        if (lower.startsWith("terrain ")) {
            String mode = lower.substring(8).trim();
            if (mode.equals("keep") || mode.equals("preserve")) ctx.preserveTerrain = true;
            else if (mode.equals("replace")) ctx.preserveTerrain = false;
            else throw error(lineNumber, "terrain must be 'replace' or 'keep'");
            return true;
        }
        // Harmless header noise from older generators; the axes are fixed and the world
        // anchor comes from the bot, so both are simply ignored rather than rejected.
        if (lower.startsWith("axes ") || lower.startsWith("origin ")) return true;
        if (lower.equals("end")) return true; // tolerate a stray section terminator
        if (lower.equals("pal") || lower.equals("palette")) {
            readPalette(ctx);
            return true;
        }
        if (lower.startsWith("part ")) {
            if (insidePart) throw error(lineNumber, "Parts cannot be nested.");
            readPart(ctx, line, lineNumber);
            return true;
        }
        if (lower.startsWith("at ")) {
            if (insidePart) throw error(lineNumber, "'at' placements belong in the main body, not inside a part.");
            // Resolve all placements after reading the complete file. This makes part
            // declarations order-independent, just like slices, and prevents a large
            // draft from failing merely because a reusable part is defined later.
            ctx.placements.add(new Placement(line, lineNumber));
            return true;
        }
        if (lower.startsWith("plan ") || lower.startsWith("face ")) {
            Slice slice = parseSliceHeader(line, lineNumber, boundX, boundY, boundZ);
            readSlice(ctx, slice, lineNumber, target, baseX, baseY, baseZ);
            return true;
        }
        return false;
    }

    // --- palette -------------------------------------------------------------

    private static void readPalette(Ctx ctx) {
        while (ctx.hasMore()) {
            int lineNumber = ctx.lineNumber();
            String line = ctx.nextPalette();
            if (line == null) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.equals("end") || lower.equals("endpal") || lower.equals("endpalette")) return;
            String body = line.trim();
            if (body.length() > 1 && body.charAt(1) == '=') body = body.charAt(0) + " " + body.substring(2);
            String[] tokens = body.split("\\s+");
            if (tokens.length < 2) throw error(lineNumber, "Palette entry must be '<symbol> <block_id>', got '" + line + "'");
            if (tokens[1].equals("=")) {
                throw error(lineNumber, "Palette entry must be '<symbol> <block_id>', got '" + line + "'");
            }
            String symbolPart = tokens[0];
            String blockId = tokens[1];
            Map<String, String> entryHints = new LinkedHashMap<>();
            for (int i = 2; i < tokens.length; i++) {
                String[] hint = tokens[i].split("=", 2);
                String key = hint[0].toLowerCase(Locale.ROOT);
                String value = hint.length == 2 ? hint[1].toLowerCase(Locale.ROOT) : "true";
                switch (key) {
                    case "up", "axis", "half", "facing", "outside", "top", "hanging" -> entryHints.put(key, value);
                    default -> throw error(lineNumber, "Unknown palette hint '" + tokens[i]
                            + "'. Supported hints are up=, axis=, half=, facing=, outside=, hanging=.");
                }
            }
            if (symbolPart.length() != 1) {
                throw error(lineNumber, "Palette symbols are exactly one character; got '" + symbolPart + "'");
            }
            char symbol = symbolPart.charAt(0);
            if (blockId.isEmpty()) throw error(lineNumber, "Palette entry '" + symbol + "' has no block id.");
            if (!entryHints.isEmpty()) ctx.hints.put(symbol, entryHints);
            if (symbol == '.') {
                if (!normalize(blockId).equals("minecraft:air")) {
                    throw error(lineNumber, "'.' always means air and cannot be redefined.");
                }
                continue;
            }
            if (Character.isWhitespace(symbol)) throw error(lineNumber, "Palette symbols cannot be whitespace.");
            String previous = ctx.palette.put(symbol, normalize(blockId));
            if (previous != null && !previous.equals(normalize(blockId))) {
                throw error(lineNumber, "Palette symbol '" + symbol + "' is defined twice ("
                        + previous + " and " + normalize(blockId) + ").");
            }
        }
        throw new IllegalArgumentException("Unterminated 'pal' section: add 'end' after the last symbol.");
    }

    // --- parts ---------------------------------------------------------------

    private static void readPart(Ctx ctx, String line, int lineNumber) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 5) throw error(lineNumber, "part needs a name and a size: part <name> <X> <Y> <Z>");
        String name = parts[1].toLowerCase(Locale.ROOT);
        int px = positive(parts[2], lineNumber, "part size X");
        int py = positive(parts[3], lineNumber, "part size Y");
        int pz = positive(parts[4], lineNumber, "part size Z");
        Map<Cell, Character> cells = new HashMap<>();
        while (ctx.hasMore()) {
            int bodyLine = ctx.lineNumber();
            String body = ctx.next();
            if (body == null) continue;
            String lower = body.toLowerCase(Locale.ROOT);
            if (lower.equals("end") || lower.equals("end part") || lower.equals("endpart")) {
                if (cells.isEmpty()) {
                    ctx.note("Line " + lineNumber + ": empty part '" + name + "' was ignored when placed.");
                }
                ctx.parts.put(name, new Part(name, px, py, pz, cells));
                return;
            }
            if (!directive(ctx, body, bodyLine, cells, 0, 0, 0, px, py, pz, true)) {
                throw error(bodyLine, "Part bodies accept only plan and face slices; got '" + body + "'");
            }
        }
        throw error(lineNumber, "Unterminated part '" + name + "': add 'end' after its slices.");
    }

    private static void placePart(Ctx ctx, String line, int lineNumber) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length < 5) throw error(lineNumber, "Placement must be 'at X Y Z <part> [turn=90] [flip=x]'");
        int x = number(parts[1], lineNumber, "at X");
        int y = number(parts[2], lineNumber, "at Y");
        int z = number(parts[3], lineNumber, "at Z");
        String name = parts[4].toLowerCase(Locale.ROOT);
        Part part = ctx.parts.get(name);
        if (part == null) {
            throw error(lineNumber, "Unknown part '" + name + "'. Define it anywhere with 'part "
                    + name + " X Y Z'.");
        }
        int turn = 0;
        String flip = "none";
        for (int i = 5; i < parts.length; i++) {
            String[] option = parts[i].split("=", 2);
            if (option.length != 2) throw error(lineNumber, "Bad placement option '" + parts[i] + "'");
            switch (option[0].toLowerCase(Locale.ROOT)) {
                case "turn", "rotate" -> turn = switch (option[1]) {
                    case "0", "90", "180", "270" -> Integer.parseInt(option[1]);
                    default -> throw error(lineNumber, "turn must be 0, 90, 180 or 270");
                };
                case "flip", "mirror" -> flip = switch (option[1].toLowerCase(Locale.ROOT)) {
                    case "x", "z", "none" -> option[1].toLowerCase(Locale.ROOT);
                    default -> throw error(lineNumber, "flip must be x, z or none");
                };
                default -> throw error(lineNumber, "Unknown placement option '" + option[0] + "'");
            }
        }

        int turnedX = (turn == 90 || turn == 270) ? part.sz() : part.sx();
        int turnedZ = (turn == 90 || turn == 270) ? part.sx() : part.sz();
        for (Map.Entry<Cell, Character> entry : part.cells().entrySet()) {
            // Air in a reusable module is transparent. This lets furnishing and
            // detail parts be stamped into an existing shell without erasing or
            // conflicting with its walls. Explicit excavation belongs in a main
            // body slice, whose dots remain authoritative under terrain keep.
            if (entry.getValue() == '.') continue;
            Cell local = entry.getKey();
            int lx = local.x();
            int lz = local.z();
            int tx;
            int tz;
            switch (turn) {
                case 90 -> { tx = part.sz() - 1 - lz; tz = lx; }
                case 180 -> { tx = part.sx() - 1 - lx; tz = part.sz() - 1 - lz; }
                case 270 -> { tx = lz; tz = part.sx() - 1 - lx; }
                default -> { tx = lx; tz = lz; }
            }
            if (flip.equals("x")) tx = turnedX - 1 - tx;
            if (flip.equals("z")) tz = turnedZ - 1 - tz;
            stampPart(ctx, new Cell(x + tx, y + local.y(), z + tz), entry.getValue(), lineNumber, name);
        }
    }

    // --- slices --------------------------------------------------------------

    private record Slice(View view, List<Integer> planes, Axis rowAxis, boolean rowDescending,
                         int rowFrom, int rowTo, Axis colAxis, boolean colDescending, int colFrom, int colTo) {
        int rows() { return rowTo - rowFrom + 1; }
        int width() { return colTo - colFrom + 1; }
    }

    private static Slice parseSliceHeader(String line, int lineNumber, int boundX, int boundY, int boundZ) {
        String[] tokens = line.trim().split("\\s+");
        View view;
        int firstOption;
        if (tokens[0].equalsIgnoreCase("plan")) {
            view = View.PLAN;
            firstOption = 1;
        } else {
            if (tokens.length < 2) throw error(lineNumber, "face needs a viewing side: face south z=6");
            view = switch (tokens[1].toLowerCase(Locale.ROOT)) {
                case "south" -> View.SOUTH;
                case "north" -> View.NORTH;
                case "east" -> View.EAST;
                case "west" -> View.WEST;
                default -> throw error(lineNumber, "face side must be north, south, east or west; got '" + tokens[1] + "'");
            };
            firstOption = 2;
        }

        Axis planeAxis = switch (view) {
            case PLAN -> Axis.Y;
            case SOUTH, NORTH -> Axis.Z;
            case EAST, WEST -> Axis.X;
        };
        Axis rowAxis = view == View.PLAN ? Axis.Z : Axis.Y;
        Axis colAxis = switch (view) {
            case PLAN, SOUTH, NORTH -> Axis.X;
            case EAST, WEST -> Axis.Z;
        };

        List<Integer> planes = null;
        int[] rowRange = null;
        int[] colRange = null;
        for (int i = firstOption; i < tokens.length; i++) {
            String[] option = tokens[i].split("=", 2);
            if (option.length != 2) throw error(lineNumber, "Slice options look like 'y=3' or 'x=1..7'; got '" + tokens[i] + "'");
            Axis axis = switch (option[0].toLowerCase(Locale.ROOT)) {
                case "x" -> Axis.X;
                case "y" -> Axis.Y;
                case "z" -> Axis.Z;
                default -> throw error(lineNumber, "Slice option axis must be x, y or z; got '" + option[0] + "'");
            };
            if (axis == planeAxis) {
                planes = planeSpec(option[1], lineNumber, bound(planeAxis, boundX, boundY, boundZ), option[0]);
            } else if (axis == rowAxis) {
                rowRange = window(option[1], lineNumber, bound(rowAxis, boundX, boundY, boundZ), option[0]);
            } else {
                colRange = window(option[1], lineNumber, bound(colAxis, boundX, boundY, boundZ), option[0]);
            }
        }
        if (planes == null) {
            String expected = planeAxis == Axis.Y ? "y" : planeAxis == Axis.Z ? "z" : "x";
            throw error(lineNumber, (view == View.PLAN ? "plan" : "face") + " needs its plane, e.g. '"
                    + expected + "=3'");
        }
        if (rowRange == null) rowRange = new int[]{0, bound(rowAxis, boundX, boundY, boundZ) - 1};
        if (colRange == null) colRange = new int[]{0, bound(colAxis, boundX, boundY, boundZ) - 1};

        boolean rowDescending = view != View.PLAN;
        boolean colDescending = view == View.NORTH || view == View.EAST;
        return new Slice(view, planes, rowAxis, rowDescending, rowRange[0], rowRange[1],
                colAxis, colDescending, colRange[0], colRange[1]);
    }

    private static void readSlice(Ctx ctx, Slice slice, int headerLine, Map<Cell, Character> target,
                                  int baseX, int baseY, int baseZ) {
        List<String> rows = new ArrayList<>();
        List<Integer> rowLines = new ArrayList<>();
        while (rows.size() < slice.rows()) {
            if (!ctx.hasMore()) {
                throw error(headerLine, "Slice needs " + slice.rows() + " rows but the file ended after "
                        + rows.size() + ".");
            }
            int rowLine = ctx.lineNumber();
            String raw = ctx.nextRow();
            if (raw == null) continue;
            int index = rows.size();
            int rowCoord = slice.rowDescending() ? slice.rowTo() - index : slice.rowFrom() + index;
            if (startsNewSection(raw)) {
                throw error(rowLine, "The slice starting on line " + headerLine + " needs " + slice.rows()
                        + " rows but only " + rows.size() + " were drawn before this line began a new section.");
            }
            String body = stripRowLabel(raw, slice.rowAxis(), rowCoord, rowLine);
            if (body.indexOf(' ') >= 0) {
                body = body.replace(" ", "");
                ctx.note("Line " + rowLine + ": spaces in a grid row were ignored as visual separators; use '.' for air.");
            }
            if (body.length() < slice.width()) {
                int missing = slice.width() - body.length();
                body = body + ".".repeat(missing);
                ctx.note("Line " + rowLine + ": row was " + missing + " character(s) short; missing trailing cells were read as air.");
            } else if (body.length() > slice.width()) {
                String excess = body.substring(slice.width());
                if (excess.chars().allMatch(character -> character == '.')) {
                    body = body.substring(0, slice.width());
                    ctx.note("Line " + rowLine + ": ignored " + excess.length() + " extra trailing air character(s).");
                } else {
                    throw error(rowLine, "Slice row must be at most " + slice.width()
                            + " characters wide; only extra trailing '.' air may be omitted automatically. Row is "
                            + body.length() + " characters: " + body);
                }
            }
            for (int i = 0; i < body.length(); i++) {
                char symbol = body.charAt(i);
                if (symbol != '.' && !ctx.palette.containsKey(symbol)) {
                    throw error(rowLine, "Symbol '" + symbol + "' is not in the palette. Row: " + body);
                }
            }
            rows.add(body);
            rowLines.add(rowLine);
        }

        for (int plane : slice.planes()) {
            for (int r = 0; r < rows.size(); r++) {
                String row = rows.get(r);
                int rowCoord = slice.rowDescending() ? slice.rowTo() - r : slice.rowFrom() + r;
                for (int c = 0; c < row.length(); c++) {
                    int colCoord = slice.colDescending() ? slice.colTo() - c : slice.colFrom() + c;
                    int x = axisValue(Axis.X, slice, plane, rowCoord, colCoord);
                    int y = axisValue(Axis.Y, slice, plane, rowCoord, colCoord);
                    int z = axisValue(Axis.Z, slice, plane, rowCoord, colCoord);
                    write(ctx, target, new Cell(baseX + x, baseY + y, baseZ + z), row.charAt(c), rowLines.get(r),
                            ctx.sizeX, ctx.sizeY, ctx.sizeZ, describe(slice, plane));
                }
            }
        }
    }

    /**
     * A missing row otherwise swallows the next header and reports itself as a
     * palette error twenty lines later, so a line that unmistakably opens a new
     * section is treated as the end of the slice instead.
     */
    private static boolean startsNewSection(String row) {
        String lower = row.trim().toLowerCase(Locale.ROOT);
        for (String keyword : new String[]{"plan ", "face ", "part ", "at ", "size ", "name ", "ground ", "terrain "}) {
            if (lower.startsWith(keyword)) return true;
        }
        return lower.equals("pal") || lower.equals("palette");
    }

    private static int axisValue(Axis axis, Slice slice, int plane, int rowCoord, int colCoord) {
        Axis planeAxis = switch (slice.view()) {
            case PLAN -> Axis.Y;
            case SOUTH, NORTH -> Axis.Z;
            case EAST, WEST -> Axis.X;
        };
        if (axis == planeAxis) return plane;
        if (axis == slice.rowAxis()) return rowCoord;
        return colCoord;
    }

    private static String describe(Slice slice, int plane) {
        String axis = switch (slice.view()) {
            case PLAN -> "y";
            case SOUTH, NORTH -> "z";
            case EAST, WEST -> "x";
        };
        String name = slice.view() == View.PLAN ? "plan" : "face " + slice.view().name().toLowerCase(Locale.ROOT);
        return name + " " + axis + "=" + plane;
    }

    /** Strips an optional {@code z3}/{@code y5} row label and verifies it names the expected row. */
    private static String stripRowLabel(String row, Axis rowAxis, int expected, int lineNumber) {
        int space = row.indexOf(' ');
        if (space <= 0) return row;
        String label = row.substring(0, space);
        char axisChar = Character.toLowerCase(label.charAt(0));
        if (axisChar != 'x' && axisChar != 'y' && axisChar != 'z') return row;
        int value;
        try {
            value = Integer.parseInt(label.substring(1));
        } catch (NumberFormatException e) {
            return row;
        }
        char expectedAxis = rowAxis == Axis.Y ? 'y' : rowAxis == Axis.Z ? 'z' : 'x';
        if (axisChar != expectedAxis) {
            throw error(lineNumber, "Row label '" + label + "' should name " + expectedAxis + ", not " + axisChar + ".");
        }
        if (value != expected) {
            throw error(lineNumber, "Row label '" + label + "' does not match this row's coordinate "
                    + expectedAxis + "=" + expected + ". Rows in a plan run north to south; rows in a face run top to bottom.");
        }
        return row.substring(space + 1).trim();
    }

    private static void write(Ctx ctx, Map<Cell, Character> target, Cell cell, char symbol, int lineNumber,
                              int sizeX, int sizeY, int sizeZ, String origin) {
        if (target == ctx.grid && (cell.x() < 0 || cell.y() < 0 || cell.z() < 0
                || cell.x() >= sizeX || cell.y() >= sizeY || cell.z() >= sizeZ)) {
            throw error(lineNumber, origin + " writes (" + cell.x() + "," + cell.y() + "," + cell.z()
                    + ") which is outside size " + sizeX + " " + sizeY + " " + sizeZ + ".");
        }
        // Air is only data when the author explicitly asked to preserve terrain,
        // in which case it means excavation. In replace mode the site is cleared
        // anyway, so retaining every drawn dot wastes enormous amounts of memory
        // on large repeated slices and makes harmless overlap needlessly strict.
        // Air in parts is always transparent when stamped.
        if (symbol == '.' && (target != ctx.grid || !ctx.preserveTerrain)) return;
        Character previous = target.put(cell, symbol);
        if (previous != null && previous != symbol) {
            throw error(lineNumber, "Views disagree at (" + cell.x() + "," + cell.y() + "," + cell.z()
                    + "): an earlier slice put '" + previous + "' there and " + origin + " puts '" + symbol
                    + "'. Every slice is authoritative, so overlapping views must draw the same block.");
        }
        if (target == ctx.grid) ctx.sourceLines.putIfAbsent(cell, lineNumber);
    }

    /**
     * A part is a composition operation rather than a second authoritative view:
     * its non-air cells stamp over the main drawing and earlier placements. This
     * is what makes a hoop base on a pitch or furniture on a floor expressible.
     */
    private static void stampPart(Ctx ctx, Cell cell, char symbol, int lineNumber, String name) {
        if (cell.x() < 0 || cell.y() < 0 || cell.z() < 0
                || cell.x() >= ctx.sizeX || cell.y() >= ctx.sizeY || cell.z() >= ctx.sizeZ) {
            throw error(lineNumber, "part " + name + " writes (" + cell.x() + "," + cell.y() + "," + cell.z()
                    + ") which is outside size " + ctx.sizeX + " " + ctx.sizeY + " " + ctx.sizeZ + ".");
        }
        ctx.grid.put(cell, symbol);
        ctx.sourceLines.put(cell, lineNumber);
    }

    // --- small helpers -------------------------------------------------------

    private static List<Integer> planeSpec(String value, int lineNumber, int bound, String axis) {
        List<Integer> planes = new ArrayList<>();
        for (String piece : value.split(",")) {
            int[] range = window(piece, lineNumber, bound, axis);
            for (int i = range[0]; i <= range[1]; i++) planes.add(i);
        }
        if (planes.isEmpty()) throw error(lineNumber, "Empty plane list for " + axis);
        return planes;
    }

    private static int[] window(String value, int lineNumber, int bound, String axis) {
        String cleaned = value.trim().replace("...", "..").replace("-", "..");
        int dots = cleaned.indexOf("..");
        int from;
        int to;
        if (dots < 0) {
            from = number(cleaned, lineNumber, axis);
            to = from;
        } else {
            from = number(cleaned.substring(0, dots), lineNumber, axis);
            to = number(cleaned.substring(dots + 2), lineNumber, axis);
        }
        if (from > to) {
            int swap = from;
            from = to;
            to = swap;
        }
        if (from < 0 || to >= bound) {
            throw error(lineNumber, axis + "=" + value + " falls outside 0.." + (bound - 1) + ".");
        }
        return new int[]{from, to};
    }

    private static int bound(Axis axis, int boundX, int boundY, int boundZ) {
        int value = switch (axis) {
            case X -> boundX;
            case Y -> boundY;
            case Z -> boundZ;
        };
        if (value < 1) throw new IllegalArgumentException("'size X Y Z' must appear before the first slice.");
        return value;
    }

    private static boolean flag(String value, int lineNumber, String label) {
        return switch (value) {
            case "true", "yes", "1" -> true;
            case "false", "no", "0" -> false;
            default -> throw error(lineNumber, label + " must be true or false");
        };
    }

    private static int number(String value, int lineNumber, String label) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw error(lineNumber, "Expected a whole number for " + label + ", got '" + value + "'");
        }
    }

    private static int positive(String value, int lineNumber, String label) {
        int parsed = number(value, lineNumber, label);
        if (parsed < 1) throw error(lineNumber, label + " must be at least 1");
        return parsed;
    }

    static String normalize(String blockId) {
        String value = blockId.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        return value.contains(":") ? value : "minecraft:" + value;
    }

    private static IllegalArgumentException error(int lineNumber, String message) {
        return new IllegalArgumentException("Line " + lineNumber + ": " + message);
    }

    private static String[] splitLines(String source) {
        String cleaned = source.replace('–', '-').replace('—', '-').replace('−', '-').trim();
        if (cleaned.startsWith("```")) {
            int newline = cleaned.indexOf('\n');
            cleaned = newline < 0 ? "" : cleaned.substring(newline + 1);
        }
        if (cleaned.endsWith("```")) {
            int lastNewline = cleaned.lastIndexOf('\n');
            if (lastNewline >= 0) cleaned = cleaned.substring(0, lastNewline);
        }
        return cleaned.split("\\r?\\n", -1);
    }

    private record Part(String name, int sx, int sy, int sz, Map<Cell, Character> cells) {}

    private record Placement(String line, int lineNumber) {}

    /** Cursor over the source plus the accumulating structure state. */
    private static final class Ctx {
        private final String[] lines;
        private int cursor;
        private String name = "structure";
        private int sizeX = -1;
        private int sizeY = -1;
        private int sizeZ = -1;
        private boolean ground = true;
        private boolean preserveTerrain;
        private final Map<Character, String> palette = new LinkedHashMap<>();
        private final Map<Character, Map<String, String>> hints = new LinkedHashMap<>();
        private final Map<Cell, Character> grid = new HashMap<>();
        private final Map<Cell, Integer> sourceLines = new HashMap<>();
        private final Map<String, Part> parts = new LinkedHashMap<>();
        private final List<Placement> placements = new ArrayList<>();
        private final List<String> notes = new ArrayList<>();

        Ctx(String[] lines) {
            this.lines = lines;
        }

        void skipToHeader() {
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.equalsIgnoreCase("VXB-2") || line.equalsIgnoreCase("VXB2")) {
                    cursor = i + 1;
                    return;
                }
                if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("//")) break;
            }
            note("No 'VXB-2' header line was found; parsed the body anyway.");
        }

        boolean hasMore() {
            return cursor < lines.length;
        }

        int lineNumber() {
            return cursor + 1;
        }

        /** Next meaningful command line with any trailing comment removed, or null for blanks. */
        String next() {
            String raw = lines[cursor++].trim();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) return null;
            int comment = raw.indexOf(" #");
            int slashComment = raw.indexOf(" //");
            if (comment < 0 || slashComment >= 0 && slashComment < comment) comment = slashComment;
            return comment > 0 ? raw.substring(0, comment).trim() : raw;
        }

        /** Palette entries may legitimately use '#' as their one-character glyph. */
        String nextPalette() {
            String raw = lines[cursor++].trim();
            if (raw.isEmpty() || raw.startsWith("//")) return null;
            int hashComment = raw.indexOf(" #");
            int slashComment = raw.indexOf(" //");
            int comment = hashComment < 0 ? slashComment
                    : slashComment < 0 ? hashComment : Math.min(hashComment, slashComment);
            return comment > 0 ? raw.substring(0, comment).trim() : raw;
        }

        /** Grid rows preserve '#', which is a valid glyph; use '//' for row comments. */
        String nextRow() {
            String raw = lines[cursor++];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return null;
            int comment = trimmed.indexOf(" //");
            return comment > 0 ? trimmed.substring(0, comment).trim() : trimmed;
        }

        void note(String message) {
            if (!notes.contains(message)) notes.add(message);
        }
    }
}
