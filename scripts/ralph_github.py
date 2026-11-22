"""The Ralph loop's GitHub, through `gh`: milestone issues and the board's workflows."""

import json
import subprocess
from collections.abc import Callable

from ralph import GitHubError, Issue

Run = Callable[[list[str], str | None], str]


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
        )

    def _status(self, items: list[dict]) -> str:
        for item in items:
            if item["project"]["number"] == self.project_number and item["fieldValueByName"]:
                return item["fieldValueByName"]["name"]
        return "No status"

    def _graphql(self, query: str, **variables: object) -> dict:
        args = ["api", "graphql", "-f", f"query={query}"]
        for name, value in variables.items():
            args += ["-F", f"{name}={value}"]
        return json.loads(self.run(args, None))["data"]
