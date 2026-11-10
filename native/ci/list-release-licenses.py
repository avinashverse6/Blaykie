#!/usr/bin/env python3
"""List filenames only, including archives nested in resolved AARs and the APK."""
import glob
import io
import re
import subprocess
import zipfile

result = subprocess.run(
    ['./gradlew', '-q', '-I', 'ci/release-artifacts.gradle', ':app:reportReleaseArtifactPaths'],
    check=True, text=True, stdout=subprocess.PIPE,
)
artifacts = []
for line in result.stdout.splitlines():
    if line.startswith('ARTIFACT\t'):
        _, name, path = line.split('\t', 2)
        artifacts.append((name, path))
if not artifacts:
    raise SystemExit('No release runtime artifacts resolved')

found = set()
pattern = re.compile(r'^(?:license|notice)(?:$|[._-])', re.IGNORECASE)

def scan(label, archive):
    with zipfile.ZipFile(archive) as z:
        for entry in z.infolist():
            if entry.is_dir():
                continue
            parts = entry.filename.split('/')
            if pattern.match(parts[-1]) or any(part.lower() in ('license', 'licenses', 'notice', 'notices') for part in parts[:-1]):
                found.add(f'{label}!{entry.filename}')
            if entry.filename.lower().endswith(('.jar', '.aar', '.zip')):
                data = z.read(entry)
                if zipfile.is_zipfile(io.BytesIO(data)):
                    scan(f'{label}!{entry.filename}', io.BytesIO(data))

for coordinate, path in sorted(artifacts):
    if zipfile.is_zipfile(path):
        scan('dependency ' + coordinate, path)
apks = glob.glob('app/build/outputs/apk/release/*.apk')
if len(apks) != 1:
    raise SystemExit('Expected exactly one release APK')
scan('release APK', apks[0])
if not found:
    print('No LICENSE or NOTICE filenames found')
else:
    for name in sorted(found):
        print(name)
    # Make the complete filename inventory visible through the check API as well as the log.
    summary = ' | '.join(sorted(found)).replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    print('::notice::' + summary)
