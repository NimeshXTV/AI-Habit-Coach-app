"""
Strands Agent wiring for the adaptive intervention engine.

Ported unchanged from legacy-reference/backend/agent.py. Strategy SELECTION
(choose_strategy()) lives in the Spring Boot backend now, not here — this
module's only job is turning {key + facts} into the spoken/written coaching
line, via a real `strands.Agent` call rather than an f-string.

Model provider is chosen by the STRANDS_MODEL_PROVIDER env var:
  - "local"   (default) -> CoachModel, fully offline, phrase-bank based
  - "bedrock" -> BedrockModel, needs AWS credentials + Bedrock model access
  - "ollama"  -> Ollama model via a local `ollama serve`, needs `pip install
                 strands-agents[ollama]`
"""
import os

from strands import Agent
from strands.handlers import null_callback_handler

from coach_model import CoachModel

SYSTEM_PROMPT = (
    "You are an adaptive habit coach. You are given a STRATEGY the coaching "
    "system has already selected from the user's own history, plus the facts "
    "for today. Write ONE short, warm, direct coaching line (1-2 sentences) "
    "that fits the strategy. Never invent facts not given to you.\n\n"
    "When USER_NAME/USER_AGE/AGE_GROUP/USER_GENDER facts are present (they "
    "won't always be — the user may not have completed onboarding yet), let "
    "them meaningfully shape the coaching line:\n"
    "- AGE_GROUP=child: simple, playful, encouraging words; small, "
    "concrete, easily achievable steps.\n"
    "- AGE_GROUP=teen: casual and encouraging; achievable, low-pressure "
    "steps.\n"
    "- AGE_GROUP=adult: practical, direct encouragement.\n"
    "- AGE_GROUP=older_adult: respectful, unpatronizing tone; where it fits "
    "naturally, note a genuine practical benefit of the habit for that age "
    "(e.g. balance, heart health, energy).\n"
    "- USER_NAME: fine to address them by it when it reads naturally.\n"
    "- USER_GENDER: only mention or lean on it when genuinely relevant to "
    "the specific habit or phrasing. Never assume interests, tone, or "
    "personality from gender, and never rely on gender stereotypes."
)

_agent = None


def _build_model():
    provider = os.environ.get("STRANDS_MODEL_PROVIDER", "local").lower()

    if provider == "bedrock":
        from strands.models import BedrockModel
        return BedrockModel(model_id=os.environ.get("BEDROCK_MODEL_ID", "anthropic.claude-3-haiku-20240307-v1:0"))

    if provider == "ollama":
        from strands.models.ollama import OllamaModel
        return OllamaModel(
            host=os.environ.get("OLLAMA_HOST", "http://localhost:11434"),
            model_id=os.environ.get("OLLAMA_MODEL", "llama3.2"),
        )

    return CoachModel()


def _get_agent() -> Agent:
    global _agent
    if _agent is None:
        _agent = Agent(model=_build_model(), system_prompt=SYSTEM_PROMPT, callback_handler=null_callback_handler)
    return _agent


def build_fact_prompt(key: str, facts: dict) -> str:
    lines = [f"STRATEGY: {key}"] + [f"{k.upper()}: {v}" for k, v in facts.items()]
    return "\n".join(lines)


def generate_with_strands(key: str, facts: dict) -> str:
    """
    Calls a real strands.Agent to produce the coaching line for the given
    key (an intervention strategy, a post-action response kind, or
    "summary" — see coach_model.py's TEMPLATES). Raises on failure so the
    HTTP layer (main.py) can return an error the Spring Boot caller falls
    back from, instead of the demo silently breaking.
    """
    agent = _get_agent()
    prompt = build_fact_prompt(key, facts)
    result = agent(prompt)
    return str(result).strip()
