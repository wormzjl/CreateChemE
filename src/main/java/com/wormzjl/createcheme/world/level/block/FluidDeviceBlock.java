package com.wormzjl.createcheme.world.level.block;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.wormzjl.createcheme.runtime.fluid.*;
import com.wormzjl.createcheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.createcheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Gameplay adapter only: authoritative inventory is never stored in this block or its block entity. */
public final class FluidDeviceBlock extends BaseEntityBlock {
    public static final MapCodec<FluidDeviceBlock> CODEC=RecordCodecBuilder.mapCodec(instance->instance.group(
            Codec.STRING.xmap(TopologyCompiler.Kind::valueOf,TopologyCompiler.Kind::name).fieldOf("device_kind").forGetter(block->block.kind),propertiesCodec()).apply(instance,FluidDeviceBlock::new));
    public static final DirectionProperty FACING=BlockStateProperties.FACING;
    private static final java.util.Map<Direction,BooleanProperty> CONNECTIONS=java.util.Map.of(
            Direction.DOWN,BlockStateProperties.DOWN,Direction.UP,BlockStateProperties.UP,Direction.NORTH,BlockStateProperties.NORTH,
            Direction.SOUTH,BlockStateProperties.SOUTH,Direction.WEST,BlockStateProperties.WEST,Direction.EAST,BlockStateProperties.EAST);
    private final TopologyCompiler.Kind kind;
    public FluidDeviceBlock(TopologyCompiler.Kind kind,BlockBehaviour.Properties properties) {
        super(properties);this.kind=kind;var state=stateDefinition.any().setValue(FACING,Direction.NORTH);for(var property:CONNECTIONS.values())state=state.setValue(property,false);registerDefaultState(state);
    }
    public TopologyCompiler.Kind kind(){return kind;}
    @Override public MapCodec<FluidDeviceBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);for(var property:CONNECTIONS.values())builder.add(property);}
    @Override public RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,BlockGetter level,BlockPos position,net.minecraft.world.phys.shapes.CollisionContext context) {
        if(kind!=TopologyCompiler.Kind.PIPE)return super.getShape(state,level,position,context);
        var shape=Block.box(5,5,5,11,11,11);
        for(var direction:Direction.values())if(state.getValue(CONNECTIONS.get(direction))) {
            var arm=switch(direction){case DOWN->Block.box(6,0,6,10,6,10);case UP->Block.box(6,10,6,10,16,10);case NORTH->Block.box(6,6,0,10,10,6);case SOUTH->Block.box(6,6,10,10,10,16);case WEST->Block.box(0,6,6,6,10,10);case EAST->Block.box(10,6,6,16,10,10);};
            shape=net.minecraft.world.phys.shapes.Shapes.or(shape,arm);
        }
        return shape;
    }
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos position,BlockState state){return new FluidDeviceBlockEntity(position,state);}
    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        var state=defaultBlockState().setValue(FACING,context.getNearestLookingDirection().getOpposite());
        for(var direction:Direction.values())state=state.setValue(CONNECTIONS.get(direction),connects(state,context.getLevel().getBlockState(context.getClickedPos().relative(direction)),direction));return state;
    }
    private static boolean boundary(TopologyCompiler.Kind kind){return kind==TopologyCompiler.Kind.RESERVOIR||kind==TopologyCompiler.Kind.GENERATOR||kind==TopologyCompiler.Kind.VOID;}
    private static boolean face(BlockState state,Direction direction) {
        var block=(FluidDeviceBlock)state.getBlock();return block.kind!=TopologyCompiler.Kind.PUMP&&block.kind!=TopologyCompiler.Kind.VALVE||state.getValue(FACING).getAxis()==direction.getAxis();
    }
    private static boolean connects(BlockState state,BlockState neighbor,Direction direction) {
        return neighbor.getBlock() instanceof FluidDeviceBlock other&&!(boundary(((FluidDeviceBlock)state.getBlock()).kind)&&boundary(other.kind))&&face(state,direction)&&face(neighbor,direction.getOpposite());
    }
    @Override protected BlockState updateShape(BlockState state,Direction direction,BlockState neighbor,LevelAccessor level,BlockPos position,BlockPos neighborPosition) {
        return super.updateShape(state.setValue(CONNECTIONS.get(direction),connects(state,neighbor,direction)),direction,neighbor,level,position,neighborPosition);
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos position,BlockState previous,boolean moved) {
        super.onPlace(state,level,position,previous,moved);if(!(level instanceof ServerLevel serverLevel))return;
        FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->{
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),position.getX(),position.getY(),position.getZ());
            if(previous.getBlock()!=this) {
                var record=world.place(p,kind,PhysicalFluidTopology.Direction.valueOf(state.getValue(FACING).name()));
                if(level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.bindIdentity(record.device().id());
            } else if(previous.getValue(FACING)!=state.getValue(FACING)) {
                world.at(p).ifPresent(old->{var d=old.device();world.edit(d.id(),old.revision(),new PhysicalFluidTopology.Device(d.id(),d.position(),d.kind(),PhysicalFluidTopology.Direction.valueOf(state.getValue(FACING).name()),d.geometry(),d.control()),old.spec());});
            }
        });
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos position,BlockState replacement,boolean moved) {
        if(state.getBlock()!=replacement.getBlock()&&level instanceof ServerLevel serverLevel)FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(world->{
            var p=new PhysicalFluidTopology.Position(level.dimension().location().toString(),position.getX(),position.getY(),position.getZ());world.at(p).ifPresent(r->world.remove(r.device().id()));
        });
        super.onRemove(state,level,position,replacement,moved);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos position,Player player,BlockHitResult hit) {
        if(player instanceof ServerPlayer serverPlayer&&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.open(serverPlayer,false);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos position,Player player,InteractionHand hand,BlockHitResult hit) {
        if(stack.is(com.wormzjl.createcheme.registry.ModItems.FLUID_DEBUGGER.get())) {
            if(player instanceof ServerPlayer serverPlayer&&level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity)entity.open(serverPlayer,true);
            return ItemInteractionResult.sidedSuccess(level.isClientSide());
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
}
