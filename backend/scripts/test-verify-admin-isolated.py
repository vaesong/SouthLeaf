"""Deterministic harness regression tests. No MySQL, Maven, DPAPI, or real server is started."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

spec = importlib.util.spec_from_file_location(
    "isolated_verifier", Path(__file__).with_name("verify-admin-isolated.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


class LifecycleTests(unittest.TestCase):
    def setUp(self):
        self.stack = contextlib.ExitStack()
        self.addCleanup(self.stack.close)
        self.root = Path(self.stack.enter_context(tempfile.TemporaryDirectory()))
        self.repo = self.root / "repo"
        self.fixture = self.root / "owned"
        self.fixture.mkdir()
        self.manifest = self.fixture / "manifest.json"
        self.manifest.write_text('{}')
        self.jar = self.repo / 'backend/admin-runtime/target/admin-runtime-0.1.0-SNAPSHOT.jar'
        cli = self.repo / 'backend/admin-migration-cli/target/admin-migration-cli-0.1.0-SNAPSHOT.jar'
        for path in (self.jar, cli):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'synthetic prepared artifact')
        self.meta = {"root": str(self.fixture), "port": 19377,
                     "serverUuid": "d477e4b4-710b-4a98-bd49-7b32f31a90bb", "testCaSha256": "synthetic"}
        self.passwords = {"root": "SYNTHETIC_NOT_A_REAL_PASSWORD"}
        self.server = mock.Mock()
        self.server.poll.return_value = None
        self.mocks = {}
        for name, value in {
            "load_manifest": (self.manifest, self.meta, self.passwords),
            "source_snapshot": {"synthetic-source": "a" * 64},
            "start_server": self.server, "migrate": None, "sql": b'',
            "prepare_owned_public_fixture": {"SOUTHLEAF_PUBLIC_IT_ISOLATED": "synthetic"},
            "run_reactor_verification": "b" * 64,
            "run_owned_public_smoke": {"status": "PASS"},
            "pin_candidate": {"path": self.jar, "sha256": "c" * 64,
                              "probeHashes": {"synthetic/Probe.class": "d" * 64}, "classpath": "synthetic"},
            "verify_probe_matrix": None, "assert_candidate_unchanged": None, "stop_server": None,
        }.items():
            self.mocks[name] = self.stack.enter_context(mock.patch.object(verifier, name, return_value=value))
        self.stack.enter_context(mock.patch.object(verifier, 'REPO', self.repo))
        self.stack.enter_context(mock.patch.object(verifier, 'JAR', self.jar))
        (self.fixture / 'verification.json').write_text('{"result":"PASS","runId":"stale"}')
        (self.fixture / 'verification-partial.json').write_text('{"result":"FAILED","runId":"stale"}')

    def run_verify(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()):
            verifier.verify(self.manifest, **kwargs)

    def failed(self):
        self.assertFalse((self.fixture / 'verification.json').exists())
        result = json.loads((self.fixture / 'verification-partial.json').read_text())
        self.assertEqual(result['result'], 'FAILED')
        self.assertNotEqual(result['runId'], 'stale')
        self.assertNotIn(self.passwords['root'], json.dumps(result))
        return result

    def test_migration_failure_always_stops_owned_server_and_invalidates_pass(self):
        self.mocks['migrate'].side_effect = verifier.VerificationFailure('MIGRATION_FAILED')
        with self.assertRaises(verifier.VerificationFailure): self.run_verify()
        self.mocks['stop_server'].assert_called_once()
        self.mocks['pin_candidate'].assert_not_called()
        self.assertEqual(self.failed()['failedStep'], 'owned-v3-migration')

    def test_grant_failure_is_inside_cleanup_coverage(self):
        def fail_grant(meta, password, statement):
            if statement.startswith('GRANT '): raise verifier.VerificationFailure('GRANT_FAILED')
            return b''
        self.mocks['sql'].side_effect = fail_grant
        with self.assertRaises(verifier.VerificationFailure): self.run_verify()
        self.mocks['stop_server'].assert_called_once()
        self.assertEqual(self.failed()['failedStep'], 'owned-auth-event-incremental-grants')

    def test_start_failure_does_not_claim_a_successful_shutdown(self):
        self.mocks['start_server'].side_effect = OSError('SYNTHETIC_SECRET_MUST_NOT_APPEAR')
        with self.assertRaises(OSError): self.run_verify()
        self.mocks['stop_server'].assert_not_called()
        report = self.failed()
        self.assertEqual(report['shutdown']['status'], 'NO_RETURNED_SERVER_HANDLE')
        self.assertNotIn('SYNTHETIC_SECRET_MUST_NOT_APPEAR', json.dumps(report))

    def test_reactor_failure_cleans_up_and_never_pins_a_candidate(self):
        self.mocks['run_reactor_verification'].side_effect = verifier.VerificationFailure('REACTOR_FAILED')
        with self.assertRaises(verifier.VerificationFailure): self.run_verify(with_reactor=True)
        self.mocks['stop_server'].assert_called_once()
        self.mocks['pin_candidate'].assert_not_called()
        self.assertEqual(self.failed()['reactor']['status'], 'FAILED')

    def test_reactor_runs_before_pinning_and_probes(self):
        events = []
        self.mocks['run_reactor_verification'].side_effect = lambda *a: events.append('build') or 'b' * 64
        candidate = self.mocks['pin_candidate'].return_value
        self.mocks['pin_candidate'].side_effect = lambda *a: events.append('pin') or candidate
        self.mocks['verify_probe_matrix'].side_effect = lambda *a: events.append('probes')
        self.run_verify(with_reactor=True)
        self.assertEqual(events, ['build', 'pin', 'probes'])
        result = json.loads((self.fixture / 'verification.json').read_text())
        self.assertEqual(result['reactor']['status'], 'PASS')
        self.assertEqual(result['c2Acceptance'], 'PASS_OWNED_PUBLIC_DB_AND_NATIVE_REGRESSION')

    def test_default_run_does_not_claim_full_reactor_acceptance(self):
        self.run_verify()
        self.mocks['run_reactor_verification'].assert_not_called()
        result = json.loads((self.fixture / 'verification.json').read_text())
        self.assertEqual(result['reactor']['status'], 'NOT_RUN')
        self.assertNotIn('No password login/', json.dumps(result))
        self.assertEqual(result['shutdown']['status'], 'COMPLETE')
        self.assertFalse((self.fixture / 'verification-partial.json').exists())

    def test_probe_failure_cleans_up_without_copying_raw_error(self):
        self.mocks['verify_probe_matrix'].side_effect = RuntimeError('RAW_SECRET_SENTINEL')
        with self.assertRaises(RuntimeError): self.run_verify()
        self.mocks['stop_server'].assert_called_once()
        self.assertNotIn('RAW_SECRET_SENTINEL', json.dumps(self.failed()))

    def test_candidate_drift_is_not_a_pass(self):
        self.mocks['assert_candidate_unchanged'].side_effect = verifier.VerificationFailure('CANDIDATE_CHANGED')
        with self.assertRaises(verifier.VerificationFailure): self.run_verify()
        self.assertEqual(self.failed()['failure'], 'CANDIDATE_CHANGED')

    def test_source_change_before_pinning_aborts(self):
        self.mocks['source_snapshot'].side_effect = [{'x': 'old'}, {'x': 'new'}]
        with self.assertRaises(verifier.VerificationFailure): self.run_verify()
        self.mocks['pin_candidate'].assert_not_called()
        self.failed()

    def test_source_change_after_probes_is_not_a_pass(self):
        self.mocks['source_snapshot'].side_effect = [{'x': 'old'}, {'x': 'old'}, {'x': 'new'}]
        with self.assertRaises(verifier.VerificationFailure): self.run_verify()
        self.assertEqual(self.failed()['failure'], 'SOURCE_CHANGED_DURING_VERIFICATION')

    def test_cleanup_failure_blocks_pass_and_manifest_completion(self):
        self.mocks['stop_server'].side_effect = verifier.VerificationFailure('CLEANUP_FAILED')
        with self.assertRaises(verifier.VerificationFailure): self.run_verify()
        self.assertEqual(self.failed()['shutdown']['status'], 'FAILED')
        self.assertEqual(json.loads(self.manifest.read_text()), {})

    def test_restore_failure_still_attempts_owned_shutdown(self):
        def fail_restore(meta, password, statement):
            if statement.startswith('SET GLOBAL '):
                raise verifier.VerificationFailure('RESTORE_FAILED')
            return b''
        self.mocks['sql'].side_effect = fail_restore
        with self.assertRaisesRegex(verifier.VerificationFailure, 'RESTORE_FAILED'):
            self.run_verify()
        self.mocks['stop_server'].assert_called_once()
        self.assertEqual(self.failed()['shutdown']['status'], 'FAILED')

    def test_primary_and_cleanup_failures_are_both_retained(self):
        self.mocks['migrate'].side_effect = verifier.VerificationFailure('PRIMARY_FAILED')
        self.mocks['stop_server'].side_effect = verifier.VerificationFailure('CLEANUP_FAILED')
        with self.assertRaisesRegex(verifier.VerificationFailure, 'PRIMARY_FAILED'): self.run_verify()
        report = self.failed()
        self.assertEqual(report['failure'], 'PRIMARY_FAILED')
        self.assertEqual(report['cleanupFailure'], 'CLEANUP_FAILED')


class CandidateAndOutputTests(unittest.TestCase):
    def fixture(self, root, unsafe=False):
        repo = root / 'repo'
        jar = repo / 'backend/admin-runtime/target/admin-runtime-0.1.0-SNAPSHOT.jar'
        jar.parent.mkdir(parents=True)
        classes = jar.parent / 'classes'
        (classes / 'synthetic').mkdir(parents=True)
        (classes / 'synthetic/Main.class').write_bytes(b'main')
        tests = jar.parent / 'test-classes/synthetic'
        tests.mkdir(parents=True)
        for name in ('AdminRuntimeIsolatedProbe.class', 'AdminCredentialFixtureHash.class'):
            (tests / name).write_bytes(name.encode())
        with zipfile.ZipFile(jar, 'w') as archive:
            archive.writestr('BOOT-INF/classes/synthetic/Main.class', b'main')
            archive.writestr('BOOT-INF/lib/synthetic.jar', b'library')
            if unsafe: archive.writestr('BOOT-INF/classes/../../escape.class', b'escape')
        return repo, jar

    def test_probe_inputs_are_copied_from_the_fixed_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp);repo, jar = self.fixture(root)
            with mock.patch.object(verifier, 'REPO', repo), mock.patch.object(verifier, 'JAR', jar):
                candidate = verifier.pin_candidate(root, 'synthetic-run')
                verifier.assert_candidate_unchanged(candidate)
                original = candidate['path'].read_bytes()
                jar.write_bytes(b'changed after pinning')
                self.assertEqual(candidate['path'].read_bytes(), original)
                with self.assertRaisesRegex(verifier.VerificationFailure, 'CANDIDATE_CHANGED'):
                    verifier.assert_candidate_unchanged(candidate)

    def test_probe_class_mutation_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp);repo, jar = self.fixture(root)
            with mock.patch.object(verifier, 'REPO', repo), mock.patch.object(verifier, 'JAR', jar):
                candidate = verifier.pin_candidate(root, 'synthetic-run')
                path = jar.parent / 'test-classes/synthetic/AdminRuntimeIsolatedProbe.class'
                path.write_bytes(b'changed')
                with self.assertRaisesRegex(verifier.VerificationFailure, 'PROBE_CLASSES_CHANGED'):
                    verifier.assert_candidate_unchanged(candidate)

    def test_unsafe_jar_paths_cannot_escape_the_private_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp);repo, jar = self.fixture(root, unsafe=True)
            with mock.patch.object(verifier, 'REPO', repo), mock.patch.object(verifier, 'JAR', jar):
                with self.assertRaisesRegex(verifier.VerificationFailure, 'UNSAFE_CANDIDATE_ENTRY'):
                    verifier.pin_candidate(root, 'synthetic-run')
                self.assertFalse((root / 'escape.class').exists())

    def test_atomic_report_failure_leaves_no_new_result_or_temporary_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'verification.json'
            with mock.patch.object(verifier.os, 'replace', side_effect=OSError('synthetic')):
                with self.assertRaises(OSError): verifier.atomic_json(path, {'result': 'PASS'})
            self.assertFalse(path.exists())
            self.assertEqual(list(path.parent.glob('*.tmp')), [])

    def test_reactor_timeout_cleans_up_only_its_owned_command(self):
        process = mock.Mock()
        process.communicate.side_effect = subprocess.TimeoutExpired(['synthetic-command'], 1)
        meta = {'root': 'synthetic', 'port': 19377, 'serverUuid': 'synthetic'}
        with mock.patch.object(verifier.subprocess, 'Popen', return_value=process), \
                mock.patch.object(verifier, 'stop_owned_command') as stop:
            with self.assertRaises(subprocess.TimeoutExpired):
                verifier.run_reactor_verification(meta, {'s1b_migrator': 'synthetic-only'})
            stop.assert_called_once_with(process)

    def test_reactor_receives_only_explicit_isolated_management_inputs(self):
        process = mock.Mock(returncode=0)
        process.communicate.return_value = (b'synthetic-success', None)
        meta = {'root': 'synthetic', 'port': 19377, 'serverUuid': 'synthetic'}
        with mock.patch.object(verifier.subprocess, 'Popen', return_value=process) as popen:
            result = verifier.run_reactor_verification(meta, {'s1b_migrator': 'synthetic-only'})
            self.assertEqual(result, hashlib.sha256(b'synthetic-success').hexdigest())
            values = popen.call_args.kwargs['env']
            self.assertIn(':19377/', values['SOUTHLEAF_SECURITY_IT_URL'])
            self.assertEqual(values['SOUTHLEAF_SECURITY_IT_EXPECTED_USER'], 's1b_migrator@localhost')
            self.assertNotIn('SOUTHLEAF_DB_PASSWORD', values)

    def test_reactor_forwards_only_the_named_booking_and_c3_inputs(self):
        process = mock.Mock(returncode=0)
        process.communicate.return_value = (b'synthetic-success', None)
        meta = {'root': 'synthetic', 'port': 19377, 'serverUuid': 'synthetic'}
        forwarded = {
            'SOUTHLEAF_BOOKING_IT_URL': 'jdbc:mysql://127.0.0.1:3307/southleaf_booking_it?sslMode=DISABLED',
            'SOUTHLEAF_BOOKING_IT_USERNAME': 'booking_it',
            'SOUTHLEAF_BOOKING_IT_PASSWORD': 'synthetic-booking',
            'SOUTHLEAF_BOOKING_IT_INSTANCE_ID': 'synthetic-instance',
            'SOUTHLEAF_C3_IT_MODE': 'demo',
            'SOUTHLEAF_C3_IT_DB_URL': 'jdbc:mysql://localhost:19378/southleaf_admin_c3_it?sslMode=VERIFY_IDENTITY',
            'SOUTHLEAF_C3_IT_DB_USERNAME': 'c3_cli',
            'SOUTHLEAF_C3_IT_DB_PASSWORD': 'synthetic-c3',
            'SOUTHLEAF_C3_IT_DB_SCHEMA': 'southleaf_admin_c3_it',
            'SOUTHLEAF_C3_IT_DB_EXPECTED_USER': 'c3_cli@localhost',
            'SOUTHLEAF_UNRELATED_SECRET': 'must-stay-out'}
        with mock.patch.dict(verifier.os.environ, forwarded, clear=False), \
                mock.patch.object(verifier.subprocess, 'Popen', return_value=process) as popen:
            verifier.run_reactor_verification(meta, {'s1b_migrator': 'synthetic-only'})
            values = popen.call_args.kwargs['env']
            for key, value in forwarded.items():
                if key == 'SOUTHLEAF_UNRELATED_SECRET':
                    self.assertNotIn(key, values)
                else:
                    self.assertEqual(values[key], value)

    def test_full_reactor_entry_explicitly_disables_test_skips(self):
        entry = (verifier.REPO / 'backend/scripts/Test-DatabaseIntegration.ps1').read_text()
        for flag in ('-DskipTests=false', '-Dmaven.test.skip=false', '-DskipITs=false', '-Pc3-isolated-it'):
            self.assertIn(flag, entry)
        self.assertIn('SOUTHLEAF_BOOKING_IT_URL', entry)
        self.assertIn('SOUTHLEAF_C3_IT_DB_URL', entry)

    def test_reactor_errors_are_hashed_not_echoed(self):
        process = mock.Mock(returncode=1)
        process.communicate.return_value = (b'SYNTHETIC_SECRET_MUST_NOT_LEAK', None)
        meta = {'root': 'synthetic', 'port': 19377, 'serverUuid': 'synthetic'}
        with mock.patch.object(verifier.subprocess, 'Popen', return_value=process) as popen:
            with self.assertRaises(verifier.VerificationFailure) as caught:
                verifier.run_reactor_verification(meta, {'s1b_migrator': 'synthetic-only'})
            self.assertNotIn('SYNTHETIC_SECRET', str(caught.exception))
            command = popen.call_args.args[0]
            self.assertIn('Test-DatabaseIntegration.ps1', command[-1])
            self.assertNotIn('install', command)


@unittest.skipUnless(os.name == 'nt', 'PowerShell entry preflight runs on the Windows host')
class PowerShellPreflightTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.truststore = Path(self.temp.name) / 'synthetic.jks'
        self.truststore.write_bytes(b'input-presence-only; not a certificate validation')
        self.values = {
            'SOUTHLEAF_SECURITY_IT_URL': 'jdbc:mysql://localhost:19377/southleaf_admin_s1b_it?sslMode=VERIFY_IDENTITY&allowPublicKeyRetrieval=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
            'SOUTHLEAF_SECURITY_IT_USERNAME': 'synthetic_user',
            'SOUTHLEAF_SECURITY_IT_PASSWORD': 'synthetic-not-a-real-secret',
            'SOUTHLEAF_SECURITY_IT_EXPECTED_USER': 'synthetic_user@localhost',
            'SOUTHLEAF_SECURITY_IT_UUID': 'd477e4b4-710b-4a98-bd49-7b32f31a90bb',
            'SOUTHLEAF_SECURITY_IT_TRUSTSTORE': str(self.truststore)}

    def run_entry(self):
        return subprocess.run(['powershell', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File',
                               str(verifier.REPO / 'backend/scripts/Test-DatabaseIntegration.ps1'), '-ValidateOnly'],
                              env=verifier.clean_env(self.values), stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)

    def test_valid_input_preflight_has_no_connection_or_credential_store_access(self):
        result = self.run_entry()
        self.assertEqual(result.returncode, 0, result.stdout.decode(errors='replace'))
        self.assertIn(b'SECURITY_IT_ENVIRONMENT_VALIDATED_NO_CONNECTION', result.stdout)

    def test_missing_inputs_fail_before_maven_or_credential_loading(self):
        self.values = {}
        self.assertNotEqual(self.run_entry().returncode, 0)

    def test_original_server_port_is_rejected(self):
        self.values['SOUTHLEAF_SECURITY_IT_URL'] = self.values['SOUTHLEAF_SECURITY_IT_URL'].replace(':19377/', ':3306/')
        self.assertNotEqual(self.run_entry().returncode, 0)

    def test_wrong_schema_is_rejected(self):
        self.values['SOUTHLEAF_SECURITY_IT_URL'] = self.values['SOUTHLEAF_SECURITY_IT_URL'].replace('southleaf_admin_s1b_it', 'southleaf_public')
        self.assertNotEqual(self.run_entry().returncode, 0)

    def test_invalid_uuid_is_rejected(self):
        self.values['SOUTHLEAF_SECURITY_IT_UUID'] = 'not-a-uuid'
        self.assertNotEqual(self.run_entry().returncode, 0)

    def test_wrong_identity_is_rejected(self):
        self.values['SOUTHLEAF_SECURITY_IT_EXPECTED_USER'] = 'other@localhost'
        self.assertNotEqual(self.run_entry().returncode, 0)

    def test_missing_truststore_is_rejected(self):
        self.truststore.unlink()
        self.assertNotEqual(self.run_entry().returncode, 0)


if __name__ == '__main__':
    unittest.main()
