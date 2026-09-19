"""
Regression tests for the "reasoningContent is not yet supported in
multi-turn conversations with the Responses API" bug seen against a real
physical-device Habit Advisor conversation.

Root cause (see agent.py's _build_agent() docstring for the full trace):
agent.py used to cache ONE strands.Agent in a module-level global and reuse
it for every /generate call for the life of the process. strands.Agent is
inherently stateful — each agent(prompt) call appends to the agent's own
self.messages and replays the FULL accumulated history (not just the new
prompt) into the next model call. With STRANDS_MODEL_PROVIDER=bedrock_mantle
(OpenAIResponsesModel + openai.gpt-oss-120b, a reasoning model), that
replayed history could contain a prior assistant turn's reasoningContent
block, which the Responses API rejects manually replaying — and because the
shared Agent was never scoped to one conversation, this could happen even
between UNRELATED habits/devices/request kinds, not just advisor turns of
the same chat.

These tests are network-free (no real Bedrock/Strands API calls) and use
the default "local" CoachModel provider, so they run without credentials or
network access — same guarantee as test_main.py.
"""
import agent


def test_build_agent_returns_a_new_instance_on_every_call():
    first = agent._build_agent("advisor")
    second = agent._build_agent("advisor")

    assert first is not second


def test_build_agent_never_carries_over_messages_from_a_previous_call():
    """
    Directly falsifies the root-cause mechanism: simulates what a real
    GPT-OSS turn would have left behind in a shared Agent's self.messages
    (an assistant message with a reasoningContent block, exactly the shape
    OpenAIResponsesModel's _format_request_messages() warns about and
    strips) and proves a freshly built Agent for the NEXT call never sees
    it — there is no shared state for reasoning content (or anything else)
    to leak through.
    """
    first = agent._build_agent("advisor")
    first.messages.append({
        "role": "assistant",
        "content": [
            {"reasoningContent": {"reasoningText": {"text": "internal chain-of-thought from turn 1"}}},
            {"text": "turn 1's actual reply"},
        ],
    })

    second = agent._build_agent("advisor")

    assert second.messages == []


def test_build_agent_uses_the_advisor_system_prompt_for_the_advisor_key():
    built = agent._build_agent("advisor")

    assert built.system_prompt == agent.ADVISOR_SYSTEM_PROMPT
    assert built.system_prompt != agent.SYSTEM_PROMPT


def test_build_agent_uses_the_regular_system_prompt_for_every_other_key():
    for key in ["encouragement", "reduce_task", "completion_first", "miss_single", "summary"]:
        built = agent._build_agent(key)
        assert built.system_prompt == agent.SYSTEM_PROMPT


def test_advisor_system_prompt_encodes_the_length_and_style_constraints():
    prompt = agent.ADVISOR_SYSTEM_PROMPT

    assert "40-80 words" in prompt
    assert "120 words" in prompt
    assert "No tables or long lists" in prompt
    assert "1-2 practical suggestions" in prompt
    assert "Never invent facts" in prompt


def test_advisor_system_prompt_treats_day_and_streak_facts_as_authoritative():
    """
    Added after a physical-device test showed the Advisor reporting a wrong
    day number and streak (e.g. "Day 3" / "streak 2" when the app's own,
    verified-correct facts were day 2 / streak 1) — the model was free to
    recalculate/reinterpret these from context instead of quoting the given
    values. Pins down that the prompt now explicitly forbids that for each
    of the four numeric facts named in the investigation.
    """
    prompt = agent.ADVISOR_SYSTEM_PROMPT

    for fact_name in ["DAY", "TOTAL_DAYS", "CURRENT_STREAK", "CONSECUTIVE_MISSED"]:
        assert fact_name in prompt
    assert "authoritative" in prompt
    assert "Never recalculate, estimate, infer, or override" in prompt


def test_advisor_system_prompt_distinguishes_day_number_from_streak_length():
    """
    Added after a live test (during this investigation, against the real
    Bedrock Mantle model) showed the model conflating DAY with
    CURRENT_STREAK — e.g. describing "day 3" as "a 3-day streak" even
    though CURRENT_STREAK was 1 — despite the general authoritative-facts
    instruction above. An explicit example distinguishing the two numbers
    fixed it in repeated live testing; this pins down that wording stays
    in the prompt.
    """
    prompt = agent.ADVISOR_SYSTEM_PROMPT

    assert "DIFFERENT numbers" in prompt
    assert "Never describe DAY as if it were the streak length" in prompt


