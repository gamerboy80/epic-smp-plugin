package com.epicsmp;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.event.entity.EntityLungeEvent;
import java.util.ArrayList;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/** Additional competitive SMP limits, with no item deletion or forced inventory drops. */
public final class LimitListener implements Listener {
  static final String LUNGE = "SPEAR_LUNGE";
  static final String MACE = "MACE_HIT";
  private final EpicSmpPlugin plugin;

  /** Creates limits and periodically normalizes effects imported by other plugins. */
  LimitListener(EpicSmpPlugin plugin) {
    this.plugin = plugin;
    Bukkit.getScheduler()
        .runTaskTimer(
            plugin,
            () -> {
              for (Player player : Bukkit.getOnlinePlayers()) {
                if (bypass(player)) {
                  continue;
                }
                for (PotionEffect effect : new ArrayList<>(player.getActivePotionEffects())) {
                  int cap = plugin.settings().potionLimit(effect.getType());
                  if (cap >= 0 && effect.getAmplifier() >= cap) {
                    player.removePotionEffect(effect.getType());
                    if (cap > 0) {
                      player.addPotionEffect(
                          new PotionEffect(
                              effect.getType(),
                              effect.getDuration(),
                              cap - 1,
                              effect.isAmbient(),
                              effect.hasParticles(),
                              effect.hasIcon()));
                    }
                  }
                }
              }
            },
            20L,
            20L);
  }

  private boolean bypass(Player player) {
    return player.hasPermission("epicsmp.bypass");
  }

  private boolean cooling(Player player, String key) {
    if (bypass(player)) {
      return false;
    }
    long remaining =
        plugin.combat().remaining(player.getUniqueId(), key, System.currentTimeMillis());
    if (remaining <= 0) {
      return false;
    }
    player.sendActionBar(
        EpicSmpPlugin.message(key + " cooldown: " + (remaining + 999) / 1000 + "s"));
    return true;
  }

