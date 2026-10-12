package com.epicsmp;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Optional public-API hooks; releases only the visibility claims owned by this plugin. */
final class VanishIntegrations {
  private final EpicSmpPlugin plugin;
  private final Map<UUID, Boolean> essentialsBaseline = new HashMap<>();
  private final Set<String> warned = new HashSet<>();

  VanishIntegrations(EpicSmpPlugin plugin) {
    this.plugin = plugin;
  }

  void hidden(Player player, boolean hidden) {
    Plugin dynmap = Bukkit.getPluginManager().getPlugin("dynmap");
    if (dynmap != null && dynmap.isEnabled()) {
      try {
        dynmap
            .getClass()
            .getMethod("assertPlayerInvisibility", String.class, boolean.class, String.class)
            .invoke(dynmap, player.getName(), hidden, plugin.getName());
      } catch (ReflectiveOperationException e) {
        warning("dynmap", e);
      }
    }
    Plugin essentials = Bukkit.getPluginManager().getPlugin("Essentials");
    if (essentials != null && essentials.isEnabled()) {
      try {
        Object user =
            essentials.getClass().getMethod("getUser", Player.class).invoke(essentials, player);
        if (user == null) {
          return;
        }
        if (hidden) {
          boolean prior = (boolean) user.getClass().getMethod("isHidden").invoke(user);
          essentialsBaseline.putIfAbsent(player.getUniqueId(), prior);
          user.getClass().getMethod("setHidden", boolean.class).invoke(user, true);
        } else {
          Boolean prior = essentialsBaseline.remove(player.getUniqueId());
          if (prior != null) {
            user.getClass().getMethod("setHidden", boolean.class).invoke(user, prior);
          }
        }
      } catch (ReflectiveOperationException e) {
        warning("Essentials", e);
      }
    } else if (!hidden) {
      essentialsBaseline.remove(player.getUniqueId());
    }
  }

  private void warning(String name, ReflectiveOperationException error) {
    if (warned.add(name)) {
      Throwable cause =
          error instanceof InvocationTargetException invocation
              ? invocation.getTargetException()
              : error;
      plugin.getLogger().warning(name + " vanish integration unavailable: " + cause.getMessage());
    }
  }
}
