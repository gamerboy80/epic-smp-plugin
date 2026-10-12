package com.epicsmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class VanishCommandTest {
  @Test
  void bothOptionSpellingsCompleteNamesAndSupportedValues() {
    var sender = mock(CommandSender.class);
    when(sender.hasPermission("epicsmp.admin")).thenReturn(true);
    var source = mock(CommandSourceStack.class);
    when(source.getSender()).thenReturn(sender);
    var command = new VanishCommand(null, null);
    for (String action : List.of("option", "options")) {
      assertEquals(
          List.of("prevent-item-drop"),
          command.suggest(source, new String[] {action, "prevent-item-d"}));
      assertEquals(
          List.of("off", "on"),
          command.suggest(source, new String[] {action, "prevent-item-drop", "o"}));
      assertEquals(
          List.of("toggle", "true"),
          command.suggest(source, new String[] {action, "prevent-item-drop", "t"}));
      assertEquals(List.of(), command.suggest(source, new String[] {action, "missing-option", ""}));
    }
  }

  @Test
  void optionNamesAndValuesAreOfferedOnlyToAdmins() {
    var sender = mock(CommandSender.class);
    when(sender.hasPermission("epicsmp.vanish")).thenReturn(true);
    var source = mock(CommandSourceStack.class);
    when(source.getSender()).thenReturn(sender);
    var command = new VanishCommand(null, null);
    assertFalse(command.suggest(source, new String[] {""}).contains("options"));
    assertEquals(List.of(), command.suggest(source, new String[] {"options", ""}));
    assertEquals(
        List.of(), command.suggest(source, new String[] {"option", "prevent-item-drop", ""}));
  }
}
