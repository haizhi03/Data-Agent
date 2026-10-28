package edu.zsc.ai.local;

import edu.zsc.ai.util.PrivateFiles;
import java.nio.file.Path;

/** Local repository alias; model and connection stores share the same file protection rules. */
final class LocalPrivateFiles extends PrivateFiles {
    LocalPrivateFiles(Path path) { super(path); }
    LocalPrivateFiles(Path path, AtomicReplace replace) { super(path, replace); }
}
