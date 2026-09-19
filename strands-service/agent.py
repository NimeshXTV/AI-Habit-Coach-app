"""
Strands Agent wiring for the adaptive intervention engine.

Ported unchanged from legacy-reference/backend/agent.py. Strategy SELECTION
(choose_strategy()) lives in the Spring Boot backend now, not here — this
module's only job is turning {key + facts} into the spoken/written coaching
line, via a real `strands.Agent` call rather than an f-string.

Model provider is chosen by the STRANDS_MODEL_PROVIDER env var:
  - "local"          (default) -> CoachModel, fully offline, phrase-bank based
  - "bedrock_mantle"  -> OpenAIResponsesModel routed through Amazon Bedrock's
                          Mantle (OpenAI-compatible) endpoint, needs
                          BEDROCK_MANTLE_MODEL_ID / BEDROCK_MANTLE_BASE_URL /
                          BEDROCK_API_KEY. Verified working for this account;
                          replaces an earlier BedrockModel (Bedrock Runtime
                          Converse) provider that this account's Bedrock
                          access does not permit.
  - "ollama"          -> Ollama model via a local `ollama serve`, needs `pip
                          install strands-agents[ollama]`
"""
import os
import re

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

# Used only for key == "advisor" (the Habit Advisor chat surface — see
# backend's AdvisorService/StrandsAdvisorProvider). A real open-ended,
# multi-turn conversation about one habit, not a single short coaching
# nudge, so it gets its own, separate system prompt rather than reusing
# SYSTEM_PROMPT's "ONE short line" instruction verbatim.
ADVISOR_SYSTEM_PROMPT = (
    "You are the Habit Advisor: a conversational chat assistant the user can "
    "ask questions to about one specific habit challenge they're working on. "
    "You are given that habit's context (day, streak, recent history, etc.), "
    "the user's message, and the recent conversation so far. Answer "
    "naturally, like a knowledgeable, supportive coach having a real "
    "conversation.\n\n"
    "DAY, TOTAL_DAYS, CURRENT_STREAK, and CONSECUTIVE_MISSED are "
    "authoritative, already-computed ground truth supplied by the "
    "application — not estimates. Always quote these exact values when "
    "referring to them. Never recalculate, estimate, infer, or override "
    "them from the conversation history or any other text, even if the "
    "conversation seems to imply a different day or streak.\n"
    "DAY and CURRENT_STREAK are DIFFERENT numbers that must never be "
    "confused: DAY is which day of the challenge this is; CURRENT_STREAK "
    "is how many days in a row were just completed, which can be smaller "
    "than DAY (e.g. DAY=3 with CURRENT_STREAK=1 means today is day 3 but "
    "only the most recent day counts toward the streak). Never describe "
    "DAY as if it were the streak length.\n\n"
    "Keep responses conversational and concise: usually 1-3 short "
    "paragraphs, roughly 40-80 words. Never exceed 120 words unless the "
    "user explicitly asks for more detail. No tables or long lists. Avoid "
    "unnecessary repetition. Give at most 1-2 practical suggestions at a "
    "time. Never invent facts that weren't given to you."
)

# Generous enough that a reasoning model (openai.gpt-oss-120b) has ample
# room to finish its internal reasoning AND produce a full 40-120 word
# answer (well under 200 tokens of actual reply text) without truncating
# mid-response, while still bounding the kind of runaway, multi-section,
# tabular response ADVISOR_SYSTEM_PROMPT alone failed to prevent (see the
# investigation this was ported from — a prompt-only word-count instruction
# was not reliably obeyed by this reasoning model, so this is a real,
# enforced backstop in addition to the prompt, not a replacement for it).
# Applies ONLY to key == "advisor" — see _build_model()'s bedrock_mantle
# branch — interventions/action responses/summaries are unaffected.
ADVISOR_MAX_OUTPUT_TOKENS = 2048

# Real, code-enforced hard cap matching ADVISOR_SYSTEM_PROMPT's own stated
# "never exceed 120 words" — see _sanitize_advisor_reply()'s docstring for
# why the prompt instruction alone isn't trusted to hold it.
ADVISOR_MAX_WORDS = 120

