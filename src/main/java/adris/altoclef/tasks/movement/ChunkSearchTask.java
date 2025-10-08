package adris.altoclef.tasks.movement;

import adris.altoclef.AltoClef;
import adris.altoclef.Debug;
import adris.altoclef.eventbus.EventBus;
import adris.altoclef.eventbus.Subscription;
import adris.altoclef.eventbus.events.ChunkLoadEvent;
import adris.altoclef.tasksystem.Task;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.*;

/**
 * Optimized version of ChunkSearchTask.
 * Features:
 * - PriorityQueue for faster best-chunk selection (O(log n))
 * - Directional bias to reduce ping-pong movement
 * - BFS-style neighbor expansion for spatial continuity
 * - Caching of chunk search results
 * - Optional parallel scanning (lightweight)
 */
abstract class ChunkSearchTask extends Task {

    private final BlockPos _startPoint;
    private final Object _searchMutex = new Object();

    // Caches
    private final Set<ChunkPos> _consideredAlready = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> _searchedAlready = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Boolean> _isSearchSpaceCache = new ConcurrentHashMap<>();

    // For deferred scanning
    private final Queue<ChunkPos> _bfsQueue = new ConcurrentLinkedQueue<>();
    private final List<ChunkPos> _justLoaded = Collections.synchronizedList(new ArrayList<>());

    // Priority queue for best-chunk selection
    private final PriorityQueue<ChunkPos> _searchQueue = new PriorityQueue<>(
            Comparator.comparingDouble(pos -> chunkScore(AltoClef.getInstance(), pos))
    );

    private boolean _first = true;
    private boolean _finished = false;
    private Subscription<ChunkLoadEvent> _onChunkLoad;

    // Optional lightweight async executor
    private final ExecutorService _executor = Executors.newFixedThreadPool(2);

    public ChunkSearchTask(BlockPos startPoint) {
        _startPoint = startPoint;
    }

    public ChunkSearchTask(ChunkPos chunkPos) {
        this(chunkPos.getStartPos().add(1, 1, 1));
    }

    public Set<ChunkPos> getSearchedChunks() {
        return _searchedAlready;
    }

    public boolean finished() {
        return _finished;
    }

    @Override
    protected void onStart() {
        if (_first) {
            _finished = false;
            _first = false;

            ChunkPos startPos = AltoClef.getInstance().getWorld().getChunk(_startPoint).getPos();
            synchronized (_searchMutex) {
                enqueueChunk(AltoClef.getInstance(), startPos);
            }
        }

        _onChunkLoad = EventBus.subscribe(ChunkLoadEvent.class, evt -> {
            WorldChunk chunk = evt.chunk;
            if (chunk != null) {
                _justLoaded.add(chunk.getPos());
            }
        });
    }

    @Override
    protected Task onTick() {
        AltoClef mod = AltoClef.getInstance();

        // Check loaded chunks we can now search
        synchronized (_searchMutex) {
            for (ChunkPos justLoaded : _justLoaded) {
                if (_searchQueue.contains(justLoaded) && trySearchChunk(mod, justLoaded)) {
                    _searchQueue.remove(justLoaded);
                }
            }
            _justLoaded.clear();
        }

        // Expand BFS if needed
        while (!_bfsQueue.isEmpty()) {
            ChunkPos pos = _bfsQueue.poll();
            enqueueChunk(mod, pos);
        }

        // Pick the next best chunk to move toward
        ChunkPos next = _searchQueue.peek();
        if (next == null) {
            _finished = true;
            Debug.logMessage("ChunkSearchTask finished: no more chunks to explore.");
            return null;
        }

        return new GetToChunkTask(next);
    }

    @Override
    protected void onStop(Task interruptTask) {
        EventBus.unsubscribe(_onChunkLoad);
        _executor.shutdownNow();
    }

    @Override
    public boolean isFinished() {
        return _finished;
    }

    @Override
    protected boolean isEqual(Task other) {
        if (other instanceof ChunkSearchTask task) {
            return _startPoint.equals(task._startPoint) && isChunkSearchEqual(task);
        }
        return false;
    }

    // ----------------------
    // Core Search Logic
    // ----------------------

    private void enqueueChunk(AltoClef mod, ChunkPos pos) {
        if (_consideredAlready.add(pos)) {
            if (!trySearchChunk(mod, pos)) {
                _searchQueue.add(pos);
            }
        }
    }

    private boolean trySearchChunk(AltoClef mod, ChunkPos pos) {
        if (_searchedAlready.contains(pos)) return true;

        if (mod.getChunkTracker().isChunkLoaded(pos)) {
            _searchedAlready.add(pos);

            boolean isSearchSpace = _isSearchSpaceCache.computeIfAbsent(
                    pos, p -> isChunkPartOfSearchSpace(mod, p)
            );

            if (isSearchSpace) {
                // Expand neighbors in BFS order
                _bfsQueue.offer(new ChunkPos(pos.x + 1, pos.z));
                _bfsQueue.offer(new ChunkPos(pos.x - 1, pos.z));
                _bfsQueue.offer(new ChunkPos(pos.x, pos.z + 1));
                _bfsQueue.offer(new ChunkPos(pos.x, pos.z - 1));
            }
            return true;
        }

        // If not loaded, maybe check asynchronously
        _executor.submit(() -> {
            if (mod.getChunkTracker().isChunkLoaded(pos)) {
                _justLoaded.add(pos);
            }
        });

        return false;
    }

    // Heuristic function for scoring chunks
    private double chunkScore(AltoClef mod, ChunkPos pos) {
        double cx = (pos.getStartX() + pos.getEndX() + 1) / 2.0;
        double cz = (pos.getStartZ() + pos.getEndZ() + 1) / 2.0;

        Vec3d playerPos = mod.getPlayer().getPos();
        Vec3d playerVel = mod.getPlayer().getVelocity();

        double px = playerPos.x, pz = playerPos.z;
        double distanceSq = (cx - px) * (cx - px) + (cz - pz) * (cz - pz);
        double distanceToCenterSq = new Vec3d(_startPoint.getX() - cx, 0, _startPoint.getZ() - cz).lengthSquared();

        // Directional bias to prevent flip-flopping
        Vec3d toChunk = new Vec3d(cx - px, 0, cz - pz).normalize();
        double forwardBias = Math.max(0, playerVel.lengthSquared() > 0 ? playerVel.normalize().dotProduct(toChunk) : 0);

        return distanceSq + distanceToCenterSq * 0.8 - forwardBias * 200.0;
    }

    // ----------------------
    // Abstracts for customization
    // ----------------------

    protected abstract boolean isChunkPartOfSearchSpace(AltoClef mod, ChunkPos pos);

    protected abstract boolean isChunkSearchEqual(ChunkSearchTask other);
}