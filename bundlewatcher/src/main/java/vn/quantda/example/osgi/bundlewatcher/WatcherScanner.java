package vn.quantda.example.osgi.bundlewatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class WatcherScanner extends Scanner {
	private final Logger LOG = LoggerFactory.getLogger(WatcherScanner.class);
	BundleContext bundleContext;
    PathMatcher fileMatcher;
    Watcher watcher;

    Set<File> changed = new HashSet<File>();

    /**
     * Create a scanner for the specified directory and file filter
     *
     * @param directory the directory to scan
     * @param filterString a filter for file names
     * @param subdirMode to use when scanning
     */
    public WatcherScanner(BundleContext bundleContext, File directory, String filterString, String subdirMode) throws IOException {
        super(directory, filterString, subdirMode);
        this.bundleContext = bundleContext;
        if (filterString != null) {
            this.fileMatcher = FileSystems.getDefault().getPathMatcher("regex:" + filterString);
        } else {
            this.fileMatcher = null;
        }
        this.watcher = new ScannerWatcher();
        this.watcher.setFileMatcher(fileMatcher);
        this.watcher.setRootDirectory(this.directory);
        this.watcher.init();
        this.watcher.rescan();
    }

    public Set<File> scan(boolean reportImmediately) {
        watcher.processEvents();
        synchronized (changed) {
            if (changed.isEmpty()) {
                return new HashSet<File>();
            }
            LOG.debug("Number of changes: " + changed.size() + " in " + this.directory.getAbsolutePath());
            Set<File> files = new HashSet<File>();
            Set<File> removed = new HashSet<File>();
            if (reportImmediately) {
                removed.addAll(storedChecksums.keySet());
            }
            for (Iterator<File> iterator = changed.iterator(); iterator.hasNext(); ) {
                File file = iterator.next();
                long lastChecksum = lastChecksums.get(file) != null ? (Long) lastChecksums.get(file) : 0;
                long storedChecksum = storedChecksums.get(file) != null ? (Long) storedChecksums.get(file) : 0;
                long newChecksum = checksum(file);
                lastChecksums.put(file, newChecksum);
                if (file.exists()) {
                    // Only handle file when it does not change anymore and it has changed since last reported
                    if ((newChecksum == lastChecksum || reportImmediately)) {
                        if (newChecksum != storedChecksum) {
                            storedChecksums.put(file, newChecksum);
                            files.add(file);
                        } else {
                            iterator.remove();
                        }
                        if (reportImmediately) {
                            removed.remove(file);
                        }
                    }
                } else {
                    if (!reportImmediately) {
                        removed.add(file);
                    }
                }
            }
            for (File file : removed) {
                // Make sure we'll handle a file that has been deleted
                files.add(file);
                // Remove no longer used checksums
                lastChecksums.remove(file);
                storedChecksums.remove(file);
                changed.remove(file);
            }

            return files;
        }
    }

    public void close() throws IOException {
        watcher.close();
    }

    class ScannerWatcher extends Watcher {

        @Override
        protected void process(Path path) {
            File file = path.toFile();
            if (!file.getParentFile().equals(directory)) {
              // File is in a sub directory.
              if (skipSubdir) {
                return;
              }
              if (jarSubdir) {
                // Walk up until the first level sub-directory.
                do  {
                  file = file.getParentFile();
                  if (file == null) {
                    // The file was not actually inside the watched directory.
                    // Should not happen.
                    return;
                  }
                } while (!file.getParentFile().equals(directory));
              }
              // Otherwise we recurse by adding the file as-is.
            }
            synchronized (changed) {
                changed.add(file);
            }
        }

        @Override
        protected void onRemove(Path path) {
            process(path);
        }

        @Override
        protected void debug(String message, Object... args) {
            LOG.debug(message, args);
        }

        @Override
        protected void warn(String message, Object... args) {
            LOG.warn(message, args);
        }

        protected void log(int level, String message, Object... args) {
            String msg = String.format(message, args);
            LOG.debug(msg, (Exception) null);
        }

    }
}
