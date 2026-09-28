package edu.zsc.ai.domain.service.db.support;

import edu.zsc.ai.plugin.capability.ConnectionManager;
import edu.zsc.ai.plugin.connection.ConnectionConfig;
import edu.zsc.ai.plugin.connection.ConnectionFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * A first-success handler chain for connection managers.
 */
public final class ConnectionManagerChain<R> {

    private final List<ConnectionManagerHandler<R>> handlers;

    private ConnectionManagerChain(List<ConnectionManagerHandler<R>> handlers) {
        this.handlers = List.copyOf(handlers);
    }

    public static <R> ConnectionManagerChain<R> fromManagers(List<ConnectionManager> managers,
                                                             BiFunction<ConnectionManager, ConnectionConfig, R> action) {
        if (managers == null || managers.isEmpty()) {
            throw new IllegalArgumentException("Connection managers cannot be null or empty");
        }
        List<ConnectionManagerHandler<R>> handlers = managers.stream()
                .<ConnectionManagerHandler<R>>map(manager -> new DefaultConnectionManagerHandler<>(manager, action))
                .toList();
        return new ConnectionManagerChain<>(handlers);
    }

    /**
     * Try each handler in order and return the first success. When every handler fails, throw an
     * aggregated {@link ConnectionFailureException} whose suppressed exceptions carry each attempt's
     * failure reason instead of swallowing them.
     */
    public ConnectionManagerHandleResult<R> handle(ConnectionConfig config) {
        List<RuntimeException> failures = new ArrayList<>();
        for (ConnectionManagerHandler<R> handler : handlers) {
            try {
                return handler.handle(config);
            } catch (RuntimeException e) {
                failures.add(e);
            }
        }
        ConnectionFailureException aggregate = new ConnectionFailureException(
                ConnectionFailureException.Category.UNKNOWN,
                String.format("All %d connection manager attempt(s) failed", failures.size()),
                failures.get(0));
        failures.forEach(aggregate::addSuppressed);
        throw aggregate;
    }

    public record ConnectionManagerHandleResult<R>(ConnectionManager manager, R result) {
    }

    private interface ConnectionManagerHandler<R> {

        ConnectionManagerHandleResult<R> handle(ConnectionConfig config);
    }

    private record DefaultConnectionManagerHandler<R>(
            ConnectionManager manager,
            BiFunction<ConnectionManager, ConnectionConfig, R> action) implements ConnectionManagerHandler<R> {

        @Override
        public ConnectionManagerHandleResult<R> handle(ConnectionConfig config) {
            return new ConnectionManagerHandleResult<>(manager, action.apply(manager, config));
        }
    }
}
