package com.epicsmp;

import java.util.UUID;

/** Durable authenticity and provenance for a single-use custom item. */
public record ItemToken(UUID issuer, Kind kind) {
  /** Craft provenance survives transfers and withdrawals. */
  public enum Kind {
    STRENGTH,
    CRAFTED_STRENGTH,
    REROLL
  }
}
