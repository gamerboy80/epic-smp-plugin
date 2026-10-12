package com.epicsmp;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Native /vanish command with explicit permissions and side-effect-free status operations. */
final class VanishCommand implements BasicCommand {
  private final EpicSmpPlugin plugin;
  private final VanishService vanish;

  VanishCommand(EpicSmpPlugin plugin, VanishService vanish) {
    this.plugin = plugin;
    this.vanish = vanish;
  }

  @Override
  public boolean canUse(CommandSender sender) {
    return sender.hasPermission("epicsmp.admin")
        || sender.hasPermission("epicsmp.vanish")
        || sender.hasPermission("epicsmp.vanish.auto")
        || sender.hasPermission("epicsmp.vanish.others");
  }

  @Override
  public void execute(CommandSourceStack source, String[] args) {
    CommandSender sender = source.getSender();
    try {
      if (!canUse(sender)) {
        throw new IllegalArgumentException("You need epicsmp.vanish");
      }
      String action = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
      if (action.equals("help")) {
        sender.sendMessage(
            EpicSmpPlugin.message(
                "/vanish [on|off|toggle|status] [player] | list | pickup | inspect | chat |"
                    + " options | interactions [on|off|toggle|status]"));
        return;
      }
      if (action.equals("chat")) {
        if (!(sender instanceof Player player) || args.length > 2) {
          throw new IllegalArgumentException("/vanish chat <private|public|toggle|status>");
        }
        vanish.chatMode(player, args.length == 1 ? "toggle" : args[1].toLowerCase(Locale.ROOT));
        return;
      }
      if (action.equals("interactions")) {
        if (!sender.hasPermission("epicsmp.admin")) {
          throw new IllegalArgumentException(
              "You need epicsmp.admin to change vanish interactions");
        }
        if (args.length > 2) {
          throw new IllegalArgumentException("/vanish interactions <on|off|toggle|status>");
        }
        String mode = args.length == 1 ? "toggle" : args[1].toLowerCase(Locale.ROOT);
        boolean allowed =
            Settings.VANISH_INTERACTION_GUARDS.stream()
                    .noneMatch(option -> plugin.settings().flag("vanish." + option))
                && plugin.settings().flag("vanish.default-item-pickup")
                && !plugin.settings().flag("vanish.silent-container-inspection");
        if (mode.equals("status")) {
          sender.sendMessage(
              EpicSmpPlugin.message("Vanish interactions: " + (allowed ? "on" : "restricted")));
          return;
        }
        boolean next =
            switch (mode) {
              case "on" -> true;
              case "off" -> false;
              case "toggle" -> !allowed;
              default ->
                  throw new IllegalArgumentException("/vanish interactions <on|off|toggle|status>");
            };
        var changes = new HashMap<String, Boolean>();
        Settings.VANISH_INTERACTION_GUARDS.forEach(
            option -> changes.put("vanish." + option, !next));
        changes.put("vanish.default-item-pickup", next);
        changes.put("vanish.silent-container-inspection", !next);
        plugin.updateRules(changes);
        vanish.resetPickupOverrides();
        sender.sendMessage(
            EpicSmpPlugin.message("Saved vanish interactions: " + (next ? "on" : "off")));
        return;
      }
      if (action.equals("options") || action.equals("option")) {
        if (!sender.hasPermission("epicsmp.admin")) {
          throw new IllegalArgumentException("You need epicsmp.admin to change vanish options");
        }
        if (action.equals("options") && args.length == 1) {
          sender.sendMessage(
              Component.text("Vanish interactions: ", NamedTextColor.AQUA)
                  .append(
                      Component.text("[enable] ", NamedTextColor.GREEN)
                          .clickEvent(ClickEvent.runCommand("/vanish interactions on")))
                  .append(
                      Component.text("[disable]", NamedTextColor.RED)
                          .clickEvent(ClickEvent.runCommand("/vanish interactions off"))));
          Settings.VANISH_OPTIONS.keySet().stream()
              .sorted()
              .forEach(
                  option -> {
                    boolean enabled = plugin.settings().flag("vanish." + option);
                    sender.sendMessage(
                        Component.text(option + " ", NamedTextColor.AQUA)
                            .append(
                                Component.text(
                                        enabled ? "[ON]" : "[OFF]",
                                        enabled ? NamedTextColor.GREEN : NamedTextColor.GRAY)
                                    .clickEvent(
                                        ClickEvent.runCommand(
                                            "/vanish option " + option + " toggle"))
                                    .hoverEvent(Component.text("Click to toggle this setting"))));
                  });
          return;
        }
        if (args.length != 3
            || !Settings.VANISH_OPTIONS.containsKey(args[1])
            || !List.of("on", "off", "true", "false", "toggle")
                .contains(args[2].toLowerCase(Locale.ROOT))) {
          throw new IllegalArgumentException("/vanish option <name> <on|off>; see /vanish options");
        }
        plugin.updateRule(
            "vanish." + args[1],
            args[2].equalsIgnoreCase("toggle")
                ? !plugin.settings().flag("vanish." + args[1])
                : List.of("on", "true").contains(args[2].toLowerCase(Locale.ROOT)));
        sender.sendMessage(EpicSmpPlugin.message("Saved vanish option: " + args[1]));
        return;
      }
      if (action.equals("list")) {
        requireOthers(sender);
        sender.sendMessage(
            EpicSmpPlugin.message(
                "Vanished: "
                    + vanish.view().hidden().values().stream()
                        .map(VanishView.Identity::name)
                        .sorted()
                        .toList()));
        return;
      }
      if (action.equals("pickup") || action.equals("inspect")) {
        if (args.length != 1 || !(sender instanceof Player player)) {
          throw new IllegalArgumentException("/vanish " + action + " requires a player");
        }
        if (action.equals("pickup")) {
          vanish.togglePickup(player);
        } else {
          VanishListener.inspect(plugin, vanish, player);
        }
        return;
      }
      if (!List.of("on", "off", "toggle", "status").contains(action)) {
        if (args.length != 1) {
          throw new IllegalArgumentException("/vanish [on|off|toggle|status] [player]");
        }
        args = new String[] {"toggle", args[0]};
        action = "toggle";
      }
      if (args.length > 2) {
        throw new IllegalArgumentException("/vanish [on|off|toggle|status] [player]");
      }
      Player target;
      if (args.length == 2) {
        requireOthers(sender);
        target = Bukkit.getPlayerExact(args[1]);
      } else {
        target = sender instanceof Player player ? player : null;
        if (target != null && !VanishService.permitted(target)) {
          throw new IllegalArgumentException("You need epicsmp.vanish to toggle yourself");
        }
      }
      if (target == null) {
        throw new IllegalArgumentException("Specify an online player");
      }
      if (action.equals("status")) {
        sender.sendMessage(
            EpicSmpPlugin.message(
                target.getName()
                    + " is "
                    + (vanish.isVanished(target.getUniqueId()) ? "vanished." : "visible.")));
        return;
      }
      boolean hidden =
          action.equals("on")
              || action.equals("toggle") && !vanish.isVanished(target.getUniqueId());
      vanish.setVanished(target, hidden);
      if (!sender.equals(target)) {
        sender.sendMessage(EpicSmpPlugin.message("Vanish for " + target.getName() + ": " + hidden));
      }
    } catch (IllegalArgumentException e) {
      sender.sendMessage(EpicSmpPlugin.message(e.getMessage()));
    } catch (IOException e) {
      plugin.getLogger().severe("Vanish save failed: " + e.getMessage());
      sender.sendMessage(EpicSmpPlugin.message("Vanish save failed; contact an admin."));
    }
  }

