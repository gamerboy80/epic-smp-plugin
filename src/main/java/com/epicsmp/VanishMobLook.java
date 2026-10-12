package com.epicsmp;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import java.lang.ref.WeakReference;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

/** Overrides passive player-looking goals near vanished staff without disabling mob AI. */
final class VanishMobLook implements Goal<Mob> {
  private final EpicSmpPlugin plugin;
  private final VanishService vanish;
  private final WeakReference<Mob> reference;
  private final GoalKey<Mob> key;

  VanishMobLook(EpicSmpPlugin plugin, VanishService vanish, Mob mob) {
    this.plugin = plugin;
    this.vanish = vanish;
    reference = new WeakReference<>(mob);
    key = GoalKey.of(Mob.class, new NamespacedKey(plugin, "vanish_look"));
  }

  @Override
  public boolean shouldActivate() {
    Mob mob = reference.get();
    return mob != null
        && mob.isValid()
        && mob.getTarget() == null
        && plugin.settings().flag("vanish.prevent-mob-looking")
        && !vanish.view().hidden().isEmpty()
        && players(mob).stream().anyMatch(player -> vanish.isVanished(player.getUniqueId()));
  }

  @Override
  public boolean shouldStayActive() {
    return shouldActivate();
  }

  @Override
  public void tick() {
    Mob mob = reference.get();
    if (mob == null) {
      return;
    }
    Location position = mob.getLocation();
    Player visible =
        players(mob).stream()
            .filter(player -> !vanish.isVanished(player.getUniqueId()))
            .min(
                Comparator.comparingDouble(
                    player -> player.getLocation().distanceSquared(position)))
            .orElse(null);
    if (visible != null) {
      mob.lookAt(visible);
    } else {
      mob.lookAt(position.clone().add(position.getDirection().multiply(8)));
    }
  }

  private static List<Player> players(Mob mob) {
    return mob.getNearbyEntities(16, 16, 16).stream()
        .filter(Player.class::isInstance)
        .map(Player.class::cast)
        .toList();
  }

  @Override
  public GoalKey<Mob> getKey() {
    return key;
  }

  @Override
  public EnumSet<GoalType> getTypes() {
    return EnumSet.of(GoalType.LOOK);
  }
}
