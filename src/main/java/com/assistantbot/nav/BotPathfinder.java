package com.assistantbot.nav;

import com.assistantbot.AssistantMod;
import com.assistantbot.bot.AssistantBot;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Custom A* pathfinder for the assistant bot, inspired by mineflayer-pathfinder
 * and azalea's approach. Unlike the previous MobNavigation wrapper, this
 * implements A* directly with neighbor generation that understands player-like
 * movement:
 *
 * - Forward walk (flat, same Y)
 * - Ascend (jump up 1 block)
 * - Descend (drop down 1-3 blocks)
 * - Diagonal walk
 *
 * The pathfinder reads block states from the world to determine what's passable,
 * solid, climbable, etc. — no dummy mob needed.
 */
public class BotPathfinder {
    /** Max A* search range in blocks (Manhattan distance cutoff). */
    private static final int PATHFINDING_RANGE = 32;

    /** Max number of nodes to evaluate before giving up. */
    private static final int MAX_ITERATIONS = 2000;

    /** Max blocks the bot is allowed to fall. */
    private static final int MAX_DROP_DOWN = 3;

    /** How often to recompute the path, in game ticks. ~1 second at 20tps. */
    private static final int RECOMPUTE_INTERVAL_TICKS = 20;

    /** Task tick interval in game ticks (tasks tick every 5 game ticks). */
    private static final int TASK_TICK_INTERVAL = 5;

    // Movement costs (inspired by mineflayer)
    private static final double COST_FORWARD = 1.0;
    private static final double COST_DIAGONAL = Math.sqrt(2.0);
    private static final double COST_JUMP_UP = 2.0;  // move + jump
    private static final double COST_DROP = 1.5;      // move + fall
    private static final double COST_LADDER = 1.0;    // climbing

    private final AssistantBot bot;

    // Current path state
    private @Nullable List<Vec3d> currentWaypoints;
    private int currentWaypointIndex;
    private @Nullable BlockPos currentTarget;
    private int ticksSinceLastCompute;

    public BotPathfinder(AssistantBot bot) {
        this.bot = bot;
    }

    // ==================== A* Core ====================

    /**
     * Compute a path from the bot's current position to the target using A*.
     * Returns a list of waypoint Vec3d positions (block-centered on XZ,
     * feet-level Y), or null if no path found.
     */
    public @Nullable List<Vec3d> computePath(BlockPos target) {
        BlockPos start = bot.getBlockPos();
        ServerWorld world = bot.getWorld();

        if (start.equals(target)) return null;

        // Manhattan distance quick reject
        int manhattanDist = Math.abs(start.getX() - target.getX())
                + Math.abs(start.getY() - target.getY())
                + Math.abs(start.getZ() - target.getZ());
        if (manhattanDist > PATHFINDING_RANGE) {
            AssistantMod.LOGGER.info("A* target too far (manhattan={}), skipping", manhattanDist);
            return null;
        }

        AssistantMod.LOGGER.info("A* computing path from {} to {}", start, target);

        // Open set: priority queue ordered by fScore
        PriorityQueue<AStarNode> openSet = new PriorityQueue<>(
                Comparator.comparingDouble(n -> n.fScore));

        // Track best gScore for each position
        Map<BlockPos, Double> gScores = new HashMap<>();

        // Track parent for path reconstruction
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();

        AStarNode startNode = new AStarNode(start, 0, heuristic(start, target));
        openSet.add(startNode);
        gScores.put(start, 0.0);

        int iterations = 0;

        while (!openSet.isEmpty() && iterations < MAX_ITERATIONS) {
            iterations++;
            AStarNode current = openSet.poll();

            // Goal check: within 1 block of target (same as the old PATH_TARGET_DISTANCE=1)
            if (current.pos.isWithinDistance(target, 1.5)) {
                List<Vec3d> path = reconstructPath(cameFrom, current.pos);
                AssistantMod.LOGGER.info("A* path found! length={}, iterations={}", path.size(), iterations);
                return path;
            }

            // Generate neighbors
            List<Neighbor> neighbors = getNeighbors(world, current.pos);

            for (Neighbor neighbor : neighbors) {
                double tentativeG = current.gScore + neighbor.cost;

                double existingG = gScores.getOrDefault(neighbor.pos, Double.MAX_VALUE);
                if (tentativeG < existingG) {
                    gScores.put(neighbor.pos, tentativeG);
                    cameFrom.put(neighbor.pos, current.pos);
                    double fScore = tentativeG + heuristic(neighbor.pos, target);
                    openSet.add(new AStarNode(neighbor.pos, tentativeG, fScore));
                }
            }
        }

        AssistantMod.LOGGER.info("A* found no path after {} iterations", iterations);
        return null;
    }