def test_system_prompt_for_non_advisor_keys_is_unchanged():
    assert agent.SYSTEM_PROMPT.startswith("You are an adaptive habit coach.")
    assert "ONE short, warm, direct coaching line" in agent.SYSTEM_PROMPT


def test_build_fact_prompt_includes_habit_context_and_flattened_conversation_history():
    """
    The application's OWN conversation-history mechanism (Spring Boot's
    StrandsAdvisorProvider embeds bounded history as a single
    CONVERSATION_SO_FAR fact) flows through build_fact_prompt() unchanged
    by this fix — multi-turn context is still fully preserved, just no
    longer duplicated by the SDK's own separate statefulness, and (as of
    the delimiter fix below) now clearly bounded rather than flattened
    inline.
    """
    facts = {
        "habit_name": "Gym",
        "day": 5,
        "total_days": 21,
        "conversation_so_far": "user: I missed today\nadvisor: That's okay, let's plan for tomorrow.",
        "user_message": "What about today?",
    }

    prompt = agent.build_fact_prompt("advisor", facts)

    assert "STRATEGY: advisor" in prompt
    assert "HABIT_NAME: Gym" in prompt
    assert "DAY: 5" in prompt
    assert "TOTAL_DAYS: 21" in prompt
    assert 'CONVERSATION_SO_FAR:\n"""\nuser: I missed today\nadvisor: '\
           'That\'s okay, let\'s plan for tomorrow.\n"""' in prompt
    assert "USER_MESSAGE: What about today?" in prompt


# ---- conversation-history delimiting (added after the investigation found
# a flattened multi-line CONVERSATION_SO_FAR value was visually
# indistinguishable from separate top-level facts once inlined) ----

def test_multiline_fact_value_is_wrapped_in_a_clearly_bounded_block():
    prompt = agent.build_fact_prompt("advisor", {
        "conversation_so_far": "user: line one\nadvisor: line two",
        "user_message": "line three?",
    })

    assert '"""' in prompt
    # The delimited block's closing """ must appear BEFORE the next fact's
    # own line, so a reader (human or model) can never mistake USER_MESSAGE
    # for part of the conversation transcript.
    block_start = prompt.index('CONVERSATION_SO_FAR:\n"""\n')
    close_index = prompt.index('\n"""', block_start + len('CONVERSATION_SO_FAR:\n"""'))
    user_message_index = prompt.index("USER_MESSAGE:")
    assert close_index < user_message_index


def test_single_line_facts_are_not_delimited_same_as_before_the_fix():
    prompt = agent.build_fact_prompt("encouragement", {
        "name": "gym",
        "day": 1,
        "total": 21,
    })

    assert '"""' not in prompt
    assert "NAME: gym" in prompt
    assert "DAY: 1" in prompt
    assert "TOTAL: 21" in prompt


def test_a_multiline_value_for_any_fact_name_gets_delimited_not_only_conversation_so_far():
    # The delimiting is generic (keyed off "does this value contain a
    # newline", not a hardcoded fact name) so it also protects any future
    # multi-line fact, not just today's one case.
    prompt = agent.build_fact_prompt("advisor", {"some_future_fact": "line one\nline two"})

    assert 'SOME_FUTURE_FACT:\n"""\nline one\nline two\n"""' in prompt


def test_generate_with_strands_first_turn_has_no_conversation_history_fact(monkeypatch):
    monkeypatch.delenv("STRANDS_MODEL_PROVIDER", raising=False)

    text = agent.generate_with_strands("advisor", {"habit_name": "gym", "user_message": "How am I doing?"})

    assert isinstance(text, str)
    assert text


def test_generate_with_strands_second_turn_with_prior_assistant_response_does_not_reuse_agent_state(monkeypatch):
    """
    Two consecutive "advisor" calls, the second carrying a
    conversation_so_far fact representing the first turn's exchange (the
    same shape StrandsAdvisorProvider builds) — proves the second call
    succeeds independently and isn't affected by whatever the SDK-level
    Agent object accumulated internally during the first call.
    """
    monkeypatch.delenv("STRANDS_MODEL_PROVIDER", raising=False)

    first_reply = agent.generate_with_strands("advisor", {
        "habit_name": "gym",
        "user_message": "How am I doing?",
    })

    second_reply = agent.generate_with_strands("advisor", {
        "habit_name": "gym",
        "conversation_so_far": f"user: How am I doing?\nadvisor: {first_reply}",
        "user_message": "What about tomorrow?",
    })

    assert isinstance(second_reply, str)
    assert second_reply
