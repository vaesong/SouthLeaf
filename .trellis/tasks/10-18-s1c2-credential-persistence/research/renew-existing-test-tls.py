"""Renew only existing private test PUBLIC certificates/JKS; never create/write private keys.
User authorization: 30 days, same existing keys, no plaintext passwords or production/system trust.
"""
import argparse
import base64
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import uuid

REPO = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('c2_verifier', REPO / 'backend/scripts/verify-admin-isolated.py')
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)
REPORT = REPO / 'docs/开发与演示/证据/stage-two-s1c2-test-tls-maintenance.json'
PUBLIC_FILES = ('ca.pem', 'server-cert.pem', 'truststore.jks', 'manifest.json')
EXTENSIONS = ('[req]\ndistinguished_name=dn\n[dn]\n'
              '[ca]\nbasicConstraints=critical,CA:true\nkeyUsage=critical,keyCertSign,cRLSign\n'
              '[server]\nbasicConstraints=critical,CA:false\n'
              'keyUsage=critical,digitalSignature,keyEncipherment\n'
              'extendedKeyUsage=serverAuth\nsubjectAltName=DNS:localhost\n')


def require(condition, code):
    if not condition:
        raise verifier.VerificationFailure(code)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def safe_failure(error):
    return str(error) if isinstance(error, verifier.VerificationFailure) else type(error).__name__


def execute(args, env=None, timeout=45):
    return verifier.run(args, env=env, timeout=timeout)


def pem_der(value):
    text = value.decode('ascii')
    begin = text.index('-----BEGIN CERTIFICATE-----')
    end = text.index('-----END CERTIFICATE-----', begin) + len('-----END CERTIFICATE-----')
    return ssl.PEM_cert_to_DER_cert(text[begin:end])


def dates(path):
    output = execute([verifier.OPENSSL, 'x509', '-in', path, '-noout', '-dates']).decode('ascii')
    values = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    return {key: datetime.datetime.fromtimestamp(ssl.cert_time_to_seconds(values[key]), datetime.timezone.utc).isoformat()
            for key in ('notBefore', 'notAfter')}


def assert_no_private_server(root):
    script = ("$ErrorActionPreference='Stop';$target=$env:SOUTHLEAF_TLS_TARGET.Replace('\\','/').ToLowerInvariant();"
              "$ids=@(Get-CimInstance Win32_Process -Filter \"Name='mysqld.exe'\" | "
              "Where-Object {$_.CommandLine -and $_.CommandLine.Replace('\\','/').ToLowerInvariant().Contains($target)} | "
              "ForEach-Object {$_.ProcessId});ConvertTo-Json -InputObject $ids -Compress")
    output = execute(['powershell', '-NoProfile', '-Command', script],
                     env={'SOUTHLEAF_TLS_TARGET': str(root / 'server.ini')})
    require(json.loads(output.decode('utf-8-sig').strip() or '[]') == [], 'EXISTING_PRIVATE_INSTANCE_IS_RUNNING')


