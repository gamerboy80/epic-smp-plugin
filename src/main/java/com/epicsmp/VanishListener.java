package com.epicsmp;

import com.destroystokyo.paper.event.server.GS4QueryEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.raid.RaidTriggerEvent;
import org.bukkit.event.world.GenericGameEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Observer isolation, lifecycle privacy and silent read-only inspection. */
final class VanishListener implements Listener {
  private final EpicSmpPlugin plugin;
  private final VanishService vanish;

  VanishListener(EpicSmpPlugin plugin, VanishService vanish) {
    this.plugin = plugin;
    this.vanish = vanish;
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void spawn(org.bukkit.event.entity.CreatureSpawnEvent event) {
    if (event.getEntity() instanceof org.bukkit.entity.Mob mob) {
      vanish.watchMob(mob);
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void chunk(org.bukkit.event.world.ChunkLoadEvent event) {
    for (Entity entity : event.getChunk().getEntities()) {
      if (entity instanceof org.bukkit.entity.Mob mob) {
        vanish.watchMob(mob);
      }
    }
  }

  private boolean hidden(Entity entity) {
    return entity instanceof Player && vanish.isVanished(entity.getUniqueId());
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void login(PlayerLoginEvent event) {
    if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
      return;
    }
    try {
      // Publish before Paper adds the player to other viewers' tab lists.
      vanish.prepareJoin(event.getPlayer());
    } catch (IOException | IllegalArgumentException e) {
      event.disallow(
          PlayerLoginEvent.Result.KICK_OTHER,
          EpicSmpPlugin.message("Vanish state unavailable. Contact an admin."));
      plugin.getLogger().severe("Vanish login failed: " + e.getMessage());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void join(PlayerJoinEvent event) {
    try {
      vanish.joined(event.getPlayer());
    } catch (IOException | IllegalArgumentException e) {
      event.joinMessage(null);
      event.getPlayer().kick(EpicSmpPlugin.message("Vanish state unavailable. Contact an admin."));
      return;
    }
    if (hidden(event.getPlayer())) {
      event.joinMessage(null);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void quit(PlayerQuitEvent event) {
    if (hidden(event.getPlayer())) {
      event.quitMessage(null);
      vanish.notifyStaff(event.getPlayer(), " left while vanished.");
    }
    vanish.left(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void world(PlayerChangedWorldEvent event) {
    vanish.sync();
    Bukkit.getScheduler().runTask(plugin, () -> reapply(event.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void respawn(PlayerRespawnEvent event) {
    Bukkit.getScheduler().runTask(plugin, () -> reapply(event.getPlayer()));
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void mode(PlayerGameModeChangeEvent event) {
    Bukkit.getScheduler().runTask(plugin, () -> reapply(event.getPlayer()));
  }

  private void reapply(Player player) {
    if (!player.isOnline()) {
      return;
    }
    try {
      vanish.prepare(player);
      vanish.sync();
    } catch (IOException | IllegalArgumentException e) {
      plugin.getLogger().severe("Vanish refresh failed: " + e.getMessage());
    }
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void damageEarly(EntityDamageEvent event) {
    isolateDamage(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void damage(EntityDamageEvent event) {
    isolateDamage(event);
  }

  private void isolateDamage(EntityDamageEvent event) {
    if (hidden(event.getEntity()) && plugin.settings().flag("vanish.prevent-incoming-damage")) {
      event.setCancelled(true);
    }
    if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent attack) {
      Player source = GameplayListener.attacker(attack.getDamager());
      if (source != null && hidden(source) && plugin.settings().flag("vanish.prevent-attacking")) {
        event.setCancelled(true);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void death(PlayerDeathEvent event) {
    if (hidden(event.getEntity())) {
      if (plugin.settings().flag("vanish.retain-death-inventory")) {
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
      }
      event.deathMessage(null);
      vanish.notifyStaff(event.getEntity(), " died while vanished.");
    } else {
      Entity cause = event.getDamageSource().getCausingEntity();
      Player attacker =
          cause == null ? event.getEntity().getKiller() : GameplayListener.attacker(cause);
      if (attacker != null && hidden(attacker) && event.deathMessage() != null) {
        event.deathMessage(Component.text(event.getEntity().getName() + " was slain."));
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void target(EntityTargetEvent event) {
    if (hidden(event.getTarget()) && plugin.settings().flag("vanish.prevent-mob-targeting")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void hunger(FoodLevelChangeEvent event) {
    if (hidden(event.getEntity())
        && plugin.settings().flag("vanish.prevent-hunger")
        && event.getFoodLevel() < event.getEntity().getFoodLevel()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void experience(com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-xp-pickup")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void entityInteractAt(org.bukkit.event.player.PlayerInteractAtEntityEvent event) {
    entityInteract(event);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void glide(org.bukkit.event.entity.EntityToggleGlideEvent event) {
    if (hidden(event.getEntity())
        && plugin.settings().flag("vanish.prevent-gliding")
        && event.isGliding()) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void pickupAttempt(org.bukkit.event.player.PlayerAttemptPickupItemEvent event) {
    if (hidden(event.getPlayer()) && !vanish.picksUp(event.getPlayer())) {
      event.setCancelled(true);
      event.setFlyAtPlayer(false);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void pickupFinal(EntityPickupItemEvent event) {
    pickup(event);
  }

  @EventHandler(priority = EventPriority.LOWEST)
  public void pickup(EntityPickupItemEvent event) {
    if (event.getEntity() instanceof Player player && hidden(player) && !vanish.picksUp(player)) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drop(org.bukkit.event.player.PlayerDropItemEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-item-drop")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void empty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-buckets")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void fill(org.bukkit.event.player.PlayerBucketFillEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-buckets")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void consume(org.bukkit.event.player.PlayerItemConsumeEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-item-consumption")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void mount(org.bukkit.event.entity.EntityMountEvent event) {
    if (hidden(event.getEntity()) && plugin.settings().flag("vanish.prevent-mounting")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void launch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
    if (event.getEntity().getShooter() instanceof Player player
        && hidden(player)
        && plugin.settings().flag("vanish.prevent-projectiles")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void shoot(org.bukkit.event.entity.EntityShootBowEvent event) {
    if (hidden(event.getEntity()) && plugin.settings().flag("vanish.prevent-projectiles")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void bed(org.bukkit.event.player.PlayerBedEnterEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-bed-use")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void armorStand(org.bukkit.event.player.PlayerArmorStandManipulateEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-entity-interactions")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void interact(PlayerInteractEvent event) {
    if (!hidden(event.getPlayer())) {
      return;
    }
    Block clicked = event.getClickedBlock();
    if (clicked != null
        && (event.getAction() == Action.LEFT_CLICK_BLOCK
            || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
      vanish.silentBlock(event.getPlayer(), clicked);
    }
    if (plugin.settings().flag("vanish.prevent-world-interactions")
        || event.getAction() == Action.PHYSICAL
            && plugin.settings().flag("vanish.prevent-physical-triggers")) {
      event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
      event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
    } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK
        && clicked != null
        && clicked.getState() instanceof Container
        && plugin.settings().flag("vanish.silent-container-inspection")) {
      event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
      event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
      if (event.getHand() == org.bukkit.inventory.EquipmentSlot.HAND
          && event.getPlayer().hasPermission("epicsmp.vanish.inspect")) {
        inspectBlock(plugin, event.getPlayer(), clicked);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void entityInteract(PlayerInteractEntityEvent event) {
    if ((hidden(event.getRightClicked()) || hidden(event.getPlayer()))
        && plugin.settings().flag("vanish.prevent-entity-interactions")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void blockBreak(BlockBreakEvent event) {
    if (hidden(event.getPlayer())) {
      if (plugin.settings().flag("vanish.prevent-block-breaking")) {
        event.setCancelled(true);
      }
      vanish.silentBlock(event.getPlayer(), event.getBlock());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void blockPlace(BlockPlaceEvent event) {
    if (hidden(event.getPlayer())) {
      if (plugin.settings().flag("vanish.prevent-block-placing")) {
        event.setCancelled(true);
      }
      vanish.silentBlock(event.getPlayer(), event.getBlock());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void raid(RaidTriggerEvent event) {
    if (hidden(event.getPlayer()) && plugin.settings().flag("vanish.prevent-raids")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void vibration(BlockReceiveGameEvent event) {
    if (hidden(event.getEntity()) && plugin.settings().flag("vanish.prevent-vibrations")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void gameEvent(GenericGameEvent event) {
    if (hidden(event.getEntity()) && plugin.settings().flag("vanish.prevent-vibrations")) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void advancement(PlayerAdvancementDoneEvent event) {
    if (hidden(event.getPlayer())) {
      event.message(null);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void chat(AsyncChatEvent event) {
    if (event.isCancelled() || !hidden(event.getPlayer())) {
      return;
    }
    boolean publicMode = vanish.publicChat(event.getPlayer().getUniqueId());
    VanishView view = vanish.view();
    if (!publicMode) {
      event
          .viewers()
          .removeIf(
              audience -> {
                if (audience instanceof Player viewer) {
                  return !view.hidden().containsKey(viewer.getUniqueId())
                      && !view.staff().contains(viewer.getUniqueId());
                }
                return !(audience instanceof org.bukkit.command.ConsoleCommandSender);
              });
    }
    event.setCancelled(true);
    for (var audience : event.viewers()) {
      Component rendered =
          publicMode
              ? event
                  .renderer()
                  .render(
                      event.getPlayer(), event.getPlayer().displayName(), event.message(), audience)
              : Component.text("[Vanish] ", NamedTextColor.DARK_AQUA)
                  .append(Component.text(event.getPlayer().getName() + ": ", NamedTextColor.AQUA))
                  .append(event.message());
      if (audience instanceof Player viewer) {
        var user =
            com.github.retrooper.packetevents.PacketEvents.getAPI()
                .getPlayerManager()
                .getUser(viewer);
        if (user != null) {
          user.sendPacketSilently(
              new com.github.retrooper.packetevents.wrapper.play.server
                  .WrapperPlayServerSystemChatMessage(false, rendered));
        } else {
          viewer.sendMessage(rendered);
        }
      } else {
        audience.sendMessage(rendered);
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void ping(PaperServerListPingEvent event) {
    VanishView view = vanish.view();
    event.setNumPlayers(
        Math.max(
            0,
            event.getNumPlayers()
                - (int)
                    view.online().stream()
                        .filter(player -> view.hidden().containsKey(player.id()))
                        .count()));
    event
        .getListedPlayers()
        .removeIf(
            player ->
                player.id() != null && view.hidden().containsKey(player.id())
                    || view.mentions(null, player.name()));
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void query(GS4QueryEvent event) {
    VanishView view = vanish.view();
    var response = event.getResponse();
    event.setResponse(
        response.toBuilder()
            .currentPlayers(
                Math.max(
                    0,
                    response.getCurrentPlayers()
                        - (int)
                            view.online().stream()
                                .filter(player -> view.hidden().containsKey(player.id()))
                                .count()))
            .players(
                response.getPlayers().stream().filter(name -> !view.mentions(null, name)).toList())
            .build());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void commands(PlayerCommandPreprocessEvent event) {
    String command = event.getMessage().substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    if (List.of("list", "minecraft:list", "bukkit:list").contains(command)) {
      event.setCancelled(true);
      List<String> visible =
          Bukkit.getOnlinePlayers().stream()
              .filter(
                  player ->
                      vanish.canSee(event.getPlayer().getUniqueId(), player.getUniqueId())
                          && event.getPlayer().canSee(player))
              .map(Player::getName)
              .sorted()
              .toList();
      event
          .getPlayer()
          .sendMessage(
              EpicSmpPlugin.message(
                  "Online (" + visible.size() + "): " + String.join(", ", visible)));
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void open(InventoryOpenEvent event) {
    if (event.getInventory().getHolder() instanceof Inspection holder
        && (!holder.owner.equals(event.getPlayer().getUniqueId())
            || !hidden(event.getPlayer())
            || !event.getPlayer().hasPermission("epicsmp.vanish.inspect"))) {
      event.setCancelled(true);
      return;
    }
    if (hidden(event.getPlayer())
        && plugin.settings().flag("vanish.prevent-inventory-access")
        && !(event.getInventory().getHolder() instanceof Inspection)
        && !AdminDashboard.owns(event.getInventory())) {
      event.setCancelled(true);
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void click(InventoryClickEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Inspection) {
      event.setCancelled(true);
      validateInspection(event.getWhoClicked());
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void drag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof Inspection) {
      event.setCancelled(true);
      validateInspection(event.getWhoClicked());
    }
  }

  private void validateInspection(org.bukkit.entity.HumanEntity entity) {
    if (entity instanceof Player player
        && (!hidden(player)
            || !player.hasPermission("epicsmp.vanish.inspect")
            || !(player.getOpenInventory().getTopInventory().getHolder()
                instanceof Inspection holder)
            || !holder.owner.equals(player.getUniqueId()))) {
      Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
    }
  }

  static void closeInvalidInspection(VanishService vanish, Player player) {
    Inventory top = player.getOpenInventory().getTopInventory();
    if (top != null
        && top.getHolder() instanceof Inspection holder
        && (!holder.owner.equals(player.getUniqueId())
            || !vanish.isVanished(player.getUniqueId())
            || !player.hasPermission("epicsmp.vanish.inspect"))) {
      player.closeInventory();
    }
  }

  static void inspect(EpicSmpPlugin plugin, VanishService vanish, Player player) {
    if (!vanish.isVanished(player.getUniqueId())
        || !player.hasPermission("epicsmp.vanish.inspect")) {
      throw new IllegalArgumentException("Requires vanish and epicsmp.vanish.inspect");
    }
    Block block = player.getTargetBlockExact(6);
    if (block == null || !(block.getState() instanceof Container)) {
      throw new IllegalArgumentException("Look at a container within six blocks");
    }
    inspectBlock(plugin, player, block);
  }

  private static void inspectBlock(EpicSmpPlugin plugin, Player player, Block block) {
    if (!(block.getState() instanceof Container container)) {
      return;
    }
    Inventory source =
        container instanceof Chest chest ? chest.getInventory() : container.getInventory();
    int size = Math.min(54, ((source.getSize() + 8) / 9) * 9);
    Inspection holder = new Inspection(player.getUniqueId(), size);
    ItemStack[] contents = java.util.Objects.requireNonNull(source.getContents());
    for (int i = 0; i < Math.min(contents.length, size); i++) {
      ItemStack item = contents[i];
      holder.inventory.setItem(i, item == null ? null : item.clone());
    }
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              if (player.isOnline()
                  && plugin.vanish().isVanished(player.getUniqueId())
                  && player.hasPermission("epicsmp.vanish.inspect")) {
                player.openInventory(holder.inventory);
              }
            });
  }

  private static final class Inspection implements InventoryHolder {
    private final java.util.UUID owner;
    private final Inventory inventory;

    Inspection(java.util.UUID owner, int size) {
      this.owner = owner;
      inventory =
          Bukkit.createInventory(
              this, size, Component.text("Silent inspection • snapshot", NamedTextColor.DARK_AQUA));
    }

    @Override
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
            "Bukkit requires the live inventory; owner checks and listeners forbid transfers")
    public Inventory getInventory() {
      return inventory;
    }
  }
}
