package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeKit;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.player.GameTeam;

public class SiegePlayer {
    public GameTeam team;
    public SiegeKit kit;
    @Nullable
    public AttackRecord lastTimeWasAttacked;
    public long timeOfDeath;
    public long timeOfSpawn;

    // If they have attacked this life, then their respawn invulnerability is removed
    public boolean attackedThisLife;
    private final Object2IntOpenHashMap<SiegePersonalResource> resources = new Object2IntOpenHashMap<>();

    // Statistics
    public int deaths;
    public int kills;
    public int captures;
    public int secures;

    public SiegePlayer(GameTeam team) {
        this.team = team;
        this.kit = SiegeKit.SOLDIER;
        this.resources.put(SiegePersonalResource.WOOD, SiegePersonalResource.WOOD.max);
        this.resources.put(SiegePersonalResource.TNT, SiegePersonalResource.TNT.max);
        this.resources.defaultReturnValue(0);
    }

    public ServerPlayerEntity attacker(long time, ServerWorld world) {
        if (this.lastTimeWasAttacked != null) {
            return this.lastTimeWasAttacked.isValid(time) ? this.lastTimeWasAttacked.player.getEntity(world) : null;
        } else {
            return null;
        }
    }

    public void incrementResource(SiegePersonalResource resource, int amount) {
        int newAmount = this.resources.addTo(resource, amount);
        if (newAmount > resource.max) {
            this.resources.replace(resource, resource.max);
        }
    }

    // Try decrement an amount, returning how much was actually decremented
    public int tryDecrementResource(SiegePersonalResource resource, int amount) {
        int newAmount = this.resources.addTo(resource, -amount) - amount; // addTo returns previous value
        if (newAmount < 0) {
            this.resources.replace(resource, 0);
            return amount + newAmount;
        } else {
            return amount;
        }
    }
}
