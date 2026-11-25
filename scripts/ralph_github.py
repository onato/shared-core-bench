"""The Ralph loop's GitHub, through `gh`: issues, board status, PRs and comments."""

import json
import subprocess
from collections.abc import Callable

from gates import approved_by
from ralph import GitHubError, Issue, PullRequest

Run = Callable[[list[str], str | None], str]
FAILURE = "FAILURE"
AGENT_MARKER = "> 🤖 Agent comment — written by Claude Code"
DRAFT, PLAN_APPROVED = "draft", "plan-approved"


def gh_runner(subprocess_run: Callable[..., subprocess.CompletedProcess]) -> Run:
    def run(args: list[str], stdin: str | None) -> str:
        result = subprocess_run(["gh", *args], input=stdin, capture_output=True, text=True)
        if result.returncode != 0:
            command = " ".join(arg.partition("\n")[0] + "…" if "\n" in arg else arg for arg in args)
            raise GitHubError(f"gh {command}: {result.stderr.strip()}")
        return result.stdout

    return run


MILESTONE_QUERY = """
query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    issue(number: $number) {
      subIssues(first: 100) {
        nodes {
          number
          state
          labels(first: 20) { nodes { name } }
          blockedBy(first: 20) { nodes { number state } }
          projectItems(first: 5) {
            nodes {
              project { number }
              fieldValueByName(name: "Status") {
                ... on ProjectV2ItemFieldSingleSelectValue { name }
              }
            }
          }
        }
      }
    }
  }
}
"""

WORKFLOWS_QUERY = """
query($owner: String!, $project: Int!) {
  user(login: $owner) {
    projectV2(number: $project) { workflows(first: 20) { nodes { name enabled } } }
  }
}
"""


STATUS_LOOKUP_QUERY = """
query($owner: String!, $repo: String!, $number: Int!, $project: Int!) {
  user(login: $owner) {
    projectV2(number: $project) {
      id
      field(name: "Status") { ... on ProjectV2SingleSelectField { id options { id name } } }
    }
  }
  repository(owner: $owner, name: $repo) {
    issue(number: $number) { id projectItems(first: 20) { nodes { id project { number } } } }
  }
}
"""

SET_STATUS_MUTATION = """
mutation($project: ID!, $item: ID!, $field: ID!, $option: String!) {
  updateProjectV2ItemFieldValue(input: {
    projectId: $project, itemId: $item, fieldId: $field,
    value: { singleSelectOptionId: $option }
  }) { projectV2Item { id } }
}
"""


ADD_ITEM_MUTATION = """
mutation($project: ID!, $content: ID!) {
  addProjectV2ItemById(input: { projectId: $project, contentId: $content }) { item { id } }
}
"""


PULL_REQUEST_QUERY = """
query($owner: String!, $repo: String!, $number: Int!) {
  repository(owner: $owner, name: $repo) {
    pullRequest(number: $number) {
      merged
      mergeStateStatus
      headRefName
      comments(last: 100) { nodes { body } }
      commits(last: 100) {
        nodes {
          commit {
            statusCheckRollup {
              contexts(first: 50) { nodes { ... on CheckRun { name conclusion } } }
            }
          }
        }
      }
    }
  }
}
"""


OPEN_PRS_QUERY = """
query($owner: String!, $repo: String!, $number: Int!, $branch: String!) {
  repository(owner: $owner, name: $repo) {
    issue(number: $number) {
      closedByPullRequestsReferences(first: 20, includeClosedPrs: false) { nodes { state } }
    }
    pullRequests(headRefName: $branch, states: OPEN) { totalCount }
  }
}
"""


