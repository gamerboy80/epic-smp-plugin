package com.epicsmp;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerResetScore;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerScoreboardObjective;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateScore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Per-connection scoreboard originals and filtered projections, including split text rows. */
final class VanishScoreboard {
  private record ScoreKey(String owner, String objective) {}

  private record Team(ScoreBoardTeamInfo info, Set<String> members) {}

  private final Map<String, Team> teams = new HashMap<>();
  private final Map<ScoreKey, WrapperPlayServerUpdateScore> scores = new HashMap<>();
  private final Map<String, WrapperPlayServerScoreboardObjective> objectives = new HashMap<>();

  synchronized WrapperPlayServerTeams team(
      WrapperPlayServerTeams source, VanishView view, UUID viewer) {
    String name = source.getTeamName();
    Team previous = teams.get(name);
    Set<String> members = new HashSet<>(previous == null ? Set.of() : previous.members());
    ScoreBoardTeamInfo info = previous == null ? null : previous.info();
    switch (source.getTeamMode()) {
      case CREATE -> {
        members = new HashSet<>(source.getPlayers());
        info = source.getTeamInfo().map(VanishScoreboard::copyInfo).orElse(null);
      }
      case UPDATE -> info = source.getTeamInfo().map(VanishScoreboard::copyInfo).orElse(info);
      case ADD_ENTITIES -> members.addAll(source.getPlayers());
      case REMOVE_ENTITIES -> members.removeAll(source.getPlayers());
      case REMOVE -> teams.remove(name);
      default -> throw new IllegalArgumentException("Unknown team operation");
    }
    if (source.getTeamMode() != TeamMode.REMOVE) {
      // Membership is exclusive: a move implicitly removes an entry from its old team.
      if (source.getTeamMode() == TeamMode.CREATE
          || source.getTeamMode() == TeamMode.ADD_ENTITIES) {
        for (Map.Entry<String, Team> entry : teams.entrySet()) {
          if (!entry.getKey().equals(name)) {
            entry.getValue().members().removeAll(source.getPlayers());
          }
        }
      }
      teams.put(name, new Team(info, members));
    }
    List<String> visible =
        source.getPlayers().stream().filter(member -> !view.mentions(viewer, member)).toList();
    Set<String> teamMembers = Set.copyOf(members);
    return new WrapperPlayServerTeams(
        name,
        source.getTeamMode(),
        source
            .getTeamInfo()
            .map(value -> filteredInfo(value, teamMembers, view, viewer))
            .orElse(null),
        visible);
  }

  synchronized boolean score(WrapperPlayServerUpdateScore source, VanishView view, UUID viewer) {
    scores.put(new ScoreKey(source.getEntityName(), source.getObjectiveName()), copyScore(source));
    return hiddenScore(source, view, viewer);
  }

  synchronized void reset(WrapperPlayServerResetScore source) {
    scores
        .keySet()
        .removeIf(
            key ->
                key.owner().equals(source.getTargetName())
                    && (source.getObjective() == null
                        || key.objective().equals(source.getObjective())));
  }

  synchronized WrapperPlayServerScoreboardObjective objective(
      WrapperPlayServerScoreboardObjective source, VanishView view, UUID viewer) {
    if (source.getMode() == WrapperPlayServerScoreboardObjective.ObjectiveMode.REMOVE) {
      objectives.remove(source.getName());
      scores.keySet().removeIf(key -> key.objective().equals(source.getName()));
    } else {
      objectives.put(source.getName(), copyObjective(source));
    }
    WrapperPlayServerScoreboardObjective output = copyObjective(source);
    if (view.mentions(viewer, output.getDisplayName())) {
      output.setDisplayName(Component.empty());
    }
    if (formatMentions(output.getScoreFormat(), view, viewer)) {
      output.setScoreFormat(null);
    }
    return output;
  }

