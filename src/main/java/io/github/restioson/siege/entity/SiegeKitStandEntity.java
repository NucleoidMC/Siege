package io.github.restioson.siege.entity;

import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.active.SiegeActive;
import io.github.restioson.siege.game.active.SiegePlayer;
import io.github.restioson.siege.game.map.SiegeFlag;
import io.github.restioson.siege.game.map.SiegeKitStandData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

public final class SiegeKitStandEntity extends ArmorStand {
    @Nullable
    public final SiegeFlag controllingFlag;
    @Nullable
    private final GameTeam team;
    private final SiegeKitStandData data;

    private final SiegeKit kit;
    private final SiegeActive game;

    public SiegeKitStandEntity(SiegeActive game, SiegeKitStandData stand) {
        super(EntityType.ARMOR_STAND, game.world);
        this.kit = stand.type();
        this.controllingFlag = stand.flag();
        this.team = this.controllingFlag != null ? this.controllingFlag.team : stand.team();
        this.game = game;
        this.setPose(Pose.CROUCHING);
        this.data = stand;

        this.absSnapTo(stand.pos().x, stand.pos().y, stand.pos().z, stand.yaw(), 0);

        this.setCustomName(this.kit.getName());
        this.setInvulnerable(true);
        this.setCustomNameVisible(true);
        this.setShowArms(true);
        this.kit.equipArmourStand(this);
    }

    public GameTeam getGameTeam() {
        return this.controllingFlag != null ? this.controllingFlag.team : this.team;
    }

    public void onControllingFlagCaptured() {
        // TODO HACK: viaversion does not work with just equipStack
        this.remove(RemovalReason.DISCARDED);
        this.game.world.addFreshEntity(new SiegeKitStandEntity(this.game, this.data));
    }

    @Override
    public InteractionResult interact(Player playerEntity, InteractionHand hand, Vec3 location) {
        var player = (ServerPlayer) playerEntity;
        SiegePlayer participant = this.game.participant(player);

        if (participant == null || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            return InteractionResult.FAIL;
        }

        if (participant.team != this.getGameTeam()) {
            player.sendSystemMessage(Component.translatable("game.siege.kit.not_your_stand").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        var cooldownMgr = player.getCooldowns();
        if (cooldownMgr.isOnCooldown(SiegeKit.KIT_SELECT_ITEM.getDefaultInstance())) {
            player.sendSystemMessage(Component.translatable("game.siege.kit.cooldown").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        this.kit.equipPlayer(player, participant, this.game.config, player.level().getGameTime());
        cooldownMgr.addCooldown(SiegeKit.KIT_SELECT_ITEM.getDefaultInstance(), SiegeKit.KIT_SWAP_COOLDOWN);

        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean isImmobile() {
        return true;
    }

    @Override
    public boolean isMarker() {
        return true;
    }
}
