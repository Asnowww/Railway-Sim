package com.railwaysim.vehicle.runtime;

/**
 * 标识当前是否由外部车辆运行时负责把列车取电负荷写入供电仿真。
 */
@FunctionalInterface
public interface VehiclePowerLoadForwardingOwner {

    boolean ownsPowerLoadForwarding();
}
