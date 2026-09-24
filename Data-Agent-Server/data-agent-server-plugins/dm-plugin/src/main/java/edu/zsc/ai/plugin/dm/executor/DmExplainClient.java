package edu.zsc.ai.plugin.dm.executor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

final class DmExplainClient {

    String getExplainInfo(Connection connection, String sql) throws SQLException {
        try {
            Connection underlying = connection.unwrap(Connection.class);
            ClassLoader driverLoader = underlying.getClass().getClassLoader();
            Class<?> driverConnection = Class.forName("dm.jdbc.driver.DmdbConnection", true, driverLoader);
            Object target = driverConnection.isInstance(underlying)
                    ? underlying : connection.unwrap(driverConnection.asSubclass(Connection.class));
            Method explain = driverConnection.getMethod("getExplainInfo", String.class);
            return (String) explain.invoke(target, sql);
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof SQLException sqlException) {
                throw sqlException;
            }
            throw new SQLException("DM explain failed", exception.getCause());
        } catch (ReflectiveOperationException | ClassCastException exception) {
            throw new SQLException("DM driver does not support getExplainInfo", exception);
        }
    }
}
