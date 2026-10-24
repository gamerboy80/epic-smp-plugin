package com.epicsmp;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Combat attribution, progression lifecycle, protection, and potion limits. */
public final class GameplayListener implements Listener {
  private final EpicSmpPlugin plugin;
  private final Set<UUID> loggingOut = new HashSet<>();

  /** Creates listeners bound to this plugin's main-thread services. */
  GameplayListener(EpicSmpPlugin plugin) {
    this.plugin = plugin;
  }

  /** Resolves direct attacks, projectiles, owned pets, and player-ignited TNT. */
  public static Player attacker(Entity damager) {
    if (damager instanceof Player player) {
      return player;
    }
    if (damager instanceof Projectile projectile
        && projectile.getShooter() instanceof Player player) {
      return player;
    }
    if (damager instanceof Tameable pet && pet.getOwner() instanceof Player player) {
      return player;
    }
    if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player) {
      return player;
    }
    return null;
  }

  private static Player deathAttacker(PlayerDeathEvent event) {
    Player direct = event.getEntity().getKiller();
    if (direct != null) {
      return direct;
    }
    Entity source = event.getDamageSource().getCausingEntity();
    return source == null ? null : attacker(source);
  }

  /** Loads the profile and modifiers on login. */
  @EventHandler
  public void join(PlayerJoinEvent event) {
    plugin.initialize(event.getPlayer());
  }

  /** Reapplies modifiers before the client receives the respawn health state. */
  @EventHandler
  public void respawn(PlayerRespawnEvent event) {
    Player player = event.getPlayer();
    plugin.refresh(player);
    Bukkit.getScheduler().runTask(plugin, () -> plugin.refresh(player));
  }

  /** Removes competitive attribute bonuses in excluded worlds. */
  @EventHandler
  public void world(PlayerChangedWorldEvent event) {
    plugin.combat().clear(event.getPlayer().getUniqueId());
    plugin.refresh(event.getPlayer());
  }

  /** Reapplies competitive bonuses when entering or leaving spectator/creative mode. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void mode(PlayerGameModeChangeEvent event) {
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              plugin.combat().clear(event.getPlayer().getUniqueId());
              plugin.refresh(event.getPlayer());
            });
  }

  /** Cancels damage for spawn-safe and temporarily protected players. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void protection(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player victim
        && plugin.active(victim)
        && (plugin.safe(victim.getLocation()) || plugin.protectedPlayer(victim))) {
      event.setCancelled(true);
    }
  }

  /** Enforces PvP settings and bounds final incoming damage. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void pvp(EntityDamageByEntityEvent event) {
    if (!(event.getEntity() instanceof Player victim)) {
      return;
    }
    Player attacker = attacker(event.getDamager());
    if (attacker == null || attacker.equals(victim)) {
      return;
    }
    if (!plugin.active(victim) || !plugin.active(attacker)) {
      return;
    }
    if (plugin.paused()
        || !plugin.settings().flag("rules.pvp")
        || plugin.protectedPlayer(attacker)
        || plugin.safe(attacker.getLocation())) {
      event.setCancelled(true);
      attacker.sendActionBar(EpicSmpPlugin.message("PvP unavailable or protected player/area."));
      return;
    }
    double limit = plugin.settings().decimal("rules.maximum-pvp-damage");
    if (limit > 0 && event.getFinalDamage() > limit) {
      // Damage modifiers are recalculated when base damage changes. Search for a capped base.
      double low = 0;
      double high = event.getDamage();
      for (int i = 0; i < 24; i++) {
        double mid = (low + high) / 2;
        event.setDamage(mid);
        if (event.getFinalDamage() > limit) {
          high = mid;
        } else {
          low = mid;
        }
      }
      event.setDamage(low);
    }
  }

  /** Tags only attacks that survived all cancellation and damage rules. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void tag(EntityDamageByEntityEvent event) {
    if (!(event.getEntity() instanceof Player victim) || event.getFinalDamage() <= 0) {
      return;
    }
    Player attacker = attacker(event.getDamager());
    if (attacker != null
        && !attacker.equals(victim)
        && plugin.active(victim)
        && plugin.active(attacker)) {
      plugin
          .combat()
          .hit(
              attacker.getUniqueId(),
              victim.getUniqueId(),
              System.currentTimeMillis(),
              plugin.settings().integer("combat.tag-seconds"));
      plugin.combatRestrictions(attacker);
      plugin.combatRestrictions(victim);
    }
  }

  /** Hides invisible killers in public death messages while preserving silent death events. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void anonymousDeath(PlayerDeathEvent event) {
    if (!plugin.settings().flag("display.hide-invisible-killers") || event.deathMessage() == null) {
      return;
    }
    Player killer = deathAttacker(event);
    if (killer == null && event.getDamageSource().getCausingEntity() instanceof Player source) {
      killer = source;
    }
    if (killer == null && plugin.settings().flag("combat.credit-tagged-deaths")) {
      CombatTracker.Tag tag =
          plugin.combat().tag(event.getEntity().getUniqueId(), System.currentTimeMillis());
      killer = tag == null ? null : Bukkit.getPlayer(tag.opponent());
    }
    if (killer != null
        && (killer.isInvisible() || killer.hasPotionEffect(PotionEffectType.INVISIBILITY))) {
      event.deathMessage(
          net.kyori.adventure.text.Component.text(event.getEntity().getName() + " was slain."));
    }
  }

  /** Applies one death transaction and updates every affected online player's Strength. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void death(PlayerDeathEvent event) {
    Player victim = event.getEntity();
    if (plugin.paused() || !plugin.active(victim)) {
      plugin.combat().clear(victim.getUniqueId());
      return;
    }
    long now = System.currentTimeMillis();
    CombatTracker.Tag tag = plugin.combat().tag(victim.getUniqueId(), now);
    Player direct = deathAttacker(event);
    UUID killer = direct != null && plugin.active(direct) ? direct.getUniqueId() : null;
    if (killer == null
        && tag != null
        && (plugin.settings().flag("combat.credit-tagged-deaths")
            || loggingOut.contains(victim.getUniqueId()))) {
      killer = tag.opponent();
    }
    plugin.combat().clear(victim.getUniqueId());
    try {
      Progression.DeathResult result =
          plugin.progression().death(victim.getUniqueId(), killer, now);
      if (result.protectedKill()) {
        victim.sendMessage(EpicSmpPlugin.message("Repeat-pair protection: progression unchanged."));
      }
      for (ItemToken.Kind token : result.drops()) {
        // Spawn these separately so keepInventory and other death-drop rules cannot erase them.
        victim.getWorld().dropItemNaturally(victim.getLocation(), plugin.items().item(token));
      }
      plugin.abilities().clear(victim);
      plugin.refreshAll();
    } catch (IOException e) {
      plugin.storageFailure(e);
    }
  }

  /** Treats a tagged disconnect as a real death, preserving vanilla drops and keepInventory. */
  @EventHandler
  public void quit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    if (!Bukkit.isStopping()
        && !plugin.paused()
        && plugin.active(player)
        && !player.isDead()
        && plugin.settings().flag("combat.punish-logout")
        && plugin.combat().tag(player.getUniqueId(), System.currentTimeMillis()) != null) {
      loggingOut.add(player.getUniqueId());
      try {
        player.setHealth(0);
      } finally {
        loggingOut.remove(player.getUniqueId());
      }
    }
    plugin.combat().clear(player.getUniqueId());
    plugin.abilities().clear(player);
    plugin.forgetNotices(player);
  }

  /** Prevents tagged players crossing into spawn protection. */
  @EventHandler(ignoreCancelled = true)
  public void move(PlayerMoveEvent event) {
    if (event instanceof PlayerTeleportEvent || !event.hasChangedPosition()) {
      return;
    }
    if (blockedEntry(event)) {
      event.setCancelled(true);
      event
          .getPlayer()
          .sendActionBar(EpicSmpPlugin.message("You cannot enter spawn during combat."));
    }
  }

  /** Also blocks pearls, chorus fruit and other plugin teleports into spawn while tagged. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void teleport(PlayerTeleportEvent event) {
    if (blockedEntry(event)) {
      event.setCancelled(true);
    }
  }

  private boolean blockedEntry(PlayerMoveEvent event) {
    if (!plugin.active(event.getPlayer())
        || plugin.combat().tag(event.getPlayer().getUniqueId(), System.currentTimeMillis())
            == null) {
      return false;
    }
    return plugin.safe(event.getTo()) && !plugin.safe(event.getFrom())
        || plugin.settings().flag("combat.block-world-change")
            && !java.util.Objects.equals(event.getFrom().getWorld(), event.getTo().getWorld());
  }

  /** Caps persistent potion levels without changing their duration. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void potion(EntityPotionEffectEvent event) {
    if (!(event.getEntity() instanceof Player player)) {
      return;
    }
    PotionEffect effect = event.getNewEffect();
    boolean applied =
        event.getAction() == EntityPotionEffectEvent.Action.ADDED
            || event.getAction() == EntityPotionEffectEvent.Action.CHANGED && event.isOverride();
    if (!applied || effect == null) {
      return;
    }
    int cap =
        player.hasPermission("epicsmp.bypass")
            ? -1
            : plugin.settings().potionLimit(effect.getType());
    if (cap == 0) {
      event.setCancelled(true);
      return;
    }
    int amplifier = cap < 0 ? effect.getAmplifier() : Math.min(effect.getAmplifier(), cap - 1);
    int duration = effect.getDuration();
    if (duration == effect.getDuration() && amplifier == effect.getAmplifier()) {
      return;
    }
    event.setCancelled(true);
    // The capped replacement no longer exceeds the limit, preventing recursion.
    player.addPotionEffect(
        new PotionEffect(
            effect.getType(),
            duration,
            amplifier,
            effect.isAmbient(),
            effect.hasParticles(),
            effect.hasIcon()));
  }
}
