package edu.campusai.rag;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads only claims[].text from an incomplete JSON document; never exposes IDs or quotes. */
final class StreamingClaimText {
    private final String input;
    private final List<StringBuilder> texts = new ArrayList<>();
    private int offset;

    private StreamingClaimText(String input) { this.input = input; }

    static String preview(String input) {
        StreamingClaimText parser = new StreamingClaimText(input);
        try { parser.root(); }
        catch (Incomplete ignored) { /* A partial JSON string is expected between provider deltas. */ }
        List<String> pieces = new ArrayList<>();
        for (StringBuilder text : parser.texts) {
            int end = text.length();
            // JSON escapes and raw surrogate pairs may be split between two provider deltas.
            if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            if (end > 0) pieces.add(text.substring(0, end));
        }
        return String.join("\n\n", pieces);
    }

    private void root() {
        expect('{');
        if (!"claims".equals(string(null))) throw invalid();
        expect(':'); expect('[');
        int count = 0;
        if (!take(']')) {
            do {
                if (++count > 8) throw invalid();
                claim();
            } while (take(','));
            expect(']');
        }
        expect('}'); whitespace();
        if (offset != input.length()) throw invalid();
    }

    private void claim() {
        expect('{');
        Set<String> keys = new HashSet<>();
        if (!take('}')) {
            do {
                String key = string(null);
                if (!Set.of("text", "chunkId", "quote").contains(key) || !keys.add(key)) throw invalid();
                expect(':');
                StringBuilder capture = null;
                if (key.equals("text")) {
                    capture = new StringBuilder(); texts.add(capture);
                }
                string(capture);
            } while (take(','));
            expect('}');
        }
    }

    private String string(StringBuilder capture) {
        expect('"'); StringBuilder value = capture == null ? new StringBuilder() : capture;
        while (true) {
            char c = next();
            if (c == '"') return value.toString();
            if (c < 0x20) throw invalid();
            if (c == '\\') {
                c = next();
                c = switch (c) {
                    case '"', '\\', '/' -> c;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n';
                    case 'r' -> '\r'; case 't' -> '\t';
                    case 'u' -> unicode();
                    default -> throw invalid();
                };
            }
            value.append(c);
            if (value.length() > (capture == null ? 12000 : 1200)) throw invalid();
        }
    }

    private char unicode() {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(next(), 16);
            if (digit < 0) throw invalid();
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private void whitespace() { while (offset < input.length() && " \t\r\n".indexOf(input.charAt(offset)) >= 0) offset++; }
    private char next() { if (offset == input.length()) throw new Incomplete(); return input.charAt(offset++); }
    private void expect(char wanted) { whitespace(); if (next() != wanted) throw invalid(); }
    private boolean take(char wanted) { whitespace(); if (offset == input.length()) throw new Incomplete(); if (input.charAt(offset) != wanted) return false; offset++; return true; }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid streaming model structure"); }
    private static final class Incomplete extends RuntimeException { @Override public synchronized Throwable fillInStackTrace() { return this; } }
}
