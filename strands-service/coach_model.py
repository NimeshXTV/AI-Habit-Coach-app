"""
Local, offline Strands model provider for the habit coach.

Ported unchanged from legacy-reference/backend/coach_model.py. A Strands
`Model` is a documented extension point: anything that implements
`update_config` / `get_config` / `structured_output` / `stream` can sit behind
`strands.Agent`. This one composes coaching language from phrase banks
instead of calling a hosted LLM, which is what keeps the whole app runnable
with no AWS account and no API key for the hackathon demo — but every
request still goes through a real `Agent` (system prompt, message history,
event loop), not a hand-rolled string call. Point `STRANDS_MODEL_PROVIDER` at
`bedrock` or `ollama` (see `agent.py`) to swap in a hosted model with zero
changes to callers.
"""
import random
import re
import time
from typing import Any, AsyncGenerator

from strands.models.model import Model
from strands.types.content import Messages
from strands.types.streaming import StreamEvent
from strands.types.tools import ToolSpec


class CoachModel(Model):
    """Composes a coaching message from the facts in the prompt, fully offline."""

    def __init__(self, **config: Any):
        self._config = config

    def update_config(self, **model_config: Any) -> None:
        self._config.update(model_config)

    def get_config(self) -> Any:
        return self._config

    async def structured_output(
        self, output_model, prompt: Messages, system_prompt: str | None = None, **kwargs: Any
    ) -> AsyncGenerator[dict, None]:
        raise NotImplementedError("CoachModel only supports free-text generation")

    async def stream(
        self,
        messages: Messages,
        tool_specs: list[ToolSpec] | None = None,
        system_prompt: str | None = None,
        **kwargs: Any,
    ) -> AsyncGenerator[StreamEvent, None]:
        start = time.monotonic()
        user_text = _last_user_text(messages)
        text = _compose(user_text)

        yield {"messageStart": {"role": "assistant"}}
        yield {"contentBlockStart": {"contentBlockIndex": 0, "start": {}}}
        words = text.split(" ")
        for i, word in enumerate(words):
            chunk = word if i == 0 else " " + word
            yield {"contentBlockDelta": {"contentBlockIndex": 0, "delta": {"text": chunk}}}
        yield {"contentBlockStop": {"contentBlockIndex": 0}}
        yield {"messageStop": {"stopReason": "end_turn"}}
        elapsed_ms = max(1, int((time.monotonic() - start) * 1000))
        yield {
            "metadata": {
                "usage": {
                    "inputTokens": len(user_text.split()),
                    "outputTokens": len(words),
                    "totalTokens": len(user_text.split()) + len(words),
                },
                "metrics": {"latencyMs": elapsed_ms},
            }
        }


def _last_user_text(messages: Messages) -> str:
    for message in reversed(messages):
        if message.get("role") == "user":
            return "".join(
                block.get("text", "") for block in message.get("content", []) if "text" in block
            )
    return ""


