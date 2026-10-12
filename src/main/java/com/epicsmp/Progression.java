package com.epicsmp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Main-thread progression engine; every mutation is persisted before becoming visible. */
public final class Progression {
  private final StateStore store;
  private Settings settings;
  private State state;

  /** Restores progression and normalizes it to configured limits. */
  public Progression(StateStore store, Settings settings) throws IOException {
    this.store = store;
    this.settings = settings;
    state = store.load();
    normalize(settings);
  }

  private void commit(State next) throws IOException {
    store.save(next);
    state = next;
  }

  /** Clamps saved Strength and clears immunity when disabled. */
  public void normalize(Settings next) throws IOException {
    Map<UUID, Profile> players = new HashMap<>();
    state
        .players()
        .forEach(
            (id, profile) ->
                players.put(
                    id,
                    profile
                        .progress(
                            clamp(
                                profile.strength(),
                                next.integer("strength.minimum"),
                                next.integer("strength.maximum")),
                            profile.kills(),
                            profile.deaths())
                        .identity(
                            profile.name(),
                            next.flag("combat.immunity-enabled") ? profile.protectionUntil() : 0)));
    commit(new State(players, state.pairs(), state.bottles()));
    settings = next;
  }

  /** Reads a profile; null indicates a player who has never joined. */
  public Profile profile(UUID id) {
    return state.players().get(id);
  }

  /** Returns the immutable active snapshot. */
  public State snapshot() {
    return state;
  }

  /** Updates account names and creates baseline progression on first join. */
  public void join(UUID id, String name, long now) throws IOException {
    Profile existing = profile(id);
    Map<UUID, Profile> players = new HashMap<>(state.players());
    if (existing == null) {
      existing =
          new Profile(
              name,
              settings.integer("strength.starting"),
              0,
              0,
              protectionExpiry(now, "combat.new-player-protection-seconds"));
    }
    players.put(id, existing.identity(name, existing.protectionUntil()));
    commit(new State(players, state.pairs(), state.bottles()));
  }

  /** Applies one death, using a symmetric persisted farming window for each player pair. */
  public DeathResult death(UUID victimId, UUID killerId, long now) throws IOException {
    Profile victim = profile(victimId);
    if (victim == null) {
      return new DeathResult(false, false, List.of());
    }
    Profile killer = killerId == null ? null : profile(killerId);
    boolean pvp = killer != null && !victimId.equals(killerId);
    String pair = pvp ? pairKey(victimId, killerId) : "";
    if (pvp
        && (settings.flag("combat.immunity-enabled")
                && (victim.protectionUntil() > now || killer.protectionUntil() > now)
            || state.pairs().getOrDefault(pair, 0L) > now)) {
      return new DeathResult(false, true, List.of());
    }
    Map<UUID, Profile> players = new HashMap<>(state.players());
    Map<String, Long> pairs = new HashMap<>(state.pairs());
    final Map<UUID, ItemToken> bottles = state.bottles();
    List<ItemToken.Kind> drops = new ArrayList<>();
    pairs.entrySet().removeIf(entry -> entry.getValue() <= now);
    int loss = settings.integer(pvp ? "strength.per-kill" : "strength.natural-death-loss");
    int strength =
        clamp(
            victim.strength() - loss,
            settings.integer("strength.minimum"),
            settings.integer("strength.maximum"));
    int lostStrength = victim.strength() - strength;
    if (pvp) {
      int gain =
          clamp(
              killer.strength() + settings.integer("strength.per-kill"),
              settings.integer("strength.minimum"),
              settings.integer("strength.maximum"));
      if (killer.strength() == settings.integer("strength.maximum")) {
        for (int index = 0; index < lostStrength; index++) {
          drops.add(ItemToken.Kind.STRENGTH);
        }
      }
      players.put(killerId, killer.progress(gain, killer.kills() + 1, killer.deaths()));
      pairs.put(pair, now + settings.integer("combat.repeat-pair-seconds") * 1000L);
    } else {
      addDeathDrops(drops, victim, lostStrength);
    }
    players.put(
        victimId,
        victim
            .progress(strength, victim.kills(), victim.deaths() + 1)
            .identity(victim.name(), protectionExpiry(now, "combat.respawn-protection-seconds")));
    commit(new State(players, pairs, bottles));
    return new DeathResult(true, false, List.copyOf(drops));
  }

  private static void addDeathDrops(List<ItemToken.Kind> drops, Profile victim, int lostStrength) {
    int craftedDrops = Math.min(victim.craftedStrength(), lostStrength);
    for (int index = 0; index < lostStrength; index++) {
      drops.add(index < craftedDrops ? ItemToken.Kind.CRAFTED_STRENGTH : ItemToken.Kind.STRENGTH);
    }
  }

  /** Applies a saved admin Strength edit within the configured limits. */
  public void set(UUID id, String field, int amount) throws IOException {
    Profile player = require(id);
    if (!field.equals("strength")) {
      throw new IllegalArgumentException("Field must be strength");
    }
    if (amount < settings.integer("strength.minimum")
        || amount > settings.integer("strength.maximum")) {
      throw new IllegalArgumentException("Strength outside configured limits");
    }
    Map<UUID, Profile> players = new HashMap<>(state.players());
    players.put(id, player.progress(amount, player.kills(), player.deaths()));
    commit(new State(players, state.pairs(), state.bottles()));
  }

