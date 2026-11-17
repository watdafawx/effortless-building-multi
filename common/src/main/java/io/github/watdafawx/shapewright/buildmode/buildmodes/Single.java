package io.github.watdafawx.shapewright.buildmode.buildmodes;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import io.github.watdafawx.shapewright.buildmode.BaseBuildMode;
import io.github.watdafawx.shapewright.utilities.BlockEntry;
import io.github.watdafawx.shapewright.utilities.BlockSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class Single extends BaseBuildMode {

	private BlockPos pos;

	@Override
	public void initialize() {
		super.initialize();
		pos = null;
	}

	@Override
	public boolean onClick(BlockSet blocks, BlockPos clickedPos, Player player) {
		pos = clickedPos;
		return true;
	}

	@Override
	public void findCoordinates(BlockSet blocks, Player player) {
		if (pos != null) {
			blocks.setStartPos(new BlockEntry(pos));
		}
	}

	@Override
	public List<BlockPos> getServerBlocks(Player player, BlockPos firstPos, BlockPos secondPos, @Nullable BlockPos thirdPos) {
		return List.of(firstPos);
	}
}