    /** Euclidean distance heuristic (admissible). */
    private double heuristic(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Reconstruct path from cameFrom map. */
    private List<Vec3d> reconstructPath(Map<BlockPos, BlockPos> cameFrom, BlockPos current) {
        List<Vec3d> path = new ArrayList<>();
        while (current != null) {
            // Block-centered XZ, feet-level Y
            path.add(new Vec3d(current.getX() + 0.5, current.getY(), current.getZ() + 0.5));
            current = cameFrom.get(current);
        }
        Collections.reverse(path);
        return path;
    }

    // ==================== Neighbor Generation ====================
    // Inspired by mineflayer-pathfinder's Movements.getNeighbors()

    /**
     * Generate all reachable neighbors from a position.
     * Considers: forward, diagonal, jump up, drop down, climb.
     */
    private List<Neighbor> getNeighbors(ServerWorld world, BlockPos pos) {
        List<Neighbor> neighbors = new ArrayList<>();

        // Cardinal directions: N, S, E, W
        int[][] cardinals = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        // Diagonal directions
        int[][] diagonals = {{-1, -1}, {-1, 1}, {1, -1}, {1, 1}};

        for (int[] dir : cardinals) {
            getMoveForward(world, pos, dir[0], dir[1], neighbors);
            getMoveJumpUp(world, pos, dir[0], dir[1], neighbors);
            getMoveDropDown(world, pos, dir[0], dir[1], neighbors);
        }

        for (int[] dir : diagonals) {
            getMoveDiagonal(world, pos, dir[0], dir[1], neighbors);
        }

        // Vertical: climb up/down ladders, or drop straight down
        getMoveClimbUp(world, pos, neighbors);
        getMoveClimbDown(world, pos, neighbors);

        return neighbors;
    }

    /**
     * Forward move: walk 1 block in a cardinal direction, same Y level.
     * Requires: destination feet and head are passable, ground below is solid.
     *
     *    .B    (head level: must be passable)
     *    +C    (feet level: must be passable)
     *    #D    (ground: must be solid)
     */
    private void getMoveForward(ServerWorld world, BlockPos pos, int dx, int dz, List<Neighbor> neighbors) {
        BlockPos dest = pos.add(dx, 0, dz);
        BlockPos destHead = dest.up();
        BlockPos destGround = dest.down();

        if (isPassable(world, dest) && isPassable(world, destHead) && isSolid(world, destGround)) {
            double cost = COST_FORWARD;
            if (isWater(world, pos)) cost += 0.5; // water penalty
            neighbors.add(new Neighbor(dest, cost));
        }
    }

    /**
     * Jump up move: jump 1 block up in a cardinal direction.
     * Requires: space above current (head+1), space at destination (feet, head),
     * and solid ground at destination level.
     *
     *     H    (above dest head: must be passable)
     *   A B    (dest head + above current: must be passable)
     *   .+C    (dest feet: must be solid or passable-with-ground)
     *   #D     (current ground + dest ground)
     */
    private void getMoveJumpUp(ServerWorld world, BlockPos pos, int dx, int dz, List<Neighbor> neighbors) {
        BlockPos dest = pos.add(dx, 1, dz);
        BlockPos destHead = dest.up();
        BlockPos destGround = dest.down(); // same as pos.add(dx, 0, dz)
        BlockPos aboveCurrent = pos.up(2); // need clearance to jump

        // Need: clearance above current pos, passable at dest feet & head, solid ground under dest
        if (isPassable(world, aboveCurrent)
                && isPassable(world, dest) && isPassable(world, destHead)
                && isSolid(world, destGround)) {
            neighbors.add(new Neighbor(dest, COST_JUMP_UP));
        }
    }

    /**
     * Drop down move: walk off edge and fall 1-3 blocks in a cardinal direction.
     * The horizontal neighbor must be air (no ground), and we find the landing spot.
     */
    private void getMoveDropDown(ServerWorld world, BlockPos pos, int dx, int dz, List<Neighbor> neighbors) {
        BlockPos horizontal = pos.add(dx, 0, dz);
        BlockPos horizontalHead = horizontal.up();

        // The horizontal position feet & head must be passable (we walk through)
        if (!isPassable(world, horizontal) || !isPassable(world, horizontalHead)) return;

        // Find landing: scan downward from horizontal position
        for (int drop = 1; drop <= MAX_DROP_DOWN; drop++) {
            BlockPos landing = horizontal.down(drop);
            BlockPos landingGround = landing.down();

            if (isSolid(world, landingGround) && isPassable(world, landing) && isPassable(world, landing.up())) {
                double cost = COST_DROP + (drop - 1) * 0.5; // more drop = more cost
                neighbors.add(new Neighbor(landing, cost));
                break; // found landing, stop scanning
            }

            // If we hit a non-passable block before finding ground, can't drop here
            if (!isPassable(world, landing)) break;
        }
    }

    /**
     * Diagonal move: walk 1 block diagonally, same Y level.
     * Both cardinal intermediate positions must have passable head space
     * (to avoid clipping corners).
     */
    private void getMoveDiagonal(ServerWorld world, BlockPos pos, int dx, int dz, List<Neighbor> neighbors) {
        BlockPos dest = pos.add(dx, 0, dz);
        BlockPos destHead = dest.up();
        BlockPos destGround = dest.down();

        if (!isPassable(world, dest) || !isPassable(world, destHead) || !isSolid(world, destGround)) return;

        // Check both cardinal intermediate positions for passability (corner check)
        BlockPos side1 = pos.add(dx, 0, 0);
        BlockPos side2 = pos.add(0, 0, dz);
        boolean side1Passable = isPassable(world, side1) && isPassable(world, side1.up());
        boolean side2Passable = isPassable(world, side2) && isPassable(world, side2.up());

        // Need at least one side passable (like mineflayer: can hug wall)
        if (!side1Passable && !side2Passable) return;

        double cost = COST_DIAGONAL;
        // Extra cost if hugging a wall (only one side passable)
        if (!side1Passable || !side2Passable) cost += 0.5;

        neighbors.add(new Neighbor(dest, cost));
    }

    /**
     * Climb up: if current position has a climbable block (ladder/vine),
     * move 1 block up.
     */
    private void getMoveClimbUp(ServerWorld world, BlockPos pos, List<Neighbor> neighbors) {
        if (!isClimbable(world, pos) && !isClimbable(world, pos.up())) return;

        BlockPos dest = pos.up();
        BlockPos destHead = dest.up();

        if (isPassable(world, destHead) || isClimbable(world, destHead)) {
            neighbors.add(new Neighbor(dest, COST_LADDER));
        }
    }

    /**
     * Climb down: if position below has a climbable block, move 1 block down.
     */
    private void getMoveClimbDown(ServerWorld world, BlockPos pos, List<Neighbor> neighbors) {
        BlockPos dest = pos.down();
        if (isClimbable(world, dest) || isClimbable(world, pos)) {
            if (isPassable(world, dest) || isClimbable(world, dest)) {
                neighbors.add(new Neighbor(dest, COST_LADDER));
            }
        }
    }

    // ==================== Block Queries ====================

    /** A block the player can walk/stand through (air, water, grass, flowers, etc.) */
    private boolean isPassable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        // Air, cave air, void air
        if (state.isAir()) return true;
        // Non-solid blocks that players can walk through
        if (!state.blocksMovement()) return true;
        return false;
    }

