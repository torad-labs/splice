"""M1-52: the D7 sweep, enumerated FROM THE SOURCE.

The denominator is every `color:` declaration in webui/src/**.css, not the pairs that happened
to render in a capture. probe-ink.mjs measures what it finds rendered, so a rule on a page
nobody captured is invisible to it -- and both known D7 instances were found by eye.
"""
import re, json, pathlib, collections, sys

ROOT = pathlib.Path('.')
SRC  = list((ROOT/'webui/src').rglob('*.css'))

# ---------- token values per theme, from tokens.css's two blocks ----------
tok = (ROOT/'webui/src/shared/tokens.css').read_text().splitlines()
def block(a, b):
    d = {}
    for l in tok[a-1:b]:
        m = re.match(r'\s*(--[a-z0-9-]+)\s*:\s*([^;]+);', l)
        if m: d[m.group(1)] = m.group(2).strip()
    return d
BASE  = block(30, 152)
DARK  = {**BASE, **block(161, 263)}
LIGHT = {**BASE, **block(276, 397)}
THEMES = {'dark': DARK, 'light': LIGHT}

def resolve(tv, name, depth=0):
    v = tv.get(name)
    if v is None or depth > 6: return None
    m = re.match(r'var\((--[a-z0-9-]+)', v)
    if m: return resolve(tv, m.group(1), depth+1)
    return v

def rgba(s):
    if s is None: return None
    s = s.strip()
    m = re.match(r'#([0-9a-fA-F]{6})$', s)
    if m:
        h = m.group(1); return (int(h[0:2],16), int(h[2:4],16), int(h[4:6],16), 1.0)
    m = re.match(r'rgba?\(([^)]+)\)', s)
    if m:
        p = [float(x) for x in re.split(r'[\s,/]+', m.group(1).strip()) if x]
        return (p[0], p[1], p[2], p[3] if len(p) > 3 else 1.0)
    return None

def over(f, b):
    return (f[3]*f[0]+(1-f[3])*b[0], f[3]*f[1]+(1-f[3])*b[1], f[3]*f[2]+(1-f[3])*b[2], 1.0)

def lum(c):
    def f(u):
        u /= 255.0
        return u/12.92 if u <= 0.03928 else ((u+0.055)/1.055)**2.4
    return .2126*f(c[0]) + .7152*f(c[1]) + .0722*f(c[2])

def ratio(a, b):
    x, y = lum(a), lum(b)
    return (max(x,y)+.05)/(min(x,y)+.05)

# ---------- plane families ----------
FAM = {}
for t in ('--ink','--ink-mute','--ink-strong','--ink-body'):       FAM[t] = 'ROOM-INK'
for t in ('--strip-ink','--strip-ink-mute'):                        FAM[t] = 'PAPER-INK'
for t in ('--scope-ink',):                                          FAM[t] = 'SCOPE-INK'
for t in ('--room','--room-deep','--bay'):                          FAM[t] = 'ROOM'
for t in ('--strip','--strip-field'):                               FAM[t] = 'PAPER'
for t in ('--plate',):                                              FAM[t] = 'PLATE'
for t in ('--scope',):                                              FAM[t] = 'SCOPE'
for t in ('--ghost','--ghost-paper','--ghost-rule','--ghost-ink'):  FAM[t] = 'GHOST'
INK_OK = {'ROOM-INK': {'ROOM'}, 'PAPER-INK': {'PAPER','PLATE'}, 'SCOPE-INK': {'SCOPE'}}

# ---------- parse rules ----------
rules = []
for p in SRC:
    txt = p.read_text()
    txt = re.sub(r'/\*.*?\*/', lambda m: '\n'*m.group(0).count('\n'), txt, flags=re.S)
    for m in re.finditer(r'([^{}]+)\{([^{}]*)\}', txt):
        sel, body = m.group(1).strip(), m.group(2)
        line = txt[:m.start()].count('\n') + 1
        if sel.startswith('@') or sel.startswith(':root'): continue
        c = re.search(r'(?:^|[^-\w])color\s*:\s*var\((--[a-z0-9-]+)', body)
        b = re.search(r'background(?:-color)?\s*:\s*(?:[^;]*?)var\((--[a-z0-9-]+)', body)
        if not c: continue
        rules.append({'file': str(p), 'line': line, 'sel': sel, 'ink': c.group(1),
                      'bg': b.group(1) if b else None})

# ---------- measured grounds, by class, from the M1-47 run ----------
meas = collections.defaultdict(lambda: collections.defaultdict(collections.Counter))
mp = ROOT/'webui/.impeccable/review/ink/ink-measurements.json'
if mp.exists():
    for r in json.loads(mp.read_text()):
        for cls in str(r['cls']).split():
            meas[cls][r['theme']][(r['ink'], r['ground'], r['ratio'])] += 1

ANC = {'dark': collections.Counter(), 'light': collections.Counter()}
if mp.exists():
    for _r in json.loads(mp.read_text()):
        for _c in set(_r.get('anc') or str(_r['cls']).split()):
            ANC[_r['theme']][(_c, _r['ink'], _r['ground'], _r['ratio'])] += 1

def classes_of(sel):
    return re.findall(r'\.([A-Za-z][\w-]*)', sel)

print(f"DENOMINATOR: {len(rules)} `color: var(--token)` rules across {len(SRC)} CSS files\n")

rows = []
for r in rules:
    ikf = FAM.get(r['ink'])
    if ikf is None or ikf not in INK_OK:
        rows.append({**r, 'kind': 'NOT-AN-INK-ROLE', 'ground': r['bg'], 'ev': 'token is not a text-ink role'})
        continue
    if r['bg']:
        gf = FAM.get(r['bg'], 'OTHER')
        ok = gf in INK_OK[ikf]
        ev = {}
        for th, tv in THEMES.items():
            ci, cg = rgba(resolve(tv, r['ink'])), rgba(resolve(tv, r['bg']))
            if ci and cg:
                if ci[3] < 1: ci = over(ci, cg)
                ev[th] = round(ratio(ci, cg), 2)
        rows.append({**r, 'kind': 'SAME-RULE', 'ground': r['bg'], 'okfam': ok, 'ev': ev})
        continue
    # no ground in the rule: fall back to what rendered, by the node's own class OR BY ANCESTRY.
    # An inherited colour is set on an element that parents no text node of its own.
    want = {}
    for th, tv in THEMES.items():
        c = rgba(resolve(tv, r['ink']))
        if c: want[th] = '#%02X%02X%02X' % (round(c[0]), round(c[1]), round(c[2]))
    seen = {}
    sel_classes = set(classes_of(r['sel']))
    for th in ('dark', 'light'):
        for (cls, ink, gnd, rt), n in ANC[th].items():
            if cls in sel_classes and (th not in want or ink.upper() == want[th]):
                seen.setdefault(th, []).append((gnd, rt, n))
    rows.append({**r, 'kind': 'RENDERED' if seen else 'UNRESOLVED', 'ground': None, 'ev': seen})

json.dump(rows, open(sys.argv[1], 'w'), indent=1)
k = collections.Counter(x['kind'] for x in rows)
for key, n in k.most_common(): print(f'  {key:16s} {n}')
