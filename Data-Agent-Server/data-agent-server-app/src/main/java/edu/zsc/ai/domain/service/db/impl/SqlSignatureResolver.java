package edu.zsc.ai.domain.service.db.impl;

import edu.zsc.ai.domain.model.context.DbContext;
import edu.zsc.ai.domain.model.dto.response.db.SqlSignatureHelpResponse;
import edu.zsc.ai.domain.model.dto.response.db.SqlSignatureItem;
import edu.zsc.ai.domain.model.dto.response.db.SqlSignatureParameter;
import edu.zsc.ai.plugin.model.metadata.FunctionMetadata;
import edu.zsc.ai.plugin.model.metadata.ParameterInfo;
import edu.zsc.ai.plugin.model.metadata.ProcedureMetadata;
import edu.zsc.ai.plugin.model.sql.CompletionFunction;
import edu.zsc.ai.plugin.model.sql.CompletionRoutine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Resolves the call at the cursor to signature help. Custom functions,
 * procedures and package members come from account-visible metadata (null when
 * offline); dialect built-ins come from the completion function catalog and
 * always remain available.
 */
final class SqlSignatureResolver {
    private final DbContext db;
    private final SqlCompletionMetadataResolver metadata;
    private final List<CompletionFunction> builtins;

    SqlSignatureResolver(DbContext db, SqlCompletionMetadataResolver metadata, List<CompletionFunction> builtins) {
        this.db = db;
        this.metadata = metadata;
        this.builtins = builtins == null ? List.of() : builtins;
    }

    SqlSignatureHelpResponse resolve(SqlTextScanner.CallSite call) {
        List<SqlSignatureItem> items = new ArrayList<>();
        SqlTextScanner.Segment name = call.name().get(call.name().size() - 1);
        if (metadata != null) {
            switch (call.name().size()) {
                case 1 -> {
                    for (FunctionMetadata function : metadata.functions(db))
                        if (matches(function.name(), name)) items.add(callable(function.name(), function.parameters(), function.returnType()));
                    for (ProcedureMetadata procedure : metadata.procedures(db))
                        if (matches(procedure.name(), name)) items.add(callable(procedure.name(), procedure.parameters(), null));
                }
                case 2 -> qualified(call.name().get(0), name, items);
                case 3 -> {
                    DbContext owner = schema(call.name().get(0).key());
                    if (owner != null)
                        for (CompletionRoutine routine : metadata.packageMembers(owner, call.name().get(1).key()))
                            if (matches(routine.name(), name)) items.add(routine(routine));
                }
                default -> { }
            }
        }
        if (items.isEmpty() && call.name().size() == 1 && !name.quoted()) {
            for (CompletionFunction function : builtins) {
                if (function.name().equalsIgnoreCase(name.name())) {
                    items.add(builtin(function));
                    break;
                }
            }
        }
        return select(items, call.activeParameter());
    }

    private void qualified(SqlTextScanner.Segment qualifier, SqlTextScanner.Segment name, List<SqlSignatureItem> items) {
        if (metadata.packages(db).contains(qualifier.key())) {
            for (CompletionRoutine routine : metadata.packageMembers(db, qualifier.key()))
                if (matches(routine.name(), name)) items.add(routine(routine));
            return;
        }
        DbContext owner = schema(qualifier.key());
        if (owner == null) return;
        for (FunctionMetadata function : metadata.functions(owner))
            if (matches(function.name(), name)) items.add(callable(function.name(), function.parameters(), function.returnType()));
        for (ProcedureMetadata procedure : metadata.procedures(owner))
            if (matches(procedure.name(), name)) items.add(callable(procedure.name(), procedure.parameters(), null));
    }

    private DbContext schema(String schema) {
        if (schema == null || schema.isBlank()) return null;
        return new DbContext(db.connectionId(), db.catalog(), schema);
    }

    private static boolean matches(String metadataName, SqlTextScanner.Segment segment) {
        return metadataName.equals(segment.key());
    }

    /** Picks the overload that still has a parameter at the cursor's argument index. */
    private static SqlSignatureHelpResponse select(List<SqlSignatureItem> items, int activeParameter) {
        if (items.isEmpty()) return SqlSignatureHelpResponse.empty();
        items.sort(Comparator.comparingInt(i -> i.parameters().size()));
        int activeSignature = items.size() - 1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).parameters().size() > activeParameter) { activeSignature = i; break; }
        }
        int count = items.get(activeSignature).parameters().size();
        int clamped = count == 0 ? 0 : Math.min(activeParameter, count - 1);
        return new SqlSignatureHelpResponse(List.copyOf(items), activeSignature, clamped);
    }

    private static SqlSignatureItem callable(String name, List<ParameterInfo> parameters, String returnType) {
        String label = signatureLabel(name, parameters, returnType);
        List<SqlSignatureParameter> params = parameters == null ? List.of()
                : parameters.stream().map(p -> new SqlSignatureParameter(parameterLabel(p))).toList();
        return new SqlSignatureItem(label, params);
    }

    private static SqlSignatureItem routine(CompletionRoutine routine) {
        return new SqlSignatureItem(routineLabel(routine),
                routine.parameters() == null ? List.of()
                        : routine.parameters().stream().map(p -> new SqlSignatureParameter(parameterLabel(p))).toList());
    }

    static String routineLabel(CompletionRoutine routine) {
        return signatureLabel(routine.name(), routine.parameters(), routine.procedure() ? null : routine.returnType());
    }

    private static String signatureLabel(String name, List<ParameterInfo> parameters, String returnType) {
        String args = parameters == null ? "…"
                : String.join(", ", parameters.stream().map(SqlSignatureResolver::parameterLabel).toList());
        return name + "(" + args + ")" + (returnType == null ? "" : " → " + returnType);
    }

    private static String parameterLabel(ParameterInfo parameter) {
        return (parameter.name() + " " + Objects.toString(parameter.dataType(), "")).trim();
    }

    /** Parses the catalog signature text {@code NAME(p:T, [opt:T]) → R} into label and parameters. */
    private static SqlSignatureItem builtin(CompletionFunction function) {
        String signature = function.signature();
        int open = signature.indexOf('(');
        if (open < 0) return new SqlSignatureItem(signature, List.of());
        int close = signature.lastIndexOf(')');
        if (close <= open) return new SqlSignatureItem(signature, List.of());
        List<SqlSignatureParameter> params = new ArrayList<>();
        for (String raw : splitTopLevel(signature.substring(open + 1, close))) {
            String param = raw.trim();
            if (param.startsWith("[") && param.endsWith("]")) param = param.substring(1, param.length() - 1).trim();
            if (!param.isEmpty()) params.add(new SqlSignatureParameter(param));
        }
        return new SqlSignatureItem(signature, List.copyOf(params));
    }

    private static List<String> splitTopLevel(String args) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) { parts.add(args.substring(start, i)); start = i + 1; }
        }
        if (start < args.length()) parts.add(args.substring(start));
        return parts;
    }
}
