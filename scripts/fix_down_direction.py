"""
Fix D-segment direction: swap from<->to on all track:down segments,
fix R_DOWN route, add S201-S213 reversed stations.
"""
import re

with open('config/line-m9.yaml', 'r', encoding='utf-8') as f:
    text = f.read()

L = 16489.02
swaps = 0

# ---- 1. Swap from<->to on all track: down segments ----
# Match each D segment block and swap from/to
def swap_from_to(m):
    global swaps
    block = m.group(0)
    swaps += 1
    # Extract from and to
    fm = re.search(r'    from: (\S+)', block)
    tm = re.search(r'    to: (\S+)', block)
    if fm and tm:
        from_val, to_val = fm.group(1), tm.group(1)
        block = block.replace(f'    from: {from_val}', '__TMP__')
        block = block.replace(f'    to: {to_val}', f'    from: {to_val}')
        block = block.replace('__TMP__', f'    to: {from_val}')
    return block

# Match each D segment: from "  - id: D..." to next "  - id: " or section boundary
text = re.sub(
    r'  - id: D\d+[a-z]*\n(?:    .*\n)*?    track: down\n',
    swap_from_to,
    text
)
print(f'Swapped from/to on {swaps} D segments')

# ---- 2. Fix R_DOWN route ----
# Old: axle_section_ids: [D47, D43, D36a, ..., D03]
# New: axle_section_ids: [D03, D04, D07, D11, D14, D17a, D17b, D17c, D17d, D21a, D21b, D21c, D24, D28a, D28b, D28c, D28d, D28e, D36a, D36b, D36c, D43, D47]
down_ids_increasing = [
    'D03', 'D04', 'D07', 'D11', 'D14',
    'D17a', 'D17b', 'D17c', 'D17d',
    'D21a', 'D21b', 'D21c', 'D24',
    'D28a', 'D28b', 'D28c', 'D28d', 'D28e',
    'D36a', 'D36b', 'D36c', 'D43', 'D47'
]
old_route = re.search(r'  - id: R_DOWN\n    name: 下行正线[^\n]*\n    type: MAIN\n    start_signal: \S+\n    end_signal: \S+\n    axle_section_ids: \[[^\]]*\]', text)
if old_route:
    new_route = (
        f'  - id: R_DOWN\n'
        f'    name: 下行正线（郭公庄→国家图书馆）\n'
        f'    type: MAIN\n'
        f'    start_signal: SIG-D03\n'
        f'    end_signal: SIG-D47\n'
        f'    axle_section_ids: [{", ".join(down_ids_increasing)}]'
    )
    text = text.replace(old_route.group(0), new_route)
    print(f'Updated R_DOWN route')

# ---- 3. Add S201-S213 reversed stations ----
s201_213 = []
for i in range(1, 14):  # 101-113 -> 201-213
    sid = f'S{i+100}'
    m = re.search(rf'  - id: {sid}\n    name: [^\n]+\n    position_meters: ([\d.]+)', text)
    if m:
        rev = L - float(m.group(1))
        s201_213.append(f'  - id: S{i+200}\n    name: S{i+200}\n    position_meters: {rev:.6f}\n')

text = text.replace('\nnodes:', '\n  # S201-S213 下行站\n' + ''.join(s201_213) + '\nnodes:', 1)
print(f'Added {len(s201_213)} reversed stations')

with open('config/line-m9.yaml', 'w', encoding='utf-8') as f:
    f.write(text)

# ---- Verify ----
nt = open('config/line-m9.yaml', 'r', encoding='utf-8').read()

# Check forward chain
import re as re2
d_from_to = list(re2.finditer(r'  - id: (D\d+[a-z]*)\n    from: (\S+)\n    to: (\S+)', nt))

print(f'\nForward chain verification (D segs, km-increasing):')
chain_ok = 0
chain_broken = 0
for m in d_from_to:
    sid, fr, to = m.group(1), m.group(2), m.group(3)
    nxt = [(s, f) for s, f, _ in re2.findall(r'  - id: (D\d+[a-z]*)\n    from: (\S+)', nt) if f == to and s != sid]
    mark = '✓' if nxt else ('END' if sid == 'D47' else '✗')
    if mark == '✓': chain_ok += 1
    elif mark == 'END': pass
    else: chain_broken += 1
    print(f'  {sid:6s} {fr}->{to}  -> {[f"{s}" for s,_ in nxt]} {mark}')

print(f'\nChain: {chain_ok} ok, {chain_broken} broken')

# Check R_DOWN
rdown = re2.search(r'  - id: R_DOWN\n.*?\]', nt, re2.DOTALL)
print(f'\nR_DOWN: {rdown.group(0)[:200] if rdown else "NOT FOUND"}')
print(f'S201: {len(re2.findall(r"id: S2\d\d", nt))} stations')
print('DONE')
