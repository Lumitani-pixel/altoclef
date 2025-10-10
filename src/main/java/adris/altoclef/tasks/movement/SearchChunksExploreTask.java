package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.ChunkPos;

import java.util.HashSet;
import java.util.Set;

/**
 * Searches/explores a continuous "blob" of chunks, attempting to load in ALL nearby chunks
 * that are part of this "blob" (e.g., biome, structure, etc.)
 * The subclass must define isChunkWithinSearchSpace to decide which chunks belong.
 */
public abstract class SearchChunksExploreTask extends Task {

    private final Object searcherMutex = new Object();
    private final Set<ChunkPos> alreadyExplored = new HashSet<>();

    private ChunkSearchTask searcher;
    private Subscription<ChunkLoadEvent> chunkLoadedSubscription;

    @Override
    protected void onStart() {
        // Listen for new chunk loads
        chunkLoadedSubscription = EventBus.subscribe(ChunkLoadEvent.class, evt -> {
            if (evt.chunk != null) onChunkLoad(evt.chunk.getPos());
        });
        resetSearch();
    }

    @Override
    protected Task onTick() {
        synchronized (searcherMutex) {
            if (searcher == null) {
                setDebugState("Exploring/Searching for valid chunk...");
                return getWanderTask();
            }

            if (!searcher.isActive()) {
                setDebugState("Searcher inactive, restarting...");
                alreadyExplored.addAll(searcher.getSearchedChunks());
                searcher = null;
                return getWanderTask();
            }

            if (searcher.isFinished() || searcher.finished()) {
                Debug.logMessage("Searcher finished. Recording explored chunks.");
                alreadyExplored.addAll(searcher.getSearchedChunks());
                searcher = null;
                return getWanderTask();
            }

            setDebugState("Searching within connected chunks...");
            return searcher;
        }
    }

    @Override
    protected void onStop(Task interruptTask) {
        EventBus.unsubscribe(chunkLoadedSubscription);
        if (searcher != null) searcher.stop(interruptTask);
    }

    /** Called when a chunk loads — we may want to start a new search here. */
    private void onChunkLoad(ChunkPos pos) {
        if (!isActive()) return;
        synchronized (searcherMutex) {
            if (searcher != null) return;
            if (alreadyExplored.contains(pos)) return;

            if (isChunkWithinSearchSpace(AltoClef.getInstance(), pos)) {
                Debug.logMessage("Starting new chunk searcher at: " + pos);
                searcher = new SearchSubTask(pos);
            }
        }
    }

    protected Task getWanderTask() {
        return new TimeoutWanderTask(true);
    }

    /** Must be implemented: defines whether a given chunk belongs to this search blob. */
    protected abstract boolean isChunkWithinSearchSpace(AltoClef mod, ChunkPos pos);

    /** Returns true if no active searcher is currently running. */
    public boolean failedSearch() {
        return searcher == null;
    }

    /** Resets the current exploration state and restarts scanning from loaded chunks. */
    public void resetSearch() {
        synchronized (searcherMutex) {
            searcher = null;
            alreadyExplored.clear();
            for (ChunkPos start : AltoClef.getInstance().getChunkTracker().getLoadedChunks()) {
                onChunkLoad(start);
            }
        }
    }

    /** Inner class that wraps a ChunkSearchTask customized for this explorer. */
    class SearchSubTask extends ChunkSearchTask {

        public SearchSubTask(ChunkPos start) {
            super(start);
        }

        @Override
        protected boolean isChunkPartOfSearchSpace(AltoClef mod, ChunkPos pos) {
            return isChunkWithinSearchSpace(mod, pos);
        }

        // The scoring/ordering is handled by the new ChunkSearchTask, so no override needed.

        @Override
        protected boolean isChunkSearchEqual(ChunkSearchTask other) {
            // All searcher instances are treated as unique.
            return other == this;
        }

        @Override
        protected String toDebugString() {
            return "Searching connected chunks...";
        }
    }
}