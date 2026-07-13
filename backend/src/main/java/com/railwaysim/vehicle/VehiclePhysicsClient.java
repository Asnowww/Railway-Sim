package com.railwaysim.vehicle;

import java.util.List;

/** @deprecated LOCAL mode only. EXTERNAL_HTTP mode uses 9300's step-fleet for physics computation. */
@Deprecated(forRemoval=true, since="2.0")
public interface VehiclePhysicsClient {

    List<VehiclePhysicsOutput> stepFleet(List<VehiclePhysicsInput> inputs);
}
