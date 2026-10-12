package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTabComplete;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VanishPacketsTest {
  private final UUID hidden = UUID.randomUUID();
  private final UUID viewer = UUID.randomUUID();
  private VanishPackets packets;
  private VanishView policy;

  @BeforeEach
  void setup() {
    TestPacketEvents.install();
    policy =
        new VanishView(
            Map.of(hidden, new VanishView.Identity(hidden, "Alice", 7)), Set.of(), List.of());
    packets = new VanishPackets(() -> policy, () -> true);
  }

  @Test
  void playerInfoIsFilteredWithoutMutatingTheSharedPacket() {
    var original =
        new WrapperPlayServerPlayerInfoUpdate(
            WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
            new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(hidden),
            new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(viewer));
    PacketSendEvent event = event(PacketType.Play.Server.PLAYER_INFO_UPDATE, original);
    packets.onPacketSend(event);
    ArgumentCaptor<PacketWrapper<?>> written = ArgumentCaptor.forClass(PacketWrapper.class);
    verify(event).setLastUsedWrapper(written.capture());
    var result = (WrapperPlayServerPlayerInfoUpdate) written.getValue();
    assertEquals(
        List.of(viewer),
        result.getEntries().stream()
            .map(WrapperPlayServerPlayerInfoUpdate.PlayerInfo::getProfileId)
            .toList());
    assertEquals(2, original.getEntries().size());
    verify(event).markForReEncode(true);
  }

  @Test
  void hiddenScoreIsCancelledAndResetUsesItsOwnPacketType() {
    var original =
        new WrapperPlayServerUpdateScore(
            "Alice",
            WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
            "sidebar",
            12,
            null,
            null);
    PacketSendEvent event = event(PacketType.Play.Server.UPDATE_SCORE, original);
    packets.onPacketSend(event);
    verify(event).setCancelled(true);
    verify(event, never()).markForReEncode(true);
    ArgumentCaptor<PacketWrapper<?>> sent = ArgumentCaptor.forClass(PacketWrapper.class);
    verify(event.getUser()).sendPacketSilently(sent.capture());
    assertTrue(sent.getValue() instanceof WrapperPlayServerResetScore);
  }

  @Test
  void entityMetadataIsBlockedForPublicAndDeliveredToStaff() {
    var original = new WrapperPlayServerEntityMetadata(7, List.of());
    PacketSendEvent event = event(PacketType.Play.Server.ENTITY_METADATA, original);
    packets.onPacketSend(event);
    verify(event).setCancelled(true);
    policy = new VanishView(policy.hidden(), Set.of(viewer), List.of());
    PacketSendEvent allowed = event(PacketType.Play.Server.ENTITY_METADATA, original);
    packets.onPacketSend(allowed);
    verify(allowed, never()).setCancelled(true);
  }

  @Test
  void suggestionsRemoveNamesAndTooltipsWithHiddenIdentity() {
    var original =
        new WrapperPlayServerTabComplete(
            1,
            new WrapperPlayServerTabComplete.CommandRange(0, 3),
            List.of(
                new WrapperPlayServerTabComplete.CommandMatch("Alice"),
                new WrapperPlayServerTabComplete.CommandMatch("Bob"),
                new WrapperPlayServerTabComplete.CommandMatch(
                    "goto", net.kyori.adventure.text.Component.text("Teleport to Alice"))));
    PacketSendEvent event = event(PacketType.Play.Server.TAB_COMPLETE, original);
    packets.onPacketSend(event);
    ArgumentCaptor<PacketWrapper<?>> written = ArgumentCaptor.forClass(PacketWrapper.class);
    verify(event).setLastUsedWrapper(written.capture());
    var result = (WrapperPlayServerTabComplete) written.getValue();
    assertEquals(
        List.of("Bob"),
        result.getCommandMatches().stream()
            .map(WrapperPlayServerTabComplete.CommandMatch::getText)
            .toList());
    assertEquals(3, original.getCommandMatches().size());
  }

  @Test
  void malformedPacketsFailClosed() {
    PacketSendEvent event =
        event(
            PacketType.Play.Server.ENTITY_METADATA,
            new WrapperPlayServerEntityMetadata(7, List.of()));
    when(event.getLastUsedWrapper()).thenThrow(new IllegalArgumentException("Malformed packet"));
    packets.onPacketSend(event);
    verify(event).setCancelled(true);
    verify(event.getUser()).closeConnection();
    verify(event.getUser(), never()).sendPacketSilently(any(PacketWrapper.class));
  }

  @Test
  void blockEffectsAreSilentOnlyAtTheActionInTheViewersWorld() {
    var actions = new VanishActions();
    UUID world = UUID.randomUUID();
    actions.worlds(Map.of(viewer, world, hidden, world));
    packets = new VanishPackets(() -> policy, () -> true, actions);
    var original =
        new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect(
            2001, new com.github.retrooper.packetevents.util.Vector3i(-2, 64, 3), 1, false);
    PacketSendEvent silent = event(PacketType.Play.Server.EFFECT, original);
    actions.block(hidden, world, -2, 64, 3);
    packets.onPacketSend(silent);
    verify(silent).setCancelled(true);
    assertTrue(!actions.silent(hidden, -1.5, 64.5, 3.5));
    actions.worlds(Map.of(viewer, UUID.randomUUID()));
    PacketSendEvent otherWorld = event(PacketType.Play.Server.EFFECT, original);
    packets.onPacketSend(otherWorld);
    verify(otherWorld, never()).setCancelled(true);
  }

  @Test
  void hiddenBlockCracksAreSuppressed() {
    var original =
        new com.github.retrooper.packetevents.wrapper.play.server
            .WrapperPlayServerBlockBreakAnimation(
            7, new com.github.retrooper.packetevents.util.Vector3i(1, 64, 1), (byte) 2);
    PacketSendEvent event = event(PacketType.Play.Server.BLOCK_BREAK_ANIMATION, original);
    packets.onPacketSend(event);
    verify(event).setCancelled(true);
  }

  @Test
  void hiddenAttackerIsRedactedWithoutSuppressingVictimHurt() {
    var original =
        new com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent(
            8,
            com.github.retrooper.packetevents.protocol.world.damagetype.DamageTypes.PLAYER_ATTACK,
            7,
            7,
            new com.github.retrooper.packetevents.util.Vector3d(1, 64, 1));
    PacketSendEvent event = event(PacketType.Play.Server.DAMAGE_EVENT, original);
    packets.onPacketSend(event);
    verify(event, never()).setCancelled(true);
    ArgumentCaptor<PacketWrapper<?>> written = ArgumentCaptor.forClass(PacketWrapper.class);
    verify(event).setLastUsedWrapper(written.capture());
    var result =
        (com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDamageEvent)
            written.getValue();
    assertEquals(-1, result.getSourceCauseId());
    assertEquals(-1, result.getSourceDirectId());
    org.junit.jupiter.api.Assertions.assertNull(result.getSourcePosition());
    assertEquals(7, original.getSourceCauseId());
  }

  @Test
  void realNightVisionRestorationRemovesInfiniteOverlayBeforeSendingPotion() {
    Object player = new Object();
    User user = mock(User.class);
    when(com.github.retrooper.packetevents.PacketEvents.getAPI().getPlayerManager().getUser(player))
        .thenReturn(user);
    org.junit.jupiter.api.Assertions.assertFalse(VanishPackets.nightVision(player, 7, null, true));
    verify(user, never()).sendPacketSilently(any(PacketWrapper.class));
    when(user.getEncoderState())
        .thenReturn(com.github.retrooper.packetevents.protocol.ConnectionState.PLAY);
    var real =
        new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION, 600, 0);
    assertTrue(VanishPackets.nightVision(player, 7, real, false));
    var order = org.mockito.Mockito.inOrder(user);
    order
        .verify(user)
        .sendPacketSilently(
            any(
                com.github.retrooper.packetevents.wrapper.play.server
                    .WrapperPlayServerRemoveEntityEffect.class));
    order
        .verify(user)
        .sendPacketSilently(
            any(
                com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEffect
                    .class));
  }

  private PacketSendEvent event(PacketType.Play.Server type, PacketWrapper<?> original) {
    PacketSendEvent event = mock(PacketSendEvent.class);
    User user = mock(User.class);
    when(user.getUUID()).thenReturn(viewer);
    when(user.getClientVersion()).thenReturn(ClientVersion.V_26_2);
    when(event.getUser()).thenReturn(user);
    when(event.getServerVersion()).thenReturn(ServerVersion.V_26_2);
    when(event.getPacketType()).thenReturn(type);
    doReturn(original).when(event).getLastUsedWrapper();
    return event;
  }
}
