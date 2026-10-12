package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanishTest {
  @TempDir Path directory;
  private final UUID hiddenId = UUID.randomUUID();
  private final UUID viewer = UUID.randomUUID();
  private final UUID staff = UUID.randomUUID();
  private VanishView view;

  @BeforeEach
  void setup() {
    TestPacketEvents.install();
    view =
        new VanishView(
            Map.of(hiddenId, new VanishView.Identity(hiddenId, "Alice", 17)),
            Set.of(staff),
            List.of());
  }

  @Test
  void policySeparatesSelfStaffPublicAndUnrelatedNames() {
    assertTrue(view.hides(viewer, hiddenId));
    assertFalse(view.hides(hiddenId, hiddenId));
    assertFalse(view.hides(staff, hiddenId));
    assertTrue(view.hides(null, hiddenId));
    assertTrue(view.hidesEntity(viewer, 17));
    assertFalse(view.hidesEntity(viewer, 18));
    assertTrue(view.mentions(viewer, "§6[#1] §aALICE§r: 5"));
    assertTrue(view.mentions(viewer, hiddenId.toString()));
    assertFalse(view.mentions(viewer, "Malice and Alice_2"));
    assertFalse(view.mentions(staff, "Alice"));
  }

  @Test
  void stateSurvivesRestartAndCorruptionDoesNotResetVisibility() throws IOException {
    Path file = directory.resolve("vanish.yml");
    VanishStore store = new VanishStore(file);
    assertTrue(store.load().isEmpty());
    store.save(Set.of(hiddenId));
    assertEquals(Set.of(hiddenId), new VanishStore(file).load());
    store.save(Set.of());
    assertTrue(store.load().isEmpty());
    Files.writeString(file, "schema: 1\nvanished: [not-a-uuid]\n");
    assertThrows(IOException.class, store::load);
  }

  @Test
  void scoresHideOnToggleAndRestoreOriginalOnRevealOrPermissionChange() {
    VanishScoreboard board = new VanishScoreboard();
    assertFalse(board.score(score("Alice", null), VanishView.EMPTY, viewer));
    assertTrue(
        board.projection(view, viewer).stream()
            .anyMatch(WrapperPlayServerResetScore.class::isInstance));
    List<PacketWrapper<?>> revealed = board.projection(VanishView.EMPTY, viewer);
    assertTrue(revealed.stream().anyMatch(WrapperPlayServerUpdateScore.class::isInstance));
    assertTrue(
        board.projection(view, staff).stream()
            .anyMatch(WrapperPlayServerUpdateScore.class::isInstance));
    board.reset(new WrapperPlayServerResetScore("Alice", "sidebar"));
    assertTrue(board.projection(VanishView.EMPTY, viewer).isEmpty());
  }

  @Test
  void teamFilteringNeverMutatesSharedOriginalOrOtherViewers() {
    VanishScoreboard board = new VanishScoreboard();
    ScoreBoardTeamInfo info = info("[Vanished] Alice", "");
    WrapperPlayServerTeams original =
        new WrapperPlayServerTeams("staff", TeamMode.CREATE, info, List.of("Alice", "Bob"));
    WrapperPlayServerTeams filtered = board.team(original, view, viewer);
    assertEquals(List.of("Bob"), filtered.getPlayers());
    assertEquals(Component.empty(), filtered.getTeamInfo().orElseThrow().getPrefix());
    assertEquals(Component.text("[Vanished] Alice"), info.getPrefix());
    assertEquals(List.of("Alice", "Bob"), original.getPlayers());
    WrapperPlayServerTeams allowed = new VanishScoreboard().team(original, view, staff);
    assertEquals(List.of("Alice", "Bob"), allowed.getPlayers());
    assertEquals(info.getPrefix(), allowed.getTeamInfo().orElseThrow().getPrefix());
  }

  @Test
  void splitTeamPrefixAndSuffixHideRowsEvenAfterUpdates() {
    VanishScoreboard board = new VanishScoreboard();
    board.team(
        new WrapperPlayServerTeams("row", TeamMode.CREATE, info("Al", "ice"), List.of("§0")),
        view,
        viewer);
    assertTrue(board.score(score("§0", null), view, viewer));
    board.team(
        new WrapperPlayServerTeams("row", TeamMode.UPDATE, info("Bob", ""), List.of()),
        view,
        viewer);
    assertTrue(
        board.scoreProjection(view, viewer).stream()
            .allMatch(WrapperPlayServerUpdateScore.class::isInstance));
    assertTrue(board.score(score("row2", Component.text("Alice: 15")), view, viewer));
  }

  @Test
  void objectiveDeletionAndTeamMovesCannotResurrectStaleRows() {
    VanishScoreboard board = new VanishScoreboard();
    board.team(
        new WrapperPlayServerTeams("old", TeamMode.CREATE, info("Alice", ""), List.of("§0")),
        view,
        viewer);
    board.score(score("§0", null), view, viewer);
    board.team(
        new WrapperPlayServerTeams("new", TeamMode.CREATE, info("Bob", ""), List.of("§0")),
        view,
        viewer);
    assertTrue(
        board.scoreProjection(view, viewer).stream()
            .allMatch(WrapperPlayServerUpdateScore.class::isInstance));
    board.objective(
        new WrapperPlayServerScoreboardObjective(
            "sidebar",
            WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE,
            Component.empty(),
            null),
        view,
        viewer);
    assertTrue(board.scoreProjection(VanishView.EMPTY, viewer).isEmpty());
  }

  @Test
  void splitRowsIncludingTheEntryAndHoverClickMetadataAreFiltered() {
    VanishScoreboard board = new VanishScoreboard();
    var original =
        new WrapperPlayServerTeams("row", TeamMode.CREATE, info("Al", "ce"), List.of("i"));
    var filtered = board.team(original, view, viewer);
    assertEquals(Component.empty(), filtered.getTeamInfo().orElseThrow().getPrefix());
    assertTrue(board.score(score("i", null), view, viewer));
    Component metadata =
        Component.text("Details")
            .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/tp Alice"));
    assertTrue(view.mentions(viewer, metadata));
    assertFalse(view.mentions(staff, metadata));
  }

  private static WrapperPlayServerUpdateScore score(String owner, Component display) {
    return new WrapperPlayServerUpdateScore(
        owner,
        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
        "sidebar",
        5,
        display,
        null);
  }

  private static ScoreBoardTeamInfo info(String prefix, String suffix) {
    return new ScoreBoardTeamInfo(
        Component.text("Team"),
        Component.text(prefix),
        Component.text(suffix),
        WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
        WrapperPlayServerTeams.CollisionRule.ALWAYS,
        NamedTextColor.WHITE,
        WrapperPlayServerTeams.OptionData.NONE);
  }
}
