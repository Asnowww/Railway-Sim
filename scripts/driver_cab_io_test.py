#!/usr/bin/env python3
"""Safe send/receive smoke test for the lab driver-cab TCP interfaces.

Packets mirror the project's Java codecs.  Transmitted state is deliberately
neutral: zero speed, zero acceleration, no traction, no ATO start, and all PLC
indicator bits cleared.  This script does not send a vehicle motion command.
"""

from __future__ import annotations

import argparse
import json
import select
import socket
import struct
import sys
import time
from dataclasses import asdict, dataclass
from datetime import datetime, timedelta, timezone


MAGIC = bytes.fromhex("55 aa 55 aa")
PLC_HOST, PLC_PORT = "192.168.100.123", 8001
NETWORK_HOST, NETWORK_PORT = "192.168.100.121", 8888
SIGNAL_HOST, SIGNAL_PORT = "192.168.100.122", 9999
PLC_RX_BYTES, PLC_TX_BYTES = 46, 26
NETWORK_TX_BYTES, NETWORK_RX_BYTES = 570, 26
SIGNAL_TX_BYTES = 68
SIGNAL_HEADER_TOTAL_BYTES = 62
SIGNAL_HEADER_DATA_BYTES = 42
CHINA_TZ = timezone(timedelta(hours=8))


@dataclass
class Result:
    endpoint: str
    connected: bool = False
    sent_frames: int = 0
    sent_bytes: int = 0
    received_frames: int = 0
    received_bytes: int = 0
    valid_frames: int = 0
    invalid_frames: int = 0
    echoed_frames: int = 0
    detail: str = ""

    @property
    def passed(self) -> bool:
        if self.endpoint == "PLC":
            return self.connected and self.sent_frames > 0 and self.valid_frames > 0
        return self.connected and self.sent_frames > 0 and self.invalid_frames == 0


def put_china_time(payload: bytearray, offset: int) -> None:
    now = datetime.now(CHINA_TZ)
    struct.pack_into(
        "<6H",
        payload,
        offset,
        now.year,
        now.month,
        now.day,
        now.hour,
        now.minute,
        now.second,
    )


def plc_neutral_output() -> bytes:
    payload = bytearray(PLC_TX_BYTES)
    payload[:4] = MAGIC
    struct.pack_into("<HH", payload, 4, PLC_TX_BYTES, PLC_TX_BYTES - 24)
    put_china_time(payload, 8)
    # Bytes 24..25 are indicator bits.  All zero is a display-only neutral state.
    return bytes(payload)


def common_screen_header(payload: bytearray, header_total: int, data_length: int, msg_id: int) -> None:
    payload[:4] = MAGIC
    struct.pack_into("<HHQ4H", payload, 4, header_total, data_length,
                     int(time.time() * 1000), 0, 0, 0, msg_id)


def network_screen_neutral_output(train_no: int = 1) -> bytes:
    payload = bytearray(NETWORK_TX_BYTES)
    common_screen_header(payload, NETWORK_TX_BYTES, NETWORK_TX_BYTES - 24, 0x1001)
    put_china_time(payload, 24)
    # All operational fields remain zero: stopped, no traction, no faults asserted.
    struct.pack_into("<H", payload, 568, train_no)
    return bytes(payload)


def signal_screen_neutral_output(train_no: int = 1) -> bytes:
    payload = bytearray(SIGNAL_TX_BYTES)
    common_screen_header(payload, SIGNAL_HEADER_TOTAL_BYTES, SIGNAL_HEADER_DATA_BYTES, 0x1002)
    put_china_time(payload, 24)
    # Match the Java codec's safe standing defaults.
    payload[39] = 1  # cab active/present
    payload[42] = 0  # UP direction display only (0=UP, 1=DOWN)
    payload[58] = 1  # brake available display
    struct.pack_into("<H", payload, 62, train_no)
    return bytes(payload)


def connect(host: str, port: int, timeout: float, local_host: str | None) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.settimeout(timeout)
    if local_host:
        sock.bind((local_host, 0))
    sock.connect((host, port))
    sock.setblocking(False)
    return sock


def pop_fixed_frames(buffer: bytearray, frame_size: int) -> list[bytes]:
    frames: list[bytes] = []
    while True:
        magic_at = buffer.find(MAGIC)
        if magic_at < 0:
            if len(buffer) > 3:
                del buffer[:-3]
            return frames
        if magic_at:
            del buffer[:magic_at]
        if len(buffer) < frame_size:
            return frames
        frames.append(bytes(buffer[:frame_size]))
        del buffer[:frame_size]


