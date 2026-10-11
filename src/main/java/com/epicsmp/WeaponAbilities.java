package com.epicsmp;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/** Season Four specialty abilities, composed with ordinary server damage and combat limits. */
public final class WeaponAbilities implements Listener {
  private static final String ULTIMATE = "WEAPON_ULTIMATE";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final EpicSmpPlugin plugin;
  private final NamespacedKey speedKey;
  private final NamespacedKey projectileKey;
  private final Map<UUID, Runtime> runtime = new HashMap<>();
  private final Map<EntityDamageByEntityEvent, Hit> pending = new IdentityHashMap<>();
  private long tick;

  /** Creates ability state and the owned-modifier lifecycle task. */
  WeaponAbilities(EpicSmpPlugin plugin) {
    this.plugin = plugin;
    speedKey = new NamespacedKey(plugin, "sword_speed");
    projectileKey = new NamespacedKey(plugin, "weapon_projectile");
    Bukkit.getScheduler().runTaskTimer(plugin, this::pulse, 1L, 1L);
  }

  private Runtime state(Player player) {
    return runtime.computeIfAbsent(player.getUniqueId(), ignored -> new Runtime());
  }

  private boolean eligible(Player player, int threshold) {
    Profile profile = plugin.progression().profile(player.getUniqueId());
    return profile != null
        && profile.strength() >= threshold
        && plugin.settings().flag("abilities.enabled")
        && !plugin.paused()
        && plugin.active(player)
        && !player.isDead()
        && !plugin.protectedPlayer(player)
        && !plugin.safe(player.getLocation());
  }

  private boolean passive(Player player) {
    return eligible(player, plugin.settings().integer("abilities.passive-strength"));
  }

  private boolean ultimate(Player player) {
    return eligible(player, plugin.settings().integer("abilities.ultimate-strength"));
  }

  private boolean ready(Player player) {
    return plugin.combat().remaining(player.getUniqueId(), ULTIMATE, System.currentTimeMillis())
        == 0;
  }

  private void charge(Player player, WeaponKind weapon) {
    plugin
        .combat()
        .cooldown(
            player.getUniqueId(),
            ULTIMATE,
            System.currentTimeMillis(),
            plugin
                    .settings()
                    .integer(
                        "abilities.cooldowns." + weapon.name().toLowerCase(java.util.Locale.ROOT))
                * 20);
  }

  private WeaponKind specialty(Player player) {
    return plugin.progression().profile(player.getUniqueId()).weapon();
  }

  private boolean holding(Player player, WeaponKind weapon) {
    return weapon.matches(player.getInventory().getItemInMainHand().getType())
        || weapon == WeaponKind.SHIELD
            && weapon.matches(player.getInventory().getItemInOffHand().getType());
  }

  /** Activates the held specialty; ranged ultimates trigger on their tenth accepted hit. */
  public void activate(Player player) {
    if (!ultimate(player)) {
      throw new IllegalArgumentException(
          "Ultimate requires Strength "
              + plugin.settings().integer("abilities.ultimate-strength")
              + " and active play outside protection");
    }
    WeaponKind weapon = specialty(player);
    if (!holding(player, weapon)) {
      throw new IllegalArgumentException("Hold your specialty weapon: " + weapon);
    }
    if (weapon == WeaponKind.BOW || weapon == WeaponKind.CROSSBOW) {
      throw new IllegalArgumentException(
          "Your ranged ultimate triggers automatically on the tenth accepted hit");
    }
    if (!ready(player)) {
      throw new IllegalArgumentException(
          "Ultimate cooldown: "
              + (plugin
                          .combat()
                          .remaining(player.getUniqueId(), ULTIMATE, System.currentTimeMillis())
                      + 999)
                  / 1000
              + "s");
    }
    Runtime state = state(player);
    int seconds;
    switch (weapon) {
      case SWORD -> {
        if (state.swordCombo < 5
            || !WeaponKind.SWORD.matches(player.getInventory().getItemInOffHand().getType())) {
          throw new IllegalArgumentException(
              "Land a five-hit sword combo and equip a second sword in your offhand");
        }
        seconds = 15;
      }
      case AXE -> seconds = 5;
      case TRIDENT -> seconds = 5;
      case SHIELD -> {
        if (!plugin.settings().flag("abilities.shield-invulnerability")) {
          throw new IllegalArgumentException("Shield invulnerability is disabled");
        }
        seconds = 15;
      }
      default -> throw new IllegalArgumentException("Unknown active ultimate");
    }
    state.ultimate = weapon;
    state.until = tick + seconds * 20L;
    charge(player, weapon);
    player.sendMessage(EpicSmpPlugin.message(weapon + " ultimate active for " + seconds + "s."));
  }

