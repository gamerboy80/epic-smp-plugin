package com.epicsmp;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Player information and permission-checked administration, registered through Paper. */
public final class SmpCommand implements BasicCommand {
  private final EpicSmpPlugin plugin;
  private final AdminTestCommands tests;

  /** Creates the /smp command. */
  SmpCommand(EpicSmpPlugin plugin) {
    this.plugin = plugin;
    tests = new AdminTestCommands(plugin);
  }

  @Override
  public String permission() {
    return "epicsmp.use";
  }

  @Override
  public void execute(CommandSourceStack source, String[] args) {
    CommandSender sender = source.getSender();
    try {
      String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
      switch (sub) {
        case "help" -> help(sender);
        case "stats" -> stats(sender, args);
        case "rules" -> rules(sender);
        case "withdraw" -> withdraw(sender, args);
        case "ability" -> {
          require(args, 1, "/smp ability");
          if (!(sender instanceof Player player)) {
            throw new IllegalArgumentException("Ability activation requires a player");
          }
          plugin.abilities().activate(player);
        }
        case "admin" -> admin(sender, args);
        default -> help(sender);
      }
    } catch (IllegalArgumentException e) {
      sender.sendMessage(EpicSmpPlugin.message(e.getMessage()));
    } catch (IOException e) {
      plugin.storageFailure(e);
      sender.sendMessage(
          EpicSmpPlugin.message("Save failed; PvP paused. Fix storage and restart."));
    }
  }

  private void withdraw(CommandSender sender, String[] args) throws IOException {
    if (!(sender instanceof Player player)) {
      throw new IllegalArgumentException("Withdraw requires an in-game player");
    }
    if (args.length > 2) {
      throw new IllegalArgumentException("/smp withdraw [amount]");
    }
    plugin.items().withdraw(player, args.length == 1 ? 1 : Integer.parseInt(args[1]));
  }

  private void help(CommandSender sender) {
    sender.sendMessage(
        EpicSmpPlugin.message("/smp stats [player] | rules | withdraw [amount] | ability"));
    if (sender.hasPermission("epicsmp.admin")) {
      sender.sendMessage(EpicSmpPlugin.message("/smp admin set <player> strength <value>"));
      sender.sendMessage(EpicSmpPlugin.message("/smp admin reload | pause | resume"));
      sender.sendMessage(
          EpicSmpPlugin.message("/smp admin immunity <on|off> | weapon <player> <specialty>"));
      sender.sendMessage(EpicSmpPlugin.message("/smp admin test help (admin playtest toolbox)"));
      sender.sendMessage(EpicSmpPlugin.message("/smp admin menu (inventory dashboard)"));
    }
  }

  private UUID target(String name) {
    try {
      UUID id = UUID.fromString(name);
      if (plugin.progression().profile(id) != null) {
        return id;
      }
    } catch (IllegalArgumentException ignored) {
      // A name is resolved only from our saved accounts; no blocking Mojang lookup.
    }
    List<UUID> matches =
        plugin.progression().snapshot().players().entrySet().stream()
            .filter(entry -> entry.getValue().name().equalsIgnoreCase(name))
            .map(Map.Entry::getKey)
            .toList();
    if (matches.size() != 1) {
      throw new IllegalArgumentException(
          "Unknown/ambiguous account; use a known player name or UUID");
    }
    return matches.getFirst();
  }

