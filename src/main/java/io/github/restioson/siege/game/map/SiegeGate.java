package io.github.restioson.siege.game.map;

import io.github.restioson.siege.game.active.SiegeActive;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

public class SiegeGate {
    public final String id;
    public final SiegeFlag flag;
    public final BlockBounds gateOpen;
    public final BlockBounds portcullis;
    public final int retractHeight;

    public final GateSlider slider;
    public boolean bashedOpen = false;
    public int health;
    public int repairedHealthThreshold;
    public int maxHealth;
    public int openSlide;
    public long timeOfLastBash;
    public final String name;
    private final boolean pluralName;

    @Nullable
    public BlockBounds brace;

    public SiegeGate(String id, SiegeFlag flag, BlockBounds gateOpen, BlockBounds portcullis,
                     @Nullable BlockBounds brace, int retractHeight, int repairedHealthThreshold, int maxHealth,
                     String name, boolean pluralName) {
        this.id = id;
        this.flag = flag;
        this.gateOpen = gateOpen;
        this.portcullis = portcullis;
        this.retractHeight = retractHeight;

        this.openSlide = 0;
        this.health = repairedHealthThreshold;
        this.repairedHealthThreshold = repairedHealthThreshold;
        this.maxHealth = maxHealth;

        this.slider = new GateSlider(portcullis, retractHeight);

        this.name = name;
        this.pluralName = pluralName;

        this.brace = brace;
    }

    public String pastToBe() {
        if (this.pluralName) {
            return "have";
        } else {
            return "has";
        }
    }

    public void broadcastHealth(ServerPlayer initiator, SiegeActive active, ServerLevel world) {
        String msg = this.bashedOpen ?
                String.format("%s more blocks to repair gate", this.blocksToRepair()) :
                String.format("Gate health: %s/%s", this.health, this.maxHealth);

        Component text = Component.literal(msg).withStyle(ChatFormatting.DARK_GREEN);
        initiator.sendSystemMessage(text, true);
        for (PlayerRef ref : active.participants.keySet()) {
            ref.ifOnline(world, p -> {
                if (this.gateOpen.contains(p.blockPosition()) && p != initiator) {
                    p.sendSystemMessage(text, true);
                }
            });
        }
    }

    public boolean tickOpen(ServerLevel world) {
        if (this.openSlide >= this.slider.getMaxOffset()) {
            return false;
        }
        this.slider.set(world, ++this.openSlide);
        return true;
    }

    public boolean tickClose(ServerLevel world) {
        if (this.openSlide <= 0) {
            return false;
        }
        this.slider.set(world, --this.openSlide);
        return true;
    }

    public float repairFraction() {
        return (float) this.health / this.repairedHealthThreshold;
    }

    public int blocksToRepair() {
        return this.repairedHealthThreshold - this.health;
    }

    public boolean underAttack(long time) {
        return time - this.timeOfLastBash < 5 * 20;
    }
}
