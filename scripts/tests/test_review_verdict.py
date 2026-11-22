import json

from review_verdict import main


def write_run(tmp_path, result):
    execution = tmp_path / "execution.json"
    execution.write_text(json.dumps([{"type": "system"}, {"type": "result", **result}]))
    return execution


def write_verdict(tmp_path, text):
    verdict = tmp_path / "verdict.md"
    verdict.write_text(text)
    return verdict


def test_approve_exits_zero(tmp_path):
    verdict = write_verdict(tmp_path, "APPROVE\n\nLooks good.\n")
    run = write_run(tmp_path, {"permission_denials": []})

    assert main([str(verdict), str(run)]) == 0


def test_request_changes_exits_one_and_prints_the_summary(tmp_path, capsys):
    verdict = write_verdict(tmp_path, "REQUEST_CHANGES\n\nStandards: test asserts on a boolean.\n")
    run = write_run(tmp_path, {"permission_denials": []})

    code = main([str(verdict), str(run)])

    assert code == 1
    assert "Standards: test asserts on a boolean." in capsys.readouterr().out


def test_a_missing_verdict_file_fails_closed(tmp_path, capsys):
    run = write_run(tmp_path, {"permission_denials": []})

    code = main([str(tmp_path / "absent.md"), str(run)])

    assert code == 1
    assert "no verdict" in capsys.readouterr().out


def test_an_empty_or_garbled_verdict_fails_closed(tmp_path, capsys):
    verdict = tmp_path / "verdict.md"
    run = write_run(tmp_path, {"permission_denials": []})
    for text in ["", "\n", "APPROVED\n", "LGTM, APPROVE\nShip it.\n"]:
        verdict.write_text(text)

        code = main([str(verdict), str(run)])

        assert code == 1, text
        assert f"no verdict: expected APPROVE or REQUEST_CHANGES in {verdict}" in (
            capsys.readouterr().out
        )


def test_an_approval_after_denied_tool_calls_fails_and_names_each_call(tmp_path, capsys):
    verdict = write_verdict(tmp_path, "APPROVE\n\nRead the full diff.\n")
    run = write_run(
        tmp_path,
        {
            "permission_denials": [
                {"tool_name": "Bash", "tool_input": {"command": "gh pr comment 26 --body-file x"}},
                {"tool_name": "Write", "tool_input": {"file_path": "/tmp/body.md"}},
            ]
        },
    )

    code = main([str(verdict), str(run)])

    out = capsys.readouterr().out
    assert code == 1
    assert "2 tool calls were denied" in out
    assert "Bash: gh pr comment 26 --body-file x" in out
    assert "Write: /tmp/body.md" in out


def test_a_denial_count_without_the_list_still_fails(tmp_path, capsys):
    verdict = write_verdict(tmp_path, "APPROVE\n\nFine.\n")
    run = write_run(tmp_path, {"permission_denials_count": 2})

    code = main([str(verdict), str(run)])

    assert code == 1
    assert "2 tool calls were denied" in capsys.readouterr().out


def test_a_missing_or_unreadable_execution_file_fails_closed(tmp_path, capsys):
    verdict = write_verdict(tmp_path, "APPROVE\n\nFine.\n")
    garbled = tmp_path / "garbled.json"
    garbled.write_text("not json")
    no_result = tmp_path / "no_result.json"
    no_result.write_text(json.dumps([{"type": "system"}]))
    for run in [tmp_path / "absent.json", garbled, no_result]:
        code = main([str(verdict), str(run)])

        assert code == 1, run
        assert f"no run result in {run}" in capsys.readouterr().out