  /** Replays client projections without mutating plugin-owned Bukkit scoreboards. */
  synchronized List<PacketWrapper<?>> projection(VanishView view, UUID viewer) {
    List<PacketWrapper<?>> output = new ArrayList<>();
    for (WrapperPlayServerScoreboardObjective original : objectives.values()) {
      WrapperPlayServerScoreboardObjective update = copyObjective(original);
      update.setMode(WrapperPlayServerScoreboardObjective.ObjectiveMode.UPDATE);
      if (view.mentions(viewer, update.getDisplayName())) {
        update.setDisplayName(Component.empty());
      }
      if (formatMentions(update.getScoreFormat(), view, viewer)) {
        update.setScoreFormat(null);
      }
      output.add(update);
    }
    for (Map.Entry<String, Team> entry : teams.entrySet()) {
      Team team = entry.getValue();
      if (team.info() != null) {
        output.add(
            new WrapperPlayServerTeams(
                entry.getKey(),
                TeamMode.UPDATE,
                filteredInfo(team.info(), team.members(), view, viewer),
                List.of()));
      }
      // Remove originals first, then add only the permitted membership.
      output.add(
          new WrapperPlayServerTeams(
              entry.getKey(),
              TeamMode.REMOVE_ENTITIES,
              (ScoreBoardTeamInfo) null,
              List.copyOf(team.members())));
      output.add(
          new WrapperPlayServerTeams(
              entry.getKey(),
              TeamMode.ADD_ENTITIES,
              (ScoreBoardTeamInfo) null,
              team.members().stream().filter(member -> !view.mentions(viewer, member)).toList()));
    }
    output.addAll(scoreProjection(view, viewer));
    return output;
  }

  synchronized List<PacketWrapper<?>> scoreProjection(VanishView view, UUID viewer) {
    List<PacketWrapper<?>> output = new ArrayList<>();
    for (WrapperPlayServerUpdateScore score : scores.values()) {
      if (hiddenScore(score, view, viewer)) {
        output.add(
            new WrapperPlayServerResetScore(score.getEntityName(), score.getObjectiveName()));
      } else {
        output.add(copyScore(score));
      }
    }
    return output;
  }

  private boolean hiddenScore(WrapperPlayServerUpdateScore score, VanishView view, UUID viewer) {
    if (view.mentions(viewer, score.getEntityName())
        || view.mentions(viewer, score.getEntityDisplayName())
        || formatMentions(score.getScoreFormat(), view, viewer)) {
      return true;
    }
    for (Team team : teams.values()) {
      if (team.info() != null && team.members().contains(score.getEntityName())) {
        String rendered =
            plain(team.info().getPrefix()) + score.getEntityName() + plain(team.info().getSuffix());
        if (view.mentions(viewer, rendered)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean formatMentions(
      com.github.retrooper.packetevents.protocol.score.ScoreFormat format,
      VanishView view,
      UUID viewer) {
    return format instanceof com.github.retrooper.packetevents.protocol.score.FixedScoreFormat fixed
        && view.mentions(viewer, fixed.getValue());
  }

  private static String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  private static ScoreBoardTeamInfo filteredInfo(
      ScoreBoardTeamInfo original, Set<String> members, VanishView view, UUID viewer) {
    ScoreBoardTeamInfo copy = copyInfo(original);
    if (view.mentions(viewer, copy.getDisplayName())) {
      copy.setDisplayName(Component.empty());
    }
    if (view.mentions(viewer, copy.getPrefix())
        || view.mentions(viewer, copy.getSuffix())
        || view.mentions(viewer, plain(copy.getPrefix()) + plain(copy.getSuffix()))
        || members.stream()
            .anyMatch(
                member ->
                    view.mentions(
                        viewer, plain(copy.getPrefix()) + member + plain(copy.getSuffix())))) {
      copy.setPrefix(Component.empty());
      copy.setSuffix(Component.empty());
    }
    return copy;
  }

  private static ScoreBoardTeamInfo copyInfo(ScoreBoardTeamInfo source) {
    return new ScoreBoardTeamInfo(
        source.getDisplayName(),
        source.getPrefix(),
        source.getSuffix(),
        source.getTagVisibility(),
        source.getCollisionRule(),
        source.getColor(),
        source.getOptionData());
  }

  private static WrapperPlayServerUpdateScore copyScore(WrapperPlayServerUpdateScore source) {
    return new WrapperPlayServerUpdateScore(
        source.getEntityName(),
        WrapperPlayServerUpdateScore.Action.CREATE_OR_UPDATE_ITEM,
        source.getObjectiveName(),
        source.getValue().orElse(0),
        source.getEntityDisplayName(),
        source.getScoreFormat());
  }

  private static WrapperPlayServerScoreboardObjective copyObjective(
      WrapperPlayServerScoreboardObjective source) {
    return new WrapperPlayServerScoreboardObjective(
        source.getName(),
        source.getMode(),
        source.getDisplayName(),
        source.getRenderType(),
        source.getScoreFormat());
  }
}
