package com.epicsmp;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Inventory dashboard with persistent rule toggles and explicit real playtest actions. */
public final class AdminDashboard implements Listener {
  private static final List<Control> CONTROLS =
      List.of(
          new Control(10, Material.DIAMOND_SWORD, "PvP", "rules.pvp", true, false),
          new Control(
              12, Material.END_CRYSTAL, "End crystals", "rules.allow-end-crystals", true, false),
          new Control(13, Material.MACE, "Mace attacks", "rules.allow-mace", true, false),
          new Control(14, Material.TOTEM_OF_UNDYING, "Totems", "rules.allow-totems", true, false),
          new Control(
              15, Material.TRIDENT, "Block combat Riptide", "combat.block-riptide", true, false),
          new Control(
              16, Material.ELYTRA, "Block combat elytra", "combat.block-elytra", true, false),
          new Control(17, Material.SHIELD, "Immunity", "combat.immunity-enabled", true, false),
          new Control(
              18,
              Material.GLASS,
              "Anonymous invisible kills",
              "display.hide-invisible-killers",
              true,
              false),
          new Control(
              26,
              Material.FERMENTED_SPIDER_EYE,
              "Harmful effects",
              "rules.allow-harmful-effects",
              true,
              false),
          new Control(
              27, Material.BLAZE_POWDER, "Weapon abilities", "abilities.enabled", true, false),
          new Control(
              35, Material.ENDER_PEARL, "Ender pearls", "rules.allow-ender-pearls", true, false),
          new Control(
              36,
              Material.ENCHANTED_GOLDEN_APPLE,
              "Enchanted apples",
              "rules.allow-enchanted-golden-apples",
              true,
              false),
          new Control(
              44,
              Material.NETHERITE_CHESTPLATE,
              "Netherite armor",
              "rules.allow-netherite-armor",
              true,
              false),
          new Control(
              46,
              Material.CHEST,
              "Block combat restocking",
              "combat.block-storage-restocking",
              true,
              false),
          new Control(
              47, Material.LAVA_BUCKET, "Block combat lava", "combat.block-lava", true, false),
          new Control(
              48, Material.TNT_MINECART, "TNT minecarts", "rules.allow-tnt-minecarts", true, false),
          new Control(
              50,
              Material.SHIELD,
              "Shield ultimate immunity",
              "abilities.shield-invulnerability",
              true,
              false),
          new Control(51, Material.GHAST_TEAR, "Strength recipe", "recipes.strength", true, false),
          new Control(52, Material.BOOK, "Reroll recipe", "recipes.weapon-reroll", true, false),
          new Control(19, Material.NETHERRACK, "Nether access", "limits.allow-nether", true, false),
          new Control(20, Material.END_STONE, "End access", "limits.allow-end", true, false),
          new Control(
              21,
              Material.TIPPED_ARROW,
              "Tipped arrows",
              "limits.allow-tipped-arrows",
              true,
              false),
          new Control(
              22,
              Material.DIAMOND_SPEAR,
              "Lunge cooldown",
              "limits.spear-lunge-cooldown-seconds",
              3,
              0),
          new Control(
              23, Material.MACE, "Mace hit cooldown", "limits.mace-hit-cooldown-seconds", 5, 0),
          new Control(
              24, Material.POTION, "Vanilla Strength effect", "limits.potions.strength", 2, 0),
          new Control(
              25,
              Material.TNT,
              "Explosion damage cap",
              "limits.maximum-explosion-damage",
              24.0,
              0.0));
  private final EpicSmpPlugin plugin;
  private final AdminTestCommands tests;

  /** Creates the dashboard controller. */
  AdminDashboard(EpicSmpPlugin plugin) {
    this.plugin = plugin;
    tests = new AdminTestCommands(plugin);
  }

  /** Opens an admin-only dashboard with the viewer as the initial primary player. */
  public void open(Player player) {
    permission(player);
    main(player, player.getUniqueId(), null);
  }

  /** Identifies private dashboard inventories for the staff observer controller. */
  static boolean owns(Inventory inventory) {
    return inventory.getHolder() instanceof DashboardHolder;
  }

  private void permission(CommandSender player) {
    if (!player.hasPermission("epicsmp.admin")) {
      throw new IllegalArgumentException("You need epicsmp.admin to open the dashboard");
    }
  }

