package dev.hmp.farlands;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Farlands
 *
 * Inside 12,550,820 blocks (on both x and z) the overworld is completely vanilla. Beyond that line,
 * on all four sides, newly generated chunks are rewritten column by column. The distance d is
 * counted outward from that line (square rings, so the corners match the sides):
 *
 *   d      0 - 12,400   plain Farlands: solid stone, y -64 to 256, nothing else
 *   d 12,400 - 12,600   void gap (200 blocks)
 *   d 12,600 - 17,600   Farlands with vanilla structures embedded in the stone (5,000 blocks)
 *   d 17,600 - 20,100   chessboard: every other chunk is stone, the rest void (2,500 blocks)
 *   d 20,100 - 20,600   corruption: more and more blocks are missing (500 blocks)
 *   d        > 20,600   void
 *
 * The "Farlands" terrain is the old terrain noise breaking down past 12,550,820 blocks (see
 * FarlandsTerrain): tall stretched walls and curtains of stone from y=-64 up to about y=255.
 * It is stone with natural blobs of granite, andesite, diorite, gravel and tuff.
 * No deepslate, no obsidian, no caves, no ores, no grass, no trees, no water.
 * Chunks in the Farlands that were generated before the mod was installed are rewritten when they load.
 */
public class FarlandsMod implements ModInitializer {
    // ---- tweak these -----------------------------------------------------------------------
    static final int EDGE = 12_550_820;     // where the Farlands start (x or z)
    static final int PLAIN = 12_400;        // plain stone Farlands
    static final int GAP = 200;             // void gap
    static final int STRUCTURES = 5_000;    // stone Farlands that keep vanilla structures
    static final int CHESS = 2_500;         // chessboard of chunks
    static final int CORRUPT = 500;         // corruption, then void
    static final int TOP = 256;             // highest solid block
    static final int BOTTOM = -64;          // lowest solid block
    // -----------------------------------------------------------------------------------------

    /** The first blocks of the Farlands are eroded, so the start is a ragged edge instead of a flat wall. */
    static final int RAGGED = 0;

    private static final int END_PLAIN = PLAIN;
    private static final int END_GAP = END_PLAIN + GAP;
    private static final int END_STRUCTURES = END_GAP + STRUCTURES;
    private static final int END_CHESS = END_STRUCTURES + CHESS;
    private static final int END_CORRUPT = END_CHESS + CORRUPT;

    private static FarlandsTerrain generator;
    private static long generatorSeed;

    private static byte[] terrainFor(ServerLevel level, int chunkX, int chunkZ) {
        long seed = level.getSeed();
        if (generator == null || generatorSeed != seed) {
            generator = new FarlandsTerrain(seed);
            generatorSeed = seed;
        }
        return generator.generate(chunkX, chunkZ);
    }

