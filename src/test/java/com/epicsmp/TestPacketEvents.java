package com.epicsmp;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;

/** PacketEvents boundary for tests; production always requires the real initialized plugin. */
public final class TestPacketEvents {
  private TestPacketEvents() {}

  /** Installs a packet transport double, retaining real wrappers and protocol versioning. */
  public static void install() {
    PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
    ServerManager server = mock(ServerManager.class);
    when(server.getVersion()).thenReturn(ServerVersion.V_26_2);
    when(api.getServerManager()).thenReturn(server);
    when(api.getPlayerManager()).thenReturn(mock(PlayerManager.class));
    var netty = mock(com.github.retrooper.packetevents.netty.NettyManager.class);
    var channels = mock(com.github.retrooper.packetevents.netty.channel.ChannelOperator.class);
    when(api.getNettyManager()).thenReturn(netty);
    when(netty.getChannelOperator()).thenReturn(channels);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              invocation.getArgument(1, Runnable.class).run();
              return null;
            })
        .when(channels)
        .runInEventLoop(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(Runnable.class));
    when(api.getEventManager()).thenReturn(new EventManager());
    when(api.getSettings()).thenReturn(new PacketEventsSettings());
    when(api.isInitialized()).thenReturn(true);
    when(api.getVersion())
        .thenReturn(new com.github.retrooper.packetevents.util.PEVersion(2, 14, 0));
    PacketEvents.setAPI(api);
  }
}