class GitHubAdapter:
    def __init__(self, owner: str, repo: str, project_number: int, run: Run):
        self.owner = owner
        self.repo = repo
        self.project_number = project_number
        self.run = run

    def board_workflows(self) -> dict[str, bool]:
        data = self._graphql(WORKFLOWS_QUERY, owner=self.owner, project=self.project_number)
        nodes = data["user"]["projectV2"]["workflows"]["nodes"]
        return {node["name"]: node["enabled"] for node in nodes}

    def milestone_issues(self, milestone: int) -> list[Issue]:
        data = self._graphql(MILESTONE_QUERY, owner=self.owner, repo=self.repo, number=milestone)
        return [self._issue(node) for node in data["repository"]["issue"]["subIssues"]["nodes"]]

    def _issue(self, node: dict) -> Issue:
        return Issue(
            number=node["number"],
            status=self._status(node["projectItems"]["nodes"]),
            is_open=node["state"] == "OPEN",
            open_blockers=tuple(
                b["number"] for b in node["blockedBy"]["nodes"] if b["state"] == "OPEN"
            ),
            draft=any(label["name"] == DRAFT for label in node["labels"]["nodes"]),
        )

    def _status(self, items: list[dict]) -> str:
        for item in items:
            if item["project"]["number"] == self.project_number and item["fieldValueByName"]:
                return item["fieldValueByName"]["name"]
        return "No status"

    def pull_request(self, number: int) -> PullRequest:
        data = self._graphql(PULL_REQUEST_QUERY, owner=self.owner, repo=self.repo, number=number)
        pr = data["repository"]["pullRequest"]
        commits = [check_runs(node["commit"]) for node in pr["commits"]["nodes"]]
        return PullRequest(
            number=number,
            merged=pr["merged"],
            changes_requested=sum(runs.get("claude-review") == FAILURE for runs in commits),
            ci_failure_streak=trailing_failures([runs["ci"] for runs in commits if "ci" in runs]),
            has_retro_notes=any("## Retro notes" in c["body"] for c in pr["comments"]["nodes"]),
            behind=pr["mergeStateStatus"] == "BEHIND",
            branch=pr["headRefName"],
        )

    def has_open_pr(self, issue: int) -> bool:
        """`includeClosedPrs: false` still lists merged PRs, so their state is checked here."""
        data = self._graphql(
            OPEN_PRS_QUERY,
            owner=self.owner,
            repo=self.repo,
            number=issue,
            branch=f"swilliams/{issue}-ralph",
        )["repository"]
        closing = data["issue"]["closedByPullRequestsReferences"]["nodes"]
        return data["pullRequests"]["totalCount"] > 0 or any(
            pr["state"] == "OPEN" for pr in closing
        )

    def set_status(self, issue: int, status: str) -> None:
        data = self._graphql(
            STATUS_LOOKUP_QUERY,
            owner=self.owner,
            repo=self.repo,
            number=issue,
            project=self.project_number,
        )
        board = data["user"]["projectV2"]
        field = board["field"]
        option = next((o["id"] for o in field["options"] if o["name"] == status), None)
        if option is None:
            raise GitHubError(f"project {self.project_number} has no Status option {status!r}")
        content = data["repository"]["issue"]
        items = content["projectItems"]["nodes"]
        item = next((i["id"] for i in items if i["project"]["number"] == self.project_number), None)
        if item is None:
            added = self._graphql(ADD_ITEM_MUTATION, project=board["id"], content=content["id"])
            item = added["addProjectV2ItemById"]["item"]["id"]
        self._graphql(
            SET_STATUS_MUTATION, project=board["id"], item=item, field=field["id"], option=option
        )

    def comment(self, issue: int, body: str) -> str:
        repo = f"{self.owner}/{self.repo}"
        args = ["issue", "comment", str(issue), "-R", repo, "--body-file", "-"]
        return self.run(args, f"{AGENT_MARKER}\n\n{body}").strip()

    def plan_approved(self, parent: int) -> bool:
        path = f"repos/{self.owner}/{self.repo}/issues/{parent}/events"
        lines = self.run(["api", "--paginate", path, "--jq", ".[]"], None).splitlines()
        return approved_by([json.loads(line) for line in lines], PLAN_APPROVED, self.owner)

    def undraft(self, issue: int) -> None:
        repo = f"{self.owner}/{self.repo}"
        self.run(["issue", "edit", str(issue), "-R", repo, "--remove-label", DRAFT], None)

    def _graphql(self, query: str, **variables: object) -> dict:
        args = ["api", "graphql", "-f", f"query={query}"]
        for name, value in variables.items():
            args += ["-F", f"{name}={value}"]
        return json.loads(self.run(args, None))["data"]


def check_runs(commit: dict) -> dict[str, str]:
    """Conclusion by check name; a commit pushed with others may have no runs (a null rollup)."""
    rollup = commit["statusCheckRollup"]
    nodes = rollup["contexts"]["nodes"] if rollup else []
    return {run["name"]: run["conclusion"] for run in nodes if "name" in run}


def trailing_failures(conclusions: list[str]) -> int:
    streak = 0
    for conclusion in reversed(conclusions):
        if conclusion != FAILURE:
            break
        streak += 1
    return streak
