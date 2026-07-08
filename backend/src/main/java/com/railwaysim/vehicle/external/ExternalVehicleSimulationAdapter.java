package com.railwaysim.vehicle.external;

import com.railwaysim.vehicle.VehiclePhysicsInput;
import com.railwaysim.vehicle.VehiclePhysicsOutput;
import java.util.List;

public interface ExternalVehicleSimulationAdapter {

    List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs);

    ExternalSimulatorHealth health();
}
