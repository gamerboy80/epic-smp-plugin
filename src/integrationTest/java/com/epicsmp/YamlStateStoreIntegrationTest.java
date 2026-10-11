package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlStateStoreIntegrationTest {
  @TempDir Path directory;

  @Test
  void fullSnapshotRoundTripsAndBackupPreservesPreviousCommit() throws IOException {
    Path path = directory.resolve("players.yml");
    YamlStateStore store = new YamlStateStore(path);
    assertEquals(State.empty(), store.load());
    UUID id = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    State first =
        new State(
            Map.of(id, new Profile("First", 2, 5, 7, 123456)), Map.of(id + "_" + other, 600000L));
    store.save(first);
    assertEquals(first, new YamlStateStore(path).load());
    State next = new State(Map.of(id, new Profile("Renamed", 1, 5, 8, 123456)), Map.of());
    store.save(next);
    assertEquals(next, new YamlStateStore(path).load());
    assertEquals(first, new YamlStateStore(directory.resolve("players.yml.bak")).load());
    assertTrue(Files.notExists(directory.resolve("players.yml.tmp")));
  }

  @Test
  void invalidDataCannotSilentlyResetProgression() throws IOException {
    Path path = directory.resolve("players.yml");
    Files.writeString(path, "schema: 99\nplayers: {}\npairs: {}\n");
    assertThrows(IOException.class, () -> new YamlStateStore(path).load());
    Files.writeString(path, "schema: 1\nplayers: [broken\n");
    assertThrows(IOException.class, () -> new YamlStateStore(path).load());
  }

  @Test
  void missingTokenLedgerInCurrentSchemaCannotLoad() throws IOException {
    Path path = directory.resolve("players.yml");
    Files.writeString(path, "schema: 3\nplayers: {}\npairs: {}\n");
    assertThrows(IOException.class, () -> new YamlStateStore(path).load());
  }
}