def pop_header_sized_frames(
    buffer: bytearray,
    historical_total: int | None = None,
    historical_wire: int | None = None,
) -> list[bytes]:
    """Split a TCP stream using the common magic + little-endian total length."""
    frames: list[bytes] = []
    while True:
        magic_at = buffer.find(MAGIC)
        if magic_at < 0:
            if len(buffer) > 3:
                del buffer[:-3]
            return frames
        if magic_at:
            del buffer[:magic_at]
        if len(buffer) < 8:
            return frames
        total = struct.unpack_from("<H", buffer, 4)[0]
        wire_length = historical_wire if total == historical_total else total
        if wire_length is None or wire_length < 24 or wire_length > 4096:
            del buffer[0]
            continue
        if len(buffer) < wire_length:
            return frames
        frames.append(bytes(buffer[:wire_length]))
        del buffer[:wire_length]


def valid_header(frame: bytes, expected_total: int, expected_data: int) -> bool:
    if len(frame) != expected_total or frame[:4] != MAGIC:
        return False
    total, data = struct.unpack_from("<HH", frame, 4)
    return total == expected_total and data == expected_data


def plc_fields(frame: bytes) -> dict[str, int | bool]:
    direction, master, traction, brake = struct.unpack_from("<4H", frame, 36)
    return {
        "key_locked": bool(frame[35] & 0x02),
        "direction": direction,
        "master_handle": master,
        "traction_percent": traction,
        "brake_percent": brake,
    }


def test_plc(args: argparse.Namespace) -> Result:
    result = Result("PLC")
    endpoint = f"{args.plc_host}:{args.plc_port}"
    buffer = bytearray()
    payload = plc_neutral_output()
    try:
        with connect(args.plc_host, args.plc_port, args.connect_timeout, args.local_host) as sock:
            result.connected = True
            deadline = time.monotonic() + args.seconds
            # Mirror DriverCabTcpAdapter: wait for the PLC's first 46-byte input
            # before returning the first 26-byte display/status frame.
            next_send: float | None = None
            while time.monotonic() < deadline:
                now = time.monotonic()
                if next_send is not None and now >= next_send:
                    sock.sendall(payload)
                    result.sent_frames += 1
                    result.sent_bytes += len(payload)
                    next_send = now + 0.100
                readable, _, exceptional = select.select([sock], [], [sock], 0.020)
                if exceptional:
                    raise OSError("socket entered exceptional state")
                if readable:
                    chunk = sock.recv(4096)
                    if not chunk:
                        result.detail = "peer closed the connection"
                        break
                    result.received_bytes += len(chunk)
                    buffer.extend(chunk)
                    for frame in pop_fixed_frames(buffer, PLC_RX_BYTES):
                        result.received_frames += 1
                        if valid_header(frame, PLC_RX_BYTES, PLC_RX_BYTES - 24):
                            result.valid_frames += 1
                            if next_send is None:
                                next_send = time.monotonic()
                            fields = plc_fields(frame)
                            print(f"PLC RX #{result.received_frames}: {json.dumps(fields, ensure_ascii=False)}")
                        else:
                            result.invalid_frames += 1
                            print(f"PLC RX invalid: {frame.hex(' ')}")
            if not result.detail:
                result.detail = "valid bidirectional exchange" if result.valid_frames else "no complete PLC input frame"
    except OSError as exc:
        result.detail = f"{type(exc).__name__}: {exc}"
    print_result(result, endpoint)
    return result


def test_screen(
    label: str,
    host: str,
    port: int,
    payload: bytes,
    args: argparse.Namespace,
    expected_rx_bytes: int | None,
) -> Result:
    result = Result(label)
    endpoint = f"{host}:{port}"
    buffer = bytearray()
    try:
        with connect(host, port, args.connect_timeout, args.local_host) as sock:
            result.connected = True
            deadline = time.monotonic() + args.seconds
            next_send = time.monotonic()
            while time.monotonic() < deadline:
                now = time.monotonic()
                if now >= next_send:
                    sock.sendall(payload)
                    result.sent_frames += 1
                    result.sent_bytes += len(payload)
                    next_send = now + 0.500
                readable, _, exceptional = select.select([sock], [], [sock], 0.050)
                if exceptional:
                    raise OSError("socket entered exceptional state")
                if readable:
                    chunk = sock.recv(4096)
                    if not chunk:
                        result.detail = "peer closed the connection"
                        break
                    result.received_bytes += len(chunk)
                    buffer.extend(chunk)
                    for frame in pop_header_sized_frames(
                        buffer,
                        SIGNAL_HEADER_TOTAL_BYTES if label == "SIGNAL_SCREEN" else None,
                        SIGNAL_TX_BYTES if label == "SIGNAL_SCREEN" else None,
                    ):
                        result.received_frames += 1
                        if frame == payload:
                            result.valid_frames += 1
                            result.echoed_frames += 1
                            print(f"{label} RX #{result.received_frames}: full {len(frame)}-byte echo")
                        elif (
                            expected_rx_bytes is not None
                            and valid_header(frame, expected_rx_bytes, expected_rx_bytes - 24)
                        ):
                            result.valid_frames += 1
                            mask = frame[24] & 0x3F
                            print(f"{label} RX #{result.received_frames}: traction_cut_mask=0x{mask:02x}")
                        else:
                            result.invalid_frames += 1
                            print(f"{label} RX invalid {len(frame)}B: {frame.hex(' ')}")
            if not result.detail:
                if result.echoed_frames:
                    result.detail = (
                        "transport OK; peer echoed outbound display frames; "
                        "project adapter compatibility warning"
                    )
                elif result.received_frames == 0:
                    result.detail = "TX accepted; no event-driven response observed"
                else:
                    result.detail = "TX/RX exchange complete"
    except OSError as exc:
        result.detail = f"{type(exc).__name__}: {exc}"
    print_result(result, endpoint)
    return result


