package com.habitcoach.strands;

import java.util.Map;

/**
 * Request body for the Python Strands service's POST /generate.
 *
 * `key` is the same string the reference implementation's CoachModel already
 * uses as a TEMPLATES lookup key (legacy-reference/backend/coach_model.py) —
 * an intervention strategy name (encouragement, reduce_task, ...), a
 * post-action response kind (completion_first, miss_consecutive, ...), or
 * "summary". `facts` is the fully self-contained fact dict for that call
 * (see legacy-reference/backend/intervention_engine.py's _facts()) — the
 * Strands service holds no state of its own and needs nothing else.
 */
public record StrandsRequest(String key, Map<String, Object> facts) {
}
