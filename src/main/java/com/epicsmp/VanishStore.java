package com.epicsmp;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** UUID-only vanish state, committed before visibility changes. */
final class VanishStore {
  private final Path file;

  VanishStore(Path file) {
    this.file = file.toAbsolutePath();
  }

  Set<UUID> load() throws IOException {
    if (!Files.exists(file)) {
      return Set.of();
    }
    YamlConfiguration yaml = new YamlConfiguration();
    try {
      yaml.load(file.toFile());
      java.util.List<?> values = yaml.getList("vanished");
      if (yaml.getInt("schema") != 1 || values == null) {
        throw new IllegalArgumentException("Invalid vanish state schema");
      }
      Set<UUID> result = new HashSet<>();
      for (Object value : values) {
        if (!(value instanceof String text) || !result.add(UUID.fromString(text))) {
          throw new IllegalArgumentException("Invalid/duplicate vanished UUID");
        }
      }
      return Set.copyOf(result);
    } catch (InvalidConfigurationException | IllegalArgumentException e) {
      throw new IOException("Cannot load vanish.yml", e);
    }
  }

  void save(Set<UUID> ids) throws IOException {
    Files.createDirectories(java.util.Objects.requireNonNull(file.getParent()));
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("schema", 1);
    yaml.set("vanished", ids.stream().map(UUID::toString).sorted().toList());
    Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
    yaml.save(temporary.toFile());
    try {
      Files.move(
          temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
