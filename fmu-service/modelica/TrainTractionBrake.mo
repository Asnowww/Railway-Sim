within ;
package RailwaySimVehicle
  // ============================================================
  // 一维线性插值函数
  // 根据给定的离散曲线点 (xGrid, yGrid)，对输入 x 进行线性插值
  // 若 x 超出网格范围则取端点值（平直外推）
  // ============================================================
  function interpolateCurve
    input Real x;              // 待插值的自变量
    input Real xGrid[:];        // 自变量网格点（单调递增）
    input Real yGrid[size(xGrid, 1)];  // 对应的因变量网格点
    output Real y;              // 插值结果
  protected
    Integer count = size(xGrid, 1);  // 网格点数
  algorithm
    if x <= xGrid[1] then
      y := yGrid[1];                // 低于下限，取左端点
    elseif x >= xGrid[count] then
      // 注意：原始数据在 4160.1 rpm 终点处故意重复以保持曲线形状
      y := yGrid[count];            // 超过上限，取右端点
    else
      y := yGrid[1];                // 默认值（会被后续循环覆盖）
      for index in 1:count - 1 loop
        if x >= xGrid[index] and x < xGrid[index + 1] then
          // 线性插值公式: y = y1 + (y2 - y1) * (x - x1) / (x2 - x1)
          y := yGrid[index] + (yGrid[index + 1] - yGrid[index])
            * (x - xGrid[index]) / (xGrid[index + 1] - xGrid[index]);
        end if;
      end for;
    end if;
  end interpolateCurve;

  // ============================================================
  // 列车牵引与制动模型
  // 模拟列车的牵引力、电制动力（含再生制动）、空气制动力、
  // 阻力（Davis 公式）及坡度力，计算运动状态与能耗。
  // ============================================================
  model TrainTractionBrake
    // ---------- 结构参数 ----------
    parameter Integer motorCount(min = 1) = 16;          // 牵引电机数量
    parameter Integer axleCount(min = 1) = 24;           // 车轴数量
    parameter Integer vehicleCount(min = 1) = 6;         // 编组车辆数
    parameter Real gearRatio(min = 0.000001, unit = "1") = 6.5;  // 齿轮传动比
    parameter Real wheelRadiusMeters(min = 0.000001, unit = "m") = 0.46;  // 车轮半径 [m]
    // ---------- 效率参数 ----------
    parameter Real tractionTotalEfficiency(min = 0.000001, max = 1, unit = "1") = 0.882;  // 牵引总效率
    parameter Real regenTotalEfficiency(min = 0.000001, max = 1, unit = "1") = 0.802;  // 再生制动总效率
    // ---------- 牵引/制动特性曲线（52 个采样点 vs 转速） ----------
    // 电机转速网格 [rev/min]，终值 4160.1 重复一次以方便查表
    parameter Real speedRpm[52](each min = 0, each unit = "rev/min") = {
      0,83.2,166.4,249.6,332.8,416,499.2,582.4,665.6,748.8,832,915.2,
      998.4,1081.6,1164.8,1248,1331.2,1414.4,1497.6,1580.8,1664,1747.2,
      1830.4,1913.6,1996.9,2080.1,2163.3,2246.5,2329.7,2412.9,2496.1,
      2579.3,2662.5,2745.7,2828.9,2912.1,2995.3,3078.5,3161.7,3244.9,
      3328.1,3411.3,3494.5,3577.7,3660.9,3744.1,3827.3,3910.5,3993.7,
      4076.9,4160.1,4160.1};
    // 每台电机的牵引转矩曲线 [N.m]：恒转矩区 ~1042.9 N.m，
    // 之后进入弱磁恒功率区，转矩随转速上升而下降
    parameter Real tractionTorqueNmPerMotor[52](each min = 0, each unit = "N.m") = {
      1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,
      1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,
      1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,1042.9,
      1042.9,1042.9,1042.9,1036.8,971,911.2,856.9,807.2,761.7,720,681.6,
      646.2,613.5,583.2,555.1,529,504.7,482,460.8,441,422.4,405,388.6,
      373.2,373.2};
    // 每台电机的电制动（再生）转矩曲线 [N.m]：
    // 转速 0~83.2 rpm 区间无制动力（安全考虑），之后保持 977.7 N.m
    parameter Real brakeTorqueNmPerMotor[52](each min = 0, each unit = "N.m") = {
      0,0,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,
      977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7,977.7};
    // ---------- Davis 阻力公式系数 ----------
    parameter Real davisMassCoefficient(min = 0, unit = "N") = 6.4;         // 质量相关阻力系数 [N]
    parameter Real davisAxleConstant(min = 0, unit = "N") = 130;           // 轴数常量 [N]
    parameter Real davisSpeedMassCoefficient(min = 0, unit = "N.h/km") = 0.14;  // 速度-质量系数 [N·h/km]
    parameter Real davisAeroBase(min = 0, unit = "N.h2/km2/m2") = 0.046;        // 空气阻力基础系数
    parameter Real davisAeroVehicleCoefficient(min = 0, unit = "N.h2/km2/m2") = 0.0065;  // 每辆车空气阻力增量
    parameter Real frontalAreaSquareMeters(min = 0, unit = "m2") = 10.6;   // 列车正面迎风面积 [m²]
    // ---------- 制动减速度 ----------
    parameter Real serviceDecelerationMps2(min = 0, unit = "m/s2") = 1.0;   // 常用制动减速度 [m/s²]
    parameter Real emergencyDecelerationMps2(min = 0, unit = "m/s2") = 1.3; // 紧急制动减速度 [m/s²]
    // ---------- 电压阈值 ----------
    parameter Real minimumVoltageVolts(min = 0, unit = "V") = 1000;        // 最低正常工作电压 [V]
    parameter Real cutoffVoltageVolts(min = 0, unit = "V") = 900;          // 牵引切断电压 [V]

    // ---------- 初始值 ----------
    parameter Real initialPositionMeters(unit = "m") = 0;           // 初始位置 [m]
    parameter Real initialSpeedMetersPerSecond(min = 0, unit = "m/s") = 0;  // 初始速度 [m/s]
    parameter Real initialEnergyConsumedKwh(min = 0, unit = "kWh") = 0;     // 初始牵引能耗 [kWh]
    parameter Real initialEnergyRegeneratedKwh(min = 0, unit = "kWh") = 0;  // 初始再生电量 [kWh]

    // ==================== 输入变量 ====================
    input Real trainMassKg(start = 225000, min = 1, unit = "kg");  // 列车总质量 [kg]
    input Real tractionCommand(start = 0, min = 0, max = 1, unit = "1");  // 牵引指令 [0~1]
    input Real brakeCommand(start = 0, min = 0, max = 1, unit = "1");     // 常用制动指令 [0~1]
    input Boolean emergencyBrakeCommand(start = false);              // 紧急制动触发信号
    input Boolean doorClosed(start = true);                          // 车门关闭信号（安全联锁）
    input Real gradient(start = 0, unit = "1");                     // 坡度（上坡为正，下坡为负）
    input Real lineVoltageVolts(start = 1500, min = 0, unit = "V"); // 接触网电压 [V]
    input Real powerAvailableWatts(start = 0, min = 0, unit = "W"); // 可用牵引功率 [W]
    input Real regenPowerAvailableWatts(start = 0, min = 0, unit = "W");  // 再生制动可回馈功率 [W]
    input Boolean currentCollectionAvailable(start = true);          // 受电弓取流状态
    input Real adhesionCoefficient(start = 0.9, min = 0.2, max = 1, unit = "1");  // 轮轨黏着系数

    // ==================== 输出变量 ====================
    output Real positionMeters(start = initialPositionMeters, fixed = true, unit = "m");  // 列车位置 [m]
    output Real speedMetersPerSecond(
      start = initialSpeedMetersPerSecond,
      fixed = true,
      min = 0,
      unit = "m/s");                                                // 列车速度 [m/s]
    output Real accelerationMetersPerSecondSquared(unit = "m/s2");  // 列车加速度 [m/s²]
    output Real tractionForceNewtons(min = 0, unit = "N");          // 牵引力 [N]
    output Real brakeForceNewtons(min = 0, unit = "N");             // 需求制动力 [N]
    output Real regenBrakeForceNewtons(min = 0, unit = "N");        // 再生制动力 [N]
    output Real motorSpeedRpm(min = 0, unit = "rev/min");           // 电机转速 [rpm]
    output Real interpolatedTractionTorqueNmPerMotor(min = 0, unit = "N.m");  // 插值得到的牵引转矩 [N·m/台]
    output Real interpolatedBrakeTorqueNmPerMotor(min = 0, unit = "N.m");     // 插值得到的制动转矩 [N·m/台]
    output Real airBrakeForceNewtons(min = 0, unit = "N");          // 空气制动力（补足再生不足部分）[N]
    output Real mechanicalTractionPowerWatts(min = 0, unit = "W");  // 牵引机械功率 [W]
    output Real tractionPowerWatts(min = 0, unit = "W");            // 牵引电功率 [W]
    output Real railCurrentAmps(min = 0, unit = "A");               // 接触网电流 [A]
    output Real mechanicalRegenPowerWatts(min = 0, unit = "W");     // 再生机械功率 [W]
    output Real regenPowerWatts(min = 0, unit = "W");               // 再生电功率（回馈电网）[W]
    output Real energyConsumedKwh(
      start = initialEnergyConsumedKwh,
      fixed = true,
      min = 0,
      unit = "kWh");                                                // 累计牵引能耗 [kWh]
    output Real energyRegeneratedKwh(
      start = initialEnergyRegeneratedKwh,
      fixed = true,
      min = 0,
      unit = "kWh");                                                // 累计再生电量 [kWh]
    output Integer faultCodeValue(start = 0, fixed = true);          // 故障代码（0=正常）

  protected
    // ---------- 常数 ----------
    constant Real gravity = 9.81;                       // 重力加速度 [m/s²]
    constant Real pi = 3.141592653589793;               // 圆周率
    constant Real speedFloorMetersPerSecond = 0.5;      // 速度下限，避免除零 [m/s]
    // ---------- 中间变量 ----------
    Real curveTractionForceNewtons(unit = "N");              // 牵引特性曲线上对应的牵引力 [N]
    Real curveElectricBrakeForceNewtons(unit = "N");          // 电制动特性曲线上对应的制动力 [N]
    Real commandTractionForceNewtons(unit = "N");             // 经指令缩放后的牵引力 [N]
    Real supplyLimitedTractionForceNewtons(unit = "N");       // 受供电功率限制的牵引力 [N]
    Real adhesionLimitedTractionForceNewtons(unit = "N");     // 受黏着限制的牵引力 [N]
    Real resistanceForceNewtons(unit = "N");                  // Davis 运行阻力 [N]
    Real gradientForceNewtons(unit = "N");                    // 坡度阻力 [N]
    Real netForceNewtons(unit = "N");                         // 合力 [N]
    Real regenCandidateForceNewtons(unit = "N");              // 再生制动候选力 [N]
    Real regenCandidateMechanicalPowerWatts(unit = "W");       // 再生制动候选机械功率 [W]
    Real regenGridMechanicalLimitWatts(unit = "W");            // 电网允许的再生机械功率上限 [W]
    Real massTonnes(unit = "kg");                             // 列车质量 [t]
    Real speedKilometersPerHour(unit = "km/h");               // 速度 [km/h]（用于 Davis 公式）
    Boolean tractionEnabled;                                    // 牵引使能条件

  equation
    // ---- 1. 电机转速计算 ----
    // 线速度 → 车轮角速度 → 齿轮增速 → 电机转速
    // ω_motor = v / r_wheel * gearRatio，再转换为 rev/min
    motorSpeedRpm = max(0, speedMetersPerSecond) / wheelRadiusMeters
      * gearRatio * 60 / (2 * pi);

    // ---- 2. 根据转速查特性曲线，得到每台电机的转矩 ----
    interpolatedTractionTorqueNmPerMotor = interpolateCurve(
      motorSpeedRpm, speedRpm, tractionTorqueNmPerMotor);
    interpolatedBrakeTorqueNmPerMotor = interpolateCurve(
      motorSpeedRpm, speedRpm, brakeTorqueNmPerMotor);

    // ---- 3. 将单电机转矩转换为整车特性力 ----
    // F_curve = T_perMotor * motorCount * gearRatio / r_wheel
    curveTractionForceNewtons = interpolatedTractionTorqueNmPerMotor
      * motorCount * gearRatio / wheelRadiusMeters;
    curveElectricBrakeForceNewtons = interpolatedBrakeTorqueNmPerMotor
      * motorCount * gearRatio / wheelRadiusMeters;

    // ---- 4. 牵引使能条件 ----
    // 必须同时满足：车门关闭、无紧急制动、受电弓取流正常、
    // 接触网电压高于切断阈值、有可用功率
    tractionEnabled = doorClosed
      and not emergencyBrakeCommand
      and currentCollectionAvailable
      and lineVoltageVolts > cutoffVoltageVolts
      and powerAvailableWatts > 0;

    // ---- 5. 牵引力计算（取三个限制的最小值） ----
    // 5a. 指令限制：根据牵引手柄位置缩放
    commandTractionForceNewtons = max(0, min(1, tractionCommand))
      * curveTractionForceNewtons;
    // 5b. 供电限制：受可用功率和牵引效率约束
    // F_supply = P_available * η_traction / v
    supplyLimitedTractionForceNewtons = max(0, powerAvailableWatts)
      * tractionTotalEfficiency / max(speedMetersPerSecond, speedFloorMetersPerSecond);
    // 5c. 黏着限制：防止轮对空转
    // F_adhesion = μ * m * g
    adhesionLimitedTractionForceNewtons = max(0.2, min(1, adhesionCoefficient))
      * max(trainMassKg, 1) * gravity;
    // 最终牵引力取三者最小值
    tractionForceNewtons = if tractionEnabled then min(
      commandTractionForceNewtons,
      min(supplyLimitedTractionForceNewtons, adhesionLimitedTractionForceNewtons))
      else 0;

    // ---- 6. 制动力与再生制动 ----
    // 6a. 需求制动力：紧急制动取紧急减速度，常用制动按指令比例缩放
    brakeForceNewtons = if emergencyBrakeCommand
      then max(trainMassKg, 1) * emergencyDecelerationMps2
      else max(0, min(1, brakeCommand)) * max(trainMassKg, 1) * serviceDecelerationMps2;

    // 6b. 再生制动候选力：优先用电制动，但不超过特性曲线
    // 紧急制动时不允许再生制动（安全优先）
    regenCandidateForceNewtons = if not emergencyBrakeCommand
      and brakeForceNewtons > 0 and speedMetersPerSecond > 0
      then min(brakeForceNewtons, curveElectricBrakeForceNewtons)
      else 0;

    // 6c. 再生候选机械功率 = 力 × 速度
    regenCandidateMechanicalPowerWatts = regenCandidateForceNewtons
      * speedMetersPerSecond;

    // 6d. 电网允许的再生机械功率上限 = P_regen_available / η_regen
    regenGridMechanicalLimitWatts = max(0, regenPowerAvailableWatts)
      / regenTotalEfficiency;

    // 6e. 实际再生机械功率取候选值与电网限制的较小值
    mechanicalRegenPowerWatts = min(
      regenCandidateMechanicalPowerWatts,
      regenGridMechanicalLimitWatts);

    // 6f. 实际再生制动力：若再生功率 > 0，由再生功率反算力，
    //     再与候选力取小（受黏着/特性限制）
    regenBrakeForceNewtons = if mechanicalRegenPowerWatts > 0
      then min(
        regenCandidateForceNewtons,
        mechanicalRegenPowerWatts / max(speedMetersPerSecond, speedFloorMetersPerSecond))
      else 0;

    // 6g. 再生电功率（回馈至电网）
    regenPowerWatts = regenBrakeForceNewtons * speedMetersPerSecond
      * regenTotalEfficiency;

    // 6h. 空气制动力 = 需求制动力 - 再生制动力（不足部分由空气制动补足）
    airBrakeForceNewtons = max(0, brakeForceNewtons - regenBrakeForceNewtons);

    // ---- 7. 运行阻力（Davis 公式） ----
    // R = A * m + B * n + C * m * v + (D + E * (n_car - 1)) * A_f * v²
    // 其中 A~E 为 Davis 系数，m 为质量[t]，n 为轴数，v 为速度[km/h]，A_f 为迎风面积
    massTonnes = max(trainMassKg, 1) / 1000;                       // 质量 [t]
    speedKilometersPerHour = max(0, speedMetersPerSecond) * 3.6;   // 速度 [km/h]
    resistanceForceNewtons = davisMassCoefficient * massTonnes           // 滚动阻力项
      + davisAxleConstant * axleCount                                    // 轴颈摩擦项
      + davisSpeedMassCoefficient * massTonnes * speedKilometersPerHour  // 速度相关项
      + (davisAeroBase + davisAeroVehicleCoefficient * (vehicleCount - 1))
        * frontalAreaSquareMeters * speedKilometersPerHour * speedKilometersPerHour;  // 空气阻力项

    // ---- 8. 坡度力 ----
    // F_grad = m * g * sin(θ)，θ 很小时 sin(θ) ≈ gradient
    gradientForceNewtons = max(trainMassKg, 1) * gravity * gradient;

    // ---- 9. 合力与加速度 ----
    // 合力 = 牵引力 - 制动力 - 运行阻力 - 坡度力
    netForceNewtons = tractionForceNewtons
      - brakeForceNewtons
      - resistanceForceNewtons
      - gradientForceNewtons;
    // 加速度限制在 [-emergDecel, serviceDecel] 范围内
    accelerationMetersPerSecondSquared = max(
      -emergencyDecelerationMps2,
      min(serviceDecelerationMps2, netForceNewtons / max(trainMassKg, 1)));

    // ---- 10. 运动学积分 ----
    // 速度：保证不反向（防止倒退）
    der(speedMetersPerSecond) = if speedMetersPerSecond <= 0
      and accelerationMetersPerSecondSquared < 0
      then 0
      else accelerationMetersPerSecondSquared;
    // 位置：速度非负累积
    der(positionMeters) = max(0, speedMetersPerSecond);

    // ---- 11. 牵引功率与能耗 ----
    // 机械牵引功率
    mechanicalTractionPowerWatts = tractionForceNewtons * speedMetersPerSecond;
    // 牵引电功率（考虑效率）
    // 实际取机械功率等效电功率与可用供电功率的较小值
    tractionPowerWatts = if mechanicalTractionPowerWatts > 0
      then min(
        max(0, powerAvailableWatts),
        mechanicalTractionPowerWatts / tractionTotalEfficiency)
      else 0;
    // 接触网电流
    railCurrentAmps = if lineVoltageVolts > 1
      then tractionPowerWatts / lineVoltageVolts
      else 0;
    // 能耗积分（1 kWh = 3,600,000 J）
    der(energyConsumedKwh) = tractionPowerWatts / 3600000;
    der(energyRegeneratedKwh) = regenPowerWatts / 3600000;

    // ---- 12. 故障代码 ----
    // 10 = 车门未关
    // 20 = 紧急制动
    // 30 = 取流异常（受电弓离线/电压切断/无可用功率）
    // 31 = 电压低于最低正常工作电压（预警）
    faultCodeValue = if not doorClosed then 10
      elseif emergencyBrakeCommand then 20
      elseif not currentCollectionAvailable
        or lineVoltageVolts <= cutoffVoltageVolts
        or powerAvailableWatts <= 0 then 30
      elseif lineVoltageVolts < minimumVoltageVolts then 31
      else 0;
  end TrainTractionBrake;
end RailwaySimVehicle;