    /** A block the player can stand on (solid, full-block collision). */
    private boolean isSolid(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;
        // Use the collision shape: if it blocks movement, it's solid enough to stand on
        return state.blocksMovement();
    }

    /** Check if a block is water. */
    private boolean isWater(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isOf(Blocks.WATER);
    }

    /** Check if a block is climbable (ladder, vine, etc.) */
    private boolean isClimbable(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isIn(BlockTags.CLIMBABLE);
    }

    // ==================== Waypoint Iteration ====================

    /**
     * Navigate to a target position. Call this every task tick (~4Hz).
     * Handles path computation, caching, recomputation when target moves,
     * and returns the next waypoint the bot should walk toward.
     *
     * @return the next waypoint to walk toward, or null if already at target / no path
     */
    public @Nullable Vec3d getNextWaypoint(BlockPos target) {
        ticksSinceLastCompute += TASK_TICK_INTERVAL;

        boolean needsRecompute = false;

        // Recompute if target changed
        if (currentTarget == null || !currentTarget.equals(target)) {
            needsRecompute = true;
        }

        // Recompute periodically (target may have moved, or we got stuck)
        if (ticksSinceLastCompute >= RECOMPUTE_INTERVAL_TICKS) {
            needsRecompute = true;
        }

        // Recompute if we finished or lost our path
        if (currentWaypoints == null || currentWaypointIndex >= currentWaypoints.size()) {
            needsRecompute = true;
        }

        if (needsRecompute) {
            currentWaypoints = computePath(target);
            currentWaypointIndex = 0;
            currentTarget = target;
            ticksSinceLastCompute = 0;

            if (currentWaypoints == null || currentWaypoints.isEmpty()) {
                return null;
            }
        }

        // Advance past waypoints we've already reached
        Vec3d botPos = bot.getPos();
        while (currentWaypointIndex < currentWaypoints.size()) {
            Vec3d waypoint = currentWaypoints.get(currentWaypointIndex);
            double horizontalDist = Math.sqrt(
                    Math.pow(botPos.x - waypoint.x, 2) +
                    Math.pow(botPos.z - waypoint.z, 2)
            );
            // Consider waypoint reached if within ~0.7 blocks horizontally
            if (horizontalDist < 0.7) {
                currentWaypointIndex++;
            } else {
                break;
            }
        }

        if (currentWaypointIndex >= currentWaypoints.size()) {
            return null; // reached end of path
        }

        return currentWaypoints.get(currentWaypointIndex);
    }

    /**
     * Clear any cached path. Call when switching tasks or stopping movement.
     */
    public void clearPath() {
        currentWaypoints = null;
        currentWaypointIndex = 0;
        currentTarget = null;
        ticksSinceLastCompute = 0;
    }

    /**
     * @return true if we have an active path with waypoints remaining
     */
    public boolean hasPath() {
        return currentWaypoints != null && currentWaypointIndex < currentWaypoints.size();
    }

    /**
     * Clean up. Call when the bot is destroyed.
     */
    public void destroy() {
        clearPath();
    }

    // ==================== Internal Types ====================

    /** A* node for the priority queue. */
    private static class AStarNode {
        final BlockPos pos;
        final double gScore;
        final double fScore;

        AStarNode(BlockPos pos, double gScore, double fScore) {
            this.pos = pos;
            this.gScore = gScore;
            this.fScore = fScore;
        }
    }

    /** A neighbor position with its movement cost. */
    private static class Neighbor {
        final BlockPos pos;
        final double cost;

        Neighbor(BlockPos pos, double cost) {
            this.pos = pos;
            this.cost = cost;
        }
    }
}
