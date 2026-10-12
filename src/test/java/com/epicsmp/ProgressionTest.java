package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class ProgressionTest {
  private final UUID winner = UUID.randomUUID();
  private final UUID victim = UUID.randomUUID();
  private YamlConfiguration config;
  private MemoryStore store;
  private Progression engine;

  @BeforeEach
  void setup() throws IOException {
    MockBukkit.mock();
    config =
        YamlConfiguration.loadConfiguration(
            new InputStreamReader(
                getClass().getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
    store = new MemoryStore();
    engine = new Progression(store, new Settings(config));
    engine.join(winner, "Winner", 1000);
    engine.join(victim, "Victim", 1000);
  }

  @AfterEach
  void cleanup() {
    MockBukkit.unmock();
  }

  @Test
  void qualifyingKillUpdatesStrengthExactlyOnceAndKeepsFloor() throws IOException {
    assertTrue(engine.death(victim, winner, 2000).applied());
    assertEquals(2, engine.profile(winner).strength());
    assertEquals(1, engine.profile(victim).strength());
    assertEquals(1, engine.profile(winner).kills());
    assertEquals(1, engine.profile(victim).deaths());
    assertTrue(engine.death(victim, winner, 2001).protectedKill());
    assertEquals(1, engine.profile(winner).kills());
  }

  @Test
  void pairWindowSurvivesRestartAndBlocksReverseFarming() throws IOException {
    engine.death(victim, winner, 2000);
    State saved = engine.snapshot();
    engine = new Progression(store, new Settings(config));
    assertTrue(engine.death(winner, victim, 3000).protectedKill());
    assertEquals(saved, engine.snapshot());
    assertTrue(engine.death(winner, victim, 602000).applied());
    assertEquals(1, engine.profile(winner).strength());
    assertEquals(2, engine.profile(victim).strength());
  }

  @Test
  void immunityDefaultsOffAndDisablingItClearsPersistentGrace() throws IOException {
    assertEquals(0, engine.profile(winner).protectionUntil());
    config.set("combat.immunity-enabled", true);
    config.set("combat.new-player-protection-seconds", 30);
    engine = new Progression(new MemoryStore(), new Settings(config));
    engine.join(winner, "Winner", 1000);
    engine.join(victim, "Victim", 1000);
    assertTrue(engine.death(victim, winner, 30999).protectedKill());
    config.set("combat.immunity-enabled", false);
    engine.normalize(new Settings(config));
    assertEquals(0, engine.profile(winner).protectionUntil());
    assertTrue(engine.death(victim, winner, 31000).applied());
  }

  @Test
  void naturalDeathLosesOneLevelAndNeverGoesBelowOne() throws IOException {
    engine.set(victim, "strength", 3);
    engine.death(victim, null, 2000);
    assertEquals(2, engine.profile(victim).strength());
    engine.death(victim, null, 3000);
    engine.death(victim, null, 4000);
    assertEquals(1, engine.profile(victim).strength());
  }

  @Test
  void cappedKillerGetsOverflowItemOnlyWhenVictimCanLoseStrength() throws IOException {
    engine.set(winner, "strength", 5);
    engine.set(victim, "strength", 2);
    Progression.DeathResult result = engine.death(victim, winner, 2000);
    assertEquals(1, result.drops().size());
    assertEquals(5, engine.profile(winner).strength());
    assertEquals(1, engine.profile(victim).strength());
    assertEquals(ItemToken.Kind.STRENGTH, result.drops().getFirst());
    engine.clearPair(winner, victim);
    assertTrue(engine.death(victim, winner, 3000).drops().isEmpty());
  }

  @Test
  void withdrawalTradesToAnotherPlayerAndPersistsRedemptionAcrossRestart() throws IOException {
    engine.set(winner, "strength", 3);
    List<ItemToken.Kind> items = engine.withdraw(winner, 2);
    assertEquals(1, engine.profile(winner).strength());
    engine.redeem(victim, items.getFirst());
    assertEquals(2, engine.profile(victim).strength());
    engine = new Progression(store, new Settings(config));
    assertEquals(2, engine.profile(victim).strength());
    engine.redeem(victim, items.getLast());
    assertEquals(3, engine.profile(victim).strength());
    assertTrue(engine.snapshot().bottles().isEmpty());
  }

  @Test
  void withdrawalRejectsFloorNegativeAndExcessAmountsWithoutSaving() {
    State before = engine.snapshot();
    for (int amount : List.of(-1, 0, 1, Integer.MAX_VALUE)) {
      assertThrows(IllegalArgumentException.class, () -> engine.withdraw(winner, amount));
      assertEquals(before, engine.snapshot());
    }
  }

  @Test
  void craftedLevelsCannotBeLaunderedThroughWithdrawalOrTrading() throws IOException {
    ItemToken.Kind first = ItemToken.Kind.CRAFTED_STRENGTH;
    ItemToken.Kind second = ItemToken.Kind.CRAFTED_STRENGTH;
    engine.redeem(winner, first);
    engine.redeem(winner, second);
    assertEquals(2, engine.profile(winner).craftedStrength());
    ItemToken.Kind excess = ItemToken.Kind.CRAFTED_STRENGTH;
    assertThrows(IllegalArgumentException.class, () -> engine.redeem(winner, excess));
    List<ItemToken.Kind> withdrawn = engine.withdraw(winner, 2);
    assertEquals(0, engine.profile(winner).craftedStrength());
    engine.set(victim, "strength", 3);
    assertEquals(ItemToken.Kind.CRAFTED_STRENGTH, withdrawn.getFirst());
    assertThrows(IllegalArgumentException.class, () -> engine.redeem(victim, withdrawn.getFirst()));
    engine.redeem(winner, withdrawn.getFirst());
    assertEquals(2, engine.profile(winner).strength());
    assertEquals(1, engine.profile(winner).craftedStrength());
  }

  @Test
  void rerollPersistsDifferentSpecialty() throws IOException {
    WeaponKind original = engine.profile(winner).weapon();
    engine.reroll(winner);
    assertNotEquals(original, engine.profile(winner).weapon());
    engine = new Progression(store, new Settings(config));
    assertNotEquals(original, engine.profile(winner).weapon());
  }

  @Test
  void failedWritesDoNotPartiallyMutateProgressionOrConsumeItems() throws IOException {
    engine.set(winner, "strength", 3);
    final ItemToken.Kind token = ItemToken.Kind.STRENGTH;
    State before = engine.snapshot();
    store.fail = true;
    assertThrows(IOException.class, () -> engine.death(victim, winner, 2000));
    assertEquals(before, engine.snapshot());
    assertThrows(IOException.class, () -> engine.withdraw(winner, 1));
    assertEquals(before, engine.snapshot());
    assertThrows(IOException.class, () -> engine.redeem(victim, token));
    assertEquals(before, engine.snapshot());
  }

  @Test
  void normalizeClampsLevelsPreservesItemsAndStatistics() throws IOException {
    engine.set(winner, "strength", 5);
    engine.death(victim, winner, 2000);
    config.set("strength.maximum", 3);
    engine.normalize(new Settings(config));
    assertEquals(3, engine.profile(winner).strength());
    assertEquals(1, engine.profile(winner).kills());
    assertTrue(engine.snapshot().bottles().isEmpty());
  }

  @Test
  void legacyBundlesMigrateOnceAndFailedWritesPreserveTheirRegistration() throws IOException {
    UUID token = UUID.randomUUID();
    State prior = engine.snapshot();
    store.state =
        new State(
            prior.players(),
            prior.pairs(),
            Map.of(token, new ItemToken(winner, ItemToken.Kind.STRENGTH)));
    engine = new Progression(store, new Settings(config));
    store.fail = true;
    assertThrows(
        IOException.class, () -> engine.migrateItems(List.of(token), ItemToken.Kind.STRENGTH));
    assertTrue(engine.snapshot().bottles().containsKey(token));
    store.fail = false;
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.migrateItems(List.of(token), ItemToken.Kind.CRAFTED_STRENGTH));
    engine.migrateItems(List.of(token), ItemToken.Kind.STRENGTH);
    assertTrue(engine.snapshot().bottles().isEmpty());
    engine = new Progression(store, new Settings(config));
    assertThrows(
        IllegalArgumentException.class,
        () -> engine.migrateItems(List.of(token), ItemToken.Kind.STRENGTH));
  }

  @Test
  void craftedStrengthMaturesIntoRegularDropsOnCreditedKillsAndRespectsPairProtection()
      throws IOException {
    engine.set(winner, "strength", 5);
    engine.redeem(victim, ItemToken.Kind.CRAFTED_STRENGTH);
    engine.redeem(victim, ItemToken.Kind.CRAFTED_STRENGTH);
    var result = engine.death(victim, winner, 2000);
    assertEquals(List.of(ItemToken.Kind.STRENGTH), result.drops());
    assertEquals(2, engine.profile(victim).strength());
    assertEquals(1, engine.profile(victim).craftedStrength());
    State committed = engine.snapshot();
    assertTrue(engine.death(victim, winner, 3000).protectedKill());
    assertEquals(committed, engine.snapshot());
    engine = new Progression(store, new Settings(config));
    assertEquals(1, engine.profile(victim).craftedStrength());
  }

  @Test
  void environmentalAndSelfDeathsRetainCraftedDropKind() throws IOException {
    engine.redeem(victim, ItemToken.Kind.CRAFTED_STRENGTH);
    engine.redeem(victim, ItemToken.Kind.CRAFTED_STRENGTH);
    assertEquals(
        List.of(ItemToken.Kind.CRAFTED_STRENGTH), engine.death(victim, null, 2000).drops());
    assertEquals(1, engine.profile(victim).craftedStrength());
    assertEquals(
        List.of(ItemToken.Kind.CRAFTED_STRENGTH), engine.death(victim, victim, 3000).drops());
    assertEquals(0, engine.profile(victim).craftedStrength());
    assertEquals(1, engine.profile(victim).strength());
  }

  @Test
  void creditedKillsBelowTheCapAwardRegularLevelsFromCraftedVictims() throws IOException {
    engine.redeem(victim, ItemToken.Kind.CRAFTED_STRENGTH);
    var result = engine.death(victim, winner, 2000);
    assertTrue(result.drops().isEmpty());
    assertEquals(2, engine.profile(winner).strength());
    assertEquals(0, engine.profile(winner).craftedStrength());
    assertEquals(List.of(ItemToken.Kind.STRENGTH), engine.withdraw(winner, 1));
  }

  private static class MemoryStore implements StateStore {
    private State state = new State(Map.of(), Map.of());
    private boolean fail;

    @Override
    public State load() {
      return state;
    }

    @Override
    public void save(State next) throws IOException {
      if (fail) {
        throw new IOException("Simulated full disk");
      }
      state = next;
    }
  }
}