def validate_metadata():
    require(os.name == 'nt', 'WINDOWS_REQUIRED')
    manifests = sorted(verifier.BASE.glob('*/manifest.json'))
    require(len(manifests) == 1, 'OWNED_MANIFEST_MISSING_OR_AMBIGUOUS')
    manifest = manifests[0].resolve()
    meta = json.loads(manifest.read_text(encoding='utf-8'))
    root = manifest.parent
    require(root.parent == verifier.BASE.resolve(), 'NOT_OWNED_MANIFEST_LOCATION')
    require(meta.get('kind') == 'southleaf-s1b-explicitly-authorized-test'
            and Path(meta.get('root', '')).resolve() == root, 'NOT_OWNED_INSTANCE')
    require(meta.get('status') in ('prepared-and-cleanly-stopped', 'verified-and-cleanly-stopped'),
            'INSTANCE_PREPARATION_INCOMPLETE')
    require(0 < int(meta.get('port', 0)) <= 65535 and int(meta['port']) != 3306, 'UNSAFE_TEST_PORT')
    require(not any(any(word in key.lower() for word in ('password', 'passwd', 'privatekey', 'private_key', 'secret', 'token'))
                    for key in meta), 'SECRET_FIELD_NOT_ALLOWED_IN_PUBLIC_MANIFEST_COPY')
    require(sha(root / 'server.ini') == meta['configSha256'], 'TEST_CONFIGURATION_CHANGED')
    require(sha(root / 'ca.pem') == meta['testCaSha256']
            and sha(root / 'truststore.jks') == meta['truststoreSha256'], 'TRUST_FIXTURE_CHANGED')
    auto = (root / 'data/auto.cnf').read_text(encoding='utf-8')
    require('server-uuid=' + meta['serverUuid'] in auto, 'DATA_UUID_MISMATCH')
    for name in ('ca-key.pem', 'server-key.pem', 'credentials.dpapi', 'server.csr'):
        path = root / name
        require(path.is_file() and not path.is_symlink(), 'EXISTING_FIXTURE_INPUT_MISSING_OR_UNSAFE')
    return manifest, root, meta


