import contextlib
import io
import json
import runpy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


ADAPTER_DIRECTORY = Path(__file__).resolve().parent


class AdapterChangedScopeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.base = Path(self.temporary.name)
        self.bundle = self.base / "bundle"
        self.bundle.mkdir()
        self.project = self.base / "project"
        (self.project / "src/main/java").mkdir(parents=True)
        (self.project / "pom.xml").write_text("<project/>\n")
        (self.project / "src/main/java/Example.java").write_text("class Example {\n    int answer() { return 1; }\n}\n")
        self.version = (ADAPTER_DIRECTORY / "version").read_text().strip()
        (self.bundle / "home").write_text(str(self.base) + "\n")
        (self.bundle / "sentinel-tool.json").write_text(json.dumps({"language": "java", "version": self.version}))
        self.main = runpy.run_path(str(ADAPTER_DIRECTORY / "sentinel-tool"))["main"]
        # The gates now write native JSON reports to the supplied binary stream.
        # Keep these shapes aligned with SelfCrapMain and MutationCommandMain.
        self.crap_report = {
            "schemaVersion": "sentinel-java-self-crap-v1", "passed": True,
            "crapMax": "8", "total": 1, "known": 1, "unknown": 0, "aboveLimit": 0,
            "functions": [{
                "file": "src/main/java/Example.java", "function": "Example.answer",
                "id": "java:v1:" + "a" * 64, "line": 2, "endLine": 2,
                "complexity": 1, "coveredUnits": 2, "totalUnits": 2,
                "coverageBasis": "jacoco-instruction", "score": "1",
                "pass": True, "reason": "passed",
            }],
        }
        self.mutation_report = {
            "inScope": 1, "killed": 1, "survived": 0, "uncovered": 0,
            "timedOut": 0, "compileError": 0, "runtimeError": 0, "pending": 0,
            "ignored": 0, "toolError": 0, "unauthorizedExclusion": 0,
            "mutationMin": "90", "pass": True,
            "mutants": [{
                "id": "mutant-1", "file": "src/main/java/Example.java", "line": 2,
                "description": "replaced return value with 0", "status": "killed",
            }],
        }
        self.crap = Mock(side_effect=self.emit_crap, return_value=0)
        self.mutation = Mock(side_effect=self.emit_mutation, return_value=0)
        self.jobs = Mock(side_effect=lambda home, first, second, mode: (first(), second()))

    def emit_crap(self, *arguments):
        arguments[-1].write(json.dumps(self.crap_report).encode("utf-8"))
        return self.crap.return_value

    def emit_mutation(self, *arguments):
        arguments[-1].write(json.dumps(self.mutation_report).encode("utf-8"))
        return self.mutation.return_value

    def tearDown(self):
        self.temporary.cleanup()

    def check(self, changed, selection=None, execution_mode="sequential"):
        request = {
            "protocolVersion": "sentinel-tool-protocol-v1", "requestId": "adapter-test", "command": "check",
            "moduleId": "module", "language": "java", "projectRoot": str(self.project), "config": None,
        }
        if execution_mode is not None:
            request["executionMode"] = execution_mode
        if changed is not None:
            request["changedFiles"] = changed
        if selection is not None:
            request["selection"] = selection
        output = io.StringIO()
        overrides = {
            "__file__": str(self.bundle / "sentinel-tool"),
            "load_locks": lambda home: {"java_home": self.base, "maven_home": self.base},
            "maven_repository": lambda locks, project: self.base,
            "crap_gate": self.crap, "mutation_gate": self.mutation,
            "run_jobs": self.jobs,
            "function_lines": lambda *args: [2] if args[4] else [],
        }
        with patch.dict(self.main.__globals__, overrides), patch("sys.stdin", io.StringIO(json.dumps(request))):
            with contextlib.redirect_stdout(output):
                exit_code = self.main()
        response = json.loads(output.getvalue())
        self.assertEqual(response["exitCode"], exit_code)
        return exit_code, response

    def test_default_and_explicit_modes_reach_the_supervisor(self):
        for requested, expected in ((None, "parallel"), ("parallel", "parallel"), ("sequential", "sequential")):
            with self.subTest(requested=requested):
                self.jobs.reset_mock()
                code, response = self.check(None, execution_mode=requested)
                self.assertEqual(code, 0)
                self.assertEqual(response["executionMode"], expected)
                self.assertEqual(self.jobs.call_args.args[3], expected)

    def test_invalid_mode_runs_no_measurement(self):
        code, response = self.check(None, execution_mode="automatic")
        self.assertEqual((code, response["status"]), (3, "usageConfigError"))
        self.jobs.assert_not_called()

    def test_project_error_still_acknowledges_execution_mode(self):
        (self.project / "pom.xml").unlink()
        code, response = self.check(None)
        self.assertEqual((code, response["status"]), (3, "usageConfigError"))
        self.assertEqual(response["executionMode"], "sequential")
        self.jobs.assert_not_called()

    def test_nonproduction_changes_do_not_run_or_pass_quality_gates(self):
        exit_code, response = self.check(["README.md"])
        self.assertEqual(exit_code, 0)
        self.assertEqual(response["status"], "noChanges")
        self.assertFalse(response["passed"])
        self.crap.assert_not_called()
        self.mutation.assert_not_called()

    def test_changed_production_and_full_checks_still_run_both_gates(self):
        for changed in (["src/main/java/Example.java"], None):
            with self.subTest(changed=changed):
                self.crap.reset_mock()
                self.mutation.reset_mock()
                exit_code, response = self.check(changed)
                self.assertEqual(exit_code, 0)
                self.assertEqual(response["status"], "passed")
                self.assertTrue(response["passed"])
                self.crap.assert_called_once()
                self.mutation.assert_called_once()
                self.assertEqual(response["details"]["crap"], self.crap_report)
                self.assertEqual(response["details"]["mutation"]["killed"], 1)
                self.assertEqual(response["details"]["mutation"]["mutants"][0]["function"], "Example.answer")

    def test_quality_failure_remains_a_failure(self):
        self.mutation.return_value = 2
        self.mutation_report.update(killed=0, survived=1, **{"pass": False})
        self.mutation_report["mutants"][0]["status"] = "survived"
        exit_code, response = self.check(["src/main/java/Example.java"])
        self.assertEqual(exit_code, 2)
        self.assertEqual(response["status"], "qualityFailed")
        self.assertFalse(response["passed"])
        self.assertFalse(response["details"]["mutation"]["pass"])
        self.assertEqual(response["details"]["mutation"]["survived"], 1)

    def test_empty_report_cannot_pass_even_when_gate_returns_success(self):
        for missing in ("crap", "mutation"):
            with self.subTest(missing=missing):
                self.crap.reset_mock()
                self.mutation.reset_mock()
                self.crap.side_effect = self.emit_crap
                self.mutation.side_effect = self.emit_mutation
                getattr(self, missing).side_effect = None
                exit_code, response = self.check(None)
                self.assertEqual(exit_code, 6)
                self.assertEqual(response["status"], "backendError")
                self.assertFalse(response["passed"])
                self.assertNotIn("details", response)
                if missing == "crap":
                    self.mutation.assert_not_called()

    def test_malformed_json_report_remains_a_backend_failure(self):
        def malformed(*arguments):
            arguments[-1].write(b"not-json\n")
            return 0

        for invalid in ("crap", "mutation"):
            with self.subTest(invalid=invalid):
                self.crap.side_effect = self.emit_crap
                self.mutation.side_effect = self.emit_mutation
                getattr(self, invalid).side_effect = malformed
                exit_code, response = self.check(None)
                self.assertEqual(exit_code, 6)
                self.assertFalse(response["passed"])
                self.assertNotIn("details", response)

    def test_missing_report_file_remains_a_backend_failure(self):
        def remove_report(*arguments):
            output = arguments[-1]
            output.close()
            Path(output.name).unlink()
            return 0

        for missing in ("crap", "mutation"):
            with self.subTest(missing=missing):
                self.crap.side_effect = self.emit_crap
                self.mutation.side_effect = self.emit_mutation
                getattr(self, missing).side_effect = remove_report
                exit_code, response = self.check(None)
                self.assertEqual(exit_code, 6)
                self.assertFalse(response["passed"])
                self.assertNotIn("details", response)

    def test_selected_function_and_tests_reach_both_gates(self):
        test_file = self.project / "src/test/java/ExampleTest.java"
        test_file.parent.mkdir(parents=True)
        test_file.write_text("class ExampleTest {}\n")
        selection = {
            "files": ["src/main/java/Example.java"], "functions": ["Example.answer"],
            "tests": ["src/test/java/ExampleTest.java"],
        }
        exit_code, response = self.check(None, selection)
        self.assertEqual(exit_code, 0)
        self.assertEqual(response["selection"], selection)
        self.assertEqual(response["details"]["scope"], {**selection, "testSelection": "explicit"})
        self.assertEqual(self.crap.call_args.args[7:9], (["Example.answer"], ["ExampleTest"]))
        self.assertEqual(self.mutation.call_args.args[8:10], ([2], ["ExampleTest"]))


if __name__ == "__main__":
    unittest.main()
