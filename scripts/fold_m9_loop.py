#!/usr/bin/env python3
"""fold_m9_loop.py — 生成 M9 环线里程（loop-km）数据块。

环线约定（详见 config/line-m9.yaml 头部注释）：
  - up 股道：loop_km = geo_km，范围 [0, L]，L = 16489.02（郭公庄=0 → 国图=L）
  - down 股道：loop_km = 2L - geo_km，范围 [L, 2L]（国图=L → 郭公庄=2L）
    区间集合换算 new_start = 2L - old_end；new_end = 2L - old_start（长度/站间距不变）
  - down 行车方向（国图→郭公庄）= loop_km 递增，满足引擎"只能里程递增"的约束
  - 区段 ID 按行车链顺序（R_DOWN axle 顺序）映射到升序 loop 区间——
    即 D47 取最小区间、D03 取最大区间；from/to 节点链与进路定义无需改动
  - 前端 LineDiagram 将 loop_km > L 的部分折叠回顶部车道显示

用法：python scripts/fold_m9_loop.py
输出数据块到 stdout（D 区段 / SIG-D 信号 / S2xx 车站 / dispatch-plan 片段），
人工粘贴进 config/line-m9.yaml 与 dispatch-plan.yaml 对应区域
（避免 PyYAML 重排丢注释）。
"""

L = 16489.02
TWO_L = 2 * L  # 32978.04

# ---- 原地理里程区间（来自旧版 line-m9.yaml，仅用于换算区间集合）----
D_GEO_RANGES = [
    (0.0, 215.49),
    (215.49, 232.81),
    (232.81, 300.19),
    (300.19, 443.81),
    (443.81, 511.19),
    (511.19, 1719.52),
    (1719.52, 2507.61),
    (2507.61, 3488.32),
    (3488.32, 5274.8396),
    (5274.8396, 6400.274),
    (6400.274, 8179.204),
    (8179.204, 8346.083),
    (8346.083, 8687.02908),
    (8687.02908, 9488.344),
    (9488.344, 10659.11378),
    (10659.11378, 12058.07),
    (12058.07, 13970.28014),
    (13970.28014, 14039.49649),
    (14039.49649, 15013.91),
    (15013.91, 16110.01966),
    (16110.01966, 16203.38149),
    (16203.38149, 16270.76149),
    (16270.76149, 16484.57149),
]

# 行车链顺序（= R_DOWN axle_section_ids）：(id, from, to, raw_id)
D_CHAIN = [
    ("D47", "ND12", "ND11", 47),
    ("D43", "ND11", "ND10", 43),
    ("D36a", "ND10", "ND10s12", 0),
    ("D36b", "ND10s12", "ND10s13", 0),
    ("D36c", "ND10s13", "ND09", 0),
    ("D28a", "ND09", "ND09s8", 0),
    ("D28b", "ND09s8", "ND09s9", 0),
    ("D28c", "ND09s9", "ND09s10", 0),
    ("D28d", "ND09s10", "ND09s11", 0),
    ("D28e", "ND09s11", "ND08", 0),
    ("D24", "ND08", "ND07", 24),
    ("D21a", "ND07", "ND07s6", 0),
    ("D21b", "ND07s6", "ND07s7", 0),
    ("D21c", "ND07s7", "ND06", 0),
    ("D17a", "ND06", "ND06s2", 0),
    ("D17b", "ND06s2", "ND06s3", 0),
    ("D17c", "ND06s3", "ND06s4", 0),
    ("D17d", "ND06s4", "ND05", 0),
    ("D14", "ND05", "ND04", 14),
    ("D11", "ND04", "ND03", 11),
    ("D07", "ND03", "ND02", 7),
    ("D04", "ND02", "ND01", 4),
    ("D03", "ND01", "ND00", 3),
]

# (station_id, name, down: (center_geo, stop_left_geo, stop_right_geo, side))
STATIONS_GEO = [
    ("S101", "郭公庄站", (372.0, 313.0, 431.0, "right")),
    ("S102", "丰台科技园站", (1719.52, 1660.52, 1778.52, "left")),
    ("S103", "科怡路站", (2507.61, 2448.61, 2566.61, "left")),
    ("S104", "丰台南路站", (3488.32, 3429.32, 3547.32, "left")),
    ("S105", "丰台东大街站", (5074.834, 5015.834, 5133.834, "left")),
    ("S106", "七里庄站", (6400.274, 6341.274, 6459.274, "left")),
    ("S107", "六里桥站", (8179.204, 8120.204, 8238.204, "right")),
    ("S108", "六里桥东站", (9488.344, 9429.344, 9547.344, "left")),
    ("S109", "北京西站", (10659.11378, 10600.11378, 10718.11378, "left")),
    ("S110", "军事博物馆站", (12058.07, 11999.07, 12117.07, "left")),
    ("S111", "白堆子站", (13970.28014, 13911.28014, 14029.28014, "left")),
    ("S112", "白石桥南站", (15013.91, 14954.91, 15072.91, "left")),
    ("S113", "国家图书馆站", (16110.01966, 16051.01966, 16169.01966, "right")),
]

