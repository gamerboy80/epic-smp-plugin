package com.epicsmp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.potion.PotionEffectType;

/** Validated configuration snapshot; reload cannot mutate the active rules accidentally. */
public final class Settings {
  private final YamlConfiguration values;
  private final Map<Material, Integer> cooldowns;
  private final Set<NamespacedKey> disabledEnchants;
  private final Map<NamespacedKey, Integer> enchantLimits;
  private final Map<NamespacedKey, Integer> potionLimits;
  private final Map<Material, Integer> inventoryLimits;

  /** Loads and validates an independent copy of a configuration. */
  public Settings(FileConfiguration config) {
    values = new YamlConfiguration();
    try {
      values.loadFromString(config.saveToString());
    } catch (InvalidConfigurationException e) {
      throw new IllegalArgumentException("Invalid YAML", e);
    }
    if (!values.isSet("combat.immunity-enabled")) {
      values.set("combat.immunity-enabled", false);
    }
    values.set("display.action-bar", null);
    values.set("display.rank-in-tab", null);
    values.set("display.rank-in-nametag", null);
    values.set("ranks", null);
    values.set("strength.deathban", null);
    values.set("rules.season-four", null);
    for (String path :
        List.of(
            "rules.allow-ender-pearls",
            "rules.allow-enchanted-golden-apples",
            "rules.allow-netherite-armor",
            "rules.allow-tnt-minecarts",
            "rules.allow-harmful-effects")) {
      if (!values.isSet(path)) {
        values.set(path, false);
      }
    }
    for (String path : List.of("combat.block-storage-restocking", "combat.block-lava")) {
      if (!values.isSet(path)) {
        values.set(path, true);
      }
    }
    if (!values.isSet("display.hide-invisible-killers")) {
      values.set("display.hide-invisible-killers", true);
    }
    if (values.getInt("strength.minimum") == -3 && values.getInt("strength.starting") == 0) {
      values.set("strength.minimum", 1);
      values.set("strength.starting", 1);
    }
    if (!values.isSet("strength.crafted-maximum")) {
      values.set("strength.crafted-maximum", 3);
    }
    for (String path :
        List.of(
            "abilities.enabled",
            "abilities.shield-invulnerability",
            "recipes.strength",
            "recipes.weapon-reroll",
            "recipes.golden-apple")) {
      if (!values.isSet(path)) {
        values.set(path, true);
      }
    }
    Map<String, Integer> defaults =
        Map.of("sword", 90, "axe", 120, "bow", 60, "crossbow", 60, "trident", 60, "shield", 120);
    defaults.forEach(
        (key, seconds) -> {
          String path = "abilities.cooldowns." + key;
          if (!values.isSet(path)) {
            values.set(path, seconds);
          }
          integer(path, 0, 3600);
        });
    if (!values.isSet("abilities.passive-strength")) {
      values.set("abilities.passive-strength", 2);
    }
    if (!values.isSet("abilities.ultimate-strength")) {
      values.set("abilities.ultimate-strength", 5);
    }
    if (!values.isSet("abilities.trident-lightning-damage")) {
      values.set("abilities.trident-lightning-damage", 2.0);
    }
    if (!values.isSet("abilities.crossbow-pull-chance")) {
      values.set("abilities.crossbow-pull-chance", 0.33);
    }
    integer("abilities.passive-strength", 1, 10);
    integer("abilities.ultimate-strength", integer("abilities.passive-strength"), 10);
    integer("strength.crafted-maximum", 1, 3);
    decimal("abilities.trident-lightning-damage", 0, 20);
    decimal("abilities.crossbow-pull-chance", 0, 1);
    integer("strength.minimum", 1, 1);
    integer("strength.maximum", 1, 10);
    integer("strength.starting", integer("strength.minimum"), integer("strength.maximum"));
    integer("strength.per-kill", 0, 10);
    integer("strength.natural-death-loss", 0, 10);
    decimal("strength.damage-per-level", 0, 20);
    for (String path :
        List.of(
            "combat.tag-seconds",
            "combat.repeat-pair-seconds",
            "combat.new-player-protection-seconds",
            "combat.respawn-protection-seconds")) {
      integer(path, 0, 86400);
    }
    decimal("combat.spawn-safe-radius", 0, 10000);
    decimal("rules.maximum-pvp-damage", 0, 200);
    decimal("limits.maximum-explosion-damage", 0, 200);
    integer("limits.spear-lunge-cooldown-seconds", 0, 3600);
    integer("limits.mace-hit-cooldown-seconds", 0, 3600);
    for (String path :
        List.of(
            "abilities.enabled",
            "abilities.shield-invulnerability",
            "recipes.strength",
            "recipes.weapon-reroll",
            "recipes.golden-apple",
            "rules.allow-ender-pearls",
            "rules.allow-enchanted-golden-apples",
            "rules.allow-netherite-armor",
            "rules.allow-tnt-minecarts",
            "rules.allow-harmful-effects",
            "combat.block-storage-restocking",
            "combat.block-lava",
            "combat.credit-tagged-deaths",
            "combat.punish-logout",
            "combat.block-elytra",
            "combat.block-world-change",
            "combat.block-riptide",
            "rules.pvp",
            "rules.allow-mace",
            "rules.allow-end-crystals",
            "rules.allow-respawn-anchor-explosions",
            "rules.allow-explosive-beds",
            "rules.allow-totems",
            "limits.allow-nether",
            "limits.allow-end",
            "limits.allow-tipped-arrows",
            "combat.immunity-enabled",
            "display.hide-invisible-killers")) {
      if (!values.isBoolean(path)) {
        throw new IllegalArgumentException(path + " must be true or false");
      }
    }
    for (String path :
        List.of(
            "combat.blocked-commands", "combat.excluded-worlds", "rules.disabled-mace-enchants")) {
      List<?> list = values.getList(path);
      if (list == null || list.stream().anyMatch(element -> !(element instanceof String))) {
        throw new IllegalArgumentException(path + " must be a list of strings");
      }
    }
    cooldowns = new HashMap<>();
    ConfigurationSection section = values.getConfigurationSection("cooldowns");
    if (section == null) {
      throw new IllegalArgumentException("cooldowns must be a mapping");
    }
    for (String key : section.getKeys(false)) {
      Material material = Material.matchMaterial(key);
      if (material == null || material.isLegacy() || !material.isItem()) {
        throw new IllegalArgumentException("Unknown cooldown item: " + key);
      }
      cooldowns.put(material, integer("cooldowns." + key, 0, 3600) * 20);
    }
    disabledEnchants =
        values.getStringList("rules.disabled-mace-enchants").stream()
            .map(
                key -> {
                  NamespacedKey id = NamespacedKey.fromString(key);
                  if (id == null || Registry.ENCHANTMENT.get(id) == null) {
                    throw new IllegalArgumentException("Unknown enchantment: " + key);
                  }
                  return id;
                })
            .collect(Collectors.toUnmodifiableSet());
    enchantLimits = registryLimits("limits.enchantments", 255, true);
    potionLimits = registryLimits("limits.potions", 256, false);
    inventoryLimits = new HashMap<>();
    ConfigurationSection inventory = values.getConfigurationSection("limits.inventory");
    if (inventory == null) {
      throw new IllegalArgumentException("limits.inventory must be a mapping");
    }
    for (String key : inventory.getKeys(false)) {
      Material material = Material.matchMaterial(key);
      if (material == null || material.isLegacy() || !material.isItem() || material.isAir()) {
        throw new IllegalArgumentException("Unknown inventory limit item: " + key);
      }
      inventoryLimits.put(material, integer("limits.inventory." + key, 0, 2304));
    }
  }

