package edu.zsc.ai.plugin.dm.parser.base;

import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.TokenStream;

public abstract class DMParserBase extends Parser {

    protected DMParserBase(TokenStream input) {
        super(input);
    }

    public boolean isVersion12() {
        return true;
    }

    public boolean isVersion10() {
        return true;
    }
}
