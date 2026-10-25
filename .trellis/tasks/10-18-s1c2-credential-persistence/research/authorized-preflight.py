"""Authorized C2 environment preflight: metadata/certificates only, no credentials or DB startup."""
import datetime
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('c2_verifier', REPO / 'backend/scripts/verify-admin-isolated.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def certificate(path):
    if not path.is_file():
        return {'present': False, 'validForNextHour': False}
    check = subprocess.run([str(verifier.OPENSSL), 'x509', '-in', str(path), '-noout', '-checkend', '3600'],
                           stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    dates = subprocess.run([str(verifier.OPENSSL), 'x509', '-in', str(path), '-noout', '-dates'],
                           stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
    safe_dates = {}
    if dates.returncode == 0:
        for line in dates.stdout.decode('ascii', 'replace').splitlines():
            key, _, value = line.partition('=')
            if key in ('notBefore', 'notAfter'):
                safe_dates[key] = value
    return {'present': True, 'validForNextHour': check.returncode == 0, **safe_dates}


def main():
    report = {'scope': 'metadata/TLS preflight only', 'atUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'userAuthorization': 'controlled C2 integration validation approved',
              'databaseProcessesStarted': False, 'migrationsExecuted': False,
              'grantsChanged': False, 'credentialStoreDecrypted': False, 'blockers': []}
    report['toolsAvailable'] = {name: path.is_file() for name, path in (
        ('mysql', verifier.MYSQL), ('mysqld', verifier.SERVER), ('openssl', verifier.OPENSSL),
        ('java', verifier.JAVA), ('keytool', verifier.KEYTOOL))}
    if not all(report['toolsAvailable'].values()):
        report['blockers'].append('REQUIRED_EXISTING_TOOL_UNAVAILABLE')
    manifests = sorted(verifier.BASE.glob('*/manifest.json'))
    report['existingManifestCount'] = len(manifests)
    if len(manifests) != 1:
        report['blockers'].append('OWNED_MANIFEST_MISSING_OR_AMBIGUOUS')
    else:
        manifest = manifests[0].resolve()
        meta = json.loads(manifest.read_text(encoding='utf-8'))
        root = manifest.parent
        checks = {
            'location': manifest.parent.parent == verifier.BASE.resolve(),
            'kind': meta.get('kind') == 'southleaf-s1b-explicitly-authorized-test',
            'root': Path(meta.get('root', '')).resolve() == root,
            'state': meta.get('status') in ('prepared-and-cleanly-stopped', 'verified-and-cleanly-stopped'),
            'port': 0 < int(meta.get('port', 0)) <= 65535 and int(meta.get('port', 0)) != 3306,
            'configDigest': sha(root / 'server.ini') == meta.get('configSha256'),
            'caDigest': sha(root / 'ca.pem') == meta.get('testCaSha256'),
            'truststoreDigest': sha(root / 'truststore.jks') == meta.get('truststoreSha256'),
        }
        auto = (root / 'data/auto.cnf').read_text(encoding='utf-8')
        found = re.search(r'server-uuid=(\S+)', auto)
        checks['dataUuid'] = bool(found and found.group(1) == meta.get('serverUuid'))
        report['ownedMetadataChecks'] = checks
        if not all(checks.values()):
            report['blockers'].append('OWNERSHIP_OR_PREPARATION_METADATA_MISMATCH')
        if report['toolsAvailable']['openssl']:
            report['certificates'] = {'ca': certificate(root / 'ca.pem'),
                                      'server': certificate(root / 'server-cert.pem')}
            chain = subprocess.run([str(verifier.OPENSSL), 'verify', '-CAfile', str(root / 'ca.pem'),
                                    '-verify_hostname', 'localhost', str(root / 'server-cert.pem')],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
            report['tlsChainAndHostnameValidNow'] = chain.returncode == 0
            if not report['tlsChainAndHostnameValidNow'] or not all(
                    c['validForNextHour'] for c in report['certificates'].values()):
                report['blockers'].append('TEST_TLS_FIXTURE_EXPIRED_OR_INVALID')
    recorded = json.loads((REPO / 'docs/开发与演示/证据/stage-two-s1c2-safe-checks.json').read_text(encoding='utf-8'))
    report['sourceHashesStillMatch'] = all(sha(REPO / p) == h for p, h in recorded['sourceSha256'].items())
    report['preparedArtifactHashesStillMatch'] = all(sha(REPO / p) == h for p, h in recorded['preparedArtifactSha256'].items())
    report['migrationDependencyJarCount'] = len(list((REPO / 'backend/admin-migration-cli/target/dependency').glob('*.jar')))
    if not report['sourceHashesStillMatch'] or not report['preparedArtifactHashesStillMatch']:
        report['blockers'].append('SOURCE_OR_CANDIDATE_CHANGED_SINCE_SAFE_CHECKS')
    if not report['migrationDependencyJarCount']:
        report['blockers'].append('PREPARED_MIGRATION_CLASSPATH_MISSING')
    report['status'] = 'BLOCKED' if report['blockers'] else 'READY_FOR_AUTHORIZED_RUN'
    destination = REPO / 'docs/开发与演示/证据/stage-two-s1c2-environment-preflight.json'
    verifier.atomic_json(destination, report)
    print(json.dumps(report, ensure_ascii=True, indent=2))
    return 2 if report['blockers'] else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print('C2_PREFLIGHT_FAILED:' + type(error).__name__, file=sys.stderr)
        sys.exit(2)
