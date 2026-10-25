"""Explicitly authorized C3 disposable MySQL verification. Never targets public/production/C2 data."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import time
import uuid

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('s1b_fixture', REPO / 'backend/scripts/verify-admin-isolated.py')
c2 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c2)
BASE = Path(os.environ.get('LOCALAPPDATA', '')) / 'SouthLeaf/S1C3Verification'
SCHEMA = 'southleaf_admin_c3_it'
REPORT = REPO / 'docs/开发与演示/证据/stage-two-s1c3-isolated-it.json'
STEP = 'preflight'


def require(condition, code):
    if not condition:
        raise c2.VerificationFailure(code)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def reserve_port():
    sock = socket.socket()
    if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    sock.bind(('127.0.0.1', 0))
    return sock, sock.getsockname()[1]


def source_fixture():
    manifests = sorted(c2.BASE.glob('*/manifest.json'))
    require(len(manifests) == 1, 'OWNED_C2_MANIFEST_MISSING_OR_AMBIGUOUS')
    return c2.load_manifest(manifests[0])


def server_config(root, source_root, port, memory):
    return '\n'.join([
        '[mysqld]', 'basedir=' + c2.MYSQL_HOME.as_posix(), 'datadir=' + (root / 'data').as_posix(),
        'port=' + str(port), 'bind-address=127.0.0.1', 'mysqlx=OFF', 'shared-memory=ON',
        'shared-memory-base-name=' + memory, 'require_secure_transport=ON',
        'ssl-ca=' + (source_root / 'ca.pem').as_posix(), 'ssl-cert=' + (source_root / 'server-cert.pem').as_posix(),
        'ssl-key=' + (source_root / 'server-key.pem').as_posix(), 'tls_version=TLSv1.2,TLSv1.3',
        'skip-log-bin', 'general-log=OFF', 'slow-query-log=OFF', 'performance_schema=OFF',
        'max_connections=12', 'innodb_buffer_pool_size=64M', 'innodb_redo_log_capacity=32M',
        'log-error=' + (root / 'server-error.log').as_posix(), 'log_error_verbosity=2']) + '\n'


def migrate(meta, passwords):
    dependencies = REPO / 'backend/admin-migration-cli/target/dependency'
    require(any(dependencies.glob('*.jar')), 'MIGRATION_RUNTIME_CLASSPATH_MISSING')
    classpath = os.pathsep.join(str(REPO / item) for item in [
        'backend/admin-migration-cli/target/admin-migration-cli-0.1.0-SNAPSHOT.jar',
        'backend/admin-db-policy/target/admin-db-policy-0.1.0-SNAPSHOT.jar',
        'backend/admin-migration-cli/target/dependency/*'])
    env = {
        'SOUTHLEAF_ADMIN_MIGRATION_URL': f"jdbc:mysql://localhost:{meta['port']}/{SCHEMA}?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
        'SOUTHLEAF_ADMIN_MIGRATION_SCHEMA': SCHEMA,
        'SOUTHLEAF_ADMIN_MIGRATION_USERNAME': 'c3_migrator',
        'SOUTHLEAF_ADMIN_MIGRATION_PASSWORD': passwords['c3_migrator']}
    command = c2.java_options(meta) + ['-cp', classpath, 'cn.zhinan.adminmigration.AdminDatabaseMigrationMain']
    c2.run(command + ['--migrate'], env=env, marker='ADMIN_SCHEMA_MIGRATION_PASS')
    c2.run(command + ['--validate'], env=env, marker='ADMIN_SCHEMA_VALIDATION_PASS')


def cli_grants():
    return '\n'.join([
        f"GRANT USAGE ON *.* TO 'c3_cli'@'localhost';",
        f"GRANT SELECT,UPDATE ON `{SCHEMA}`.admin_bootstrap_guard TO 'c3_cli'@'localhost';",
        f"GRANT SELECT,INSERT,UPDATE ON `{SCHEMA}`.admin_account TO 'c3_cli'@'localhost';",
        f"GRANT SELECT,INSERT,DELETE ON `{SCHEMA}`.admin_account_permission TO 'c3_cli'@'localhost';",
        f"GRANT SELECT,INSERT ON `{SCHEMA}`.admin_account_command TO 'c3_cli'@'localhost';"])


def provision():
    global STEP
    require(os.name == 'nt' and os.environ.get('LOCALAPPDATA'), 'WINDOWS_REQUIRED')
    _, source_meta, source_passwords = source_fixture()
    source_root = Path(source_meta['root'])
    require(source_meta['status'] in ('prepared-and-cleanly-stopped', 'verified-and-cleanly-stopped'), 'C2_FIXTURE_NOT_STOPPED')
    require(all(path.is_file() for path in (c2.MYSQL, c2.SERVER, c2.JAVA, source_root / 'ca.pem', source_root / 'server-cert.pem', source_root / 'server-key.pem', source_root / 'truststore.jks')), 'REQUIRED_EXISTING_PRIVATE_TEST_MATERIAL_MISSING')
    if BASE.exists():
        require(not any(BASE.iterdir()), 'C3_FIXTURE_ALREADY_EXISTS_REFUSE_OVERWRITE')
        BASE.rmdir()
    BASE.mkdir(parents=True)
    root = BASE / ('run-' + uuid.uuid4().hex)
    root.mkdir()
    reservation, port = reserve_port()
    passwords = {key: secrets.token_urlsafe(32) for key in ('root', 'c3_migrator', 'c3_cli', 'c3_runtime')}
    passwords['truststore'] = source_passwords['truststore']
    memory = 'SouthLeafC3' + uuid.uuid4().hex
    meta = {'kind': 'southleaf-s1c3-explicitly-authorized-test', 'root': str(root), 'port': port,
            'memoryName': memory, 'status': 'initializing', 'createdAt': time.strftime('%Y-%m-%dT%H:%M:%S%z')}
    server = None
    try:
        STEP = 'private-public-trust-copy-no-private-key-write'
        shutil.copy2(source_root / 'ca.pem', root / 'ca.pem')
        shutil.copy2(source_root / 'truststore.jks', root / 'truststore.jks')
        (root / 'credentials.dpapi').write_bytes(c2.protect(json.dumps(passwords).encode('utf-8')))
        (root / 'server.ini').write_text(server_config(root, source_root, port, memory), encoding='ascii')
        meta.update(configSha256=digest(root / 'server.ini'), testCaSha256=digest(root / 'ca.pem'), truststoreSha256=digest(root / 'truststore.jks'), sourcePublicTrustSha256=digest(source_root / 'ca.pem'))
        manifest = root / 'manifest.json'
        manifest.write_text(json.dumps(meta, indent=2), encoding='utf-8')
        STEP = 'initialize-owned-datadir'
        c2.run([c2.SERVER, '--defaults-file=' + str(root / 'server.ini'), '--initialize-insecure'], timeout=90)
        meta['serverUuid'] = (root / 'data/auto.cnf').read_text(encoding='utf-8').split('server-uuid=', 1)[1].splitlines()[0].strip()
        manifest.write_text(json.dumps(meta, indent=2), encoding='utf-8')
        STEP = 'private-bootstrap'
        server = c2.start_server(meta, '', bootstrap=True)
        c2.sql(meta, '', "ALTER USER 'root'@'localhost' IDENTIFIED BY '" + passwords['root'] + "';")
        c2.stop_server(meta, passwords['root'], server)
        server = None
        # The reservation protects initialization only. Release it before the owned server binds
        # its verified loopback TCP listener.
        reservation.close()
        reservation = None
        STEP = 'private-schema-and-identities'
        server = c2.start_server(meta, passwords['root'])
        statements = [f"CREATE DATABASE `{SCHEMA}`;", "CREATE ROLE 'c3_forbidden_role'@'localhost';"]
        for user in ('c3_migrator', 'c3_cli', 'c3_runtime'):
            statements.append(f"CREATE USER '{user}'@'localhost' IDENTIFIED BY '{passwords[user]}' REQUIRE SSL;")
        statements.append(f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,DROP,INDEX,REFERENCES ON `{SCHEMA}`.* TO 'c3_migrator'@'localhost';")
        c2.sql(meta, passwords['root'], '\n'.join(statements))
        STEP = 'explicit-v2-v4-migration'
        migrate(meta, passwords)
        STEP = 'exact-cli-grants'
        # The L3 lifecycle probe uses only the dedicated CLI identity. Keep the separately
        # created runtime identity grantless here; C2 runtime-grant validation remains its own
        # fixture and must not be inferred from C3 lifecycle evidence.
        c2.sql(meta, passwords['root'], cli_grants())
        c2.stop_server(meta, passwords['root'], server)
        server = None
        meta['status'] = 'prepared-and-cleanly-stopped'
        manifest.write_text(json.dumps(meta, indent=2), encoding='utf-8')
        return manifest, meta, passwords
    except BaseException:
        if server is not None and server.poll() is None:
            try:
                c2.stop_server(meta, passwords.get('root', ''), server)
            except BaseException:
                server.kill()
        shutil.rmtree(root, ignore_errors=True)
        raise
    finally:
        if reservation is not None:
            reservation.close()
        source_passwords.clear()


def execute(manifest, meta, passwords):
    global STEP
    STEP = 'start-owned-c3-instance'
    server = c2.start_server(meta, passwords['root'])
    started = time.time()
    primary_failure = None
    try:
        STEP = 'run-explicit-c3-l3-it'
        environment = {
            'SOUTHLEAF_C3_IT_MODE': 'demo',
            'SOUTHLEAF_C3_IT_DB_URL': f"jdbc:mysql://localhost:{meta['port']}/{SCHEMA}?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8",
            'SOUTHLEAF_C3_IT_DB_USERNAME': 'c3_cli', 'SOUTHLEAF_C3_IT_DB_PASSWORD': passwords['c3_cli'],
            'SOUTHLEAF_C3_IT_DB_SCHEMA': SCHEMA, 'SOUTHLEAF_C3_IT_DB_EXPECTED_USER': 'c3_cli@localhost',
            'JAVA_TOOL_OPTIONS': '-Djavax.net.ssl.trustStore=' + str(Path(meta['root']) / 'truststore.jks') + ' -Djavax.net.ssl.trustStoreType=JKS'}
        result = subprocess.run(
            ['mvnw.cmd', '-pl', 'admin-account-cli', '-am', '-Pc3-isolated-it', 'verify'],
            cwd=REPO / 'backend',
            env=c2.clean_env(environment),
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=300)
        if result.returncode != 0:
            raise c2.VerificationFailure(
                'C3_MAVEN_VERIFY_FAILED:exit=' + str(result.returncode) + ':output-sha256=' + hashlib.sha256(result.stdout).hexdigest())
        return {'mavenOutputSha256': hashlib.sha256(result.stdout).hexdigest(), 'completedAfterEpoch': time.time() >= started}
    except BaseException as error:
        primary_failure = error
        raise
    finally:
        STEP = 'stop-owned-c3-instance'
        try:
            c2.stop_server(meta, passwords['root'], server)
        except BaseException:
            # Preserve the verification failure rather than masking it with a secondary cleanup
            # failure. c2.stop_server has already force-stopped its owned child if needed.
            if primary_failure is None:
                raise


def cleanup(root):
    require(root.parent == BASE.resolve(), 'UNOWNED_C3_CLEANUP_REFUSED')
    shutil.rmtree(root)
    if BASE.exists() and not any(BASE.iterdir()):
        BASE.rmdir()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--confirm-isolated-c3-provisioning-and-run', action='store_true')
    options = parser.parse_args()
    require(options.confirm_isolated_c3_provisioning_and_run, 'EXPLICIT_C3_PROVISIONING_CONFIRMATION_REQUIRED')
    report = {'scope': 'disposable C3 private MySQL L3/L4 fixture only', 'atUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'originalServicesChanged': False, 'publicFixtureChanged': False, 'productionAccessed': False,
              'newPrivateKeysWritten': False, 'plaintextSecretsWritten': False, 'c2FixtureModified': False}
    manifest = meta = passwords = None
    try:
        manifest, meta, passwords = provision()
        report.update(execute(manifest, meta, passwords), status='PASS', c3Tests='AccountLifecycleRealIT')
    except BaseException as error:
        report.update(status='FAIL', failure=str(error) if isinstance(error, c2.VerificationFailure) else type(error).__name__)
        raise
    finally:
        if meta is not None:
            try:
                cleanup(Path(meta['root']))
                report['ownedFixtureRemoved'] = True
            except BaseException:
                report['ownedFixtureRemoved'] = False
        if passwords:
            passwords.clear()
        c2.atomic_json(REPORT, report)
        print(json.dumps({k: v for k, v in report.items() if k not in ('mavenOutputSha256',)}, ensure_ascii=True))


if __name__ == '__main__':
    try:
        main()
    except (c2.VerificationFailure, subprocess.TimeoutExpired, OSError, ValueError) as failure:
        print('S1C3_ISOLATED_FAILED:' + STEP + ':' + (str(failure) if isinstance(failure, c2.VerificationFailure) else type(failure).__name__), file=sys.stderr)
        sys.exit(2)