_MD_TABLE_ROW_RE = re.compile(r"^\s*\|.*\|\s*$")
_MD_TABLE_SEP_RE = re.compile(r"^\s*\|?[\s:|-]+\|[\s:|-]*$")
_MD_HEADER_RE = re.compile(r"^\s{0,3}#{1,6}\s*")
_MD_HR_RE = re.compile(r"^\s*([-*_])\1{2,}\s*$")
_MD_LIST_RE = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s+")
_MD_BOLD_ITALIC_RE = re.compile(r"(\*\*\*|\*\*|\*|__)(.+?)\1")
_SENTENCE_END_RE = re.compile(r"(?<=[.!?])\s+")


def _strip_markdown_structure(text: str) -> str:
    """
    Removes markdown structure (tables, headers, bold/italic markers,
    bullet/numbered lists, horizontal rules) from a model response,
    collapsing what's left into plain paragraphs (blank-line-separated
    runs of text, each joined onto one line).

    Empirically, an explicit "no markdown of any kind: no headers, no
    tables, no pipe characters, no bullet/numbered lists" instruction in
    the system prompt (tried during this investigation) was NOT reliably
    obeyed by this reasoning model (openai.gpt-oss-120b via Bedrock
    Mantle) — it kept producing markdown tables and section headers
    regardless of wording strength or reasoning-effort setting. This is
    therefore enforced defensively in code, for key == "advisor" only.
    """
    paragraphs: list[str] = []
    current: list[str] = []
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or _MD_TABLE_ROW_RE.match(line) or _MD_TABLE_SEP_RE.match(line) or _MD_HR_RE.match(line):
            if current:
                paragraphs.append(" ".join(current))
                current = []
            continue
        line = _MD_HEADER_RE.sub("", line)
        line = _MD_LIST_RE.sub("", line)
        line = _MD_BOLD_ITALIC_RE.sub(r"\2", line)
        line = line.strip()
        if line:
            current.append(line)
    if current:
        paragraphs.append(" ".join(current))
    return "\n\n".join(paragraphs)


def _cap_word_count(text: str, max_words: int) -> str:
    """Trims text to at most max_words, cut back to the last complete
    sentence so the result always reads as finished prose — never a
    mid-sentence fragment — unlike truncating via max_output_tokens at the
    raw API level (tried during this investigation: it cut responses off
    mid-table/mid-sentence, which read worse than the original overly-long
    response)."""
    words = text.split()
    if len(words) <= max_words:
        return text
    truncated = " ".join(words[:max_words])
    sentences = _SENTENCE_END_RE.split(truncated)
    if len(sentences) > 1:
        return " ".join(sentences[:-1]).strip()
    return truncated.strip()


def _sanitize_advisor_reply(text: str) -> str:
    """Applied ONLY to key == "advisor" replies (see generate_with_strands)
    — strips markdown structure the model keeps producing despite
    ADVISOR_SYSTEM_PROMPT's instructions not to, then enforces the
    prompt's own stated 120-word hard cap for real. Never applied to
    interventions/action responses/summaries, whose output is unchanged."""
    return _cap_word_count(_strip_markdown_structure(text), ADVISOR_MAX_WORDS)


def _build_model(key: str):
    provider = os.environ.get("STRANDS_MODEL_PROVIDER", "local").lower()

    if provider == "bedrock_mantle":
        # OpenAI-compatible Responses API client pointed at Amazon Bedrock's
        # Mantle endpoint — NOT BedrockModel/Bedrock Runtime Converse, which
        # this account's Bedrock access does not permit. client_args map
        # straight onto openai.AsyncOpenAI(base_url=..., api_key=...); the
        # api_key is a Bedrock API key (bearer token), read from the
        # environment only, never hardcoded or logged.
        from strands.models.openai_responses import OpenAIResponsesModel
        model_kwargs = {
            "client_args": {
                "base_url": os.environ["BEDROCK_MANTLE_BASE_URL"],
                "api_key": os.environ["BEDROCK_API_KEY"],
            },
            "model_id": os.environ["BEDROCK_MANTLE_MODEL_ID"],
        }
        if key == "advisor":
            # See ADVISOR_MAX_OUTPUT_TOKENS's own comment — only the
            # open-ended Habit Advisor chat gets a token cap; interventions/
            # action responses/summaries are untouched (no "params" key at
            # all for them, exactly as before this change).
            model_kwargs["params"] = {"max_output_tokens": ADVISOR_MAX_OUTPUT_TOKENS}
        return OpenAIResponsesModel(**model_kwargs)

    if provider == "ollama":
        from strands.models.ollama import OllamaModel
        return OllamaModel(
            host=os.environ.get("OLLAMA_HOST", "http://localhost:11434"),
            model_id=os.environ.get("OLLAMA_MODEL", "llama3.2"),
        )

    return CoachModel()


