package com.epicsmp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
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

/** Authenticated Strength items and rerolls with immutable presentation and safe crafting. */
public final class SmpItems implements Listener {
  private final EpicSmpPlugin plugin;
  private final NamespacedKey tokenKey;
  private final NamespacedKey kindKey;
  private final NamespacedKey strengthRecipe;
  private final NamespacedKey rerollRecipe;
  private final NamespacedKey appleRecipe;

  /** Creates the custom-item controller and its periodic presentation normalization. */
  SmpItems(EpicSmpPlugin plugin) {
    this.plugin = plugin;
    tokenKey = new NamespacedKey(plugin, "strength_bottle");
    kindKey = new NamespacedKey(plugin, "item_kind");
    strengthRecipe = new NamespacedKey(plugin, "strength_item");
    rerollRecipe = new NamespacedKey(plugin, "weapon_reroll");
    appleRecipe = new NamespacedKey(plugin, "golden_apple");
    Bukkit.getScheduler()
        .runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::scan), 1L, 20L);
  }

  /**
   * Registers the Season Four recipes with preview outputs; each actual item gets its own token.
   */
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

  /** Builds an authentic issued item from its durable provenance. */
  public ItemStack item(UUID token) {
    ItemToken issued = plugin.progression().snapshot().bottles().get(token);
    if (issued == null) {
      throw new IllegalArgumentException("Unknown custom-item token");
    }
    return decorate(preview(issued.kind()), issued.kind(), List.of(token));
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

  private boolean stackable(ItemToken.Kind kind) {
    return kind == ItemToken.Kind.STRENGTH || kind == ItemToken.Kind.CRAFTED_STRENGTH;
  }

  private boolean authentic(ItemStack item) {
    if (item == null || item.getType().isAir()) {
      return false;
    }
    ItemToken.Kind kind = kind(item);
    List<UUID> tokens = tokens(item);
    if (kind == null
        || tokens.size() != item.getAmount()
        || kind == ItemToken.Kind.REROLL && item.getAmount() != 1
        || new HashSet<>(tokens).size() != tokens.size()) {
      return false;
    }
    for (UUID token : tokens) {
      ItemToken issued = plugin.progression().snapshot().bottles().get(token);
      if (issued == null || issued.kind() != kind) {
        return false;
      }
    }
    return true;
  }

  private ItemStack bundle(ItemStack template, ItemToken.Kind kind, List<UUID> tokens) {
    if (tokens.isEmpty()) {
      return null;
    }
    ItemStack result = template.clone();
    result.setAmount(tokens.size());
    return decorate(result, kind, tokens);
  }

  private ItemStack insert(Player player, ItemStack source) {
    ItemToken.Kind kind = kind(source);
    if (!stackable(kind) || !authentic(source)) {
      return source;
    }
    List<UUID> remaining = new ArrayList<>(tokens(source));
    ItemStack[] storage = Objects.requireNonNull(player.getInventory().getStorageContents());
    for (int slot = 0; slot < storage.length && !remaining.isEmpty(); slot++) {
      ItemStack current = storage[slot];
      if (current == null
          || current.getType().isAir()
          || kind(current) != kind
          || !authentic(current)) {
        continue;
      }
      List<UUID> currentTokens = tokens(current);
      Set<UUID> alreadyPresent = new HashSet<>(currentTokens);
      List<UUID> mergeable =
          remaining.stream().filter(token -> !alreadyPresent.contains(token)).toList();
      int amount =
          Math.min(current.getMaxStackSize() - current.getAmount(), mergeable.size());
      if (amount <= 0) {
        continue;
      }
      List<UUID> moved = new ArrayList<>(mergeable.subList(0, amount));
      List<UUID> combined = new ArrayList<>(currentTokens);
      combined.addAll(moved);
      ItemStack merged = bundle(current, kind, combined);
      player.getInventory().setItem(slot, merged);
      storage[slot] = merged;
      remaining.removeAll(moved);
    }
    for (int slot = 0; slot < storage.length && !remaining.isEmpty(); slot++) {
      ItemStack current = storage[slot];
      if (current != null && !current.getType().isAir()) {
        continue;
      }
      int amount = Math.min(source.getMaxStackSize(), remaining.size());
      List<UUID> placed = new ArrayList<>(remaining.subList(0, amount));
      ItemStack stored = bundle(source, kind, placed);
      player.getInventory().setItem(slot, stored);
      storage[slot] = stored;
      remaining.subList(0, amount).clear();
    }
    return remaining.isEmpty() ? null : bundle(source, kind, remaining);
  }

  private boolean hasStorageSpace(Player player, int amount) {
    int space = 0;
    ItemStack[] storage = Objects.requireNonNull(player.getInventory().getStorageContents());
    for (ItemStack item : storage) {
      if (item == null || item.getType().isAir()) {
        space += Material.GHAST_TEAR.getMaxStackSize();
      } else if (stackable(kind(item)) && authentic(item)) {
        space += Math.max(0, item.getMaxStackSize() - item.getAmount());
      }
      if (space >= amount) {
        return true;
      }
    }
    return false;
  }

  /** Merges independent issued Strength items when they are picked up. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void pickup(EntityPickupItemEvent event) {
    if (!(event.getEntity() instanceof Player player)) {
      return;
    }
    ItemStack source = event.getItem().getItemStack();
    ItemToken.Kind kind = kind(source);
    if (!stackable(kind)) {
      return;
    }
    event.setCancelled(true);
    if (!authentic(source)) {
      return;
    }
    ItemStack remainder = insert(player, source);
    if (remainder == null) {
      event.getItem().remove();
    } else {
      event.getItem().setItemStack(remainder);
      event.getItem().setPickupDelay(1);
    }
  }

  /** Keeps token IDs aligned with stacks split or combined through inventory clicks. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void stackClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    ItemStack cursor = event.getCursor();
    ItemStack current = event.getCurrentItem();
    boolean customCursor = stackable(kind(cursor));
    boolean customCurrent = stackable(kind(current));
    if (!customCursor && !customCurrent) {
      return;
    }
    if (event.getClick() == ClickType.MIDDLE || event.getClick() == ClickType.DOUBLE_CLICK) {
      event.setCancelled(true);
      return;
    }
    if (event.isShiftClick() && customCurrent) {
      event.setCancelled(true);
      if (authentic(current)) {
        if (event.getClickedInventory() == player.getInventory()) {
          if (event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
            event.setCurrentItem(
                insertIntoEmpty(event.getView().getTopInventory(), current.clone()));
          }
        } else {
          event.setCurrentItem(insert(player, current.clone()));
        }
      }
      return;
    }
    if (customCursor
        && authentic(cursor)
        && event.getClickedInventory() == null
        && (event.isLeftClick() || event.isRightClick())) {
      event.setCancelled(true);
      int dropped = event.isRightClick() ? 1 : cursor.getAmount();
      List<UUID> cursorTokens = tokens(cursor);
      List<UUID> droppedTokens = new ArrayList<>(cursorTokens.subList(0, dropped));
      player
          .getWorld()
          .dropItemNaturally(player.getLocation(), bundle(cursor, kind(cursor), droppedTokens));
      List<UUID> remaining = new ArrayList<>(cursorTokens.subList(dropped, cursorTokens.size()));
      event.setCursor(bundle(cursor, kind(cursor), remaining));
      return;
    }
    if (customCursor && authentic(cursor) && isEmpty(current)) {
      if (!event.isLeftClick() && !event.isRightClick()) {
        return;
      }
      event.setCancelled(true);
      int moved = event.isRightClick() ? 1 : cursor.getAmount();
      List<UUID> cursorTokens = tokens(cursor);
      List<UUID> placedTokens = new ArrayList<>(cursorTokens.subList(0, moved));
      event.setCurrentItem(bundle(cursor, kind(cursor), placedTokens));
      List<UUID> remaining = new ArrayList<>(cursorTokens.subList(moved, cursorTokens.size()));
      event.setCursor(bundle(cursor, kind(cursor), remaining));
      return;
    }
    if (customCursor
        && customCurrent
        && authentic(cursor)
        && authentic(current)
        && kind(cursor) == kind(current)
        && (event.isLeftClick() || event.isRightClick())) {
      event.setCancelled(true);
      int capacity = current.getMaxStackSize() - current.getAmount();
      int moved = Math.min(event.isRightClick() ? 1 : cursor.getAmount(), capacity);
      if (moved < 1) {
        return;
      }
      Set<UUID> combinedIds = new HashSet<>(tokens(current));
      if (tokens(cursor).stream().anyMatch(combinedIds::contains)) {
        return;
      }
      List<UUID> combined = new ArrayList<>(tokens(current));
      combined.addAll(tokens(cursor).subList(0, moved));
      event.setCurrentItem(bundle(current, kind(current), combined));
      List<UUID> remaining = new ArrayList<>(tokens(cursor).subList(moved, cursor.getAmount()));
      event.setCursor(bundle(cursor, kind(cursor), remaining));
      return;
    }
    if (!customCursor
        && customCurrent
        && authentic(current)
        && isEmpty(cursor)
        && event.isRightClick()) {
      event.setCancelled(true);
      List<UUID> currentTokens = tokens(current);
      int moved = (currentTokens.size() + 1) / 2;
      List<UUID> cursorTokens = new ArrayList<>(currentTokens.subList(0, moved));
      List<UUID> remaining = new ArrayList<>(currentTokens.subList(moved, currentTokens.size()));
      event.setCurrentItem(bundle(current, kind(current), remaining));
      event.setCursor(bundle(current, kind(current), cursorTokens));
    }
  }

  /** Prevents drag-splitting from copying the same token IDs into several stacks. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void stackDrag(InventoryDragEvent event) {
    if (stackable(kind(event.getOldCursor()))) {
      event.setCancelled(true);
    }
  }

  /** Keeps hoppers from splitting authenticated bundles or merging duplicate token stacks. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void hopperMove(InventoryMoveItemEvent event) {
    if (custom(event.getItem())) {
      event.setCancelled(true);
    }
  }

  /** Stops cloned token bundles from combining into stacks with mismatched token counts. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void merge(ItemMergeEvent event) {
    if (stackable(kind(event.getEntity().getItemStack()))
        || stackable(kind(event.getTarget().getItemStack()))) {
      event.setCancelled(true);
    }
  }

  /** Assigns separate token IDs to the dropped portion and remaining inventory stack. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void drop(PlayerDropItemEvent event) {
    ItemStack dropped = event.getItemDrop().getItemStack();
    ItemToken.Kind kind = kind(dropped);
    if (!stackable(kind)) {
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

  private ItemStack insertIntoEmpty(org.bukkit.inventory.Inventory inventory, ItemStack source) {
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      if (isEmpty(inventory.getItem(slot))) {
        inventory.setItem(slot, source);
        return null;
      }
    }
    return source;
  }

  private void scan(Player player) {
    ItemStack[] contents = player.getInventory().getContents();
    if (contents != null) {
      for (int slot = 0; slot < contents.length; slot++) {
        ItemStack item = contents[slot];
        if (custom(item)) {
          ItemToken.Kind kind = kind(Objects.requireNonNull(item));
          if (authentic(item)) {
            player.getInventory().setItem(slot, decorate(item, kind, tokens(item)));
          }
        }
      }
    }
  }

  private void available(Player player) {
    if (plugin.paused() || !plugin.active(player) || player.isDead()) {
      throw new IllegalArgumentException(
          "Custom items require active play while progression is running");
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
    if (!hasStorageSpace(player, amount)) {
      throw new IllegalArgumentException(
          "Free inventory space for " + amount + " Strength items first");
    }
    for (UUID token : plugin.progression().withdraw(player.getUniqueId(), amount)) {
      ItemStack remainder = insert(player, item(token));
      if (remainder != null) {
        throw new IllegalStateException("Could not fit withdrawn Strength items");
      }
    }
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
    if (held == null || !authentic(held) || !tokens(held).equals(tokens(clicked))) {
      return;
    }
    try {
      available(player);
      List<UUID> heldTokens = tokens(held);
      UUID token = heldTokens.getFirst();
      ItemToken issued = plugin.progression().snapshot().bottles().get(token);
      if (issued == null) {
        throw new IllegalArgumentException("Invalid or already used custom item");
      }
      if (issued.kind() == ItemToken.Kind.REROLL) {
        plugin.progression().reroll(player.getUniqueId(), token);
        plugin.abilities().clear(player);
        player.sendMessage(
            EpicSmpPlugin.message(
                "Weapon specialty: "
                    + plugin.progression().profile(player.getUniqueId()).weapon()));
      } else {
        plugin.progression().redeem(player.getUniqueId(), token);
      }
      List<UUID> remaining = new ArrayList<>(heldTokens);
      remaining.removeFirst();
      player.getInventory().setItem(
          hand, remaining.isEmpty() ? null : bundle(held, issued.kind(), remaining));
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

  /**
   * Issues one unique item per ordinary craft; bulk/number-key crafts cannot duplicate previews.
   */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void craft(CraftItemEvent event) {
    if (containsCustom(event.getInventory().getMatrix())) {
      event.setCancelled(true);
      return;
    }
    ItemToken.Kind kind = recipeKind(event.getRecipe());
    if (kind == null) {
      return;
    }
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    ItemStack cursor = event.getCursor();
    boolean emptyCursor = isEmpty(cursor);
    boolean matchingStrengthStack =
        kind == ItemToken.Kind.CRAFTED_STRENGTH
            && authentic(cursor)
            && kind(cursor) == kind
            && cursor.getAmount() < cursor.getMaxStackSize();
    if (event.isShiftClick()
        || !event.isLeftClick() && !event.isRightClick()
        || !emptyCursor && !matchingStrengthStack) {
      player.sendMessage(
          EpicSmpPlugin.message(
              "Craft custom items one at a time onto an empty cursor or matching Strength stack."));
      return;
    }
    try {
      available(player);
      ItemStack[] matrix = java.util.Objects.requireNonNull(event.getInventory().getMatrix());
      UUID token = plugin.progression().issue(player.getUniqueId(), kind);
      for (int slot = 0; slot < matrix.length; slot++) {
        ItemStack ingredient = matrix[slot];
        if (ingredient != null && !ingredient.getType().isAir()) {
          matrix[slot] = ingredient.subtract(1);
        }
      }
      event.getInventory().setMatrix(matrix);
      if (emptyCursor) {
        player.setItemOnCursor(item(token));
      } else {
        List<UUID> combined = new ArrayList<>(tokens(cursor));
        combined.add(token);
        player.setItemOnCursor(bundle(cursor, kind, combined));
      }
    } catch (IllegalArgumentException e) {
      player.sendMessage(EpicSmpPlugin.message(e.getMessage()));
    } catch (IOException e) {
      plugin.storageFailure(e);
    }
  }

  /** Automated crafters cannot issue ownerless tokens or consume custom items. */
  @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
  public void crafter(CrafterCraftEvent event) {
    if (recipeKind(event.getRecipe()) != null
        || event.getBlock().getState() instanceof Crafter crafter
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
