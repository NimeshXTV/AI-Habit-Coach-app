"""
Tests for agent.py's Advisor-only response sanitizer, added after a real
physical-device test showed the Habit Advisor producing a very long,
markdown-heavy response (headers, a table, numbered lists) despite
ADVISOR_SYSTEM_PROMPT's explicit "1-3 short paragraphs, 40-80 words, never
exceed 120, no tables or long lists" instructions.

Live experimentation against the real Bedrock Mantle model (during this
investigation, not part of the automated suite) showed prompt wording alone
— even a much more forceful, explicit "no markdown of any kind" version —
was NOT reliably obeyed, and that lowering max_output_tokens to force
brevity instead caused the response to be cut off mid-table/mid-sentence,
which reads worse than the original problem. _sanitize_advisor_reply() is
the resulting code-level backstop: strip markdown structure, then enforce
the 120-word cap by trimming back to the last complete sentence.

Network-free: these tests call the sanitizer functions directly on
synthetic text, never a real model.
"""
import agent


def test_strips_markdown_table_including_separator_row():
    text = (
        "Intro line.\n\n"
        "| Metric | Value |\n"
        "|--------|-------|\n"
        "| Day | 3 |\n"
        "| Streak | 1 |\n\n"
        "Closing line."
    )

    cleaned = agent._strip_markdown_structure(text)

    assert "|" not in cleaned
    assert "Intro line." in cleaned
    assert "Closing line." in cleaned


def test_strips_headers_bold_italic_and_horizontal_rules():
    text = (
        "## Quick Check-In\n\n"
        "You're **doing great** and *making progress*.\n\n"
        "---\n\n"
        "### Next Steps\n"
        "Keep it up."
    )

    cleaned = agent._strip_markdown_structure(text)

    assert "#" not in cleaned
    assert "*" not in cleaned
    assert "---" not in cleaned
    assert "doing great" in cleaned
    assert "making progress" in cleaned


def test_strips_bullet_and_numbered_lists_into_plain_text():
    text = (
        "Here are some ideas.\n\n"
        "- First idea\n"
        "- Second idea\n\n"
        "1. First step\n"
        "2. Second step\n"
    )

    cleaned = agent._strip_markdown_structure(text)

    assert "- " not in cleaned
    assert "1. " not in cleaned
    assert "First idea" in cleaned
    assert "First step" in cleaned


def test_cap_word_count_leaves_short_text_untouched():
    text = "Short reply under the limit."
    assert agent._cap_word_count(text, 120) == text


def test_cap_word_count_trims_to_the_last_complete_sentence_not_mid_sentence():
    sentences = ["This is sentence number {}.".format(i) for i in range(1, 40)]
    long_text = " ".join(sentences)  # well over 120 words

    result = agent._cap_word_count(long_text, 20)

    assert len(result.split()) <= 20
    # Never ends mid-sentence: the last character is sentence-ending punctuation.
    assert result.rstrip()[-1] in ".!?"
    assert "This is sentence number 1." in result


def test_sanitize_advisor_reply_removes_markdown_and_enforces_word_cap():
    verbose = (
        "**Your Check-In**\n\n"
        "| Metric | Value |\n"
        "|--------|-------|\n"
        "| Day | 3 |\n\n"
        "## Quick Wins\n\n" + " ".join(f"Sentence number {i} here." for i in range(1, 60))
    )

    result = agent._sanitize_advisor_reply(verbose)

    assert "|" not in result
    assert "#" not in result
    assert "**" not in result
    assert len(result.split()) <= agent.ADVISOR_MAX_WORDS


def test_sanitize_advisor_reply_is_a_no_op_for_already_clean_short_text():
    clean = "You're doing great on day 3. Keep the streak going tomorrow!"
    assert agent._sanitize_advisor_reply(clean) == clean


# ---- generate_with_strands() wiring: sanitizer applies ONLY to "advisor" ----

def test_generate_with_strands_sanitizes_only_the_advisor_key(monkeypatch):
    monkeypatch.delenv("STRANDS_MODEL_PROVIDER", raising=False)  # local CoachModel, network-free

    text = agent.generate_with_strands("advisor", {
        "habit_name": "gym",
        "day": 3,
        "user_message": "How am I doing?",
    })

    # CoachModel's plain phrase-bank text has no markdown to begin with;
    # this pins down that the sanitizer path runs (doesn't error) and never
    # produces markdown syntax for the advisor key.
    assert "|" not in text
    assert "##" not in text


def test_generate_with_strands_never_sanitizes_non_advisor_keys(monkeypatch):
    """
    Spies on _sanitize_advisor_reply to prove it is only ever invoked for
    key == "advisor" — interventions/action responses/summaries take the
    exact same code path as before this change (str(agent(prompt)).strip()
    returned directly, no post-processing).
    """
    monkeypatch.delenv("STRANDS_MODEL_PROVIDER", raising=False)  # local CoachModel, network-free
    calls = []
    monkeypatch.setattr(agent, "_sanitize_advisor_reply", lambda text: calls.append(text) or text)

    for key in ["encouragement", "reduce_task", "completion_first", "miss_single", "summary"]:
        agent.generate_with_strands(key, {"name": "gym", "day": 1, "total": 21, "completed": 0})
    assert calls == []

    agent.generate_with_strands("advisor", {"habit_name": "gym", "user_message": "hi"})
    assert len(calls) == 1