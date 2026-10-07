package dev.blockagentspaces.service;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.atomic.AtomicBoolean;

/** Saves domain state on the server thread; bridge workers only mark it dirty through WorldState events. */
public final class RuntimeStatePersistence {
    private final WorldState state;
    private final AtomicBoolean dirty = new AtomicBoolean();

    public RuntimeStatePersistence(WorldState state) {
        this.state = state;
        state.addListener(ignored -> dirty.set(true));
    }

    /** Must run before demo seeding and before the local bridge accepts mutations. */
    public boolean restore(MinecraftServer server) {
        boolean restored = RuntimeStateStore.get(server.overworld()).restoreInto(state);
        dirty.set(false);
        return restored;
    }

    /** Called from END_SERVER_TICK. */
    public void tick(MinecraftServer server) {
        if (dirty.compareAndSet(true, false)) RuntimeStateStore.get(server.overworld()).saveFrom(state);
    }

    /** Keeps the last server-thread snapshot dirty for Minecraft's normal world-save cycle. */
    public void flush(MinecraftServer server) {
        RuntimeStateStore.get(server.overworld()).saveFrom(state);
        dirty.set(false);
    }
}
