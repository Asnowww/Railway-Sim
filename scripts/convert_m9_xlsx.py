# -*- coding: utf-8 -*-
"""
9号线真实线路数据转换：视景公里标 xlsx → config/line-m9.yaml

数据源：视景系统公里标最新数据+站台位置20260703.xlsx（OOXML strict 格式，
openpyxl 无法打开——报 0 sheets，本脚本手写解析 purl.oclc.org 命名空间）。

生成内容：
- 上行(track=up) 13 段 / 下行(track=down) 12 段，raw_segment_id=视景边号
- 13 站，position=上行 StationKm；platforms 双向（含停车窗口/站台侧）
- 每段入口信号机 SIG-{segId}
- 两端折返渡线 XGGZ/XLIB + 道岔 + 折返进路（R_TB_*）
- 正线进路 R_UP / R_DOWN

用法：python scripts/convert_m9_xlsx.py [xlsx路径] [输出yaml路径]
"""
import sys
import zipfile
import re
import io
import xml.etree.ElementTree as ET

DEFAULT_XLSX = r'C:\Users\patri\xwechat_files\wxid_2nwes58frsrb32_472c\temp\RWTemp\2026-07\649c8c9df5d1cdcab6ca22d0f3b0cb8b\视景系统公里标最新数据+站台位置20260703.xlsx'
DEFAULT_OUT = 'config/line-m9.yaml'
M = '{http://purl.oclc.org/ooxml/spreadsheetml/main}'
R = '{http://purl.oclc.org/ooxml/officeDocument/relationships}'

DEFAULT_SPEED = 22.2  # m/s ≈ 80km/h；xlsx 未提供限速，后续有数据再精化
CROSSOVER_SPEED = 8.3  # 渡线 30km/h


def read_strict_xlsx(path):
    z = zipfile.ZipFile(path)
    shared = []
    root = ET.fromstring(z.read('xl/sharedStrings.xml'))
    for si in root.findall(f'{M}si'):
        shared.append(''.join(t.text or '' for t in si.iter(f'{M}t')))
    wb = ET.fromstring(z.read('xl/workbook.xml'))
    rels = ET.fromstring(z.read('xl/_rels/workbook.xml.rels'))
    relmap = {rel.get('Id'): rel.get('Target') for rel in rels}
    sheets = []
    for sh in wb.iter(f'{M}sheet'):
        sheets.append((sh.get('name'), relmap[sh.get(f'{R}id')]))

    def col_idx(ref):
        col = 0
        for ch in re.match(r'([A-Z]+)', ref).group(1):
            col = col * 26 + (ord(ch) - 64)
        return col - 1

    out = {}
    for name, target in sheets:
        root = ET.fromstring(z.read('xl/' + target.replace('xl/', '').lstrip('/')))
        rows = []
        for row in root.iter(f'{M}row'):
            cells = {}
            for c in row.findall(f'{M}c'):
                t, v = c.get('t'), c.find(f'{M}v')
                val = None
                if t == 's' and v is not None:
                    val = shared[int(v.text)]
                elif v is not None:
                    val = v.text
                if val is not None:
                    cells[col_idx(c.get('r'))] = val
            if cells:
                rows.append([cells.get(i, '') for i in range(max(cells) + 1)])
        out[name] = rows
    return out


