package com.habitcoach.strands;

/** `source` is always "strands" for a successful call from the Python side. */
public record StrandsResponse(String text, String source) {
}