  private static void requireOthers(CommandSender sender) {
    if (!sender.hasPermission("epicsmp.vanish.others")) {
      throw new IllegalArgumentException("You need epicsmp.vanish.others");
    }
  }

  @Override
  public Collection<String> suggest(CommandSourceStack source, String[] args) {
    CommandSender sender = source.getSender();
    if (!canUse(sender)) {
      return List.of();
    }
    List<String> options = new ArrayList<>();
    if (args.length <= 1) {
      options.addAll(List.of("on", "off", "toggle", "status", "help", "pickup", "inspect", "chat"));
      if (sender.hasPermission("epicsmp.admin")) {
        options.addAll(List.of("option", "options", "interactions"));
      }
      if (sender.hasPermission("epicsmp.vanish.others")) {
        options.add("list");
      }
    } else if (args.length == 2
        && args[0].equalsIgnoreCase("interactions")
        && sender.hasPermission("epicsmp.admin")) {
      options.addAll(List.of("on", "off", "toggle", "status"));
    } else if (args.length == 2 && args[0].equalsIgnoreCase("chat")) {
      options.addAll(List.of("private", "public", "toggle", "status"));
    } else if (args.length == 2
        && (args[0].equalsIgnoreCase("option") || args[0].equalsIgnoreCase("options"))
        && sender.hasPermission("epicsmp.admin")) {
      options.addAll(Settings.VANISH_OPTIONS.keySet());
    } else if (args.length == 3
        && (args[0].equalsIgnoreCase("option") || args[0].equalsIgnoreCase("options"))
        && sender.hasPermission("epicsmp.admin")) {
      if (Settings.VANISH_OPTIONS.containsKey(args[1])) {
        options.addAll(List.of("on", "off", "true", "false", "toggle"));
      }
    } else if (args.length == 2
        && sender.hasPermission("epicsmp.vanish.others")
        && List.of("on", "off", "toggle", "status").contains(args[0].toLowerCase(Locale.ROOT))) {
      Bukkit.getOnlinePlayers().stream()
          .filter(
              player ->
                  !(sender instanceof Player viewer)
                      || vanish.canSee(viewer.getUniqueId(), player.getUniqueId())
                          && viewer.canSee(player))
          .map(Player::getName)
          .forEach(options::add);
    }
    String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
    return options.stream()
        .filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix))
        .sorted()
        .toList();
  }
}
