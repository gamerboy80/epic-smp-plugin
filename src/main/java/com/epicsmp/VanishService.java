package com.epicsmp;

import com.github.retrooper.packetevents.PacketEvents;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scheduler.BukkitTask;

/** Main-thread vanish lifecycle and public visibility API for other server plugins. */
public final class VanishService {
  private record Baseline(
      boolean flight,
      boolean flying,
      boolean collision,
      boolean silent,
      boolean sleeping,
      boolean spawning,
      GameMode mode) {}

  private record Pair(UUID viewer, UUID subject) {}

  private final EpicSmpPlugin plugin;
  private final VanishStore store;
  private final org.bukkit.NamespacedKey baselineKey;
  private final VanishPackets packets;
  private final VanishActions actions = new VanishActions();
  private final Map<UUID, Integer> visionEntities = new HashMap<>();
  private final Set<UUID> visionDirty = new HashSet<>();
  private final Map<UUID, java.lang.ref.WeakReference<Mob>> lookMobs = new HashMap<>();
  private final VanishIntegrations integrations;
  private Runnable unregisterPlaceholders = () -> {};
  private volatile boolean registered;
  private final Map<UUID, Baseline> baselines = new HashMap<>();
  private final Set<Pair> hiddenPairs = new HashSet<>();
  private final Set<UUID> sessions = new HashSet<>();
  private final Map<UUID, Boolean> pickup = new HashMap<>();
  private final Map<UUID, Boolean> publicChat = new java.util.concurrent.ConcurrentHashMap<>();
  private Set<UUID> saved;
  private volatile VanishView view = VanishView.EMPTY;
  private BukkitTask task;

  VanishService(EpicSmpPlugin plugin) throws IOException {
    this.plugin = plugin;
    baselineKey = new org.bukkit.NamespacedKey(plugin, "vanish_baseline");
    integrations = new VanishIntegrations(plugin);
    store = new VanishStore(plugin.getDataFolder().toPath().resolve("vanish.yml"));
    saved = store.load();
    packets =
        new VanishPackets(
            () -> view, () -> plugin.settings().flag("vanish.client-night-vision"), actions);
  }

