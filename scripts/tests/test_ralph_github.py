import json
import socket
import subprocess

import pytest
from ralph import GitHubError, Issue, PullRequest
from ralph_github import GitHubAdapter, gh_runner


class FakeGh:
    """Answers each `gh` call with the next canned reply and records what was sent."""

    def __init__(self, *replies):
        self.replies = list(replies)
        self.calls = []

    def __call__(self, args, stdin=None):
        self.calls.append((args, stdin))
        reply = self.replies.pop(0)
        if isinstance(reply, Exception):
            raise reply
        return reply if isinstance(reply, str) else json.dumps({"data": reply})


def adapter(gh):
    return GitHubAdapter("onato", "shared-core-bench", 2, gh)


def sub_issue(number, state="OPEN", status="Todo", blockers=(), project=2):
    return {
        "number": number,
        "state": state,
        "blockedBy": {"nodes": [{"number": n, "state": s} for n, s in blockers]},
        "projectItems": {
            "nodes": [{"project": {"number": project}, "fieldValueByName": {"name": status}}]
        },
    }


def test_milestone_issues_come_from_the_parents_sub_issues_with_status_and_open_blockers():
    gh = FakeGh(
        {
            "repository": {
                "issue": {
                    "subIssues": {
                        "nodes": [
                            sub_issue(6, state="CLOSED", status="Done"),
                            sub_issue(7, blockers=[(6, "CLOSED")]),
                            sub_issue(8, blockers=[(6, "CLOSED"), (7, "OPEN")]),
                            sub_issue(9, status="Todo", project=5),
                        ]
                    }
                }
            }
        }
    )

    issues = adapter(gh).milestone_issues(2)

    assert issues == [
        Issue(6, "Done", is_open=False),
        Issue(7, "Todo"),
        Issue(8, "Todo", open_blockers=(7,)),
        Issue(9, "No status"),
    ]
    args, _ = gh.calls[0]
    assert args[:2] == ["api", "graphql"]
    assert "number=2" in args


def test_board_workflows_maps_each_built_in_workflow_to_whether_it_is_enabled():
    gh = FakeGh(
        {
            "user": {
                "projectV2": {
                    "workflows": {
                        "nodes": [
                            {"name": "Item closed", "enabled": False},
                            {"name": "Auto-add sub-issues to project", "enabled": True},
                        ]
                    }
                }
            }
        }
    )

    workflows = adapter(gh).board_workflows()

    assert workflows == {"Item closed": False, "Auto-add sub-issues to project": True}
    args, _ = gh.calls[0]
    assert "owner=onato" in args
    assert "project=2" in args


def status_lookup(items):
    return {
        "user": {
            "projectV2": {
                "id": "PVT_board",
                "field": {
                    "id": "PVTSSF_status",
                    "options": [
                        {"id": "opt_todo", "name": "Todo"},
                        {"id": "opt_progress", "name": "In progress"},
                        {"id": "opt_review", "name": "In review"},
                        {"id": "opt_done", "name": "Done"},
                    ],
                },
            }
        },
        "repository": {
            "issue": {
                "id": "I_38",
                "projectItems": {
                    "nodes": [{"id": item, "project": {"number": n}} for item, n in items]
                },
            }
        },
    }


def sent_query(call):
    args, _ = call
    return args[args.index("-f") + 1]


def sent_variables(call):
    args, _ = call
    return [args[i + 1] for i, arg in enumerate(args) if arg == "-F"]


@pytest.mark.parametrize(
    ("status", "option"),
    [("In progress", "opt_progress"), ("In review", "opt_review"), ("Done", "opt_done")],
)
def test_set_status_edits_the_issues_status_field_on_the_board(status, option):
    gh = FakeGh(status_lookup([("PVTI_other", 5), ("PVTI_38", 2)]), {})

    adapter(gh).set_status(38, status)

    assert "updateProjectV2ItemFieldValue" in sent_query(gh.calls[1])
    assert sent_variables(gh.calls[1]) == [
        "project=PVT_board",
        "item=PVTI_38",
        "field=PVTSSF_status",
        f"option={option}",
    ]


def test_set_status_adds_an_issue_missing_from_the_board_before_editing_it():
    gh = FakeGh(
        status_lookup([("PVTI_other", 5)]),
        {"addProjectV2ItemById": {"item": {"id": "PVTI_new"}}},
        {},
    )

    adapter(gh).set_status(38, "In progress")

    assert "addProjectV2ItemById" in sent_query(gh.calls[1])
    assert sent_variables(gh.calls[1]) == ["project=PVT_board", "content=I_38"]
    assert "item=PVTI_new" in sent_variables(gh.calls[2])


def commit(**checks):
    """A PR commit and its check runs; no checks means GitHub ran none (a null rollup)."""
    if not checks:
        return {"commit": {"statusCheckRollup": None}}
    runs = [{"name": name.replace("_", "-"), "conclusion": c} for name, c in checks.items()]
    return {"commit": {"statusCheckRollup": {"contexts": {"nodes": [*runs, {}]}}}}