  /** Cancels only the forward Lunge, leaving normal spear attacks available. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void lunge(EntityLungeEvent event) {
    if (!(event.getEntity() instanceof Player player) || bypass(player)) {
      return;
    }
    int cap =
        plugin
            .settings()
            .enchantLimits()
            .getOrDefault(org.bukkit.enchantments.Enchantment.LUNGE.getKey(), Integer.MAX_VALUE);
    if (cap == 0 || cooling(player, LUNGE)) {
      event.setCancelled(true);
    } else {
      event.setLungePower(Math.min(event.getLungePower(), cap));
    }
  }

  /** Starts a single account-wide cooldown only after an accepted Lunge. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void lunged(EntityLungeEvent event) {
    if (event.getEntity() instanceof Player player && !bypass(player)) {
      plugin
          .combat()
          .cooldown(
              player.getUniqueId(),
              LUNGE,
              System.currentTimeMillis(),
              plugin.settings().integer("limits.spear-lunge-cooldown-seconds") * 20);
    }
  }

  /** Enforces shared mace timing and usable inventory limits before melee damage. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void hit(EntityDamageByEntityEvent event) {
    if (!melee(event)) {
      return;
    }
    if (event.getDamager() instanceof Player player && !bypass(player)) {
      Material weapon =
          player.getInventory().getItem(RuleListener.weaponHand(player, event)).getType();
      if (overLimit(player, weapon, 0) || weapon == Material.MACE && cooling(player, MACE)) {
        event.setCancelled(true);
      }
    }
  }

  /** Misses and cancelled hits do not charge the mace cooldown. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void hitAccepted(EntityDamageByEntityEvent event) {
    if (!melee(event)) {
      return;
    }
    if (event.getDamager() instanceof Player player
        && !bypass(player)
        && event.getFinalDamage() > 0
        && player.getInventory().getItem(RuleListener.weaponHand(player, event)).getType()
            == Material.MACE) {
      plugin
          .combat()
          .cooldown(
              player.getUniqueId(),
              MACE,
              System.currentTimeMillis(),
              plugin.settings().integer("limits.mace-hit-cooldown-seconds") * 20);
    }
  }

  private boolean melee(EntityDamageByEntityEvent event) {
    return event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
        || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
  }

  /** Caps final incoming explosion damage, including unattributed TNT minecarts. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void explosion(EntityDamageEvent event) {
    if (event.getEntity() instanceof Player player
        && !bypass(player)
        && (event.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
            || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION)) {
      double cap = plugin.settings().decimal("limits.maximum-explosion-damage");
      if (cap > 0 && event.getFinalDamage() > cap) {
        double low = 0;
        double high = event.getDamage();
        for (int i = 0; i < 24; i++) {
          double mid = (low + high) / 2;
          event.setDamage(mid);
          if (event.getFinalDamage() > cap) {
            high = mid;
          } else {
            low = mid;
          }
        }
        event.setDamage(low);
      }
    }
  }

  /** Locks season dimensions for portals and other teleports without trapping residents. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void dimension(PlayerTeleportEvent event) {
    World target = event.getTo().getWorld();
    if (!bypass(event.getPlayer())
        && target != null
        && !target.equals(event.getFrom().getWorld())
        && (target.getEnvironment() == World.Environment.NETHER
                && !plugin.settings().flag("limits.allow-nether")
            || target.getEnvironment() == World.Environment.THE_END
                && !plugin.settings().flag("limits.allow-end"))) {
      event.setCancelled(true);
      event.getPlayer().sendMessage(EpicSmpPlugin.message("That dimension is closed this season."));
    }
  }

  private boolean overLimit(Player player, Material material, int additional) {
    int cap = plugin.settings().inventoryLimit(material);
    if (bypass(player) || cap < 0) {
      return false;
    }
    int count = additional;
    ItemStack[] contents = player.getInventory().getContents();
    if (contents != null) {
      for (ItemStack item : contents) {
        if (item != null && item.getType() == material) {
          count += item.getAmount();
        }
      }
    }
    ItemStack cursor = player.getItemOnCursor();
    if (cursor != null && cursor.getType() == material) {
      count += cursor.getAmount();
    }
    if (count <= cap && cap != 0) {
      return false;
    }
    player.sendActionBar(EpicSmpPlugin.message("Inventory limit for " + material + ": " + cap));
    return true;
  }

  /** Leaves excess ground items on the ground instead of deleting them. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void pickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player) {
      ItemStack item = event.getItem().getItemStack();
      int accepted = Math.max(0, item.getAmount() - event.getRemaining());
      if (overLimit(player, item.getType(), accepted)) {
        event.setCancelled(true);
      }
    }
  }

  /** Blocks use of surplus carried items obtained through crafting, containers or admin grants. */
  @EventHandler(priority = EventPriority.HIGH)
  public void interact(PlayerInteractEvent event) {
    ItemStack item = event.getItem();
    if (item != null && overLimit(event.getPlayer(), item.getType(), 0)) {
      event.setUseItemInHand(Event.Result.DENY);
    }
  }

  /** Rechecks food at consumption so inventory changes cannot bypass a limit. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void consume(PlayerItemConsumeEvent event) {
    if (overLimit(event.getPlayer(), event.getItem().getType(), 0)) {
      event.setCancelled(true);
    }
  }

  /** Rechecks thrown-item limits at launch, including separate stacks and offhand use. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void launch(PlayerLaunchProjectileEvent event) {
    if (overLimit(event.getPlayer(), event.getItemStack().getType(), 0)) {
      event.setCancelled(true);
    }
  }

  /** Includes offhand totems in the carried total. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void resurrect(EntityResurrectEvent event) {
    if (event.getEntity() instanceof Player player
        && overLimit(player, Material.TOTEM_OF_UNDYING, 0)) {
      event.setCancelled(true);
    }
  }

  /** Controls tipped arrows and caps imported bow enchants before damage is created. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void bow(EntityShootBowEvent event) {
    if (event.getEntity() instanceof Player player && !bypass(player)) {
      ItemStack arrow = event.getConsumable();
      if (arrow != null
          && (overLimit(player, arrow.getType(), 0)
              || arrow.getType() == Material.TIPPED_ARROW
                  && !plugin.settings().flag("limits.allow-tipped-arrows"))) {
        event.setCancelled(true);
      }
      ItemStack bow = event.getBow();
      if (RuleListener.sanitize(plugin.settings(), bow)) {
        if (bow != null) {
          player.getInventory().setItem(event.getHand(), bow);
        }
        event.setCancelled(true);
      }
    }
  }
}
