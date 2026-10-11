package com.epicsmp;

import java.util.Map;
import java.util.UUID;

/** Immutable snapshot, committed to storage before becoming the active progression state. */
public record State(
    Map<UUID, Profile> players, Map<String, Long> pairs, Map<UUID, ItemToken> bottles) {

  /** Copies all maps to prevent mutation after a commit. */
  public State {
    players = Map.copyOf(players);
    pairs = Map.copyOf(pairs);
    bottles = Map.copyOf(bottles);
  }

  /** Creates a snapshot without outstanding bottles, including legacy snapshots. */
  public State(Map<UUID, Profile> players, Map<String, Long> pairs) {
    this(players, pairs, Map.of());
  }

  /** Creates a new empty season. */
  public static State empty() {
    return new State(Map.of(), Map.of());
  }
}
