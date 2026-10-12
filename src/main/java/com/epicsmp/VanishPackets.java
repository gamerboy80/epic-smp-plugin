package com.epicsmp;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCamera;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerRemoveEntityEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Recipient-specific PacketEvents firewall with no Bukkit calls from Netty threads. */
final class VanishPackets extends PacketListenerAbstract {
  private final Supplier<VanishView> policy;
  private final BooleanSupplier clientVision;
  private final VanishActions actions;
  private final AtomicBoolean warned = new AtomicBoolean();
  // User identity binds each cache to one connection, even when a UUID reconnects immediately.
  // Weak keys also release late writes from a channel that has already disconnected.
  private final Map<User, VanishScoreboard> boards =
      Collections.synchronizedMap(new WeakHashMap<>());
  private final Map<User, Map<Integer, WrapperPlayServerMapData>> maps =
      Collections.synchronizedMap(new WeakHashMap<>());

  VanishPackets(Supplier<VanishView> policy, BooleanSupplier clientVision) {
    this(policy, clientVision, new VanishActions());
  }

  VanishPackets(Supplier<VanishView> policy, BooleanSupplier clientVision, VanishActions actions) {
    super(PacketListenerPriority.HIGHEST);
    this.actions = actions;
    this.policy = policy;
    this.clientVision = clientVision;
  }

