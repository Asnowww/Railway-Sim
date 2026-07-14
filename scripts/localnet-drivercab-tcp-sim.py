# -*- coding: utf-8 -*-
"""机房局域网 司机台 TCP 设备仿真器（PLC / 网络屏HMI / 信号屏MMI）

协议方向：PLC 与两块屏都是 TCP 服务器，中央 DriverCabTcpAdapter 作客户端外连。
本仿真器即扮演这三台设备（loopback 端口），用于在无实物时端到端验收：
  - PLC 服务器  ：周期发送 46 字节司机手柄帧(前进+牵引)，并接收校验 26 字节下行输出帧
  - 网络屏HMI   ：接收校验 570 字节单向输出帧(头 totalLen=570/dataLen=546, msgId=0x1001)
  - 信号屏MMI   ：接收校验 68 字节单向输出帧(头 totalLen=62/dataLen=42, wire=68, msgId=0x1002)

默认端口 PLC=18001 HMI=18888 MMI=19999（避开真实 8001/8888/9999，防抢现场口）。
运行 DURATION 秒后打印 JSON 报告并按是否全部通过返回退出码。

用法: python localnet-drivercab-tcp-sim.py [--duration 12] [--plc 18001 --hmi 18888 --mmi 19999]
"""
import argparse
import json
import socket
import struct
import threading
import time

IDENTIFY = b"\x55\xaa\x55\xaa"


def build_plc_frame(direction=1, master=1, traction=60, brake=0,
                    key_locked=True, doors_closed=True, door_mode=1):
    """46 字节 PLC->上位机帧(小端, 帧头 55 AA 55 AA)。字段偏移同协议 7.1。"""
    p = bytearray(46)
    p[0:4] = IDENTIFY
    struct.pack_into("<H", p, 4, 46)      # 总长
    struct.pack_into("<H", p, 6, 22)      # 数据长
    if doors_closed:
        p[24] |= 1 << 5
    p[24] |= 1 << 1                        # 高压合
    p[29] |= (1 << 2) | (1 << 3)          # 关左/右门
    struct.pack_into("<H", p, 32, door_mode)
    if key_locked:
        p[35] |= 1 << 1                   # 钥匙锁定
    struct.pack_into("<H", p, 36, direction)
    struct.pack_into("<H", p, 38, master)
    struct.pack_into("<H", p, 40, traction)
    struct.pack_into("<H", p, 42, brake)
    return bytes(p)


class Channel:
    def __init__(self, name, port, expect_len, header_total, header_data, msg_id=None):
        self.name = name
        self.port = port
        self.expect_len = expect_len
        self.header_total = header_total
        self.header_data = header_data
        self.msg_id = msg_id
        self.connected = False
        self.recv_frames = 0
        self.valid_frames = 0
        self.sent_frames = 0
        self.errors = []
        self._buf = bytearray()

    def validate_frame(self, frame):
        if frame[0:4] != IDENTIFY:
            self.errors.append("bad identify")
            return False
        total = struct.unpack_from("<H", frame, 4)[0]
        data = struct.unpack_from("<H", frame, 6)[0]
        if total != self.header_total or data != self.header_data:
            self.errors.append(f"header total/data={total}/{data} expected {self.header_total}/{self.header_data}")
            return False
        if self.msg_id is not None:
            mid = struct.unpack_from("<H", frame, 22)[0]
            if mid != self.msg_id:
                self.errors.append(f"msgId={hex(mid)} expected {hex(self.msg_id)}")
                return False
        return True

    def feed(self, chunk):
        """按固定 wire 长度切帧(以 55AA 重同步)。"""
        self._buf += chunk
        while True:
            idx = self._buf.find(IDENTIFY)
            if idx < 0:
                if len(self._buf) > 3:
                    del self._buf[:-3]
                return
            if idx > 0:
                del self._buf[:idx]
            if len(self._buf) < self.expect_len:
                return
            frame = bytes(self._buf[:self.expect_len])
            del self._buf[:self.expect_len]
            self.recv_frames += 1
            if self.validate_frame(frame):
                self.valid_frames += 1


def serve_screen(ch, stop_evt):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", ch.port))
    srv.listen(1)
    srv.settimeout(0.5)
    while not stop_evt.is_set():
        try:
            conn, _ = srv.accept()
        except socket.timeout:
            continue
        ch.connected = True
        conn.settimeout(0.5)
        with conn:
            while not stop_evt.is_set():
                try:
                    data = conn.recv(4096)
                    if not data:
                        break
                    ch.feed(data)
                except socket.timeout:
                    continue
                except OSError:
                    break
    srv.close()


def serve_plc(ch, stop_evt, send_interval):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", ch.port))
    srv.listen(1)
    srv.settimeout(0.5)
    frame = build_plc_frame()
    while not stop_evt.is_set():
        try:
            conn, _ = srv.accept()
        except socket.timeout:
            continue
        ch.connected = True
        conn.settimeout(0.2)
        last_send = 0.0
        with conn:
            while not stop_evt.is_set():
                now = time.monotonic()
                if now - last_send >= send_interval:
                    try:
                        conn.sendall(frame)
                        ch.sent_frames += 1
                        last_send = now
                    except OSError:
                        break
                try:
                    data = conn.recv(4096)
                    if not data:
                        break
                    ch.feed(data)
                except socket.timeout:
                    pass
                except OSError:
                    break
    srv.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration", type=float, default=12.0)
    ap.add_argument("--plc", type=int, default=18001)
    ap.add_argument("--hmi", type=int, default=18888)
    ap.add_argument("--mmi", type=int, default=19999)
    ap.add_argument("--send-interval", type=float, default=0.1)
    args = ap.parse_args()

    plc = Channel("PLC", args.plc, 26, 26, 2)                 # 下行 26B 输出帧
    hmi = Channel("HMI", args.hmi, 570, 570, 546, msg_id=0x1001)
    mmi = Channel("MMI", args.mmi, 68, 62, 42, msg_id=0x1002)  # wire 68 但头保留 62

    stop_evt = threading.Event()
    threads = [
        threading.Thread(target=serve_plc, args=(plc, stop_evt, args.send_interval), daemon=True),
        threading.Thread(target=serve_screen, args=(hmi, stop_evt), daemon=True),
        threading.Thread(target=serve_screen, args=(mmi, stop_evt), daemon=True),
    ]
    for t in threads:
        t.start()

    print(f"[sim] listening PLC:{args.plc} HMI:{args.hmi} MMI:{args.mmi} for {args.duration}s ...", flush=True)
    time.sleep(args.duration)
    stop_evt.set()
    for t in threads:
        t.join(timeout=2)

    report = {}
    ok = True
    for ch in (plc, hmi, mmi):
        passed = ch.connected and ch.valid_frames > 0 and ch.valid_frames == ch.recv_frames
        if ch.name == "PLC":
            passed = passed and ch.sent_frames > 0
        ok = ok and passed
        report[ch.name] = {
            "connected": ch.connected, "sent": ch.sent_frames,
            "recv": ch.recv_frames, "valid": ch.valid_frames,
            "errors": ch.errors[:5], "pass": passed,
        }
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)
    print("RESULT:", "ALL_PASS" if ok else "FAIL", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