  private void main(Player viewer, UUID primary, UUID secondary) {
    DashboardHolder holder = new DashboardHolder(viewer.getUniqueId(), primary, secondary, null, 0);
    Inventory inventory = create(holder, "Epic SMP • Admin dashboard");
    button(
        inventory,
        0,
        Material.BOOK,
        "Strength SMP",
        "Strength 1 is baseline damage.",
        "Withdraw excess with /smp withdraw.",
        "Rule buttons save config immediately.");
    button(
        inventory,
        2,
        Material.PLAYER_HEAD,
        "Primary / attacker: " + name(primary),
        "Click to select an online player.",
        describe(primary),
        healthInfo(primary),
        timers(primary));
    button(
        inventory,
        6,
        Material.PLAYER_HEAD,
        "Secondary / victim: " + name(secondary),
        "Click to select an online player.",
        describe(secondary),
        healthInfo(secondary),
        timers(secondary));
    button(
        inventory,
        4,
        Material.CLOCK,
        "PvP paused: " + plugin.paused(),
        "Pause is temporary; rule toggles persist.",
        "Refresh to update these values.");
    for (Control control : CONTROLS) {
      Object current =
          control.on() instanceof Boolean
              ? plugin.settings().flag(control.path())
              : plugin.settings().decimal(control.path());
      button(
          inventory,
          control.slot(),
          control.icon(),
          control.label() + ": " + current,
          "Click to toggle and save.",
          "On: " + control.on() + " | Off: " + control.off(),
          "Custom values can be edited in config.yml.");
    }
    button(
        inventory,
        11,
        Material.BARRIER,
        "Pause / resume PvP",
        "Click to toggle temporary maintenance pause.",
        "Pausing clears all combat tags.");
    button(
        inventory,
        28,
        Material.SPYGLASS,
        "Inspect primary",
        "Live stats, tags and cooldowns in chat.");
    button(
        inventory,
        29,
        Material.GOLDEN_APPLE,
        "Heal primary",
        "REAL: fill health/hunger and clear fire.");
    button(
        inventory,
        30,
        Material.DIAMOND_SPEAR,
        "Grant test weapon",
        "REAL item: left-click Lunge III spear.",
        "Right-click mace with forbidden enchants.",
        "Normal rules sanitize the granted weapon.");
    button(
        inventory,
        31,
        Material.MILK_BUCKET,
        "Clear primary test timers",
        "Clear tag and ALL item cooldowns.",
        "Protection, pair windows and progression remain.");
    button(
        inventory,
        32,
        Material.SHIELD,
        "Primary protection",
        "Left-click: REAL 30-second protection.",
        "Right-click: remove protection.");
    button(
        inventory,
        33,
        Material.SHEARS,
        "Reset farming window",
        "REAL: clear primary/secondary pair window.");
    button(
        inventory,
        34,
        Material.WITHER_SKELETON_SKULL,
        "Test PvP death transaction",
        "REAL saved Strength and K/D change.",
        "Primary kills secondary; grace/anti-farm apply.",
        "No physical death; overflow Strength items may drop.");
    button(
        inventory,
        37,
        Material.IRON_SWORD,
        "Tag both players",
        "30-second REAL combat tags.",
        "Logout punishment and escape restrictions apply.");
    button(
        inventory,
        38,
        Material.REDSTONE,
        "Hit secondary",
        "REAL: primary deals 4 HP through damage rules.",
        "Can kill and drop inventory.");
    button(
        inventory,
        39,
        Material.EXPERIENCE_BOTTLE,
        "Award primary XP",
        "REAL: 10 XP through server event rules.");
    button(
        inventory,
        40,
        Material.POTION,
        "Test Speed effect",
        "REAL: Speed I, 10s through drink/effect-cap rules.");
    button(
        inventory,
        41,
        Material.FEATHER,
        "Probe primary Lunge",
        "Test power III cancellation and cooldown.",
        "Accepted event charges cooldown; no movement.");
    button(
        inventory,
        42,
        Material.TOTEM_OF_UNDYING,
        "Grant primary totem",
        "REAL totem grant; inventory use cap applies.");
    button(
        inventory,
        43,
        Material.PAPER,
        "Rule details",
        "Show active rules in chat.",
        "Use /smp admin test help for every test command.");
    button(
        inventory,
        45,
        Material.WRITABLE_BOOK,
        "Reload configuration",
        "Read and validate edited config.yml.",
        "Reload file edits before changing menu toggles.");
    button(
        inventory,
        49,
        Material.COMPASS,
        "Refresh dashboard",
        "Update status; selection is preserved.");
    button(inventory, 53, Material.OAK_DOOR, "Close", "Close this dashboard.");
    viewer.openInventory(inventory);
  }

