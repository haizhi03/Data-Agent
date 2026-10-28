package edu.zsc.ai.local;

import edu.zsc.ai.context.RequestContext;
import java.nio.file.Path;

/** Test child JVM: exercises the real OS lock rather than a second object sharing the JVM mutex. */
public final class LocalConnectionWriterProcess {
    public static void main(String[] args) {
        RequestContext.set(new LocalRuntimeIdentity().newRequestContext());
        var service = new LocalConnectionService(new LocalConnectionRepository(Path.of(args[0])),
                (manager, config, request) -> { throw new AssertionError("No database connection in this test"); }, event -> {});
        try {
            for (int n = 0; n < 6; n++) service.save(LocalConnectionStoreTest.input(args[2] + "-" + n, Path.of(args[1])), true);
        } finally { service.close(); RequestContext.clear(); }
    }
}
