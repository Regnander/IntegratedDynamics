package org.cyclops.integrateddynamics.client.render.tileentity;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import org.cyclops.cyclopscore.client.render.tileentity.RenderTileEntityBakedModel;
import org.cyclops.integrateddynamics.core.block.IgnoredBlock;
import org.cyclops.integrateddynamics.core.part.IPartType;
import org.cyclops.integrateddynamics.core.tileentity.TileMultipartTicking;

import java.util.Map;

/**
 * Renderer for cable components.
 * @author rubensworks
 */
public class RenderCable extends RenderTileEntityBakedModel<TileMultipartTicking> {

    private IBlockState tempBlockState;

    protected void renderTileEntityAt(TileMultipartTicking tile, double x, double y, double z, float partialTick,
                                      int destroyStage) {
        for(Map.Entry<EnumFacing, IPartType<?, ?>> entry : tile.getParts().entrySet()) {
            tempBlockState = entry.getValue().getBlockState(tile, x, y, z, partialTick, destroyStage).withProperty(IgnoredBlock.FACING, entry.getKey());
            super.renderTileEntityAt(tile, x, y, z, partialTick, destroyStage);
        }
    }

    @Override
    protected IBlockState getBlockState(TileMultipartTicking tile, double x, double y, double z, float partialTick,
                                        int destroyStage) {
        return tempBlockState;
    }

}
