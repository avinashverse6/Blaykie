import glob, hashlib, os, re, struct, subprocess, sys, traceback, zipfile

def report_error(kind, value, tb):
    print("::error::" + " | ".join(traceback.format_exception(kind, value, tb)).replace("\n", " ").replace("%", "%25"))
    sys.__excepthook__(kind, value, tb)
sys.excepthook = report_error

apk, baseline, aapt = sys.argv[1:]
expected_assets = {
    'assets/csl/citeproc.js', 'assets/csl/locales-en-US.xml',
    'assets/licenses/AGPL-3.0', 'assets/licenses/Apache-2.0',
    'assets/dexopt/baseline.prof', 'assets/dexopt/baseline.profm',
}
sources = open('app/src/debug/assets/csl/SOURCES.md').read()
hashes = {name: re.search(r'\| ' + re.escape(name) + r' \|[^\n]*?\| ([0-9a-f]{64}) \|', sources).group(1)
          for name in ('citeproc.js', 'locales-en-US.xml')}

def manifest(path):
    text = subprocess.check_output([aapt, 'dump', 'xmltree', path, 'AndroidManifest.xml'], text=True)
    components, permissions, current = [], set(), None
    for line in text.splitlines():
        element = re.match(r'^(\s*)E: (\S+)', line)
        if element:
            depth, tag = len(element[1]), element[2]
            if current and depth <= current['depth']:
                components.append(current); current = None
            if tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider'):
                current = {'depth': depth, 'tag': tag, 'name': None, 'exported': None, 'filter': False}
            if current and tag == 'intent-filter': current['filter'] = True
            permission = tag in ('uses-permission', 'uses-permission-sdk-23')
        attr = re.search(r'A: android:(name|exported)\([^)]*\)=(.*)', line)
        if attr:
            key, value = attr.groups()
            string = re.search(r'"([^"]+)"', value)
            if permission and key == 'name' and string: permissions.add(string[1])
            if current and len(line) - len(line.lstrip()) == current['depth'] + 2:
                if key == 'name' and string: current['name'] = string[1]
                if key == 'exported': current['exported'] = not value.endswith('0x0')
    if current: components.append(current)
    exported = {(c['tag'], c['name']) for c in components if c['exported'] is True or
                (c['exported'] is None and c['filter'])}
    assert all(c['name'] for c in components), components
    return permissions, exported, components

def classes(data):
    count, off = struct.unpack_from('<II', data, 56)
    strings = []
    for i in range(count):
        pos = struct.unpack_from('<I', data, off + 4*i)[0]
        while data[pos] & 128: pos += 1
        pos += 1
        end = data.index(b'\0', pos)
        strings.append(data[pos:end].decode('utf-8', 'replace'))
    count, off = struct.unpack_from('<II', data, 64)
    types = [strings[struct.unpack_from('<I', data, off + 4*i)[0]] for i in range(count)]
    count, off = struct.unpack_from('<II', data, 96)
    return [types[struct.unpack_from('<I', data, off + 32*i)[0]] for i in range(count)]

def notice(label, items):
    for i in range(0, len(items), 30):
        print('::notice::' + label + ': ' + ', '.join(items[i:i+30]))

with zipfile.ZipFile(apk) as z:
    names = z.namelist()
    assets = sorted(n for n in names if n.startswith('assets/'))
    assert set(assets) == expected_assets, assets
    for name, expected in hashes.items():
        assert hashlib.sha256(z.read('assets/csl/' + name)).hexdigest() == expected, name
    assert not any(n.lower().endswith('.csl') or any(x in n.lower() for x in ('fixture', 'comparison', 'phone-fixture')) for n in names)
    all_classes = sorted({c for n in names if n.endswith('.dex') for c in classes(z.read(n))})
    assert not any(c.startswith('Lorg/mozilla/javascript/') for c in all_classes)
    assert not any(c.startswith('Lorg/blaykie/nativeapp/csl/spike/') or c.startswith('Lorg/blaykie/nativeapp/csl/ZoteroCslJson') for c in all_classes)
    # Check every top-level class declared in debug-only source files, including nested classes.
    for path in glob.glob('app/src/debug/java/**/*.kt', recursive=True):
        text = open(path).read()
        package = re.search(r'^package\s+([\w.]+)', text, re.M).group(1).replace('.', '/')
        for name in re.findall(r'^(?:(?:internal|private|public|data|sealed|abstract|open)\s+)*(?:class|object|interface)\s+(\w+)', text, re.M):
            prefix = 'L' + package + '/' + name
            assert not any(c == prefix + ';' or c.startswith(prefix + '$') for c in all_classes), (path, name)
    related = [c for c in all_classes if c.startswith(('Lorg/blaykie/nativeapp/csl/', 'Lorg/blaykie/nativeapp/citation/style/', 'Lorg/blaykie/nativeapp/citation/processor/'))]
permissions, exported, components = manifest(apk)
base_permissions, base_exported, _ = manifest(baseline)
assert permissions == base_permissions == {'android.permission.INTERNET'}, permissions
assert exported == base_exported, (exported, base_exported)
assert not any('spike' in (c['name'] or '').lower() for c in components), components
print('Release APK assets:', *assets, sep='\n')
print('Release permissions:', *sorted(permissions), sep='\n')
print('Release exported components:', sorted(exported))
print('Release CSL-related classes:', *related, sep='\n')
print('Release APK:', os.path.getsize(apk), 'bytes; v1.0.1 rebuilt unsigned APK:', os.path.getsize(baseline), 'bytes')
notice('Release assets', assets)
notice('Release permissions', sorted(permissions))
notice('Release CSL classes', related)
print('::notice::Release APK: %d bytes; v1.0.1 rebuilt unsigned APK: %d bytes' % (os.path.getsize(apk), os.path.getsize(baseline)))