  private Profile require(UUID id) {
    Profile profile = profile(id);
    if (profile == null) {
      throw new IllegalArgumentException("Player has never joined");
    }
    return profile;
  }

  /** Persists a test protection expiry through the normal storage transaction. */
  public void protection(UUID id, long until) throws IOException {
    Profile player = require(id);
    if (until > 0 && !settings.flag("combat.immunity-enabled")) {
      throw new IllegalArgumentException("Enable immunity first: /smp admin immunity on");
    }
    Map<UUID, Profile> players = new HashMap<>(state.players());
    players.put(id, player.identity(player.name(), until));
    commit(new State(players, state.pairs(), state.bottles()));
  }

  /** Clears one pair's persistent farming window for administrator playtesting. */
  public void clearPair(UUID first, UUID second) throws IOException {
    require(first);
    require(second);
    Map<String, Long> pairs = new HashMap<>(state.pairs());
    pairs.remove(pairKey(first, second));
    commit(new State(state.players(), pairs, state.bottles()));
  }

  private long protectionExpiry(long now, String path) {
    return settings.flag("combat.immunity-enabled") ? now + settings.integer(path) * 1000L : 0;
  }

  /** Exchanges excess Strength for transferable item kinds, retaining craft provenance. */
  public List<ItemToken.Kind> withdraw(UUID id, int amount) throws IOException {
    Profile player = require(id);
    if (amount < 1 || amount > player.strength() - 1) {
      throw new IllegalArgumentException(
          "Withdraw a positive amount while keeping at least Strength 1");
    }
    List<ItemToken.Kind> issued = new ArrayList<>();
    for (int index = 0; index < amount; index++) {
      issued.add(
          index < player.craftedStrength()
              ? ItemToken.Kind.CRAFTED_STRENGTH
              : ItemToken.Kind.STRENGTH);
    }
    Map<UUID, Profile> players = new HashMap<>(state.players());
    players.put(id, player.progress(player.strength() - amount, player.kills(), player.deaths()));
    commit(new State(players, state.pairs(), state.bottles()));
    return List.copyOf(issued);
  }

  /** Saves one item redemption before the inventory controller consumes its physical item. */
  public void redeem(UUID id, ItemToken.Kind kind) throws IOException {
    Profile player = require(id);
    if (kind == null || kind == ItemToken.Kind.REROLL) {
      throw new IllegalArgumentException("Choose a Strength item");
    }
    int cap =
        kind == ItemToken.Kind.CRAFTED_STRENGTH
            ? Math.min(
                settings.integer("strength.maximum"), settings.integer("strength.crafted-maximum"))
            : settings.integer("strength.maximum");
    if (player.strength() >= cap) {
      throw new IllegalArgumentException("This Strength item can only be used below level " + cap);
    }
    Map<UUID, Profile> players = new HashMap<>(state.players());
    players.put(
        id,
        new Profile(
            player.name(),
            player.strength() + 1,
            player.craftedStrength() + (kind == ItemToken.Kind.CRAFTED_STRENGTH ? 1 : 0),
            player.weapon(),
            player.kills(),
            player.deaths(),
            player.protectionUntil()));
    commit(new State(players, state.pairs(), state.bottles()));
  }

  /** Retires a legacy bundle before converting it; copied old bundles cannot migrate twice. */
  public void migrateItems(List<UUID> tokens, ItemToken.Kind kind) throws IOException {
    if (tokens.isEmpty()
        || new java.util.HashSet<>(tokens).size() != tokens.size()
        || tokens.stream()
            .anyMatch(
                token ->
                    state.bottles().get(token) == null
                        || state.bottles().get(token).kind() != kind)) {
      throw new IllegalArgumentException("Invalid or already converted legacy item");
    }
    Map<UUID, ItemToken> bottles = new HashMap<>(state.bottles());
    tokens.forEach(bottles::remove);
    commit(new State(state.players(), state.pairs(), bottles));
  }

  /** Persists a replacement specialty before consuming a physical reroll. */
  public void reroll(UUID id) throws IOException {
    Profile player = require(id);
    Map<UUID, Profile> players = new HashMap<>(state.players());
    players.put(id, player.specialty(WeaponKind.randomExcept(player.weapon())));
    commit(new State(players, state.pairs(), state.bottles()));
  }

  /** Saves an administrator's weapon assignment. */
  public void weapon(UUID id, WeaponKind next) throws IOException {
    Map<UUID, Profile> players = new HashMap<>(state.players());
    players.put(id, require(id).specialty(next));
    commit(new State(players, state.pairs(), state.bottles()));
  }

  private static int clamp(int value, int minimum, int maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  private static String pairKey(UUID first, UUID second) {
    return first.compareTo(second) < 0 ? first + "_" + second : second + "_" + first;
  }

  /** Outcome for player feedback. */
  public record DeathResult(boolean applied, boolean protectedKill, List<ItemToken.Kind> drops) {
    /** Copies item IDs so the committed outcome cannot be changed by callers. */
    public DeathResult {
      drops = List.copyOf(drops);
    }
  }
}
