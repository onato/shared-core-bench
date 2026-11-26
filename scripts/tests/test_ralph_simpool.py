import subprocess

from ralph_simpool import SimPool

STATUS = """\
QA Pool 1  1C9E-…  iOS 26.0  Booted    free
issue      scb-120  stopped    for 'android', 1d old, 4.7 GB
issue      scb-125  emulator-5554  free  for 'issue-125 bench', 0d old, 2.4 GB
issue      pixel-demo  stopped    for 'owner', 3d old, 1.1 GB
"""


class FakeSimpool:
    """Answers every `simpool` call with one canned result and records what was run."""

    def __init__(self, returncode=0, stdout="", stderr=""):
        self.result = subprocess.CompletedProcess([], returncode, stdout, stderr)
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append(args)
        return self.result


def installed(tmp_path):
    simpool = tmp_path / "sim-pool" / "scripts" / "simpool"
    simpool.parent.mkdir(parents=True)
    simpool.touch()
    return {"HOME": str(tmp_path / "home"), "SIM_POOL_SKILL": str(tmp_path / "sim-pool")}


def test_the_issues_with_an_emulator_are_read_from_simpool_status(tmp_path):
    run = FakeSimpool(stdout=STATUS)

    issues = SimPool(installed(tmp_path), run).issues()

    assert issues == [120, 125]
    assert run.calls == [[str(tmp_path / "sim-pool" / "scripts" / "simpool"), "status"]]


def test_simpool_is_looked_for_in_the_home_skills_when_sim_pool_skill_is_unset(tmp_path):
    simpool = tmp_path / ".claude" / "skills" / "sim-pool" / "scripts" / "simpool"
    simpool.parent.mkdir(parents=True)
    simpool.touch()
    run = FakeSimpool(stdout=STATUS)

    SimPool({"HOME": str(tmp_path)}, run).issues()

    assert run.calls == [[str(simpool), "status"]]


def test_without_simpool_installed_no_issue_has_an_emulator(tmp_path):
    run = FakeSimpool(stdout=STATUS)

    issues = SimPool({"HOME": str(tmp_path)}, run).issues()

    assert (issues, run.calls) == ([], [])


def test_a_prune_names_the_issues_emulator_and_reports_nothing_once_it_is_deleted(tmp_path):
    run = FakeSimpool(stderr="simpool: deleted scb-120 (4.7 GB)\n")

    failed = SimPool(installed(tmp_path), run).prune(120)

    assert failed is None
    assert run.calls == [[str(tmp_path / "sim-pool" / "scripts" / "simpool"), "prune", "scb-120"]]


def test_a_prune_simpool_refuses_reports_why_the_emulator_stays(tmp_path):
    run = FakeSimpool(stderr="simpool: keeping scb-125: leased to 'issue-125 bench'\n")

    failed = SimPool(installed(tmp_path), run).prune(125)

    assert failed == "simpool: keeping scb-125: leased to 'issue-125 bench'"


def test_a_prune_that_fails_without_a_word_reports_its_exit_status(tmp_path):
    run = FakeSimpool(returncode=1)

    failed = SimPool(installed(tmp_path), run).prune(120)

    assert failed == "simpool prune scb-120 exited 1"
