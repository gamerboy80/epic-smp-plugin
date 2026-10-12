package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import io.papermc.paper.event.entity.EntityLungeEvent;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.ArrowMock;
import org.mockbukkit.mockbukkit.entity.EnderPearlMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class PluginIntegrationTest {
  @TempDir Path directory;

  private TestServerMock server;
  private EpicSmpPlugin plugin;
  private PlayerMock attacker;
  private PlayerMock victim;

  @BeforeEach
  void setup() throws IOException {
    server = MockBukkit.mock(new TestServerMock());
    TestPacketEvents.install();
    MockBukkit.createMockPlugin("packetevents");
    server.addSimpleWorld("world");
    YamlConfiguration config =
        YamlConfiguration.loadConfiguration(
            new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    config.set("combat.new-player-protection-seconds", 0);
    config.set("combat.respawn-protection-seconds", 0);
    config.set("combat.spawn-safe-radius", 0);
    config.set("rules.allow-ender-pearls", true);
    config.set("rules.allow-enchanted-golden-apples", true);
    config.set("rules.allow-netherite-armor", true);
    config.set("rules.allow-harmful-effects", true);
    config.set(
        "rules.disabled-mace-enchants", java.util.List.of("density", "breach", "wind_burst"));
    config.set("rules.allow-totems", true);
    config.set("abilities.enabled", false);
    plugin = MockBukkit.load(EpicSmpPlugin.class);
    config.save(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    plugin.reloadRules();
    assertTrue(plugin.isEnabled());
    attacker = server.addPlayer("Attacker");
    victim = server.addPlayer("Victim");
    attacker.setGameMode(GameMode.SURVIVAL);
    victim.setGameMode(GameMode.SURVIVAL);
    // PlayerMock omits attack damage from its default attribute set.
    attacker.registerAttribute(Attribute.ATTACK_DAMAGE);
    victim.registerAttribute(Attribute.ATTACK_DAMAGE);
    attacker.registerAttribute(Attribute.ATTACK_SPEED);
    java.util.Objects.requireNonNull(attacker.getAttribute(Attribute.ATTACK_SPEED)).setBaseValue(4);
    java.util.Objects.requireNonNull(attacker.getAttribute(Attribute.ATTACK_DAMAGE))
        .setBaseValue(1);
    java.util.Objects.requireNonNull(victim.getAttribute(Attribute.ATTACK_DAMAGE)).setBaseValue(1);
    plugin.refreshAll();
  }

  @AfterEach
  void cleanup() {
    MockBukkit.unmock();
  }

  @SuppressWarnings("removal")
  private EntityDamageByEntityEvent attack(double damage) {
    DamageSource source =
        DamageSource.builder(DamageType.PLAYER_ATTACK)
            .withCausingEntity(attacker)
            .withDirectEntity(attacker)
            .build();
    EntityDamageByEntityEvent event =
        new EntityDamageByEntityEvent(
            attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, damage);
    server.getPluginManager().callEvent(event);
    return event;
  }

  @Test
  void pluginStartsAndKillUpdatesOnlyStrengthAndPersists() throws IOException {
    assertEquals(20, attacker.getAttribute(Attribute.MAX_HEALTH).getValue(), 0.001);
    assertEquals(20, victim.getAttribute(Attribute.MAX_HEALTH).getValue(), 0.001);
    assertFalse(attack(4).isCancelled());
    PlayerDeathEvent death =
        new PlayerDeathEvent(
            victim,
            DamageSource.builder(DamageType.FALL).build(),
            new ArrayList<>(),
            0,
            (net.kyori.adventure.text.Component) null,
            true);
    server.getPluginManager().callEvent(death);
    assertEquals(20, attacker.getAttribute(Attribute.MAX_HEALTH).getValue(), 0.001);
    assertEquals(20, victim.getAttribute(Attribute.MAX_HEALTH).getValue(), 0.001);
    assertEquals(2, plugin.progression().profile(attacker.getUniqueId()).strength());
    assertEquals(2.5, attacker.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), 0.001);
    Path copy = directory.resolve("players.yml");
    Files.copy(plugin.getDataFolder().toPath().resolve("players.yml"), copy);
    assertEquals(plugin.progression().snapshot(), new YamlStateStore(copy).load());
    assertTrue(attacker.performCommand("smp stats"));
    assertTrue(attacker.performCommand("epicsmp rules"));
  }

  @Test
  void cancelledDamageDoesNotTagAndDamageCapAppliesToFinalDamage() {
    plugin.pause(true);
    assertTrue(attack(100).isCancelled());
    assertEquals(null, plugin.combat().tag(victim.getUniqueId(), System.currentTimeMillis()));
    plugin.pause(false);
    EntityDamageByEntityEvent accepted = attack(100);
    assertFalse(accepted.isCancelled());
    assertTrue(accepted.getFinalDamage() <= 24.000001);
    assertNotNull(plugin.combat().tag(victim.getUniqueId(), System.currentTimeMillis()));
  }

  @Test
  void consumeStartsOneSharedCooldownAndCancelledConsumeAwardsNone() {
    ItemStack apple = new ItemStack(Material.GOLDEN_APPLE);
    PlayerItemConsumeEvent first = new PlayerItemConsumeEvent(attacker, apple, EquipmentSlot.HAND);
    server.getPluginManager().callEvent(first);
    assertFalse(first.isCancelled());
    PlayerItemConsumeEvent second =
        new PlayerItemConsumeEvent(attacker, apple, EquipmentSlot.OFF_HAND);
    server.getPluginManager().callEvent(second);
    assertTrue(second.isCancelled());
    assertTrue(
        plugin
                .combat()
                .remaining(
                    attacker.getUniqueId(),
                    Material.GOLDEN_APPLE.name(),
                    System.currentTimeMillis())
            > 0);
    PlayerItemConsumeEvent cancelled =
        new PlayerItemConsumeEvent(victim, apple, EquipmentSlot.HAND);
    cancelled.setCancelled(true);
    server.getPluginManager().callEvent(cancelled);
    assertEquals(
        0,
        plugin
            .combat()
            .remaining(
                victim.getUniqueId(), Material.GOLDEN_APPLE.name(), System.currentTimeMillis()));
  }

  @Test
  void thrownItemCooldownSharesAcrossStacksAndCancelledLaunchAwardsNone() {
    ItemStack pearl = new ItemStack(Material.ENDER_PEARL);
    EnderPearlMock projectile = new EnderPearlMock(server, java.util.UUID.randomUUID());
    PlayerLaunchProjectileEvent first =
        new PlayerLaunchProjectileEvent(attacker, pearl, projectile);
    server.getPluginManager().callEvent(first);
    assertFalse(first.isCancelled());
    PlayerLaunchProjectileEvent second =
        new PlayerLaunchProjectileEvent(attacker, new ItemStack(Material.ENDER_PEARL), projectile);
    server.getPluginManager().callEvent(second);
    assertTrue(second.isCancelled());
    PlayerLaunchProjectileEvent cancelled =
        new PlayerLaunchProjectileEvent(victim, pearl, projectile);
    cancelled.setCancelled(true);
    server.getPluginManager().callEvent(cancelled);
    assertEquals(
        0,
        plugin.combat().remaining(victim.getUniqueId(), "ENDER_PEARL", System.currentTimeMillis()));
  }

  @Test
  void importedMaceIsSanitizedAndFirstHitCancelled() {
    ItemStack mace = new ItemStack(Material.MACE);
    mace.addUnsafeEnchantment(Enchantment.DENSITY, 5);
    mace.addUnsafeEnchantment(Enchantment.WIND_BURST, 3);
    mace.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
    attacker.getInventory().setItemInMainHand(mace);
    assertTrue(attack(100).isCancelled());
    ItemStack result = attacker.getInventory().getItemInMainHand();
    assertFalse(result.containsEnchantment(Enchantment.DENSITY));
    assertFalse(result.containsEnchantment(Enchantment.WIND_BURST));
    assertTrue(result.containsEnchantment(Enchantment.UNBREAKING));
  }

  @Test
  void blockedCommandsRecognizeNamespacesAndAdminPermissionIsEnforced() throws IOException {
    attack(4);
    PlayerCommandPreprocessEvent home =
        new PlayerCommandPreprocessEvent(attacker, "/essentials:home base");
    server.getPluginManager().callEvent(home);
    assertTrue(home.isCancelled());
    int strength = plugin.progression().profile(attacker.getUniqueId()).strength();
    attacker.performCommand("smp admin set Attacker strength 3");
    assertEquals(strength, plugin.progression().profile(attacker.getUniqueId()).strength());
    attacker.setOp(true);
    attacker.performCommand("smp admin set Attacker strength 3");
    assertEquals(3, plugin.progression().profile(attacker.getUniqueId()).strength());
    assertFalse(attacker.hasPermission("epicsmp.bypass"));
  }

  @Test
  void ordinaryXpAndPotionDurationStayUnchanged() {
    PlayerExpChangeEvent xp = new PlayerExpChangeEvent(attacker, 10);
    server.getPluginManager().callEvent(xp);
    assertEquals(10, xp.getAmount());
    EntityPotionEffectEvent event =
        new EntityPotionEffectEvent(
            victim,
            null,
            new PotionEffect(PotionEffectType.SPEED, 200, 1),
            null,
            EntityPotionEffectEvent.Cause.POTION_DRINK,
            EntityPotionEffectEvent.Action.ADDED,
            false);
    server.getPluginManager().callEvent(event);
    assertFalse(event.isCancelled());
  }

  @Test
  void invalidReloadKeepsRulesAndDoesNotPauseCombat() throws IOException {
    Path config = plugin.getDataFolder().toPath().resolve("config.yml");
    Files.writeString(config, "broken: [\n");
    attacker.setOp(true);
    attacker.performCommand("smp admin reload");
    assertFalse(plugin.paused());
    assertTrue(plugin.settings().flag("rules.pvp"));
    assertEquals(5, plugin.settings().integer("strength.maximum"));
  }

  @Test
  void taggedLogoutCausesOneCreditedDeathEvenWhenEnvironmentalCreditIsDisabled()
      throws IOException {
    YamlConfiguration config =
        YamlConfiguration.loadConfiguration(
            plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    config.set("combat.credit-tagged-deaths", false);
    config.save(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    plugin.reloadRules();
    attack(4);
    server
        .getPluginManager()
        .callEvent(
            new PlayerQuitEvent(
                victim, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertEquals(1, plugin.progression().profile(attacker.getUniqueId()).kills());
    assertEquals(1, plugin.progression().profile(victim.getUniqueId()).deaths());
  }

  @Test
  void cancelledDeathCannotAwardProgressionAndCrossWorldEscapeIsBlocked() {
    attack(4);
    State before = plugin.progression().snapshot();
    PlayerDeathEvent death =
        new PlayerDeathEvent(
            victim,
            DamageSource.builder(DamageType.FALL).build(),
            new ArrayList<>(),
            0,
            (Component) null,
            true);
    death.setCancelled(true);
    server.getPluginManager().callEvent(death);
    assertEquals(before, plugin.progression().snapshot());
    PlayerTeleportEvent teleport =
        new PlayerTeleportEvent(
            victim,
            victim.getLocation(),
            new Location(server.addSimpleWorld("other"), 200, 70, 200),
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
    server.getPluginManager().callEvent(teleport);
    assertTrue(teleport.isCancelled());
  }

  @Test
  void changingStrengthDoesNotHealOrChangeHealth() throws IOException {
    attacker.setHealth(12);
    plugin.progression().set(attacker.getUniqueId(), "strength", 5);
    plugin.refreshAll();
    assertEquals(12, attacker.getHealth(), 0.001);
    assertEquals(20, attacker.getAttribute(Attribute.MAX_HEALTH).getValue(), 0.001);
  }

  @Test
  void shutdownDisconnectDoesNotPunishTaggedPlayers() {
    attack(4);
    State before = plugin.progression().snapshot();
    server.stopping = true;
    server
        .getPluginManager()
        .callEvent(
            new PlayerQuitEvent(
                victim, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
    assertEquals(before, plugin.progression().snapshot());
    assertFalse(victim.isDead());
  }

  @Test
  void disableRemovesOnlyPluginModifiersAndRestoresVanillaHealth() {
    plugin.onDisable();
    assertEquals(20, victim.getAttribute(Attribute.MAX_HEALTH).getValue(), 0.001);
    assertEquals(20, attacker.getAttribute(Attribute.MAX_HEALTH).getValue(), 0.001);
  }

  @Test
  void lungeCooldownSharesAllSpearsAndCancelledAttemptsDoNotChargeIt() {
    EntityLungeEvent cancelled = new EntityLungeEvent(victim, 3);
    cancelled.setCancelled(true);
    server.getPluginManager().callEvent(cancelled);
    assertEquals(
        0,
        plugin
            .combat()
            .remaining(victim.getUniqueId(), LimitListener.LUNGE, System.currentTimeMillis()));
    attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SPEAR));
    EntityLungeEvent first = new EntityLungeEvent(attacker, 8);
    server.getPluginManager().callEvent(first);
    assertFalse(first.isCancelled());
    assertEquals(3, first.getLungePower());
    attacker.getInventory().setItemInMainHand(new ItemStack(Material.IRON_SPEAR));
    attacker.getInventory().setItemInOffHand(new ItemStack(Material.NETHERITE_SPEAR));
    EntityLungeEvent swapped = new EntityLungeEvent(attacker, 3);
    server.getPluginManager().callEvent(swapped);
    assertTrue(swapped.isCancelled());
    EntityLungeEvent other = new EntityLungeEvent(victim, 3);
    server.getPluginManager().callEvent(other);
    assertFalse(other.isCancelled());
    attacker.setOp(true);
    attacker.performCommand("smp admin test cooldown Attacker SPEAR_LUNGE 0");
    EntityLungeEvent cleared = new EntityLungeEvent(attacker, 3);
    server.getPluginManager().callEvent(cleared);
    assertFalse(cleared.isCancelled());
  }

  @Test
  void explicitItemBypassSkipsLimitsButCannotBypassPvpPause() {
    attacker.addAttachment(plugin, "epicsmp.bypass", true);
    plugin
        .combat()
        .cooldown(attacker.getUniqueId(), LimitListener.LUNGE, System.currentTimeMillis(), 60);
    EntityLungeEvent lunge = new EntityLungeEvent(attacker, 5);
    server.getPluginManager().callEvent(lunge);
    assertFalse(lunge.isCancelled());
    assertEquals(5, lunge.getLungePower());
    attacker.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
    attacker.getInventory().setItem(5, new ItemStack(Material.TOTEM_OF_UNDYING));
    EntityResurrectEvent resurrection = new EntityResurrectEvent(attacker, EquipmentSlot.OFF_HAND);
    server.getPluginManager().callEvent(resurrection);
    assertFalse(resurrection.isCancelled());
    attacker.setGliding(true);
    attack(4);
    assertTrue(attacker.isGliding());
    plugin.pause(true);
    assertTrue(attack(4).isCancelled());
  }

  @Test
  void maceCooldownStartsAfterAcceptedHitsAndCannotBeEvadedWithAnotherMace() {
    ItemStack forbidden = new ItemStack(Material.MACE);
    forbidden.addUnsafeEnchantment(Enchantment.DENSITY, 5);
    attacker.getInventory().setItemInMainHand(forbidden);
    assertTrue(attack(5).isCancelled());
    assertEquals(
        0,
        plugin
            .combat()
            .remaining(attacker.getUniqueId(), LimitListener.MACE, System.currentTimeMillis()));
    assertFalse(attack(5).isCancelled());
    attacker.getInventory().setItemInMainHand(new ItemStack(Material.MACE));
    assertTrue(attack(5).isCancelled());
    attacker.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_SWORD));
    assertFalse(attack(5).isCancelled());
  }

  @Test
  @SuppressWarnings("removal")
  void offhandSpearDamageAndThornsAreNotTreatedAsMaceHits() throws IOException {
    YamlConfiguration config =
        YamlConfiguration.loadConfiguration(
            plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    config.set("rules.allow-mace", false);
    config.save(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    plugin.reloadRules();
    attacker.getInventory().setItemInMainHand(new ItemStack(Material.MACE));
    attacker.getInventory().setItemInOffHand(new ItemStack(Material.DIAMOND_SPEAR));
    EntityDamageByEntityEvent spear =
        new EntityDamageByEntityEvent(
            attacker,
            victim,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            DamageSource.builder(DamageType.SPEAR).build(),
            4);
    server.getPluginManager().callEvent(spear);
    assertFalse(spear.isCancelled());
    EntityDamageByEntityEvent thorns =
        new EntityDamageByEntityEvent(
            attacker,
            victim,
            EntityDamageEvent.DamageCause.THORNS,
            DamageSource.builder(DamageType.THORNS).build(),
            2);
    server.getPluginManager().callEvent(thorns);
    assertFalse(thorns.isCancelled());
    assertEquals(
        0,
        plugin
            .combat()
            .remaining(attacker.getUniqueId(), LimitListener.MACE, System.currentTimeMillis()));
  }

  @Test
  void adminTestsCheckPermissionsAndPersistProtectionAndProgression() throws IOException {
    State before = plugin.progression().snapshot();
    attacker.performCommand("smp admin test simulatekill Attacker Victim");
    assertEquals(before, plugin.progression().snapshot());
    attacker.setOp(true);
    attacker.performCommand("smp admin immunity on");
    attacker.performCommand("smp admin test protect Victim 30");
    long until = plugin.progression().profile(victim.getUniqueId()).protectionUntil();
    assertTrue(until > System.currentTimeMillis());
    State saved = new YamlStateStore(plugin.getDataFolder().toPath().resolve("players.yml")).load();
    assertEquals(until, saved.players().get(victim.getUniqueId()).protectionUntil());
    attacker.performCommand("smp admin test simulatekill Attacker Victim");
    assertEquals(0, plugin.progression().profile(attacker.getUniqueId()).kills());
    attacker.performCommand("smp admin test protect Victim 0");
    attacker.performCommand("smp admin test simulatekill Attacker Victim");
    assertEquals(2, plugin.progression().profile(attacker.getUniqueId()).strength());
    attacker.performCommand("smp admin test simulatekill Victim Attacker");
    assertEquals(1, plugin.progression().profile(attacker.getUniqueId()).kills());
    attacker.performCommand("smp admin test pairreset Attacker Victim");
    attacker.performCommand("smp admin test simulatekill Victim Attacker");
    assertFalse(attacker.hasPermission("epicsmp.bypass"));
  }

  @Test
  void testToolboxClearsTemporaryStateGrantsWeaponsAndAwardsXp() {
    attacker.setOp(true);
    attacker.performCommand("smp admin test tag Attacker Victim 60");
    assertNotNull(plugin.combat().tag(attacker.getUniqueId(), System.currentTimeMillis()));
    attacker.performCommand("smp admin test cooldown Attacker ENDER_PEARL 15");
    assertTrue(attacker.hasCooldown(Material.ENDER_PEARL));
    attacker.performCommand("smp admin test clear Attacker");
    assertEquals(null, plugin.combat().tag(attacker.getUniqueId(), System.currentTimeMillis()));
    assertFalse(attacker.hasCooldown(Material.ENDER_PEARL));
    attacker.performCommand("smp admin test give Attacker spear");
    assertTrue(attacker.getInventory().contains(Material.DIAMOND_SPEAR));
    attacker.performCommand("smp admin test give Attacker mace");
    server.getScheduler().performTicks(20);
    ItemStack mace = attacker.getInventory().getItem(attacker.getInventory().first(Material.MACE));
    assertFalse(mace.containsEnchantment(Enchantment.DENSITY));
    assertTrue(mace.containsEnchantment(Enchantment.UNBREAKING));
    int xp = victim.getTotalExperience();
    attacker.performCommand("smp admin test xp Victim 10");
    assertEquals(xp + 10, victim.getTotalExperience());
    PlayerItemConsumeEvent food =
        new PlayerItemConsumeEvent(
            attacker, new ItemStack(Material.GOLDEN_APPLE), EquipmentSlot.HAND);
    server.getPluginManager().callEvent(food);
    attacker.performCommand("smp admin test clear Attacker");
    server.getScheduler().performTicks(1);
    assertFalse(attacker.hasCooldown(Material.GOLDEN_APPLE));
  }

  @Test
  void potionCapsPreserveDurationAndAllowIndependentVanillaStrength() {
    EntityPotionEffectEvent strength =
        new EntityPotionEffectEvent(
            attacker,
            null,
            new PotionEffect(PotionEffectType.STRENGTH, 200, 1),
            null,
            EntityPotionEffectEvent.Cause.POTION_SPLASH,
            EntityPotionEffectEvent.Action.ADDED,
            true);
    server.getPluginManager().callEvent(strength);
    assertFalse(strength.isCancelled());
    PotionEffectType speed = spy(PotionEffectType.SPEED);
    doReturn(PotionEffectTypeCategory.BENEFICIAL).when(speed).getCategory();
    EntityPotionEffectEvent high =
        new EntityPotionEffectEvent(
            victim,
            null,
            new PotionEffect(speed, 200, 4),
            null,
            EntityPotionEffectEvent.Cause.AREA_EFFECT_CLOUD,
            EntityPotionEffectEvent.Action.ADDED,
            true);
    server.getPluginManager().callEvent(high);
    assertTrue(high.isCancelled());
    assertEquals(1, victim.getPotionEffect(speed).getAmplifier());
    assertEquals(200, victim.getPotionEffect(speed).getDuration());
    victim.addPotionEffect(
        new PotionEffect(PotionEffectType.RESISTANCE, PotionEffect.INFINITE_DURATION, 3));
    assertEquals(0, victim.getPotionEffect(PotionEffectType.RESISTANCE).getAmplifier());
    assertTrue(victim.getPotionEffect(PotionEffectType.RESISTANCE).isInfinite());
  }

  @Test
  void inventoryLimitsCoverOffhandTotemsConsumptionAndPickupWithoutDeletingItems() {
    attacker.getInventory().setItemInOffHand(new ItemStack(Material.TOTEM_OF_UNDYING));
    attacker.getInventory().setItem(5, new ItemStack(Material.TOTEM_OF_UNDYING));
    EntityResurrectEvent resurrection = new EntityResurrectEvent(attacker, EquipmentSlot.OFF_HAND);
    server.getPluginManager().callEvent(resurrection);
    assertTrue(resurrection.isCancelled());
    assertEquals(Material.TOTEM_OF_UNDYING, attacker.getInventory().getItemInOffHand().getType());
    assertEquals(Material.TOTEM_OF_UNDYING, attacker.getInventory().getItem(5).getType());
    attacker.getInventory().setItem(5, null);
    EntityResurrectEvent one = new EntityResurrectEvent(attacker, EquipmentSlot.OFF_HAND);
    server.getPluginManager().callEvent(one);
    assertFalse(one.isCancelled());
    org.bukkit.entity.Item ground =
        attacker
            .getWorld()
            .dropItem(attacker.getLocation(), new ItemStack(Material.TOTEM_OF_UNDYING));
    EntityPickupItemEvent pickup = new EntityPickupItemEvent(attacker, ground, 0);
    server.getPluginManager().callEvent(pickup);
    assertTrue(pickup.isCancelled());
    assertTrue(ground.isValid());
    attacker.getInventory().setItem(4, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE, 5));
    PlayerItemConsumeEvent consume =
        new PlayerItemConsumeEvent(
            attacker, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), EquipmentSlot.HAND);
    server.getPluginManager().callEvent(consume);
    assertTrue(consume.isCancelled());
    assertEquals(
        0,
        plugin
            .combat()
            .remaining(
                attacker.getUniqueId(), "ENCHANTED_GOLDEN_APPLE", System.currentTimeMillis()));
  }

  @Test
  @SuppressWarnings("removal")
  void extraLimitsCapImportedWeaponsAndExplosionDamageAndBlockRiptide() throws IOException {
    YamlConfiguration config =
        YamlConfiguration.loadConfiguration(
            plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    config.set("limits.enchantments.sharpness", 2);
    config.set("limits.maximum-explosion-damage", 8);
    config.save(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    plugin.reloadRules();
    ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
    sword.addUnsafeEnchantment(Enchantment.SHARPNESS, 8);
    attacker.getInventory().setItemInMainHand(sword);
    assertTrue(attack(8).isCancelled());
    assertEquals(
        2, attacker.getInventory().getItemInMainHand().getEnchantmentLevel(Enchantment.SHARPNESS));
    assertFalse(attack(4).isCancelled());
    ItemStack trident = new ItemStack(Material.TRIDENT);
    trident.addUnsafeEnchantment(Enchantment.RIPTIDE, 3);
    PlayerInteractEvent interact =
        new PlayerInteractEvent(
            attacker,
            Action.RIGHT_CLICK_AIR,
            trident,
            null,
            org.bukkit.block.BlockFace.SELF,
            EquipmentSlot.HAND);
    server.getPluginManager().callEvent(interact);
    assertEquals(org.bukkit.event.Event.Result.DENY, interact.useItemInHand());
    EntityDamageEvent explosion =
        new EntityDamageEvent(
            attacker,
            EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
            DamageSource.builder(DamageType.EXPLOSION).build(),
            100);
    server.getPluginManager().callEvent(explosion);
    assertTrue(explosion.getFinalDamage() <= 8.000001);
  }

  @Test
  void seasonDimensionAndTippedArrowSwitchesAreIndependentOfCombatTags() throws IOException {
    YamlConfiguration config =
        YamlConfiguration.loadConfiguration(
            plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    config.set("limits.allow-nether", false);
    config.set("limits.allow-tipped-arrows", false);
    config.save(plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    plugin.reloadRules();
    WorldMock nether =
        new WorldMock(
            new org.bukkit.WorldCreator("nether").environment(org.bukkit.World.Environment.NETHER));
    server.addWorld(nether);
    PlayerTeleportEvent portal =
        new PlayerTeleportEvent(
            attacker,
            attacker.getLocation(),
            new Location(nether, 100, 20, 100),
            PlayerTeleportEvent.TeleportCause.NETHER_PORTAL);
    server.getPluginManager().callEvent(portal);
    assertTrue(portal.isCancelled());
    EntityShootBowEvent bow =
        new EntityShootBowEvent(
            attacker,
            new ItemStack(Material.BOW),
            new ItemStack(Material.TIPPED_ARROW),
            new ArrowMock(server, java.util.UUID.randomUUID()),
            EquipmentSlot.HAND,
            1,
            true);
    server.getPluginManager().callEvent(bow);
    assertTrue(bow.isCancelled());
  }

  @Test
  void dashboardRequiresAdminAndCannotTransferItemsOrActAfterPermissionRevocation() {
    attacker.performCommand("smp admin menu");
    assertEquals(InventoryType.CRAFTING, attacker.getOpenInventory().getType());
    attacker.setOp(true);
    attacker.performCommand("smp admin menu");
    assertEquals(54, attacker.getOpenInventory().getTopInventory().getSize());
    InventoryClickEvent shift =
        new InventoryClickEvent(
            attacker.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            10,
            ClickType.SHIFT_LEFT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY);
    server.getPluginManager().callEvent(shift);
    assertTrue(shift.isCancelled());
    server.getScheduler().performTicks(1);
    assertTrue(plugin.settings().flag("rules.pvp"));
    ItemStack glass =
        java.util.Objects.requireNonNull(attacker.getOpenInventory().getTopInventory().getItem(10))
            .clone();
    InventoryDragEvent drag =
        new InventoryDragEvent(
            attacker.getOpenInventory(),
            null,
            new ItemStack(Material.STONE, 2),
            false,
            java.util.Map.of(10, new ItemStack(Material.STONE)));
    server.getPluginManager().callEvent(drag);
    assertTrue(drag.isCancelled());
    assertEquals(glass, attacker.getOpenInventory().getTopInventory().getItem(10));
    InventoryClickEvent pending =
        new InventoryClickEvent(
            attacker.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            10,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(pending);
    attacker.setOp(false);
    server.getScheduler().performTicks(1);
    assertTrue(plugin.settings().flag("rules.pvp"));
  }

  @Test
  void dashboardTogglesPersistAndSelectionRoutesTestActionsToChosenPlayers() throws IOException {
    attacker.setOp(true);
    attacker.performCommand("smp admin menu");
    menuClick(10);
    assertFalse(plugin.settings().flag("rules.pvp"));
    YamlConfiguration saved =
        YamlConfiguration.loadConfiguration(
            plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    assertFalse(saved.getBoolean("rules.pvp"));
    menuClick(10);
    assertTrue(plugin.settings().flag("rules.pvp"));
    menuClick(6);
    // Roster is sorted by name: Attacker, Victim.
    menuClick(1);
    menuClick(34);
    menuClick(41);
    assertTrue(
        plugin
                .combat()
                .remaining(attacker.getUniqueId(), LimitListener.LUNGE, System.currentTimeMillis())
            > 0);
    menuClick(31);
    assertEquals(
        0,
        plugin
            .combat()
            .remaining(attacker.getUniqueId(), LimitListener.LUNGE, System.currentTimeMillis()));
    menuClick(53);
    assertEquals(InventoryType.CRAFTING, attacker.getOpenInventory().getType());
  }

  @Test
  void strengthChatReplacesPersistentStatusAndPotionModifierRemainsIndependent()
      throws IOException {
    while (attacker.nextComponentMessage() != null) {}
    assertEquals(1, attacker.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), 0.001);
    org.bukkit.attribute.AttributeModifier potion =
        new org.bukkit.attribute.AttributeModifier(
            new org.bukkit.NamespacedKey("minecraft", "test_strength_potion"),
            6,
            org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);
    java.util.Objects.requireNonNull(attacker.getAttribute(Attribute.ATTACK_DAMAGE))
        .addTransientModifier(potion);
    attacker.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 200, 1));
    assertTrue(attacker.hasPotionEffect(PotionEffectType.STRENGTH));
    plugin.progression().set(attacker.getUniqueId(), "strength", 5);
    plugin.refreshAll();
    assertEquals(13, attacker.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), 0.001);
    assertTrue(plain(attacker.nextComponentMessage()).contains("Strength: 1 → 5"));
    plugin.refreshAll();
    assertEquals(null, attacker.nextComponentMessage());
    server.getScheduler().performTicks(20);
    assertEquals(null, attacker.nextActionBar());
    plugin.onDisable();
    assertEquals(7, attacker.getAttribute(Attribute.ATTACK_DAMAGE).getValue(), 0.001);
  }

  private String plain(Component component) {
    return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
        .serialize(component);
  }

  @Test
  void immunitySwitchControlsSpawnAndGraceAndPersistsOff() throws IOException {
    assertFalse(plugin.settings().flag("combat.immunity-enabled"));
    plugin.updateRule("combat.spawn-safe-radius", 32);
    assertFalse(plugin.safe(victim.getWorld().getSpawnLocation()));
    attacker.setOp(true);
    attacker.performCommand("smp admin immunity on");
    assertTrue(plugin.safe(victim.getWorld().getSpawnLocation()));
    attacker.performCommand("smp admin test protect Victim 30");
    assertTrue(plugin.protectedPlayer(victim));
    assertTrue(attack(4).isCancelled());
    attacker.performCommand("smp admin immunity off");
    assertFalse(plugin.protectedPlayer(victim));
    assertFalse(plugin.safe(victim.getWorld().getSpawnLocation()));
    assertFalse(attack(4).isCancelled());
    assertEquals(
        0,
        new YamlStateStore(plugin.getDataFolder().toPath().resolve("players.yml"))
            .load()
            .players()
            .get(victim.getUniqueId())
            .protectionUntil());
    plugin.reloadRules();
    assertFalse(plugin.settings().flag("combat.immunity-enabled"));
  }

  private PlayerDeathEvent attributedDeath(Component message) {
    return new PlayerDeathEvent(
        victim,
        DamageSource.builder(DamageType.PLAYER_ATTACK)
            .withCausingEntity(attacker)
            .withDirectEntity(attacker)
            .build(),
        new ArrayList<>(),
        0,
        message,
        true);
  }

  @Test
  void invisibleDeathMessagesAreAnonymousToggleableAndRespectSilentDeaths() throws IOException {
    attacker.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 200, 0));
    Component normal = Component.text("Victim was slain by Attacker");
    PlayerDeathEvent hidden = attributedDeath(normal);
    server.getPluginManager().callEvent(hidden);
    assertEquals("Victim was slain.", plain(hidden.deathMessage()));
    assertEquals(2, plugin.progression().profile(attacker.getUniqueId()).strength());
    PlayerDeathEvent silent = attributedDeath(null);
    server.getPluginManager().callEvent(silent);
    assertEquals(null, silent.deathMessage());
    plugin.updateRule("display.hide-invisible-killers", false);
    PlayerDeathEvent visible = attributedDeath(normal);
    server.getPluginManager().callEvent(visible);
    assertEquals(normal, visible.deathMessage());
  }

  private PlayerInteractEvent useCustom(PlayerMock player, EquipmentSlot hand) {
    PlayerInteractEvent event =
        new PlayerInteractEvent(
            player,
            Action.RIGHT_CLICK_AIR,
            player.getInventory().getItem(hand),
            null,
            org.bukkit.block.BlockFace.UP,
            hand);
    server.getPluginManager().callEvent(event);
    assertTrue(event.isCancelled());
    return event;
  }

  @Test
  void namedGhastTearsTradeFromEitherHandRejectFakeNamesAndKeepItemsAtCap() throws IOException {
    plugin.progression().set(attacker.getUniqueId(), "strength", 3);
    attacker.performCommand("smp withdraw 2");
    assertEquals(1, plugin.progression().profile(attacker.getUniqueId()).strength());
    ItemStack first = attacker.getInventory().getItem(0);
    assertEquals(Material.GHAST_TEAR, first.getType());
    assertEquals(2, first.getAmount());
    assertEquals("Strength", plain(first.getItemMeta().displayName()));
    victim.getInventory().setItemInOffHand(first);
    attacker.getInventory().setItem(0, null);
    useCustom(victim, EquipmentSlot.OFF_HAND);
    assertEquals(2, plugin.progression().profile(victim.getUniqueId()).strength());
    assertEquals(1, victim.getInventory().getItemInOffHand().getAmount());
    ItemStack fake = new ItemStack(Material.GHAST_TEAR);
    var meta = fake.getItemMeta();
    meta.displayName(Component.text("Strength"));
    fake.setItemMeta(meta);
    victim.getInventory().setItemInMainHand(fake);
    useCustom(victim, EquipmentSlot.HAND);
    assertEquals(2, plugin.progression().profile(victim.getUniqueId()).strength());
    assertEquals(fake, victim.getInventory().getItemInMainHand());
    ItemStack second = victim.getInventory().getItemInOffHand();
    victim.getInventory().setItemInOffHand(null);
    victim.getInventory().setItemInMainHand(second);
    useCustom(victim, EquipmentSlot.HAND);
    assertEquals(3, plugin.progression().profile(victim.getUniqueId()).strength());
    plugin.progression().set(victim.getUniqueId(), "strength", 5);
    ItemToken.Kind cappedToken = ItemToken.Kind.STRENGTH;
    ItemStack cappedItem = plugin.items().item(cappedToken);
    victim.getInventory().setItemInMainHand(cappedItem);
    useCustom(victim, EquipmentSlot.HAND);
    assertEquals(cappedItem, victim.getInventory().getItemInMainHand());
    assertEquals(0, plugin.progression().snapshot().bottles().size());
  }

  @Test
  void strengthItemStacksMergeOnPickupAndRedeemIndividually() throws IOException {
    ItemToken.Kind firstToken = ItemToken.Kind.STRENGTH;
    final ItemToken.Kind secondToken = ItemToken.Kind.STRENGTH;
    org.bukkit.entity.Item firstDrop =
        attacker.getWorld().dropItem(attacker.getLocation(), plugin.items().item(firstToken));
    EntityPickupItemEvent firstPickup = new EntityPickupItemEvent(attacker, firstDrop, 0);
    server.getPluginManager().callEvent(firstPickup);
    assertFalse(firstPickup.isCancelled());
    assertTrue(firstDrop.isValid());
    assertEquals(null, attacker.getInventory().getItem(0));
    nativePickup(attacker, firstDrop);
    org.bukkit.entity.Item secondDrop =
        attacker.getWorld().dropItem(attacker.getLocation(), plugin.items().item(secondToken));
    EntityPickupItemEvent secondPickup = new EntityPickupItemEvent(attacker, secondDrop, 0);
    server.getPluginManager().callEvent(secondPickup);
    assertFalse(secondPickup.isCancelled());
    nativePickup(attacker, secondDrop);
    ItemStack stack = attacker.getInventory().getItem(0);
    assertEquals(2, stack.getAmount());
    assertEquals(0, plugin.progression().snapshot().bottles().size());
    ItemStack droppedStack = stack.clone();
    droppedStack.setAmount(1);
    ItemStack remainingStack = stack.clone();
    remainingStack.setAmount(1);
    attacker.getInventory().setItem(0, remainingStack);
    org.bukkit.entity.Item droppedEntity =
        attacker.getWorld().dropItem(attacker.getLocation(), droppedStack);
    PlayerDropItemEvent drop = new PlayerDropItemEvent(attacker, droppedEntity);
    server.getPluginManager().callEvent(drop);
    assertFalse(drop.isCancelled());
    assertEquals(1, droppedEntity.getItemStack().getAmount());
    assertEquals(1, attacker.getInventory().getItem(0).getAmount());
    EntityPickupItemEvent rePickup = new EntityPickupItemEvent(attacker, droppedEntity, 0);
    server.getPluginManager().callEvent(rePickup);
    assertFalse(rePickup.isCancelled());
    nativePickup(attacker, droppedEntity);
    assertEquals(2, attacker.getInventory().getItem(0).getAmount());
    attacker.getInventory().setItemInMainHand(attacker.getInventory().getItem(0));
    useCustom(attacker, EquipmentSlot.HAND);
    assertEquals(2, plugin.progression().profile(attacker.getUniqueId()).strength());
    assertEquals(1, attacker.getInventory().getItemInMainHand().getAmount());
    useCustom(attacker, EquipmentSlot.HAND);
    assertEquals(3, plugin.progression().profile(attacker.getUniqueId()).strength());
    assertTrue(
        attacker.getInventory().getItemInMainHand() == null
            || attacker.getInventory().getItemInMainHand().getType().isAir());
    assertEquals(0, plugin.progression().snapshot().bottles().size());
  }

  @Test
  void fullInventoryAndFailedRedemptionDoNotLoseStrengthOrItems() throws IOException {
    plugin.progression().set(attacker.getUniqueId(), "strength", 3);
    for (int slot = 0; slot < 36; slot++) {
      attacker.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }
    State before = plugin.progression().snapshot();
    attacker.performCommand("smp withdraw 1");
    assertEquals(before, plugin.progression().snapshot());
    ItemToken.Kind token = ItemToken.Kind.STRENGTH;
    ItemStack item = plugin.items().item(token);
    victim.getInventory().setItemInOffHand(item);
    State issued = plugin.progression().snapshot();
    Files.createDirectory(plugin.getDataFolder().toPath().resolve("players.yml.tmp"));
    useCustom(victim, EquipmentSlot.OFF_HAND);
    assertEquals(issued, plugin.progression().snapshot());
    assertEquals(item, victim.getInventory().getItemInOffHand());
    assertTrue(plugin.paused());
  }

  @Test
  void customItemsRejectAnvilAndBrewingAndNormalizeEditedPresentation() throws IOException {
    ItemToken.Kind token = ItemToken.Kind.STRENGTH;
    ItemStack item = plugin.items().item(token);
    org.bukkit.inventory.meta.ItemMeta edited = item.getItemMeta();
    edited.displayName(Component.text("Renamed"));
    assertTrue(item.setItemMeta(edited));
    attacker.getInventory().setItemInMainHand(item);
    org.bukkit.inventory.AnvilInventory anvil =
        new org.mockbukkit.mockbukkit.inventory.AnvilInventoryMock(null);
    anvil.setItem(0, item);
    org.bukkit.inventory.view.AnvilView view =
        org.mockito.Mockito.mock(org.bukkit.inventory.view.AnvilView.class);
    org.mockito.Mockito.when(view.getTopInventory()).thenReturn(anvil);
    org.mockito.Mockito.when(view.getPlayer()).thenReturn(attacker);
    org.bukkit.event.inventory.PrepareAnvilEvent event =
        new org.bukkit.event.inventory.PrepareAnvilEvent(view, item.clone());
    server.getPluginManager().callEvent(event);
    assertEquals(null, event.getResult());
    org.bukkit.inventory.BrewerInventory brewer =
        (org.bukkit.inventory.BrewerInventory) server.createInventory(null, InventoryType.BREWING);
    brewer.setIngredient(item);
    org.bukkit.event.inventory.BrewEvent brew =
        new org.bukkit.event.inventory.BrewEvent(
            attacker.getWorld().getBlockAt(0, 64, 0), brewer, new ArrayList<>(), 1);
    server.getPluginManager().callEvent(brew);
    assertTrue(brew.isCancelled());
    server.getScheduler().performTicks(20);
    assertEquals(
        "Strength", plain(attacker.getInventory().getItemInMainHand().getItemMeta().displayName()));
  }

  @Test
  void recipesMatchSeasonFourAndNativeCraftingKeepsTheirTags() {
    org.bukkit.inventory.ShapedRecipe recipe =
        (org.bukkit.inventory.ShapedRecipe)
            server.getRecipe(new org.bukkit.NamespacedKey(plugin, "strength_item"));
    assertEquals(java.util.List.of("CNC", "SAS", "CNC"), java.util.List.of(recipe.getShape()));
    assertEquals(Material.ENCHANTED_GOLDEN_APPLE, recipe.getIngredientMap().get('A').getType());
    org.bukkit.inventory.ShapedRecipe reroll =
        (org.bukkit.inventory.ShapedRecipe)
            server.getRecipe(new org.bukkit.NamespacedKey(plugin, "weapon_reroll"));
    assertEquals(java.util.List.of("IGI", "GDG", "IGI"), java.util.List.of(reroll.getShape()));
    org.bukkit.inventory.CraftingInventory inventory =
        new org.mockbukkit.mockbukkit.inventory.WorkbenchInventoryMock(null);
    ItemStack[] matrix = new ItemStack[9];
    for (int index = 0; index < 9; index++) {
      char symbol = recipe.getShape()[index / 3].charAt(index % 3);
      matrix[index] = recipe.getIngredientMap().get(symbol).clone();
      matrix[index].setAmount(2);
    }
    inventory.setMatrix(matrix);
    org.bukkit.inventory.InventoryView view =
        new org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock(
            attacker, inventory, attacker.getInventory(), InventoryType.WORKBENCH) {
          @Override
          public int convertSlot(int slot) {
            return slot;
          }
        };
    org.bukkit.event.inventory.CraftItemEvent shift =
        new org.bukkit.event.inventory.CraftItemEvent(
            recipe,
            view,
            InventoryType.SlotType.RESULT,
            0,
            ClickType.SHIFT_LEFT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY);
    server.getPluginManager().callEvent(shift);
    assertFalse(shift.isCancelled());
    assertTrue(plugin.progression().snapshot().bottles().isEmpty());
    org.bukkit.event.inventory.CraftItemEvent craft =
        new org.bukkit.event.inventory.CraftItemEvent(
            recipe,
            view,
            InventoryType.SlotType.RESULT,
            0,
            ClickType.NUMBER_KEY,
            InventoryAction.HOTBAR_SWAP,
            1);
    server.getPluginManager().callEvent(craft);
    assertFalse(craft.isCancelled());
    assertTrue(recipe.getResult().isSimilar(plugin.items().item(ItemToken.Kind.CRAFTED_STRENGTH)));
    // MockBukkit does not execute native craft transactions after firing these events.
    assertEquals(2, inventory.getMatrix()[0].getAmount());
  }

  private void specialty(WeaponKind weapon, int strength, Material material) throws IOException {
    plugin.updateRule("abilities.enabled", true);
    plugin.progression().weapon(attacker.getUniqueId(), weapon);
    plugin.progression().set(attacker.getUniqueId(), "strength", strength);
    attacker.getInventory().setItemInMainHand(new ItemStack(material));
    plugin.refreshAll();
  }

  @Test
  void swordComboRequiresAcceptedHitsMissResetsAndUltimateCleansOwnedSpeed() throws IOException {
    specialty(WeaponKind.SWORD, 5, Material.DIAMOND_SWORD);
    attacker.getInventory().setItemInOffHand(new ItemStack(Material.DIAMOND_SWORD));
    assertEquals(4, attack(4).getDamage(), 0.001);
    assertEquals(4, attack(4).getDamage(), 0.001);
    assertEquals(6, attack(4).getDamage(), 0.001);
    attack(4);
    attack(4);
    plugin.abilities().activate(attacker);
    server.getScheduler().performTicks(1);
    assertEquals(8, attacker.getAttribute(Attribute.ATTACK_SPEED).getValue(), 0.001);
    plugin.abilities().clear(attacker);
    assertEquals(4, attacker.getAttribute(Attribute.ATTACK_SPEED).getValue(), 0.001);
    assertThrowsAbility(attacker);
    plugin.combat().clearCooldowns(attacker.getUniqueId(), null);
    attack(4);
    attack(4);
    server
        .getPluginManager()
        .callEvent(
            new io.papermc.paper.event.player.PlayerArmSwingEvent(attacker, EquipmentSlot.HAND));
    server.getScheduler().performTicks(1);
    assertEquals(4, attack(4).getDamage(), 0.001);
  }

  private void assertThrowsAbility(PlayerMock player) {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> plugin.abilities().activate(player));
  }

  @SuppressWarnings("removal")
  private EntityDamageByEntityEvent criticalAttack(double damage) {
    EntityDamageByEntityEvent event =
        spy(
            new EntityDamageByEntityEvent(
                attacker,
                victim,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.PLAYER_ATTACK)
                    .withCausingEntity(attacker)
                    .withDirectEntity(attacker)
                    .build(),
                damage));
    doReturn(true).when(event).isCritical();
    server.getPluginManager().callEvent(event);
    return event;
  }

  @Test
  void axeCriticalStunFreezesMovementAndUltimateDoublesCrits() throws IOException {
    specialty(WeaponKind.AXE, 5, Material.DIAMOND_AXE);
    for (int index = 0; index < 5; index++) {
      criticalAttack(4);
    }
    org.bukkit.event.player.PlayerMoveEvent move =
        new org.bukkit.event.player.PlayerMoveEvent(
            victim, victim.getLocation(), victim.getLocation().clone().add(1, 0, 0));
    server.getPluginManager().callEvent(move);
    assertEquals(move.getFrom().getX(), move.getTo().getX(), 0.001);
    plugin.abilities().activate(attacker);
    assertEquals(8, criticalAttack(4).getDamage(), 0.001);
    server.getScheduler().performTicks(100);
    assertEquals(4, criticalAttack(4).getDamage(), 0.001);
    assertThrowsAbility(attacker);
  }

  @SuppressWarnings("removal")
  private EntityDamageByEntityEvent arrowHit(Material weapon, double damage) {
    ArrowMock arrow = new ArrowMock(server, java.util.UUID.randomUUID());
    arrow.setShooter(attacker);
    EntityShootBowEvent launch =
        new EntityShootBowEvent(
            attacker,
            new ItemStack(weapon),
            new ItemStack(Material.ARROW),
            arrow,
            EquipmentSlot.HAND,
            1,
            true);
    server.getPluginManager().callEvent(launch);
    EntityDamageByEntityEvent hit =
        new EntityDamageByEntityEvent(
            arrow,
            victim,
            EntityDamageEvent.DamageCause.PROJECTILE,
            DamageSource.builder(DamageType.ARROW)
                .withCausingEntity(attacker)
                .withDirectEntity(arrow)
                .build(),
            damage);
    server.getPluginManager().callEvent(hit);
    return hit;
  }

  @Test
  void bowAndCrossbowAcceptedHitMultipliersComposeWithDamageCap() throws IOException {
    specialty(WeaponKind.BOW, 5, Material.BOW);
    assertEquals(4, arrowHit(Material.BOW, 4).getDamage(), 0.001);
    assertEquals(4, arrowHit(Material.BOW, 4).getDamage(), 0.001);
    assertEquals(8, arrowHit(Material.BOW, 4).getDamage(), 0.001);
    for (int index = 4; index <= 9; index++) {
      arrowHit(Material.BOW, 4);
    }
    assertEquals(12, arrowHit(Material.BOW, 4).getDamage(), 0.001);
    specialty(WeaponKind.CROSSBOW, 5, Material.CROSSBOW);
    plugin.updateRule("abilities.crossbow-pull-chance", 0.0);
    plugin.combat().clearCooldowns(attacker.getUniqueId(), null);
    for (int index = 1; index <= 9; index++) {
      arrowHit(Material.CROSSBOW, 4);
    }
    assertEquals(16, arrowHit(Material.CROSSBOW, 4).getDamage(), 0.001);
    assertTrue(arrowHit(Material.CROSSBOW, 100).getFinalDamage() <= 24.000001);
  }

  @Test
  void shieldDefenseAndUltimateEndOnStrengthLossAndRespectOwnToggle() throws IOException {
    plugin.updateRule("abilities.enabled", true);
    plugin.progression().weapon(victim.getUniqueId(), WeaponKind.SHIELD);
    plugin.progression().set(victim.getUniqueId(), "strength", 5);
    victim.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
    server
        .getPluginManager()
        .callEvent(
            new io.papermc.paper.event.player.PlayerShieldDisableEvent(victim, attacker, 100));
    assertEquals(3, attack(4).getDamage(), 0.001);
    plugin.abilities().activate(victim);
    assertTrue(attack(4).isCancelled());
    plugin.progression().set(victim.getUniqueId(), "strength", 4);
    server.getScheduler().performTicks(1);
    assertFalse(attack(4).isCancelled());
    plugin.progression().set(victim.getUniqueId(), "strength", 5);
    plugin.updateRule("abilities.shield-invulnerability", false);
    plugin.combat().clearCooldowns(victim.getUniqueId(), null);
    assertThrowsAbility(victim);
  }

  @Test
  void tridentLightningBonusHasTwoSecondCooldownAndWaveMovesWithoutPersistentChanges()
      throws IOException {
    WorldMock visualWorld = spy(new WorldMock());
    visualWorld.setName("visual");
    doReturn(null).when(visualWorld).strikeLightningEffect(org.mockito.ArgumentMatchers.any());
    server.addWorld(visualWorld);
    attacker.teleport(new Location(visualWorld, 200, 64, 200));
    victim.teleport(new Location(visualWorld, 205, 64, 200));
    specialty(WeaponKind.TRIDENT, 5, Material.TRIDENT);
    assertEquals(6, attack(4).getDamage(), 0.001);
    assertEquals(4, attack(4).getDamage(), 0.001);
    server.getScheduler().performTicks(40);
    assertEquals(6, attack(4).getDamage(), 0.001);
    plugin.abilities().activate(attacker);
    server.getScheduler().performTicks(1);
    assertTrue(attacker.getVelocity().lengthSquared() > 0);
    plugin.pause(true);
    assertFalse(attacker.isInvulnerable());
  }

  @Test
  void integratedStorageAndLavaRulesToggleIndependentlyAndKeepDashboardUsable() throws IOException {
    plugin
        .combat()
        .hit(attacker.getUniqueId(), victim.getUniqueId(), System.currentTimeMillis(), 30);
    org.bukkit.inventory.Inventory chest = server.createInventory(null, 27);
    org.bukkit.event.inventory.InventoryOpenEvent open =
        new org.bukkit.event.inventory.InventoryOpenEvent(
            new org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock(
                attacker, chest, attacker.getInventory(), InventoryType.CHEST));
    server.getPluginManager().callEvent(open);
    assertTrue(open.isCancelled());
    plugin.updateRule("combat.block-storage-restocking", false);
    open.setCancelled(false);
    server.getPluginManager().callEvent(open);
    assertFalse(open.isCancelled());
    assertTrue(plugin.settings().flag("combat.block-lava"));
    org.bukkit.block.Block clicked = attacker.getLocation().getBlock();
    org.bukkit.event.player.PlayerBucketEmptyEvent lava =
        new org.bukkit.event.player.PlayerBucketEmptyEvent(
            attacker,
            clicked,
            clicked,
            org.bukkit.block.BlockFace.UP,
            Material.LAVA_BUCKET,
            new ItemStack(Material.BUCKET),
            EquipmentSlot.HAND);
    server.getPluginManager().callEvent(lava);
    assertTrue(lava.isCancelled());
    plugin.updateRule("combat.block-lava", false);
    lava.setCancelled(false);
    server.getPluginManager().callEvent(lava);
    assertFalse(lava.isCancelled());
    plugin.updateRule("combat.block-storage-restocking", true);
    attacker.setOp(true);
    attacker.performCommand("smp admin menu");
    assertEquals(InventoryType.CHEST, attacker.getOpenInventory().getType());
    menuClick(35);
    assertFalse(plugin.settings().flag("rules.allow-ender-pearls"));
    assertTrue(plugin.settings().flag("rules.allow-netherite-armor"));
    menuClick(44);
    assertFalse(plugin.settings().flag("rules.allow-netherite-armor"));
    YamlConfiguration saved =
        YamlConfiguration.loadConfiguration(
            plugin.getDataFolder().toPath().resolve("config.yml").toFile());
    assertFalse(saved.getBoolean("rules.allow-ender-pearls"));
    assertFalse(saved.getBoolean("rules.allow-netherite-armor"));
  }

  @Test
  void individualRulesBanUseAndUnequipArmorWithoutDeletingItems() throws IOException {
    plugin.updateRule("rules.allow-ender-pearls", false);
    plugin.updateRule("rules.allow-netherite-armor", false);
    plugin.updateRule("rules.allow-harmful-effects", false);
    plugin.updateRule(
        "rules.disabled-mace-enchants",
        java.util.List.of(
            "density", "breach", "wind_burst", "unbreaking", "mending", "vanishing_curse"));
    PlayerInteractEvent pearl =
        new PlayerInteractEvent(
            attacker,
            Action.RIGHT_CLICK_AIR,
            new ItemStack(Material.ENDER_PEARL),
            null,
            org.bukkit.block.BlockFace.UP,
            EquipmentSlot.HAND);
    server.getPluginManager().callEvent(pearl);
    assertEquals(org.bukkit.event.Event.Result.DENY, pearl.useItemInHand());
    ItemStack mace = new ItemStack(Material.MACE);
    mace.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
    assertTrue(RuleListener.sanitize(plugin.settings(), mace));
    assertTrue(mace.getEnchantments().isEmpty());
    attacker.getInventory().setChestplate(new ItemStack(Material.NETHERITE_CHESTPLATE));
    server.getScheduler().performTicks(20);
    assertEquals(null, attacker.getInventory().getChestplate());
    assertTrue(attacker.getInventory().contains(Material.NETHERITE_CHESTPLATE));
    victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 200, 0));
    assertFalse(victim.hasPotionEffect(PotionEffectType.POISON));
  }

  @Test
  void vanishCommandHidesOnlyFromOrdinaryViewersAndRestoresPlayerState() throws IOException {
    attacker.setOp(true);
    final var name = attacker.displayName();
    final var tabName = attacker.playerListName();
    final boolean collisions = attacker.isCollidable();
    final boolean sleeping = attacker.isSleepingIgnored();
    final boolean spawning = attacker.getAffectsSpawning();
    attacker.performCommand("vanish");
    assertTrue(plugin.vanish().isVanished(attacker.getUniqueId()));
    assertFalse(victim.canSee(attacker));
    assertTrue(plugin.vanish().canSee(attacker.getUniqueId(), attacker.getUniqueId()));
    assertFalse(plugin.active(attacker));
    assertTrue(attacker.getAllowFlight());
    assertFalse(attacker.isCollidable());
    assertFalse(attacker.getAffectsSpawning());
    assertTrue(attacker.isSleepingIgnored());
    assertEquals(name, attacker.displayName());
    assertEquals(tabName, attacker.playerListName());
    assertTrue(
        new VanishStore(plugin.getDataFolder().toPath().resolve("vanish.yml"))
            .load()
            .contains(attacker.getUniqueId()));
    attacker.performCommand("vanish off");
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
    assertTrue(victim.canSee(attacker));
    assertTrue(plugin.active(attacker));
    assertFalse(attacker.getAllowFlight());
    assertEquals(collisions, attacker.isCollidable());
    assertEquals(sleeping, attacker.isSleepingIgnored());
    assertEquals(spawning, attacker.getAffectsSpawning());
  }

  @Test
  void vanishAllowsAttacksWithoutTagsAndBlocksCombatTaggedEntry() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    EntityDamageByEntityEvent damage =
        new EntityDamageByEntityEvent(
            attacker,
            victim,
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker)
                .withDirectEntity(attacker)
                .build(),
            4);
    server.getPluginManager().callEvent(damage);
    assertFalse(damage.isCancelled());
    plugin.updateRule("vanish.prevent-attacking", true);
    damage.setCancelled(false);
    server.getPluginManager().callEvent(damage);
    assertTrue(damage.isCancelled());
    assertEquals(null, plugin.combat().tag(victim.getUniqueId(), System.currentTimeMillis()));
    EntityDamageEvent fall =
        new EntityDamageEvent(
            attacker,
            EntityDamageEvent.DamageCause.FALL,
            DamageSource.builder(DamageType.FALL).build(),
            5);
    server.getPluginManager().callEvent(fall);
    assertTrue(fall.isCancelled());
    plugin.vanish().setVanished(attacker, false);
    plugin
        .combat()
        .hit(attacker.getUniqueId(), victim.getUniqueId(), System.currentTimeMillis(), 30);
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> plugin.vanish().setVanished(attacker, true));
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
  }

  @Test
  void autoPermissionAlwaysVanishesOnJoinEvenAfterExplicitReveal() throws IOException {
    attacker.addAttachment(plugin, "epicsmp.vanish.auto", true);
    plugin.vanish().left(attacker);
    plugin.vanish().joined(attacker);
    assertTrue(plugin.vanish().isVanished(attacker.getUniqueId()));
    assertFalse(victim.canSee(attacker));
    plugin.vanish().setVanished(attacker, false);
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
    plugin.vanish().left(attacker);
    org.bukkit.event.player.PlayerJoinEvent join =
        new org.bukkit.event.player.PlayerJoinEvent(attacker, Component.text("Attacker joined"));
    server.getPluginManager().callEvent(join);
    assertTrue(plugin.vanish().isVanished(attacker.getUniqueId()));
    assertEquals(null, join.joinMessage());
  }

  @Test
  void permissionChangesUpdateVisibilityAndRevokedUseRestoresFlight() throws IOException {
    var permission = attacker.addAttachment(plugin);
    permission.setPermission("epicsmp.vanish", true);
    plugin.vanish().setVanished(attacker, true);
    assertFalse(victim.canSee(attacker));
    var see = victim.addAttachment(plugin, "epicsmp.vanish.see", true);
    server.getScheduler().performTicks(20);
    assertTrue(victim.canSee(attacker));
    see.setPermission("epicsmp.vanish.see", false);
    server.getScheduler().performTicks(20);
    assertFalse(victim.canSee(attacker));
    permission.setPermission("epicsmp.vanish", false);
    server.getScheduler().performTicks(20);
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
    assertFalse(attacker.getAllowFlight());
    assertTrue(victim.canSee(attacker));
  }

  @Test
  void vanishedContainersArePrivateSnapshotsAndEveryTransferIsCancelled() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    var block = attacker.getWorld().getBlockAt(5, 70, 5);
    block.setType(Material.CHEST);
    org.bukkit.block.Chest chest = (org.bukkit.block.Chest) block.getState();
    chest.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
    PlayerInteractEvent interaction =
        new PlayerInteractEvent(
            attacker,
            Action.RIGHT_CLICK_BLOCK,
            null,
            block,
            org.bukkit.block.BlockFace.UP,
            EquipmentSlot.HAND);
    server.getPluginManager().callEvent(interaction);
    assertEquals(org.bukkit.event.Event.Result.DENY, interaction.useInteractedBlock());
    server.getScheduler().performTicks(1);
    InventoryClickEvent transfer =
        new InventoryClickEvent(
            attacker.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            0,
            ClickType.SHIFT_LEFT,
            InventoryAction.MOVE_TO_OTHER_INVENTORY);
    server.getPluginManager().callEvent(transfer);
    assertTrue(transfer.isCancelled());
    assertEquals(3, chest.getInventory().getItem(0).getAmount());
    assertEquals(3, attacker.getOpenInventory().getTopInventory().getItem(0).getAmount());
    attacker.performCommand("smp admin menu");
    assertEquals(InventoryType.CHEST, attacker.getOpenInventory().getType());
  }

  @Test
  void vanishReloadRestoresFlightAndKeepsRealNightVisionIndependent() throws IOException {
    attacker.setOp(true);
    attacker.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 600, 0));
    var effect = attacker.getPotionEffect(PotionEffectType.NIGHT_VISION);
    plugin.vanish().setVanished(attacker, true);
    assertEquals(effect, attacker.getPotionEffect(PotionEffectType.NIGHT_VISION));
    assertTrue(attacker.getAllowFlight());
    plugin.updateRule("vanish.grant-flight", false);
    assertFalse(attacker.getAllowFlight());
    assertTrue(plugin.vanish().isVanished(attacker.getUniqueId()));
    plugin.vanish().setVanished(attacker, false);
    assertEquals(effect, attacker.getPotionEffect(PotionEffectType.NIGHT_VISION));
  }

  @Test
  void failedVanishSaveDoesNotHidePlayerOrGrantFlight() throws IOException {
    attacker.setOp(true);
    Path temporary = plugin.getDataFolder().toPath().resolve("vanish.yml.tmp");
    Files.createDirectory(temporary);
    try {
      org.junit.jupiter.api.Assertions.assertThrows(
          IOException.class, () -> plugin.vanish().setVanished(attacker, true));
      assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
      assertTrue(victim.canSee(attacker));
      assertFalse(attacker.getAllowFlight());
    } finally {
      Files.delete(temporary);
    }
  }

  @Test
  void savedCrashBaselineRestoresOriginalPropertiesOnVisibleJoin() throws IOException {
    attacker.setOp(true);
    attacker.setAllowFlight(true);
    attacker.setSilent(true);
    attacker.setCollidable(false);
    attacker.setSleepingIgnored(true);
    attacker.setAffectsSpawning(false);
    var key = new org.bukkit.NamespacedKey(plugin, "vanish_baseline");
    attacker
        .getPersistentDataContainer()
        .set(
            key,
            org.bukkit.persistence.PersistentDataType.STRING,
            "1;false;false;true;false;false;true;SURVIVAL");
    plugin.vanish().joined(attacker);
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
    assertFalse(attacker.getAllowFlight());
    assertFalse(attacker.isSilent());
    assertTrue(attacker.isCollidable());
    assertFalse(attacker.isSleepingIgnored());
    assertTrue(attacker.getAffectsSpawning());
    assertFalse(attacker.getPersistentDataContainer().has(key));
  }

  @Test
  void inspectionCannotBeOpenedByAnotherViewerOrRetainedAfterPermissionRevocation()
      throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    var block = attacker.getWorld().getBlockAt(5, 70, 5);
    block.setType(Material.CHEST);
    PlayerInteractEvent interaction =
        new PlayerInteractEvent(
            attacker,
            Action.RIGHT_CLICK_BLOCK,
            null,
            block,
            org.bukkit.block.BlockFace.UP,
            EquipmentSlot.HAND);
    server.getPluginManager().callEvent(interaction);
    server.getScheduler().performTicks(1);
    var snapshot = attacker.getOpenInventory().getTopInventory();
    victim.openInventory(snapshot);
    assertFalse(snapshot.equals(victim.getOpenInventory().getTopInventory()));
    attacker.addAttachment(plugin, "epicsmp.vanish.inspect", false);
    server.getScheduler().performTicks(20);
    assertFalse(snapshot.equals(attacker.getOpenInventory().getTopInventory()));
  }

  @Test
  void publicPingFiltersCountAndPlayerSampleFromTheSameSnapshot() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    var ping =
        org.mockito.Mockito.mock(
            com.destroystokyo.paper.event.server.PaperServerListPingEvent.class);
    var sample =
        new ArrayList<
            com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo>();
    sample.add(
        new com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo(
            attacker.getName(), attacker.getUniqueId()));
    sample.add(
        new com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo(
            victim.getName(), victim.getUniqueId()));
    org.mockito.Mockito.when(ping.getNumPlayers()).thenReturn(2);
    org.mockito.Mockito.when(ping.getListedPlayers()).thenReturn(sample);
    new VanishListener(plugin, plugin.vanish()).ping(ping);
    org.mockito.Mockito.verify(ping).setNumPlayers(1);
    assertEquals(
        java.util.List.of(victim.getUniqueId()),
        sample.stream()
            .map(com.destroystokyo.paper.event.server.PaperServerListPingEvent.ListedPlayerInfo::id)
            .toList());
  }

  @Test
  void vanishedChatFiltersPrivateAudiencesAndPublicSpeechUsesSystemPackets() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    final var event = org.mockito.Mockito.mock(io.papermc.paper.event.player.AsyncChatEvent.class);
    var unknownAudience = org.mockito.Mockito.mock(net.kyori.adventure.audience.Audience.class);
    var audiences = new java.util.HashSet<net.kyori.adventure.audience.Audience>();
    audiences.add(attacker);
    audiences.add(victim);
    audiences.add(server.getConsoleSender());
    audiences.add(unknownAudience);
    org.mockito.Mockito.when(event.getPlayer()).thenReturn(attacker);
    org.mockito.Mockito.when(event.viewers()).thenReturn(audiences);
    org.mockito.Mockito.when(event.message()).thenReturn(Component.text("Hello"));
    VanishListener listener = new VanishListener(plugin, plugin.vanish());
    listener.chat(event);
    assertEquals(java.util.Set.of(attacker, server.getConsoleSender()), audiences);
    attacker.performCommand("vanish chat public");
    audiences.clear();
    audiences.add(victim);
    var renderer = org.mockito.Mockito.mock(io.papermc.paper.chat.ChatRenderer.class);
    org.mockito.Mockito.when(event.renderer()).thenReturn(renderer);
    org.mockito.Mockito.when(event.message()).thenReturn(Component.text("Hello"));
    org.mockito.Mockito.when(
            renderer.render(attacker, attacker.displayName(), Component.text("Hello"), victim))
        .thenReturn(Component.text("Attacker: Hello"));
    var user =
        org.mockito.Mockito.mock(com.github.retrooper.packetevents.protocol.player.User.class);
    org.mockito.Mockito.when(
            com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getPlayerManager()
                .getUser(victim))
        .thenReturn(user);
    listener.chat(event);
    org.mockito.Mockito.verify(event, org.mockito.Mockito.times(2)).setCancelled(true);
    org.mockito.Mockito.verify(user)
        .sendPacketSilently(
            org.mockito.ArgumentMatchers.any(
                com.github.retrooper.packetevents.wrapper.play.server
                    .WrapperPlayServerSystemChatMessage.class));
    org.mockito.Mockito.when(
            com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getPlayerManager()
                .getUser(victim))
        .thenReturn(null);
  }

  // MockBukkit does not execute native entity item-pickup physics after firing the event.
  private void nativePickup(PlayerMock player, org.bukkit.entity.Item drop) {
    var remaining = player.getInventory().addItem(drop.getItemStack().clone());
    if (remaining.isEmpty()) {
      drop.remove();
    } else {
      drop.setItemStack(remaining.values().iterator().next());
    }
    server.getScheduler().performTicks(1);
  }

  @Test
  void pickupOffBlocksStrengthAndOrdinaryItemsAndDropDefaultsToAllowed() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    var token = ItemToken.Kind.STRENGTH;
    for (var stack :
        java.util.List.of(plugin.items().item(token), new ItemStack(Material.DIAMOND))) {
      var entity = attacker.getWorld().dropItem(attacker.getLocation(), stack);
      var attempt = new org.bukkit.event.player.PlayerAttemptPickupItemEvent(attacker, entity);
      server.getPluginManager().callEvent(attempt);
      assertTrue(attempt.isCancelled());
      assertFalse(attempt.getFlyAtPlayer());
      var pickup = new EntityPickupItemEvent(attacker, entity, 0);
      server.getPluginManager().callEvent(pickup);
      assertTrue(pickup.isCancelled());
      assertTrue(entity.isValid());
      assertTrue(attacker.getInventory().isEmpty());
    }
    attacker.performCommand("vanish pickup");
    var entity = attacker.getWorld().dropItem(attacker.getLocation(), plugin.items().item(token));
    var pickup = new EntityPickupItemEvent(attacker, entity, 0);
    server.getPluginManager().callEvent(pickup);
    assertFalse(pickup.isCancelled());
    nativePickup(attacker, entity);
    assertNotNull(attacker.getInventory().getItem(0));
    var dropped =
        attacker.getWorld().dropItem(attacker.getLocation(), new ItemStack(Material.DIAMOND));
    var drop = new PlayerDropItemEvent(attacker, dropped);
    server.getPluginManager().callEvent(drop);
    assertFalse(drop.isCancelled());
    attacker.performCommand("vanish option prevent-item-drop on");
    drop.setCancelled(false);
    server.getPluginManager().callEvent(drop);
    assertTrue(drop.isCancelled());
  }

  @Test
  void vanishedBlocksAndNormalClicksWorkAndRestrictionsReloadIndependently() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    var block = attacker.getWorld().getBlockAt(1, 70, 1);
    block.setType(Material.STONE);
    var breakEvent = new org.bukkit.event.block.BlockBreakEvent(block, attacker);
    server.getPluginManager().callEvent(breakEvent);
    assertFalse(breakEvent.isCancelled());
    var place =
        new org.bukkit.event.block.BlockPlaceEvent(
            block,
            block.getState(),
            block,
            new ItemStack(Material.STONE),
            attacker,
            true,
            EquipmentSlot.HAND);
    server.getPluginManager().callEvent(place);
    assertFalse(place.isCancelled());
    var click =
        new PlayerInteractEvent(
            attacker,
            Action.RIGHT_CLICK_BLOCK,
            new ItemStack(Material.STONE),
            block,
            org.bukkit.block.BlockFace.UP,
            EquipmentSlot.HAND);
    server.getPluginManager().callEvent(click);
    assertFalse(click.isCancelled());
    plugin.updateRule("vanish.prevent-block-breaking", true);
    server.getPluginManager().callEvent(breakEvent);
    assertTrue(breakEvent.isCancelled());
    assertFalse(place.isCancelled());
    plugin.updateRule("vanish.prevent-world-interactions", true);
    server.getPluginManager().callEvent(click);
    assertEquals(org.bukkit.event.Event.Result.DENY, click.useItemInHand());
  }

  @Test
  void infiniteNightVisionIsNotResentByEveryMaintenanceTick() throws IOException {
    attacker.setOp(true);
    var user =
        org.mockito.Mockito.mock(com.github.retrooper.packetevents.protocol.player.User.class);
    org.mockito.Mockito.when(user.getEncoderState())
        .thenReturn(com.github.retrooper.packetevents.protocol.ConnectionState.PLAY);
    org.mockito.Mockito.when(
            com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getPlayerManager()
                .getUser(attacker))
        .thenReturn(user);
    plugin.vanish().setVanished(attacker, true);
    server.getScheduler().performTicks(80);
    var packets =
        org.mockito.ArgumentCaptor.forClass(
            com.github.retrooper.packetevents.wrapper.PacketWrapper.class);
    org.mockito.Mockito.verify(user, org.mockito.Mockito.times(1))
        .sendPacketSilently(packets.capture());
    var effect =
        (com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect)
            packets.getValue();
    assertEquals(-1, effect.getEffectDurationTicks());
    plugin.updateRule("vanish.client-night-vision", false);
    org.mockito.Mockito.verify(user, org.mockito.Mockito.times(2))
        .sendPacketSilently(
            org.mockito.ArgumentMatchers.any(
                com.github.retrooper.packetevents.wrapper.PacketWrapper.class));
    org.mockito.Mockito.when(
            com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getPlayerManager()
                .getUser(attacker))
        .thenReturn(null);
  }

  @Test
  void passiveMobLookIgnoresHiddenStaffAndReturnsToNormalWhenDisabled() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    var mob = org.mockito.Mockito.mock(org.bukkit.entity.Mob.class);
    org.mockito.Mockito.when(mob.isValid()).thenReturn(true);
    org.mockito.Mockito.when(mob.getLocation()).thenReturn(attacker.getLocation());
    org.mockito.Mockito.when(mob.getNearbyEntities(16, 16, 16))
        .thenReturn(java.util.List.of(attacker, victim));
    var goal = new VanishMobLook(plugin, plugin.vanish(), mob);
    assertTrue(goal.shouldActivate());
    goal.tick();
    org.mockito.Mockito.verify(mob).lookAt(victim);
    plugin.updateRule("vanish.prevent-mob-looking", false);
    assertFalse(goal.shouldActivate());
    assertEquals(
        java.util.EnumSet.of(com.destroystokyo.paper.entity.ai.GoalType.LOOK), goal.getTypes());
  }

  @Test
  void nativeStrengthPickupRespectsCancellationAndFullStorage() throws IOException {
    var token = ItemToken.Kind.STRENGTH;
    var stack = plugin.items().item(token);
    var entity = attacker.getWorld().dropItem(attacker.getLocation(), stack);
    var cancelled = new EntityPickupItemEvent(attacker, entity, 0);
    cancelled.setCancelled(true);
    server.getPluginManager().callEvent(cancelled);
    server.getScheduler().performTicks(1);
    assertTrue(entity.isValid());
    assertTrue(attacker.getInventory().isEmpty());
    for (int slot = 0; slot < 36; slot++) {
      attacker.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }
    var full = new EntityPickupItemEvent(attacker, entity, 1);
    server.getPluginManager().callEvent(full);
    // Vanilla reports the uncollected item; this boundary does not run pickup physics in
    // MockBukkit.
    server.getScheduler().performTicks(1);
    assertTrue(entity.isValid());
    assertEquals(stack, entity.getItemStack());
    assertFalse(attacker.getInventory().contains(Material.GHAST_TEAR));
  }

  @Test
  void privateChatIncludesHiddenParticipantsAndResetsOnVanishEntry() throws IOException {
    attacker.setOp(true);
    victim.addAttachment(plugin, "epicsmp.vanish", true);
    victim.addAttachment(plugin, "epicsmp.vanish.see", false);
    plugin.vanish().setVanished(attacker, true);
    plugin.vanish().setVanished(victim, true);
    var regular = server.addPlayer("RegularViewer");
    final var event = org.mockito.Mockito.mock(io.papermc.paper.event.player.AsyncChatEvent.class);
    var audiences = new java.util.HashSet<net.kyori.adventure.audience.Audience>();
    audiences.add(attacker);
    audiences.add(victim);
    audiences.add(regular);
    org.mockito.Mockito.when(event.getPlayer()).thenReturn(attacker);
    org.mockito.Mockito.when(event.viewers()).thenReturn(audiences);
    org.mockito.Mockito.when(event.message()).thenReturn(Component.text("Staff message"));
    new VanishListener(plugin, plugin.vanish()).chat(event);
    assertEquals(java.util.Set.of(attacker, victim), audiences);
    attacker.performCommand("vanish chat public");
    assertTrue(plugin.vanish().publicChat(attacker.getUniqueId()));
    plugin.vanish().setVanished(attacker, false);
    plugin.vanish().setVanished(attacker, true);
    assertFalse(plugin.vanish().publicChat(attacker.getUniqueId()));
  }

  @Test
  void taggedStrengthLeavesInventoryClicksDragsHoppersAndGroundMergesNative() {
    var item = plugin.items().item(ItemToken.Kind.STRENGTH);
    var another = plugin.items().item(ItemToken.Kind.STRENGTH);
    assertTrue(item.isSimilar(another));
    assertFalse(item.isSimilar(plugin.items().item(ItemToken.Kind.CRAFTED_STRENGTH)));
    assertFalse(
        item.getPersistentDataContainer()
            .has(new org.bukkit.NamespacedKey(plugin, "strength_bottle")));
    attacker.getInventory().setItem(0, item);
    var top = server.createInventory(null, 9);
    var view =
        new org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock(
            attacker, top, attacker.getInventory(), InventoryType.CHEST) {
          @Override
          public int convertSlot(int slot) {
            return slot < 9 ? slot : slot - 9;
          }

          @Override
          public org.bukkit.inventory.Inventory getInventory(int slot) {
            return slot < 0 ? null : slot < 9 ? top : attacker.getInventory();
          }
        };

    for (ClickType click :
        java.util.List.of(
            ClickType.LEFT,
            ClickType.RIGHT,
            ClickType.SHIFT_LEFT,
            ClickType.DOUBLE_CLICK,
            ClickType.NUMBER_KEY)) {
      var event =
          new InventoryClickEvent(
              view, InventoryType.SlotType.CONTAINER, 9, click, InventoryAction.PICKUP_ALL, 1);
      server.getPluginManager().callEvent(event);
      assertFalse(event.isCancelled(), click.name());
    }
    var drag = new InventoryDragEvent(view, item, item, false, java.util.Map.of(9, item.clone()));
    server.getPluginManager().callEvent(drag);
    assertFalse(drag.isCancelled());
    var source = server.createInventory(null, 9);
    var destination = server.createInventory(null, 9);
    source.setItem(0, item);
    var move =
        new org.bukkit.event.inventory.InventoryMoveItemEvent(source, item, destination, true);
    server.getPluginManager().callEvent(move);
    assertFalse(move.isCancelled());
    var first = attacker.getWorld().dropItem(attacker.getLocation(), item);
    var second = attacker.getWorld().dropItem(attacker.getLocation(), another);
    var merge = new org.bukkit.event.entity.ItemMergeEvent(first, second);
    server.getPluginManager().callEvent(merge);
    assertFalse(merge.isCancelled());
  }

  @Test
  void oldBundlesConvertOnPickupAndCopiedOldBundlesCannotConvertAgain() throws Exception {
    java.util.UUID id = java.util.UUID.randomUUID();
    State current = plugin.progression().snapshot();
    var path = directory.resolve("legacy.yml");
    var store = new YamlStateStore(path);
    store.save(
        new State(
            current.players(),
            current.pairs(),
            java.util.Map.of(
                id, new ItemToken(attacker.getUniqueId(), ItemToken.Kind.CRAFTED_STRENGTH))));
    var yaml = YamlConfiguration.loadConfiguration(path.toFile());
    yaml.set("schema", 3);
    yaml.save(path.toFile());
    var replacement = new Progression(store, plugin.settings());
    var field = EpicSmpPlugin.class.getDeclaredField("progression");
    field.setAccessible(true);
    field.set(plugin, replacement);
    var old = plugin.items().item(ItemToken.Kind.CRAFTED_STRENGTH);
    var meta = old.getItemMeta();
    meta.getPersistentDataContainer().remove(new org.bukkit.NamespacedKey(plugin, "item_format"));
    meta.getPersistentDataContainer()
        .set(
            new org.bukkit.NamespacedKey(plugin, "strength_bottle"),
            org.bukkit.persistence.PersistentDataType.STRING,
            id.toString());
    old.setItemMeta(meta);
    final var copied = old.clone();
    var ground = attacker.getWorld().dropItem(attacker.getLocation(), old);
    var pickup = new EntityPickupItemEvent(attacker, ground, 0);
    server.getPluginManager().callEvent(pickup);
    assertFalse(pickup.isCancelled());
    assertTrue(ground.isValid());
    assertTrue(
        ground.getItemStack().isSimilar(plugin.items().item(ItemToken.Kind.CRAFTED_STRENGTH)));
    assertTrue(replacement.snapshot().bottles().isEmpty());
    assertTrue(new YamlStateStore(path).load().bottles().isEmpty());
    victim.getInventory().setItemInMainHand(copied);
    useCustom(victim, EquipmentSlot.HAND);
    assertEquals(1, replacement.profile(victim.getUniqueId()).strength());
    assertEquals(copied, victim.getInventory().getItemInMainHand());
    nativePickup(attacker, ground);
    useCustom(attacker, EquipmentSlot.HAND);
    assertEquals(2, replacement.profile(attacker.getUniqueId()).strength());
    assertEquals(1, replacement.profile(attacker.getUniqueId()).craftedStrength());
  }

  @Test
  void groupedInteractionsPersistTogetherAndIndividualOptionsHaveClickableToggles()
      throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    attacker.performCommand("vanish interactions on");
    for (String option : Settings.VANISH_INTERACTION_GUARDS) {
      assertFalse(plugin.settings().flag("vanish." + option), option);
    }
    assertTrue(plugin.vanish().picksUp(attacker));
    assertFalse(plugin.settings().flag("vanish.silent-container-inspection"));
    assertTrue(plugin.settings().flag("vanish.prevent-incoming-damage"));
    assertFalse(plugin.vanish().publicChat(attacker.getUniqueId()));
    plugin.reloadRules();
    assertFalse(plugin.settings().flag("vanish.prevent-attacking"));
    attacker.performCommand("vanish interactions toggle");
    for (String option : Settings.VANISH_INTERACTION_GUARDS) {
      assertTrue(plugin.settings().flag("vanish." + option), option);
    }
    assertFalse(plugin.vanish().picksUp(attacker));
    while (attacker.nextComponentMessage() != null) {}
    attacker.performCommand("vanish options");
    Component message;
    boolean found = false;
    while ((message = attacker.nextComponentMessage()) != null) {
      if (plain(message).startsWith("prevent-item-drop ")) {
        var click = message.children().getFirst().clickEvent();
        assertNotNull(click);
        assertEquals(
            net.kyori.adventure.text.event.ClickEvent.runCommand(
                "/vanish option prevent-item-drop toggle"),
            click);
        found = true;
      }
    }
    assertTrue(found);
    attacker.performCommand("vanish option prevent-item-drop toggle");
    assertFalse(plugin.settings().flag("vanish.prevent-item-drop"));
    victim.performCommand("vanish interactions on");
    assertTrue(plugin.settings().flag("vanish.prevent-attacking"));
  }

  @Test
  void withdrawalUsesMatchingStackCapacityBeforeEmptySlotsAndPreservesBothKinds()
      throws IOException {
    plugin.progression().redeem(attacker.getUniqueId(), ItemToken.Kind.CRAFTED_STRENGTH);
    plugin.progression().redeem(attacker.getUniqueId(), ItemToken.Kind.CRAFTED_STRENGTH);
    plugin.progression().redeem(attacker.getUniqueId(), ItemToken.Kind.STRENGTH);
    for (int slot = 0; slot < 36; slot++) {
      attacker.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
    }
    attacker.getInventory().setItem(0, null);
    var crafted = plugin.items().item(ItemToken.Kind.CRAFTED_STRENGTH);
    crafted.setAmount(62);
    attacker.getInventory().setItem(1, crafted);
    plugin.items().withdraw(attacker, 3);
    assertEquals(1, plugin.progression().profile(attacker.getUniqueId()).strength());
    assertEquals(0, plugin.progression().profile(attacker.getUniqueId()).craftedStrength());
    assertEquals(64, attacker.getInventory().getItem(1).getAmount());
    assertTrue(
        attacker.getInventory().getItem(0).isSimilar(plugin.items().item(ItemToken.Kind.STRENGTH)));
    assertEquals(1, attacker.getInventory().getItem(0).getAmount());
  }

  @Test
  void revealedAutoVanishPlayerStaysVisibleAcrossRespawnWorldAndModeChanges() throws IOException {
    attacker.addAttachment(plugin, "epicsmp.vanish.auto", true);
    plugin.vanish().left(attacker);
    plugin.vanish().prepareJoin(attacker);
    assertTrue(plugin.vanish().isVanished(attacker.getUniqueId()));
    plugin.vanish().setVanished(attacker, false);
    // A second phase of the same login must preserve an explicit reveal as well.
    plugin.vanish().joined(attacker);
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
    var respawn = org.mockito.Mockito.mock(org.bukkit.event.player.PlayerRespawnEvent.class);
    org.mockito.Mockito.when(respawn.getPlayer()).thenReturn(attacker);
    org.mockito.Mockito.when(respawn.getHandlers())
        .thenReturn(org.bukkit.event.player.PlayerRespawnEvent.getHandlerList());
    server.getPluginManager().callEvent(respawn);
    server.getScheduler().performTicks(1);
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
    assertTrue(victim.canSee(attacker));
    server
        .getPluginManager()
        .callEvent(
            new org.bukkit.event.player.PlayerChangedWorldEvent(attacker, attacker.getWorld()));
    attacker.setGameMode(GameMode.CREATIVE);
    server.getScheduler().performTicks(21);
    plugin.reloadRules();
    assertFalse(plugin.vanish().isVanished(attacker.getUniqueId()));
    plugin.vanish().left(attacker);
    plugin.vanish().prepareJoin(attacker);
    assertTrue(plugin.vanish().isVanished(attacker.getUniqueId()));
  }

  @Test
  void vanishedCreativePlayersCanUseWithdrawAndRerollItemsWithNormalCaps() throws IOException {
    attacker.setOp(true);
    plugin.vanish().setVanished(attacker, true);
    attacker.setGameMode(GameMode.CREATIVE);
    server.getScheduler().performTicks(1);
    var crafted = plugin.items().item(ItemToken.Kind.CRAFTED_STRENGTH);
    crafted.setAmount(3);
    attacker.getInventory().setItemInMainHand(crafted);
    useCustom(attacker, EquipmentSlot.HAND);
    useCustom(attacker, EquipmentSlot.HAND);
    useCustom(attacker, EquipmentSlot.HAND);
    assertEquals(3, plugin.progression().profile(attacker.getUniqueId()).strength());
    assertEquals(1, attacker.getInventory().getItemInMainHand().getAmount());
    attacker.getInventory().setItemInMainHand(plugin.items().item(ItemToken.Kind.STRENGTH));
    useCustom(attacker, EquipmentSlot.HAND);
    assertEquals(4, plugin.progression().profile(attacker.getUniqueId()).strength());
    plugin.items().withdraw(attacker, 3);
    assertEquals(1, plugin.progression().profile(attacker.getUniqueId()).strength());
    var original = plugin.progression().profile(attacker.getUniqueId()).weapon();
    attacker.getInventory().setItemInMainHand(plugin.items().item(ItemToken.Kind.REROLL));
    useCustom(attacker, EquipmentSlot.HAND);
    assertFalse(original == plugin.progression().profile(attacker.getUniqueId()).weapon());
    assertTrue(
        attacker.getInventory().getItemInMainHand() == null
            || attacker.getInventory().getItemInMainHand().getType().isAir());
    assertEquals(GameMode.CREATIVE, attacker.getGameMode());
    assertTrue(plugin.vanish().isVanished(attacker.getUniqueId()));
  }

  @Test
  void combatTaggedFallDeathConvertsLostCraftedStrengthToRegularDrop() throws IOException {
    plugin.progression().set(attacker.getUniqueId(), "strength", 5);
    plugin.progression().redeem(victim.getUniqueId(), ItemToken.Kind.CRAFTED_STRENGTH);
    attack(4);
    var death =
        new PlayerDeathEvent(
            victim,
            DamageSource.builder(DamageType.FALL).build(),
            new ArrayList<>(),
            0,
            (Component) null,
            true);
    server.getPluginManager().callEvent(death);
    assertEquals(1, plugin.progression().profile(attacker.getUniqueId()).kills());
    assertEquals(0, plugin.progression().profile(victim.getUniqueId()).craftedStrength());
    var drops =
        victim.getWorld().getEntities().stream()
            .filter(org.bukkit.entity.Item.class::isInstance)
            .map(org.bukkit.entity.Item.class::cast)
            .map(org.bukkit.entity.Item::getItemStack)
            .toList();
    assertEquals(1, drops.size());
    assertTrue(drops.getFirst().isSimilar(plugin.items().item(ItemToken.Kind.STRENGTH)));
  }

  private void menuClick(int slot) {
    InventoryClickEvent click =
        new InventoryClickEvent(
            attacker.getOpenInventory(),
            InventoryType.SlotType.CONTAINER,
            slot,
            ClickType.LEFT,
            InventoryAction.PICKUP_ALL);
    server.getPluginManager().callEvent(click);
    assertTrue(click.isCancelled());
    server.getScheduler().performTicks(1);
  }

  private static class TestServerMock extends ServerMock {
    private boolean stopping;
    private final com.destroystokyo.paper.entity.ai.MobGoals goals =
        org.mockito.Mockito.mock(com.destroystokyo.paper.entity.ai.MobGoals.class);

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "MockBukkit transport boundary exposes the same shared goal manager as Paper")
    public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() {
      return goals;
    }

    @Override
    public boolean isStopping() {
      // MockBukkit 4.117.0 leaves this Paper lifecycle method unimplemented.
      return stopping;
    }
  }
}