  static boolean nightVision(
      Object player, int entityId, org.bukkit.potion.PotionEffect real, boolean enabled) {
    var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null
        || user.getEncoderState()
            != com.github.retrooper.packetevents.protocol.ConnectionState.PLAY) {
      return false;
    }
    if (enabled) {
      user.sendPacketSilently(
          new WrapperPlayServerEntityEffect(
              entityId,
              com.github.retrooper.packetevents.protocol.potion.PotionTypes.NIGHT_VISION,
              0,
              -1,
              (byte) 0));
    } else if (real != null) {
      user.sendPacketSilently(
          new WrapperPlayServerRemoveEntityEffect(
              entityId,
              com.github.retrooper.packetevents.protocol.potion.PotionTypes.NIGHT_VISION));
      byte flags =
          (byte)
              ((real.isAmbient() ? 1 : 0)
                  | (real.hasParticles() ? 2 : 0)
                  | (real.hasIcon() ? 4 : 0));
      user.sendPacketSilently(
          new WrapperPlayServerEntityEffect(
              entityId,
              com.github.retrooper.packetevents.protocol.potion.PotionTypes.NIGHT_VISION,
              real.getAmplifier(),
              real.getDuration(),
              flags));
    } else {
      user.sendPacketSilently(
          new WrapperPlayServerRemoveEntityEffect(
              entityId,
              com.github.retrooper.packetevents.protocol.potion.PotionTypes.NIGHT_VISION));
    }
    return true;
  }

  void forget(UUID player) {
    synchronized (boards) {
      boards.keySet().removeIf(user -> player.equals(user.getUUID()));
    }
    synchronized (maps) {
      maps.keySet().removeIf(user -> player.equals(user.getUUID()));
    }
  }

  void refresh(UUID viewer, Object player) {
    User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
    if (user == null) {
      return;
    }
    try {
      PacketEvents.getAPI()
          .getNettyManager()
          .getChannelOperator()
          .runInEventLoop(
              user.getChannel(),
              () -> {
                try {
                  VanishView current = policy.get();
                  Map<Integer, WrapperPlayServerMapData> knownMaps = maps.get(user);
                  if (knownMaps != null) {
                    knownMaps
                        .values()
                        .forEach(map -> user.sendPacketSilently(projectMap(map, current, viewer)));
                  }
                  VanishScoreboard board = boards.get(user);
                  if (board != null) {
                    for (PacketWrapper<?> packet : board.projection(current, viewer)) {
                      // Already projected: do not cache our own filtered state as the original.
                      user.sendPacketSilently(packet);
                    }
                  }
                } catch (RuntimeException | LinkageError error) {
                  failClosed(user, error);
                }
              });
    } catch (RuntimeException | LinkageError error) {
      failClosed(user, error);
    }
  }

  @Override
  public void onPacketSend(PacketSendEvent event) {
    try {
      filter(event);
    } catch (RuntimeException | LinkageError error) {
      // Disconnect also removes previously cached client rows that could no longer be filtered.
      event.setCancelled(true);
      failClosed(event.getUser(), error);
    }
  }

  private void failClosed(User user, Throwable error) {
    if (warned.compareAndSet(false, true)) {
      java.util.logging.Logger.getLogger("EpicSmpPlugin")
          .log(java.util.logging.Level.SEVERE, "Vanish packet filtering failed closed", error);
    }
    user.closeConnection();
  }

  private void filter(PacketSendEvent event) {
    if (event.isCancelled() || !(event.getPacketType() instanceof PacketType.Play.Server type)) {
      return;
    }
    UUID viewer = event.getUser().getUUID();
    if (viewer == null) {
      return;
    }
    VanishView view = policy.get();
    if (view.hidden().containsKey(viewer) && clientVision.getAsBoolean()) {
      if (type == PacketType.Play.Server.ENTITY_EFFECT) {
        WrapperPlayServerEntityEffect effect = new WrapperPlayServerEntityEffect(event);
        if (effect.getEntityId() == view.hidden().get(viewer).entityId()
            && effect.getPotionType()
                == com.github.retrooper.packetevents.protocol.potion.PotionTypes.NIGHT_VISION) {
          event.setCancelled(true);
          return;
        }
      } else if (type == PacketType.Play.Server.REMOVE_ENTITY_EFFECT) {
        WrapperPlayServerRemoveEntityEffect effect = new WrapperPlayServerRemoveEntityEffect(event);
        if (effect.getEntityId() == view.hidden().get(viewer).entityId()
            && effect.getPotionType()
                == com.github.retrooper.packetevents.protocol.potion.PotionTypes.NIGHT_VISION) {
          event.setCancelled(true);
          return;
        }
      }
    }
    switch (type) {
      case PLAYER_INFO_UPDATE -> {
        WrapperPlayServerPlayerInfoUpdate packet = new WrapperPlayServerPlayerInfoUpdate(event);
        List<WrapperPlayServerPlayerInfoUpdate.PlayerInfo> visible =
            packet.getEntries().stream()
                .filter(entry -> !view.hides(viewer, entry.getProfileId()))
                .toList();
        if (visible.isEmpty()) {
          event.setCancelled(true);
        } else if (visible.size() != packet.getEntries().size()) {
          packet.setEntries(visible);
          event.markForReEncode(true);
        }
      }
      case SPAWN_ENTITY -> {
        WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
        if (view.hidesEntity(viewer, packet.getEntityId())
            || packet.getUUID().map(id -> view.hides(viewer, id)).orElse(false)) {
          event.setCancelled(true);
        }
      }
      case TAB_COMPLETE -> {
        WrapperPlayServerTabComplete packet = new WrapperPlayServerTabComplete(event);
        packet.setCommandMatches(
            packet.getCommandMatches().stream()
                .filter(
                    match ->
                        !view.mentions(viewer, match.getText())
                            && !match
                                .getTooltip()
                                .map(text -> view.mentions(viewer, text))
                                .orElse(false))
                .toList());
        event.markForReEncode(true);
      }
      case TEAMS -> {
        VanishScoreboard board =
            boards.computeIfAbsent(event.getUser(), ignored -> new VanishScoreboard());
        WrapperPlayServerTeams original = new WrapperPlayServerTeams(event);
        event.setLastUsedWrapper(board.team(original, view, viewer));
        event.markForReEncode(true);
        // Run after this team update is written; split prefix/entry/suffix rows may change
        // visibility.
        event
            .getTasksAfterSend()
            .add(
                () -> {
                  try {
                    for (PacketWrapper<?> update : board.scoreProjection(policy.get(), viewer)) {
                      event.getUser().sendPacketSilently(update);
                    }
                  } catch (RuntimeException | LinkageError error) {
                    failClosed(event.getUser(), error);
                  }
                });
      }
      case UPDATE_SCORE -> {
        WrapperPlayServerUpdateScore packet = new WrapperPlayServerUpdateScore(event);
        if (boards
            .computeIfAbsent(event.getUser(), ignored -> new VanishScoreboard())
            .score(packet, view, viewer)) {
          event.setCancelled(true);
          event
              .getUser()
              .sendPacketSilently(
                  new WrapperPlayServerResetScore(
                      packet.getEntityName(), packet.getObjectiveName()));
        }
      }
      case RESET_SCORE -> {
        VanishScoreboard board = boards.get(event.getUser());
        if (board != null) {
          board.reset(new WrapperPlayServerResetScore(event));
        }
      }
      case SCOREBOARD_OBJECTIVE -> {
        VanishScoreboard board =
            boards.computeIfAbsent(event.getUser(), ignored -> new VanishScoreboard());
        event.setLastUsedWrapper(
            board.objective(new WrapperPlayServerScoreboardObjective(event), view, viewer));
        event.markForReEncode(true);
      }
      case SET_PASSENGERS -> {
        WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
        if (view.hidesEntity(viewer, packet.getEntityId())) {
          event.setCancelled(true);
        } else {
          packet.setPassengers(
              Arrays.stream(packet.getPassengers())
                  .filter(id -> !view.hidesEntity(viewer, id))
                  .toArray());
          event.markForReEncode(true);
        }
      }
      case ATTACH_ENTITY -> {
        WrapperPlayServerAttachEntity packet = new WrapperPlayServerAttachEntity(event);
        if (view.hidesEntity(viewer, packet.getAttachedId())
            || view.hidesEntity(viewer, packet.getHoldingId())) {
          event.setCancelled(true);
        }
      }
      case SOUND_EFFECT -> {
        var sound =
            new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect(
                event);
        String key = sound.getSound().getName().getKey();
        var position = sound.getPosition();
        if (key.startsWith("block.")
            && (key.endsWith(".break") || key.endsWith(".place") || key.endsWith(".hit"))
            && actions.silent(viewer, position.getX(), position.getY(), position.getZ())) {
          event.setCancelled(true);
        }
      }
      case EFFECT -> {
        var effect =
            new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect(
                event);
        var position = effect.getPosition();
        if (effect.getType() == 2001
            && actions.silent(viewer, position.getX(), position.getY(), position.getZ())) {
          event.setCancelled(true);
        }
      }
      case BLOCK_BREAK_ANIMATION -> {
        var animation =
            new com.github.retrooper.packetevents.wrapper.play.server
                .WrapperPlayServerBlockBreakAnimation(event);
        if (view.hidesEntity(viewer, animation.getEntityId())) {
          event.setCancelled(true);
        }
      }
      case DAMAGE_EVENT -> {
        WrapperPlayServerDamageEvent packet = new WrapperPlayServerDamageEvent(event);
        if (view.hidesEntity(viewer, packet.getEntityId())) {
          event.setCancelled(true);
        } else if (view.hidesEntity(viewer, packet.getSourceCauseId())
            || view.hidesEntity(viewer, packet.getSourceDirectId())) {
          packet.setSourceCauseId(-1);
          packet.setSourceDirectId(-1);
          packet.setSourcePosition(null);
          event.markForReEncode(true);
        }
      }
      case SYSTEM_CHAT_MESSAGE -> {
        if (view.mentions(viewer, new WrapperPlayServerSystemChatMessage(event).getMessage())) {
          event.setCancelled(true);
        }
      }
      case MAP_DATA -> {
        WrapperPlayServerMapData packet = new WrapperPlayServerMapData(event);
        if (packet.getDecorations() != null) {
          WrapperPlayServerMapData original =
              new WrapperPlayServerMapData(
                  packet.getMapId(),
                  packet.getScale(),
                  packet.isTrackingPosition(),
                  packet.isLocked(),
                  List.copyOf(packet.getDecorations()),
                  0,
                  0,
                  0,
                  0,
                  null);
          maps.computeIfAbsent(event.getUser(), ignored -> new ConcurrentHashMap<>())
              .put(packet.getMapId(), original);
          packet.setDecorations(projectMap(original, view, viewer).getDecorations());
          event.markForReEncode(true);
        }
      }
      default -> {
        int id = entityId(type, event);
        if (id != -1 && view.hidesEntity(viewer, id)) {
          event.setCancelled(true);
        }
      }
    }
  }

  private static WrapperPlayServerMapData projectMap(
      WrapperPlayServerMapData map, VanishView view, UUID viewer) {
    return new WrapperPlayServerMapData(
        map.getMapId(),
        map.getScale(),
        map.isTrackingPosition(),
        map.isLocked(),
        java.util.Objects.requireNonNull(map.getDecorations()).stream()
            .filter(marker -> !view.mentions(viewer, marker.getDisplayName()))
            .filter(
                marker ->
                    !hasHiddenOthers(view, viewer)
                        || !marker.getType().getName().getKey().startsWith("player"))
            .toList(),
        0,
        0,
        0,
        0,
        null);
  }

  private static boolean hasHiddenOthers(VanishView view, UUID viewer) {
    return view.hidden().keySet().stream().anyMatch(id -> view.hides(viewer, id));
  }

  private static int entityId(PacketType.Play.Server type, PacketSendEvent event) {
    return switch (type) {
      case ENTITY_ANIMATION -> new WrapperPlayServerEntityAnimation(event).getEntityId();
      case ENTITY_RELATIVE_MOVE -> new WrapperPlayServerEntityRelativeMove(event).getEntityId();
      case ENTITY_RELATIVE_MOVE_AND_ROTATION ->
          new WrapperPlayServerEntityRelativeMoveAndRotation(event).getEntityId();
      case ENTITY_ROTATION -> new WrapperPlayServerEntityRotation(event).getEntityId();
      case ENTITY_HEAD_LOOK -> new WrapperPlayServerEntityHeadLook(event).getEntityId();
      case ENTITY_METADATA -> new WrapperPlayServerEntityMetadata(event).getEntityId();
      case ENTITY_VELOCITY -> new WrapperPlayServerEntityVelocity(event).getEntityId();
      case ENTITY_EQUIPMENT -> new WrapperPlayServerEntityEquipment(event).getEntityId();
      case ENTITY_TELEPORT -> new WrapperPlayServerEntityTeleport(event).getEntityId();
      case ENTITY_EFFECT -> new WrapperPlayServerEntityEffect(event).getEntityId();
      case REMOVE_ENTITY_EFFECT -> new WrapperPlayServerRemoveEntityEffect(event).getEntityId();
      case ENTITY_SOUND_EFFECT -> new WrapperPlayServerEntitySoundEffect(event).getEntityId();
      case ENTITY_STATUS -> new WrapperPlayServerEntityStatus(event).getEntityId();
      case CAMERA -> new WrapperPlayServerCamera(event).getCameraId();
      case ENTITY_POSITION_SYNC ->
          new com.github.retrooper.packetevents.wrapper.play.server
                  .WrapperPlayServerEntityPositionSync(event)
              .getId();
      default -> -1;
    };
  }
}
