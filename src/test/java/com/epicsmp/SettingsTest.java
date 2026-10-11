package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class SettingsTest {
  private YamlConfiguration config;

  @BeforeEach
  void setup() {
    MockBukkit.mock();
    config =
        YamlConfiguration.loadConfiguration(
            new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
  }

  @AfterEach
  void cleanup() {
    MockBukkit.unmock();
  }

  @Test
  void defaultsUseBaselineOneAndIndependentVanillaStrength() {
    Settings settings = new Settings(config);
    assertEquals(1, settings.integer("strength.minimum"));
    assertEquals(1, settings.integer("strength.starting"));
    assertEquals(5, settings.integer("strength.maximum"));
    assertEquals(2, settings.potionLimit(org.bukkit.potion.PotionEffectType.STRENGTH));
    assertTrue(!settings.flag("combat.immunity-enabled"));
    assertTrue(settings.flag("display.hide-invisible-killers"));
    config.set("combat.immunity-enabled", "yes");
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
  }

  @Test
  void individualRestrictionsAreIndependentAndRejectInvalidTypes() {
    config.set("rules.allow-ender-pearls", true);
    config.set("combat.block-storage-restocking", false);
    Settings settings = new Settings(config);
    assertTrue(settings.flag("rules.allow-ender-pearls"));
    assertTrue(!settings.flag("rules.allow-netherite-armor"));
    assertTrue(!settings.flag("combat.block-storage-restocking"));
    assertTrue(settings.flag("combat.block-lava"));
    assertEquals(0, settings.potionLimit(org.bukkit.potion.PotionEffectType.POISON));
    config.set("rules.allow-harmful-effects", true);
    assertEquals(-1, new Settings(config).potionLimit(org.bukkit.potion.PotionEffectType.POISON));
    config.set("combat.block-lava", "enabled");
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
  }

  @Test
  void invalidRulesFailEarlyAndSnapshotDoesNotFollowExternalMutations() {
    Settings settings = new Settings(config);
    config.set("rules.pvp", false);
    assertTrue(settings.flag("rules.pvp"));
    config.set("strength.minimum", -10);
    config.set("strength.damage-per-level", 0.2);
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
  }

  @Test
  void malformedListsAndAbilityThresholdsAreRejected() {
    config.set("abilities.passive-strength", 6);
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    config.set("abilities.passive-strength", 2);
    config.set("combat.excluded-worlds", "world");
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
  }

  @Test
  void unknownItemsAndEnchantmentsAreRejected() {
    config.set("cooldowns.NOT_A_MATERIAL", 5);
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
    config.set("cooldowns.NOT_A_MATERIAL", null);
    config.set("rules.disabled-mace-enchants", java.util.List.of("not_an_enchantment"));
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
  }

  @Test
  void competitiveLimitsValidateTypesRangesAndRegistries() {
    Settings settings = new Settings(config);
    assertEquals(3, settings.integer("limits.spear-lunge-cooldown-seconds"));
    assertEquals(2, settings.potionLimit(org.bukkit.potion.PotionEffectType.STRENGTH));
    assertEquals(1, settings.inventoryLimit(org.bukkit.Material.TOTEM_OF_UNDYING));
    for (String path :
        java.util.List.of(
            "limits.enchantments.fake",
            "limits.potions.fake",
            "limits.inventory.FAKE",
            "limits.spear-lunge-cooldown-seconds")) {
      YamlConfiguration invalid =
          YamlConfiguration.loadConfiguration(new java.io.StringReader(config.saveToString()));
      invalid.set(path, -1);
      assertThrows(IllegalArgumentException.class, () -> new Settings(invalid), path);
    }
    config.set("limits.potions.instant_damage", 1);
    assertThrows(IllegalArgumentException.class, () -> new Settings(config));
  }
}
