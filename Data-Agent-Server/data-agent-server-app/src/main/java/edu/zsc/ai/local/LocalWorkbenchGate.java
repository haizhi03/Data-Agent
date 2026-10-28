package edu.zsc.ai.local;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

/** Imported legacy metadata controllers contain writes too. Only these read handlers may run locally. */
final class LocalWorkbenchGate implements WebMvcConfigurer {
    private static final Set<String> READS = Set.of("/api/databases", "/api/schemas", "/api/tables", "/api/tables/ddl",
            "/api/tables/data", "/api/tables/er-diagram", "/api/views", "/api/views/ddl", "/api/views/data", "/api/columns",
            "/api/primary-keys", "/api/indexes", "/api/constraints", "/api/constraints/ddl", "/api/sequences", "/api/sequences/ddl",
            "/api/functions", "/api/functions/ddl", "/api/procedures", "/api/procedures/ddl", "/api/triggers", "/api/triggers/ddl",
            "/api/db-types", "/api/db/sql/completion-dictionary");
    private static final Set<String> HELPERS = Set.of("/api/db/sql/complete", "/api/db/sql/hover", "/api/db/sql/signature",
            "/api/db/sql/completion-invalidate", "/api/db/sql/analyze");
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) {
                if (!(handler instanceof HandlerMethod method)
                        || !method.getBeanType().getPackageName().equals("edu.zsc.ai.api.controller.db")) return true;
                String path = request.getRequestURI().substring(request.getContextPath().length());
                if (method.getBeanType().getSimpleName().equals("DriverController")) return true;
                if (!("GET".equals(request.getMethod()) && READS.contains(path))
                        && !("POST".equals(request.getMethod()) && HELPERS.contains(path))) throw LocalReadOnlySql.unsupported();
                if (path.equals("/api/tables/data") || path.equals("/api/views/data")) validatePreview(request);
                return true;
            }
        });
    }
    private static void validatePreview(HttpServletRequest request) {
        try {
            int page = Integer.parseInt(request.getParameter("currentPage") == null ? "1" : request.getParameter("currentPage"));
            int size = Integer.parseInt(request.getParameter("pageSize") == null ? "100" : request.getParameter("pageSize"));
            if (page < 1 || size < 1 || size > 10000 || (long) (page - 1) * size > Integer.MAX_VALUE) throw LocalRequestException.invalid();
        } catch (NumberFormatException e) { throw LocalRequestException.invalid(); }
        String where = request.getParameter("whereClause");
        if (where != null && !where.isBlank()) LocalReadOnlySql.validate("SELECT * FROM local_preview WHERE " + where);
        String direction = request.getParameter("orderByDirection");
        if (direction != null && !direction.equalsIgnoreCase("ASC") && !direction.equalsIgnoreCase("DESC")) throw LocalRequestException.invalid();
    }
}
