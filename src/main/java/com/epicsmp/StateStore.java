package com.epicsmp;

import java.io.IOException;

/** Boundary for durable snapshots, also used by tests to simulate write failures. */
public interface StateStore {

  /** Reads the last saved state. */
  State load() throws IOException;

  /** Atomically replaces the last saved state. */
  void save(State state) throws IOException;
}
