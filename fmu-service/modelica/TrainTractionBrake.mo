within ;
package RailwaySimVehicle
  function interpolateCurve
    input Real x;
    input Real xGrid[:];
    input Real yGrid[size(xGrid, 1)];
    output Real y;
  protected
    Integer count = size(xGrid, 1);
  algorithm
    if x <= xGrid[1] then
      y := yGrid[1];
    elseif x >= xGrid[count] then
      // The source data intentionally repeats the 4160.1 rpm terminal point.
      y := yGrid[count];
    else
      y := yGrid[1];
      for index in 1:count - 1 loop
        if x >= xGrid[index] and x < xGrid[index + 1] then
          y := yGrid[index] + (yGrid[index + 1] - yGrid[index])
            * (x - xGrid[index]) / (xGrid[index + 1] - xGrid[index]);
        end if;
      end for;
    end if;
  end interpolateCurve;

  model TrainTractionBrake
    parameter Integer motorCount(min = 1) = 16;
    parameter Integer axleCount(min = 1) = 24;
    parameter Integer vehicleCount(min = 1) = 6;
    parameter Real gearRatio(min = 0.000001, unit = "1") = 6.5;
    parameter Real wheelRadiusMeters(min = 0.000001, unit = "m") = 0.46;
    parameter Real tractionTotalEfficiency(min = 0.000001, max = 1, unit = "1") = 0.882;
    parameter Real regenTotalEfficiency(min = 0.000001, max = 1, unit = "1") = 0.802;
    parameter Real speedRpm[52](each min = 0, each unit = "rev/min") = {
      0,83.2,166.4,249.6,332.8,416,499.2,582.4,665.6,748.8,832,915.2,
      998.4,1081.6,1164.8,1248,1331.2,1414.4,1497.6,1580.8,1664,1747.2,
      1830.4,1913.6,1996.9,2080.1,2163.3,2246.5,2329.7,2412.9,2496.1,
      2579.3,2662.5,2745.7,2828.9,2912.1,2995.3,3078.5,3161.7,3244.9,
      3328.1,3411.3,3494.5,3577.7,3660.9,3744.1,3827.3,3910.5,3993.7,
      4076.9,4160.1,4160.1};
    parameter Real tractionTorqueNmPerMotor[52](each min = 0, each unit = "N.m") = {
      1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,
      1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,
      1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,
      1042.9,1042.9,1042.9,1036.8,971,911.2,856.9,807.2,761.7,720,681.6,
      646.2,613.5,583.2,555.1,529,504.7,482,460.8,441,422.4,405,388.6,
      373.2,373.2};
    parameter Real brakeTorqueNmPerMotor[52](each min = 0, each unit = "N.m") = {
      0,0,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7};
    parameter Real davisMassCoefficient(min = 0, unit = "N") = 6.4;
    parameter Real davisAxleConstant(min = 0, unit = "N") = 130;
    parameter Real davisSpeedMassCoefficient(min = 0, unit = "N.h/km") = 0.14;
    parameter Real davisAeroBase(min = 0, unit = "N.h2/km2/m2") = 0.046;
    parameter Real davisAeroVehicleCoefficient(min = 0, unit = "N.h2/km2/m2") = 0.0065;
    parameter Real frontalAreaSquareMeters(min = 0, unit = "m2") = 10.6;
    parameter Real serviceDecelerationMps2(min = 0, unit = "m/s2") = 1.0;
    parameter Real emergencyDecelerationMps2(min = 0, unit = "m/s2") = 1.3;
    parameter Real minimumVoltageVolts(min = 0, unit = "V") = 1000;
    parameter Real cutoffVoltageVolts(min = 0, unit = "V") = 900;

    parameter Real initialPositionMeters(unit = "m") = 0;
    parameter Real initialSpeedMetersPerSecond(min = 0, unit = "m/s") = 0;
    parameter Real initialEnergyConsumedKwh(min = 0, unit = "kWh") = 0;
    parameter Real initialEnergyRegeneratedKwh(min = 0, unit = "kWh") = 0;

    input Real trainMassKg(start = 225000, min = 1, unit = "kg");
    input Real tractionCommand(start = 0, min = 0, max = 1, unit = "1");
    input Real brakeCommand(start = 0, min = 0, max = 1, unit = "1");
    input Boolean emergencyBrakeCommand(start = false);
    input Boolean doorClosed(start = true);
    input Real gradient(start = 0, unit = "1");
    input Real lineVoltageVolts(start = 1500, min = 0, unit = "V");
    input Real powerAvailableWatts(start = 0, min = 0, unit = "W");
    input Real regenPowerAvailableWatts(start = 0, min = 0, unit = "W");
    input Boolean currentCollectionAvailable(start = true);
    input Real adhesionCoefficient(start = 0.9, min = 0.2, max = 1, unit = "1");

    output Real positionMeters(start = initialPositionMeters, fixed = true, unit = "m");
    output Real speedMetersPerSecond(
      start = initialSpeedMetersPerSecond,
      fixed = true,
      min = 0,
      unit = "m/s");
    output Real accelerationMetersPerSecondSquared(unit = "m/s2");
    output Real tractionForceNewtons(min = 0, unit = "N");
    output Real brakeForceNewtons(min = 0, unit = "N");
    output Real regenBrakeForceNewtons(min = 0, unit = "N");
    output Real motorSpeedRpm(min = 0, unit = "rev/min");
    output Real interpolatedTractionTorqueNmPerMotor(min = 0, unit = "N.m");
    output Real interpolatedBrakeTorqueNmPerMotor(min = 0, unit = "N.m");
    output Real airBrakeForceNewtons(min = 0, unit = "N");
    output Real mechanicalTractionPowerWatts(min = 0, unit = "W");
    output Real tractionPowerWatts(min = 0, unit = "W");
    output Real railCurrentAmps(min = 0, unit = "A");
    output Real mechanicalRegenPowerWatts(min = 0, unit = "W");
    output Real regenPowerWatts(min = 0, unit = "W");
    output Real energyConsumedKwh(
      start = initialEnergyConsumedKwh,
      fixed = true,
      min = 0,
      unit = "kWh");
    output Real energyRegeneratedKwh(
      start = initialEnergyRegeneratedKwh,
      fixed = true,
      min = 0,
      unit = "kWh");
    output Integer faultCodeValue(start = 0, fixed = true);

  protected
    constant Real gravity = 9.81;
    constant Real pi = 3.141592653589793;
    constant Real speedFloorMetersPerSecond = 0.5;
    Real curveTractionForceNewtons(unit = "N");
    Real curveElectricBrakeForceNewtons(unit = "N");
    Real commandTractionForceNewtons(unit = "N");
    Real supplyLimitedTractionForceNewtons(unit = "N");
    Real adhesionLimitedTractionForceNewtons(unit = "N");
    Real resistanceForceNewtons(unit = "N");
    Real gradientForceNewtons(unit = "N");
    Real netForceNewtons(unit = "N");
    Real regenCandidateForceNewtons(unit = "N");
    Real regenCandidateMechanicalPowerWatts(unit = "W");
    Real regenGridMechanicalLimitWatts(unit = "W");
    Real massTonnes(unit = "kg");
    Real speedKilometersPerHour(unit = "km/h");
    Boolean tractionEnabled;

  equation
    motorSpeedRpm = max(0, speedMetersPerSecond) / wheelRadiusMeters
      * gearRatio * 60 / (2 * pi);
    interpolatedTractionTorqueNmPerMotor = interpolateCurve(
      motorSpeedRpm, speedRpm, tractionTorqueNmPerMotor);
    interpolatedBrakeTorqueNmPerMotor = interpolateCurve(
      motorSpeedRpm, speedRpm, brakeTorqueNmPerMotor);
    curveTractionForceNewtons = interpolatedTractionTorqueNmPerMotor
      * motorCount * gearRatio / wheelRadiusMeters;
    curveElectricBrakeForceNewtons = interpolatedBrakeTorqueNmPerMotor
      * motorCount * gearRatio / wheelRadiusMeters;

    tractionEnabled = doorClosed
      and not emergencyBrakeCommand
      and currentCollectionAvailable
      and lineVoltageVolts > cutoffVoltageVolts
      and powerAvailableWatts > 0;
    commandTractionForceNewtons = max(0, min(1, tractionCommand))
      * curveTractionForceNewtons;
    supplyLimitedTractionForceNewtons = max(0, powerAvailableWatts)
      * tractionTotalEfficiency / max(speedMetersPerSecond, speedFloorMetersPerSecond);
    adhesionLimitedTractionForceNewtons = max(0.2, min(1, adhesionCoefficient))
      * max(trainMassKg, 1) * gravity;
    tractionForceNewtons = if tractionEnabled then min(
      commandTractionForceNewtons,
      min(supplyLimitedTractionForceNewtons, adhesionLimitedTractionForceNewtons))
      else 0;

    brakeForceNewtons = if emergencyBrakeCommand
      then max(trainMassKg, 1) * emergencyDecelerationMps2
      else max(0, min(1, brakeCommand)) * max(trainMassKg, 1) * serviceDecelerationMps2;
    regenCandidateForceNewtons = if not emergencyBrakeCommand
      and brakeForceNewtons > 0 and speedMetersPerSecond > 0
      then min(brakeForceNewtons, curveElectricBrakeForceNewtons)
      else 0;
    regenCandidateMechanicalPowerWatts = regenCandidateForceNewtons
      * speedMetersPerSecond;
    regenGridMechanicalLimitWatts = max(0, regenPowerAvailableWatts)
      / regenTotalEfficiency;
    mechanicalRegenPowerWatts = min(
      regenCandidateMechanicalPowerWatts,
      regenGridMechanicalLimitWatts);
    regenBrakeForceNewtons = if mechanicalRegenPowerWatts > 0
      then min(
        regenCandidateForceNewtons,
        mechanicalRegenPowerWatts / max(speedMetersPerSecond, speedFloorMetersPerSecond))
      else 0;
    regenPowerWatts = regenBrakeForceNewtons * speedMetersPerSecond
      * regenTotalEfficiency;
    airBrakeForceNewtons = max(0, brakeForceNewtons - regenBrakeForceNewtons);

    massTonnes = max(trainMassKg, 1) / 1000;
    speedKilometersPerHour = max(0, speedMetersPerSecond) * 3.6;
    resistanceForceNewtons = davisMassCoefficient * massTonnes
      + davisAxleConstant * axleCount
      + davisSpeedMassCoefficient * massTonnes * speedKilometersPerHour
      + (davisAeroBase + davisAeroVehicleCoefficient * (vehicleCount - 1))
        * frontalAreaSquareMeters * speedKilometersPerHour * speedKilometersPerHour;
    gradientForceNewtons = max(trainMassKg, 1) * gravity * gradient;
    netForceNewtons = tractionForceNewtons
      - brakeForceNewtons
      - resistanceForceNewtons
      - gradientForceNewtons;
    accelerationMetersPerSecondSquared = max(
      -emergencyDecelerationMps2,
      min(serviceDecelerationMps2, netForceNewtons / max(trainMassKg, 1)));

    der(speedMetersPerSecond) = if speedMetersPerSecond <= 0
      and accelerationMetersPerSecondSquared < 0
      then 0
      else accelerationMetersPerSecondSquared;
    der(positionMeters) = max(0, speedMetersPerSecond);

    mechanicalTractionPowerWatts = tractionForceNewtons * speedMetersPerSecond;
    tractionPowerWatts = if mechanicalTractionPowerWatts > 0
      then min(
        max(0, powerAvailableWatts),
        mechanicalTractionPowerWatts / tractionTotalEfficiency)
      else 0;
    railCurrentAmps = if lineVoltageVolts > 1
      then tractionPowerWatts / lineVoltageVolts
      else 0;
    der(energyConsumedKwh) = tractionPowerWatts / 3600000;
    der(energyRegeneratedKwh) = regenPowerWatts / 3600000;

    faultCodeValue = if not doorClosed then 10
      elseif emergencyBrakeCommand then 20
      elseif not currentCollectionAvailable
        or lineVoltageVolts <= cutoffVoltageVolts
        or powerAvailableWatts <= 0 then 30
      elseif lineVoltageVolts < minimumVoltageVolts then 31
      else 0;
  end TrainTractionBrake;
end RailwaySimVehicle;
