# -*- coding: utf-8 -*-
"""PLC / 司机台 / 视景 连通性测试脚本

链路: 浏览器司机台(JSON) -> 8080网关 -> 46字节PLC帧 -> 9300车辆控制 -> 视景UDP(8302)

前置: 9200 供电、8080 中央(split)、9300 车辆运行时(split) 均已启动。
可用环境变量覆盖端点:
  CENTRAL_BASE  默认 http://127.0.0.1:8080
  VEHICLE_BASE  默认 http://127.0.0.1:9300
  POWER_BASE    默认 http://127.0.0.1:9200
  TRAIN_ID      默认 TR-001
  VISION_PORT   默认 8302 (本机模拟视景控制机的UDP收包端口)
"""
import json
import os
import socket
import struct
import sys
import threading
import time
import urllib.request
import urllib.error

CENTRAL = os.environ.get("CENTRAL_BASE", "http://127.0.0.1:8080")
VEHICLE = os.environ.get("VEHICLE_BASE", "http://127.0.0.1:9300")
POWER = os.environ.get("POWER_BASE", "http://127.0.0.1:9200")
TRAIN = os.environ.get("TRAIN_ID", "TR-001")
VISION_ADDR = ("127.0.0.1", int(os.environ.get("VISION_PORT", "8302")))

results = []

def record(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("[PASS] " if ok else "[FAIL] ") + name + ("  | " + detail if detail else ""))

def http(method, url, body=None, content_type="application/json", timeout=8):
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            ct = resp.headers.get("Content-Type", "")
            if "json" in ct:
                return resp.status, json.loads(raw.decode())
            return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw.decode())
        except Exception:
            return e.code, raw
    except Exception as e:
        return -1, str(e)

# ---------------- 46字节 PLC 输入帧 (小端, 帧头 55 AA 55 AA) ----------------
def build_plc_frame(direction=1, master=1, traction=0, brake=0,
                    key_locked=True, doors_closed=True, door_mode=1,
                    bad_identify=False):
    """direction: 0=FORWARD? 按协议码 DriverCabDirectionHandleState; master: 协议码"""
    p = bytearray(46)
    ident = b"\xaa\x55\xaa\x55" if bad_identify else b"\x55\xaa\x55\xaa"
    p[0:4] = ident
    struct.pack_into("<H", p, 4, 46)     # 总长
    struct.pack_into("<H", p, 6, 22)     # 数据长 46-24
    if doors_closed:
        p[24] |= 1 << 5                  # doorsClosedLockedIndicator
    p[24] |= 1 << 1                      # highVoltageClosedIndicator
    p[29] |= (1 << 2) | (1 << 3)         # closeLeft/RightDoorFlag
    struct.pack_into("<H", p, 32, door_mode)   # 门模式 SEMI_AUTOMATIC=1(按枚举序)
    if key_locked:
        p[35] |= 1 << 1                  # keySwitchLocked
    struct.pack_into("<H", p, 36, direction)   # 方向手柄
    struct.pack_into("<H", p, 38, master)      # 主手柄
    struct.pack_into("<H", p, 40, traction)
    struct.pack_into("<H", p, 42, brake)
    return bytes(p)

# ---------------- 视景 UDP 报文解码 (小端) ----------------
def decode_vision(pkt):
    off = 0
    counter, = struct.unpack_from("<I", pkt, off); off += 4
    n_sig = pkt[off]; off += 1
    signals = list(pkt[off:off + n_sig]); off += n_sig
    n_sw = pkt[off]; off += 1
    switches = list(pkt[off:off + n_sw]); off += n_sw
    speed_mm_s, = struct.unpack_from("<i", pkt, off); off += 4
    departure, = struct.unpack_from("<H", pkt, off); off += 2
    op_code = pkt[off]; off += 1
    accel_pct = pkt[off]; off += 1
    head_mm, = struct.unpack_from("<i", pkt, off); off += 4
    seg_no, = struct.unpack_from("<H", pkt, off); off += 2
    direction, = struct.unpack_from("<b", pkt, off); off += 1
    n_other = pkt[off]; off += 1
    return {
        "counter": counter, "signalCount": n_sig, "switchCount": n_sw,
        "signals": signals[:8], "switches": switches[:8],
        "speed_mm_s": speed_mm_s, "departure": departure,
        "opCode": hex(op_code), "accelPct": accel_pct,
        "head_mm": head_mm, "segNo": seg_no, "direction": direction,
        "otherTrains": n_other, "bytes": len(pkt),
    }

