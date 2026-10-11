package com.epicsmp;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.entity.minecart.ExplosiveMinecart;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Configurable item restrictions, successful-use cooldowns and combat escape controls. */
public final class RuleListener implements Listener {
  private final EpicSmpPlugin plugin;

  /** Creates the management rule listener. */
  RuleListener(EpicSmpPlugin plugin) {
    this.plugin = plugin;
    Bukkit.getScheduler()
        .runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::scan), 20L, 20L);
  }

  private boolean bypass(Player player) {
    return player.hasPermission("epicsmp.bypass");
  }

  /**
   * Caps usable enchants and removes forbidden mace enchants; stored book enchants are retained.
   */
  public boolean sanitize(ItemStack item) {
    return sanitize(plugin.settings(), item);
  }

  /** Applies the same equipment normalization at attack and inventory boundaries. */
  static boolean sanitize(Settings settings, ItemStack item) {
    if (item == null || item.getType().isAir()) {
      return false;
    }
    boolean changed = false;
    for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
      Integer cap = settings.enchantLimits().get(entry.getKey().getKey());
      if (cap != null && entry.getValue() > cap) {
        changed |= item.removeEnchantment(entry.getKey()) > 0;
        if (cap > 0) {
          item.addUnsafeEnchantment(entry.getKey(), cap);
        }
        changed = true;
      }
    }
    for (NamespacedKey key :
        item.getType() == Material.MACE
            ? settings.disabledEnchants()
            : java.util.Set.<NamespacedKey>of()) {
      Enchantment enchantment = Registry.ENCHANTMENT.get(key);
      if (enchantment != null && item.containsEnchantment(enchantment)) {
        changed |= item.removeEnchantment(enchantment) > 0;
      }
    }
    return changed;
  }

  private void scan(Player player) {
    if (bypass(player)) {
      return;
    }
    armor(player);
    ItemStack[] contents = player.getInventory().getContents();
    if (contents != null) {
      for (int slot = 0; slot < contents.length; slot++) {
        if (sanitize(contents[slot])) {
          player.getInventory().setItem(slot, contents[slot]);
        }
      }
    }
    ItemStack cursor = player.getItemOnCursor();
    if (sanitize(cursor)) {
      player.setItemOnCursor(cursor);
    }
  }

  private void scanLater(Player player) {
    Bukkit.getScheduler().runTask(plugin, () -> scan(player));
  }

  /** Removes forbidden enchants from existing items at login. */
  @EventHandler
  public void join(PlayerJoinEvent event) {
    scan(event.getPlayer());
  }

  /** Prevents enchanting tables adding configured mace enchantments. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void enchant(EnchantItemEvent event) {
    if (!bypass(event.getEnchanter())) {
      event
          .getEnchantsToAdd()
          .replaceAll(
              (enchant, level) ->
                  Math.min(
                      level,
                      plugin.settings().enchantLimits().getOrDefault(enchant.getKey(), level)));
      event
          .getEnchantsToAdd()
          .keySet()
          .removeIf(
              enchantment ->
                  event.getEnchantsToAdd().get(enchantment) <= 0
                      || event.getItem().getType() == Material.MACE
                          && plugin.settings().disabledEnchants().contains(enchantment.getKey()));
      if (event.getEnchantsToAdd().isEmpty()) {
        event.setCancelled(true);
      }
    }
  }

  /** Removes forbidden enchantments from anvil output before it can be taken. */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void anvil(PrepareAnvilEvent event) {
    if (event.getView().getPlayer() instanceof Player player && !bypass(player)) {
      ItemStack result = event.getResult();
      if (sanitize(result)) {
        event.setResult(result);
      }
    }
  }

  /** Sanitizes inventory transaction results and imported items. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player player && !bypass(player)) {
      ItemStack current = event.getCurrentItem();
      if (sanitize(current)) {
        event.setCurrentItem(current);
      }
      ItemStack cursor = event.getCursor();
      if (sanitize(cursor)) {
        player.setItemOnCursor(cursor);
      }
      scanLater(player);
    }
  }

  /** Scans dragged items after inventory changes. */
  @EventHandler(ignoreCancelled = true)
  public void drag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      scanLater(player);
    }
  }

  /** Sanitizes picked-up maces before their first attack. */
  @EventHandler(ignoreCancelled = true)
  public void pickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player && !bypass(player)) {
      ItemStack item = event.getItem().getItemStack();
      if (sanitize(item)) {
        event.getItem().setItemStack(item);
      }
    }
  }

  /** Scans a newly selected weapon. */
  @EventHandler(ignoreCancelled = true)
  public void held(PlayerItemHeldEvent event) {
    scan(event.getPlayer());
  }

  /** Sanitizes both hands during an offhand swap. */
  @EventHandler(ignoreCancelled = true)
  public void swap(PlayerSwapHandItemsEvent event) {
    if (!bypass(event.getPlayer())) {
      ItemStack main = event.getMainHandItem();
      ItemStack off = event.getOffHandItem();
      if (sanitize(main)) {
        event.setMainHandItem(main);
      }
      if (sanitize(off)) {
        event.setOffHandItem(off);
      }
    }
  }

  /** Stops a forbidden mace's first hit and disables crystal damage when configured. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void damage(EntityDamageByEntityEvent event) {
    if (event.getDamager() instanceof EnderCrystal
        && !plugin.settings().flag("rules.allow-end-crystals")) {
      event.setCancelled(true);
      return;
    }
    if (event.getDamager() instanceof Player attacker
        && !bypass(attacker)
        && (event.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK
            || event.getCause()
                == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
      EquipmentSlot hand = weaponHand(attacker, event);
      ItemStack weapon = attacker.getInventory().getItem(hand);
      if (sanitize(weapon)
          || weapon.getType() == Material.MACE && !plugin.settings().flag("rules.allow-mace")) {
        attacker.getInventory().setItem(hand, weapon);
        event.setCancelled(true);
        attacker.sendActionBar(
            EpicSmpPlugin.message("That weapon or its enchantments exceed server limits."));
      }
    }
  }

  /** Spear charge damage can come from the offhand while another weapon occupies the main hand. */
  static EquipmentSlot weaponHand(Player player, EntityDamageByEntityEvent event) {
    if (event.getDamageSource().getDamageType() == org.bukkit.damage.DamageType.SPEAR
        && !player.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR")
        && player.getInventory().getItemInOffHand().getType().name().endsWith("_SPEAR")) {
      return EquipmentSlot.OFF_HAND;
    }
    return EquipmentSlot.HAND;
  }

  /** Suppresses block destruction from preexisting crystals when crystal PvP is disabled. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void explosion(EntityExplodeEvent event) {
    if (event.getEntity() instanceof EnderCrystal
            && !plugin.settings().flag("rules.allow-end-crystals")
        || event.getEntity() instanceof ExplosiveMinecart
            && !plugin.settings().flag("rules.allow-tnt-minecarts")) {
      event.setCancelled(true);
    }
  }

  /** Prevents configured resurrection, including offhand totems. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void resurrect(EntityResurrectEvent event) {
    if (event.getEntity() instanceof Player player
        && !bypass(player)
        && !plugin.settings().flag("rules.allow-totems")) {
      event.setCancelled(true);
    }
  }

  /** Prevents entering elytra flight while combat tagged. */
  @EventHandler(ignoreCancelled = true)
  public void glide(EntityToggleGlideEvent event) {
    if (event.getEntity() instanceof Player player
        && event.isGliding()
        && !bypass(player)
        && plugin.settings().flag("combat.block-elytra")
        && plugin.combat().tag(player.getUniqueId(), System.currentTimeMillis()) != null) {
      event.setCancelled(true);
    }
  }

  /** Blocks escape commands, including namespaced forms and configured aliases. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void command(PlayerCommandPreprocessEvent event) {
    if (bypass(event.getPlayer())
        || plugin.combat().tag(event.getPlayer().getUniqueId(), System.currentTimeMillis())
            == null) {
      return;
    }
    String root = event.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    root = root.substring(root.lastIndexOf(':') + 1);
    String command = root;
    if (plugin.settings().strings("combat.blocked-commands").stream()
        .anyMatch(blocked -> blocked.equalsIgnoreCase(command))) {
      event.setCancelled(true);
      event
          .getPlayer()
          .sendMessage(EpicSmpPlugin.message("That command is blocked during combat."));
    }
  }

  /** Blocks explosive item placement/use without charging a cooldown for rejected actions. */
  @EventHandler(priority = EventPriority.HIGH)
  public void interact(PlayerInteractEvent event) {
    if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK
        || bypass(event.getPlayer())) {
      return;
    }
    ItemStack item = event.getItem();
    Block block = event.getClickedBlock();
    Settings settings = plugin.settings();
    boolean forbidden =
        item != null && forbiddenUse(item.getType())
            || item != null
                && item.getType() == Material.END_CRYSTAL
                && !settings.flag("rules.allow-end-crystals");
    if (item != null
        && item.getType() == Material.TRIDENT
        && item.containsEnchantment(Enchantment.RIPTIDE)
        && settings.flag("combat.block-riptide")
        && plugin.combat().tag(event.getPlayer().getUniqueId(), System.currentTimeMillis())
            != null) {
      forbidden = true;
    }
    if (block != null) {
      if (block.getType() == Material.RESPAWN_ANCHOR
          && !block.getWorld().isRespawnAnchorWorks()
          && !settings.flag("rules.allow-respawn-anchor-explosions")) {
        forbidden = true;
      }
      if (block.getType().name().endsWith("_BED")
          && !block.getWorld().isBedWorks()
          && !settings.flag("rules.allow-explosive-beds")) {
        forbidden = true;
      }
    }
    if (forbidden) {
      event.setUseInteractedBlock(Event.Result.DENY);
      event.setUseItemInHand(Event.Result.DENY);
      event
          .getPlayer()
          .sendActionBar(EpicSmpPlugin.message("That item is disabled by server rules."));
    }
  }

  /** Rejects item consumption during its server-side cooldown. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void consume(PlayerItemConsumeEvent event) {
    if (!bypass(event.getPlayer()) && forbiddenUse(event.getItem().getType())
        || blocked(event.getPlayer(), event.getItem().getType())) {
      event.setCancelled(true);
    }
  }

  /** Starts food/chorus cooldown only after an uncancelled consume. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void consumed(PlayerItemConsumeEvent event) {
    start(event.getPlayer(), event.getItem().getType());
  }

  /** Rejects pearl/wind-charge launches during their server-side cooldown. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void launch(PlayerLaunchProjectileEvent event) {
    if (!bypass(event.getPlayer()) && forbiddenUse(event.getItemStack().getType())
        || blocked(event.getPlayer(), event.getItemStack().getType())) {
      event.setCancelled(true);
    }
  }

  /** Starts thrown-item cooldown only after an uncancelled launch. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
  public void launched(PlayerLaunchProjectileEvent event) {
    start(event.getPlayer(), event.getItemStack().getType());
  }

  private void armor(Player player) {
    if (bypass(player) || plugin.settings().flag("rules.allow-netherite-armor")) {
      return;
    }
    ItemStack[] armor = java.util.Objects.requireNonNull(player.getInventory().getArmorContents());
    boolean changed = false;
    for (int slot = 0; slot < armor.length; slot++) {
      ItemStack item = armor[slot];
      if (item != null && item.getType().name().startsWith("NETHERITE_")) {
        armor[slot] = null;
        player
            .getInventory()
            .addItem(item)
            .values()
            .forEach(
                remaining -> player.getWorld().dropItemNaturally(player.getLocation(), remaining));
        changed = true;
      }
    }
    if (changed) {
      player.getInventory().setArmorContents(armor);
      player.sendMessage(
          EpicSmpPlugin.message("Netherite armor is disabled; it was moved to storage."));
    }
  }

  /**
   * Stops combat restocking through storage interfaces, while leaving the admin dashboard usable.
   */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void open(InventoryOpenEvent event) {
    InventoryHolder holder = event.getInventory().getHolder();
    if (!(event.getPlayer() instanceof Player player)
        || bypass(player)
        || !plugin.settings().flag("combat.block-storage-restocking")
        || plugin.combat().tag(player.getUniqueId(), System.currentTimeMillis()) == null
        || holder != null && holder.getClass().getEnclosingClass() == AdminDashboard.class) {
      return;
    }
    InventoryType type = event.getInventory().getType();
    if (type == InventoryType.CHEST
        || type == InventoryType.ENDER_CHEST
        || type == InventoryType.BARREL
        || type == InventoryType.SHULKER_BOX
        || type == InventoryType.HOPPER
        || type == InventoryType.DISPENSER
        || type == InventoryType.DROPPER) {
      event.setCancelled(true);
      player.sendMessage(EpicSmpPlugin.message("Storage restocking is blocked during combat."));
    }
  }

  /** Blocks pouring lava while combat tagged. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void lava(PlayerBucketEmptyEvent event) {
    if (!bypass(event.getPlayer())
        && plugin.settings().flag("combat.block-lava")
        && event.getBucket() == Material.LAVA_BUCKET
        && plugin.combat().tag(event.getPlayer().getUniqueId(), System.currentTimeMillis())
            != null) {
      event.setCancelled(true);
    }
  }

  /** Prevents damage from preexisting TNT minecarts as well as their block explosions. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
  public void minecartDamage(EntityDamageEvent event) {
    if (!plugin.settings().flag("rules.allow-tnt-minecarts")
        && event.getDamageSource().getDirectEntity() instanceof ExplosiveMinecart) {
      event.setCancelled(true);
    }
  }

  private boolean forbiddenUse(Material material) {
    String path =
        switch (material) {
          case ENDER_PEARL -> "rules.allow-ender-pearls";
          case ENCHANTED_GOLDEN_APPLE -> "rules.allow-enchanted-golden-apples";
          case TNT_MINECART -> "rules.allow-tnt-minecarts";
          case TOTEM_OF_UNDYING -> "rules.allow-totems";
          default -> null;
        };
    return path != null && !plugin.settings().flag(path);
  }

  private boolean blocked(Player player, Material material) {
    if (bypass(player)) {
      return false;
    }
    long remaining =
        plugin
            .combat()
            .remaining(player.getUniqueId(), material.name(), System.currentTimeMillis());
    if (remaining <= 0) {
      return false;
    }
    player.sendActionBar(
        EpicSmpPlugin.message(
            material.name().toLowerCase(Locale.ROOT)
                + " cooldown: "
                + (remaining + 999) / 1000
                + "s"));
    return true;
  }

  private void start(Player player, Material material) {
    if (bypass(player)) {
      return;
    }
    int ticks = plugin.settings().cooldown(material);
    if (ticks <= 0) {
      return;
    }
    plugin
        .combat()
        .cooldown(player.getUniqueId(), material.name(), System.currentTimeMillis(), ticks);
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (player.isOnline()) {
                long remaining =
                    plugin
                        .combat()
                        .remaining(
                            player.getUniqueId(), material.name(), System.currentTimeMillis());
                if (remaining > 0) {
                  player.setCooldown(
                      material,
                      Math.max((int) ((remaining + 49) / 50), player.getCooldown(material)));
                }
              }
            });
  }
}
