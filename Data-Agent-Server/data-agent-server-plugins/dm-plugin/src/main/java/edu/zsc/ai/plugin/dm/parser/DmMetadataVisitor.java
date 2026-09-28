package edu.zsc.ai.plugin.dm.parser;

import edu.zsc.ai.plugin.dm.parser.base.DMParser;
import edu.zsc.ai.plugin.dm.parser.base.DMParserBaseVisitor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.antlr.v4.runtime.tree.ParseTree;

final class DmMetadataVisitor extends DMParserBaseVisitor<Void> {

    private final Set<String> tables = new LinkedHashSet<>();
    private final Set<String> columns = new LinkedHashSet<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();
    private boolean potentiallyMutatingExpression;

    @Override
    public Void visitTable_ref_aux(DMParser.Table_ref_auxContext context) {
        DMParser.Dml_table_expression_clauseContext expression =
                context.table_ref_aux_internal() instanceof DMParser.Table_ref_aux_internal_oneContext direct
                        ? direct.dml_table_expression_clause() : null;
        if (expression != null && expression.tableview_name() != null) {
            String name = expression.tableview_name().getText();
            tables.add(name);
            if (context.table_alias() != null) {
                aliases.put(context.table_alias().getText(), name);
            }
        }
        return visitChildren(context);
    }

    @Override
    public Void visitGeneral_table_ref(DMParser.General_table_refContext context) {
        if (context.dml_table_expression_clause() != null
                && context.dml_table_expression_clause().tableview_name() != null) {
            tables.add(context.dml_table_expression_clause().tableview_name().getText());
        }
        return visitChildren(context);
    }

    @Override
    public Void visitTableview_name(DMParser.Tableview_nameContext context) {
        if (context.getParent() instanceof DMParser.Merge_statementContext
                || context.getParent() instanceof DMParser.Alter_tableContext
                || context.getParent() instanceof DMParser.Drop_tableContext
                || context.getParent() instanceof DMParser.Truncate_tableContext
                || context.getParent() instanceof DMParser.Drop_viewContext) {
            tables.add(context.getText());
        }
        return visitChildren(context);
    }

    @Override
    public Void visitCreate_table(DMParser.Create_tableContext context) {
        String schema = context.schema_name() == null ? "" : context.schema_name().getText() + ".";
        tables.add(schema + context.table_name().getText());
        return visitChildren(context);
    }

    @Override
    public Void visitCreate_view(DMParser.Create_viewContext context) {
        String schema = context.schema_name() == null ? "" : context.schema_name().getText() + ".";
        tables.add(schema + context.v.getText());
        return visitChildren(context);
    }

    @Override
    public Void visitColumn_name(DMParser.Column_nameContext context) {
        columns.add(context.getText());
        return visitChildren(context);
    }

    @Override
    public Void visitGeneral_element(DMParser.General_elementContext context) {
        if (!(context.getParent() instanceof DMParser.General_elementContext)
                && isExpression(context)
                && !hasFunctionArguments(context)) {
            columns.add(context.getText());
        }
        return visitChildren(context);
    }

    @Override
    public Void visitGeneral_element_part(DMParser.General_element_partContext context) {
        if (!context.function_argument().isEmpty()
                || context.id_expression() != null
                && "NEXTVAL".equalsIgnoreCase(context.id_expression().getText())) {
            potentiallyMutatingExpression = true;
        }
        return visitChildren(context);
    }

    @Override
    public Void visitStandard_function(DMParser.Standard_functionContext context) {
        potentiallyMutatingExpression = true;
        return visitChildren(context);
    }

    List<String> tables() {
        return new ArrayList<>(tables);
    }

    List<String> columns() {
        return new ArrayList<>(columns);
    }

    Map<String, String> aliases() {
        return Map.copyOf(aliases);
    }

    boolean potentiallyMutatingExpression() {
        return potentiallyMutatingExpression;
    }

    private boolean isExpression(ParseTree context) {
        for (ParseTree parent = context.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof DMParser.ExpressionContext) return true;
            if (parent instanceof DMParser.Table_refContext) return false;
        }
        return false;
    }

    private boolean hasFunctionArguments(ParseTree context) {
        if (context instanceof DMParser.General_element_partContext part
                && !part.function_argument().isEmpty()) return true;
        for (int index = 0; index < context.getChildCount(); index++) {
            if (hasFunctionArguments(context.getChild(index))) return true;
        }
        return false;
    }
}
