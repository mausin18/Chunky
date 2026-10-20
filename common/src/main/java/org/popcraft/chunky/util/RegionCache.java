package org.popcraft.chunky.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

public class RegionCache {
    private final Map<String, WorldState> cache = new ConcurrentHashMap<>();

    public WorldState getWorld(final String world) {
        return cache.computeIfAbsent(world, x -> new WorldState());
    }

    public void clear(final String world) {
        cache.remove(world);
    }

    public static final class WorldState {
        private final Map<Long, AtomicLongArray> regions = new ConcurrentHashMap<>();

        public void setGenerated(final int x, final int z) {
            final int regionX = x >> 5;
            final int regionZ = z >> 5;
            final long regionKey = ChunkMath.pack(regionX, regionZ);
            final AtomicLongArray region = regions.computeIfAbsent(regionKey, v -> new AtomicLongArray(16));
            final int chunkIndex = ChunkMath.regionIndex(x, z);
            final int wordIndex = chunkIndex >> 6;
            final long mask = 1L << (chunkIndex & 63);
            long word;
            do {
                word = region.get(wordIndex);
                if ((word & mask) != 0) {
                    return;
                }
            } while (!region.compareAndSet(wordIndex, word, word | mask));
        }

        public boolean isGenerated(final int x, final int z) {
            final int regionX = x >> 5;
            final int regionZ = z >> 5;
            final long regionKey = ChunkMath.pack(regionX, regionZ);
            final AtomicLongArray region = regions.get(regionKey);
            if (region == null) {
                return false;
            }
            final int chunkIndex = ChunkMath.regionIndex(x, z);
            return (region.get(chunkIndex >> 6) & (1L << (chunkIndex & 63))) != 0;
        }
    }
}