  /**
   * Stamps accepted bow/crossbow launches so switching held items cannot change arrow abilities.
   */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void shoot(EntityShootBowEvent event) {
    ItemStack bow = event.getBow();
    if (event.getEntity() instanceof Player player && passive(player) && bow != null) {
      WeaponKind weapon = specialty(player);
      if ((weapon == WeaponKind.BOW || weapon == WeaponKind.CROSSBOW)
          && weapon.matches(bow.getType())) {
        event
            .getProjectile()
            .getPersistentDataContainer()
            .set(projectileKey, PersistentDataType.STRING, weapon.name());
      }
    }
  }

  /** Stamps thrown tridents as well as melee use. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void launch(ProjectileLaunchEvent event) {
    if (event.getEntity() instanceof Trident trident
        && trident.getShooter() instanceof Player player
        && passive(player)
        && specialty(player) == WeaponKind.TRIDENT) {
      trident
          .getPersistentDataContainer()
          .set(projectileKey, PersistentDataType.STRING, WeaponKind.TRIDENT.name());
    }
  }

  private WeaponKind used(EntityDamageByEntityEvent event, Player player) {
    if (event.getDamager() instanceof Projectile projectile) {
      String kind =
          projectile.getPersistentDataContainer().get(projectileKey, PersistentDataType.STRING);
      return kind == null ? null : WeaponKind.valueOf(kind);
    }
    if (event.getDamager() == player
        && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
            || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
      WeaponKind weapon = specialty(player);
      return weapon.matches(
              player.getInventory().getItem(RuleListener.weaponHand(player, event)).getType())
          ? weapon
          : null;
    }
    return null;
  }

  /** Plans damage bonuses before the final PvP cap; only accepted hits advance counters. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void damage(EntityDamageByEntityEvent event) {
    if (!(event.getEntity() instanceof Player victim)) {
      return;
    }
    Player attacker = GameplayListener.attacker(event.getDamager());
    if (attacker == null
        || attacker == victim
        || !passive(attacker)
        || !plugin.active(victim)
        || plugin.safe(victim.getLocation())
        || plugin.protectedPlayer(victim)
        || !plugin.settings().flag("rules.pvp")) {
      return;
    }
    Runtime state = state(attacker);
    if (state.stunnedUntil > tick) {
      event.setCancelled(true);
      return;
    }
    WeaponKind weapon = used(event, attacker);
    if (weapon == null || weapon != specialty(attacker)) {
      return;
    }
    if (tick - state.lastSwordTick > 60 || !victim.getUniqueId().equals(state.comboTarget)) {
      state.swordCombo = 0;
    }
    int count = weapon == WeaponKind.BOW ? state.bowHits + 1 : state.crossbowHits + 1;
    boolean proc =
        (weapon == WeaponKind.BOW || weapon == WeaponKind.CROSSBOW)
            && count % 10 == 0
            && ultimate(attacker)
            && ready(attacker);
    double multiplier = 1;
    switch (weapon) {
      case SWORD -> {
        if (state.swordCombo + 1 >= 3 && !event.isCritical()) {
          multiplier = 1.5;
        }
      }
      case AXE -> {
        if (state.ultimate == WeaponKind.AXE
            && state.until > tick
            && ultimate(attacker)
            && event.isCritical()) {
          multiplier = 2;
        }
      }
      case BOW -> multiplier = proc ? 3 : count % 3 == 0 ? 2 : 1;
      case CROSSBOW -> multiplier = proc ? 4 : 1;
      case TRIDENT -> {
        if (state.lightningUntil <= tick) {
          event.setDamage(
              event.getDamage() + plugin.settings().decimal("abilities.trident-lightning-damage"));
        }
      }
      default -> {}
    }
    event.setDamage(event.getDamage() * multiplier);
    pending.put(event, new Hit(attacker, victim, weapon, proc, event.isCritical()));
  }

  /** Commits combo and cooldown changes only after all cancellation and damage-cap decisions. */
  @EventHandler(priority = EventPriority.MONITOR)
  public void accepted(EntityDamageByEntityEvent event) {
    Hit hit = pending.remove(event);
    if (hit == null || event.isCancelled() || event.getFinalDamage() <= 0) {
      return;
    }
    Runtime state = state(hit.attacker());
    state.lastHitNanos = System.nanoTime();
    if (hit.proc()) {
      charge(hit.attacker(), hit.weapon());
    }
    switch (hit.weapon()) {
      case SWORD -> {
        state.swordCombo++;
        state.lastSwordTick = tick;
        state.comboTarget = hit.victim().getUniqueId();
        if (state.ultimate == WeaponKind.SWORD && state.until > tick) {
          hit.attacker().swingOffHand();
        }
      }
      case AXE -> {
        if (hit.critical() && ++state.axeCrits % 5 == 0) {
          state(hit.victim()).stunnedUntil = tick + 20;
          hit.victim().sendMessage(EpicSmpPlugin.message("Stunned for 1s."));
        }
      }
      case BOW -> state.bowHits++;
      case CROSSBOW -> {
        state.crossbowHits++;
        if (RANDOM.nextDouble() < plugin.settings().decimal("abilities.crossbow-pull-chance")) {
          Vector pull =
              hit.attacker()
                  .getLocation()
                  .toVector()
                  .subtract(hit.victim().getLocation().toVector());
          if (pull.lengthSquared() > 0) {
            hit.victim().setVelocity(pull.normalize().multiply(0.6).setY(0.2));
          }
        }
        if (state.crossbowHits % 3 == 0) {
          for (Entity entity : hit.attacker().getNearbyEntities(32, 16, 32)) {
            if (entity instanceof LivingEntity living && entity != hit.attacker()) {
              living.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 100, 0));
            }
          }
        }
      }
      case TRIDENT -> {
        if (state.lightningUntil <= tick) {
          state.lightningUntil = tick + 40;
          hit.victim().getWorld().strikeLightningEffect(hit.victim().getLocation());
        }
      }
      default -> {}
    }
    if (event.getDamager() instanceof Projectile projectile) {
      projectile.getPersistentDataContainer().remove(projectileKey);
    }
  }

  /** A sword swing that hits nothing resets the combo, including cancelled attacks. */
  @EventHandler(ignoreCancelled = true)
  public void swing(PlayerArmSwingEvent event) {
    Player player = event.getPlayer();
    if (!passive(player)
        || specialty(player) != WeaponKind.SWORD
        || !holding(player, WeaponKind.SWORD)) {
      return;
    }
    long started = System.nanoTime();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              Runtime state = runtime.get(player.getUniqueId());
              if (state != null && state.lastHitNanos < started) {
                state.swordCombo = 0;
              }
            });
  }

  /** Shield-disable attacks unlock five seconds of damage reduction. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void shield(PlayerShieldDisableEvent event) {
    if (passive(event.getPlayer()) && specialty(event.getPlayer()) == WeaponKind.SHIELD) {
      state(event.getPlayer()).shieldUntil = tick + 100;
    }
  }

  /** Shield defenses use damage events, never persistent entity invulnerability. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void defense(EntityDamageEvent event) {
    if (!(event.getEntity() instanceof Player player)) {
      return;
    }
    Runtime state = runtime.get(player.getUniqueId());
    if (state == null || !passive(player) || specialty(player) != WeaponKind.SHIELD) {
      return;
    }
    if (state.ultimate == WeaponKind.SHIELD
        && state.until > tick
        && ultimate(player)
        && plugin.settings().flag("abilities.shield-invulnerability")) {
      event.setCancelled(true);
    } else if (state.shieldUntil > tick) {
      event.setDamage(event.getDamage() * 0.75);
    }
  }

  /** Freezes position for an axe stun while allowing looking and external teleports. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void move(PlayerMoveEvent event) {
    Runtime state = runtime.get(event.getPlayer().getUniqueId());
    if (state != null
        && state.stunnedUntil > tick
        && !(event instanceof PlayerTeleportEvent)
        && event.hasChangedPosition()) {
      org.bukkit.Location target = event.getFrom().clone();
      target.setYaw(event.getTo().getYaw());
      target.setPitch(event.getTo().getPitch());
      event.setTo(target);
    }
  }

  private void pulse() {
    tick++;
    for (Player player : Bukkit.getOnlinePlayers()) {
      Runtime state = runtime.get(player.getUniqueId());
      if (state == null) {
        continue;
      }
      if (!ultimate(player) || state.until <= tick || state.ultimate != specialty(player)) {
        state.ultimate = null;
      }
      boolean swords =
          state.ultimate == WeaponKind.SWORD
              && holding(player, WeaponKind.SWORD)
              && WeaponKind.SWORD.matches(player.getInventory().getItemInOffHand().getType());
      speed(player, swords);
      if (state.ultimate == WeaponKind.TRIDENT && holding(player, WeaponKind.TRIDENT)) {
        Vector direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() > 0) {
          player.setVelocity(direction.normalize().multiply(0.8).setY(0.15));
        }
        for (Entity nearby : player.getNearbyEntities(3, 2, 3)) {
          if (!(nearby instanceof LivingEntity) || nearby == player) {
            continue;
          }
          if (nearby instanceof Player target
              && (!plugin.active(target)
                  || plugin.safe(target.getLocation())
                  || plugin.protectedPlayer(target)
                  || !plugin.settings().flag("rules.pvp"))) {
            continue;
          }
          Vector away = nearby.getLocation().toVector().subtract(player.getLocation().toVector());
          if (away.lengthSquared() > 0) {
            nearby.setVelocity(away.normalize().multiply(0.8).setY(0.25));
          }
        }
      }
    }
  }

  private void speed(Player player, boolean active) {
    AttributeInstance instance = player.getAttribute(Attribute.ATTACK_SPEED);
    if (instance == null) {
      return;
    }
    AttributeModifier existing = instance.getModifier(speedKey);
    if (active && existing == null) {
      instance.addTransientModifier(
          new AttributeModifier(speedKey, 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    } else if (!active && existing != null) {
      instance.removeModifier(existing);
    }
  }

  /** Ends transient effects and counters without resetting the shared ultimate cooldown. */
  public void clear(Player player) {
    runtime.remove(player.getUniqueId());
    speed(player, false);
  }

  /** Releases only this plugin's owned ability modifiers. */
  public void clearAll() {
    Bukkit.getOnlinePlayers().forEach(this::clear);
    runtime.clear();
    pending.clear();
  }

  private record Hit(
      Player attacker, Player victim, WeaponKind weapon, boolean proc, boolean critical) {}

  private static final class Runtime {
    private int swordCombo;
    private int axeCrits;
    private int bowHits;
    private int crossbowHits;
    private UUID comboTarget;
    private long lastSwordTick;
    private long lastHitNanos;
    private long stunnedUntil;
    private long shieldUntil;
    private long lightningUntil;
    private long until;
    private WeaponKind ultimate;
  }
}
