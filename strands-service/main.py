"""
Strands service — the AI reasoning layer, called over HTTP by the Spring
Boot backend. Deliberately stateless: it holds no database and remembers
nothing between calls. Every request carries the full fact set it needs
(see StrandsRequest in the Spring Boot backend's strands package), matching
how legacy-reference/backend/intervention_engine.py's _facts() already built
a fully self-contained dict for each call.

Strategy SELECTION (choose_strategy()) is Spring Boot's job, not this
service's — this only turns an already-chosen key + facts into coaching
text via agent.py / coach_model.py, ported near-verbatim from the reference
FastAPI backend.
"""
from typing import Any, Dict, Optional

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

from agent import generate_with_strands

app = FastAPI(title="AI Habit Coach — Strands Service")


class GenerateRequest(BaseModel):
    key: str
    facts: Dict[str, Any] = {}


class GenerateResponse(BaseModel):
    text: str
    source: str = "strands"


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/generate", response_model=GenerateResponse)
def generate(body: GenerateRequest):
    try:
        text = generate_with_strands(body.key, body.facts)
    except Exception as e:
        # No template fallback here on purpose — that safety net lives in
        # Spring Boot's coaching layer (StrandsUnavailableException), same
        # split of responsibility as the old try/except around the
        # in-process call in legacy-reference/backend/intervention_engine.py.
        raise HTTPException(status_code=502, detail=f"generation failed: {e}") from e
    return GenerateResponse(text=text, source="strands")