# Each strategy gets a small bank of sentence templates over the same fact
# fields `agent.py` puts in the prompt (see FACT_KEYS). Picking one at random
# per call is what keeps two calls for the same day from reading identically.
TEMPLATES = {
    "encouragement": [
        "Day {day} of {total}. {progress_clause} Just focus on {name} today.",
        "Let's keep it simple — day {day}, and all that matters is starting {name}.",
        "You're on day {day} of {total}. {progress_clause} One more counts.",
    ],
    "reduce_task": [
        "No pressure about the full {minutes} minutes today — just do {shrink} minutes of {name}, that's it.",
        "You've been finding {name} hard to start lately. Forget the full session: {shrink} minutes only, today.",
        "Let's shrink the ask. {shrink} minutes of {name} — nothing more expected of you right now.",
        "Don't worry about the whole thing yet. Just show up for {name} — {shrink} minutes, or even less, still counts.",
        "Forget {name} entirely for a second. Just put your shoes on and step toward it. That's the only ask right now.",
    ],
    "reinforcement": [
        "You've completed {completed} of the last {window} days ({pct}%) on {name}. That's real momentum.",
        "Day {day} of {total}, {completed} done already — {name} is becoming a real habit, not a plan.",
        "Almost there — day {day} of {total}. {completed} days in the bank on {name}. Let's close it out.",
    ],
    "accountability": [
        "You've postponed {name} a couple of times now. One small, concrete step today beats skipping again.",
        "This is the moment it's easiest to let {name} slide again. Don't — just do the smallest honest version.",
    ],
    "reschedule": [
        "{time_of_day} hasn't been working for {name} lately — you've mentioned {reason} more than once. Want to try a different time?",
        "The timing, not the willpower, looks like the problem with {name}. Worth picking a new slot for {time_of_day}?",
    ],
    "reflection": [
        "We've missed a couple of {name} sessions in a row. No guilt — let's figure out what's getting in the way and make tomorrow easier.",
        "Two in a row on {name} now. Before another attempt: what's the real blocker here — time, energy, or something else?",
    ],
    "completion_first": [
        "You showed up. That's the hardest part of starting. Day 1 of {name} complete — you've officially begun.",
        "One down. The first day of {name} is always the hardest to start, and you just did it.",
    ],
    "completion_final": [
        "You made it through all {total} days of {name}. You kept coming back to this — that's a real accomplishment.",
        "That's the full {total}-day journey on {name} done. Take a moment to be proud of what you built.",
    ],
    "completion_recovery": [
        "You came back today — and that's what matters. One missed day didn't stop you on {name}. Back on track.",
        "That's the comeback. A miss on {name} didn't turn into two — you showed up again today.",
    ],
    "completion_milestone": [
        "Day {day} done, {completed} completed on {name} so far — real momentum. Be proud of showing up today.",
        "That's another one done. {day} days in on {name}, and you're building something real. Nice work.",
    ],
    "completion_plain": [
        "Day {day} of {name} complete. That's {completed} days now — keep it going.",
        "Done for today. {completed} days on {name} and counting.",
    ],
    "miss_single": [
        "That's okay. One missed day on {name} doesn't erase the work you've already done. Let's get back on track tomorrow.",
        "No harm done. One miss on {name} is normal — what matters is showing up again tomorrow.",
    ],
    "miss_consecutive": [
        "We've missed two days in a row on {name}. No guilt — let's figure out what's getting in the way and make tomorrow easier.",
        "Two misses in a row now. Rather than push harder, let's understand what's blocking {name} right now.",
    ],
    "summary": [
        "You completed {completed} out of {total} days of {name}. Want to continue this habit, start a new challenge, or stop here?",
        "{completed} of {total} days done on {name}. That's real, recorded progress. Continue, restart, or stop here — your call.",
    ],
}


def _compose(prompt_text: str) -> str:
    facts = _parse_facts(prompt_text)
    strategy = facts.get("strategy", "encouragement")
    template = random.choice(TEMPLATES.get(strategy, TEMPLATES["encouragement"]))
    try:
        return template.format(**facts)
    except KeyError:
        return facts.get("name", "Time for your habit.")


def _parse_facts(prompt_text: str) -> dict:
    facts = {}
    for line in prompt_text.splitlines():
        m = re.match(r"^([A-Z_]+):\s*(.*)$", line.strip())
        if m:
            key, value = m.group(1).lower(), m.group(2).strip()
            facts[key] = value

    def as_int(key, default=0):
        try:
            return int(facts.get(key, default))
        except ValueError:
            return default

    day, total = as_int("day", 1), as_int("total", 21)
    completed = as_int("completed", 0)
    window = max(1, day - 1)
    facts["day"], facts["total"], facts["completed"] = day, total, completed
    facts["window"] = window
    facts["pct"] = int(100 * completed / window) if window else 0
    facts["minutes"] = as_int("minutes", 30)
    attempts = as_int("snooze_count_today", 0)
    facts["shrink"] = max(5, facts["minutes"] // (2 + attempts))
    facts["progress_clause"] = f"You've completed {completed} so far." if completed else "Nothing to prove yet — just begin."
    facts.setdefault("name", "your habit")
    facts.setdefault("time_of_day", "your scheduled time")
    facts.setdefault("reason", "trouble with the timing")
    return facts
