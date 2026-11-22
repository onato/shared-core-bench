from review_verdict import main


def test_approve_exits_zero(tmp_path):
    verdict = tmp_path / "verdict.md"
    verdict.write_text("APPROVE\n\nLooks good.\n")

    assert main([str(verdict)]) == 0


def test_request_changes_exits_one_and_prints_the_summary(tmp_path, capsys):
    verdict = tmp_path / "verdict.md"
    verdict.write_text("REQUEST_CHANGES\n\nStandards: test asserts on a boolean.\n")

    code = main([str(verdict)])

    assert code == 1
    assert "Standards: test asserts on a boolean." in capsys.readouterr().out


def test_a_missing_verdict_file_fails_closed(tmp_path, capsys):
    code = main([str(tmp_path / "absent.md")])

    assert code == 1
    assert "no verdict" in capsys.readouterr().out


def test_an_empty_or_garbled_verdict_fails_closed(tmp_path, capsys):
    verdict = tmp_path / "verdict.md"
    for text in ["", "\n", "APPROVED\n", "LGTM, APPROVE\nShip it.\n"]:
        verdict.write_text(text)

        code = main([str(verdict)])

        assert code == 1, text
        assert f"no verdict: expected APPROVE or REQUEST_CHANGES in {verdict}" in (
            capsys.readouterr().out
        )
