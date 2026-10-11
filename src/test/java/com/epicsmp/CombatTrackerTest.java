package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CombatTrackerTest {
  @Test
  void tagsExpireAndMostRecentOpponentGetsCredit() {
    CombatTracker combat = new CombatTracker();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    UUID third = UUID.randomUUID();
    combat.hit(first, second, 1000, 30);
    combat.hit(third, second, 2000, 30);
    assertEquals(third, combat.tag(second, 2001).opponent());
    assertEquals(second, combat.tag(first, 2001).opponent());
    assertNull(combat.tag(second, 32000));
  }

  @Test
  void cooldownCannotBeShortenedAndExpiresExactlyAtBoundary() {
    CombatTracker combat = new CombatTracker();
    UUID id = UUID.randomUUID();
    combat.cooldown(id, "ENDER_PEARL", 1000, 300);
    combat.cooldown(id, "ENDER_PEARL", 2000, 1);
    assertEquals(14000, combat.remaining(id, "ENDER_PEARL", 2000));
    assertEquals(0, combat.remaining(id, "WIND_CHARGE", 2000));
    assertEquals(0, combat.remaining(id, "ENDER_PEARL", 16000));
  }

  @Test
  void adminCooldownClearLeavesOtherAccountsAndItemsIntact() {
    CombatTracker combat = new CombatTracker();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    combat.cooldown(first, "SPEAR_LUNGE", 1000, 60);
    combat.cooldown(first, "MACE_HIT", 1000, 100);
    combat.cooldown(second, "SPEAR_LUNGE", 1000, 60);
    combat.clearCooldowns(first, "SPEAR_LUNGE");
    assertEquals(0, combat.remaining(first, "SPEAR_LUNGE", 1000));
    assertEquals(5000, combat.remaining(first, "MACE_HIT", 1000));
    combat.clearCooldowns(first, null);
    assertEquals(0, combat.remaining(first, "MACE_HIT", 1000));
    assertEquals(3000, combat.remaining(second, "SPEAR_LUNGE", 1000));
  }
}
