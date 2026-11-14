package dev.hmp.farlands;

import java.util.Random;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Old-style terrain noise, written so that it breaks down at 12,550,820 blocks exactly like the
 * original Far Lands did.
 *
 * Classic terrain was built from "Perlin" noise sampled every 4 blocks (x, z) and 8 blocks (y), with
 * the first, finest octave using a coordinate scale of 684.412. In that octave the sample position
 * reaches 2^31 at x = 12,550,820. Past that, the integer part of the position is stuck at the
 * largest integer, while the fractional part keeps growing without limit. The smoothing polynomial
 * then makes the noise astronomically large, so the terrain no longer rolls gently: at every
 * height it is either solid or air, and the pattern only changes in the other direction, which
 * stretches the terrain into tall curtains and walls. This class reproduces that behaviour (the
 * integer cast below saturates in Java just like the old code did).
 *
 * The noise covers the whole height, y = -64 to y = 256, so the underside is shaped like the top.
 *
 * Output per chunk: a block kind for every block from y = -64 to y = 256:
 * 0 air, 1 stone, 2 granite, 3 diorite, 4 andesite, 5 gravel, 6 tuff, 7 grass block, 8 dirt.
 * Every exposed top surface gets a grass block with three dirt blocks under it, like the vanilla
 * surface, which gives the ledges of the walls their grass and dirt stripes.
 * Granite, diorite, andesite, gravel and tuff are natural-looking blobs, placed the way vanilla
 * places its ore-like veins, and they only replace stone.
 */
final class FarlandsTerrain {
    static final int BOTTOM = -64;
    static final int TOP = 256;
    static final int HEIGHT = TOP - BOTTOM + 1;

    private static final BlockState[] PALETTE = {
            Blocks.AIR.defaultBlockState(),
            Blocks.STONE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.GRAVEL.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.GRASS_BLOCK.defaultBlockState(),
            Blocks.DIRT.defaultBlockState()
    };

    static BlockState state(byte kind) {
        return PALETTE[kind];
    }

    static int index(int lx, int lz, int y) {
        return (lx * 16 + lz) * HEIGHT + (y - BOTTOM);
    }

    // ---- noise -----------------------------------------------------------------------------

    private final long seed;
    private final Perlin[] lower = new Perlin[16];
    private final Perlin[] upper = new Perlin[16];
    private final Perlin[] main = new Perlin[8];

    FarlandsTerrain(long seed) {
        this.seed = seed;
        Random random = new Random(seed);
        for (int i = 0; i < lower.length; i++) lower[i] = new Perlin(random);
        for (int i = 0; i < upper.length; i++) upper[i] = new Perlin(random);
        for (int i = 0; i < main.length; i++) main[i] = new Perlin(random);
    }

    /** Sum of octaves: each octave has half the frequency and twice the weight of the one before. */
    private static double octaves(Perlin[] noises, double x, double y, double z, double sx, double sy, double sz) {
        double total = 0.0D;
        double d = 1.0D;
        for (Perlin noise : noises) {
            total += noise.sample(x * sx * d, y * sy * d, z * sz * d) / d;
            d /= 2.0D;
        }
        return total;
    }

    /** Terrain density at a noise grid point: above 0 is solid. gx and gz are in 4 block steps, j in 8 block steps. */
    private double density(long gx, int j, long gz) {
        double lo = octaves(lower, gx, j, gz, 684.412D, 684.412D, 684.412D) / 512.0D;
        double hi = octaves(upper, gx, j, gz, 684.412D, 684.412D, 684.412D) / 512.0D;
        double mixer = (octaves(main, gx, j, gz, 8.55515D, 4.277575D, 8.55515D) / 10.0D + 1.0D) / 2.0D;

        double value;
        if (mixer < 0.0D) value = lo;
        else if (mixer > 1.0D) value = hi;
        else value = lo + (hi - lo) * mixer;

        // Pull the ground towards a base height of about y=68, steeper below than above.
        double offset = (j - 8.5D) * 6.0D;
        if (offset < 0.0D) offset *= 4.0D;
        value -= offset;

        // Smooth the top so the terrain ends at the build limit.
        if (j > 29) {
            double t = (j - 29) / 3.0D;
            value = value * (1.0D - t) + -10.0D * t;
        }
        return value;
    }

    // ---- one chunk -------------------------------------------------------------------------

