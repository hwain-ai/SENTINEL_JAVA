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
        (self.project / "src/main/java/Example.java").write_text("class Example {}\n")
        self.version = (ADAPTER_DIRECTORY / "version").read_text().strip()
        (self.bundle / "home").write_text(str(self.base) + "\n")
        (self.bundle / "sentinel-tool.json").write_text(json.dumps({"language": "java", "version": self.version}))
        self.main = runpy.run_path(str(ADAPTER_DIRECTORY / "sentinel-tool"))["main"]
        self.crap = Mock(return_value=0)
        self.mutation = Mock(return_value=0)

    def tearDown(self):
        self.temporary.cleanup()

    def check(self, changed):
        request = {
            "protocolVersion": "sentinel-tool-protocol-v1", "requestId": "adapter-test", "command": "check",
            "moduleId": "module", "language": "java", "projectRoot": str(self.project), "config": None,
        }
        if changed is not None:
            request["changedFiles"] = changed
        output = io.StringIO()
        overrides = {
            "__file__": str(self.bundle / "sentinel-tool"),
            "load_locks": lambda home: {"java_home": self.base, "maven_home": self.base},
            "maven_repository": lambda locks, project: self.base,
            "crap_gate": self.crap, "mutation_gate": self.mutation,
        }
        with patch.dict(self.main.__globals__, overrides), patch("sys.stdin", io.StringIO(json.dumps(request))):
            with contextlib.redirect_stdout(output):
                exit_code = self.main()
        response = json.loads(output.getvalue())
        self.assertEqual(response["exitCode"], exit_code)
        return exit_code, response

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

    def test_quality_failure_remains_a_failure(self):
        self.mutation.return_value = 2
        exit_code, response = self.check(["src/main/java/Example.java"])
        self.assertEqual(exit_code, 2)
        self.assertEqual(response["status"], "qualityFailed")
        self.assertFalse(response["passed"])


if __name__ == "__main__":
    unittest.main()