    private enum Mode { VANILLA, VOID, SLAB, SLAB_KEEP_STRUCTURES, CORRUPT }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    @Override
    public void onInitialize() {
        // The rewrite is deterministic, so it is safe to run on every chunk load: a chunk that was
        // already rewritten needs no changes and is skipped quickly.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            if (level.dimension() == Level.OVERWORLD) rewrite(level, chunk);
        });
    }

    // ---- zones -----------------------------------------------------------------------------

    private static Mode modeAt(int x, int z) {
        int d = Math.max(Math.abs(x), Math.abs(z)) - EDGE;
        if (d < 0) return Mode.VANILLA;
        if (d < END_PLAIN) return Mode.SLAB;
        if (d < END_GAP) return Mode.VOID;
        if (d < END_STRUCTURES) return Mode.SLAB_KEEP_STRUCTURES;
        if (d < END_CHESS) {
            // chessboard of single chunks: land on one colour, void on the other
            return (((x >> 4) + (z >> 4)) & 1) == 0 ? Mode.SLAB : Mode.VOID;
        }
        if (d < END_CORRUPT) return Mode.CORRUPT;
        return Mode.VOID;
    }

    /** 0 at the start of the corruption zone, rising to 1 at its end. */
    private static double corruption(int x, int z) {
        int d = Math.max(Math.abs(x), Math.abs(z)) - EDGE - END_CHESS;
        return Math.max(0.0D, Math.min(1.0D, d / (double) CORRUPT));
    }

    // ---- chunk rewrite ---------------------------------------------------------------------

    private static void rewrite(ServerLevel level, LevelChunk chunk) {
        ChunkPos cp = chunk.getPos();
        int minX = cp.getMinBlockX();
        int minZ = cp.getMinBlockZ();

        // Chunks completely inside the vanilla world are left alone.
        int far = Math.max(Math.max(Math.abs(minX), Math.abs(minX + 15)), Math.max(Math.abs(minZ), Math.abs(minZ + 15)));
        if (far < EDGE) return;

        // Boxes of structures that touch this chunk (only needed in the structure zone).
        List<BoundingBox> boxes = null;
        for (int lx = 0; lx < 16 && boxes == null; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (modeAt(minX + lx, minZ + lz) == Mode.SLAB_KEEP_STRUCTURES) {
                    boxes = structureBoxes(level, cp, minX, minZ);
                    break;
                }
            }
        }

        byte[] terrain = null;
        boolean changed = false;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = minX + lx;
                int z = minZ + lz;
                Mode mode = modeAt(x, z);
                if (mode == Mode.VANILLA) continue;
                double corrupt = mode == Mode.CORRUPT ? corruption(x, z) : 0.0D;
                if (mode != Mode.VOID && terrain == null) terrain = terrainFor(level, minX >> 4, minZ >> 4);

                for (int si = 0; si < chunk.getSectionsCount(); si++) {
                    LevelChunkSection section = chunk.getSection(si);
                    int baseY = chunk.getSectionYFromSectionIndex(si) << 4;
                    for (int ly = 0; ly < 16; ly++) {
                        int y = baseY + ly;
                        BlockState current = section.getBlockState(lx, ly, lz);
                        BlockState want = wanted(mode, corrupt, boxes, terrain, current, lx, lz, x, y, z);
                        if (want == null) continue; // keep what vanilla generated
                        if (section.getBlockState(lx, ly, lz) != want) {
                            section.setBlockState(lx, ly, lz, want, false);
                            changed = true;
                        }
                    }
                }
            }
        }

        if (!changed) return; // already rewritten earlier

        // Drop block entities (chests, spawners, ...) whose block no longer exists.
        for (BlockPos pos : new ArrayList<>(chunk.getBlockEntitiesPos())) {
            if (!chunk.getBlockState(pos).hasBlockEntity()) chunk.removeBlockEntity(pos);
        }

        Heightmap.primeHeightmaps(chunk, EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE));

        markUnsaved(chunk, minX, minZ);
    }

    /** Returns the block that should be at x,y,z, or null to keep the vanilla block. */
    private static BlockState wanted(Mode mode, double corrupt, List<BoundingBox> boxes, byte[] terrain,
                                     BlockState current, int lx, int lz, int x, int y, int z) {
        switch (mode) {
            case VOID:
                return AIR;
            case SLAB_KEEP_STRUCTURES:
                if (boxes != null) {
                    for (BoundingBox box : boxes) {
                        if (box.isInside(x, y, z)) {
                            // Inside a structure piece: keep the structure's own blocks and air, but replace
                            // the natural terrain vanilla put around it (stone, deepslate, bedrock, ...).
                            return isNaturalTerrain(current) ? terrainBlock(terrain, lx, lz, y) : null;
                        }
                    }
                }
                return terrainBlock(terrain, lx, lz, y);
            case CORRUPT:
                if (y >= BOTTOM && y <= TOP && cellRandom(x >> 1, y >> 1, z >> 1, 7919L) < corrupt) return AIR;
                return terrainBlock(terrain, lx, lz, y);
            case SLAB:
            default:
                if (isRagged(x, y, z)) return AIR;
                return terrainBlock(terrain, lx, lz, y);
        }
    }

    /** Erodes the first RAGGED blocks of the Farlands: strong at the very edge, fading to nothing. */
    private static boolean isRagged(int x, int y, int z) {
        int d = Math.max(Math.abs(x), Math.abs(z)) - EDGE;
        if (d >= RAGGED) return false;
        double chance = 0.6D * (1.0D - d / (double) RAGGED);
        return cellRandom(Math.floorDiv(x, 3), Math.floorDiv(y, 3), Math.floorDiv(z, 3), 31337L) < chance;
    }

    private static BlockState terrainBlock(byte[] terrain, int lx, int lz, int y) {
        if (terrain == null || y < FarlandsTerrain.BOTTOM || y > FarlandsTerrain.TOP) return AIR;
        return FarlandsTerrain.state(terrain[FarlandsTerrain.index(lx, lz, y)]);
    }

    /** Deterministic pseudo-random number in [0, 1) for a cell, so the same world always looks the same. */
    private static double cellRandom(long a, long b, long c, long salt) {
        long h = a * 341873128712L + b * 132897987541L + c * 42317861L + salt * 0x9E3779B97F4A7C15L;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h & 0xFFFFFFL) / (double) 0x1000000L;
    }

    /** Boxes of the individual pieces of every structure that touches this chunk. */
    private static List<BoundingBox> structureBoxes(ServerLevel level, ChunkPos cp, int minX, int minZ) {
        List<BoundingBox> boxes = new ArrayList<>();
        for (StructureStart start : level.structureManager().startsForStructure(cp, structure -> true)) {
            if (!start.isValid()) continue;
            for (StructurePiece piece : start.getPieces()) {
                BoundingBox box = piece.getBoundingBox();
                if (box.intersects(minX, minZ, minX + 15, minZ + 15)) boxes.add(box);
            }
        }
        return boxes;
    }

    /** Natural terrain that vanilla generated around a structure. It is replaced; structure blocks are not. */
    private static boolean isNaturalTerrain(BlockState state) {
        if (state.is(Blocks.TUFF)) return false; // trial chambers and ancient cities are built with it
        return state.is(BlockTags.BASE_STONE_OVERWORLD)   // stone, granite, diorite, andesite, deepslate
                || state.is(Blocks.BEDROCK)
                || state.is(BlockTags.DIRT)                // dirt, grass block, podzol, moss, mud, ...
                || state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
                || state.is(BlockTags.GOLD_ORES) || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES)
                || state.is(Blocks.WATER) || state.is(Blocks.LAVA)
                || state.is(BlockTags.ICE) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.SNOW)
                || state.is(BlockTags.LEAVES) || state.is(BlockTags.FLOWERS)
                || state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DRIPSTONE_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE)
                || state.is(Blocks.CALCITE) || state.is(Blocks.SMOOTH_BASALT);
    }

    /**
     * Make sure the chunk is saved with its new blocks: changing one block through the normal
     * method flags the chunk as unsaved. A high-up block that is air (or about to be) is used.
     */
    private static void markUnsaved(LevelChunk chunk, int minX, int minZ) {
        BlockPos pos = new BlockPos(minX, 318, minZ);
        BlockState now = chunk.getBlockState(pos);
        if (now.hasBlockEntity()) return;
        chunk.setBlockState(pos, Blocks.BARRIER.defaultBlockState(), 0);
        chunk.setBlockState(pos, now, 0);
    }
}
