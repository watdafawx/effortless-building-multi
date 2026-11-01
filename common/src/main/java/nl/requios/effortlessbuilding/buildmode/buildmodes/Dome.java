package nl.requios.effortlessbuilding.buildmode.buildmodes;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import nl.requios.effortlessbuilding.buildmode.ModeOptions;
import nl.requios.effortlessbuilding.buildmode.ThreeClicksBuildMode;

import java.util.ArrayList;
import java.util.List;

public class Dome extends ThreeClicksBuildMode {

	public static List<BlockPos> getDomeBlocks(Player player,
											   int x1, int y1, int z1,
											   int x2, int y2, int z2,
											   int x3, int y3, int z3) {
		List<BlockPos> list = new ArrayList<>();

		// Center of dome base
		float centerX, centerY, centerZ;
		if (ModeOptions.getCircleStart() == ModeOptions.ActionEnum.CIRCLE_START_CORNER) {
			centerX = x1 + (x2 - x1) / 2f;
			centerZ = z1 + (z2 - z1) / 2f;
		} else {
			centerX = x1;
			centerZ = z1;
			x1 = (int) (centerX - (x2 - centerX));
			z1 = (int) (centerZ - (z2 - centerZ));
		}
		centerY = y1;

		// Radii
		float radiusX = Mth.abs(x2 - centerX);
		float radiusZ = Mth.abs(z2 - centerZ);
		float radiusY = Mth.abs(y3 - y1);

		// Bounding box
		int minX = (int) Math.floor(centerX - radiusX);
		int maxX = (int) Math.ceil(centerX + radiusX);
		int minY = (int) Math.floor(centerY);
		int maxY = (int) Math.ceil(centerY + radiusY);
		int minZ = (int) Math.floor(centerZ - radiusZ);
		int maxZ = (int) Math.ceil(centerZ + radiusZ);

		boolean filled = ModeOptions.getFill() == ModeOptions.ActionEnum.FULL;

		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int y = minY; y <= maxY; y++) {
					// Normalized distance from center (0 to 1, 1 = on surface)
					float dx = (x - centerX) / radiusX;
					float dz = (z - centerZ) / radiusZ;
					float dy = (y - centerY) / radiusY;

					float distSq = dx * dx + dy * dy + dz * dz;

					if (filled) {
						// Inside the dome
						if (distSq < 1.0f) {
							list.add(new BlockPos(x, y, z));
						}
					} else {
						// Shell: near the surface
						if (distSq < 1.0f && distSq > 0.36f) {
							list.add(new BlockPos(x, y, z));
						}
					}
				}
			}
		}

		return list;
	}

	@Override
	protected BlockPos findSecondPos(Player player, BlockPos firstPos, boolean skipRaytrace) {
		return Floor.findFloor(player, firstPos, skipRaytrace);
	}

	@Override
	protected BlockPos findThirdPos(Player player, BlockPos firstPos, BlockPos secondPos, boolean skipRaytrace) {
		return findHeight(player, secondPos, skipRaytrace);
	}

	@Override
	protected List<BlockPos> getIntermediateBlocks(Player player, int x1, int y1, int z1, int x2, int y2, int z2) {
		return Circle.getCircleBlocks(player, x1, y1, z1, x2, y2, z2);
	}

	@Override
	protected List<BlockPos> getFinalBlocks(Player player, int x1, int y1, int z1, int x2, int y2, int z2, int x3, int y3, int z3) {
		return getDomeBlocks(player, x1, y1, z1, x2, y2, z2, x3, y3, z3);
	}
}
