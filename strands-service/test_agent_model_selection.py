"""
Tests for agent.py's _build_model() provider selection — specifically the
"bedrock_mantle" branch (OpenAIResponsesModel routed through Amazon
Bedrock's Mantle endpoint), added to replace the earlier BedrockModel/
Bedrock Runtime Converse provider that this account's Bedrock access does
not permit.

Deliberately network-free: OpenAIResponsesModel.__init__ does no I/O (the
openai.AsyncOpenAI client is only constructed, and any HTTP call only made,
when the agent is actually invoked — see agent.py's generate_with_strands).
These tests only inspect the constructed model's config/client_args, so
they never call real Bedrock/OpenAI, matching every other test in this
suite.
"""
import logging

import pytest
from strands.models.openai_responses import OpenAIResponsesModel

import agent


@pytest.fixture
def bedrock_mantle_env(monkeypatch):
    monkeypatch.setenv("STRANDS_MODEL_PROVIDER", "bedrock_mantle")
    monkeypatch.setenv("BEDROCK_MANTLE_MODEL_ID", "openai.gpt-oss-120b")
    monkeypatch.setenv("BEDROCK_MANTLE_BASE_URL", "https://bedrock-mantle.us-east-1.api.aws/v1")
    monkeypatch.setenv("BEDROCK_API_KEY", "test-only-fake-bedrock-api-key")


def test_bedrock_mantle_provider_selects_openai_responses_model(bedrock_mantle_env):
    model = agent._build_model("encouragement")
    assert isinstance(model, OpenAIResponsesModel)


def test_bedrock_mantle_provider_uses_configured_model_id(bedrock_mantle_env):
    model = agent._build_model("encouragement")
    assert model.get_config()["model_id"] == "openai.gpt-oss-120b"


def test_bedrock_mantle_provider_uses_configured_base_url(bedrock_mantle_env):
    model = agent._build_model("encouragement")
    assert model.client_args["base_url"] == "https://bedrock-mantle.us-east-1.api.aws/v1"


def test_bedrock_mantle_provider_reads_api_key_from_environment_not_hardcoded(monkeypatch):
    monkeypatch.setenv("STRANDS_MODEL_PROVIDER", "bedrock_mantle")
    monkeypatch.setenv("BEDROCK_MANTLE_MODEL_ID", "openai.gpt-oss-120b")
    monkeypatch.setenv("BEDROCK_MANTLE_BASE_URL", "https://bedrock-mantle.us-east-1.api.aws/v1")
    monkeypatch.setenv("BEDROCK_API_KEY", "first-fake-key")
    assert agent._build_model("encouragement").client_args["api_key"] == "first-fake-key"

    monkeypatch.setenv("BEDROCK_API_KEY", "second-fake-key")
    assert agent._build_model("encouragement").client_args["api_key"] == "second-fake-key"


def test_bedrock_mantle_provider_requires_api_key_env_var(monkeypatch):
    monkeypatch.setenv("STRANDS_MODEL_PROVIDER", "bedrock_mantle")
    monkeypatch.setenv("BEDROCK_MANTLE_MODEL_ID", "openai.gpt-oss-120b")
    monkeypatch.setenv("BEDROCK_MANTLE_BASE_URL", "https://bedrock-mantle.us-east-1.api.aws/v1")
    monkeypatch.delenv("BEDROCK_API_KEY", raising=False)

    with pytest.raises(KeyError):
        agent._build_model("encouragement")


def test_bedrock_mantle_construction_never_logs_the_api_key(bedrock_mantle_env, caplog):
    with caplog.at_level(logging.DEBUG):
        agent._build_model("encouragement")

    assert "test-only-fake-bedrock-api-key" not in caplog.text


def test_local_provider_is_still_the_default_with_no_env_var_set(monkeypatch):
    monkeypatch.delenv("STRANDS_MODEL_PROVIDER", raising=False)
    from coach_model import CoachModel

    assert isinstance(agent._build_model("encouragement"), CoachModel)


# ---- Advisor-specific max_output_tokens cap (added after a physical-device
# test showed a long, multi-section, tabular Advisor response despite
# ADVISOR_SYSTEM_PROMPT's concise-response instructions — a real, enforced
# backstop in addition to the prompt) ----

def test_advisor_key_gets_a_max_output_tokens_cap(bedrock_mantle_env):
    model = agent._build_model("advisor")
    assert model.get_config()["params"] == {"max_output_tokens": agent.ADVISOR_MAX_OUTPUT_TOKENS}


def test_non_advisor_keys_get_no_params_at_all_same_as_before_this_change(bedrock_mantle_env):
    for key in ["encouragement", "reduce_task", "completion_first", "miss_single", "summary"]:
        model = agent._build_model(key)
        assert "params" not in model.get_config()


def test_advisor_max_output_tokens_is_generous_not_artificially_tiny():
    # Sanity-checks the constant itself: must comfortably exceed a 120-word
    # reply (~160-200 tokens) plus real headroom for a reasoning model's
    # internal reasoning tokens, which the task explicitly warned against
    # under-provisioning.
    assert agent.ADVISOR_MAX_OUTPUT_TOKENS >= 1000
