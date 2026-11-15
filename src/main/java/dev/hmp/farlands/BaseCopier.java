package dev.hmp.farlands;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Base copier: the Farlands remember where you lived.
 *
 * TRACK    - once per second, every player in the overworld adds one point (four while sleeping) to
 *            the chunk they stand in. This is the player's heat map. It is saved in the world folder
 *            (world/farlands/heat.txt) and never shown to anyone.
 * DETECT   - once a minute, for every online player: the 7x7 chunk window with the most heat is
 *            their base, as soon as it holds at least `minSeconds` of their time and at least
 *            `minShare` of all their time. Windows near an already known base are ignored, so every
 *            region is copied once, no matter how many players use it.
 * PLACE    - the base is copied into the plain Farlands, before the void gap, on the side that is
 *            nearest to it and at the same "latitude" (same x or z) as the original, so somebody
 *            walking from the base straight to the Farlands can run into it. The position is fixed
 *            by a seeded random number, so it never changes.
 * COPY     - whole chunk columns (every block from the bedrock to the build limit, biomes, signs, chests and
 *            other block entities) are copied one chunk every few ticks so the
 *            server never lags. Containers arrive EMPTY (setting copyContainerItems=false is the default).
 *            Entities (mobs, item frames, armor stands) are not copied.
 * REFRESH  - every `refreshMinutes` game minutes the copy is updated with the current state of the
 *            base, but only if the base was not damaged: if more than `removedLimit` blocks that
 *            are in the copy are gone from the base, the base is assumed to be griefed or blown up
 *            and the old, intact copy is kept. As soon as somebody has spent time near the copy in the
 *            Farlands, it is locked and never refreshed, so their changes there stay.
 *
 * Settings are read from config/farlands.properties (created on the first start).
 */
final class BaseCopier {
    private static final Logger LOGGER = LoggerFactory.getLogger("Farlands");

    // ---- settings (config/farlands.properties) ----------------------------------------------
    private static int minSeconds = 3600;       // time a player must spend in one 7x7 chunk area
    private static double minShare = 0.25D;     // ... and that area must hold this share of all their time
    private static int copyRadius = 3;          // chunks around the centre that are copied: 3 -> 7x7 chunks
    private static int maxBasesPerPlayer = 3;   // how many bases of one player are copied
    private static int refreshMinutes = 60;     // game minutes between refreshes (1 minute = 1200 ticks)
    private static int removedLimit = 1500;     // more removed blocks than this: base looks griefed, keep old copy
    private static int minDistance = 1500;      // placement: blocks past the Farlands line, at least
    private static int maxDistance = FarlandsMod.PLAIN - 800; // ... and at most (the void gap starts at PLAIN)
    private static boolean sameLatitude = true; // keep the base's own x or z coordinate
    private static boolean copyContainerItems = false; // false: chests, barrels, dispensers ... arrive empty

    private static final int DETECT_INTERVAL = 1200; // ticks
    private static final int SAVE_INTERVAL = 6000;   // ticks
    private static final int JOB_INTERVAL = 4;       // ticks between two chunk jobs
    private static final BlockState MARKER = Blocks.STRUCTURE_VOID.defaultBlockState();

    // ---- state ------------------------------------------------------------------------------
    private static final class Base {
        final UUID owner;
        final int srcCx;
        final int srcCz;
        final int dstCx;
        final int dstCz;
        final int radius;
        long lastRefresh;
        boolean copied;
        boolean locked;

        Base(UUID owner, int srcCx, int srcCz, int dstCx, int dstCz, int radius) {
            this.owner = owner;
            this.srcCx = srcCx;
            this.srcCz = srcCz;
            this.dstCx = dstCx;
            this.dstCz = dstCz;
            this.radius = radius;
        }
    }

    /** One copy (or refresh) of one base, processed one chunk at a time. */
    private static final class Task {
        final Base base;
        int phase;      // 0: compare the base with the copy (refresh only), 1: copy
        int index;      // chunk number inside the (2r+1) x (2r+1) square
        long removed;   // blocks that are in the copy but gone from the base
        int blockEntities; // block entities (chests, signs, ...) copied by this task
        int stacksSeen;    // item stacks found in the containers of the base
        int stacksCopied;  // item stacks that ended up in the containers of the copy
        int emptied;       // containers (and other item holders) that were left empty on purpose

