package dev.blockagentspaces.service;

import dev.blockagentspaces.world.WorkspaceBuilder;
import net.minecraft.server.MinecraftServer;

/** Schedules bridge-driven visual updates on the Minecraft server thread after a short quiet period. */
public final class WorkspaceAutoRefresher {
    private static final int QUIET_TICKS = 5;
    private final RefreshDebouncer debouncer = new RefreshDebouncer(QUIET_TICKS);
    private final WorldState state;
    private final WorkspaceBuilder builder;

    public WorkspaceAutoRefresher(WorldState state, WorkspaceBuilder builder) {
        this.state = state;
        this.builder = builder;
        state.addListener(ignored -> debouncer.request());
    }

    /** Safe bridge callback: records the presentation source and schedules, but never touches a world. */
    public void requestExternalUpdate() {
        state.markExternalUpdate();
        debouncer.request();
    }

    /** Invoked by Fabric's END_SERVER_TICK event, never by bridge worker threads. */
    public RefreshReport tick(MinecraftServer server) {
        if (!debouncer.ready()) return RefreshReport.NONE;
        int refreshed = 0;
        int protectedFromOverwrite = 0;
        for (var level : server.getAllLevels()) {
            WorkspaceBuilder.RefreshResult result = builder.refreshInstalled(level, state);
            refreshed += result.refreshedInstallations();
            protectedFromOverwrite += result.protectedInstallations();
        }
        return new RefreshReport(refreshed, protectedFromOverwrite);
    }

    public record RefreshReport(int refreshedInstallations, int protectedInstallations) {
        public static final RefreshReport NONE = new RefreshReport(0, 0);
        public boolean didWork() { return refreshedInstallations > 0 || protectedInstallations > 0; }
    }
}