    /** Block kinds for chunk (chunkX, chunkZ), indexed with {@link #index}. */
    byte[] generate(int chunkX, int chunkZ) {
        byte[] kinds = new byte[256 * HEIGHT];

        // Grid points every 4 blocks in x and z and every 8 blocks in y. Index j + 8 holds the
        // point at y = j * 8, so the grid runs from y = -64 (j = -8) to y = 256 (j = 32).
        double[][][] d = new double[5][41][5];
        for (int i = 0; i < 5; i++) {
            for (int k = 0; k < 5; k++) {
                for (int j = -8; j <= 32; j++) {
                    d[i][j + 8][k] = density((long) chunkX * 4 + i, j, (long) chunkZ * 4 + k);
                }
            }
        }

        for (int lx = 0; lx < 16; lx++) {
            int i = lx >> 2;
            double fx = (lx & 3) / 4.0D;
            for (int lz = 0; lz < 16; lz++) {
                int k = lz >> 2;
                double fz = (lz & 3) / 4.0D;
                for (int y = BOTTOM; y < TOP; y++) {
                    int j = Math.floorDiv(y, 8) + 8;
                    double fy = Math.floorMod(y, 8) / 8.0D;
                    double a = lerp(fz, d[i][j][k], d[i][j][k + 1]);
                    double b = lerp(fz, d[i + 1][j][k], d[i + 1][j][k + 1]);
                    double c = lerp(fz, d[i][j + 1][k], d[i][j + 1][k + 1]);
                    double e = lerp(fz, d[i + 1][j + 1][k], d[i + 1][j + 1][k + 1]);
                    double v = lerp(fy, lerp(fx, a, b), lerp(fx, c, e));
                    if (v > 0.0D) kinds[index(lx, lz, y)] = 1; // NaN is false: air
                }
            }
        }

        placeBlobs(kinds, chunkX, chunkZ);
        addSurface(kinds);
        return kinds;
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** Grass block on every exposed top, with three dirt blocks beneath it. */
    private static void addSurface(byte[] kinds) {
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int depth = 0; // 0: air directly above, 1..3: dirt layers, 4: below the surface layer
                for (int y = TOP - 1; y >= BOTTOM; y--) {
                    int idx = index(lx, lz, y);
                    if (kinds[idx] == 0) {
                        depth = 0;
                    } else if (depth == 0) {
                        kinds[idx] = 7;
                        depth = 1;
                    } else if (depth <= 3) {
                        kinds[idx] = 8;
                        depth++;
                    }
                }
            }
        }
    }

    // ---- natural blobs of granite, diorite, andesite, gravel and tuff -----------------------

    // kind, blobs per chunk, blob size, lowest y, highest y
    private static final int[][] BLOBS = {
            {2, 3, 64, BOTTOM, TOP}, // granite
            {3, 3, 64, BOTTOM, TOP}, // diorite
            {4, 3, 64, BOTTOM, TOP}, // andesite
            {5, 14, 33, BOTTOM, TOP}, // gravel
            {6, 2, 64, BOTTOM, 64},  // tuff
    };

    private void placeBlobs(byte[] kinds, int chunkX, int chunkZ) {
        // A blob can reach into the next chunk, so the blobs of all 9 surrounding chunks are
        // generated and only the parts that fall inside this chunk are kept.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int ncx = chunkX + dx;
                int ncz = chunkZ + dz;
                for (int[] blob : BLOBS) {
                    Random random = new Random(seed ^ (ncx * 341873128712L) ^ (ncz * 132897987541L) ^ (blob[0] * 0x9E3779B97F4A7C15L));
                    for (int n = 0; n < blob[1]; n++) {
                        int x = ncx * 16 + random.nextInt(16);
                        int z = ncz * 16 + random.nextInt(16);
                        int y = blob[3] + random.nextInt(blob[4] - blob[3] + 1);
                        placeBlob(kinds, chunkX, chunkZ, random, x, y, z, blob[2], (byte) blob[0]);
                    }
                }
            }
        }
    }

    /** A chain of overlapping ellipsoids along a short line, the same shape vanilla uses for ore veins. */
    private static void placeBlob(byte[] kinds, int chunkX, int chunkZ, Random random,
                                  int ox, int oy, int oz, int size, byte kind) {
        float angle = random.nextFloat() * (float) Math.PI;
        float spread = size / 8.0F;
        double x0 = ox + Math.sin(angle) * spread;
        double x1 = ox - Math.sin(angle) * spread;
        double z0 = oz + Math.cos(angle) * spread;
        double z1 = oz - Math.cos(angle) * spread;
        double y0 = oy + random.nextInt(3) - 2;
        double y1 = oy + random.nextInt(3) - 2;

        int minX = chunkX * 16;
        int minZ = chunkZ * 16;

        for (int n = 0; n < size; n++) {
            float t = (float) n / (float) size;
            double cx = lerp(t, x0, x1);
            double cy = lerp(t, y0, y1);
            double cz = lerp(t, z0, z1);
            double s = random.nextDouble() * size / 16.0D;
            double radius = ((Math.sin((float) Math.PI * t) + 1.0F) * s + 1.0D) / 2.0D;

            int fromX = (int) Math.floor(cx - radius);
            int toX = (int) Math.floor(cx + radius);
            int fromY = (int) Math.floor(cy - radius);
            int toY = (int) Math.floor(cy + radius);
            int fromZ = (int) Math.floor(cz - radius);
            int toZ = (int) Math.floor(cz + radius);

            for (int x = Math.max(fromX, minX); x <= Math.min(toX, minX + 15); x++) {
                double ddx = (x + 0.5D - cx) / radius;
                if (ddx * ddx >= 1.0D) continue;
                for (int y = Math.max(fromY, BOTTOM); y <= Math.min(toY, TOP); y++) {
                    double ddy = (y + 0.5D - cy) / radius;
                    if (ddx * ddx + ddy * ddy >= 1.0D) continue;
                    for (int z = Math.max(fromZ, minZ); z <= Math.min(toZ, minZ + 15); z++) {
                        double ddz = (z + 0.5D - cz) / radius;
                        if (ddx * ddx + ddy * ddy + ddz * ddz >= 1.0D) continue;
                        int idx = index(x - minX, z - minZ, y);
                        if (kinds[idx] == 1) kinds[idx] = kind; // only replaces plain stone
                    }
                }
            }
        }
    }

    // ---- classic 3D Perlin noise, including its overflow behaviour --------------------------

    private static final class Perlin {
        private final double xo;
        private final double yo;
        private final double zo;
        private final int[] p = new int[512];

        Perlin(Random random) {
            xo = random.nextDouble() * 256.0D;
            yo = random.nextDouble() * 256.0D;
            zo = random.nextDouble() * 256.0D;
            for (int i = 0; i < 256; i++) p[i] = i;
            for (int i = 0; i < 256; i++) {
                int j = random.nextInt(256 - i) + i;
                int tmp = p[i];
                p[i] = p[j];
                p[j] = tmp;
                p[i + 256] = p[i];
            }
        }

        double sample(double x, double y, double z) {
            x += xo;
            y += yo;
            z += zo;
            // The cast to int saturates for huge values. That is what causes the Far Lands:
            // the whole part gets stuck while the remainder below keeps growing.
            int xi = (int) x;
            if (x < xi) xi--;
            int yi = (int) y;
            if (y < yi) yi--;
            int zi = (int) z;
            if (z < zi) zi--;
            int ix = xi & 255;
            int iy = yi & 255;
            int iz = zi & 255;
            x -= xi;
            y -= yi;
            z -= zi;
            double u = fade(x);
            double v = fade(y);
            double w = fade(z);
            int a = p[ix] + iy;
            int aa = p[a] + iz;
            int ab = p[a + 1] + iz;
            int b = p[ix + 1] + iy;
            int ba = p[b] + iz;
            int bb = p[b + 1] + iz;
            return lerp(w,
                    lerp(v,
                            lerp(u, grad(p[aa], x, y, z), grad(p[ba], x - 1, y, z)),
                            lerp(u, grad(p[ab], x, y - 1, z), grad(p[bb], x - 1, y - 1, z))),
                    lerp(v,
                            lerp(u, grad(p[aa + 1], x, y, z - 1), grad(p[ba + 1], x - 1, y, z - 1)),
                            lerp(u, grad(p[ab + 1], x, y - 1, z - 1), grad(p[bb + 1], x - 1, y - 1, z - 1))));
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6.0D - 15.0D) + 10.0D);
        }

        private static double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }
    }
}
