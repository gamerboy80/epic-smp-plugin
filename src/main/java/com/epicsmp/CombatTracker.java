package com.epicsmp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Runtime combat tags; cooldown checks use wall time and remain active across reconnects. */
public final class CombatTracker {
  private final Map<UUID, Tag> tags = new HashMap<>();
  private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();

  /** Tags both participants, crediting the most recent opponent. */
  public void hit(UUID attacker, UUID victim, long now, int seconds) {
    tags.put(attacker, new Tag(victim, now + seconds * 1000L));
    tags.put(victim, new Tag(attacker, now + seconds * 1000L));
  }

  /** Returns an active tag, expiring old entries. */
  public Tag tag(UUID player, long now) {
    Tag tag = tags.get(player);
    if (tag != null && tag.until() <= now) {
      tags.remove(player);
      return null;
    }
    return tag;
  }

  /** Clears tags on death or an administrative pause. */
  public void clear(UUID player) {
    tags.remove(player);
  }

  /** Removes all runtime tags during a maintenance pause. */
  public void clearAll() {
    tags.clear();
  }

  /** Returns the remaining plugin cooldown in milliseconds. */
  public long remaining(UUID player, String item, long now) {
    Map<String, Long> items = cooldowns.get(player);
    if (items == null) {
      return 0;
    }
    long until = items.getOrDefault(item, 0L);
    if (until <= now) {
      items.remove(item);
      if (items.isEmpty()) {
        cooldowns.remove(player);
      }
      return 0;
    }
    return until - now;
  }

  /** Starts a cooldown after a successful use, never shortening an existing plugin cooldown. */
  public void cooldown(UUID player, String item, long now, int ticks) {
    cooldowns
        .computeIfAbsent(player, ignored -> new HashMap<>())
        .merge(item, now + ticks * 50L, Math::max);
  }

  /** Clears one administrative test cooldown, or all when item is null. */
  public void clearCooldowns(UUID player, String item) {
    if (item == null) {
      cooldowns.remove(player);
    } else {
      Map<String, Long> items = cooldowns.get(player);
      if (items != null) {
        items.remove(item);
        if (items.isEmpty()) {
          cooldowns.remove(player);
        }
      }
    }
  }

  /** Last opponent and expiry timestamp. */
  public record Tag(UUID opponent, long until) {}
}
