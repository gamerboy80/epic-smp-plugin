package com.epicsmp;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper 26.2 entry point for Strength SMP and combat management. */
public class EpicSmpPlugin extends JavaPlugin {
  private final CombatTracker combat = new CombatTracker();
  private final Map<UUID, Integer> notices = new HashMap<>();
  private SmpItems items;
  private WeaponAbilities abilities;
  private Settings settings;
  private Progression progression;
  private NamespacedKey strengthKey;
  private boolean paused;
  private boolean storageFailed;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    strengthKey = new NamespacedKey(this, "strength");
    try {
      settings = readSettings();
      progression =
          new Progression(
              new YamlStateStore(getDataFolder().toPath().resolve("players.yml")), settings);
    } catch (IOException | IllegalArgumentException e) {
      getLogger().severe("Cannot initialize EpicSmpPlugin: " + e.getMessage());
      Bukkit.getPluginManager().disablePlugin(this);
      return;
    }
    getServer().getPluginManager().registerEvents(new GameplayListener(this), this);
    getServer().getPluginManager().registerEvents(new RuleListener(this), this);
    getServer().getPluginManager().registerEvents(new LimitListener(this), this);
    getServer().getPluginManager().registerEvents(new AdminDashboard(this), this);
    items = new SmpItems(this);
    abilities = new WeaponAbilities(this);
    getServer().getPluginManager().registerEvents(items, this);
    getServer().getPluginManager().registerEvents(abilities, this);
    items.recipes();
    registerCommand("smp", "Strength SMP management", List.of("epicsmp"), new SmpCommand(this));
    for (Player player : Bukkit.getOnlinePlayers()) {
      initialize(player);
    }
    getLogger().info("Strength SMP ready for Paper 26.2.");
  }

  @Override
  public void onDisable() {
    combat.clearAll();
    notices.clear();
    if (items != null) {
      items.clearRecipes();
    }
    if (abilities != null) {
      abilities.clearAll();
    }
    if (strengthKey != null) {
      for (Player player : Bukkit.getOnlinePlayers()) {
        removeModifier(player, Attribute.ATTACK_DAMAGE, strengthKey);
      }
    }
  }

  private Settings readSettings() throws IOException {
    YamlConfiguration config = new YamlConfiguration();
    try {
      config.load(getDataFolder().toPath().resolve("config.yml").toFile());
    } catch (InvalidConfigurationException e) {
      throw new IOException("Invalid config.yml", e);
    }
    return new Settings(config);
  }

  /** Validates and commits a replacement configuration before activating it. */
  public void reloadRules() throws IOException {
    Settings next;
    try {
      next = readSettings();
    } catch (IOException e) {
      throw new IllegalArgumentException("Configuration reload rejected: " + e.getMessage(), e);
    }
    progression.normalize(next);
    settings = next;
    if (items != null) {
      items.recipes();
    }
    if (abilities != null) {
      abilities.clearAll();
    }
    refreshAll();
  }

  /** Validates and atomically saves a dashboard rule edit before activating it. */
  void updateRule(String path, Object value) throws IOException {
    YamlConfiguration next = settings.copy();
    next.set(path, value);
    new Settings(next);
    Path destination = getDataFolder().toPath().resolve("config.yml");
    Path temporary = getDataFolder().toPath().resolve("config.yml.tmp");
    next.save(temporary.toFile());
    try {
      Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
    reloadRules();
  }

  /** Returns the active configuration snapshot. */
  public Settings settings() {
    return settings;
  }

  /** Returns the progression engine. */
  Progression progression() {
    return progression;
  }

  /** Returns runtime tags and item cooldowns. */
  CombatTracker combat() {
    return combat;
  }

  /** Returns the custom-item controller. */
  SmpItems items() {
    return items;
  }

  /** Returns the weapon ability controller. */
  WeaponAbilities abilities() {
    return abilities;
  }

  /** Applies the immediate escape restrictions when a real or test combat tag begins. */
  void combatRestrictions(Player player) {
    if (player.hasPermission("epicsmp.bypass")) {
      return;
    }
    if (settings.flag("combat.block-elytra")) {
      player.setGliding(false);
    }
    org.bukkit.inventory.ItemStack item = player.getActiveItem();
    if (settings.flag("combat.block-riptide")
        && item.getType() == org.bukkit.Material.TRIDENT
        && item.containsEnchantment(org.bukkit.enchantments.Enchantment.RIPTIDE)) {
      player.clearActiveItem();
    }
  }

  /** Returns whether progression/PvP is paused, including persistence failures. */
  public boolean paused() {
    return paused || storageFailed;
  }

  /** Pauses combat and progression without disabling equipment restrictions. */
  public void pause(boolean value) {
    if (!value && storageFailed) {
      throw new IllegalArgumentException("Storage failed. Fix disk access and restart the server.");
    }
    paused = value;
    if (value) {
      abilities.clearAll();
      combat.clearAll();
    }
  }

  /** Prevents further combat when progression cannot be saved. */
  public void storageFailure(IOException exception) {
    storageFailed = true;
    if (abilities != null) {
      abilities.clearAll();
    }
    combat.clearAll();
    getLogger().severe("Progression storage failed; PvP paused: " + exception.getMessage());
    Bukkit.broadcast(message("PvP paused: progression could not be saved. Contact an admin."));
  }

  /** Creates or restores a player's progression before applying modifiers. */
  public void initialize(Player player) {
    try {
      progression.join(player.getUniqueId(), player.getName(), System.currentTimeMillis());
      refresh(player);
    } catch (IOException e) {
      storageFailure(e);
      player.kick(message("Progression storage unavailable. Please try again later."));
    }
  }

  /** Updates the melee Strength modifier and sends changed values to chat. */
  public void refresh(Player player) {
    Profile profile = progression.profile(player.getUniqueId());
    if (profile == null) {
      return;
    }
    announce(player, profile);
    modifier(
        player,
        Attribute.ATTACK_DAMAGE,
        strengthKey,
        active(player)
            ? (profile.strength() - 1) * settings.decimal("strength.damage-per-level")
            : 0,
        AttributeModifier.Operation.ADD_NUMBER);
  }

  /** Reapplies all online modifiers after an admin edit or reload. */
  public void refreshAll() {
    Bukkit.getOnlinePlayers().forEach(this::refresh);
  }

  private void modifier(
      Player player,
      Attribute attribute,
      NamespacedKey key,
      double amount,
      AttributeModifier.Operation operation) {
    AttributeInstance instance = player.getAttribute(attribute);
    if (instance == null) {
      return;
    }
    removeModifier(player, attribute, key);
    if (amount != 0) {
      instance.addTransientModifier(new AttributeModifier(key, amount, operation));
    }
  }

  private void removeModifier(Player player, Attribute attribute, NamespacedKey key) {
    AttributeInstance instance = player.getAttribute(attribute);
    if (instance != null) {
      instance.getModifiers().stream()
          .filter(modifier -> modifier.getKey().equals(key))
          .toList()
          .forEach(instance::removeModifier);
    }
  }

  /** Checks whether world and game mode participate in combat/progression. */
  public boolean active(Player player) {
    return (player.getGameMode() == org.bukkit.GameMode.SURVIVAL
            || player.getGameMode() == org.bukkit.GameMode.ADVENTURE)
        && !settings.strings("combat.excluded-worlds").contains(player.getWorld().getName());
  }

  /** Checks the horizontal spawn protection radius in a participating world. */
  public boolean safe(org.bukkit.Location location) {
    if (!settings.flag("combat.immunity-enabled")
        || location.getWorld() == null
        || settings.strings("combat.excluded-worlds").contains(location.getWorld().getName())) {
      return false;
    }
    double radius = settings.decimal("combat.spawn-safe-radius");
    org.bukkit.Location spawn = location.getWorld().getSpawnLocation();
    return radius > 0
        && Math.hypot(location.getX() - spawn.getX(), location.getZ() - spawn.getZ()) <= radius;
  }

  /** Checks persistent new-player and respawn protection. */
  public boolean protectedPlayer(Player player) {
    Profile profile = progression.profile(player.getUniqueId());
    return settings.flag("combat.immunity-enabled")
        && profile != null
        && profile.protectionUntil() > System.currentTimeMillis();
  }

  /** Builds literal player-facing text. */
  public static Component message(String text) {
    return Component.text("[EpicSMP] ", NamedTextColor.GOLD)
        .append(Component.text(text, NamedTextColor.WHITE));
  }

  private void announce(Player player, Profile profile) {
    Integer previous = notices.put(player.getUniqueId(), profile.strength());
    if (previous == null || previous != profile.strength()) {
      player.sendMessage(
          message("Strength: " + (previous == null ? "" : previous + " → ") + profile.strength()));
    }
  }

  /** Resets chat notification tracking when a player leaves. */
  void forgetNotices(Player player) {
    notices.remove(player.getUniqueId());
  }
}
