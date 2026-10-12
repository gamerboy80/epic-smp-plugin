package com.epicsmp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Versioned YAML snapshots; invalid data aborts startup rather than resetting progression. */
public final class YamlStateStore implements StateStore {
  private final Path path;

  /** Opens the specified state file. */
  public YamlStateStore(Path path) {
    this.path = path;
  }

  @Override
  public State load() throws IOException {
    if (!Files.exists(path)) {
      return State.empty();
    }
    YamlConfiguration yaml = new YamlConfiguration();
    try {
      yaml.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
      int schema = yaml.getInt("schema", -1);
      if (schema < 1 || schema > 4) {
        throw new IOException("Unsupported state schema in " + path);
      }
      Map<UUID, Profile> players = new HashMap<>();
      ConfigurationSection section = yaml.getConfigurationSection("players");
      if (section == null) {
        throw new IOException("Missing players mapping in " + path);
      }
      for (String id : section.getKeys(false)) {
        String prefix = "players." + id + ".";
        String name = yaml.getString(prefix + "name");
        if (name == null
            || name.isBlank()
            || !(yaml.get(prefix + "protection-until") instanceof Number)) {
          throw new IOException("Invalid profile: " + id);
        }
        players.put(
            UUID.fromString(id),
            new Profile(
                name,
                requiredInt(yaml, prefix + "strength", -10, 10),
                schema >= 3 ? requiredInt(yaml, prefix + "crafted-strength", 0, 9) : 0,
                schema >= 3
                    ? WeaponKind.valueOf(yaml.getString(prefix + "weapon", ""))
                    : WeaponKind.randomExcept(null),
                requiredInt(yaml, prefix + "kills", 0, Integer.MAX_VALUE),
                requiredInt(yaml, prefix + "deaths", 0, Integer.MAX_VALUE),
                yaml.getLong(prefix + "protection-until")));
      }
      Map<String, Long> pairs = new HashMap<>();
      ConfigurationSection pairSection = yaml.getConfigurationSection("pairs");
      if (pairSection == null) {
        throw new IOException("Missing pairs mapping in " + path);
      }
      for (String key : pairSection.getKeys(false)) {
        String[] ids = key.split("_", -1);
        if (ids.length != 2 || !(pairSection.get(key) instanceof Number)) {
          throw new IOException("Invalid saved combat pair");
        }
        UUID.fromString(ids[0]);
        UUID.fromString(ids[1]);
        pairs.put(key, pairSection.getLong(key));
      }
      Map<UUID, ItemToken> bottles = new HashMap<>();
      if (schema >= 2) {
        ConfigurationSection tokens = yaml.getConfigurationSection("bottles");
        if (tokens == null) {
          throw new IOException("Missing bottles mapping in " + path);
        }
        for (String token : tokens.getKeys(false)) {
          String issuer = tokens.getString(schema == 2 ? token : token + ".issuer");
          if (issuer == null || !players.containsKey(UUID.fromString(issuer))) {
            throw new IOException("Invalid bottle issuer in " + path);
          }
          bottles.put(
              UUID.fromString(token),
              new ItemToken(
                  UUID.fromString(issuer),
                  schema == 2
                      ? ItemToken.Kind.STRENGTH
                      : ItemToken.Kind.valueOf(tokens.getString(token + ".kind", ""))));
        }
      }
      return new State(players, pairs, bottles);
    } catch (InvalidConfigurationException | IllegalArgumentException e) {
      throw new IOException("Invalid progression file: " + path, e);
    }
  }

  private int requiredInt(YamlConfiguration yaml, String key, int minimum, int maximum)
      throws IOException {
    int value = yaml.getInt(key);
    if (!yaml.isInt(key) || value < minimum || value > maximum) {
      throw new IOException("Invalid integer in progression: " + key);
    }
    return value;
  }

  @Override
  public void save(State state) throws IOException {
    YamlConfiguration yaml = new YamlConfiguration();
    yaml.set("schema", 4);
    yaml.createSection("players");
    yaml.createSection("pairs");
    yaml.createSection("bottles");
    state
        .players()
        .forEach(
            (id, player) -> {
              String prefix = "players." + id + ".";
              yaml.set(prefix + "name", player.name());
              yaml.set(prefix + "strength", player.strength());
              yaml.set(prefix + "crafted-strength", player.craftedStrength());
              yaml.set(prefix + "weapon", player.weapon().name());
              yaml.set(prefix + "kills", player.kills());
              yaml.set(prefix + "deaths", player.deaths());
              yaml.set(prefix + "protection-until", player.protectionUntil());
            });
    state.pairs().forEach((pair, expiry) -> yaml.set("pairs." + pair, expiry));
    state
        .bottles()
        .forEach(
            (token, item) -> {
              yaml.set("bottles." + token + ".issuer", item.issuer().toString());
              yaml.set("bottles." + token + ".kind", item.kind().name());
            });
    Path parent = path.toAbsolutePath().getParent();
    if (parent == null) {
      throw new IOException("State file must have a parent directory");
    }
    Files.createDirectories(parent);
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
    if (Files.exists(path)) {
      Files.copy(
          path,
          path.resolveSibling(path.getFileName() + ".bak"),
          StandardCopyOption.REPLACE_EXISTING);
    }
    try {
      Files.move(
          temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
