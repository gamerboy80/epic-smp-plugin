package com.epicsmp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Crafter;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/** Tagged Strength items and rerolls with native inventory behavior and protected presentation. */
public final class SmpItems implements Listener {
  private final EpicSmpPlugin plugin;
  private final NamespacedKey tokenKey;
  private final NamespacedKey kindKey;
  private final NamespacedKey formatKey;
  private final NamespacedKey strengthRecipe;
  private final NamespacedKey rerollRecipe;
  private final NamespacedKey appleRecipe;

  /** Creates the custom-item controller and its periodic presentation normalization. */
  SmpItems(EpicSmpPlugin plugin) {
    this.plugin = plugin;
    tokenKey = new NamespacedKey(plugin, "strength_bottle");
    kindKey = new NamespacedKey(plugin, "item_kind");
    formatKey = new NamespacedKey(plugin, "item_format");
    strengthRecipe = new NamespacedKey(plugin, "strength_item");
    rerollRecipe = new NamespacedKey(plugin, "weapon_reroll");
    appleRecipe = new NamespacedKey(plugin, "golden_apple");
    Bukkit.getScheduler()
        .runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::scan), 1L, 20L);
  }

  /** Registers tagged outputs for native Season Four crafting. */
  public void recipes() {
    clearRecipes();
    if (plugin.settings().flag("recipes.strength")) {
      ShapedRecipe recipe =
          new ShapedRecipe(strengthRecipe, preview(ItemToken.Kind.CRAFTED_STRENGTH));
      recipe.shape("CNC", "SAS", "CNC");
      recipe.setIngredient('C', Material.END_CRYSTAL);
      recipe.setIngredient('N', Material.NETHERITE_INGOT);
      recipe.setIngredient('S', Material.NETHER_STAR);
      recipe.setIngredient('A', Material.ENCHANTED_GOLDEN_APPLE);
      Bukkit.addRecipe(recipe);
    }
    if (plugin.settings().flag("recipes.weapon-reroll")) {
      ShapedRecipe recipe = new ShapedRecipe(rerollRecipe, preview(ItemToken.Kind.REROLL));
      recipe.shape("IGI", "GDG", "IGI");
      recipe.setIngredient('I', Material.IRON_BLOCK);
      recipe.setIngredient('G', Material.GOLD_BLOCK);
      recipe.setIngredient('D', Material.DIAMOND_BLOCK);
      Bukkit.addRecipe(recipe);
    }
    if (plugin.settings().flag("recipes.golden-apple")) {
      ShapedRecipe recipe = new ShapedRecipe(appleRecipe, new ItemStack(Material.GOLDEN_APPLE));
      recipe.shape(" G ", "GAG", " G ");
      recipe.setIngredient('G', Material.GOLD_INGOT);
      recipe.setIngredient('A', Material.APPLE);
      Bukkit.addRecipe(recipe);
    }
  }

  /** Removes only recipes owned by this plugin. */
  public void clearRecipes() {
    Bukkit.removeRecipe(strengthRecipe);
    Bukkit.removeRecipe(rerollRecipe);
    Bukkit.removeRecipe(appleRecipe);
  }

  /** Checks the identifying data, independently of display names and material. */
  public boolean custom(ItemStack item) {
    return item != null
        && !item.getType().isAir()
        && (item.getPersistentDataContainer().has(tokenKey)
            || item.getPersistentDataContainer().has(kindKey));
  }

  private ItemStack preview(ItemToken.Kind kind) {
    return decorate(
        new ItemStack(kind == ItemToken.Kind.REROLL ? Material.BOOK : Material.GHAST_TEAR), kind);
  }

  /** Creates an identical tagged item, ready for native stacking and movement. */
  public ItemStack item(ItemToken.Kind kind) {
    return preview(Objects.requireNonNull(kind));
  }

  private ItemStack decorate(ItemStack item, ItemToken.Kind kind) {
    return decorate(item, kind, List.of());
  }

  private ItemStack decorate(ItemStack item, ItemToken.Kind kind, List<UUID> tokens) {
    item.setType(kind == ItemToken.Kind.REROLL ? Material.BOOK : Material.GHAST_TEAR);
    ItemMeta meta = Bukkit.getItemFactory().getItemMeta(item.getType());
    if (meta == null) {
      throw new IllegalStateException("Custom item metadata unavailable");
    }
    String name =
        switch (kind) {
          case STRENGTH -> "Strength";
          case CRAFTED_STRENGTH -> "Crafted Strength";
          case REROLL -> "Weapon Reroll";
        };
    meta.displayName(
        Component.text(name, NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
    String help =
        kind == ItemToken.Kind.REROLL
            ? "Right-click to change weapon specialty."
            : kind == ItemToken.Kind.CRAFTED_STRENGTH
                ? "Gain 1 Strength, up to level 3."
                : "Right-click to gain 1 Strength.";
    meta.lore(
        List.of(
            Component.text(help, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false)));
    meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, kind.name());
    if (tokens.isEmpty()) {
      meta.getPersistentDataContainer().set(formatKey, PersistentDataType.INTEGER, 1);
      meta.getPersistentDataContainer().remove(tokenKey);
    } else {
      meta.getPersistentDataContainer()
          .set(
              tokenKey,
              PersistentDataType.STRING,
              String.join(",", tokens.stream().map(UUID::toString).toList()));
    }
    if (!item.setItemMeta(meta)) {
      throw new IllegalStateException("Could not apply custom item metadata");
    }
    return item;
  }

  private List<UUID> tokens(ItemStack item) {
    String value = item.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
    if (value == null || value.isBlank()) {
      return List.of();
    }
    List<UUID> result = new ArrayList<>();
    try {
      for (String token : value.split(",", -1)) {
        result.add(UUID.fromString(token));
      }
    } catch (IllegalArgumentException ignored) {
      return List.of();
    }
    return result;
  }

  private ItemToken.Kind kind(ItemStack item) {
    if (item == null || item.getType().isAir()) {
      return null;
    }
    String value = item.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
    try {
      return value == null ? null : ItemToken.Kind.valueOf(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private boolean legacy(ItemStack item) {
    return item != null && item.getPersistentDataContainer().has(tokenKey);
  }

  private boolean authentic(ItemStack item) {
    ItemToken.Kind kind = kind(item);
    return kind != null
        && !legacy(item)
        && Integer.valueOf(1)
            .equals(item.getPersistentDataContainer().get(formatKey, PersistentDataType.INTEGER))
        && item.getType() == (kind == ItemToken.Kind.REROLL ? Material.BOOK : Material.GHAST_TEAR);
  }

  private boolean authenticLegacy(ItemStack item) {
    ItemToken.Kind kind = kind(item);
    List<UUID> ids = tokens(item);
    return kind != null
        && ids.size() == item.getAmount()
        && new HashSet<>(ids).size() == ids.size()
        && ids.stream()
            .allMatch(
                id -> {
                  ItemToken token = plugin.progression().snapshot().bottles().get(id);
                  return token != null && token.kind() == kind;
                });
  }

  private ItemStack bundle(ItemStack template, ItemToken.Kind kind, List<UUID> tokens) {
    if (tokens.isEmpty()) {
      return null;
    }
    ItemStack result = template.clone();
    result.setAmount(tokens.size());
    return decorate(result, kind, tokens);
  }

  private ItemStack normalize(ItemStack source) throws IOException {
    if (!custom(source)) {
      return source;
    }
    if (legacy(source)) {
      if (!authenticLegacy(source)) {
        return source;
      }
      plugin.progression().migrateItems(tokens(source), kind(source));
    } else if (!authentic(source)) {
      return source;
    }
    ItemStack result = item(kind(source));
    result.setAmount(source.getAmount());
    return result.equals(source) ? source : result;
  }

  private void normalizeInventory(org.bukkit.inventory.Inventory inventory) throws IOException {
    ItemStack[] contents = Objects.requireNonNull(inventory.getContents());
    for (int slot = 0; slot < contents.length; slot++) {
      ItemStack normalized = normalize(contents[slot]);
      if (normalized != contents[slot]) {
        inventory.setItem(slot, normalized);
      }
    }
  }

  private boolean physical(org.bukkit.inventory.Inventory inventory, Player player) {
    return inventory != null
        && (inventory.equals(player.getInventory())
            || inventory.equals(player.getEnderChest())
            || inventory.getLocation() != null);
  }

  /** Converts existing bundles before native clicks; tagged items need no click handling. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
  public void stackClick(InventoryClickEvent event) {
    if (event instanceof CraftItemEvent
        || !legacy(event.getCurrentItem()) && !legacy(event.getCursor())) {
      return;
    }
    if (!(event.getWhoClicked() instanceof Player player)
        || !physical(event.getClickedInventory(), player) && event.getClickedInventory() != null) {
      return;
    }
    try {
      ItemStack current = event.getCurrentItem();
      ItemStack cursor = event.getCursor();
      ItemStack nextCurrent = normalize(current);
      if (nextCurrent != current) {
        event.setCurrentItem(nextCurrent);
      }
      ItemStack nextCursor = normalize(cursor);
      if (nextCursor != cursor) {
        event.setCursor(nextCursor);
      }
    } catch (IOException e) {
      event.setCancelled(true);
      plugin.storageFailure(e);
    }
  }

  /** A legacy drag is retried after conversion; ordinary tagged drags remain native. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
  public void stackDrag(InventoryDragEvent event) {
    if (legacy(event.getOldCursor())) {
      event.setCancelled(true);
      if (event.getWhoClicked() instanceof Player player) {
        scan(player);
      }
    }
  }

  /** Migrates real containers, leaving read-only inventory snapshots untouched. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
  public void open(org.bukkit.event.inventory.InventoryOpenEvent event) {
    if (event.getPlayer() instanceof Player player && physical(event.getInventory(), player)) {
      try {
        normalizeInventory(event.getInventory());
        scan(player);
      } catch (IOException e) {
        event.setCancelled(true);
        plugin.storageFailure(e);
      }
    }
  }

  /** Converts the ground stack without taking over acquisition or its cancellation. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
  public void pickup(EntityPickupItemEvent event) {
    migrateGround(event.getItem(), event);
  }

  /** Converts legacy ground bundles when a hopper attempts to collect them. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
  public void hopperPickup(org.bukkit.event.inventory.InventoryPickupItemEvent event) {
    migrateGround(event.getItem(), event);
  }

  private void migrateGround(org.bukkit.entity.Item ground, org.bukkit.event.Cancellable event) {
    if (!legacy(ground.getItemStack())) {
      return;
    }
    try {
      ground.setItemStack(normalize(ground.getItemStack()));
    } catch (IOException e) {
      event.setCancelled(true);
      plugin.storageFailure(e);
    }
  }

  /** Retries legacy hopper transfers after converting their complete source stacks. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
  public void hopperMove(InventoryMoveItemEvent event) {
    if (legacy(event.getItem())) {
      event.setCancelled(true);
      try {
        normalizeInventory(event.getSource());
      } catch (IOException e) {
        plugin.storageFailure(e);
      }
    }
  }

  /** Keeps legacy IDs safe until conversion; normal ground merges remain native. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
  public void merge(ItemMergeEvent event) {
    if (legacy(event.getEntity().getItemStack()) || legacy(event.getTarget().getItemStack())) {
      event.setCancelled(true);
      migrateGround(event.getEntity(), event);
      migrateGround(event.getTarget(), event);
    }
  }

  /** Keeps existing legacy bundles valid when dropped before their first conversion. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void drop(PlayerDropItemEvent event) {
    ItemStack dropped = event.getItemDrop().getItemStack();
    ItemToken.Kind kind = kind(dropped);
    if (!legacy(dropped)) {
      return;
    }
    List<UUID> allTokens = tokens(dropped);
    int dropAmount = dropped.getAmount();
    if (allTokens.size() < dropAmount || new HashSet<>(allTokens).size() != allTokens.size()) {
      event.setCancelled(true);
      return;
    }
    List<UUID> droppedTokens = new ArrayList<>(allTokens.subList(0, dropAmount));
    List<UUID> remaining = new ArrayList<>(allTokens.subList(dropAmount, allTokens.size()));
    ItemStack source = null;
    int sourceSlot = -1;
    if (!remaining.isEmpty()) {
      ItemStack[] inventory =
          Objects.requireNonNull(event.getPlayer().getInventory().getContents());
      for (int slot = 0; slot < inventory.length; slot++) {
        ItemStack candidate = inventory[slot];
        if (candidate != null
            && kind(candidate) == kind
            && candidate.getAmount() == remaining.size()
            && tokens(candidate).equals(allTokens)) {
          source = candidate;
          sourceSlot = slot;
          break;
        }
      }
      if (source == null) {
        event.setCancelled(true);
        return;
      }
    }
    event.getItemDrop().setItemStack(bundle(dropped, kind, droppedTokens));
    if (source != null) {
      event.getPlayer().getInventory().setItem(sourceSlot, bundle(source, kind, remaining));
    }
  }

  private boolean isEmpty(ItemStack item) {
    return item == null || item.getType().isAir();
  }

  private void scan(Player player) {
    try {
      normalizeInventory(player.getInventory());
      ItemStack cursor = player.getItemOnCursor();
      ItemStack normalized = normalize(cursor);
      if (normalized != cursor) {
        player.setItemOnCursor(normalized);
      }
    } catch (IOException e) {
      plugin.storageFailure(e);
    }
  }

  private List<ItemStack> withdrawalItems(Profile profile, int amount) {
    List<ItemStack> items = new ArrayList<>();
    for (int index = 0; index < amount; index++) {
      items.add(
          item(
              index < profile.craftedStrength()
                  ? ItemToken.Kind.CRAFTED_STRENGTH
                  : ItemToken.Kind.STRENGTH));
    }
    return items;
  }

  private ItemStack[] withdrawalStorage(Player player, List<ItemStack> items) {
    ItemStack[] storage = Objects.requireNonNull(player.getInventory().getStorageContents());
    for (int slot = 0; slot < storage.length; slot++) {
      if (storage[slot] != null) {
        storage[slot] = storage[slot].clone();
      }
    }
    for (ItemStack item : items) {
      boolean fits = false;
      for (ItemStack existing : storage) {
        if (!isEmpty(existing)
            && existing.isSimilar(item)
            && existing.getAmount() < existing.getMaxStackSize()) {
          existing.setAmount(existing.getAmount() + 1);
          fits = true;
          break;
        }
      }
      if (!fits) {
        for (int slot = 0; slot < storage.length; slot++) {
          if (isEmpty(storage[slot])) {
            storage[slot] = item.clone();
            fits = true;
            break;
          }
        }
      }
      if (!fits) {
        return null;
      }
    }
    return storage;
  }

  private void available(Player player) {
    if (plugin.paused()) {
      throw new IllegalArgumentException("Progression is paused.");
    }
    if (player.isDead()) {
      throw new IllegalArgumentException("Respawn before using Strength items.");
    }
  }

  /** Exchanges excess levels for physical items; crafted provenance survives withdrawal. */
  public void withdraw(Player player, int amount) throws IOException {
    available(player);
    Profile profile = plugin.progression().profile(player.getUniqueId());
    if (profile == null || amount < 1 || amount > profile.strength() - 1) {
      throw new IllegalArgumentException(
          "Withdraw a positive amount while keeping at least Strength 1");
    }
    scan(player);
    available(player);
    ItemStack[] storage = withdrawalStorage(player, withdrawalItems(profile, amount));
    if (storage == null) {
      throw new IllegalArgumentException(
          "Free inventory space for " + amount + " Strength items first");
    }
    plugin.progression().withdraw(player.getUniqueId(), amount);
    player.getInventory().setStorageContents(storage);
    plugin.refresh(player);
    player.sendMessage(EpicSmpPlugin.message("Withdrew " + amount + " Strength item(s)."));
  }

  /** Consumes an authentic item once from either hand. */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent event) {
    ItemStack clicked = event.getItem();
    if (!custom(clicked)
        || event.getAction() != Action.RIGHT_CLICK_AIR
            && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
      return;
    }
    boolean denied = event.useItemInHand() == Event.Result.DENY;
    event.setCancelled(true);
    EquipmentSlot hand = event.getHand();
    if (denied || hand == null) {
      return;
    }
    Player player = event.getPlayer();
    ItemStack held = player.getInventory().getItem(hand);
    try {
      available(player);
      ItemStack normalized = normalize(held);
      if (normalized != held) {
        player.getInventory().setItem(hand, normalized);
        held = normalized;
      }
      if (!authentic(held) || kind(held) != kind(clicked)) {
        return;
      }
      ItemToken.Kind kind = kind(held);
      if (kind == ItemToken.Kind.REROLL) {
        plugin.progression().reroll(player.getUniqueId());
        plugin.abilities().clear(player);
        player.sendMessage(
            EpicSmpPlugin.message(
                "Weapon specialty: "
                    + plugin.progression().profile(player.getUniqueId()).weapon()));
      } else {
        plugin.progression().redeem(player.getUniqueId(), kind);
      }
      ItemStack remaining = held.clone();
      remaining.setAmount(remaining.getAmount() - 1);
      player.getInventory().setItem(hand, remaining.getAmount() == 0 ? null : remaining);
      plugin.refresh(player);
    } catch (IllegalArgumentException e) {
      player.sendMessage(EpicSmpPlugin.message(e.getMessage()));
    } catch (IOException e) {
      plugin.storageFailure(e);
      player.sendMessage(EpicSmpPlugin.message("Save failed; your custom item was kept."));
    }
  }

  private ItemToken.Kind recipeKind(org.bukkit.inventory.Recipe recipe) {
    if (!(recipe instanceof org.bukkit.Keyed keyed)) {
      return null;
    }
    return keyed.getKey().equals(strengthRecipe)
        ? ItemToken.Kind.CRAFTED_STRENGTH
        : keyed.getKey().equals(rerollRecipe) ? ItemToken.Kind.REROLL : null;
  }

  private boolean containsCustom(ItemStack[] matrix) {
    if (matrix != null) {
      for (ItemStack ingredient : matrix) {
        if (custom(ingredient)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Custom items cannot be consumed as ingredients, including vanilla ghast-tear recipes. */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void prepare(PrepareItemCraftEvent event) {
    if (containsCustom(event.getInventory().getMatrix())) {
      event.getInventory().setResult(null);
    }
  }

  /** Native crafting supports bulk and number-key crafts while enforcing gameplay eligibility. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void craft(CraftItemEvent event) {
    if (containsCustom(event.getInventory().getMatrix())) {
      event.setCancelled(true);
      return;
    }
    if (recipeKind(event.getRecipe()) != null && event.getWhoClicked() instanceof Player player) {
      try {
        available(player);
      } catch (IllegalArgumentException e) {
        event.setCancelled(true);
        player.sendMessage(EpicSmpPlugin.message(e.getMessage()));
      }
    }
  }

  /** Automated crafting preserves the recipe's tag and rejects custom ingredients. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void crafter(CrafterCraftEvent event) {
    if (event.getBlock().getState() instanceof Crafter crafter
        && containsCustom(crafter.getInventory().getContents())) {
      event.setCancelled(true);
    }
  }

  /** Blocks renaming and combining a custom item, even for operators. */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void anvil(PrepareAnvilEvent event) {
    if (custom(event.getInventory().getItem(0)) || custom(event.getInventory().getItem(1))) {
      event.setResult(null);
    }
  }

  /** Blocks smithing changes to custom items. */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void smith(PrepareSmithingEvent event) {
    if (containsCustom(event.getInventory().getContents())) {
      event.setResult(null);
    }
  }

  /** Blocks grindstone changes and disenchanting custom items. */
  @EventHandler(priority = EventPriority.HIGHEST)
  public void grind(org.bukkit.event.inventory.PrepareGrindstoneEvent event) {
    if (custom(event.getInventory().getItem(0)) || custom(event.getInventory().getItem(1))) {
      event.setResult(null);
    }
  }

  /** Prevents custom ghast tears from becoming regeneration potions. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void brew(BrewEvent event) {
    if (containsCustom(event.getContents().getContents())) {
      event.setCancelled(true);
    }
  }

  /** Blocks enchanting changes to the custom items. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void enchant(EnchantItemEvent event) {
    if (custom(event.getItem())) {
      event.setCancelled(true);
    }
  }
}
