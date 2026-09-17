"""
Basic tests for the Strands service's HTTP surface, using the default
"local" (offline, phrase-bank) provider so these never need network access
or credentials — same guarantee the reference implementation made.
"""
from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_generate_intervention_strategy():
    response = client.post("/generate", json={
        "key": "encouragement",
        "facts": {"name": "gym", "day": 1, "total": 21, "completed": 0},
    })
    assert response.status_code == 200
    body = response.json()
    assert body["source"] == "strands"
    assert "gym" in body["text"]


def test_generate_post_action_response_kind():
    response = client.post("/generate", json={
        "key": "completion_first",
        "facts": {"name": "gym", "day": 1, "total": 21, "completed": 1},
    })
    assert response.status_code == 200
    body = response.json()
    assert "Day 1" in body["text"] or "day 1" in body["text"].lower() or "gym" in body["text"]


def test_generate_summary():
    response = client.post("/generate", json={
        "key": "summary",
        "facts": {"name": "gym", "total": 21, "completed": 19},
    })
    assert response.status_code == 200
    body = response.json()
    assert "19" in body["text"] and "21" in body["text"]
