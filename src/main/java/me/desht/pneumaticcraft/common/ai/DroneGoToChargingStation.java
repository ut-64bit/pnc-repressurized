/*
 * This file is part of pnc-repressurized.
 *
 *     pnc-repressurized is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     pnc-repressurized is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with pnc-repressurized.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.desht.pneumaticcraft.common.ai;

import me.desht.pneumaticcraft.api.PNCCapabilities;
import me.desht.pneumaticcraft.api.item.EnumUpgrade;
import me.desht.pneumaticcraft.common.config.ConfigHelper;
import me.desht.pneumaticcraft.common.entity.living.EntityDrone;
import me.desht.pneumaticcraft.common.tileentity.TileEntityChargingStation;
import me.desht.pneumaticcraft.common.tileentity.TileEntitySecurityStation;
import me.desht.pneumaticcraft.common.util.GlobalTileEntityCacheManager;
import me.desht.pneumaticcraft.lib.PneumaticValues;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

public class DroneGoToChargingStation extends Goal {
    private final EntityDrone drone;
    public boolean isExecuting;
    private TileEntityChargingStation curCharger;
    private int chargingTime;

    public DroneGoToChargingStation(EntityDrone drone) {
        this.drone = drone;
        setFlags(EnumSet.allOf(Flag.class)); // exclusive to all other AI tasks.
    }

    /**
     * Returns whether the EntityAIBase should begin execution.
     */
    @Override
    public boolean canUse() {
        List<TileEntityChargingStation> validChargingStations = new ArrayList<>();
        World level = drone.level;
        drone.getCapability(PNCCapabilities.AIR_HANDLER_CAPABILITY).ifPresent(h -> {
            if (h.getPressure() < PneumaticValues.DRONE_LOW_PRESSURE && h.getPressure() > 0.001f) {
                int maxDistSq = ConfigHelper.common().advanced.maxDroneChargingStationSearchRange.get()
                        * ConfigHelper.common().advanced.maxDroneChargingStationSearchRange.get();
                for (TileEntityChargingStation station : GlobalTileEntityCacheManager.getInstance().chargingStations) {
                    BlockPos chargingPos = station.getBlockPos();
                    if (station.getLevel() == level && level.isLoaded(chargingPos) && drone.distanceToSqr(Vector3d.atCenterOf(chargingPos)) <= maxDistSq) {
                        if (DroneClaimManager.getInstance(level).isClaimed(chargingPos)) {
                            drone.getDebugger().addEntry("pneumaticcraft.gui.progWidget.chargingStation.debug.claimed", chargingPos);
                        } else if (station.getPressure() <= PneumaticValues.DRONE_LOW_PRESSURE) {
                            drone.getDebugger().addEntry("pneumaticcraft.gui.progWidget.chargingStation.debug.notEnoughPressure", chargingPos);
                        } else if (station.getUpgrades(EnumUpgrade.DISPENSER) == 0) {
                            drone.getDebugger().addEntry("pneumaticcraft.gui.progWidget.chargingStation.debug.noDispenserUpgrades", chargingPos);
                        } else {
                            validChargingStations.add(station);
                        }
                    }
                }
            }
        });

        validChargingStations.sort(Comparator.comparingDouble(te -> te.getBlockPos().distSqr(drone.blockPosition())));

        for (TileEntityChargingStation station : validChargingStations) {
            BlockPos chargingPos = station.getBlockPos();
            if (TileEntitySecurityStation.isProtectedFromPlayer(drone.getFakePlayer(), chargingPos, false)) {
                drone.getDebugger().addEntry("pneumaticcraft.gui.progWidget.chargingStation.debug.protected", chargingPos);
            } else if (drone.getPathNavigator().moveToXYZ(chargingPos.getX() + 0.5, chargingPos.getY() + 1, chargingPos.getZ() + 0.5)
                    || drone.getPathNavigator().isGoingToTeleport()) {
                isExecuting = true;
                curCharger = station;
                DroneClaimManager.getInstance(level).claim(chargingPos);
                return true;
            } else {
                drone.getDebugger().addEntry("pneumaticcraft.gui.progWidget.chargingStation.debug.cantNavigate", chargingPos);
            }
        }
        isExecuting = false;
        return false;
    }

    /**
     * Returns whether an in-progress EntityAIBase should continue executing
     */
    @Override
    public boolean canContinueToUse() {
        if (curCharger.getUpgrades(EnumUpgrade.DISPENSER) == 0 || curCharger.isRemoved()) {
            isExecuting = false;
            return false;
        } else if (!drone.getPathNavigator().isGoingToTeleport() && (drone.getNavigation().getPath() == null || drone.getNavigation().getPath().isDone())) {
            isExecuting = drone.getCapability(PNCCapabilities.AIR_HANDLER_CAPABILITY)
                    .map(h -> h.getPressure() < 9.9F && curCharger.getPressure() > h.getPressure() + 0.1F)
                    .orElseThrow(RuntimeException::new);
            if (isExecuting) {
                chargingTime++;
                if (chargingTime > 20) {
                    drone.getPathNavigator().moveToXYZ(curCharger.getBlockPos().getX() + 0.5, curCharger.getBlockPos().getY() + 1.5, curCharger.getBlockPos().getZ() + 0.5);
                    if (drone.getNavigation().getPath() == null || drone.getNavigation().getPath().isDone()) {
                        drone.setStandby(true);
                    } else {
                        chargingTime = 0;
                    }
                }
                DroneClaimManager.getInstance(drone.level).claim(curCharger.getBlockPos());
            }
            return isExecuting;
        } else {
            chargingTime = 0;
            DroneClaimManager.getInstance(drone.level).claim(curCharger.getBlockPos());
            return drone.isAccelerating();
        }
    }
}
