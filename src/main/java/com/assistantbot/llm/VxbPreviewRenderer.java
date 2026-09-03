package com.assistantbot.llm;

import com.assistantbot.llm.BuildStructure.BlockEntry;
import com.assistantbot.llm.BuildStructure.Cell;
import java.util.HashMap;
import java.util.Map;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import javax.imageio.ImageIO;

/**
 * Renders a compiled structure back into VXB-2 slices.
 *
 * <p>The echo is deliberately written in the same grammar and the same palette
 * symbols the author used, so comparing intent against result is a character
 * diff rather than an act of imagination. Everything the compiler decided —
 * inferred stairs, relocated torches, doors that grew a second half — shows up
 * here as a changed glyph.
 */
public final class VxbPreviewRenderer {
    private static final int MAX_ECHO_CELLS = 16000;

    private VxbPreviewRenderer() {}

    public static String render(BuildStructure structure) {
        Map<Cell, BlockEntry> grid = new HashMap<>();
        for (BlockEntry block : structure.getBlocks()) grid.put(new Cell(block.x(), block.y(), block.z()), block);
        int sx = extent(structure.getSizeX(), structure, 'x');
        int sy = extent(structure.getSizeY(), structure, 'y');
        int sz = extent(structure.getSizeZ(), structure, 'z');

        StringBuilder out = new StringBuilder();
        out.append("Compiled result, drawn back in VXB-2. Compare it against your source.\n");
        out.append("size ").append(sx).append(' ').append(sy).append(' ').append(sz).append('\n');

        if ((long) sx * sy * sz <= MAX_ECHO_CELLS) {
            for (int y = 0; y < sy; y++) {
                if (emptyLevel(grid, sx, sz, y)) continue;
                out.append("\nplan y=").append(y).append('\n');
                for (int z = 0; z < sz; z++) {
                    out.append('z').append(z).append(' ');
                    for (int x = 0; x < sx; x++) out.append(glyph(structure, grid.get(new Cell(x, y, z))));
                    out.append('\n');
                }
            }
        } else {
            out.append("(too large to echo every level; silhouettes only)\n");
        }

        out.append("\nSilhouette, face south (what you see standing to the south)\n");
        for (int y = sy - 1; y >= 0; y--) {
            out.append('y').append(y).append(' ');
            for (int x = 0; x < sx; x++) {
                BlockEntry visible = null;
                for (int z = sz - 1; z >= 0 && visible == null; z--) visible = grid.get(new Cell(x, y, z));
                out.append(glyph(structure, visible));
            }
            out.append('\n');
        }
        out.append("\nSilhouette, face east (what you see standing to the east)\n");
        for (int y = sy - 1; y >= 0; y--) {
            out.append('y').append(y).append(' ');
            for (int z = sz - 1; z >= 0; z--) {
                BlockEntry visible = null;
                for (int x = sx - 1; x >= 0 && visible == null; x--) visible = grid.get(new Cell(x, y, z));
                out.append(glyph(structure, visible));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static boolean emptyLevel(Map<Cell, BlockEntry> grid, int sx, int sz, int y) {
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) if (grid.containsKey(new Cell(x, y, z))) return false;
        }
        return true;
    }

    private static char glyph(BuildStructure structure, BlockEntry block) {
        if (block == null) return '.';
        Character symbol = structure.getGlyphs().get(BlockIdResolver.normalizeBaseId(block.blockId()));
        return symbol != null ? symbol : categoryGlyph(block.blockId());
    }

    /** Fallback for blocks the compiler introduced, such as a torch turned into a wall torch. */
    private static char categoryGlyph(String id) {
        if (id.contains("door")) return 'D';
        if (id.contains("stairs")) return '^';
        if (id.contains("slab")) return '=';
        if (id.contains("glass")) return 'g';
        if (id.contains("log")) return 'L';
        if (id.contains("torch") || id.contains("lantern")) return '*';
        if (id.contains("bed")) return 'b';
        if (id.contains("planks")) return 'P';
        if (id.contains("stone") || id.contains("brick") || id.contains("cobble")) return 'S';
        return '#';
    }

    private static int extent(int declared, BuildStructure structure, char axis) {
        if (declared > 0) return declared;
        int result = 0;
        for (BlockEntry b : structure.getBlocks()) {
            result = Math.max(result, axis == 'x' ? b.x() : axis == 'y' ? b.y() : b.z());
        }
        return result + 1;
    }

    /** Render the same projections as a compact PNG suitable for a vision-model input. */
    public static String renderPngDataUrl(BuildStructure structure) {
        Map<Cell, BlockEntry> grid = new HashMap<>();
        for (BlockEntry block : structure.getBlocks()) grid.put(new Cell(block.x(), block.y(), block.z()), block);
        int sx = extent(structure.getSizeX(), structure, 'x');
        int sy = extent(structure.getSizeY(), structure, 'y');
        int sz = extent(structure.getSizeZ(), structure, 'z');
        if (sx > 40 || sy > 40 || sz > 40) throw new IllegalArgumentException("Visual preview is limited to 40 blocks per axis");
        int cell = 12, label = 22, gap = 12;
        int width = sx * cell + gap + sx * cell + gap + sz * cell;
        int height = label + Math.max(sz, sy) * cell;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(24, 27, 32));
        graphics.fillRect(0, 0, width, height);
        graphics.setColor(Color.WHITE);
        graphics.drawString("TOP", 2, 15);
        int frontX = sx * cell + gap;
        int sideX = frontX + sx * cell + gap;
        graphics.drawString("SOUTH", frontX + 2, 15);
        graphics.drawString("EAST", sideX + 2, 15);

        for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) {
            BlockEntry visible = null;
            for (int y = sy - 1; y >= 0 && visible == null; y--) visible = grid.get(new Cell(x, y, z));
            drawCell(graphics, x * cell, label + z * cell, cell, visible);
        }
        for (int y = 0; y < sy; y++) for (int x = 0; x < sx; x++) {
            BlockEntry visible = null;
            for (int z = sz - 1; z >= 0 && visible == null; z--) visible = grid.get(new Cell(x, y, z));
            drawCell(graphics, frontX + x * cell, label + (sy - 1 - y) * cell, cell, visible);
        }
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) {
            BlockEntry visible = null;
            for (int x = sx - 1; x >= 0 && visible == null; x--) visible = grid.get(new Cell(x, y, z));
            drawCell(graphics, sideX + z * cell, label + (sy - 1 - y) * cell, cell, visible);
        }
        graphics.dispose();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "png", bytes);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode VXB preview", e);
        }
    }

    private static void drawCell(Graphics2D graphics, int x, int y, int size, BlockEntry block) {
        graphics.setColor(color(block));
        graphics.fillRect(x, y, size, size);
        graphics.setColor(new Color(45, 48, 54));
        graphics.drawRect(x, y, size, size);
    }

    private static Color color(BlockEntry block) {
        if (block == null) return new Color(24, 27, 32);
        String id = block.blockId();
        if (id.contains("door")) return new Color(177, 123, 69);
        if (id.contains("glass")) return new Color(111, 193, 214);
        if (id.contains("log")) return new Color(105, 75, 45);
        if (id.contains("planks") || id.contains("stairs")) return new Color(174, 132, 77);
        if (id.contains("stone") || id.contains("brick") || id.contains("cobble")) return new Color(125, 127, 130);
        if (id.contains("torch") || id.contains("lantern")) return new Color(255, 199, 64);
        if (id.contains("bed")) return new Color(190, 55, 55);
        if (id.contains("plant") || id.contains("flower") || id.contains("leaves")) return new Color(68, 145, 65);
        return new Color(151, 151, 151);
    }
}