  private UUID self(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      throw new IllegalArgumentException("Console must specify a player");
    }
    return player.getUniqueId();
  }

  private void stats(CommandSender sender, String[] args) {
    UUID id = args.length >= 2 ? target(args[1]) : self(sender);
    Profile profile = plugin.progression().profile(id);
    sender.sendMessage(
        EpicSmpPlugin.message(
            String.format(
                Locale.ROOT,
                "%s | Strength %d | Weapon %s | K/D %d/%d",
                profile.name(),
                profile.strength(),
                profile.weapon(),
                profile.kills(),
                profile.deaths())));
  }

  private void rules(CommandSender sender) {
    Settings settings = plugin.settings();
    sender.sendMessage(
        EpicSmpPlugin.message(
            "Strength changes by "
                + settings.integer("strength.per-kill")
                + " on PvP kills/deaths; limits "
                + settings.integer("strength.minimum")
                + ".."
                + settings.integer("strength.maximum")
                + ". Strength 1 is normal melee damage; withdraw excess with /smp withdraw"));
    sender.sendMessage(
        EpicSmpPlugin.message(
            "PvP "
                + settings.flag("rules.pvp")
                + " | Paused "
                + plugin.paused()
                + " | Immunity "
                + settings.flag("combat.immunity-enabled")
                + " | Combat "
                + settings.integer("combat.tag-seconds")
                + "s | Repeat-pair protection "
                + settings.integer("combat.repeat-pair-seconds")
                + "s"));
    sender.sendMessage(
        EpicSmpPlugin.message("Disabled mace enchants: " + settings.disabledEnchants()));
    sender.sendMessage(
        EpicSmpPlugin.message(
            "Pearls "
                + settings.flag("rules.allow-ender-pearls")
                + " | Enchanted apples "
                + settings.flag("rules.allow-enchanted-golden-apples")
                + " | Netherite armor "
                + settings.flag("rules.allow-netherite-armor")
                + " | Harmful effects "
                + settings.flag("rules.allow-harmful-effects")
                + " | TNT minecarts "
                + settings.flag("rules.allow-tnt-minecarts")));
    sender.sendMessage(
        EpicSmpPlugin.message(
            "Block combat restocking "
                + settings.flag("combat.block-storage-restocking")
                + " | Block combat lava "
                + settings.flag("combat.block-lava")
                + " | Weapon abilities "
                + settings.flag("abilities.enabled")
                + " (passive "
                + settings.integer("abilities.passive-strength")
                + ", ultimate "
                + settings.integer("abilities.ultimate-strength")
                + ")"));
    sender.sendMessage(
        EpicSmpPlugin.message(
            "Crystals "
                + settings.flag("rules.allow-end-crystals")
                + " | Explosive anchors "
                + settings.flag("rules.allow-respawn-anchor-explosions")
                + " | Explosive beds "
                + settings.flag("rules.allow-explosive-beds")
                + " | Totems "
                + settings.flag("rules.allow-totems")));
    sender.sendMessage(
        EpicSmpPlugin.message(
            "Pearl "
                + settings.cooldown(org.bukkit.Material.ENDER_PEARL) / 20
                + "s | Wind charge "
                + settings.cooldown(org.bukkit.Material.WIND_CHARGE) / 20
                + "s | Golden apple "
                + settings.cooldown(org.bukkit.Material.GOLDEN_APPLE) / 20
                + "s"));
    sender.sendMessage(
        EpicSmpPlugin.message(
            "Lunge "
                + settings.integer("limits.spear-lunge-cooldown-seconds")
                + "s | Mace hit "
                + settings.integer("limits.mace-hit-cooldown-seconds")
                + "s | Explosion cap "
                + settings.decimal("limits.maximum-explosion-damage")
                + " HP"));
    sender.sendMessage(
        EpicSmpPlugin.message(
            "Vanilla Strength effect max level "
                + settings.potionLimit(org.bukkit.potion.PotionEffectType.STRENGTH)
                + " (0=disabled, -1=unlimited) | Nether "
                + settings.flag("limits.allow-nether")
                + " | End "
                + settings.flag("limits.allow-end")
                + " | Tipped arrows "
                + settings.flag("limits.allow-tipped-arrows")));
  }

  private void admin(CommandSender sender, String[] args) throws IOException {
    if (!sender.hasPermission("epicsmp.admin")) {
      throw new IllegalArgumentException("You need epicsmp.admin to manage the SMP");
    }
    if (args.length < 2) {
      help(sender);
      return;
    }
    switch (args[1].toLowerCase(Locale.ROOT)) {
      case "menu" -> {
        require(args, 2, "/smp admin menu");
        if (!(sender instanceof Player player)) {
          throw new IllegalArgumentException("The inventory dashboard requires an in-game player");
        }
        new AdminDashboard(plugin).open(player);
        return;
      }
      case "test" -> {
        tests.execute(sender, args);
        return;
      }
      case "immunity" -> {
        require(args, 3, "/smp admin immunity <on|off>");
        if (!List.of("on", "off").contains(args[2].toLowerCase(Locale.ROOT))) {
          throw new IllegalArgumentException("/smp admin immunity <on|off>");
        }
        plugin.updateRule("combat.immunity-enabled", args[2].equalsIgnoreCase("on"));
      }
      case "reload" -> plugin.reloadRules();
      case "pause" -> plugin.pause(true);
      case "resume" -> plugin.pause(false);
      case "set" -> {
        require(args, 5, "/smp admin set <player> strength <value>");
        plugin
            .progression()
            .set(target(args[2]), args[3].toLowerCase(Locale.ROOT), Integer.parseInt(args[4]));
        plugin.refreshAll();
      }
      case "weapon" -> {
        require(args, 4, "/smp admin weapon <player> <specialty>");
        UUID id = target(args[2]);
        plugin.progression().weapon(id, WeaponKind.valueOf(args[3].toUpperCase(Locale.ROOT)));
        Player online = org.bukkit.Bukkit.getPlayer(id);
        if (online != null) {
          plugin.abilities().clear(online);
        }
        plugin.refreshAll();
      }
      default -> throw new IllegalArgumentException("Unknown admin action; use /smp help");
    }
    sender.sendMessage(EpicSmpPlugin.message("SMP settings/progression updated."));
  }

  private void require(String[] args, int length, String usage) {
    if (args.length != length) {
      throw new IllegalArgumentException(usage);
    }
  }

  @Override
  public Collection<String> suggest(CommandSourceStack source, String[] args) {
    List<String> options = new ArrayList<>();
    boolean admin = source.getSender().hasPermission("epicsmp.admin");
    if (args.length <= 1) {
      options.addAll(List.of("help", "stats", "rules", "withdraw", "ability"));
      if (admin) {
        options.add("admin");
      }
    } else if (args[0].equalsIgnoreCase("admin") && admin) {
      if (args.length == 2) {
        options.addAll(List.of("set", "reload", "pause", "resume", "menu", "immunity", "weapon"));
        if (source.getSender().hasPermission("epicsmp.admin.test")) {
          options.add("test");
        }
      } else if (args[1].equalsIgnoreCase("test")
          && source.getSender().hasPermission("epicsmp.admin.test")) {
        options.addAll(tests.suggest(args));
      } else if (args.length == 3 && List.of("set", "weapon").contains(args[1])) {
        plugin.progression().snapshot().players().values().forEach(p -> options.add(p.name()));
      } else if (args.length == 4 && args[1].equals("set")) {
        options.addAll(List.of("strength"));
      } else if (args.length == 4 && args[1].equals("weapon")) {
        for (WeaponKind weapon : WeaponKind.values()) {
          options.add(weapon.name().toLowerCase(Locale.ROOT));
        }
      } else if (args.length == 3 && args[1].equals("immunity")) {
        options.addAll(List.of("on", "off"));
      }
    } else if (args[0].equals("stats") && args.length == 2) {
      plugin.progression().snapshot().players().values().forEach(p -> options.add(p.name()));
    }
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return options.stream()
        .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
        .toList();
  }
}
