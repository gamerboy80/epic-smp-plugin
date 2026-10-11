package com.epicsmp;

/** Durable player Strength, its crafted contribution, weapon specialty and combat statistics. */
public record Profile(
    String name,
    int strength,
    int craftedStrength,
    WeaponKind weapon,
    int kills,
    int deaths,
    long protectionUntil) {
  /** Creates a baseline profile without a crafted contribution. */
  public Profile(String name, int strength, int kills, int deaths, long until) {
    this(name, strength, 0, WeaponKind.randomExcept(null), kills, deaths, until);
  }

  /** Returns a copy with updated progression; lost levels consume crafted contributions first. */
  public Profile progress(int newStrength, int newKills, int newDeaths) {
    return new Profile(
        name,
        newStrength,
        Math.max(
            0, Math.min(newStrength - 1, craftedStrength - Math.max(0, strength - newStrength))),
        weapon,
        newKills,
        newDeaths,
        protectionUntil);
  }

  /** Returns a copy with the current account name and protection expiry. */
  public Profile identity(String newName, long until) {
    return new Profile(newName, strength, craftedStrength, weapon, kills, deaths, until);
  }

  /** Returns a copy with a replacement weapon specialty. */
  public Profile specialty(WeaponKind next) {
    return new Profile(name, strength, craftedStrength, next, kills, deaths, protectionUntil);
  }
}
