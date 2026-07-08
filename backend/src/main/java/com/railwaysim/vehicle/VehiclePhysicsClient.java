package com.railwaysim.vehicle;

import java.util.List;

public interface VehiclePhysicsClient {

    List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs);
}
