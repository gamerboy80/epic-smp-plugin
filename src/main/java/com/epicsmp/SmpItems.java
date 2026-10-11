package com.epicsmp;

import java.io.IOException;
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
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
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
        new ItemStack(kind == ItemToken.Kind.REROLL ? Material.BOOK : Material.GHAST_TEAR),
        kind,
        null);
  }

  /** Builds an authentic issued item from its durable provenance. */
  public ItemStack item(UUID token) {
    ItemToken issued = plugin.progression().snapshot().bottles().get(token);
    if (issued == null) {
      throw new IllegalArgumentException("Unknown custom-item token");
    }
    return decorate(preview(issued.kind()), issued.kind(), token);
  }

  private ItemStack decorate(ItemStack item, ItemToken.Kind kind, UUID token) {
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
    if (token != null) {
      meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, token.toString());
    }
    if (!item.setItemMeta(meta)) {
      throw new IllegalStateException("Could not apply custom item metadata");
    }
    return item;
  }

  private UUID serial(ItemStack item) {
    String value = item.getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING);
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private void scan(Player player) {
    ItemStack[] contents = player.getInventory().getContents();
    if (contents != null) {
      for (int slot = 0; slot < contents.length; slot++) {
        ItemStack item = contents[slot];
        if (custom(item)) {
          UUID token = serial(Objects.requireNonNull(item));
          ItemToken issued =
              token == null ? null : plugin.progression().snapshot().bottles().get(token);
          if (issued != null) {
            player.getInventory().setItem(slot, decorate(item, issued.kind(), token));
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
    ItemStack[] storage = player.getInventory().getStorageContents();
    int free = 0;
    if (storage != null) {
      for (ItemStack item : storage) {
        if (item == null || item.getType().isAir()) {
          free++;
        }
      }
    }
    if (free < amount) {
      throw new IllegalArgumentException("Free " + amount + " inventory slots first");
    }
    for (UUID token : plugin.progression().withdraw(player.getUniqueId(), amount)) {
      player.getInventory().setItem(player.getInventory().firstEmpty(), item(token));
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
    UUID token = serial(Objects.requireNonNull(clicked));
    if (held == null || token == null || !token.equals(serial(held))) {
      return;
    }
    try {
      available(player);
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
      player.getInventory().setItem(hand, held.subtract(1));
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
    if (event.isShiftClick()
        || !event.isLeftClick() && !event.isRightClick()
        || cursor != null && !cursor.getType().isAir()) {
      player.sendMessage(
          EpicSmpPlugin.message("Craft custom items one at a time with an empty cursor."));
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
      player.setItemOnCursor(item(token));
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
