from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from html import escape
import json
from urllib.parse import urlparse
from typing import Any

from .manager import PowerNetworkModel


class PowerNetworkHttpHandler(BaseHTTPRequestHandler):
    model = PowerNetworkModel()

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path == "/":
            self._write_html(self._dashboard_html())
            return
        if path == "/demo":
            self.model.bootstrap(demo_bootstrap_payload())
            self.model.query_state({
                "sectionLoads": [
                    {"powerSectionId": "P-DEMO-W", "tractionPowerWatts": 1_050_000, "regenPowerWatts": 0, "currentAmps": 1_350},
                    {"powerSectionId": "P-DEMO-E", "tractionPowerWatts": 650_000, "regenPowerWatts": 120_000, "currentAmps": 900},
                ]
            })
            self._write_html(self._dashboard_html("演示拓扑已加载。"))
            return
        if path == "/health":
            self._write_json({"status": "UP"})
            return
        if path == "/power-network/state":
            self._write_json(self.model.snapshot())
            return
        if path == "/power-network/events":
            self._write_json(self.model.event_list())
            return
        if path == "/power-network/topology":
            self._write_json(self.model.topology())
            return
        self.send_error(404, "Not found")

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        if path == "/power-network/bootstrap":
            payload = self._read_json()
            self._write_json(self.model.bootstrap(payload))
            return
        if path == "/power-network/operations":
            payload = self._read_json()
            self._write_json(self.model.operate(payload))
            return
        if path == "/power-network/state/query":
            payload = self._read_json()
            self._write_json(self.model.query_state(payload))
            return
        self.send_error(404, "Not found")

    def _read_json(self) -> dict[str, Any]:
        content_length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(content_length) if content_length > 0 else b"{}"
        return json.loads(raw_body.decode("utf-8"))

    def _write_json(self, payload: Any) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _write_html(self, html: str) -> None:
        body = html.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _dashboard_html(self, notice: str = "") -> str:
        topology = self.model.topology()
        state = self.model.snapshot()
        segments = topology.get("topologySegments", [])
        substations = topology.get("substations", [])
        sections = state.get("thirdRailSections", [])
        notice_html = f"<p class='notice'>{notice}</p>" if notice else ""
        diagram_html = self._network_diagram(topology, state)
        segment_rows = "".join(
            "<tr>"
            f"<td>{segment.get('id', '')}</td>"
            f"<td>{segment.get('fromNodeId', '')} -> {segment.get('toNodeId', '')}</td>"
            f"<td>{segment.get('startMeters', 0):.0f}-{segment.get('endMeters', 0):.0f}m</td>"
            f"<td>{translate_track(segment.get('track', ''))}</td>"
            "</tr>"
            for segment in segments
        ) or "<tr><td colspan='4'>尚未加载轨道拓扑。点击“加载演示拓扑”。</td></tr>"
        section_cards = "".join(
            "<article>"
            f"<h3>{section.get('powerSectionId', '')}</h3>"
            f"<p>{translate_state(section.get('energizationState', ''))} / {translate_supply_mode(section.get('recommendedSupplyMode', ''))}</p>"
            f"<strong>{section.get('contactRailVoltage', 0):.1f} V</strong>"
            f"<small>{section.get('tractionCurrentAmps', 0):.0f} A</small>"
            "</article>"
            for section in sections
        )
        return f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <title>虚拟供电网络演示</title>
  <style>
    body {{ margin: 0; font-family: 'Songti SC', 'Noto Serif CJK SC', Georgia, serif; background: #f1eadb; color: #1f2a24; }}
    main {{ max-width: 1080px; margin: 0 auto; padding: 36px 28px 56px; }}
    h1 {{ font-size: 38px; margin: 0 0 8px; letter-spacing: .03em; }}
    h2 {{ margin-top: 30px; }}
    a.button {{ display: inline-block; margin: 18px 10px 26px 0; padding: 10px 16px; background: #203b2d; color: white; text-decoration: none; border-radius: 999px; }}
    .notice {{ padding: 12px 16px; background: #d7ead4; border-left: 5px solid #2f7c45; }}
    .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); gap: 14px; margin: 20px 0; }}
    article {{ background: white; border: 1px solid #d7cbb5; padding: 18px; border-radius: 16px; box-shadow: 0 10px 22px rgba(31, 42, 36, .08); }}
    article h3 {{ margin: 0 0 6px; }}
    article strong {{ display: block; font-size: 26px; margin-top: 10px; }}
    article small {{ color: #6c5d49; }}
    table {{ width: 100%; border-collapse: collapse; background: white; border-radius: 16px; overflow: hidden; }}
    th, td {{ padding: 12px 14px; border-bottom: 1px solid #eadfcc; text-align: left; }}
    th {{ background: #203b2d; color: white; }}
    code {{ background: #e6ddcb; padding: 2px 6px; border-radius: 6px; }}
    .diagram {{ background: #fbfaf4; border: 1px solid #d7cbb5; border-radius: 22px; padding: 18px; box-shadow: 0 18px 34px rgba(31, 42, 36, .08); }}
    .diagram svg {{ width: 100%; height: auto; display: block; }}
    .legend {{ display: flex; gap: 16px; flex-wrap: wrap; margin: 10px 0 0; color: #5e5140; }}
    .legend span::before {{ content: ""; display: inline-block; width: 22px; height: 8px; border-radius: 99px; margin-right: 7px; vertical-align: middle; }}
    .legend .mv::before {{ background: #355d7a; }}
    .legend .dc::before {{ background: #d28a2c; }}
    .legend .track::before {{ background: #22392d; }}
  </style>
</head>
<body>
<main>
  <h1>虚拟供电网络演示</h1>
  <p>线路: <strong>{topology.get('lineId', '')}</strong> / {topology.get('lineName', '')}</p>
  <p>轨道拓扑区段: <strong>{len(segments)}</strong>；变电所: <strong>{len(substations)}</strong>；接触轨分区: <strong>{len(sections)}</strong></p>
  <a class="button" href="/demo">加载演示拓扑</a>
  <a class="button" href="/power-network/topology">查看拓扑 JSON</a>
  <a class="button" href="/power-network/state">查看状态 JSON</a>
  {notice_html}
  <h2>拓扑图示</h2>
  <section class="diagram">
    {diagram_html}
    <div class="legend"><span class="mv">10kV 环网</span><span class="dc">750V 接触轨供电分区</span><span class="track">轨道拓扑区段</span></div>
  </section>
  <section class="grid">{section_cards}</section>
  <h2>轨道拓扑明细</h2>
  <table>
    <thead><tr><th>区段</th><th>节点连接</th><th>里程范围</th><th>股道</th></tr></thead>
    <tbody>{segment_rows}</tbody>
  </table>
  <p>接口: <code>GET /power-network/topology</code>, <code>POST /power-network/state/query</code>, <code>POST /power-network/operations</code></p>
</main>
</body>
</html>"""

    def _network_diagram(self, topology: dict[str, Any], state: dict[str, Any]) -> str:
        segments = topology.get("topologySegments", [])
        sections = state.get("thirdRailSections", [])
        substations = topology.get("substations", [])
        if not segments:
            return """
<svg viewBox="0 0 980 250" role="img" aria-label="尚未加载演示拓扑">
  <rect x="20" y="20" width="940" height="210" rx="20" fill="#f1eadb" stroke="#d7cbb5"/>
  <text x="490" y="120" text-anchor="middle" font-size="28" fill="#203b2d">尚未加载轨道拓扑</text>
  <text x="490" y="160" text-anchor="middle" font-size="18" fill="#6c5d49">点击“加载演示拓扑”后显示轨道-供电映射图</text>
</svg>"""

        min_meter = min(float(segment.get("startMeters", 0) or 0) for segment in segments)
        max_meter = max(float(segment.get("endMeters", 0) or 0) for segment in segments)
        span = max(1.0, max_meter - min_meter)

        def x_at(meter: float) -> float:
            return 80 + (meter - min_meter) / span * 820

        track_lines = []
        node_labels = {}
        for segment in segments:
            x1 = x_at(float(segment.get("startMeters", 0) or 0))
            x2 = x_at(float(segment.get("endMeters", 0) or 0))
            track_lines.append(
                f"<line x1='{x1:.1f}' y1='330' x2='{x2:.1f}' y2='330' stroke='#203b2d' stroke-width='10' stroke-linecap='round'/>"
            )
            track_lines.append(
                f"<text x='{(x1 + x2) / 2:.1f}' y='358' text-anchor='middle' font-size='15' fill='#203b2d'>{escape(str(segment.get('id', '')))}</text>"
            )
            node_labels[float(segment.get("startMeters", 0) or 0)] = segment.get("fromNodeId", "")
            node_labels[float(segment.get("endMeters", 0) or 0)] = segment.get("toNodeId", "")
        node_marks = "".join(
            f"<circle cx='{x_at(meter):.1f}' cy='330' r='7' fill='#f1eadb' stroke='#203b2d' stroke-width='3'/>"
            f"<text x='{x_at(meter):.1f}' y='390' text-anchor='middle' font-size='14' fill='#6c5d49'>{escape(str(label))}</text>"
            for meter, label in sorted(node_labels.items())
        )

        section_shapes = []
        for section in sections:
            start = next(
                (float(item.get("startMeters", 0) or 0) for item in topology.get("thirdRailSections", []) if item.get("powerSectionId") == section.get("powerSectionId")),
                None,
            )
            end = next(
                (float(item.get("endMeters", 0) or 0) for item in topology.get("thirdRailSections", []) if item.get("powerSectionId") == section.get("powerSectionId")),
                None,
            )
            if start is None or end is None:
                continue
            x1 = x_at(start)
            x2 = x_at(end)
            width = max(60.0, x2 - x1)
            section_shapes.append(
                f"<rect x='{x1:.1f}' y='235' width='{width:.1f}' height='42' rx='12' fill='#f0b45c' stroke='#9b6420'/>"
                f"<text x='{x1 + width / 2:.1f}' y='262' text-anchor='middle' font-size='16' font-weight='700' fill='#3b2712'>{escape(str(section.get('powerSectionId', '')))} "
                f"{translate_supply_mode(section.get('recommendedSupplyMode', ''))}</text>"
            )

        substation_shapes = []
        for index, substation in enumerate(substations):
            section_ids = substation.get("devices", [{}])[0].get("affectsSectionIds", []) if substation.get("devices") else []
            section_id = section_ids[0] if section_ids else ""
            section = next((item for item in sections if item.get("powerSectionId") == section_id), None)
            third = next((item for item in topology.get("thirdRailSections", []) if item.get("powerSectionId") == section_id), None)
            midpoint = (
                (float(third.get("startMeters", 0) or 0) + float(third.get("endMeters", 0) or 0)) / 2
                if third else min_meter + span * (0.25 + index * 0.5)
            )
            x = x_at(midpoint)
            availability = translate_state(substation.get("availability", ""))
            voltage = section.get("contactRailVoltage", 0) if section else 0
            substation_shapes.append(
                f"<line x1='{x:.1f}' y1='170' x2='{x:.1f}' y2='235' stroke='#8c6b32' stroke-width='3' stroke-dasharray='7 6'/>"
                f"<rect x='{x - 92:.1f}' y='110' width='184' height='72' rx='16' fill='#ffffff' stroke='#d7cbb5'/>"
                f"<text x='{x:.1f}' y='136' text-anchor='middle' font-size='17' font-weight='700' fill='#203b2d'>{escape(str(substation.get('id', '')))}</text>"
                f"<text x='{x:.1f}' y='160' text-anchor='middle' font-size='14' fill='#6c5d49'>{availability} / {voltage:.1f}V</text>"
            )

        bus_shapes = "".join(
            f"<rect x='{190 + index * 430}' y='32' width='210' height='46' rx='23' fill='#dceaf2' stroke='#355d7a'/>"
            f"<text x='{295 + index * 430}' y='61' text-anchor='middle' font-size='16' font-weight='700' fill='#23445d'>{escape(str(bus.get('id', '')))} 10kV</text>"
            for index, bus in enumerate(topology.get("mediumVoltageBuses", [])[:2])
        )

        return f"""
<svg viewBox="0 0 980 430" role="img" aria-label="轨道拓扑支撑的虚拟供电网络图">
  <rect x="20" y="20" width="940" height="390" rx="24" fill="#fffdf7" stroke="#d7cbb5"/>
  <text x="42" y="58" font-size="18" font-weight="700" fill="#203b2d">10kV 环网</text>
  <line x1="400" y1="55" x2="620" y2="55" stroke="#355d7a" stroke-width="6" stroke-linecap="round"/>
  {bus_shapes}
  <text x="42" y="150" font-size="18" font-weight="700" fill="#203b2d">牵引变电所</text>
  {''.join(substation_shapes)}
  <text x="42" y="262" font-size="18" font-weight="700" fill="#203b2d">接触轨分区</text>
  {''.join(section_shapes)}
  <text x="42" y="334" font-size="18" font-weight="700" fill="#203b2d">轨道拓扑</text>
  {''.join(track_lines)}
  {node_marks}
</svg>"""

    def log_message(self, format: str, *args: Any) -> None:
        return


def demo_bootstrap_payload() -> dict[str, Any]:
    return {
        "lineId": "demo-topology-line",
        "lineName": "轨道拓扑支撑虚拟电网演示线",
        "topologySegments": [
            {"id": "SEG-DEMO-01", "rawSegmentId": 1, "startMeters": 0, "endMeters": 900, "fromNodeId": "N-START", "toNodeId": "N-A", "track": "UP"},
            {"id": "SEG-DEMO-02", "rawSegmentId": 2, "startMeters": 900, "endMeters": 1800, "fromNodeId": "N-A", "toNodeId": "N-MID", "track": "UP"},
            {"id": "SEG-DEMO-03", "rawSegmentId": 3, "startMeters": 1800, "endMeters": 2700, "fromNodeId": "N-MID", "toNodeId": "N-B", "track": "UP"},
            {"id": "SEG-DEMO-04", "rawSegmentId": 4, "startMeters": 2700, "endMeters": 3600, "fromNodeId": "N-B", "toNodeId": "N-END", "track": "UP"},
        ],
        "sectionBindings": [
            {"powerSectionId": "P-DEMO-W", "thirdRailSectionId": "TRS-DEMO-W", "substationId": "SS-DEMO-W", "feederId": "F-DEMO-W", "startMeters": 0, "endMeters": 1800, "isolatorIds": ["ISO-DEMO-W-A", "ISO-DEMO-W-B"]},
            {"powerSectionId": "P-DEMO-E", "thirdRailSectionId": "TRS-DEMO-E", "substationId": "SS-DEMO-E", "feederId": "F-DEMO-E", "startMeters": 1800, "endMeters": 3600, "isolatorIds": ["ISO-DEMO-E-A", "ISO-DEMO-E-B"]},
        ],
        "substations": [
            {"id": "SS-DEMO-W", "name": "西端牵引变电所", "supplyMode": "DOUBLE_END", "startMeters": 0, "endMeters": 1800, "sectionIds": ["P-DEMO-W"], "devices": [
                device("SS-DEMO-W-TR", "西端牵引变压器", "TRACTION_TRANSFORMER", "P-DEMO-W"),
                device("SS-DEMO-W-REC", "西端整流器", "RECTIFIER", "P-DEMO-W"),
                device("SS-DEMO-W-DCB", "西端直流快速断路器", "DC_BREAKER", "P-DEMO-W", "CLOSED"),
            ]},
            {"id": "SS-DEMO-E", "name": "东端牵引变电所", "supplyMode": "DOUBLE_END", "startMeters": 1800, "endMeters": 3600, "sectionIds": ["P-DEMO-E"], "devices": [
                device("SS-DEMO-E-TR", "东端牵引变压器", "TRACTION_TRANSFORMER", "P-DEMO-E"),
                device("SS-DEMO-E-REC", "东端整流器", "RECTIFIER", "P-DEMO-E"),
                device("SS-DEMO-E-DCB", "东端直流快速断路器", "DC_BREAKER", "P-DEMO-E", "CLOSED"),
            ]},
        ],
        "isolators": [
            {"id": "ISO-DEMO-W-A", "name": "西端首端隔离开关", "thirdRailSectionId": "TRS-DEMO-W", "positionMeters": 0, "defaultState": "CLOSED"},
            {"id": "ISO-DEMO-W-B", "name": "西端末端隔离开关", "thirdRailSectionId": "TRS-DEMO-W", "positionMeters": 1800, "defaultState": "CLOSED"},
            {"id": "ISO-DEMO-E-A", "name": "东端首端隔离开关", "thirdRailSectionId": "TRS-DEMO-E", "positionMeters": 1800, "defaultState": "CLOSED"},
            {"id": "ISO-DEMO-E-B", "name": "东端末端隔离开关", "thirdRailSectionId": "TRS-DEMO-E", "positionMeters": 3600, "defaultState": "CLOSED"},
        ],
        "strayCurrentMonitors": [
            {"id": "SCP-DEMO-W", "name": "西段极化电位监测点", "sectionId": "P-DEMO-W", "returnCurrentDeviceId": "RCD-P-DEMO-W", "positionMeters": 900, "normalMinPotentialVolts": -0.8, "normalMaxPotentialVolts": 1.8},
            {"id": "SCP-DEMO-E", "name": "东段极化电位监测点", "sectionId": "P-DEMO-E", "returnCurrentDeviceId": "RCD-P-DEMO-E", "positionMeters": 2700, "normalMinPotentialVolts": -0.8, "normalMaxPotentialVolts": 1.8},
        ],
    }


def device(device_id: str, name: str, device_type: str, section_id: str, default_state: str = "AVAILABLE") -> dict[str, Any]:
    return {
        "id": device_id,
        "name": name,
        "deviceType": device_type,
        "defaultState": default_state,
        "ratedVoltage": 750,
        "ratedCurrentAmps": 2200,
        "affectsSectionIds": [section_id],
    }


def translate_state(value: str) -> str:
    return {
        "UP": "在线",
        "AVAILABLE": "可用",
        "ENERGIZED": "带电",
        "DEENERGIZED": "失电",
        "OUT_OF_SERVICE": "停运",
        "CLOSED": "合闸",
        "OPEN": "分闸",
        "TRIPPED": "跳闸",
        "ISOLATED": "隔离",
    }.get(value, value or "未知")


def translate_supply_mode(value: str) -> str:
    return {
        "DOUBLE_END": "双端供电",
        "SINGLE_END": "单端支援",
        "CROSS_FEED": "越区供电",
        "OUTAGE": "无电",
    }.get(value, value or "未知")


def translate_track(value: str) -> str:
    return {
        "UP": "上行",
        "DOWN": "下行",
        "BOTH": "双线",
    }.get(value, value or "未知")


def main() -> None:
    server = ThreadingHTTPServer(("127.0.0.1", 9200), PowerNetworkHttpHandler)
    print("External power network service listening on http://127.0.0.1:9200")
    server.serve_forever()


if __name__ == "__main__":
    main()