def main():
    xlsx = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_XLSX
    out_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT
    sheets = read_strict_xlsx(xlsx)
    names = list(sheets.keys())
    up_rows = sheets[names[0]][1:]    # sheet1 上行视景边号公里标
    down_rows = sheets[names[1]][1:]  # sheet2 下行视景边号公里标
    plat_rows = sheets[names[2]][1:]  # sheet3 站台位置

    up_edges = [(int(r[0]), float(r[1]), float(r[2])) for r in up_rows]
    down_edges = [(int(r[0]), float(r[1]), float(r[2])) for r in down_rows]
    up_edges.sort(key=lambda e: e[1])
    down_edges.sort(key=lambda e: e[1])

    # 站台：Track 1=上行(up)，Track 0=下行(down)；同 StaID 归并
    stations = {}
    for r in plat_rows:
        track = 'up' if r[0] == '1' else 'down'
        sta_id, name = int(r[1]), r[2]
        entry = stations.setdefault(sta_id, {'name': name, 'platforms': {}})
        entry['platforms'][track] = {
            'center': float(r[3]), 'left': float(r[4]), 'right': float(r[5]), 'side': r[6]
        }

    line_len = max(up_edges[-1][2], down_edges[-1][2])

    w = io.StringIO()
    def p(s=''):
        w.write(s + '\n')

    p('# 北京地铁9号线（郭公庄—国家图书馆）真实线路数据')
    p('# 由 scripts/convert_m9_xlsx.py 从《视景系统公里标最新数据+站台位置20260703.xlsx》生成，勿手改')
    p(f'# 上行 {len(up_edges)} 边 / 下行 {len(down_edges)} 边 / {len(stations)} 站 / 全线 {line_len:.2f}m')
    p('# 约定：track=up 对应 Track1/上行视景边表；track=down 对应 Track0/下行视景边表')
    p('# raw_segment_id = 视景系统 UDP 边号（segNo）')
    p('# 注意：下行股道公里标与上行同源（郭公庄端为 0），下行行车方向为里程递减；')
    p('#       现阶段动车运行在上行股道验证，下行股道用于态势展示/视景映射/折返拓扑。')
    p()
    p('line:')
    p('  id: m9')
    p('  name: 北京地铁9号线')
    p(f'  length_meters: {line_len:.2f}')
    p(f'  default_speed_limit_meters_per_second: {DEFAULT_SPEED}')
    p()

    # ---- 车站 ----
    p('stations:')
    for sta_id in sorted(stations):
        st = stations[sta_id]
        up_p = st['platforms'].get('up')
        center = up_p['center'] if up_p else next(iter(st['platforms'].values()))['center']
        p(f'  - id: S{sta_id}')
        p(f'    name: {st["name"]}')
        p(f'    position_meters: {center}')
        p('    platforms:')
        for track in ('up', 'down'):
            pl = st['platforms'].get(track)
            if not pl:
                continue
            p(f'      - track: {track}')
            p(f'        center_meters: {pl["center"]}')
            p(f'        stop_left_meters: {pl["left"]}')
            p(f'        stop_right_meters: {pl["right"]}')
            p(f'        side: {pl["side"]}')
    p()

    # ---- 节点 ----
    p('nodes:')
    for i in range(len(up_edges) + 1):
        p(f'  - id: NU{i:02d}')
        p(f'    name: 上行节点{i:02d}')
    for i in range(len(down_edges) + 1):
        p(f'  - id: ND{i:02d}')
        p(f'    name: 下行节点{i:02d}')
    p()

    # ---- 区段 ----
    p('segments:')
    p('  # ===== 上行（郭公庄→国家图书馆，里程递增） =====')
    up_seg_ids = []
    for i, (edge, start, end) in enumerate(up_edges):
        seg_id = f'U{edge:02d}'
        up_seg_ids.append(seg_id)
        p(f'  - id: {seg_id}')
        p(f'    from: NU{i:02d}')
        p(f'    to: NU{i + 1:02d}')
        p(f'    start_meters: {start}')
        p(f'    end_meters: {end}')
        p(f'    raw_segment_id: {edge}')
        p(f'    speed_limit_meters_per_second: {DEFAULT_SPEED}')
        p('    track: up')
    p('  # ===== 下行（国家图书馆→郭公庄，行车方向=里程递减） =====')
    p('  # from/to 按行车方向（高里程节点→低里程节点），保证进路 axle 链连通性校验通过；')
    p('  # start/end_meters 仍为升序数值（引擎 1D 投影约定 start<end）。')
    down_seg_ids = []
    for i, (edge, start, end) in enumerate(down_edges):
        seg_id = f'D{edge:02d}'
        down_seg_ids.append(seg_id)
        p(f'  - id: {seg_id}')
        p(f'    from: ND{i + 1:02d}')
        p(f'    to: ND{i:02d}')
        p(f'    start_meters: {start}')
        p(f'    end_meters: {end}')
        p(f'    raw_segment_id: {edge}')
        p(f'    speed_limit_meters_per_second: {DEFAULT_SPEED}')
        p('    track: down')
    # 折返渡线（虚拟短段，仅拓扑/进路用）
    nu_last = f'NU{len(up_edges):02d}'
    nd_last = f'ND{len(down_edges):02d}'
    up_first, up_last = up_seg_ids[0], up_seg_ids[-1]
    down_first, down_last = down_seg_ids[0], down_seg_ids[-1]
    p('  # ===== 折返渡线（虚拟段）=====')
    p('  # 郭公庄端：下行到达(ND00)→上行出发(NU00)；国图端：上行到达(NU_last)→下行出发(ND_last)')
    p('  - id: XGGZ')
    p('    from: ND00')
    p('    to: NU00')
    p(f'    start_meters: 0')
    p(f'    end_meters: 25')
    p(f'    speed_limit_meters_per_second: {CROSSOVER_SPEED}')
    p('    track: crossover')
    p('  - id: XLIB')
    p(f'    from: {nu_last}')
    p(f'    to: {nd_last}')
    p(f'    start_meters: {line_len - 25:.2f}')
    p(f'    end_meters: {line_len:.2f}')
    p(f'    speed_limit_meters_per_second: {CROSSOVER_SPEED}')
    p('    track: crossover')
    p()

    # ---- 道岔 ----
    p('switches:')
    p('  - id: SW_GGZ')
    p('    node: ND00')
    p(f'    normal_target: {down_first}')
    p('    reverse_target: XGGZ')
    p('    default_position: NORMAL')
    p('  - id: SW_LIB')
    p(f'    node: {nu_last}')
    p(f'    normal_target: {up_last}')
    p('    reverse_target: XLIB')
    p('    default_position: NORMAL')
    p()

    # ---- 信号机（每段入口一个） ----
    p('signals:')
    all_edges = ([(sid, s, 'up') for sid, (e, s, _) in zip(up_seg_ids, up_edges)]
                 + [(sid, s, 'down') for sid, (e, s, _) in zip(down_seg_ids, down_edges)]
                 + [('XGGZ', 0.0, 'crossover'), ('XLIB', line_len - 25, 'crossover')])
    for seg_id, start, track in all_edges:
        p(f'  - id: SIG-{seg_id}')
        p(f'    name: {seg_id}入口')
        p(f'    position_meters: {start + 5:.2f}')
        p('    direction: FORWARD')
        p(f'    segment_id: {seg_id}')
    p()

    # ---- 进路 ----
    p('routes:')
    p('  - id: R_UP')
    p('    name: 上行正线（郭公庄→国家图书馆）')
    p('    type: MAIN')
    p(f'    start_signal: SIG-{up_first}')
    p(f'    end_signal: SIG-{up_last}')
    p(f'    axle_section_ids: [{", ".join(up_seg_ids)}]')
    p('  - id: R_DOWN')
    p('    name: 下行正线（国家图书馆→郭公庄）')
    p('    type: MAIN')
    p(f'    start_signal: SIG-{down_last}')
    p(f'    end_signal: SIG-{down_first}')
    p(f'    axle_section_ids: [{", ".join(reversed(down_seg_ids))}]')
    p('  - id: R_TB_LIB')
    p('    name: 国家图书馆折返（上行→下行）')
    p('    type: BRANCH')
    p('    start_signal: SIG-XLIB')
    p(f'    end_signal: SIG-{down_last}')
    p(f'    axle_section_ids: [XLIB, {down_last}]')  # 不含到达段，避免道岔双位矛盾
    p('  - id: R_TB_GGZ')
    p('    name: 郭公庄折返（下行→上行）')
    p('    type: BRANCH')
    p('    start_signal: SIG-XGGZ')
    p(f'    end_signal: SIG-{up_first}')
    p(f'    axle_section_ids: [XGGZ, {up_first}]')  # 不含到达段，避免道岔双位矛盾

    with open(out_path, 'w', encoding='utf-8') as f:
        f.write(w.getvalue())

    print(f'OK -> {out_path}')
    print(f'  上行段: {up_seg_ids}')
    print(f'  下行段: {down_seg_ids}')
    print(f'  车站: {[(sid, stations[sid]["name"]) for sid in sorted(stations)]}')
    print(f'  全线: {line_len:.2f} m')


if __name__ == '__main__':
    main()
