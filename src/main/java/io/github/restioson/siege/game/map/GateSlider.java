package io.github.restioson.siege.game.map;

import xyz.nucleoid.map_templates.BlockBounds;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class GateSlider {
    private final BlockBounds bounds;
    private final int maxOffset;

    private final int height;
    private Slice[] slices;
    private Slice emptySlice;

    private int offset;

    public GateSlider(BlockBounds bounds, int maxOffset) {
        this.bounds = bounds;
        this.maxOffset = maxOffset;

        this.height = bounds.max().getY() - bounds.min().getY() + 1;
    }

    private Slice[] getSlices(ServerLevel world) {
        if (this.slices != null) {
            return this.slices;
        }

        BlockPos min = this.bounds.min();
        BlockPos max = this.bounds.max();

        int sizeX = max.getX() - min.getX() + 1;
        int sizeZ = max.getZ() - min.getZ() + 1;

        this.slices = new Slice[this.height];
        this.emptySlice = new Slice(sizeX, sizeZ);

        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        for (int y = 0; y < this.height; y++) {
            Slice slice = new Slice(sizeX, sizeZ);

            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    mutablePos.set(x + min.getX(), y + min.getY(), z + min.getZ());
                    BlockState state = world.getBlockState(mutablePos);

                    slice.set(x, z, state);
                }
            }

            this.slices[y] = slice;
        }

        return this.slices;
    }

    private Slice getSlice(ServerLevel world, int y) {
        Slice[] slices = this.getSlices(world);
        if (y < 0 || y >= slices.length) {
            return this.emptySlice;
        }
        return slices[y];
    }

    public void set(ServerLevel world, int offset) {
        offset = Mth.clamp(offset, 0, this.maxOffset);
        if (this.offset == offset) {
            return;
        }

        this.offset = offset;

        BlockPos min = this.bounds.min();

        for (int y = 0; y < this.height; y++) {
            Slice slice = this.getSlice(world, y - offset);
            slice.applyAt(world, min, y);
        }

        for (BlockPos pos : this.bounds) {
            var state = world.getBlockState(pos);
            if (state.getBlock() instanceof FenceBlock fence) {
                for (var entry : CrossCollisionBlock.PROPERTY_BY_DIRECTION.entrySet()) {
                    var dir = entry.getKey();
                    var prop = entry.getValue();
                    var neighbourPos = pos.relative(dir, 1);
                    var neighbourBlock = world.getBlockState(neighbourPos);

                    boolean neighbourSideConnectable = neighbourBlock.isFaceSturdy(world, neighbourPos, dir.getOpposite());
                    boolean connects = fence.connectsTo(neighbourBlock, neighbourSideConnectable, dir.getOpposite());

                    state = state.setValue(prop, connects);
                    world.setBlockAndUpdate(pos, state);
                }
            }
        }
    }

    public int getMaxOffset() {
        return this.maxOffset;
    }

    public void setOpen(ServerLevel world) {
        this.set(world, this.getMaxOffset());
    }

    public void setClosed(ServerLevel world) {
        this.set(world, 0);
    }

    static class Slice {
        final BlockState[] states;
        final int sizeX;
        final int sizeZ;

        Slice(int sizeX, int sizeZ) {
            this.states = new BlockState[sizeX * sizeZ];
            Arrays.fill(this.states, Blocks.AIR.defaultBlockState());

            this.sizeX = sizeX;
            this.sizeZ = sizeZ;
        }

        void set(int x, int z, BlockState state) {
            this.states[this.index(x, z)] = state;
        }

        void applyAt(ServerLevel world, BlockPos min, int y) {
            BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

            for (int z = 0; z < this.sizeZ; z++) {
                for (int x = 0; x < this.sizeX; x++) {
                    mutablePos.setWithOffset(min, x, y, z);
                    BlockState state = this.get(x, z);
                    world.setBlockAndUpdate(mutablePos, state);
                }
            }
        }

        BlockState get(int x, int z) {
            return this.states[this.index(x, z)];
        }

        int index(int x, int z) {
            return x + z * this.sizeX;
        }
    }
}