        Task(Base base, boolean compareFirst) {
            this.base = base;
            this.phase = compareFirst ? 0 : 1;
        }
    }

    private static final Map<UUID, Map<Long, Integer>> HEAT = new HashMap<>();
    private static final List<Base> BASES = new ArrayList<>();
    private static final Deque<Task> TASKS = new ArrayDeque<>();
    private static Path dir;
    private static boolean ready;
    private static boolean heatDirty;

    private BaseCopier() {}

    static void init() {
        loadConfig();
        ServerLifecycleEvents.SERVER_STARTED.register(BaseCopier::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (ready) saveAll();
            ready = false;
        });
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            if (ready && level.dimension() == Level.OVERWORLD) tick(level);
        });
    }

    // ---- tick -------------------------------------------------------------------------------

    private static void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (now % 20 == 0) trackPlayers(level);
        if (now % DETECT_INTERVAL == 0) {
            detectBases(level);
            scheduleRefreshes(now);
        }
        if (now % SAVE_INTERVAL == 0) saveAll();
        if (now % JOB_INTERVAL == 0) processTask(level, now);
    }

    // ---- heat map ---------------------------------------------------------------------------

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyZ(long key) {
        return (int) key;
    }

    private static void trackPlayers(ServerLevel level) {
        int limit = FarlandsMod.EDGE - 512;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            BlockPos pos = player.blockPosition();
            if (Math.abs(pos.getX()) >= limit || Math.abs(pos.getZ()) >= limit) continue; // only the normal world
            int weight = player.isSleeping() ? 4 : 1; // a bed is a strong sign of "home"
            HEAT.computeIfAbsent(player.getUUID(), id -> new HashMap<>())
                    .merge(key(pos.getX() >> 4, pos.getZ() >> 4), weight, Integer::sum);
            heatDirty = true;
        }
    }

    // ---- base detection ---------------------------------------------------------------------

    private static int countBases(UUID owner) {
        int n = 0;
        for (Base base : BASES) if (base.owner.equals(owner)) n++;
        return n;
    }

    /** True if this chunk is inside or right next to a region that is already copied. */
    private static boolean nearExistingBase(int cx, int cz) {
        for (Base base : BASES) {
            int gap = base.radius * 2 + 4;
            if (Math.max(Math.abs(cx - base.srcCx), Math.abs(cz - base.srcCz)) <= gap) return true;
        }
        return false;
    }

    private static void detectBases(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            Map<Long, Integer> heat = HEAT.get(id);
            if (heat == null || countBases(id) >= maxBasesPerPlayer) continue;

            long total = 0;
            for (int value : heat.values()) total += value;
            if (total < minSeconds) continue;

            long bestSum = 0;
            long bestKey = 0;
            boolean found = false;
            for (long k : heat.keySet()) {
                int cx = keyX(k);
                int cz = keyZ(k);
                if (nearExistingBase(cx, cz)) continue;
                long sum = 0;
                for (int dx = -copyRadius; dx <= copyRadius; dx++) {
                    for (int dz = -copyRadius; dz <= copyRadius; dz++) {
                        sum += heat.getOrDefault(key(cx + dx, cz + dz), 0);
                    }
                }
                if (sum > bestSum) {
                    bestSum = sum;
                    bestKey = k;
                    found = true;
                }
            }
            if (!found || bestSum < minSeconds || bestSum < total * minShare) continue;
            registerBase(level, id, keyX(bestKey), keyZ(bestKey));
        }
    }

    private static void registerBase(ServerLevel level, UUID owner, int cx, int cz) {
        int[] target = chooseTarget(level, owner, cx, cz);
        if (target == null) {
            LOGGER.warn("Farlands: no free spot found for the base of {} at chunk ({}, {})", owner, cx, cz);
            return;
        }
        Base base = new Base(owner, cx, cz, target[0], target[1], copyRadius);
        BASES.add(base);
        TASKS.add(new Task(base, false));
        saveAll();
        LOGGER.info("Farlands: base of {} at chunk ({}, {}) = block ({}, {}) will be copied to block ({}, {}) in the Farlands",
                owner, cx, cz, cx * 16 + 8, cz * 16 + 8, target[0] * 16 + 8, target[1] * 16 + 8);
    }

    // ---- placement --------------------------------------------------------------------------

    private static boolean overlapsExistingCopy(int dcx, int dcz) {
        for (Base base : BASES) {
            int gap = base.radius * 2 + 3;
            if (Math.max(Math.abs(dcx - base.dstCx), Math.abs(dcz - base.dstCz)) <= gap) return true;
        }
        return false;
    }

    /** Chunk coordinates of the centre of the copy, or null. Always inside the plain Farlands, before the void gap. */
    private static int[] chooseTarget(ServerLevel level, UUID owner, int cx, int cz) {
        Random random = new Random(level.getSeed()
                ^ owner.getMostSignificantBits()
                ^ (owner.getLeastSignificantBits() * 31L)
                ^ (cx * 73856093L)
                ^ (cz * 19349663L));

        int margin = (copyRadius + 2) * 16;
        int minD = Math.max(minDistance, margin);
        int maxD = Math.min(maxDistance, FarlandsMod.PLAIN - margin);
        if (maxD < minD) return null;

        boolean alongX = Math.abs(cx) >= Math.abs(cz); // the side that is nearest to the base
        int sign = (alongX ? cx : cz) >= 0 ? 1 : -1;
        int range = FarlandsMod.EDGE / 16 - 100;

        for (int attempt = 0; attempt < 200; attempt++) {
            int distance = minD + random.nextInt(maxD - minD + 1);
            int along = Math.floorDiv(sign * (FarlandsMod.EDGE + distance), 16);
            int across = sameLatitude ? (alongX ? cz : cx) : random.nextInt(2 * range + 1) - range;
            int dcx = alongX ? along : across;
            int dcz = alongX ? across : along;
            if (!overlapsExistingCopy(dcx, dcz)) return new int[] {dcx, dcz};
        }
        return null;
    }

    // ---- scheduling -------------------------------------------------------------------------

    private static boolean hasTask(Base base) {
        for (Task task : TASKS) if (task.base == base) return true;
        return false;
    }

    private static void scheduleRefreshes(long now) {
        for (Base base : BASES) {
            if (hasTask(base)) continue;
            if (!base.copied) {
                TASKS.add(new Task(base, false)); // for example the server stopped in the middle of the first copy
            } else if (!base.locked && now - base.lastRefresh >= refreshMinutes * 1200L) {
                TASKS.add(new Task(base, true));
            }
        }
    }

    private static void finish(Task task, long now, String message) {
        TASKS.remove(task);
        task.base.lastRefresh = now;
        heatDirty = true; // makes sure the next save also writes the bases
        saveAll();
        LOGGER.info("Farlands: base at chunk ({}, {}): {}", task.base.srcCx, task.base.srcCz, message);
    }

    /** Copies or compares one chunk of the oldest task. */
    private static void processTask(ServerLevel level, long now) {
        Task task = TASKS.peek();
        if (task == null) return;
        Base base = task.base;
        int side = base.radius * 2 + 1;
        int sx = base.srcCx - base.radius + task.index % side;
        int sz = base.srcCz - base.radius + task.index / side;
        int dx = sx + (base.dstCx - base.srcCx);
        int dz = sz + (base.dstCz - base.srcCz);

        try {
            LevelChunk dst = level.getChunk(dx, dz); // generates the chunk in the Farlands if it does not exist yet
            if (task.phase == 0) {
                if (dst.getInhabitedTime() > 0L) {
                    base.locked = true;
                    finish(task, now, "somebody has been to the copy in the Farlands, it is locked and stays as it is");
                    return;
                }
                LevelChunk src = level.getChunk(sx, sz);
                task.removed += countRemoved(src, dst, removedLimit - task.removed + 1);
                if (task.removed > removedLimit) {
                    finish(task, now, "looks damaged (" + task.removed + "+ blocks gone), the old copy is kept");
                    return;
                }
                task.index++;
                if (task.index >= side * side) {
                    task.phase = 1;
                    task.index = 0;
                }
            } else {
                if (base.copied && dst.getInhabitedTime() > 0L) {
                    base.locked = true;
                    finish(task, now, "somebody has been to the copy in the Farlands, it is locked and stays as it is");
                    return;
                }
                LevelChunk src = level.getChunk(sx, sz);
                copyChunk(level, src, dst, (dx - sx) * 16, (dz - sz) * 16, task);
                task.index++;
                if (task.index >= side * side) {
                    base.copied = true;
                    String where = "copied to block (" + (base.dstCx * 16 + 8) + ", " + (base.dstCz * 16 + 8) + "), "
                            + task.blockEntities + " block entities, ";
                    finish(task, now, copyContainerItems
                            ? where + "item stacks in containers: " + task.stacksSeen + " in the base, " + task.stacksCopied + " in the copy"
                            : where + task.emptied + " containers left empty on purpose");
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("Farlands: copying a base failed, trying again later", e);
            finish(task, now, "failed");
        }
    }

    // ---- compare and copy -------------------------------------------------------------------

    /** Blocks that are solid in the copy but air in the base (stops counting at `stopAt`). */
    private static long countRemoved(LevelChunk src, LevelChunk dst, long stopAt) {
        LevelChunkSection[] from = src.getSections();
        LevelChunkSection[] to = dst.getSections();
        int n = Math.min(from.length, to.length);
        long removed = 0;
        for (int i = 0; i < n; i++) {
            if (to[i].hasOnlyAir()) continue;
            boolean baseEmpty = from[i].hasOnlyAir();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState copy = to[i].getBlockState(x, y, z);
                        if (copy.isAir() || !copy.getFluidState().isEmpty()) continue;
                        if (baseEmpty || from[i].getBlockState(x, y, z).isAir()) removed++;
                    }
                }
            }
            if (removed >= stopAt) return removed;
        }
        return removed;
    }

    /** Replaces the chunk `dst` with the contents of `src`: blocks, biomes and block entities. */
    private static void copyChunk(ServerLevel level, LevelChunk src, LevelChunk dst, int shiftX, int shiftZ, Task task) {
        LevelChunkSection[] from = src.getSections();
        LevelChunkSection[] to = dst.getSections();
        int n = Math.min(from.length, to.length);
        for (int i = 0; i < n; i++) {
            to[i] = new LevelChunkSection(from[i].getStates().copy(), from[i].getBiomes().copy());
        }

        for (BlockPos pos : new ArrayList<>(dst.getBlockEntitiesPos())) dst.removeBlockEntity(pos);
        for (BlockPos pos : new ArrayList<>(src.getBlockEntitiesPos())) {
            BlockEntity entity = src.getBlockEntity(pos);
            if (entity == null) continue;
            BlockPos moved = pos.offset(shiftX, 0, shiftZ);
            BlockState movedState = dst.getBlockState(moved);

            // 1. Copy everything the block entity knows (name, lock, items, ...) through its saved data.
            CompoundTag tag = entity.saveWithFullMetadata(level.registryAccess());
            tag.putInt("x", moved.getX());
            tag.putInt("y", moved.getY());
            tag.putInt("z", moved.getZ());
            if (!copyContainerItems) {
                tag.remove("Items");         // chests, barrels, dispensers, droppers, hoppers, shulker boxes, furnaces, ...
                tag.remove("LootTable");     // an unopened loot chest becomes a plain empty chest
                tag.remove("LootTableSeed");
            }
            BlockEntity copy = BlockEntity.loadStatic(moved, movedState, tag, level.registryAccess());
            if (copy == null && movedState.getBlock() instanceof EntityBlock maker) {
                copy = maker.newBlockEntity(moved, movedState); // fallback: a fresh, empty block entity
            }
            if (copy == null) {
                LOGGER.warn("Farlands: could not copy the block entity at {}", pos);
                continue;
            }
            dst.setBlockEntity(copy);
            task.blockEntities++;

            // 2. Containers (chests, barrels, dispensers, droppers, hoppers, shulker boxes, furnaces, ...):
            //    also copy the items slot by slot, so they arrive even if step 1 lost them. Containers that
            //    still hold an unopened loot table are skipped, because reading them would generate the loot.
            if (!copyContainerItems) {
                // Make sure nothing at all is left inside (also jukebox discs, lectern books, campfire food, ...).
                if (copy instanceof Clearable clearable) {
                    clearable.clearContent();
                    task.emptied++;
                }
            } else if (entity instanceof Container baseInv && copy instanceof Container copyInv && !tag.contains("LootTable")) {
                int slots = Math.min(baseInv.getContainerSize(), copyInv.getContainerSize());
                int seen = 0;
                int copied = 0;
                for (int slot = 0; slot < slots; slot++) {
                    ItemStack stack = baseInv.getItem(slot);
                    if (stack.isEmpty()) continue;
                    seen++;
                    copyInv.setItem(slot, stack.copy());
                }
                for (int slot = 0; slot < slots; slot++) {
                    if (!copyInv.getItem(slot).isEmpty()) copied++;
                }
                task.stacksSeen += seen;
                task.stacksCopied += copied;
                if (seen != copied) {
                    LOGGER.warn("Farlands: container at {} had {} item stacks, its copy has {}", pos, seen, copied);
                }
            }
        }

        Heightmap.primeHeightmaps(dst, EnumSet.of(
                Heightmap.Types.MOTION_BLOCKING,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                Heightmap.Types.OCEAN_FLOOR,
                Heightmap.Types.WORLD_SURFACE));

        // Lighting was calculated for the Farlands terrain, not for the base: have it calculated again.
        dst.setLightCorrect(false);

        // Hidden marker: this chunk is finished, FarlandsMod must not rewrite it. Changing a block also
        // makes sure that the chunk is saved to disk.
        BlockPos marker = new BlockPos(dst.getPos().getMinBlockX(), 319, dst.getPos().getMinBlockZ());
        dst.setBlockState(marker, Blocks.BARRIER.defaultBlockState(), 0);
        dst.setBlockState(marker, MARKER, 0);
    }

    // ---- settings ---------------------------------------------------------------------------

    private static void loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("farlands.properties");
        Properties props = new Properties();
        try {
            if (Files.exists(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    props.load(in);
                }
            } else {
                Files.writeString(file, String.join("\n",
                        "# Farlands: base copy settings. Restart the server after changing something.",
                        "# Seconds a player must spend inside one 7x7 chunk area before it counts as their base.",
                        "# (For a quick test, use 60.)",
                        "minSeconds=3600",
                        "# ... and that area must hold at least this share of all the time the player has played.",
                        "# (For a quick test, use 0.05.)",
                        "minShare=0.25",
                        "# Chunks copied around the centre of the base. 3 means 7x7 chunks, 4 means 9x9 chunks.",
                        "copyRadius=3",
                        "# How many bases of one player are copied.",
                        "maxBasesPerPlayer=3",
                        "# Game minutes between two refreshes of a copy (1 game minute = 1200 ticks).",
                        "refreshMinutes=60",
                        "# If more than this many blocks of the copy are gone from the base, the base is",
                        "# seen as griefed or blown up and the old copy is kept.",
                        "removedLimit=1500",
                        "# Where the copy is placed: blocks past the Farlands line (the void gap starts at 12400).",
                        "minDistance=1500",
                        "maxDistance=11600",
                        "# true: same x or z coordinate as the base, so it can be found by walking straight there.",
                        "# false: random position along the Farlands.",
                        "sameLatitude=true",
                        "# false: chests, barrels, dispensers and other containers arrive empty in the copy.",
                        "# true: their items are copied as well.",
                        "copyContainerItems=false",
                        "") + "\n");
            }
        } catch (IOException e) {
            LOGGER.warn("Farlands: could not read or write farlands.properties, using the defaults", e);
        }
        minSeconds = intProp(props, "minSeconds", minSeconds, 1);
        minShare = doubleProp(props, "minShare", minShare);
        copyRadius = intProp(props, "copyRadius", copyRadius, 1);
        copyRadius = Math.min(copyRadius, 8);
        maxBasesPerPlayer = intProp(props, "maxBasesPerPlayer", maxBasesPerPlayer, 1);
        refreshMinutes = intProp(props, "refreshMinutes", refreshMinutes, 1);
        removedLimit = intProp(props, "removedLimit", removedLimit, 0);
        minDistance = intProp(props, "minDistance", minDistance, 0);
        maxDistance = intProp(props, "maxDistance", maxDistance, 1);
        sameLatitude = Boolean.parseBoolean(props.getProperty("sameLatitude", Boolean.toString(sameLatitude)).trim());
        copyContainerItems = Boolean.parseBoolean(props.getProperty("copyContainerItems", Boolean.toString(copyContainerItems)).trim());
    }

    private static int intProp(Properties props, String name, int fallback, int min) {
        try {
            return Math.max(min, Integer.parseInt(props.getProperty(name, Integer.toString(fallback)).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double doubleProp(Properties props, String name, double fallback) {
        try {
            return Double.parseDouble(props.getProperty(name, Double.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- saving and loading (world/farlands/) -----------------------------------------------

    private static void load(MinecraftServer server) {
        HEAT.clear();
        BASES.clear();
        TASKS.clear();
        heatDirty = false;
        dir = server.getWorldPath(LevelResource.ROOT).resolve("farlands");
        try {
            Files.createDirectories(dir);

            Path heatFile = dir.resolve("heat.txt");
            if (Files.exists(heatFile)) {
                for (String line : Files.readAllLines(heatFile)) {
                    String[] p = line.split("\t");
                    if (p.length != 4) continue;
                    try {
                        HEAT.computeIfAbsent(UUID.fromString(p[0]), id -> new HashMap<>())
                                .put(key(Integer.parseInt(p[1]), Integer.parseInt(p[2])), Integer.parseInt(p[3]));
                    } catch (IllegalArgumentException ignored) {
                        // skip a damaged line
                    }
                }
            }

            Path baseFile = dir.resolve("bases.txt");
            if (Files.exists(baseFile)) {
                for (String line : Files.readAllLines(baseFile)) {
                    String[] p = line.split("\t");
                    if (p.length != 9) continue;
                    try {
                        Base base = new Base(UUID.fromString(p[0]),
                                Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                                Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
                        base.lastRefresh = Long.parseLong(p[6]);
                        base.copied = p[7].equals("1");
                        base.locked = p[8].equals("1");
                        BASES.add(base);
                    } catch (IllegalArgumentException ignored) {
                        // skip a damaged line
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Farlands: could not read the saved base data", e);
        }
        ready = true;
    }

    /**
     * Saves the heat map (only if it changed) and the bases. bases.txt columns, tab separated: owner uuid,
     * base chunk x, base chunk z, copy chunk x, copy chunk z, radius, last refresh (game tick),
     * copied (0/1), locked (0/1). Multiply chunk numbers by 16 to get blocks.
     */
    private static void saveAll() {
        if (dir == null) return;
        try {
            if (heatDirty) {
                List<String> lines = new ArrayList<>();
                for (Map.Entry<UUID, Map<Long, Integer>> player : HEAT.entrySet()) {
                    for (Map.Entry<Long, Integer> cell : player.getValue().entrySet()) {
                        lines.add(player.getKey() + "\t" + keyX(cell.getKey()) + "\t" + keyZ(cell.getKey()) + "\t" + cell.getValue());
                    }
                }
                Files.write(dir.resolve("heat.txt"), lines);
                heatDirty = false;
            }
            List<String> lines = new ArrayList<>();
            for (Base b : BASES) {
                lines.add(b.owner + "\t" + b.srcCx + "\t" + b.srcCz + "\t" + b.dstCx + "\t" + b.dstCz + "\t" + b.radius
                        + "\t" + b.lastRefresh + "\t" + (b.copied ? "1" : "0") + "\t" + (b.locked ? "1" : "0"));
            }
            Files.write(dir.resolve("bases.txt"), lines);
        } catch (IOException e) {
            LOGGER.warn("Farlands: could not save the base data", e);
        }
    }
}
