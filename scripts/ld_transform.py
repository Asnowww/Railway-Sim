"""
纯追加 LD 段 + LD 节点 + LD 信号 + LD 站 + R_DOWN 路由
不动任何现有 D 段
"""
import re

with open('config/line-m9.yaml', 'r', encoding='utf-8') as f:
    text = f.read()

L = 16489.02

# Parse original D segments (split subsegments)
seg_pat = re.compile(
    r'  - id: (D\d+[a-z]*)\n    from: (\w+)\n    to: (\w+)\n'
    r'    start_meters: ([\d.]+)\n    end_meters: ([\d.]+)\n'
    r'    raw_segment_id: (\d+)\n    speed_limit_meters_per_second: ([\d.]+)\n    track: down\n'
)
dsegs = []
for m in seg_pat.finditer(text):
    dsegs.append((m.group(1), m.group(2), m.group(3),
                  float(m.group(4)), float(m.group(5)),
                  int(m.group(6)), float(m.group(7))))
dsegs.sort(key=lambda x: x[3])  # sort by start km
print(f'Found {len(dsegs)} D segments')

# Build reversed km chain: last D segment first in LD
ld_segs = []
offset = L
for sid, fr, to, st, en, raw, spd in reversed(dsegs):
    length = en - st
    new_st = offset
    new_en = offset + length
    new_id = 'LD' + sid[1:]  # e.g. D47 -> LD47
    new_fr = 'LDN' + str(len(ld_segs)).zfill(2)
    new_to = 'LDN' + str(len(ld_segs) + 1).zfill(2)
    ld_segs.append((new_id, new_fr, new_to, new_st, new_en, 0, spd))
    offset = new_en

# Final node
last_node = 'LDN' + str(len(ld_segs)).zfill(2)

# Print chain
print(f'\nLD segment chain ({len(ld_segs)} segments):')
for s in ld_segs[:3]:
    print(f'  {s[0]:8s} {s[1]}->{s[2]} km={s[3]:.1f}-{s[4]:.1f} len={s[4]-s[3]:.1f}')
print(f'  ...')
for s in ld_segs[-3:]:
    print(f'  {s[0]:8s} {s[1]}->{s[2]} km={s[3]:.1f}-{s[4]:.1f} len={s[4]-s[3]:.1f}')

# Verify forward chain
for i in range(len(ld_segs)-1):
    current_to = ld_segs[i][2]
    next_from = ld_segs[i+1][1]
    if current_to != next_from:
        print(f'CHAIN BREAK at {ld_segs[i][0]}: to={current_to} != next from={next_from}')
print('Chain verified')

# Build YAML blocks
# LD nodes
node_lines = []
for i in range(len(ld_segs)):
    node_lines.append(f'  - id: LDN{i:02d}\n    name: LDN{i:02d}\n')
node_lines.append(f'  - id: LDN{len(ld_segs):02d}\n    name: LDN{len(ld_segs):02d}\n')

# LD segments
seg_lines = []
for s in ld_segs:
    seg_lines.append(
        f'  - id: {s[0]}\n    from: {s[1]}\n    to: {s[2]}\n'
        f'    start_meters: {s[3]:.6f}\n    end_meters: {s[4]:.6f}\n'
        f'    raw_segment_id: 0\n    speed_limit_meters_per_second: {s[6]:.1f}\n    track: down\n'
    )

# LD signals (mirror original SIG-D positions)
sig_pat = re.compile(
    r'  - id: SIG-(D\d+[a-z]*)\n    name: ([^\n]+)\n'
    r'    position_meters: ([\d.]+)\n    direction: (\w+)\n    segment_id: (D\d+[a-z]*)\n'
)
sig_lines = []
for m in sig_pat.finditer(text):
    orig_pos = float(m.group(3))
    rev_pos = L + (L - orig_pos)
    # Find which LD segment contains this position
    for s in ld_segs:
        if s[3] <= rev_pos < s[4]:
            sig_lines.append(
                f'  - id: SIG-L{m.group(1)}\n    name: L{m.group(2)}\n'
                f'    position_meters: {rev_pos:.2f}\n    direction: {m.group(4)}\n    segment_id: {s[0]}\n'
            )
            break

# LD stations
st_lines = []
for i in range(1, 14):
    sid = f'S{i+100}'
    m = re.search(rf'  - id: {sid}\n    name: [^\n]+\n    position_meters: ([\d.]+)', text)
    if m:
        orig_km = float(m.group(1))
        rev_km = L + (L - orig_km)
        st_lines.append(f'  - id: S{i+200}\n    name: S{i+200}\n    position_meters: {rev_km:.6f}\n')

# R_RDOWN route (NEW route for loop down)
axle_ids = [s[0] for s in ld_segs]
route_line = (
    f'  - id: R_RDOWN\n    name: 下行环路\n    type: MAIN\n'
    f'    start_signal: SIG-LD47\n    end_signal: SIG-LD03\n'
    f'    axle_section_ids: [{", ".join(axle_ids)}]\n'
)

# INSERT
text = text.replace('    name: 下行节点12\n', '    name: 下行节点12\n' + ''.join(node_lines), 1)
text = text.replace('\nnodes:', '\n  # LD stations\n' + ''.join(st_lines) + '\nnodes:', 1)
text = text.replace('    track: crossover\n\nswitches:', '    track: crossover\n  # LD segments\n' + ''.join(seg_lines) + '\nswitches:', 1)
text = text.replace('    segment_id: XLIB\n\nroutes:', '    segment_id: XLIB\n  # LD signals\n' + ''.join(sig_lines) + '\nroutes:', 1)
text = text.replace('    axle_section_ids: [XGGZ, U02]\n', f'    axle_section_ids: [XGGZ, U02]\n{route_line}\n', 1)

with open('config/line-m9.yaml', 'w', encoding='utf-8') as f:
    f.write(text)

# Verify
nt = open('config/line-m9.yaml', 'r', encoding='utf-8').read()
print(f'\nLD segs: {len(re.findall(r"  - id: LD\d", nt))} (expect {len(ld_segs)})')
print(f'SIG-LD: {len(re.findall(r"SIG-LD", nt))}')
print(f'S2xx: {len(re.findall(r"id: S2\d\d", nt))} (expect 13)')
print(f'R_RDOWN: {"R_RDOWN" in nt}')
print(f'LDN nodes: {len(re.findall(r"id: LDN", nt))}')
print('DONE')
