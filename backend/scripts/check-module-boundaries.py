#!/usr/bin/env python3
"""S1-A structural proof over declared Maven graphs, compiled JDK dependencies and final jars.
Run after reactor verify. Does not connect to databases, modify credentials or touch legacy target.
"""
import io
import json
import pathlib
import re
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
MODULES = ['public-api', 'admin-security-core', 'admin-db-policy', 'admin-migration-cli',
           'admin-account-cli', 'admin-security-jdbc', 'admin-runtime', 'admin-credential-crypto']
ARTIFACTS = {m: ('uniapp-api' if m == 'public-api' else m) for m in MODULES}
VERSION = '0.1.0-SNAPSHOT'
EXPECTED = {
    'public-api': set(), 'admin-security-core': set(), 'admin-db-policy': set(),
    'admin-credential-crypto': {('admin-security-core', 'compile')},
    'admin-migration-cli': {('admin-db-policy', 'compile')},
    'admin-account-cli': {('admin-security-core', 'compile'), ('admin-credential-crypto', 'compile'),
                          ('admin-db-policy', 'compile')},
    'admin-security-jdbc': {('admin-security-core', 'compile'), ('admin-migration-cli', 'test'),
                             ('admin-credential-crypto', 'test')},
    'admin-runtime': {('admin-security-core', 'compile'), ('admin-security-jdbc', 'compile'),
                      ('admin-db-policy', 'compile'), ('admin-credential-crypto', 'compile')},
}


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def acyclic(graph):
    active, done = set(), set()
    def visit(node):
        require(node not in active, 'Module cycle: ' + node)
        if node in done:
            return
        active.add(node)
        for child in graph[node]:
            visit(child)
        active.remove(node)
        done.add(node)
    for node in graph:
        visit(node)


def disallowed(name, fragments):
    return any(f in name for f in fragments)


def scan_jar(path, forbidden):
    count = 0
    def scan(data, label):
        nonlocal count
        with zipfile.ZipFile(data) as archive:
            for name in archive.namelist():
                require(not disallowed(name, forbidden), 'Forbidden artifact entry: ' + label + '!' + name)
                count += 1
                if name.endswith('.jar'):
                    scan(io.BytesIO(archive.read(name)), label + '!' + name)
    scan(path, path.name)
    return count


def self_test():
    try:
        acyclic({'a': {'b'}, 'b': {'a'}})
        raise RuntimeError('Cycle mutation was accepted')
    except AssertionError:
        pass
    outer = io.BytesIO()
    inner = io.BytesIO()
    with zipfile.ZipFile(inner, 'w') as z:
        z.writestr('cn/zhinan/adminsecurity/Forbidden.class', b'fixture')
    with zipfile.ZipFile(outer, 'w') as z:
        z.writestr('BOOT-INF/lib/hidden.jar', inner.getvalue())
    # Exercise the same recursive scanner using a temporary file, never a product jar.
    import tempfile
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / 'mutation.jar'
        p.write_bytes(outer.getvalue())
        try:
            scan_jar(p, ['cn/zhinan/adminsecurity/'])
            raise RuntimeError('Nested artifact mutation was accepted')
        except AssertionError:
            pass