def pr_reply(merged=False, comments=(), commits=(), merge_state="CLEAN"):
    return {
        "repository": {
            "pullRequest": {
                "merged": merged,
                "mergeStateStatus": merge_state,
                "comments": {"nodes": [{"body": body} for body in comments]},
                "commits": {"nodes": list(commits)},
            }
        }
    }


def test_pull_request_counts_review_change_requests_and_the_trailing_ci_failures():
    gh = FakeGh(
        pr_reply(
            commits=[
                commit(ci="FAILURE", claude_review="FAILURE"),
                commit(ci="SUCCESS", claude_review="FAILURE"),
                commit(ci="FAILURE", claude_review="SUCCESS"),
                commit(),
                commit(ci="FAILURE", claude_review="FAILURE"),
            ],
            comments=["## Standards\nNo hard findings."],
        )
    )

    pr = adapter(gh).pull_request(41)

    assert pr == PullRequest(41, changes_requested=3, ci_failure_streak=2, has_retro_notes=False)
    assert "number=41" in sent_variables(gh.calls[0])


def test_pull_request_reports_merged_and_a_retro_notes_comment():
    gh = FakeGh(
        pr_reply(
            merged=True, comments=["Done.\n\n## Retro notes\n- Went fine."], commits=[commit()]
        )
    )

    assert adapter(gh).pull_request(44) == PullRequest(44, merged=True, has_retro_notes=True)


@pytest.mark.parametrize(
    ("state", "behind"), [("BEHIND", True), ("CLEAN", False), ("BLOCKED", False)]
)
def test_pull_request_says_whether_it_is_behind_main(state, behind):
    gh = FakeGh(pr_reply(merge_state=state, commits=[commit()]))

    assert adapter(gh).pull_request(54).behind is behind
    assert "mergeStateStatus" in sent_query(gh.calls[0])


def test_update_branch_brings_the_pr_up_to_date_with_a_merge_through_gh():
    gh = FakeGh("{}")

    adapter(gh).update_branch(54)

    assert gh.calls == [
        (["api", "-X", "PUT", "repos/onato/shared-core-bench/pulls/54/update-branch"], None)
    ]


def test_comment_posts_the_body_on_the_issue_under_the_agent_marker():
    gh = FakeGh("https://github.com/onato/shared-core-bench/issues/7#issuecomment-1\n")

    url = adapter(gh).comment(7, "Moved to In review by the Ralph loop: no PR opened.")

    assert url == "https://github.com/onato/shared-core-bench/issues/7#issuecomment-1"
    assert gh.calls == [
        (
            ["issue", "comment", "7", "-R", "onato/shared-core-bench", "--body-file", "-"],
            "> 🤖 Agent comment — written by Claude Code\n\n"
            "Moved to In review by the Ralph loop: no PR opened.",
        )
    ]


class FakeSubprocess:
    def __init__(self, returncode=0, stdout="", stderr=""):
        self.result = subprocess.CompletedProcess([], returncode, stdout, stderr)
        self.calls = []

    def __call__(self, args, **kwargs):
        self.calls.append((args, kwargs))
        return self.result


def test_gh_runner_sends_stdin_to_gh_and_returns_its_output():
    process = FakeSubprocess(stdout="ok\n")

    output = gh_runner(process)(["issue", "comment", "7"], "body")

    assert output == "ok\n"
    args, kwargs = process.calls[0]
    assert args == ["gh", "issue", "comment", "7"]
    assert kwargs["input"] == "body"


def test_gh_runner_raises_the_command_and_its_error_when_gh_fails():
    process = FakeSubprocess(returncode=1, stderr="HTTP 401: Bad credentials\n")

    with pytest.raises(GitHubError) as error:
        gh_runner(process)(["api", "graphql"], None)

    assert str(error.value) == "gh api graphql: HTTP 401: Bad credentials"


def test_gh_runner_errors_shorten_a_multi_line_query_to_its_name():
    process = FakeSubprocess(returncode=1, stderr="HTTP 502\n")

    with pytest.raises(GitHubError) as error:
        gh_runner(process)(["api", "graphql", "-f", "query=\nquery {\n}\n", "-F", "n=1"], None)

    assert str(error.value) == "gh api graphql -f query=… -F n=1: HTTP 502"


def test_set_status_to_a_status_the_board_lacks_fails_before_touching_the_board():
    gh = FakeGh(status_lookup([]))

    with pytest.raises(GitHubError) as error:
        adapter(gh).set_status(38, "Blocked")

    assert str(error.value) == "project 2 has no Status option 'Blocked'"
    assert len(gh.calls) == 1


def test_tests_cannot_reach_github():
    with pytest.raises(RuntimeError, match="tests must not touch the network"):
        gh_runner(subprocess.run)(["--version"], None)
    with pytest.raises(RuntimeError, match="tests must not touch the network"):
        socket.create_connection(("api.github.com", 443))
