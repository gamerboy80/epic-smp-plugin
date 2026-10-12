package com.epicsmp;

import java.util.UUID;

/** Registration retained for converting existing ID-backed items to tagged inventory items. */
public record ItemToken(UUID issuer, Kind kind) {
  /** Craft provenance survives transfers and withdrawals. */
  public enum Kind {
    STRENGTH,
    CRAFTED_STRENGTH,
    REROLL
  }
}