def check_client_boundaries():
    workspace = ROOT.parent
    admin_manifest_path = workspace / 'admin-web' / 'package.json'
    native_manifest_path = workspace / 'frontend' / 'package.json'
    require(admin_manifest_path.is_file(), 'Missing separate admin-web package manifest')
    require(native_manifest_path.is_file(), 'Missing native frontend package manifest')
    admin = json.loads(admin_manifest_path.read_text(encoding='utf-8'))
    native = json.loads(native_manifest_path.read_text(encoding='utf-8'))
    admin_dependencies = set(admin.get('dependencies', {}))
    admin_dev_dependencies = set(admin.get('devDependencies', {}))
    require({'vue', 'vue-router', 'element-plus'} <= admin_dependencies,
            'admin-web must own its Vue 3 / router / Element Plus runtime stack')
    require({'vite', 'typescript', 'vitest', 'vue-tsc'} <= admin_dev_dependencies,
            'admin-web must own its Vite / TypeScript validation stack')
    native_dependencies = set(native.get('dependencies', {})) | set(native.get('devDependencies', {}))
    forbidden_native = {'vue', 'vue-router', 'element-plus', 'vite', '@vitejs/plugin-vue',
                        '@vue/test-utils', 'vitest', 'vue-tsc'}
    leaked = sorted(native_dependencies & forbidden_native)
    require(not leaked, 'Admin web dependencies leaked into native WeChat frontend: ' + str(leaked))
    source_root = workspace / 'admin-web' / 'src'
    sources = sorted(p for p in source_root.rglob('*') if p.is_file() and p.suffix in {'.ts', '.vue'})
    require(bool(sources), 'Missing admin-web TypeScript/Vue sources')
    source_text = '\n'.join(p.read_text(encoding='utf-8') for p in sources)
    require('\"/api/admin/v1/auth\"' in source_text,
            'admin-web must use the relative same-origin auth API path')
    require(not re.search(r'https?://(?:localhost|127\.0\.0\.1)(?::\d+)?', source_text, re.I),
            'admin-web source must not hard-code a loopback host')
    require(not re.search(r'\b(?:localStorage|sessionStorage)\b', source_text),
            'admin-web source must not persist auth material in browser storage')
    return {'adminWebDependencies': sorted(admin_dependencies),
            'nativeFrontendDependencies': sorted(native_dependencies),
            'sameOriginRelativeApi': True, 'noLoopbackOrWebStorage': True}