  /** Returns an integer setting. */
  public int integer(String path) {
    return values.getInt(path);
  }

  private int integer(String path, int minimum, int maximum) {
    int value = integer(path);
    if (!values.isInt(path) || value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          path + " must be an integer in " + minimum + ".." + maximum);
    }
    return value;
  }

  /** Returns a decimal setting. */
  public double decimal(String path) {
    return values.getDouble(path);
  }

  private double decimal(String path, double minimum, double maximum) {
    double value = decimal(path);
    if (!values.isSet(path)
        || !(values.get(path) instanceof Number)
        || !Double.isFinite(value)
        || value < minimum
        || value > maximum) {
      throw new IllegalArgumentException(path + " must be a number in " + minimum + ".." + maximum);
    }
    return value;
  }

  /** Returns a boolean setting. */
  public boolean flag(String path) {
    return values.getBoolean(path);
  }

  /** Returns an immutable list setting. */
  public List<String> strings(String path) {
    return List.copyOf(values.getStringList(path));
  }

  /** Returns cooldown duration in server ticks. */
  public int cooldown(Material material) {
    return cooldowns.getOrDefault(material, 0);
  }

  /** Returns forbidden enchantment identifiers. */
  public Set<NamespacedKey> disabledEnchants() {
    return Set.copyOf(disabledEnchants);
  }

  private Map<NamespacedKey, Integer> registryLimits(String path, int max, boolean enchants) {
    ConfigurationSection section = values.getConfigurationSection(path);
    if (section == null) {
      throw new IllegalArgumentException(path + " must be a mapping");
    }
    Map<NamespacedKey, Integer> limits = new HashMap<>();
    for (String key : section.getKeys(false)) {
      NamespacedKey id = NamespacedKey.fromString(key);
      PotionEffectType effect = id == null ? null : Registry.POTION_EFFECT_TYPE.get(id);
      if (id == null || (enchants ? Registry.ENCHANTMENT.get(id) == null : effect == null)) {
        throw new IllegalArgumentException("Unknown " + path + " entry: " + key);
      }
      if (!enchants && effect != null && effect.isInstant()) {
        throw new IllegalArgumentException(
            "Instant effects cannot be capped by " + path + ": " + key);
      }
      limits.put(id, integer(path + "." + key, 0, max));
    }
    return Map.copyOf(limits);
  }

  /** Enchantment caps for all usable equipment; zero disables the enchantment. */
  public Map<NamespacedKey, Integer> enchantLimits() {
    return Map.copyOf(enchantLimits);
  }

  /** Returns an independent copy for validated administrative configuration updates. */
  public YamlConfiguration copy() {
    return YamlConfiguration.loadConfiguration(new java.io.StringReader(values.saveToString()));
  }

  /** Maximum effect level (one-based), or -1 for unlimited. */
  public int potionLimit(PotionEffectType type) {
    if (!flag("rules.allow-harmful-effects")
        && java.util.Set.of(
                "slowness",
                "mining_fatigue",
                "instant_damage",
                "nausea",
                "blindness",
                "hunger",
                "weakness",
                "poison",
                "wither",
                "levitation",
                "darkness",
                "unluck")
            .contains(type.getKey().getKey())) {
      return 0;
    }
    return potionLimits.getOrDefault(type.getKey(), -1);
  }

  /** Maximum carried amount for acquisition/use checks, or -1 for unlimited. */
  public int inventoryLimit(Material material) {
    return inventoryLimits.getOrDefault(material, -1);
  }
}
