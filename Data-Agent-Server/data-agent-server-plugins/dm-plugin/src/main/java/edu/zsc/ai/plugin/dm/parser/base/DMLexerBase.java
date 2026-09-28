package edu.zsc.ai.plugin.dm.parser.base;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;

public abstract class DMLexerBase extends Lexer {

    protected DMLexerBase(CharStream input) {
        super(input);
    }

    protected boolean IsNewlineAtPos(int position) {
        int next = _input.LA(position);
        return next == -1 || next == '\n';
    }
}