  void start() {
    if (PacketEvents.getAPI() == null
        || !PacketEvents.getAPI().isInitialized()
        || PacketEvents.getAPI()
            .getVersion()
            .isOlderThan(new com.github.retrooper.packetevents.util.PEVersion(2, 14, 0))) {
      throw new IllegalStateException(
          "PacketEvents 2.14.0+ must be initialized before EpicSmpPlugin");
    }
    PacketEvents.getAPI().getEventManager().registerListener(packets);
    registered = true;
    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      VanishPlaceholders expansion = new VanishPlaceholders(plugin, this);
      if (!expansion.register()) {
        throw new IllegalStateException("Cannot register EpicSmpPlugin vanish placeholders");
      }
      unregisterPlaceholders = () -> expansion.unregister();
    }
    Bukkit.getPluginManager().registerEvents(new VanishListener(plugin, this), plugin);
    plugin.registerCommand(
        "vanish",
        "Staff visibility and silent inspection",
        List.of("v"),
        new VanishCommand(plugin, this));
    for (Player player : Bukkit.getOnlinePlayers()) {
      try {
        prepare(player);
        sessions.add(player.getUniqueId());
      } catch (IOException | IllegalArgumentException e) {
        player.kick(EpicSmpPlugin.message("Vanish state unavailable. Contact an admin."));
        plugin.getLogger().severe("Cannot initialize vanish: " + e.getMessage());
      }
    }
    for (org.bukkit.World world : Bukkit.getWorlds()) {
      world.getLivingEntities().stream()
          .filter(Mob.class::isInstance)
          .map(Mob.class::cast)
          .forEach(this::watchMob);
    }
    task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
  }

  /** Whether this UUID is currently vanished, safe for async visibility consumers. */
  public boolean isVanished(UUID id) {
    return view.hidden().containsKey(id);
  }

  /** Whether a viewer may see a subject; callers should also honor Player.canSee. */
  public boolean canSee(UUID viewer, UUID subject) {
    return !view.hides(viewer, subject);
  }

  VanishView view() {
    return view;
  }

  static boolean permitted(Player player) {
    return player.hasPermission("epicsmp.vanish") || player.hasPermission("epicsmp.vanish.auto");
  }

  void prepare(Player player) throws IOException {
    prepare(player, false);
  }

  private void prepare(Player player, boolean joining) throws IOException {
    boolean hidden =
        permitted(player)
            && (saved.contains(player.getUniqueId())
                || joining && player.hasPermission("epicsmp.vanish.auto"));
    save(player.getUniqueId(), hidden);
    publish(player, hidden);
    if (hidden) {
      publicChat.putIfAbsent(
          player.getUniqueId(), plugin.settings().flag("vanish.default-chat-public"));
      visionDirty.add(player.getUniqueId());
      apply(player);
    } else if (baselines.containsKey(player.getUniqueId())
        || player.getPersistentDataContainer().has(baselineKey)) {
      restore(player);
      pickup.remove(player.getUniqueId());
    }
    sync();
  }

  void prepareJoin(Player player) throws IOException {
    prepare(player, !sessions.contains(player.getUniqueId()));
    sessions.add(player.getUniqueId());
  }

  void joined(Player player) throws IOException {
    // Also handles servers/test harnesses that skip PlayerLoginEvent.
    prepareJoin(player);
    if (isVanished(player.getUniqueId())) {
      player.sendMessage(Component.text("You joined vanished.", NamedTextColor.AQUA));
      notifyStaff(player, " joined vanished.");
    }
    sync();
    Bukkit.getScheduler().runTask(plugin, this::sync);
  }

  /** Persists a toggle before changing packets, flight, metadata or announcements. */
  public void setVanished(Player player, boolean hidden) throws IOException {
    if (!Bukkit.isPrimaryThread()) {
      throw new IllegalStateException("Vanish changes require the server thread");
    }
    if (isVanished(player.getUniqueId()) == hidden) {
      return;
    }
    if (hidden && !permitted(player)) {
      throw new IllegalArgumentException("Target requires epicsmp.vanish or epicsmp.vanish.auto");
    }
    if (hidden
        && plugin.settings().flag("vanish.prevent-combat-entry")
        && plugin.combat().tag(player.getUniqueId(), System.currentTimeMillis()) != null) {
      throw new IllegalArgumentException("Vanish is blocked while combat tagged");
    }
    if (hidden
        && plugin.settings().flag("vanish.prevent-mounting")
        && (player.isInsideVehicle() || !player.getPassengers().isEmpty())) {
      throw new IllegalArgumentException("Leave your vehicle/passengers before vanishing");
    }
    Set<UUID> previouslyVisible = new HashSet<>();
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      if (viewer.canSee(player)) {
        previouslyVisible.add(viewer.getUniqueId());
      }
    }
    save(player.getUniqueId(), hidden);
    publish(player, hidden);
    if (hidden) {
      plugin.abilities().clear(player);
      plugin.combat().clear(player.getUniqueId());
      player.closeInventory();
      publicChat.put(player.getUniqueId(), plugin.settings().flag("vanish.default-chat-public"));
      apply(player);
      for (org.bukkit.entity.LivingEntity entity : player.getWorld().getLivingEntities()) {
        if (entity instanceof Mob mob
            && player.equals(mob.getTarget())
            && plugin.settings().flag("vanish.prevent-mob-targeting")) {
          mob.setTarget(null);
        }
      }
    } else {
      player.closeInventory();
      restore(player);
      pickup.remove(player.getUniqueId());
    }
    sync();
    plugin.refresh(player);
    player.sendMessage(
        Component.text(
            hidden ? "Vanish enabled." : "Vanish disabled.",
            hidden ? NamedTextColor.AQUA : NamedTextColor.GREEN));
    notifyStaff(player, hidden ? " vanished." : " became visible.");
    if (plugin.settings().flag("vanish.fake-join-quit")) {
      Component message =
          Component.text(
              player.getName() + (hidden ? " left the game" : " joined the game"),
              NamedTextColor.YELLOW);
      for (Player viewer : Bukkit.getOnlinePlayers()) {
        if (!viewer.equals(player)
            && !viewer.hasPermission("epicsmp.vanish.see")
            && (hidden
                ? previouslyVisible.contains(viewer.getUniqueId())
                : viewer.canSee(player))) {
          var user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
          if (user != null) {
            user.sendPacketSilently(
                new com.github.retrooper.packetevents.wrapper.play.server
                    .WrapperPlayServerSystemChatMessage(false, message));
          }
        }
      }
    }
  }

  private void save(UUID id, boolean hidden) throws IOException {
    if (saved.contains(id) == hidden) {
      return;
    }
    Set<UUID> next = new HashSet<>(saved);
    if (hidden) {
      next.add(id);
    } else {
      next.remove(id);
    }
    store.save(next);
    saved = Set.copyOf(next);
  }

  private void publish(Player player, boolean hidden) {
    Map<UUID, VanishView.Identity> identities = new HashMap<>(view.hidden());
    if (hidden) {
      identities.put(player.getUniqueId(), identity(player));
    } else {
      identities.remove(player.getUniqueId());
    }
    Set<UUID> staff = new HashSet<>();
    List<VanishView.Identity> online =
        Bukkit.getOnlinePlayers().stream().map(VanishService::identity).toList();
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      if (viewer.hasPermission("epicsmp.vanish.see")) {
        staff.add(viewer.getUniqueId());
      }
    }
    if (player.hasPermission("epicsmp.vanish.see")) {
      staff.add(player.getUniqueId());
    }
    view = new VanishView(identities, staff, online);
  }

  private static VanishView.Identity identity(Player player) {
    return new VanishView.Identity(player.getUniqueId(), player.getName(), player.getEntityId());
  }

  private void apply(Player player) {
    baselines.computeIfAbsent(player.getUniqueId(), ignored -> baseline(player));
    Baseline baseline = baselines.get(player.getUniqueId());
    // The baseline travels atomically with player.dat if the server crashes during observation.
    player
        .getPersistentDataContainer()
        .set(
            baselineKey,
            org.bukkit.persistence.PersistentDataType.STRING,
            "1;"
                + baseline.flight()
                + ";"
                + baseline.flying()
                + ";"
                + baseline.collision()
                + ";"
                + baseline.silent()
                + ";"
                + baseline.sleeping()
                + ";"
                + baseline.spawning()
                + ";"
                + baseline.mode().name());
    integrations.hidden(player, true);
    player.setMetadata("vanished", new FixedMetadataValue(plugin, true));
    if (plugin.settings().flag("vanish.prevent-gliding")) {
      player.setGliding(false);
    }
    player.setCollidable(
        plugin.settings().flag("vanish.prevent-collision") ? false : baseline.collision());
    player.setSilent(plugin.settings().flag("vanish.silent-entities") || baseline.silent());
    player.setSleepingIgnored(plugin.settings().flag("vanish.ignore-sleep") || baseline.sleeping());
    player.setAffectsSpawning(
        plugin.settings().flag("vanish.prevent-spawning") ? false : baseline.spawning());
    if (plugin.settings().flag("vanish.grant-flight")) {
      player.setAllowFlight(true);
    } else {
      restoreFlight(player, baselines.get(player.getUniqueId()));
    }
    updateVision(player);
  }

  private void updateVision(Player player) {
    UUID id = player.getUniqueId();
    boolean enabled = plugin.settings().flag("vanish.client-night-vision");
    Integer entity = visionEntities.get(id);
    boolean dirty = visionDirty.remove(id);
    if (enabled && (entity == null || entity != player.getEntityId() || dirty)) {
      if (VanishPackets.nightVision(player, player.getEntityId(), null, true)) {
        visionEntities.put(id, player.getEntityId());
      }
    } else if (!enabled && visionEntities.remove(id) != null) {
      VanishPackets.nightVision(
          player,
          player.getEntityId(),
          player.getPotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION),
          false);
    }
  }

  void watchMob(Mob mob) {
    var previous = lookMobs.get(mob.getUniqueId());
    if (previous != null && previous.get() == mob) {
      return;
    }
    lookMobs.put(mob.getUniqueId(), new java.lang.ref.WeakReference<>(mob));
    Bukkit.getMobGoals().addGoal(mob, 0, new VanishMobLook(plugin, this, mob));
  }

  void silentBlock(Player player, org.bukkit.block.Block block) {
    if (!plugin.settings().flag("vanish.silent-block-actions")) {
      return;
    }
    actions.block(
        player.getUniqueId(), block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
  }

  private void restore(Player player) {
    integrations.hidden(player, false);
    publicChat.remove(player.getUniqueId());
    player.removeMetadata("vanished", plugin);
    player.sendActionBar(Component.empty());
    Baseline old = baselines.remove(player.getUniqueId());
    if (old == null && player.getPersistentDataContainer().has(baselineKey)) {
      old = baseline(player);
    }
    if (old == null) {
      return;
    }
    visionDirty.remove(player.getUniqueId());
    if (visionEntities.remove(player.getUniqueId()) != null) {
      VanishPackets.nightVision(
          player,
          player.getEntityId(),
          player.getPotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION),
          false);
    }
    player.setCollidable(old.collision());
    player.setSilent(old.silent());
    player.setSleepingIgnored(old.sleeping());
    player.setAffectsSpawning(old.spawning());
    restoreFlight(player, old);
    player.getPersistentDataContainer().remove(baselineKey);
  }

  private Baseline baseline(Player player) {
    String stored =
        player
            .getPersistentDataContainer()
            .get(baselineKey, org.bukkit.persistence.PersistentDataType.STRING);
    if (stored == null) {
      return new Baseline(
          player.getAllowFlight(),
          player.isFlying(),
          player.isCollidable(),
          player.isSilent(),
          player.isSleepingIgnored(),
          player.getAffectsSpawning(),
          player.getGameMode());
    }
    String[] fields = stored.split(";", -1);
    if (fields.length != 8 || !fields[0].equals("1")) {
      throw new IllegalArgumentException("Invalid saved vanish baseline");
    }
    for (int i = 1; i <= 6; i++) {
      if (!fields[i].equals("true") && !fields[i].equals("false")) {
        throw new IllegalArgumentException("Invalid saved vanish baseline");
      }
    }
    return new Baseline(
        Boolean.parseBoolean(fields[1]),
        Boolean.parseBoolean(fields[2]),
        Boolean.parseBoolean(fields[3]),
        Boolean.parseBoolean(fields[4]),
        Boolean.parseBoolean(fields[5]),
        Boolean.parseBoolean(fields[6]),
        GameMode.valueOf(fields[7]));
  }

  private static void restoreFlight(Player player, Baseline old) {
    if (player.getGameMode() != GameMode.CREATIVE && player.getGameMode() != GameMode.SPECTATOR) {
      boolean naturalMode = old.mode() != GameMode.CREATIVE && old.mode() != GameMode.SPECTATOR;
      boolean allow = naturalMode && old.flight();
      player.setFlying(allow && old.flying());
      player.setAllowFlight(allow);
      player.setFallDistance(0);
    }
  }

  void reload() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (isVanished(player.getUniqueId())) {
        apply(player);
        if (!plugin.settings().flag("vanish.action-bar")) {
          player.sendActionBar(Component.empty());
        }
      }
    }
    sync();
  }

  private void tick() {
    lookMobs.entrySet().removeIf(entry -> entry.getValue().get() == null);
    if (plugin.settings().flag("vanish.prevent-mob-targeting")) {
      for (var reference : lookMobs.values()) {
        Mob mob = reference.get();
        if (mob != null
            && mob.getTarget() instanceof Player target
            && isVanished(target.getUniqueId())) {
          mob.setTarget(null);
        }
      }
    }
    VanishView previous = view;
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (isVanished(player.getUniqueId())) {
        if (!permitted(player)) {
          try {
            setVanished(player, false);
          } catch (IOException e) {
            // Fail closed: keep the player hidden when the persistent edit cannot commit.
            plugin.getLogger().severe("Cannot clear revoked vanish: " + e.getMessage());
          }
        } else {
          apply(player);
          if (plugin.settings().flag("vanish.action-bar")) {
            player.sendActionBar(
                Component.text("Vanished • /vanish to become visible", NamedTextColor.AQUA));
          }
        }
      }
      VanishListener.closeInvalidInspection(this, player);
      publish(player, isVanished(player.getUniqueId()));
    }
    if (!view.equals(previous)) {
      sync();
    }
  }

  void sync() {
    Map<UUID, UUID> worlds = new HashMap<>();
    for (Player player : Bukkit.getOnlinePlayers()) {
      worlds.put(player.getUniqueId(), player.getWorld().getUID());
    }
    actions.worlds(worlds);
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      for (Player subject : Bukkit.getOnlinePlayers()) {
        Pair pair = new Pair(viewer.getUniqueId(), subject.getUniqueId());
        if (!canSee(pair.viewer(), pair.subject())) {
          if (viewer.getGameMode() == GameMode.SPECTATOR
              && subject.equals(viewer.getSpectatorTarget())) {
            viewer.setSpectatorTarget(null);
          }
          viewer.hidePlayer(plugin, subject);
          hiddenPairs.add(pair);
        } else if (hiddenPairs.remove(pair)) {
          viewer.showPlayer(plugin, subject);
        }
      }
      packets.refresh(viewer.getUniqueId(), viewer);
    }
  }

  void left(Player player) {
    sessions.remove(player.getUniqueId());
    restore(player);
    pickup.remove(player.getUniqueId());
    packets.forget(player.getUniqueId());
    UUID id = player.getUniqueId();
    hiddenPairs.removeIf(pair -> pair.viewer().equals(id) || pair.subject().equals(id));
    Bukkit.getScheduler()
        .runTask(
            plugin,
            () -> {
              Map<UUID, VanishView.Identity> next = new HashMap<>(view.hidden());
              // Do not remove a new connection with the same UUID.
              if (Bukkit.getPlayer(id) == null) {
                next.remove(id);
              }
              view =
                  new VanishView(
                      next,
                      view.staff(),
                      Bukkit.getOnlinePlayers().stream().map(VanishService::identity).toList());
              sync();
            });
  }

  boolean picksUp(Player player) {
    return pickup.getOrDefault(
        player.getUniqueId(), plugin.settings().flag("vanish.default-item-pickup"));
  }

  void togglePickup(Player player) {
    if (!isVanished(player.getUniqueId())) {
      throw new IllegalArgumentException("Enable vanish before toggling item pickup");
    }
    pickup.put(player.getUniqueId(), !picksUp(player));
    player.sendMessage(EpicSmpPlugin.message("Vanish item pickup: " + picksUp(player)));
  }

  void resetPickupOverrides() {
    pickup.clear();
  }

  boolean publicChat(UUID player) {
    return publicChat.getOrDefault(player, false);
  }

  void chatMode(Player player, String mode) {
    if (!isVanished(player.getUniqueId())) {
      throw new IllegalArgumentException("Enable vanish before selecting its chat channel");
    }
    boolean next =
        switch (mode) {
          case "public" -> true;
          case "private" -> false;
          case "toggle" -> !publicChat(player.getUniqueId());
          case "status" -> publicChat(player.getUniqueId());
          default ->
              throw new IllegalArgumentException("/vanish chat <private|public|toggle|status>");
        };
    publicChat.put(player.getUniqueId(), next);
    player.sendMessage(EpicSmpPlugin.message("Vanish chat: " + (next ? "public" : "private")));
  }

  void notifyStaff(Player subject, String action) {
    for (Player viewer : Bukkit.getOnlinePlayers()) {
      if (!viewer.equals(subject) && viewer.hasPermission("epicsmp.vanish.see")) {
        viewer.sendMessage(Component.text(subject.getName() + action, NamedTextColor.DARK_AQUA));
      }
    }
  }

  void stop() {
    for (var reference : lookMobs.values()) {
      Mob mob = reference.get();
      if (mob != null && mob.isValid()) {
        Bukkit.getMobGoals().removeGoal(mob, new VanishMobLook(plugin, this, mob).getKey());
      }
    }
    lookMobs.clear();
    if (task != null) {
      task.cancel();
    }
    view = VanishView.EMPTY;
    for (Player player : Bukkit.getOnlinePlayers()) {
      restore(player);
    }
    sync();
    unregisterPlaceholders.run();
    if (registered) {
      PacketEvents.getAPI().getEventManager().unregisterListener(packets);
      registered = false;
    }
    hiddenPairs.clear();
    sessions.clear();
    pickup.clear();
  }
}