def _build_agent(key: str) -> Agent:
    """
    Builds a fresh Agent for this call — never cached/reused across calls.

    Uses ADVISOR_SYSTEM_PROMPT for key == "advisor" (the Habit Advisor chat)
    and SYSTEM_PROMPT for every other key (interventions, action responses,
    "summary") — unchanged behavior for all of those.

    A strands.Agent is inherently a stateful, multi-turn conversational
    object: every agent(prompt) call appends to its own internal
    self.messages and replays the FULL accumulated history into the next
    request (see strands/agent/agent.py's _append_messages). This module's
    own docstring, and main.py's, both promise every /generate call is
    fully self-contained and stateless ("remembers nothing between calls")
    — this codebase already builds its own bounded, per-conversation
    history representation (Spring Boot's StrandsAdvisorProvider embeds it
    as a single "CONVERSATION_SO_FAR" fact in the one prompt string below),
    so the SDK's own separate history/statefulness is neither needed nor
    wanted here.

    A previous version of this function cached one Agent in a module-level
    global and reused it for every /generate call for the life of the
    process — across every habit, device, and request kind (interventions,
    action responses, summaries, AND advisor chats), not just turns of the
    same conversation. That silently violated the promised statelessness:
    with a reasoning-capable model (STRANDS_MODEL_PROVIDER=bedrock_mantle's
    OpenAIResponsesModel + openai.gpt-oss-120b), each assistant turn stored
    in that shared, ever-growing self.messages carries a reasoningContent
    block, and OpenAIResponsesModel replays the ENTIRE accumulated history
    (not just the new prompt) on every subsequent call — see its
    _format_request_messages(), which detects and drops reasoningContent
    from replayed history (logging "reasoningContent is not yet supported
    in multi-turn conversations with the Responses API") precisely because
    it was present in that shared state. Building a fresh, empty-history
    Agent per call means there is never any prior state — reasoning or
    otherwise, from this conversation or any other — for that replay path
    to ever encounter.
    """
    system_prompt = ADVISOR_SYSTEM_PROMPT if key == "advisor" else SYSTEM_PROMPT
    return Agent(model=_build_model(key), system_prompt=system_prompt, callback_handler=null_callback_handler)


def build_fact_prompt(key: str, facts: dict) -> str:
    """
    Flattens {key, facts} into the one prompt string sent to the agent, one
    fact per "KEY: value" line — except a value that itself contains a
    newline (today, only "conversation_so_far" ever does — see
    StrandsAdvisorProvider.formatHistory on the Java side), which would
    otherwise break the implicit one-fact-per-line convention: once
    flattened inline, a multi-turn CONVERSATION_SO_FAR's own lines are
    visually indistinguishable from separate top-level facts, and a model
    parsing the prompt could misattribute conversation content as
    standalone facts (or vice versa). Those values get a clearly bounded
    block instead:

        CONVERSATION_SO_FAR:
        \"\"\"
        user: ...
        advisor: ...
        \"\"\"

    so its extent is unambiguous regardless of how many lines it spans.
    Single-line facts (every fact for every non-advisor key today) are
    completely unaffected — same "KEY: value" line as before.
    """
    lines = [f"STRATEGY: {key}"]
    for k, v in facts.items():
        value = str(v)
        if "\n" in value:
            lines.append(f'{k.upper()}:\n"""\n{value}\n"""')
        else:
            lines.append(f"{k.upper()}: {value}")
    return "\n".join(lines)


def generate_with_strands(key: str, facts: dict) -> str:
    """
    Calls a real strands.Agent to produce the coaching line for the given
    key (an intervention strategy, a post-action response kind, or
    "summary" — see coach_model.py's TEMPLATES). Raises on failure so the
    HTTP layer (main.py) can return an error the Spring Boot caller falls
    back from, instead of the demo silently breaking.
    """
    agent = _build_agent(key)
    prompt = build_fact_prompt(key, facts)
    text = str(agent(prompt)).strip()
    if key == "advisor":
        text = _sanitize_advisor_reply(text)
    return text
