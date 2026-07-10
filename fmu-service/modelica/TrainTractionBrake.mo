within ;
package RailwaySimVehicle
  model TrainTractionBrake
    // YAML-backed calibration parameters. 9000 writes them during FMI initialization.
    parameter Real maxMechanicalTractionPowerWatts(min = 1, unit = "W") = 3200000;
    parameter Real maxTractionForceNewtons(min = 1, unit = "N") = 240000;
    parameter Real tractionEfficiency(min = 0.000001, max = 1, unit = "1") = 0.88;
    parameter Real maxServiceBrakeForceNewtons(min = 1, unit = "N") = 220000;
    parameter Real maxEmergencyBrakeForceNewtons(min = 1, unit = "N") = 300000;
    parameter Real regenBrakeRatio(min = 0, max = 1, unit = "1") = 0.45;
    parameter Real regenEfficiency(min = 0.000001, max = 1, unit = "1") = 0.35;
    parameter Real davisA(min = 0, unit = "N") = 1800;
    parameter Real davisB(min = 0, unit = "N.s/m") = 45;
    parameter Real davisC(min = 0, unit = "N.s2/m2") = 3.2;
    parameter Real minimumVoltageVolts(min = 0, unit = "V") = 1000;
    parameter Real cutoffVoltageVolts(min = 0, unit = "V") = 900;

    // External state is written only for INIT, RESET, and RESYNC.
    parameter Real initialPositionMeters(unit = "m") = 0;
    parameter Real initialSpeedMetersPerSecond(min = 0, unit = "m/s") = 0;
    parameter Real initialEnergyConsumedKwh(min = 0, unit = "kWh") = 0;
    parameter Real initialEnergyRegeneratedKwh(min = 0, unit = "kWh") = 0;

    // Per-step physical inputs. MA, station, signal, and speed-limit decisions stay in 9300.
    input Real trainMassKg(start = 198000, min = 1, unit = "kg");
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

    // Physical state and outputs.
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
    constant Real speedFloorMetersPerSecond = 0.5;
    Real mechanicalPowerFromGridWatts(unit = "W");
    Real mechanicalPowerLimitWatts(unit = "W");
    Real commandTractionForceNewtons(unit = "N");
    Real powerLimitedTractionForceNewtons(unit = "N");
    Real adhesionLimitedTractionForceNewtons(unit = "N");
    Real resistanceForceNewtons(unit = "N");
    Real gradientForceNewtons(unit = "N");
    Real netForceNewtons(unit = "N");
    Real regenCandidateForceNewtons(unit = "N");
    Real regenCandidateMechanicalPowerWatts(unit = "W");
    Real regenGridMechanicalLimitWatts(unit = "W");
    Real regenMechanicalPowerLimitWatts(unit = "W");
    Boolean tractionEnabled;

  equation
    tractionEnabled = doorClosed
      and not emergencyBrakeCommand
      and currentCollectionAvailable
      and lineVoltageVolts > cutoffVoltageVolts
      and powerAvailableWatts > 0;

    mechanicalPowerFromGridWatts = max(0, powerAvailableWatts) * tractionEfficiency;
    mechanicalPowerLimitWatts = min(
      maxMechanicalTractionPowerWatts,
      mechanicalPowerFromGridWatts);
    commandTractionForceNewtons = maxTractionForceNewtons
      * max(0, min(1, tractionCommand));
    powerLimitedTractionForceNewtons = mechanicalPowerLimitWatts
      / max(speedMetersPerSecond, speedFloorMetersPerSecond);
    adhesionLimitedTractionForceNewtons = max(0.2, min(1, adhesionCoefficient))
      * max(trainMassKg, 1)
      * gravity;
    tractionForceNewtons = if tractionEnabled then min(
      commandTractionForceNewtons,
      min(powerLimitedTractionForceNewtons, adhesionLimitedTractionForceNewtons))
      else 0;

    brakeForceNewtons = if emergencyBrakeCommand
      then maxEmergencyBrakeForceNewtons
      else maxServiceBrakeForceNewtons * max(0, min(1, brakeCommand));

    regenCandidateForceNewtons = if brakeForceNewtons > 0 and speedMetersPerSecond > 0
      then regenBrakeRatio * brakeForceNewtons
      else 0;
    regenCandidateMechanicalPowerWatts = regenCandidateForceNewtons
      * speedMetersPerSecond;
    regenGridMechanicalLimitWatts = max(0, regenPowerAvailableWatts)
      / regenEfficiency;
    regenMechanicalPowerLimitWatts = min(
      regenCandidateMechanicalPowerWatts,
      min(maxMechanicalTractionPowerWatts, regenGridMechanicalLimitWatts));
    regenBrakeForceNewtons = if regenMechanicalPowerLimitWatts > 0
      then min(
        regenCandidateForceNewtons,
        regenMechanicalPowerLimitWatts
          / max(speedMetersPerSecond, speedFloorMetersPerSecond))
      else 0;
    mechanicalRegenPowerWatts = regenBrakeForceNewtons * speedMetersPerSecond;
    regenPowerWatts = mechanicalRegenPowerWatts * regenEfficiency;

    resistanceForceNewtons = davisA
      + davisB * speedMetersPerSecond
      + davisC * speedMetersPerSecond * speedMetersPerSecond;
    gradientForceNewtons = max(trainMassKg, 1) * gravity * gradient;
    // brakeForceNewtons is the total braking force; regen is its subset and is not subtracted twice.
    netForceNewtons = tractionForceNewtons
      - brakeForceNewtons
      - resistanceForceNewtons
      - gradientForceNewtons;
    accelerationMetersPerSecondSquared = max(
      -1.3,
      min(1.0, netForceNewtons / max(trainMassKg, 1)));

    der(speedMetersPerSecond) = if speedMetersPerSecond <= 0
      and accelerationMetersPerSecondSquared < 0
      then 0
      else accelerationMetersPerSecondSquared;
    der(positionMeters) = max(0, speedMetersPerSecond);

    mechanicalTractionPowerWatts = tractionForceNewtons * speedMetersPerSecond;
    tractionPowerWatts = if mechanicalTractionPowerWatts > 0
      then min(
        max(0, powerAvailableWatts),
        mechanicalTractionPowerWatts / tractionEfficiency)
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
