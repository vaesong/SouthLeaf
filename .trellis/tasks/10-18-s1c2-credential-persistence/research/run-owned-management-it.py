"""Authorized management-only real IT. Public/full-reactor acceptance remains a separate gate.
No existing public service is started, and no secret or raw child output is written to evidence.
"""
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

REPO = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('c2_verifier', REPO / 'backend/scripts/verify-admin-isolated.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
MODULES = ('admin-security-core', 'admin-credential-crypto', 'admin-db-policy',
           'admin-migration-cli', 'admin-security-jdbc', 'admin-runtime')
DESTINATION = REPO / 'docs/开发与演示/证据/stage-two-s1c2-management-it.json'


def reports_since(started):
    groups = {}
    for kind, folder in (('unit', 'surefire-reports'), ('mysqlIT', 'failsafe-reports')):
        suites = []
        for module in MODULES:
            for file in (REPO / 'backend' / module / 'target' / folder).glob('TEST-*.xml'):
                if file.stat().st_mtime < started - 1:
                    continue
                root = ET.parse(file).getroot()
                cases = []
                for case in root.findall('testcase'):
                    errors = case.findall('error') + case.findall('failure')
                    if kind == 'mysqlIT' or errors:
                        cases.append({'class': case.attrib.get('classname'), 'method': case.attrib.get('name'),
                                      'failureTypes': [e.attrib.get('type', 'unknown') for e in errors]})
                suites.append({'module': module, 'suite': root.attrib.get('name'),
                               **{k: int(root.attrib.get(k, 0)) for k in ('tests', 'failures', 'errors', 'skipped')},
                               'cases': cases})
        groups[kind] = {'suites': suites, **{k: sum(s[k] for s in suites)
                                             for k in ('tests', 'failures', 'errors', 'skipped')}}
    return groups


def run():
    paths = sorted(verifier.BASE.glob('*/manifest.json'))
    verifier.require(len(paths) == 1, 'OWNED_MANIFEST_MISSING_OR_AMBIGUOUS')
    manifest, meta, passwords = verifier.load_manifest(paths[0])
    sources = verifier.source_snapshot()
    started = None
    server = None
    process = None
    failure = None
    cleanup_failure = None
    step = 'start-owned-instance'
    evidence = {'scope': 'management dependency reactor only; NOT full public reactor/C2 acceptance',
                'authorized': True, 'atUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'publicReactorExecuted': False, 'originalServicesChanged': False,
                'plaintextSecretsWrittenByCoordinator': False, 'mavenOutputPersisted': False}
    try:
        server = verifier.start_server(meta, passwords['root'])
        step = 'owned-v3-migration'
        print('MANAGEMENT_IT_STEP:' + step, flush=True)
        verifier.migrate(meta, passwords, verifier.SCHEMA)
        verifier.migrate(meta, passwords, verifier.PRODUCTION_FIXTURE)
        step = 'owned-auth-event-insert-grants'
        verifier.sql(meta, passwords['root'],
            f"GRANT INSERT ON `{verifier.SCHEMA}`.admin_auth_event TO 's1b_writer'@'localhost'; "
            f"GRANT INSERT ON `{verifier.SCHEMA}`.admin_auth_event TO 's1b_probe'@'localhost'; "
            f"GRANT INSERT ON `{verifier.PRODUCTION_FIXTURE}`.admin_auth_event TO 's1b_prod_writer'@'localhost';")
        step = 'management-reactor-verify'
        print('MANAGEMENT_IT_STEP:' + step, flush=True)
        started = time.time()
        process = subprocess.Popen(
            ['cmd.exe', '/d', '/c', str(REPO / 'backend/mvnw.cmd'), '-pl', 'admin-runtime', '-am', 'verify',
             '-DskipTests=false', '-Dmaven.test.skip=false', '-DskipITs=false'],
            cwd=REPO / 'backend', env=verifier.clean_env(verifier.security_test_env(meta, passwords)),
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        try:
            output, _ = process.communicate(timeout=600)
        except BaseException:
            verifier.stop_owned_command(process)
            raise
        evidence['mavenExitCode'] = process.returncode
        evidence['mavenOutputSha256'] = hashlib.sha256(output).hexdigest()
        evidence['reports'] = reports_since(started)
        verifier.require(process.returncode == 0, 'MANAGEMENT_REACTOR_FAILED')
        for group in evidence['reports'].values():
            verifier.require(group['tests'] > 0 and not any(group[k] for k in ('failures', 'errors', 'skipped')),
                             'FRESH_REPORTS_MISSING_FAILED_OR_SKIPPED')
        fresh = evidence['reports']['mysqlIT']['suites']
        verifier.require(any(s['suite'].endswith('AdminCredentialLoginIT') and s['tests'] == 10 for s in fresh),
                         'NEW_TEN_CASE_MYSQL_SUITE_NOT_EXECUTED')
        verifier.require(any(s['suite'].endswith('AdminSessionStoreIT') for s in fresh), 'PREVIOUS_STORE_SUITE_NOT_EXECUTED')
        step = 'source-and-packaged-boundaries'
        verifier.require(sources == verifier.source_snapshot(), 'SOURCE_CHANGED_DURING_MANAGEMENT_IT')
        boundary = verifier.run([sys.executable, REPO / 'backend/scripts/check-module-boundaries.py'])
        evidence['artifactBoundaries'] = json.loads(boundary.decode('utf-8'))
        evidence['sourceSha256'] = sources
        evidence['preparedRuntimeJarSha256'] = verifier.digest(verifier.JAR)
        evidence['migrationJarSha256'] = verifier.digest(REPO / 'backend/admin-migration-cli/target/admin-migration-cli-0.1.0-SNAPSHOT.jar')
    except BaseException as error:
        failure = error
        evidence['failedStep'] = step
        evidence['failure'] = verifier.verification_failure(error)
        if started is not None and 'reports' not in evidence:
            evidence['reports'] = reports_since(started)
    finally:
        if server is not None:
            try:
                try:
                    if server.poll() is None:
                        verifier.sql(meta, passwords['root'],
                                     "SET GLOBAL mandatory_roles=''; SET GLOBAL require_secure_transport=ON;")
                finally:
                    verifier.stop_server(meta, passwords['root'], server)
            except BaseException as error:
                cleanup_failure = error
        passwords.clear()
    evidence['ownedInstanceShutdown'] = 'FAILED' if cleanup_failure else 'COMPLETE' if server is not None else 'NOT_STARTED'
    if cleanup_failure:
        evidence['cleanupFailure'] = verifier.verification_failure(cleanup_failure)
    evidence['status'] = 'FAILED' if failure or cleanup_failure else 'PASS_MANAGEMENT_SCOPE_ONLY'
    evidence['fullC2Acceptance'] = 'NOT_CLOSED_BY_THIS_REPORT'
    verifier.atomic_json(DESTINATION, evidence)
    print(json.dumps(evidence, ensure_ascii=True, indent=2))
    if failure or cleanup_failure:
        raise failure if failure else cleanup_failure


if __name__ == '__main__':
    try:
        run()
    except (verifier.VerificationFailure, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print('MANAGEMENT_IT_FAILED:' + verifier.verification_failure(error), file=sys.stderr)
        sys.exit(2)
