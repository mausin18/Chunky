package org.popcraft.chunky;

import org.popcraft.chunky.api.event.task.GenerationCompleteEvent;
import org.popcraft.chunky.api.event.task.GenerationProgressEvent;
import org.popcraft.chunky.event.task.GenerationTaskFinishEvent;
import org.popcraft.chunky.event.task.GenerationTaskUpdateEvent;
import org.popcraft.chunky.iterator.ChunkIterator;
import org.popcraft.chunky.iterator.ChunkIteratorFactory;
import org.popcraft.chunky.platform.Sender;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.shape.ShapeFactory;
import org.popcraft.chunky.util.ChunkCoordinate;
import org.popcraft.chunky.util.RegionCache;
import org.popcraft.chunky.util.TranslationKey;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class GenerationTask implements Runnable {
    private static final long RATE_UPDATE_INTERVAL = 250L;
    private final Chunky chunky;
    private final int maxWorkingCount;
    private final int loadThreads;
    private final Selection selection;
    private final Shape shape;
    private final AtomicLong startTime = new AtomicLong();
    private final AtomicLong updateTime = new AtomicLong();
    private final AtomicLong finishedChunks = new AtomicLong();
    private final AtomicLong lastRateUpdate = new AtomicLong();
    private final AtomicLong lastRateCount = new AtomicLong();
    private final AtomicLong lastRateTime = new AtomicLong();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final Progress progress;
    private final RegionCache.WorldState worldState;
    private ChunkIterator chunkIterator;
    private volatile boolean stopped, cancelled;
    private long prevTime;

    public GenerationTask(final Chunky chunky, final Selection selection, final long count, final long time, final boolean cancelled) {
        this(chunky, selection);
        this.chunkIterator = ChunkIteratorFactory.getChunkIterator(selection, count);
        this.finishedChunks.set(count);
        this.cancelled = cancelled;
        this.prevTime = time;
    }

    public GenerationTask(final Chunky chunky, final Selection selection) {
        this.chunky = chunky;
        this.selection = selection;
        this.chunkIterator = ChunkIteratorFactory.getChunkIterator(selection);
        this.shape = ShapeFactory.getShape(selection);
        this.progress = new Progress(selection.world().getName());
        this.worldState = chunky.getRegionCache().getWorld(selection.world().getName());
        this.maxWorkingCount = resolveMaxWorkingCount(chunky.getConfig().getMaxWorkingCount());
        this.loadThreads = resolveLoadThreads(chunky.getConfig().getLoadThreads());
    }

    private static int resolveMaxWorkingCount(final int configured) {
        if (configured > 0) {
            return configured;
        }
        return Math.max(512, Math.min(8192, Runtime.getRuntime().availableProcessors() * 128));
    }

    private static int resolveLoadThreads(final int configured) {
        if (configured > 0) {
            return Math.min(configured, 64);
        }
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    }

    private void update(final int chunkX, final int chunkZ, final boolean loaded) {
        if (stopped) {
            return;
        }
        final long chunkCount = finishedChunks.incrementAndGet();
        if (loaded) {
            worldState.setGenerated(chunkX, chunkZ);
        }
        final long total = chunkIterator.total();
        final long chunksLeft = total - chunkCount;
        final long currentTime = System.currentTimeMillis();
        if (chunksLeft > 0) {
            final long lastUpdate = lastRateUpdate.get();
            if (currentTime - lastUpdate < RATE_UPDATE_INTERVAL || !lastRateUpdate.compareAndSet(lastUpdate, currentTime)) {
                return;
            }
            final long previousCount = lastRateCount.getAndSet(chunkCount);
            final long previousTime = lastRateTime.getAndSet(currentTime);
            final double elapsed = (currentTime - previousTime) / 1e3;
            progress.rate = elapsed > 0 ? (chunkCount - previousCount) / elapsed : 0;
            progress.chunkCount = chunkCount;
            progress.chunkX = chunkX;
            progress.chunkZ = chunkZ;
            progress.percentComplete = 100f * chunkCount / total;
            if (progress.rate > 0) {
                final long time = (long) (chunksLeft / progress.rate);
                progress.hours = time / 3600;
                progress.minutes = (time - progress.hours * 3600) / 60;
                progress.seconds = time - progress.hours * 3600 - progress.minutes * 60;
            }
            chunky.getEventBus().call(new GenerationProgressEvent(progress.world, progress.chunkCount, progress.complete, progress.percentComplete, progress.hours, progress.minutes, progress.seconds, progress.rate, progress.chunkX, progress.chunkZ));
            if (((currentTime - updateTime.get()) / 1e3) > chunky.getConfig().getUpdateInterval()) {
                if (!chunky.getConfig().isSilent()) {
                    progress.sendUpdate(chunky.getServer().getConsole());
                }
                chunky.getEventBus().call(new GenerationTaskUpdateEvent(this));
                updateTime.set(currentTime);
            }
            return;
        }
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        progress.chunkCount = chunkCount;
        progress.chunkX = chunkX;
        progress.chunkZ = chunkZ;
        progress.complete = true;
        progress.percentComplete = 100f;
        final long time = (prevTime + (currentTime - startTime.get())) / 1000;
        progress.hours = time / 3600;
        progress.minutes = (time - progress.hours * 3600) / 60;
        progress.seconds = time - progress.hours * 3600 - progress.minutes * 60;
        chunky.getEventBus().call(new GenerationProgressEvent(progress.world, progress.chunkCount, progress.complete, progress.percentComplete, progress.hours, progress.minutes, progress.seconds, progress.rate, progress.chunkX, progress.chunkZ));
        progress.sendUpdate(chunky.getServer().getConsole());
        chunky.getEventBus().call(new GenerationTaskUpdateEvent(this));
    }

    @Override
    public void run() {
        final String poolThreadName = Thread.currentThread().getName();
        Thread.currentThread().setName(String.format("Chunky-%s Thread", selection.world().getName()));
        if (!chunkIterator.process()) {
            stop(true);
        }
        final Semaphore working = new Semaphore(maxWorkingCount);
        final boolean forceLoadExistingChunks = chunky.getConfig().isForceLoadExistingChunks();
        final ExecutorService loader = loadThreads > 1
                ? Executors.newFixedThreadPool(loadThreads, runnable -> {
                    final Thread thread = new Thread(runnable, "Chunky-Load-" + selection.world().getName());
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
        startTime.set(System.currentTimeMillis());
        while (!stopped && chunkIterator.hasNext()) {
            final ChunkCoordinate chunk = chunkIterator.next();
            final int chunkCenterX = (chunk.x() << 4) + 8;
            final int chunkCenterZ = (chunk.z() << 4) + 8;
            if (!shape.isBounding(chunkCenterX, chunkCenterZ)) {
                update(chunk.x(), chunk.z(), false);
                continue;
            }
            if (!forceLoadExistingChunks && worldState.isGenerated(chunk.x(), chunk.z())) {
                update(chunk.x(), chunk.z(), false);
                continue;
            }
            try {
                working.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop(cancelled);
                break;
            }
            if (loader == null) {
                submitLoad(chunk, working, forceLoadExistingChunks);
            } else {
                loader.execute(() -> submitLoad(chunk, working, forceLoadExistingChunks));
            }
        }
        if (loader != null) {
            loader.shutdown();
        }
        if (stopped) {
            chunky.getServer().getConsole().sendMessagePrefixed(TranslationKey.TASK_STOPPED, selection.world().getName());
        } else {
            cancelled = true;
        }
        chunky.getTaskLoader().saveTask(this);
        chunky.getGenerationTasks().remove(selection.world().getName());
        Thread.currentThread().setName(poolThreadName);
        chunky.getEventBus().call(new GenerationTaskFinishEvent(this));
        chunky.getEventBus().call(new GenerationCompleteEvent(selection.world().getName()));
    }

    private void submitLoad(final ChunkCoordinate chunk, final Semaphore working, final boolean forceLoadExistingChunks) {
        try {
            final CompletableFuture<Void> loadFuture;
            if (forceLoadExistingChunks) {
                loadFuture = selection.world().getChunkAtAsync(chunk.x(), chunk.z());
            } else {
                final CompletableFuture<Boolean> isChunkGenerated = selection.world().isChunkGenerated(chunk.x(), chunk.z());
                if (isChunkGenerated.isDone()) {
                    loadFuture = Boolean.TRUE.equals(isChunkGenerated.getNow(Boolean.FALSE)) ?
                            CompletableFuture.completedFuture(null) :
                            selection.world().getChunkAtAsync(chunk.x(), chunk.z());
                } else {
                    loadFuture = isChunkGenerated.thenCompose(generated -> Boolean.TRUE.equals(generated) ?
                            CompletableFuture.completedFuture(null) :
                            selection.world().getChunkAtAsync(chunk.x(), chunk.z()));
                }
            }
            loadFuture.whenComplete((ignored, throwable) -> {
                working.release();
                update(chunk.x(), chunk.z(), true);
            });
        } catch (Throwable throwable) {
            working.release();
            update(chunk.x(), chunk.z(), true);
        }
    }

    public void stop(final boolean cancelled) {
        this.stopped = true;
        this.cancelled = cancelled;
    }

    public Chunky getChunky() {
        return chunky;
    }

    public Selection getSelection() {
        return selection;
    }

    public long getCount() {
        return finishedChunks.get();
    }

    public ChunkIterator getChunkIterator() {
        return chunkIterator;
    }

    public Shape getShape() {
        return shape;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public long getTotalTime() {
        return prevTime + (startTime.get() > 0 ? System.currentTimeMillis() - startTime.get() : 0);
    }

    public Progress getProgress() {
        return progress;
    }

    @SuppressWarnings("unused")
    public static final class Progress {
        private final String world;
        private volatile long chunkCount;
        private volatile boolean complete;
        private volatile float percentComplete;
        private volatile long hours, minutes, seconds;
        private volatile double rate;
        private volatile int chunkX, chunkZ;

        private Progress(final String world) {
            this.world = world;
        }

        public String getWorld() {
            return world;
        }

        public long getChunkCount() {
            return chunkCount;
        }

        public boolean isComplete() {
            return complete;
        }

        public float getPercentComplete() {
            return percentComplete;
        }

        public long getHours() {
            return hours;
        }

        public long getMinutes() {
            return minutes;
        }

        public long getSeconds() {
            return seconds;
        }

        public double getRate() {
            return rate;
        }

        public int getChunkX() {
            return chunkX;
        }

        public int getChunkZ() {
            return chunkZ;
        }

        public void sendUpdate(final Sender sender) {
            if (complete) {
                sender.sendMessagePrefixed(TranslationKey.TASK_DONE, world, chunkCount, String.format("%.2f", percentComplete), String.format("%01d", hours), String.format("%02d", minutes), String.format("%02d", seconds));
            } else {
                sender.sendMessagePrefixed(TranslationKey.TASK_UPDATE, world, chunkCount, String.format("%.2f", percentComplete), String.format("%01d", hours), String.format("%02d", minutes), String.format("%02d", seconds), String.format("%.1f", rate), chunkX, chunkZ);
            }
        }
    }
}