def renew(days, dry_run=False):
    require(days == 30, 'ONLY_USER_AUTHORIZED_30_DAY_VALIDITY')
    manifest, root, meta = validate_metadata()
    assert_no_private_server(root)
    reservation = socket.socket()
    if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
        reservation.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    try:
        reservation.bind(('127.0.0.1', int(meta['port'])))
    except OSError:
        reservation.close()
        raise verifier.VerificationFailure('TEST_PORT_IN_USE_RENEWAL_REFUSED')
    passwords = {}
    secret_env = {}
    report = {'scope': 'existing private TEST public certificates/JKS/manifest only',
              'authorizedValidityDays': 30, 'atUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'newPrivateKeysWritten': False, 'plaintextPasswordsWritten': False,
              'systemOrProductionTrustChanged': False, 'originalServicesChanged': False,
              'databaseProcessesStarted': False, 'migrationOrGrantsExecuted': False}
    stage = None
    backup = None
    replacements_started = False
    try:
        print('TEST_TLS_STEP:validate-existing-key-bindings', flush=True)
        unchanged = {name: sha(root / name) for name in ('ca-key.pem', 'server-key.pem', 'credentials.dpapi', 'server.ini')}
        original_manifest = sha(manifest)
        original_public = {name: sha(root / name) for name in PUBLIC_FILES}
        ca_public = execute([verifier.OPENSSL, 'pkey', '-in', root / 'ca-key.pem', '-pubout']).strip()
        server_public = execute([verifier.OPENSSL, 'pkey', '-in', root / 'server-key.pem', '-pubout']).strip()
        require(ca_public == execute([verifier.OPENSSL, 'x509', '-in', root / 'ca.pem', '-pubkey', '-noout']).strip(),
                'EXISTING_CA_KEY_BINDING_MISMATCH')
        require(server_public == execute([verifier.OPENSSL, 'x509', '-in', root / 'server-cert.pem', '-pubkey', '-noout']).strip(),
                'EXISTING_SERVER_KEY_BINDING_MISMATCH')
        require(server_public == execute([verifier.OPENSSL, 'req', '-in', root / 'server.csr', '-pubkey', '-noout']).strip(),
                'EXISTING_SERVER_CSR_BINDING_MISMATCH')
        report['existingKeyBindingsValidated'] = True
        report['previousCertificateDates'] = {'ca': dates(root / 'ca.pem'), 'server': dates(root / 'server-cert.pem')}
        if dry_run:
            report['status'] = 'DRY_RUN_VALIDATED_NO_FIXTURE_WRITES'
            report['credentialStoreDecrypted'] = False
            print(json.dumps(report, indent=2))
            return
        stage = root / ('tls-renewal-' + uuid.uuid4().hex)
        stage.mkdir()
        config = stage / 'public-extensions.cnf'
        config.write_text(EXTENSIONS, encoding='ascii')
        print('TEST_TLS_STEP:issue-30-day-public-certificates-with-existing-keys', flush=True)
        execute([verifier.OPENSSL, 'req', '-new', '-x509', '-key', root / 'ca-key.pem',
                 '-out', stage / 'ca.pem', '-days', str(days), '-sha256',
                 '-set_serial', '0x' + secrets.token_hex(16),
                 '-subj', '/CN=SouthLeaf S1B Isolated Test CA', '-config', config, '-extensions', 'ca'])
        execute([verifier.OPENSSL, 'x509', '-req', '-in', root / 'server.csr',
                 '-CA', stage / 'ca.pem', '-CAkey', root / 'ca-key.pem',
                 '-set_serial', '0x' + secrets.token_hex(16), '-days', str(days), '-sha256',
                 '-extfile', config, '-extensions', 'server', '-out', stage / 'server-cert.pem'])
        require(ca_public == execute([verifier.OPENSSL, 'x509', '-in', stage / 'ca.pem', '-pubkey', '-noout']).strip(),
                'NEW_CA_PUBLIC_KEY_CHANGED')
        require(server_public == execute([verifier.OPENSSL, 'x509', '-in', stage / 'server-cert.pem', '-pubkey', '-noout']).strip(),
                'NEW_SERVER_PUBLIC_KEY_CHANGED')
        execute([verifier.OPENSSL, 'verify', '-CAfile', stage / 'ca.pem',
                 '-verify_hostname', 'localhost', stage / 'server-cert.pem'])
        mismatch = subprocess.run([str(verifier.OPENSSL), 'verify', '-CAfile', str(stage / 'ca.pem'),
                                   '-verify_hostname', '127.0.0.1', str(stage / 'server-cert.pem')],
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        require(mismatch.returncode != 0, 'HOSTNAME_MISMATCH_MUST_STILL_FAIL')
        report['newCertificateDates'] = {'ca': dates(stage / 'ca.pem'), 'server': dates(stage / 'server-cert.pem')}
        for value in report['newCertificateDates'].values():
            before = datetime.datetime.fromisoformat(value['notBefore'])
            after = datetime.datetime.fromisoformat(value['notAfter'])
            require(after - before == datetime.timedelta(days=30), 'CERTIFICATE_VALIDITY_IS_NOT_30_DAYS')
        print('TEST_TLS_STEP:rebuild-private-public-CA-only-JKS', flush=True)
        _, loaded_meta, passwords = verifier.load_manifest(manifest)
        require(loaded_meta == meta and bool(passwords.get('truststore')), 'EXISTING_TRUSTSTORE_CREDENTIAL_UNAVAILABLE')
        secret_env = {'SOUTHLEAF_TEST_TRUSTSTORE_PASSWORD': passwords['truststore']}
        execute([verifier.KEYTOOL, '-importcert', '-noprompt', '-alias', 's1b-test-ca',
                 '-file', stage / 'ca.pem', '-keystore', stage / 'truststore.jks', '-storetype', 'JKS',
                 '-storepass:env', 'SOUTHLEAF_TEST_TRUSTSTORE_PASSWORD'], env=secret_env)
        exported = execute([verifier.KEYTOOL, '-exportcert', '-rfc', '-alias', 's1b-test-ca',
                            '-keystore', stage / 'truststore.jks', '-storetype', 'JKS',
                            '-storepass:env', 'SOUTHLEAF_TEST_TRUSTSTORE_PASSWORD'], env=secret_env)
        require(pem_der(exported) == execute([verifier.OPENSSL, 'x509', '-in', stage / 'ca.pem', '-outform', 'DER']),
                'NEW_JKS_CA_BINDING_MISMATCH')
        require(all(sha(root / name) == value for name, value in unchanged.items()), 'EXISTING_KEY_CREDENTIAL_OR_CONFIG_CHANGED')
        require(sha(manifest) == original_manifest
                and all(sha(root / name) == value for name, value in original_public.items()), 'FIXTURE_CHANGED_DURING_RENEWAL')
        assert_no_private_server(root)
        backup = stage / 'previous-public-fixture'
        backup.mkdir()
        for name in PUBLIC_FILES:
            shutil.copy2(root / name, backup / name)
        updated = dict(meta)
        updated.update(testCaSha256=sha(stage / 'ca.pem'), truststoreSha256=sha(stage / 'truststore.jks'),
                       status='prepared-and-cleanly-stopped', tlsFixtureRenewalDays=30,
                       tlsFixtureRenewedAtUtc=report['atUtc'], tlsFixtureNotAfterUtc=report['newCertificateDates']['ca']['notAfter'])
        if 'latestVerificationRun' in updated:
            updated['previousVerificationRun'] = updated.pop('latestVerificationRun')
        old_bytes = manifest.read_bytes()
        eol = '\r\n' if b'\r\n' in old_bytes else '\n'
        updated_text = json.dumps(updated, indent=2).replace('\n', eol)
        if old_bytes.endswith((b'\n', b'\r')):
            updated_text += eol
        (stage / 'manifest.json').write_bytes(updated_text.encode('utf-8'))
        print('TEST_TLS_STEP:atomic-public-file-replacement-and-manifest-last', flush=True)
        for name in ('verification.json', 'verification-partial.json'):
            if (root / name).is_file():
                os.replace(root / name, backup / name)
        replacements_started = True
        for name in PUBLIC_FILES:
            os.replace(stage / name, root / name)
        require(all(sha(root / name) == value for name, value in unchanged.items()), 'EXISTING_KEY_CREDENTIAL_OR_CONFIG_CHANGED')
        require(sha(root / 'ca.pem') == updated['testCaSha256']
                and sha(root / 'truststore.jks') == updated['truststoreSha256'], 'COMMITTED_TRUST_FINGERPRINT_MISMATCH')
        execute([verifier.OPENSSL, 'verify', '-CAfile', root / 'ca.pem',
                 '-verify_hostname', 'localhost', root / 'server-cert.pem'])
        report.update(status='RENEWED', existingPrivateKeysUnchanged=True,
                      encryptedCredentialFileUnchanged=True, databaseConfigurationUnchanged=True,
                      credentialStoreDecrypted=True, publicFixtureBackupCreated=True,
                      caFingerprintBefore=original_public['ca.pem'], caFingerprintAfter=updated['testCaSha256'],
                      truststoreFingerprintBefore=original_public['truststore.jks'], truststoreFingerprintAfter=updated['truststoreSha256'],
                      strictChainAndLocalhostValid=True, hostnameMismatchStillRejected=True,
                      manifestFingerprintsUpdated=True, legacyPassArchived=True)
        verifier.atomic_json(REPORT, report)
        print(json.dumps(report, indent=2))
    except BaseException as error:
        report.update(status='FAILED', failure=safe_failure(error), rollback='NOT_REQUIRED')
        if replacements_started and backup is not None:
            try:
                for name in PUBLIC_FILES:
                    restore = stage / ('restore-' + name)
                    shutil.copy2(backup / name, restore)
                    os.replace(restore, root / name)
                report['rollback'] = 'PUBLIC_FIXTURE_RESTORED'
            except BaseException as restore_error:
                report['rollback'] = 'FAILED'
                report['rollbackFailure'] = safe_failure(restore_error)
        verifier.atomic_json(REPORT, report)
        raise
    finally:
        secret_env.clear()
        passwords.clear()
        reservation.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--days', type=int, choices=[30], default=30)
    parser.add_argument('--dry-run', action='store_true')
    options = parser.parse_args()
    try:
        renew(options.days, options.dry_run)
    except (verifier.VerificationFailure, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        print('TEST_TLS_RENEWAL_FAILED:' + safe_failure(error), file=sys.stderr)
        sys.exit(2)