def print_result(result: Result, endpoint: str) -> None:
    status = "PASS" if result.passed else "FAIL"
    print(
        f"{status} {result.endpoint} {endpoint} connected={result.connected} "
        f"tx={result.sent_frames}/{result.sent_bytes}B "
        f"rx={result.received_frames}/{result.received_bytes}B "
        f"valid={result.valid_frames} invalid={result.invalid_frames} echo={result.echoed_frames} "
        f"detail={result.detail}"
    )


def probe_endpoint(label: str, host: str, port: int, args: argparse.Namespace) -> Result:
    result = Result(label)
    try:
        with connect(host, port, args.connect_timeout, args.local_host):
            result.connected = True
            result.detail = "TCP connect succeeded"
    except OSError as exc:
        result.detail = f"{type(exc).__name__}: {exc}"
    status = "PASS" if result.connected else "FAIL"
    print(f"{status} {label} {host}:{port} detail={result.detail}")
    return result


def self_test() -> None:
    plc = plc_neutral_output()
    network = network_screen_neutral_output()
    signal = signal_screen_neutral_output()
    assert valid_header(plc, 26, 2)
    assert valid_header(network, 570, 546)
    assert len(signal) == 68
    assert signal[:4] == MAGIC
    assert struct.unpack_from("<HH", signal, 4) == (62, 42)
    assert struct.unpack_from("<H", network, 568)[0] == 1
    assert struct.unpack_from("<H", signal, 62)[0] == 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Safe driver-cab TCP packet smoke test")
    parser.add_argument("mode", choices=("probe", "plc", "screens", "all", "self-test"))
    parser.add_argument("--seconds", type=float, default=3.0)
    parser.add_argument("--connect-timeout", type=float, default=2.0)
    parser.add_argument("--local-host", help="optional source IP; normally leave unset")
    parser.add_argument("--plc-host", default=PLC_HOST)
    parser.add_argument("--plc-port", type=int, default=PLC_PORT)
    parser.add_argument("--network-host", default=NETWORK_HOST)
    parser.add_argument("--network-port", type=int, default=NETWORK_PORT)
    parser.add_argument("--signal-host", default=SIGNAL_HOST)
    parser.add_argument("--signal-port", type=int, default=SIGNAL_PORT)
    parser.add_argument("--json-report", help="optional path for a machine-readable result")
    args = parser.parse_args()
    self_test()
    if args.mode == "self-test":
        print("PASS: all locally constructed frames match project sizes and little-endian headers.")
        return 0

    if args.mode == "probe":
        results = [
            probe_endpoint("PLC", args.plc_host, args.plc_port, args),
            probe_endpoint("NETWORK_SCREEN", args.network_host, args.network_port, args),
            probe_endpoint("SIGNAL_SCREEN", args.signal_host, args.signal_port, args),
        ]
        ok = all(result.connected for result in results)
    else:
        results: list[Result] = []
        if args.mode in ("plc", "all"):
            results.append(test_plc(args))
        if args.mode in ("screens", "all"):
            results.append(test_screen(
                "NETWORK_SCREEN", args.network_host, args.network_port,
                network_screen_neutral_output(), args, NETWORK_RX_BYTES
            ))
            results.append(test_screen(
                "SIGNAL_SCREEN", args.signal_host, args.signal_port,
                signal_screen_neutral_output(), args, None
            ))
        ok = all(result.passed for result in results)

    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "safe_neutral_packets": True,
        "results": [{**asdict(result), "passed": result.passed} for result in results],
        "passed": ok,
    }
    if args.json_report:
        with open(args.json_report, "w", encoding="utf-8") as handle:
            json.dump(report, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print(f"JSON report: {args.json_report}")
    print("OVERALL PASS" if ok else "OVERALL FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
