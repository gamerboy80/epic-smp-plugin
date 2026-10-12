package com.epicsmp;

import io.papermc.paper.event.entity.EntityLungeEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

/** Permission-checked playtest tools; progression, damage, XP and item grants are real. */
public final class AdminTestCommands {
  private static final List<String> ACTIONS =
      List.of(
          "help",
          "status",
          "tag",
          "untag",
          "protect",
          "cooldown",
          "clear",
          "pairreset",
          "hit",
          "simulatekill",
          "lunge",
          "give",
          "xp",
          "effect",
          "heal",
          "item",
          "ability");
  private final EpicSmpPlugin plugin;

  /** Creates the admin toolbox. */
  AdminTestCommands(EpicSmpPlugin plugin) {
    this.plugin = plugin;
  }

  /** Executes a test action with exact argument validation. */
  public void execute(CommandSender sender, String[] args) throws IOException {
    if (!sender.hasPermission("epicsmp.admin.test")) {
      throw new IllegalArgumentException("You need epicsmp.admin.test to use the test toolbox");
    }
    String action = args.length < 3 ? "help" : args[2].toLowerCase(Locale.ROOT);
    if (action.equals("help")) {
      help(sender);
      return;
    }
    if (!ACTIONS.contains(action)) {
      throw new IllegalArgumentException("Unknown test action; use /smp admin test help");
    }
    require(args.length >= 4, "Specify an online player; use /smp admin test help");
    Player player = online(args[3]);
    long now = System.currentTimeMillis();
    switch (action) {
      case "ability" -> {
        length(args, 4);
        plugin.abilities().activate(player);
      }
      case "item" -> {
        length(args, 5);
        ItemToken.Kind kind =
            switch (args[4].toLowerCase(Locale.ROOT)) {
              case "strength" -> ItemToken.Kind.STRENGTH;
              case "crafted" -> ItemToken.Kind.CRAFTED_STRENGTH;
              case "reroll" -> ItemToken.Kind.REROLL;
              default -> throw new IllegalArgumentException("Choose strength, crafted or reroll");
            };
        require(player.getInventory().firstEmpty() >= 0, "Free an inventory slot first");
        ItemToken.Kind token = kind;
        player
            .getInventory()
            .setItem(player.getInventory().firstEmpty(), plugin.items().item(token));
      }
      case "status" -> {
        length(args, 4);
        status(sender, player, now);
        return;
      }
      case "tag" -> {
        length(args, 6);
        Player other = different(player, args[4]);
        int seconds = number(args[5], 1, 3600);
        plugin.combat().hit(player.getUniqueId(), other.getUniqueId(), now, seconds);
        plugin.combatRestrictions(player);
        plugin.combatRestrictions(other);
        tell(other, "Admin test combat tag: " + seconds + "s. Logout penalties apply.");
      }
      case "untag" -> {
        length(args, 4);
        plugin.combat().clear(player.getUniqueId());
      }
      case "protect" -> {
        length(args, 5);
        int seconds = number(args[4], 0, 86400);
        plugin
            .progression()
            .protection(player.getUniqueId(), seconds == 0 ? 0 : now + seconds * 1000L);
      }
      case "cooldown" -> {
        length(args, 6);
        cooldown(player, args[4], number(args[5], 0, 3600), now);
      }
      case "clear" -> {
        length(args, 4);
        plugin.combat().clear(player.getUniqueId());
        plugin.combat().clearCooldowns(player.getUniqueId(), null);
        for (Material material : Material.values()) {
          if (!material.isLegacy()
              && material.isItem()
              && !material.isAir()
              && player.hasCooldown(material)) {
            player.setCooldown(material, 0);
          }
        }
      }
      case "pairreset" -> {
        length(args, 5);
        Player other = different(player, args[4]);
        plugin.progression().clearPair(player.getUniqueId(), other.getUniqueId());
      }
      case "hit" -> {
        length(args, 6);
        Player victim = different(player, args[4]);
        double damage = Double.parseDouble(args[5]);
        require(
            Double.isFinite(damage) && damage > 0 && damage <= 200,
            "Damage must be >0 through 200 HP");
        double before = victim.getHealth();
        victim.damage(damage, player);
        tell(
            sender,
            "Real hit: victim health "
                + before
                + " -> "
                + victim.getHealth()
                + ". Protection, armor and server damage rules apply.");
      }
      case "simulatekill" -> {
        length(args, 5);
        Player victim = different(player, args[4]);
        require(
            !plugin.paused()
                && plugin.settings().flag("rules.pvp")
                && plugin.active(player)
                && plugin.active(victim)
                && !plugin.safe(player.getLocation())
                && !plugin.safe(victim.getLocation()),
            "Progression test requires active PvP outside spawn in participating game"
                + " modes/worlds");
        Progression.DeathResult result =
            plugin.progression().death(victim.getUniqueId(), player.getUniqueId(), now);
        for (ItemToken.Kind token : result.drops()) {
          victim.getWorld().dropItemNaturally(victim.getLocation(), plugin.items().item(token));
        }
        plugin.abilities().clear(victim);
        plugin.combat().clear(victim.getUniqueId());
        plugin.refreshAll();
        tell(
            sender,
            "Real progression transaction; no physical death. Strength items may drop. Applied="
                + result.applied()
                + ", protection/anti-farm="
                + result.protectedKill());
        tell(victim, "An admin tested a death transaction against your REAL progression.");
      }
      case "lunge" -> {
        length(args, 5);
        EntityLungeEvent event = new EntityLungeEvent(player, number(args[4], 1, 10));
        Bukkit.getPluginManager().callEvent(event);
        tell(
            sender,
            "Lunge event probe: cancelled="
                + event.isCancelled()
                + ", power="
                + event.getLungePower()
                + ". No physical movement; an accepted probe starts the real cooldown.");
      }
      case "give" -> {
        length(args, 5);
        give(player, args[4].toLowerCase(Locale.ROOT));
      }
      case "xp" -> {
        length(args, 5);
        PlayerExpChangeEvent event = new PlayerExpChangeEvent(player, number(args[4], 1, 10000));
        Bukkit.getPluginManager().callEvent(event);
        player.giveExp(Math.max(0, event.getAmount()));
        tell(sender, "Awarded " + event.getAmount() + " real XP after other event listeners.");
      }
      case "effect" -> {
        length(args, 7);
        PotionEffectType type =
            Registry.POTION_EFFECT_TYPE.get(
                Objects.requireNonNullElse(
                    NamespacedKey.fromString(args[4].toLowerCase(Locale.ROOT)),
                    NamespacedKey.minecraft("invalid")));
        if (type == null || type.isInstant()) {
          throw new IllegalArgumentException(
              "Specify a non-instant effect, e.g. speed or strength");
        }
        PotionEffect effect =
            new PotionEffect(type, number(args[6], 1, 3600) * 20, number(args[5], 1, 256) - 1);
        EntityPotionEffectEvent event =
            new EntityPotionEffectEvent(
                player,
                player.getPotionEffect(type),
                effect,
                null,
                EntityPotionEffectEvent.Cause.POTION_DRINK,
                player.hasPotionEffect(type)
                    ? EntityPotionEffectEvent.Action.CHANGED
                    : EntityPotionEffectEvent.Action.ADDED,
                true);
        Bukkit.getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
          player.addPotionEffect(effect);
        }
        PotionEffect actual = player.getPotionEffect(type);
        tell(sender, "Real effect after caps: " + (actual == null ? "none" : actual));
      }
      case "heal" -> {
        length(args, 4);
        require(!player.isDead(), "Respawn before healing");
        player.setHealth(
            Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue());
        player.setFoodLevel(20);
        player.setSaturation(20);
        player.setFireTicks(0);
      }
      default -> throw new IllegalArgumentException("Unknown test action");
    }
    plugin.getLogger().info(sender.getName() + " ran admin test: " + String.join(" ", args));
    tell(
        sender,
        "Test action completed. Use /smp admin test status " + player.getName() + " to inspect.");
    tell(player, "Admin test action: " + action + ". Progression, grants and damage are real.");
  }

  private void cooldown(Player player, String input, int seconds, long now) {
    String key = input.toUpperCase(Locale.ROOT);
    Material material = Material.matchMaterial(key);
    require(
        key.equals(LimitListener.LUNGE)
            || key.equals(LimitListener.MACE)
            || material != null && material.isItem() && !material.isAir(),
        "Use a material, SPEAR_LUNGE or MACE_HIT");
    if (material != null) {
      key = material.name();
    }
    plugin.combat().clearCooldowns(player.getUniqueId(), key);
    if (seconds > 0) {
      plugin.combat().cooldown(player.getUniqueId(), key, now, seconds * 20);
    }
    if (material != null) {
      player.setCooldown(material, seconds * 20);
    }
  }

  private void give(Player player, String kind) {
    ItemStack item =
        switch (kind) {
          case "mace" -> new ItemStack(Material.MACE);
          case "spear" -> new ItemStack(Material.DIAMOND_SPEAR);
          case "apple" -> new ItemStack(Material.ENCHANTED_GOLDEN_APPLE);
          case "totem" -> new ItemStack(Material.TOTEM_OF_UNDYING);
          case "speed" -> new ItemStack(Material.POTION);
          default ->
              throw new IllegalArgumentException("Test items: mace, spear, apple, totem, speed");
        };
    require(player.getInventory().firstEmpty() >= 0, "Player needs an empty inventory slot");
    if (kind.equals("mace")) {
      item.addUnsafeEnchantment(Enchantment.DENSITY, 5);
      item.addUnsafeEnchantment(Enchantment.BREACH, 4);
      item.addUnsafeEnchantment(Enchantment.WIND_BURST, 3);
      item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
    } else if (kind.equals("spear")) {
      item.addUnsafeEnchantment(Enchantment.LUNGE, 3);
    } else if (kind.equals("speed")) {
      PotionMeta meta = (PotionMeta) item.getItemMeta();
      meta.setBasePotionType(PotionType.LONG_SWIFTNESS);
      if (!item.setItemMeta(meta)) {
        throw new IllegalArgumentException("Could not create the test potion");
      }
    }
    player.getInventory().addItem(item);
    tell(player, "Granted real test item " + kind + "; normal restrictions and scans apply.");
  }

  private void status(CommandSender sender, Player player, long now) {
    Profile profile = plugin.progression().profile(player.getUniqueId());
    CombatTracker.Tag tag = plugin.combat().tag(player.getUniqueId(), now);
    tell(
        sender,
        player.getName()
            + " weapon="
            + profile.weapon()
            + " strength="
            + profile.strength()
            + " health="
            + player.getHealth()
            + "/"
            + Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue()
            + " attack="
            + Objects.requireNonNull(player.getAttribute(Attribute.ATTACK_DAMAGE)).getValue());
    tell(
        sender,
        "Active="
            + plugin.active(player)
            + " spawn="
            + plugin.safe(player.getLocation())
            + " paused="
            + plugin.paused()
            + " bypass="
            + player.hasPermission("epicsmp.bypass")
            + " grace="
            + Math.max(0, profile.protectionUntil() - now) / 1000.0
            + "s"
            + " tag="
            + (tag == null ? "none" : tag.opponent() + " " + (tag.until() - now) / 1000.0 + "s"));
    List<String> keys = new ArrayList<>(List.of(LimitListener.LUNGE, LimitListener.MACE));
    for (Material material : Material.values()) {
      if (plugin.settings().cooldown(material) > 0) {
        keys.add(material.name());
      }
    }
    for (String key : keys) {
      tell(
          sender,
          key + "=" + plugin.combat().remaining(player.getUniqueId(), key, now) / 1000.0 + "s");
    }
  }

  private void help(CommandSender sender) {
    tell(
        sender,
        "Admin tests affect REAL players/data. hit can kill/drop items; simulatekill changes"
            + " Strength/stats.");
    for (String usage :
        List.of(
            "status <player>",
            "tag <attacker> <victim> <seconds>",
            "untag <player>",
            "protect <player> <seconds; 0 clears>",
            "cooldown <player> <material|SPEAR_LUNGE|MACE_HIT> <seconds; 0 clears>",
            "clear <player> (tags and ALL item cooldowns only)",
            "pairreset <player> <other>",
            "hit <attacker> <victim> <damageHP>",
            "simulatekill <attacker> <victim>",
            "lunge <player> <power> (event probe; no movement)",
            "give <player> <mace|spear|apple|totem|speed>",
            "xp <player> <baseAmount>",
            "effect <player> <effect> <level> <seconds>",
            "heal <player>",
            "item <player> <strength|crafted|reroll>",
            "ability <player>")) {
      tell(sender, "/smp admin test " + usage);
    }
    tell(
        sender,
        "Set Strength with /smp admin set. Protection and pair windows must be cleared"
            + " separately.");
  }

  /** Offers test actions and context-specific online player/value suggestions. */
  public List<String> suggest(String[] args) {
    if (args.length == 3) {
      return ACTIONS;
    }
    if (args.length == 4
        || args.length == 5
            && List.of("tag", "pairreset", "hit", "simulatekill").contains(args[2])) {
      return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
    if (args.length == 5) {
      return switch (args[2]) {
        case "item" -> List.of("strength", "crafted", "reroll");
        case "give" -> List.of("mace", "spear", "apple", "totem", "speed");
        case "cooldown" ->
            List.of(
                "SPEAR_LUNGE",
                "MACE_HIT",
                "ENDER_PEARL",
                "WIND_CHARGE",
                "GOLDEN_APPLE",
                "ENCHANTED_GOLDEN_APPLE");
        case "effect" -> List.of("speed", "strength", "resistance", "poison");
        default -> List.of("0", "1", "3", "10", "30");
      };
    }
    return List.of("1", "3", "10", "30");
  }

  private Player online(String name) {
    Player player = Bukkit.getPlayerExact(name);
    if (player == null) {
      throw new IllegalArgumentException("Player must be online (use their exact name)");
    }
    return player;
  }

  private Player different(Player player, String name) {
    Player other = online(name);
    require(!player.equals(other), "Specify two different players");
    return other;
  }

  private int number(String input, int min, int max) {
    int value = Integer.parseInt(input);
    require(value >= min && value <= max, "Value must be " + min + ".." + max);
    return value;
  }

  private void length(String[] args, int count) {
    require(args.length == count, "Wrong arguments; use /smp admin test help");
  }

  private void require(boolean valid, String error) {
    if (!valid) {
      throw new IllegalArgumentException(error);
    }
  }

  private void tell(CommandSender sender, String message) {
    sender.sendMessage(EpicSmpPlugin.message(message));
  }
}
