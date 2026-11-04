package edu.zsc.ai.domain.service.db.impl;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Dialect-neutral lexical scan shared by hover and signature help. Handles
 * line/block comments, single-quoted strings with doubled quotes, alternative
 * quoted literals and double-quoted identifiers; name characters follow the
 * same letter/digit/_/$/# rule as the dialect completion parsers.
 */
final class SqlTextScanner {
    enum Kind { WORD, QUOTED, STRING, COMMENT, NUMBER, SYMBOL }

    record Token(Kind kind, String value, int start, int end, boolean closed) {
        boolean is(String symbol) {
            return kind == Kind.SYMBOL && value.equals(symbol);
        }
        boolean identifier() {
            return kind == Kind.WORD || kind == Kind.QUOTED;
        }
    }

    /** One dotted name part; quoted names keep their decoded spelling. */
    record Segment(String name, boolean quoted, int start, int end) {
        /** Lookup key: unquoted names fold to upper case, quoted names stay exact. */
        String key() {
            return quoted ? name : name.toUpperCase(java.util.Locale.ROOT);
        }
    }

    /** A dotted identifier chain (a[.b[.c]]) with the segment under the cursor. */
    record Chain(List<Segment> segments, Segment hovered, int hoveredIndex) {}

    /** The innermost unclosed call before the cursor. */
    record CallSite(List<Segment> name, int openOffset, int activeParameter) {}

    private SqlTextScanner() {}

    static List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        for (int i = 0; i < text.length();) {
            int start = i;
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (text.startsWith("--", i)) {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') i++;
                out.add(new Token(Kind.COMMENT, "", start, i, i < text.length()));
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
                out.add(new Token(Kind.COMMENT, "", start, i, end >= 0));
            } else if ((c == 'q' || c == 'Q') && i + 2 < text.length() && text.charAt(i + 1) == '\'') {
                char open = text.charAt(i + 2);
                char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                int end = text.indexOf("" + close + '\'', i + 3);
                i = end < 0 ? text.length() : end + 2;
                out.add(new Token(Kind.STRING, "", start, i, end >= 0));
            } else if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < text.length()) {
                    c = text.charAt(i++);
                    if (c == quote) {
                        if (i < text.length() && text.charAt(i) == quote) { value.append(quote); i++; }
                        else { closed = true; break; }
                    } else value.append(c);
                }
                out.add(new Token(quote == '"' ? Kind.QUOTED : Kind.STRING, value.toString(), start, i, closed));
            } else if (nameChar(text.codePointAt(i))) {
                boolean number = Character.isDigit(text.codePointAt(i));
                while (i < text.length() && nameChar(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i));
                out.add(new Token(number ? Kind.NUMBER : Kind.WORD, text.substring(start, i), start, i, true));
            } else {
                i++;
                out.add(new Token(Kind.SYMBOL, text.substring(start, i), start, i, true));
            }
        }
        return out;
    }

    private static boolean nameChar(int c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    /**
     * The dotted identifier chain under the cursor, or null when the cursor is
     * not on an identifier (strings, comments, numbers and symbols have no hover).
     */
    static Chain chainAt(String text, int cursor) {
        if (cursor < 0 || cursor > text.length()) return null;
        List<Token> tokens = tokenize(text);
        int at = -1;
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.start <= cursor && cursor <= token.end) {
                if (!token.identifier()) return null;
                at = i;
                break;
            }
            if (token.start > cursor) break;
        }
        if (at < 0) return null;
        int left = at, right = at;
        while (left >= 2 && tokens.get(left - 1).is(".") && tokens.get(left - 2).identifier()) left -= 2;
        while (right + 2 < tokens.size() && tokens.get(right + 1).is(".") && tokens.get(right + 2).identifier()) right += 2;
        List<Segment> segments = new ArrayList<>();
        Segment hovered = null;
        for (int i = left; i <= right; i += 2) {
            Token token = tokens.get(i);
            Segment segment = new Segment(token.value, token.kind == Kind.QUOTED, token.start, token.end);
            segments.add(segment);
            if (i == at) hovered = segment;
        }
        return new Chain(List.copyOf(segments), hovered, (at - left) / 2);
    }

    /**
     * The innermost unclosed parenthesized call whose opening parenthesis is
     * preceded by an identifier chain, with the number of top-level commas
     * between it and the cursor. Null when the cursor is inside a string or
     * comment, or not inside any call.
     */
    static CallSite callAt(String text, int cursor) {
        if (cursor < 0 || cursor > text.length()) return null;
        List<Token> tokens = tokenize(text);
        Deque<int[]> stack = new ArrayDeque<>(); // [nameStart, openTokenIndex, commas]
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.start >= cursor) break;
            boolean containsCursor = token.start < cursor && (cursor < token.end || cursor == token.end && !token.closed);
            if (containsCursor && (token.kind == Kind.STRING || token.kind == Kind.COMMENT)) return null;
            if (token.is("(")) {
                stack.push(new int[]{nameStart(tokens, i), i, 0});
            } else if (token.is(")")) {
                if (!stack.isEmpty()) stack.pop();
            } else if (token.is(",") && !stack.isEmpty()) {
                stack.peek()[2]++;
            }
        }
        if (stack.isEmpty()) return null;
        int[] frame = stack.peek();
        if (frame[0] < 0) return null; // Innermost open paren is not a call; no useful signature.
        List<Segment> name = new ArrayList<>();
        for (int i = frame[0]; i < frame[1]; i += 2) {
            Token part = tokens.get(i);
            name.add(new Segment(part.value, part.kind == Kind.QUOTED, part.start, part.end));
        }
        return new CallSite(List.copyOf(name), tokens.get(frame[1]).start, frame[2]);
    }

    /** Token index where the identifier chain immediately before token {@code open} starts, or -1. */
    private static int nameStart(List<Token> tokens, int open) {
        int back = open - 1;
        if (back < 0 || !tokens.get(back).identifier()) return -1;
        while (back >= 2 && tokens.get(back - 1).is(".") && tokens.get(back - 2).identifier()) back -= 2;
        return back;
    }
}