def main():
    self_test()
    client_boundary = check_client_boundaries()
    parent = ET.parse(ROOT / 'pom.xml').getroot()
    declared = [e.text for e in parent.findall('m:modules/m:module', NS)]
    require(set(declared) == set(MODULES) and len(declared) == len(MODULES), 'Unexpected reactor modules')
    require(parent.find('m:dependencies', NS) is None, 'Parent must not inject runtime dependencies')
    graph = {}
    jars = {}
    for module in MODULES:
        pom = ET.parse(ROOT / module / 'pom.xml').getroot()
        internal = set()
        for d in pom.findall('m:dependencies/m:dependency', NS):
            group = d.findtext('m:groupId', namespaces=NS)
            artifact = d.findtext('m:artifactId', namespaces=NS)
            scope = d.findtext('m:scope', default='compile', namespaces=NS)
            if group == 'cn.zhinan':
                require(artifact in MODULES, 'Unknown internal module: ' + str(artifact))
                internal.add((artifact, scope))
            if module == 'admin-credential-crypto' and scope != 'test' and group != 'cn.zhinan':
                require(scope == 'compile' and (group, artifact) in {
                    ('org.springframework.security', 'spring-security-crypto'),
                    ('org.springframework', 'spring-core'), ('org.bouncycastle', 'bcprov-jdk18on')
                }, 'Unexpected credential implementation dependency: ' + str(artifact))
            if module in ('admin-security-core', 'admin-db-policy'):
                require(scope == 'test', 'Pure module has production dependency: ' + str(artifact))
        require(internal == EXPECTED[module], 'Unexpected dependency edges: ' + module + ' ' + str(internal))
        graph[module] = {a for a, _ in internal}  # test edges included intentionally
        jar = ROOT / module / 'target' / (ARTIFACTS[module] + '-' + VERSION + '.jar')
        require(jar.is_file(), 'Missing final artifact: ' + str(jar))
        jars[module] = jar
        with zipfile.ZipFile(jar) as archive:
            boot = any(n.startswith('BOOT-INF/') for n in archive.namelist())
            require(boot == (module in ('public-api', 'admin-runtime')), 'Wrong executable packaging: ' + module)
    acyclic(graph)
    public_forbidden = ['cn/zhinan/uniapp/adminsecurity/', 'cn/zhinan/adminsecurity/',
                        'cn/zhinan/adminruntime/', 'cn/zhinan/adminmigration/', 'cn/zhinan/adminaccountcli/',
                        'cn/zhinan/admindbpolicy/', 'AdminDatabaseMigrationMain', 'db/admin-migration/', 'application-admin.properties',
                        'TestSessionTokenCodec', 'cn/zhinan/admincredentialcrypto/',
                        'org/bouncycastle/', 'org/springframework/security/crypto/']
    # S1-D makes admin-runtime an opt-in same-origin servlet surface. Servlet/Spring Web classes are
    # therefore allowed here, while every public application class/resource and migration surface remains forbidden.
    runtime_forbidden = ['cn/zhinan/uniapp/', 'cn/zhinan/adminmigration/', 'cn/zhinan/adminaccountcli/',
                         'db/migration/', 'db/admin-migration/', 'db/demo/', 'TestSessionTokenCodec']

    entries = {
        'public-api': scan_jar(jars['public-api'], public_forbidden),
        'admin-runtime': scan_jar(jars['admin-runtime'], runtime_forbidden),
        'admin-credential-crypto': scan_jar(jars['admin-credential-crypto'],
            ['BOOT-INF/', 'cn/zhinan/adminruntime/', 'cn/zhinan/uniapp/', 'CredentialKdfBenchmark',
             'Argon2PasswordServiceTest', 'cn/zhinan/adminaccountcli/', 'db/']),
        'admin-migration-cli': scan_jar(
            jars['admin-migration-cli'],
            ['BOOT-INF/', 'cn/zhinan/adminruntime/', 'cn/zhinan/adminaccountcli/']),
        'admin-account-cli': scan_jar(
            jars['admin-account-cli'],
            ['BOOT-INF/', 'cn/zhinan/uniapp/', 'cn/zhinan/adminruntime/',
             'cn/zhinan/adminmigration/', 'db/', 'application-admin.properties']),
    }
    with zipfile.ZipFile(jars['admin-migration-cli']) as archive:
        require('db/admin-migration/V2__admin_security.sql' in archive.namelist(), 'CLI migration missing')
    for module, package in [('admin-security-core', 'cn.zhinan.adminsecurity.'),
                            ('admin-db-policy', 'cn.zhinan.admindbpolicy.')]:
        result = subprocess.run(['jdeps', '--multi-release', '21', '-verbose:class', str(jars[module])],
                                text=True, capture_output=True, check=True)
        edges = re.findall(r'^\s+(cn\.zhinan\.[\w.$]+)\s+->\s+([\w.$]+)', result.stdout, re.M)
        require(bool(edges), 'No bytecode dependency evidence: ' + module)
        for source, target in edges:
            require(target.startswith(('java.', package)) and not target.startswith(('java.sql.', 'javax.sql.', 'java.nio.file.')), 'Forbidden bytecode edge: ' + source + ' -> ' + target)
    result = subprocess.run(['jdeps', '--multi-release', '21', '-verbose:class',
                             str(jars['admin-credential-crypto'])],
                            text=True, capture_output=True, check=True)
    edges = re.findall(r'^\s+(cn\.zhinan\.[\w.$]+)\s+->\s+([\w.$]+)', result.stdout, re.M)
    require(bool(edges), 'No crypto bytecode dependency evidence')
    for source, target in edges:
        require(target.startswith(('java.', 'cn.zhinan.adminsecurity.domain.', 'cn.zhinan.adminsecurity.port.',
                'cn.zhinan.admincredentialcrypto.', 'org.springframework.security.crypto.argon2.'))
                and not target.startswith(('java.sql.', 'java.nio.file.', 'java.net.')),
                'Forbidden crypto bytecode edge: ' + source + ' -> ' + target)
    report = {'result': 'PASS', 'scope': 'declared compile/runtime/test module graph + jdeps pure jars + nested artifacts',
              'modules': MODULES, 'nestedEntriesChecked': entries, 'negativeSelfTests': 2,
              'clientBoundary': client_boundary,
              'limitations': ['Not a Writer grants, management login, or production security acceptance']}
    destination = ROOT / 'admin-runtime' / 'target' / 'module-boundaries.json'
    destination.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
