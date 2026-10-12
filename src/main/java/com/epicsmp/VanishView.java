package com.epicsmp;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Immutable packet-thread policy; never reads Bukkit players or permissions off-thread. */
record VanishView(Map<UUID, Identity> hidden, Set<UUID> staff, List<Identity> online) {
  private static final Pattern COLORS = Pattern.compile("(?i)§[0-9a-fk-orx]");
  static final VanishView EMPTY = new VanishView(Map.of(), Set.of(), List.of());

  VanishView {
    hidden = Map.copyOf(hidden);
    staff = Set.copyOf(staff);
    online = List.copyOf(online);
  }

  record Identity(UUID id, String name, int entityId) {}

  boolean hides(UUID viewer, UUID subject) {
    return hidden.containsKey(subject)
        && !subject.equals(viewer)
        && (viewer == null || !staff.contains(viewer));
  }

  boolean hidesEntity(UUID viewer, int entityId) {
    return hidden.values().stream()
        .anyMatch(subject -> subject.entityId() == entityId && hides(viewer, subject.id()));
  }

  boolean mentions(UUID viewer, Component component) {
    return component != null
        && (mentions(viewer, PlainTextComponentSerializer.plainText().serialize(component))
            || mentions(viewer, GsonComponentSerializer.gson().serialize(component)));
  }

  boolean mentions(UUID viewer, String text) {
    if (text == null || text.isEmpty()) {
      return false;
    }
    String plain = COLORS.matcher(text).replaceAll("").toLowerCase(Locale.ROOT);
    for (Identity identity : hidden.values()) {
      if (!hides(viewer, identity.id())) {
        continue;
      }
      if (plain.contains(identity.id().toString())) {
        return true;
      }
      String name = identity.name().toLowerCase(Locale.ROOT);
      int offset = plain.indexOf(name);
      while (offset >= 0) {
        int end = offset + name.length();
        if ((offset == 0 || !nameChar(plain.charAt(offset - 1)))
            && (end == plain.length() || !nameChar(plain.charAt(end)))) {
          return true;
        }
        offset = plain.indexOf(name, end);
      }
    }
    return false;
  }

  private static boolean nameChar(char value) {
    return value >= 'a' && value <= 'z' || value >= '0' && value <= '9' || value == '_';
  }
}
