package nl.requios.effortlessbuilding.utilities;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import nl.requios.effortlessbuilding.config.ServerConfig;

import java.util.*;

/**
 * Spreads big builds over several server ticks: block changes are queued per player and done
 * {@link ServerConfig#blocksPerTick} at a time, with progress on the action bar. Items are paid when the
 * build is submitted; only the world changes wait. With 0 blocks per tick everything happens at once.
 */
public final class BuildQueue {

    private static final class Job {
        final Deque<Runnable> steps = new ArrayDeque<>();
        int total;
        int done;
    }

    private static final Map<UUID, Job> jobs = new HashMap<>();

    private BuildQueue() {}

    /** Runs the change now, or queues it behind this player's earlier ones. */
    public static void submit(ServerPlayer player, Runnable change) {
        Job job = jobs.get(player.getUUID());
        if (ServerConfig.INSTANCE.blocksPerTick <= 0 && job == null) {
            change.run();
            return;
        }
        if (job == null) jobs.put(player.getUUID(), job = new Job());
        job.steps.add(change);
        job.total++;
    }

    /** Called every server tick by the loaders. */
    public static void tick(MinecraftServer server) {
        if (jobs.isEmpty()) return;
        int budget = Math.max(1, ServerConfig.INSTANCE.blocksPerTick);
        Iterator<Map.Entry<UUID, Job>> it = jobs.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            Job job = e.getValue();
            for (int i = 0; i < budget && !job.steps.isEmpty(); i++) {
                job.steps.poll().run();
                job.done++;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (job.steps.isEmpty()) {
                it.remove();
                if (player != null && job.total > budget) {
                    player.displayClientMessage(Component.translatable("effortlessbuilding.message.build_done", job.total), true);
                }
            } else if (player != null && server.getTickCount() % 10 == 0) {
                int percent = (int) (100L * job.done / Math.max(1, job.total));
                player.displayClientMessage(Component.translatable("effortlessbuilding.message.building", percent, job.done, job.total), true);
            }
        }
    }

    /** Finishes the player's queued changes right now (before undo, or when they leave). */
    public static void finish(UUID player) {
        Job job = jobs.remove(player);
        if (job == null) return;
        while (!job.steps.isEmpty()) job.steps.poll().run();
    }

    public static boolean isBuilding(UUID player) {
        return jobs.containsKey(player);
    }
}
