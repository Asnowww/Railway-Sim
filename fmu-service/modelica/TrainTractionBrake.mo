within ;
package RailwaySimVehicle
  model TrainTractionBrake
    parameter Real emptyMassKg = 198000;
    parameter Real maxLoadMassKg = 72000;
    parameter Real maxTractionForceN = 240000;
    parameter Real maxServiceBrakeForceN = 220000;
    parameter Real maxEmergencyBrakeForceN = 300000;
    parameter Real maxTractionPowerW = 3200000;
    parameter Real tractionEfficiency = 0.88;
    parameter Real regenEfficiency = 0.35;
    parameter Real nominalRailVoltageV = 1500;
    parameter Real cutoffRailVoltageV = 900;
    parameter Real davisA = 1800;
    parameter Real davisB = 45;
    parameter Real davisC = 3.2;
    parameter Real g = 9.81;

    input Real loadRate(min = 0, max = 1);
    input Real tractionCmd(min = 0, max = 1);
    input Real brakeCmd(min = 0, max = 1);
    input Real emergencyBrakeCmd(min = 0, max = 1);
    input Real speedLimit;
    input Real maDistance;
    input Real gradient;
    input Real curveRadius;
    input Real railVoltage;
    input Real powerAvailable;
    input Real doorClosed(min = 0, max = 1);
    input Real adhesionCoeff(min = 0, max = 1);

    output Real newPosition;
    output Real newSpeed;
    output Real acceleration;
    output Real tractionForce;
    output Real brakeForce;
    output Real regenBrakeForce;
    output Real tractionPower;
    output Real railCurrent;
    output Real regenPower;
    output Real energyConsumed(start = 0);
    output Real energyRegenerated(start = 0);
    output Real faultCode;

    Real massKg;
    Real speed(start = 0, min = 0);
    Real position(start = 0);
    Real powerLimitFactor;
    Real speedGuard;
    Real resistanceForce;
    Real gradientForce;
    Real netForce;
  equation
    massKg = emptyMassKg + maxLoadMassKg * loadRate;
    powerLimitFactor = if railVoltage <= cutoffRailVoltageV or powerAvailable <= 0 then 0 else min(1, powerAvailable / maxTractionPowerW);
    speedGuard = if speed > speedLimit or maDistance <= 0 then 0 else 1;

    tractionForce = maxTractionForceN * tractionCmd * powerLimitFactor * adhesionCoeff * doorClosed * speedGuard;
    brakeForce = if emergencyBrakeCmd > 0.5 then maxEmergencyBrakeForceN else maxServiceBrakeForceN * brakeCmd;
    regenBrakeForce = if brakeForce > 0 and speed > 0 then 0.45 * brakeForce else 0;

    resistanceForce = davisA + davisB * speed + davisC * speed * speed;
    gradientForce = massKg * g * gradient;
    netForce = tractionForce - brakeForce - resistanceForce - gradientForce;
    acceleration = max(-1.3, min(1.0, netForce / massKg));

    der(speed) = acceleration;
    der(position) = speed;
    newSpeed = max(0, speed);
    newPosition = position;

    tractionPower = min(powerAvailable, max(0, tractionForce * max(speed, 0.1) / tractionEfficiency));
    railCurrent = if railVoltage > 1 then tractionPower / railVoltage else 0;
    regenPower = regenBrakeForce * speed * regenEfficiency;
    der(energyConsumed) = tractionPower / 3600000;
    der(energyRegenerated) = regenPower / 3600000;
    faultCode = if railVoltage <= cutoffRailVoltageV then 1 else 0;
  end TrainTractionBrake;
end RailwaySimVehicle;
