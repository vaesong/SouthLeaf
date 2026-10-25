"""Read-only C1 evidence collector. Run from repository root after verification."""
from pathlib import Path
import hashlib
import json
import socket
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

root = Path.cwd()
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
baseline = json.loads((root / '.trellis/tasks/10-18-s1c1-credential-core/research/baseline.json').read_text())
changed = [p for p, h in baseline['sources'].items() if sha(root / p) != h]
expected = {'backend/pom.xml', 'backend/admin-security-core/src/main/java/cn/zhinan/adminsecurity/domain/AdminAccount.java',
            'backend/scripts/check-module-boundaries.py', 'backend/scripts/check-runtime-advisories.mjs'}
assert set(changed) == expected, changed
assert all(sha(root / p) == h for p, h in baseline['legacy'].items()), 'Legacy runtime changed'
modules = ['public-api', 'admin-security-core', 'admin-credential-crypto', 'admin-db-policy', 'admin-migration-cli', 'admin-security-jdbc', 'admin-runtime']
suites = []
for module in modules:
    for kind in ['surefire', 'failsafe']:
        for p in (root / 'backend' / module / 'target' / (kind + '-reports')).glob('TEST-*.xml'):
            suite = ET.parse(p).getroot()
            a = suite.attrib
            row = {'module': module, 'kind': kind, 'suite': a['name'],
                   **{k:int(a[k]) for k in ['tests','failures','errors','skipped']}}
            assert row['failures'] == row['errors'] == row['skipped'] == 0, row
            suites.append(row)
unit = sum(s['tests'] for s in suites if s['kind'] == 'surefire')
it = sum(s['tests'] for s in suites if s['kind'] == 'failsafe')
assert (unit, it) == (234, 42), (unit, it)
artifacts = {m: root / 'backend' / m / 'target' / (('uniapp-api' if m == 'public-api' else m)+'-0.1.0-SNAPSHOT.jar') for m in modules}
assert sha(artifacts['public-api']) == 'ece61cbc3a198aa1de9d78d2215e5017996a04c96792d512f36aeb61f0914b33'
with zipfile.ZipFile(artifacts['admin-runtime']) as z:
    assert hashlib.sha256(z.read('BOOT-INF/lib/admin-security-core-0.1.0-SNAPSHOT.jar')).hexdigest() == sha(artifacts['admin-security-core'])
for port in [8091,8092,59769]:
    with socket.socket() as s:
        s.settimeout(1)
        assert s.connect_ex(('127.0.0.1',port)) != 0, ('Temporary listener remains', port)
assert urllib.request.urlopen('http://127.0.0.1:8090/api/v1/public/home',timeout=10).status == 200
advisories={}
for m in ['public-api','admin-credential-crypto']:
    report_path=root/'backend'/m/'target/runtime-advisories.json'
    assert report_path.stat().st_mtime >= max((root/'backend/scripts/check-runtime-advisories.mjs').stat().st_mtime, (root/'backend'/m/'target/runtime-dependencies.txt').stat().st_mtime)
    report=json.loads(report_path.read_text())
    assert report['matches'] == [] and len(report['queries']) == (52 if m=='public-api' else 5)
    advisories[m]=report
paths = [p for p in (root/'backend').glob('*/src/**/*') if p.is_file()] + list((root/'backend').glob('*/pom.xml')) + [root/'backend/pom.xml',root/'backend/scripts/check-module-boundaries.py',root/'backend/scripts/check-runtime-advisories.mjs',root/'backend/scripts/test-runtime-advisories.mjs']
print(json.dumps({'result':'PASS','unitTests':unit,'realMysqlTests':it,'suites':suites,
    'baselineFiles':len(baseline['sources']), 'allowedChanged':changed,
    'unchangedProtectedFiles':len(baseline['sources'])-len(changed),'unchangedLegacyRuntimeFiles':len(baseline['legacy']),
    'artifactSha256':{m:sha(p) for m,p in artifacts.items()}, 'currentSourceSha256':{p.relative_to(root).as_posix():sha(p) for p in paths},
    'resolvedCredentialJarSha256':{p.name:sha(p) for p in (root/'backend/admin-credential-crypto/target/benchmark-libs').glob('*.jar')},
    'advisories':advisories,'temporaryPortsClosed':[8091,8092,59769],'originalApiStatus':200,
    'scope':'C1 pure core and standalone crypto; no runtime credential wiring, account CLI, new SQL/grants or production acceptance'},indent=2))
