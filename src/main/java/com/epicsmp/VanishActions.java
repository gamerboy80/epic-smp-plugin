package com.epicsmp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived, world-specific block actions shared safely with the packet thread. */
final class VanishActions {
  private record Block(UUID world, int x, int y, int z) {}

  private record Action(UUID actor, long expires) {}

  private final Map<Block, Action> blocks = new ConcurrentHashMap<>();
  private volatile Map<UUID, UUID> worlds = Map.of();

  void worlds(Map<UUID, UUID> next) {
    worlds = Map.copyOf(next);
  }

  void block(UUID actor, UUID world, int x, int y, int z) {
    long now = System.nanoTime();
    blocks.entrySet().removeIf(entry -> entry.getValue().expires() <= now);
    blocks.put(new Block(world, x, y, z), new Action(actor, now + 400_000_000L));
  }

  boolean silent(UUID viewer, double x, double y, double z) {
    UUID world = worlds.get(viewer);
    if (world == null) {
      return false;
    }
    Action action =
        blocks.get(new Block(world, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
    return action != null && !action.actor().equals(viewer) && action.expires() > System.nanoTime();
  }
}
