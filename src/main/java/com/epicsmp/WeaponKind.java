package com.epicsmp;

import java.security.SecureRandom;
import org.bukkit.Material;

/** Season Four's six documented weapon specialties. */
public enum WeaponKind {
  SWORD,
  AXE,
  BOW,
  CROSSBOW,
  TRIDENT,
  SHIELD;

  private static final SecureRandom RANDOM = new SecureRandom();

  /** Selects a different specialty for a weapon reroll. */
  public static WeaponKind randomExcept(WeaponKind previous) {
    WeaponKind[] choices = values();
    WeaponKind result;
    do {
      result = choices[RANDOM.nextInt(choices.length)];
    } while (result == previous);
    return result;
  }

  /** Tests a held material against this specialty. */
  public boolean matches(Material material) {
    return switch (this) {
      case SWORD -> material.name().endsWith("_SWORD");
      case AXE -> material.name().endsWith("_AXE");
      case BOW -> material == Material.BOW;
      case CROSSBOW -> material == Material.CROSSBOW;
      case TRIDENT -> material == Material.TRIDENT;
      case SHIELD -> material == Material.SHIELD;
    };
  }
}
