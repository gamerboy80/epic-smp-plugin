package com.epicsmp;

import java.util.Locale;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Public-safe scoreboard placeholders independent of a scoreboard plugin's subject context. */
final class VanishPlaceholders extends PlaceholderExpansion {
  private final EpicSmpPlugin plugin;
  private final VanishService vanish;

  VanishPlaceholders(EpicSmpPlugin plugin, VanishService vanish) {
    this.plugin = plugin;
    this.vanish = vanish;
  }

  @Override
  public String getIdentifier() {
    return "epicsmp";
  }

  @Override
  public String getAuthor() {
    return "EpicSmpPlugin";
  }

  @Override
  public String getVersion() {
    return plugin.getPluginMeta().getVersion();
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(getIdentifier(), getAuthor(), getVersion());
  }

  @Override
  public boolean persist() {
    return true;
  }

  @Override
  public String onRequest(OfflinePlayer player, String parameter) {
    VanishView view = vanish.view();
    var visible =
        view.online().stream().filter(identity -> !view.hidden().containsKey(identity.id()));
    return switch (parameter.toLowerCase(Locale.ROOT)) {
      case "visible_player_count" -> Long.toString(visible.count());
      case "visible_players" ->
          visible
              .map(VanishView.Identity::name)
              .sorted()
              .collect(java.util.stream.Collectors.joining(", "));
      default -> null;
    };
  }
}