  private void roster(Player viewer, DashboardHolder previous, boolean primary, int page) {
    List<? extends Player> players =
        Bukkit.getOnlinePlayers().stream()
            .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
            .toList();
    int boundedPage = Math.max(0, Math.min(page, Math.max(0, (players.size() - 1) / 45)));
    DashboardHolder holder =
        new DashboardHolder(
            viewer.getUniqueId(), previous.primary, previous.secondary, primary, boundedPage);
    Inventory inventory =
        create(
            holder,
            "Select " + (primary ? "primary" : "secondary") + " • Page " + (boundedPage + 1));
    for (int slot = 0; slot < 45 && boundedPage * 45 + slot < players.size(); slot++) {
      Player player = players.get(boundedPage * 45 + slot);
      holder.players.put(slot, player.getUniqueId());
      button(
          inventory,
          slot,
          Material.PLAYER_HEAD,
          player.getName(),
          describe(player.getUniqueId()),
          "Click to select.");
    }
    button(inventory, 45, Material.ARROW, "Previous page", "Browse online players.");
    button(inventory, 49, Material.COMPASS, "Back to dashboard", "Keep the current selection.");
    button(inventory, 53, Material.ARROW, "Next page", "Browse online players.");
    viewer.openInventory(inventory);
  }

  private Inventory create(DashboardHolder holder, String title) {
    Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(title));
    holder.inventory = inventory;
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      button(inventory, slot, Material.GRAY_STAINED_GLASS_PANE, " ");
    }
    return inventory;
  }

  private void button(
      Inventory inventory, int slot, Material material, String title, String... lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
    meta.displayName(Component.text(title, NamedTextColor.GOLD));
    meta.lore(
        java.util.Arrays.stream(lore)
            .map(line -> Component.text(line, NamedTextColor.GRAY))
            .toList());
    if (!item.setItemMeta(meta)) {
      throw new IllegalArgumentException("Could not create dashboard button " + title);
    }
    inventory.setItem(slot, item);
  }

  private String name(UUID id) {
    Profile profile = id == null ? null : plugin.progression().profile(id);
    return profile == null ? "none" : profile.name();
  }

  private String describe(UUID id) {
    Profile profile = id == null ? null : plugin.progression().profile(id);
    return profile == null
        ? "Choose an online test player."
        : "Strength " + profile.strength() + " | K/D " + profile.kills() + "/" + profile.deaths();
  }

  private String healthInfo(UUID id) {
    Player player = id == null ? null : Bukkit.getPlayer(id);
    return player == null
        ? "Select an online player."
        : String.format(Locale.ROOT, "%.1f current hearts", player.getHealth() / 2);
  }

  private String timers(UUID id) {
    Profile profile = id == null ? null : plugin.progression().profile(id);
    if (profile == null) {
      return "Refresh updates combat and protection timers.";
    }
    long now = System.currentTimeMillis();
    CombatTracker.Tag tag = plugin.combat().tag(id, now);
    return "Grace "
        + (Math.max(0, profile.protectionUntil() - now) + 999) / 1000
        + "s | Tag "
        + (tag == null ? 0 : (tag.until() - now + 999) / 1000)
        + "s | Lunge "
        + (plugin.combat().remaining(id, LimitListener.LUNGE, now) + 999) / 1000
        + "s | Mace "
        + (plugin.combat().remaining(id, LimitListener.MACE, now) + 999) / 1000
        + "s";
  }

  private String target(UUID id) {
    Player player = id == null ? null : Bukkit.getPlayer(id);
    if (player == null) {
      throw new IllegalArgumentException("Select an online player first");
    }
    return player.getName();
  }

  /** Cancels every inventory transfer and dispatches only this viewer's authorized buttons. */
  @EventHandler(ignoreCancelled = true)
  public void click(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof DashboardHolder holder)) {
      return;
    }
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player viewer)) {
      return;
    }
    if (!holder.owner.equals(viewer.getUniqueId()) || !viewer.hasPermission("epicsmp.admin")) {
      Bukkit.getScheduler().runTask(plugin, () -> viewer.closeInventory());
      return;
    }
    int slot = event.getRawSlot();
    if (slot < 0
        || slot >= 54
        || event.getClick() != org.bukkit.event.inventory.ClickType.LEFT
            && event.getClick() != org.bukkit.event.inventory.ClickType.RIGHT) {
      return;
    }
    // Bukkit requires opening/closing inventories after the transaction callback returns.
    boolean right = event.isRightClick();
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (!viewer.isOnline()
                  || viewer.getOpenInventory().getTopInventory().getHolder() != holder) {
                return;
              }
              try {
                permission(viewer);
                if (holder.selectPrimary != null) {
                  select(viewer, holder, slot);
                  return;
                }
                activate(viewer, holder, slot, right);
              } catch (IllegalArgumentException e) {
                viewer.sendMessage(EpicSmpPlugin.message(e.getMessage()));
              } catch (IOException e) {
                plugin.storageFailure(e);
                viewer.sendMessage(
                    EpicSmpPlugin.message("Save failed; PvP paused. Fix storage and restart."));
              }
            });
  }

  private void select(Player viewer, DashboardHolder holder, int slot) {
    UUID id = holder.players.get(slot);
    if (id != null) {
      target(id);
      main(
          viewer,
          holder.selectPrimary ? id : holder.primary,
          holder.selectPrimary ? holder.secondary : id);
    } else if (slot == 49) {
      main(viewer, holder.primary, holder.secondary);
    } else if (slot == 45 || slot == 53) {
      roster(viewer, holder, holder.selectPrimary, holder.page + (slot == 45 ? -1 : 1));
    }
  }

  private void activate(Player viewer, DashboardHolder holder, int slot, boolean right)
      throws IOException {
    for (Control control : CONTROLS) {
      if (control.slot() == slot) {
        boolean enabled =
            control.on() instanceof Boolean
                ? plugin.settings().flag(control.path())
                : plugin.settings().decimal(control.path())
                    != ((Number) control.off()).doubleValue();
        Object next = enabled ? control.off() : control.on();
        plugin.updateRule(control.path(), next);
        plugin
            .getLogger()
            .info(viewer.getName() + " changed dashboard rule " + control.path() + "=" + next);
        viewer.sendMessage(EpicSmpPlugin.message(control.label() + " saved: " + next));
        main(viewer, holder.primary, holder.secondary);
        return;
      }
    }
    switch (slot) {
      case 2, 6 -> {
        roster(viewer, holder, slot == 2, 0);
        return;
      }
      case 11 -> plugin.pause(!plugin.paused());
      case 28 -> test(viewer, "status", target(holder.primary));
      case 29 -> test(viewer, "heal", target(holder.primary));
      case 30 -> test(viewer, "give", target(holder.primary), right ? "mace" : "spear");
      case 31 -> test(viewer, "clear", target(holder.primary));
      case 32 -> test(viewer, "protect", target(holder.primary), right ? "0" : "30");
      case 33 -> test(viewer, "pairreset", target(holder.primary), target(holder.secondary));
      case 34 -> test(viewer, "simulatekill", target(holder.primary), target(holder.secondary));
      case 37 -> test(viewer, "tag", target(holder.primary), target(holder.secondary), "30");
      case 38 -> test(viewer, "hit", target(holder.primary), target(holder.secondary), "4");
      case 39 -> test(viewer, "xp", target(holder.primary), "10");
      case 40 -> test(viewer, "effect", target(holder.primary), "speed", "1", "10");
      case 41 -> test(viewer, "lunge", target(holder.primary), "3");
      case 42 -> test(viewer, "give", target(holder.primary), "totem");
      case 43 -> viewer.performCommand("smp rules");
      case 45 -> {
        plugin.reloadRules();
        viewer.sendMessage(EpicSmpPlugin.message("Configuration reloaded successfully."));
      }
      case 49 -> {
        /* Refresh below. */
      }
      case 53 -> {
        viewer.closeInventory();
        return;
      }
      default -> {
        return;
      }
    }
    main(viewer, holder.primary, holder.secondary);
  }

  private void test(Player viewer, String... arguments) throws IOException {
    String[] args = new String[arguments.length + 2];
    args[0] = "admin";
    args[1] = "test";
    System.arraycopy(arguments, 0, args, 2, arguments.length);
    tests.execute(viewer, args);
  }

  /** Cancels drag transactions over both the dashboard and the player's inventory. */
  @EventHandler(ignoreCancelled = true)
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof DashboardHolder) {
      event.setCancelled(true);
    }
  }

  private record Control(
      int slot, Material icon, String label, String path, Object on, Object off) {}

  private static final class DashboardHolder implements InventoryHolder {
    private final UUID owner;
    private final UUID primary;
    private final UUID secondary;
    private final Boolean selectPrimary;
    private final int page;
    private final Map<Integer, UUID> players = new HashMap<>();
    private Inventory inventory;

    private DashboardHolder(
        UUID owner, UUID primary, UUID secondary, Boolean selectPrimary, int page) {
      this.owner = owner;
      this.primary = primary;
      this.secondary = secondary;
      this.selectPrimary = selectPrimary;
      this.page = page;
    }

    @Override
    public Inventory getInventory() {
      return Objects.requireNonNull(inventory);
    }
  }
}