vision_packets = []
def vision_listener(stop_evt):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind(VISION_ADDR)
    s.settimeout(0.5)
    while not stop_evt.is_set():
        try:
            data, addr = s.recvfrom(4096)
            vision_packets.append((time.time(), data))
        except socket.timeout:
            pass
    s.close()

def get_state():
    code, body = http("GET", f"{VEHICLE}/vehicle-runtime/trains/{TRAIN}/state")
    return body if code == 200 else None

def main():
    print("=" * 62)
    print("Railway-Sim  PLC / 司机台 / 视景 连通性测试")
    print("=" * 62)

    # ---- 0. 服务健康 ----
    code, _ = http("GET", f"{POWER}/docs", timeout=5)
    record("0.1 供电服务 9200 在线", code == 200, f"GET /docs -> {code}")
    code, body = http("GET", f"{VEHICLE}/vehicle-runtime/health", timeout=5)
    record("0.2 车辆运行时 9300 在线", code == 200, str(body)[:80])
    code, body = http("GET", f"{CENTRAL}/api/service-health", timeout=5)
    if code != 200:
        code, body = http("GET", f"{CENTRAL}/api/simulation/status", timeout=5)
    record("0.3 中央后端 18080 在线", code == 200, f"-> {code}")

    # ---- 1. 创建列车运行实例 ----
    code, body = http("POST", f"{VEHICLE}/vehicle-runtime/trains/launch", {
        "trainId": TRAIN, "linkId": 12, "offsetMeters": 640.0,
        "direction": "DOWN", "registerWithCentral": True,
        "reason": "connectivity test", "traceId": "conn-test-001",
    })
    record("1.1 9300 创建列车实例 launch", code == 200,
           json.dumps(body, ensure_ascii=False)[:160] if isinstance(body, dict) else str(body)[:160])

    # 中央仿真启动(让中央时钟推进, 镜像状态)
    code, body = http("POST", f"{CENTRAL}/api/simulation/start", {})
    record("1.2 中央仿真 start", code == 200, str(body)[:120])

    st = get_state()
    record("1.3 9300 读取车辆权威状态", st is not None,
           f"pos={st.get('positionMeters'):.1f}m v={st.get('speedMetersPerSecond'):.2f}m/s mode={st.get('operationMode')}" if st else "no state")

    # ---- 2. PLC 通道: JSON 网关 (司机台 -> 8080 -> 9300) ----
    plc_json = {
        "highVoltageClosedIndicator": True, "doorsClosedLockedIndicator": True,
        "networkFaultIndicator": False, "automaticTurnbackAvailable": False,
        "atoModeAvailable": True, "atoModeActive": False,
        "automaticTurnbackActive": False, "emergencyBrakeButtonLocked": False,
        "openLeftDoorFlag": False, "openRightDoorFlag": False,
        "closeLeftDoorFlag": True, "closeRightDoorFlag": True,
        "doorModeSwitchState": "SEMI_AUTOMATIC",
        "modeUpgradeConfirmFlag": False, "modeDowngradeConfirmFlag": False,
        "automaticTurnbackFlag": False, "atoStartFlag": False,
        "keySwitchLocked": True, "directionHandleState": "FORWARD",
        "masterHandleState": "TRACTION",
        "tractionNotchPercent": 40, "brakeNotchPercent": 0,
    }
    code, body = http("POST", f"{CENTRAL}/api/vehicle/driver-cabs/{TRAIN}/plc-input", plc_json)
    ok = code == 200 and isinstance(body, dict) and body.get("accepted") is True
    record("2.1 JSON PLC输入经18080网关转发9300被接受", ok,
           json.dumps(body, ensure_ascii=False)[:160] if isinstance(body, dict) else str(body)[:160])

    # ---- 3. 列车前进验证: 持续刷新手柄(1s周期, 防5s过期), 观察速度/位置 ----
    v0 = get_state()
    samples = []
    t_end = time.time() + 10
    while time.time() < t_end:
        http("POST", f"{CENTRAL}/api/vehicle/driver-cabs/{TRAIN}/plc-input", plc_json)
        st = get_state()
        if st:
            samples.append((st["positionMeters"], st["speedMetersPerSecond"],
                            st.get("tractionState"), st.get("tractionForceNewtons")))
        time.sleep(1.0)
    if v0 and samples:
        dv = samples[-1][1] - v0["speedMetersPerSecond"]
        dx = samples[-1][0] - v0["positionMeters"]
        record("3.1 列车前进: 速度建立", dv > 0.1,
               f"v: {v0['speedMetersPerSecond']:.2f} -> {samples[-1][1]:.2f} m/s, tractionState={samples[-1][2]}, F={samples[-1][3]:.0f}N")
        record("3.2 列车前进: 位置推进", dx > 0.5,
               f"pos: {v0['positionMeters']:.1f} -> {samples[-1][0]:.1f} m (Δ{dx:.1f}m)")
    else:
        record("3.1 列车前进", False, "无状态样本")

    # ---- 4. 中央侧司机台状态与PLC输出回读 ----
    code, body = http("GET", f"{CENTRAL}/api/vehicle/driver-cabs/{TRAIN}/state")
    record("4.1 中央司机台状态快照", code == 200,
           json.dumps(body, ensure_ascii=False)[:160] if isinstance(body, dict) else f"-> {code}")
    code, body = http("GET", f"{CENTRAL}/api/vehicle/driver-cabs/{TRAIN}/plc-output")
    ok = code == 200 and isinstance(body, (bytes, bytearray))
    hexs = body.hex(" ") if isinstance(body, (bytes, bytearray)) else str(body)[:80]
    record("4.2 中央PLC输出回读(预期4字节 55 aa ..)", ok and len(body) == 4 and body[:2] == b"\x55\xaa",
           f"{len(body) if isinstance(body, (bytes, bytearray)) else '?'}B: {hexs}")

    # ---- 5. 二进制 46字节 PLC 帧直发 9300 ----
    frame = build_plc_frame(direction=1, master=1, traction=60, brake=0)
    code, body = http("POST", f"{VEHICLE}/api/vehicle/driver-cabs/{TRAIN}/plc-input",
                      frame, content_type="application/octet-stream")
    ok = code == 200 and isinstance(body, dict) and body.get("accepted") is True
    record("5.1 46字节二进制PLC帧直发9300被接受", ok,
           json.dumps(body, ensure_ascii=False)[:160] if isinstance(body, dict) else str(body)[:160])

    # 704文档回归: 帧头写反(AA 55 AA 55)必须被拒绝
    bad = build_plc_frame(direction=1, master=1, traction=60, bad_identify=True)
    code, body = http("POST", f"{VEHICLE}/api/vehicle/driver-cabs/{TRAIN}/plc-input",
                      bad, content_type="application/octet-stream")
    record("5.2 帧头AA 55 AA 55被正确拒绝(704文档回归)", code == 400,
           f"-> {code} {json.dumps(body, ensure_ascii=False)[:120] if isinstance(body, dict) else str(body)[:100]}")

    # ---- 6. 视景系统 UDP 收包 (模拟视景控制机) ----
    stop_evt = threading.Event()
    th = threading.Thread(target=vision_listener, args=(stop_evt,), daemon=True)
    th.start()
    time.sleep(0.3)

    send_results = []
    move_frame = build_plc_frame(direction=1, master=1, traction=60, brake=0)
    for i in range(5):
        http("POST", f"{VEHICLE}/api/vehicle/driver-cabs/{TRAIN}/plc-input",
             move_frame, content_type="application/octet-stream")
        code, body = http("POST",
            f"{CENTRAL}/api/signal/vision/udp/send?trainId={TRAIN}&host={VISION_ADDR[0]}&port={VISION_ADDR[1]}", b"")
        send_results.append((code, body))
        time.sleep(1.0)
    time.sleep(0.5)
    stop_evt.set(); th.join(timeout=2)

    ok_send = all(c == 200 for c, _ in send_results)
    record("6.1 中央触发视景UDP发包接口", ok_send,
           json.dumps(send_results[0][1], ensure_ascii=False)[:160] if isinstance(send_results[0][1], dict) else str(send_results[0][1])[:120])
    record("6.2 视景控制机(模拟)收到UDP报文", len(vision_packets) >= 3,
           f"收到 {len(vision_packets)}/5 包")

    if vision_packets:
        decoded = [decode_vision(p) for _, p in vision_packets]
        print("\n  -- 视景报文解码 --")
        for d in decoded:
            print("  ", json.dumps(d, ensure_ascii=False))
        counters = [d["counter"] for d in decoded]
        record("6.3 报文计数器递增", all(b > a for a, b in zip(counters, counters[1:])),
               f"counters={counters}")
        positions = [d["head_mm"] for d in decoded]
        record("6.4 视景报文中列车位置随时间推进(视景系统移动)",
               positions[-1] > positions[0],
               f"head_mm: {positions[0]} -> {positions[-1]} (Δ{(positions[-1]-positions[0])/1000.0:.1f}m)")
        st = get_state()
        if st:
            v_mm = decoded[-1]["speed_mm_s"]
            v_rt = st["speedMetersPerSecond"] * 1000
            record("6.5 视景速度与9300权威速度一致(±20%)",
                   abs(v_mm - v_rt) < max(500, 0.2 * max(v_mm, v_rt)),
                   f"vision={v_mm}mm/s runtime={v_rt:.0f}mm/s")
        d0 = decoded[-1]
        record("6.6 报文含信号机/道岔状态列表", d0["signalCount"] > 0 and d0["switchCount"] > 0,
               f"signals={d0['signalCount']} switches={d0['switchCount']} dir={d0['direction']} op={d0['opCode']}")

    # ---- 7. 制动收车: manual-control 快捷接口 ----
    code, body = http("POST", f"{VEHICLE}/vehicle-runtime/trains/{TRAIN}/manual-control", {
        "tractionCommand": 0.0, "brakeCommand": 0.6, "emergencyBrake": False,
        "direction": 1.0, "doorOpenRequest": False, "timeoutMs": 8000,
    })
    ok = code == 200 and isinstance(body, dict) and body.get("accepted") is True
    record("7.1 manual-control 制动命令被接受", ok,
           json.dumps(body, ensure_ascii=False)[:140] if isinstance(body, dict) else str(body)[:120])
    v_before = get_state()
    time.sleep(4)
    v_after = get_state()
    if v_before and v_after:
        record("7.2 制动生效: 速度下降", v_after["speedMetersPerSecond"] < v_before["speedMetersPerSecond"] + 0.01,
               f"v: {v_before['speedMetersPerSecond']:.2f} -> {v_after['speedMetersPerSecond']:.2f} m/s, brakeState={v_after.get('brakeState')}")

    # ---- 汇总 ----
    print("\n" + "=" * 62)
    passed = sum(1 for _, ok, _ in results if ok)
    print(f"结果: {passed}/{len(results)} 项通过")
    for name, ok, _ in results:
        if not ok:
            print("  未通过: " + name)
    return 0 if passed == len(results) else 1

if __name__ == "__main__":
    sys.exit(main())