# down 信号：每组行车入口子段（= 旧 YAML 中的 segment_id，不变）
SIG_D_GROUPS = [
    ("SIG-D47", "D47"),
    ("SIG-D43", "D43"),
    ("SIG-D36", "D36a"),
    ("SIG-D28", "D28a"),
    ("SIG-D24", "D24"),
    ("SIG-D21", "D21a"),
    ("SIG-D17", "D17a"),
    ("SIG-D14", "D14"),
    ("SIG-D11", "D11"),
    ("SIG-D07", "D07"),
    ("SIG-D04", "D04"),
    ("SIG-D03", "D03"),
]


def fmt(v: float) -> str:
    """最多 5 位小数、去尾零（与现有 YAML 数字风格一致）。"""
    s = f"{v:.5f}".rstrip("0").rstrip(".")
    return s if s else "0"


def main() -> None:
    # 区间集合镜像后升序 = 行车链顺序
    loop_ranges = sorted(
        (round(TWO_L - ge, 5), round(TWO_L - gs, 5)) for gs, ge in D_GEO_RANGES
    )
    assert len(loop_ranges) == len(D_CHAIN)
    for i in range(1, len(loop_ranges)):
        assert abs(loop_ranges[i][0] - loop_ranges[i - 1][1]) < 1e-6, "区间不连续"

    folded = {}
    print("# ========== 1) line-m9.yaml D 区段（loop-km，行车序 D47→D03） ==========")
    for (seg_id, frm, to, raw), (ls, le) in zip(D_CHAIN, loop_ranges):
        folded[seg_id] = (ls, le)
        print(f"  - id: {seg_id}")
        print(f"    from: {frm}")
        print(f"    to: {to}")
        print(f"    start_meters: {fmt(ls)}")
        print(f"    end_meters: {fmt(le)}")
        if raw:
            print(f"    raw_segment_id: {raw}")
        else:
            print("    raw_segment_id: 0")
        print("    speed_limit_meters_per_second: 22.2")
        print("    track: down")
        print()

    print("# ========== 2) line-m9.yaml SIG-D 信号（loop_start + 5m） ==========")
    for sig_id, seg_id in SIG_D_GROUPS:
        ls, _ = folded[seg_id]
        print(f"  - id: {sig_id}")
        print(f"    name: {seg_id.rstrip('abcde') if len(seg_id) > 3 else seg_id}入口")
        print(f"    position_meters: {fmt(round(ls + 5.0, 5))}")
        print("    direction: FORWARD")
        print(f"    segment_id: {seg_id}")
        print()

    print("# ========== 3) line-m9.yaml S2xx 车站（down 侧拆点，loop-km） ==========")
    s2 = []
    for sid, name, (dc, dl, dr, side) in STATIONS_GEO:
        s2id = "S2" + sid[2:]
        c = round(TWO_L - dc, 5)
        left = round(TWO_L - dr, 5)
        right = round(TWO_L - dl, 5)
        s2.append((c, s2id, name, left, right, side))
    s2.sort(key=lambda r: r[0])  # loop-km 升序 = 行车序 S213→S201
    for c, s2id, name, left, right, side in s2:
        print(f"  - id: {s2id}")
        print(f"    name: {name}")
        print(f"    position_meters: {fmt(c)}")
        print("    platforms:")
        print("      - track: down")
        print(f"        center_meters: {fmt(c)}")
        print(f"        stop_left_meters: {fmt(left)}")
        print(f"        stop_right_meters: {fmt(right)}")
        print(f"        side: {side}")

    print()
    print("# ========== 4) dispatch-plan.yaml stations（S2xx） ==========")
    for c, s2id, _name, _l, _r, _side in s2:
        print(f"  - id: {s2id}")
        print(f"    positionMeters: {fmt(c)}")
        print("    platformCapacity: 1000")

    print()
    print("# ========== 5) dispatch-plan.yaml segments（D 段组级，loop-km） ==========")
    groups: dict[str, list[tuple[float, float]]] = {}
    for seg_id, (ls, le) in folded.items():
        g = seg_id.rstrip("abcde") if len(seg_id) > 3 else seg_id
        groups.setdefault(g, []).append((ls, le))
    grows = sorted(
        ((min(a for a, _ in v), max(b for _, b in v), g) for g, v in groups.items()),
        key=lambda r: r[0],
    )
    for ls, le, g in grows:
        print(f"  - id: {g}")
        print(f"    startMeters: {fmt(ls)}")
        print(f"    endMeters: {fmt(le)}")
        print("    speedLimitMps: 22.2")

    print()
    print(f"# L = {fmt(L)}, 2L = {fmt(TWO_L)}")
    print(f"# XLIB = [{fmt(L)}, {fmt(folded['D47'][0])}]  (U48末端 → D47起点)")
    print(f"# down 出发 offset = {fmt(folded['D47'][0])}")


if __name__ == "__main__":
    main()
