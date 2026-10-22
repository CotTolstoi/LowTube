"""Audit embedded catalogs and statically referenced UI translation keys. No network."""
import json
import re
from pathlib import Path
root = Path(__file__).resolve().parents[1]
source = (root / 'src/I18n.cs').read_text(encoding='utf-8-sig')
s = r'"(?:\\.|[^"\\])*"'
rows = re.findall(r'\{('+s+r'),new\[\]\{('+s+r'),('+s+r')\}\}', source)
catalog = {json.loads(k): (json.loads(uk), json.loads(ru)) for k, uk, ru in rows}
assert len(catalog) == len(rows) and len(catalog) >= 160
for key, translations in catalog.items():
    for value in translations:
        assert value.strip(), key
        assert sorted(re.findall(r'\{\d+(?:[^}]*)\}', key)) == sorted(re.findall(r'\{\d+(?:[^}]*)\}', value)), key
used = set()
for path in (root / 'src').glob('*.cs'):
    if path.name == 'I18n.cs':
        continue
    text = path.read_text(encoding='utf-8-sig')
    for start in re.finditer(r'(?:L\.T|Ui\.Label|Ui\.Button)\(', text):
        # Parse the first argument, including conditional expressions.
        i = start.end()
        depth = 0
        while i < len(text):
            if text[i] == '"':
                literal = re.match(s, text[i:])
                assert literal, (path, i)
                # A ternary condition can compare a stable container ID, e.g. Kind == "MP3".
                if not text[start.end():i].rstrip().endswith(('==', '!=')):
                    used.add(json.loads(literal.group()))
                i += literal.end()
                continue
            c = text[i]
            if c == '(':
                depth += 1
            elif c == ')':
                if depth == 0:
                    break
                depth -= 1
            elif c == ',' and depth == 0:
                break
            i += 1
    for pattern in [r'NavButton\(("(?:\\.|[^"\\])*"),',r'Add\(panel,("(?:\\.|[^"\\])*"),']:
        used.update(json.loads(key) for key in re.findall(pattern,text))
    # Ukrainian/Russian user captions must live in the catalog, except language names.
    tokens = re.findall(r'//[^\n]*|/\*[\s\S]*?\*/|\'(?:\\.|[^\'\\])+\'|@"(?:""|[^"])*"|'+s, text)
    for literal in tokens:
        if not literal.startswith('"'):
            continue
        value = json.loads(literal)
        if re.search('[А-Яа-яІіЇїЄєҐґ]', value):
            assert value in ('Українська', 'Русский') or (path.name == 'LocalFeeds.cs' and value in ('українською', 'на русском')), (path, value)
assert used <= catalog.keys(), sorted(used - catalog.keys())
print('PASS:', len(catalog), 'complete EN/UK/RU entries with matching placeholders')
print('PASS:', len(used), 'referenced translation keys found; no hardcoded Cyrillic captions')
