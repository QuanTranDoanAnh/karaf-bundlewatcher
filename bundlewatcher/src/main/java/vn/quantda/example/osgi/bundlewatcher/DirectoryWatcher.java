package vn.quantda.example.osgi.bundlewatcher;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.karaf.cave.server.api.CaveRepository;
import org.apache.karaf.cave.server.api.CaveRepositoryService;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DirectoryWatcher extends Thread {

	public final static String FILENAME = "quantda.bundlewatcher.filename";
    public final static String POLL = "quantda.bundlewatcher.poll";
    public final static String DIR = "quantda.bundlewatcher.dir";
    public final static String LOG_LEVEL = "quantda.bundlewatcher.log.level";
    public final static String LOG_DEFAULT = "quantda.bundlewatcher.log.default";
    public final static String TMPDIR = "quantda.bundlewatcher.tmpdir";
    public final static String FILTER = "quantda.bundlewatcher.filter";
    public final static String DISABLE_CONFIG_SAVE = "quantda.bundlewatcher.disableConfigSave";
    public final static String ENABLE_CONFIG_SAVE = "quantda.bundlewatcher.enableConfigSave";
    public final static String CONFIG_ENCODING = "quantda.bundlewatcher.configEncoding";
    public final static String DISABLE_NIO2 = "quantda.bundlewatcher.disableNio2";
    public final static String SUBDIR_MODE = "quantda.bundlewatcher.subdir.mode";

    public final static String SCOPE_NONE = "none";
    public final static String SCOPE_MANAGED = "managed";
    public final static String SCOPE_ALL = "all";

    public final static String LOG_STDOUT = "stdout";
    public final static String LOG_JUL = "jul";
    
    private final Logger LOG = LoggerFactory.getLogger(DirectoryWatcher.class);
    
	BundleWatcher bundleWatcher;
	Map<String, String> properties;
	BundleContext context;
	private Bundle systemBundle;
    String originatingFileName;
    
    File watchedDirectory;
    File tmpDir;
    long poll;
    String filter;
    boolean disableNio2;
    
    // The scanner to report files changes
    Scanner scanner;
    
    // Cave Repository Service
    private CaveRepositoryService caveRepoService;
	
	public DirectoryWatcher(BundleWatcher bundleWatcher, Map<String, String> properties, BundleContext context)
    {
		super("bundlewatcher-" + getThreadName(properties));
		this.bundleWatcher = bundleWatcher;
		this.properties = properties;
		this.context = context;
		systemBundle = context.getBundle(Constants.SYSTEM_BUNDLE_LOCATION);
        poll = getLong(properties, POLL, 2000);
		originatingFileName = properties.get(FILENAME);
		LOG.info("originatingFileName: " + originatingFileName);
		watchedDirectory = getFile(properties, DIR, new File("./load"));
		LOG.info("watchedDirectory: " + watchedDirectory.getAbsolutePath());
		verifyWatchedDir();
        tmpDir = getFile(properties, TMPDIR, null);
        prepareTempDir();
        
        filter = properties.get(FILTER);
        disableNio2 = getBoolean(properties, DISABLE_NIO2, false);
        
        if (disableNio2) {
            scanner = new Scanner(watchedDirectory, filter, properties.get(SUBDIR_MODE));
        } else {
            try {
                scanner = new WatcherScanner(context, watchedDirectory, filter, properties.get(SUBDIR_MODE));
            } catch (Throwable t) {
                scanner = new Scanner(watchedDirectory, filter, properties.get(SUBDIR_MODE));
            }
        }
    }
	
	public static String getThreadName(Map<String, String> properties)
    {
        return (properties.get(DIR) != null ? properties.get(DIR) : "./load");
    }

	/**
     * Retrieve a property as a boolan.
     *
     * @param properties the properties to retrieve the value from
     * @param property the name of the property to retrieve
     * @param dflt the default value
     * @return the property as a boolean or the default value
     */
    boolean getBoolean(Map<String, String> properties, String property, boolean dflt)
    {
        String value = properties.get(property);
        if (value != null)
        {
            return Boolean.valueOf(value);
        }
        return dflt;
    }
    
    /**
     * Retrieve a property as a long.
     *
     * @param properties the properties to retrieve the value from
     * @param property the name of the property to retrieve
     * @param dflt the default value
     * @return the property as a long or the default value
     */
    long getLong(Map<String, String> properties, String property, long dflt)
    {
        String value = properties.get(property);
        if (value != null)
        {
            try
            {
                return Long.parseLong(value);
            }
            catch (Exception e)
            {
                LOG.warn(property + " set, but not a long: " + value);
            }
        }
        return dflt;
    }
    
    public void setCaveRepoService(CaveRepositoryService service) {
    	this.caveRepoService = service;
    }

	private void prepareTempDir() {
		if (tmpDir == null)
        {
            File javaIoTmpdir = new File(System.getProperty("java.io.tmpdir"));
            if (!javaIoTmpdir.exists() && !javaIoTmpdir.mkdirs()) {
                throw new IllegalStateException("Unable to create temporary directory " + javaIoTmpdir);
            }
            Random random = new Random();
            while (tmpDir == null)
            {
                File f = new File(javaIoTmpdir, "bundlewatcher-" + Long.toString(random.nextLong()));
                if (!f.exists() && f.mkdirs())
                {
                    tmpDir = f;
                    tmpDir.deleteOnExit();
                }
            }
        }
        else
        {
            prepareDir(tmpDir);
        }
		
	}

	/**
     * Create the watched directory, if not existing.
     * Throws a runtime exception if the directory cannot be created,
     * or if the provided File parameter does not refer to a directory.
     *
     * @param dir
     *            The directory File Install will monitor
     */
	private void prepareDir(File dir) {
		if (!dir.exists() && !dir.mkdirs())
        {
            LOG.error(
                "Cannot create folder "
                + dir
                + ". Is the folder write-protected?", (Exception) null);
            throw new RuntimeException("Cannot create folder: " + dir);
        }

        if (!dir.isDirectory())
        {
        	LOG.error(
                "Cannot use "
                + dir
                + " because it's not a directory", (Exception) null);
            throw new RuntimeException(
                "Cannot start BundleWatcher using something that is not a directory");
        }
		
	}

	private void verifyWatchedDir() {
		if (!watchedDirectory.exists())
        {
            // Issue #2069: Do not create the directory if it does not exist,
            // instead, warn user and continue. We will automatically start
            // monitoring the dir when it becomes available.
            LOG.warn(watchedDirectory + " does not exist, please create it.",(Exception) null);
        }
        else if (!watchedDirectory.isDirectory())
        {
        	LOG.error("Cannot use "
                + watchedDirectory
                + " because it's not a directory", (Exception) null);
            throw new RuntimeException(
                "File Install can't monitor " + watchedDirectory + " because it is not a directory");
        }
		
	}

	File getFile(Map<String, String> properties, String property, File file) {
		String value = properties.get(property);
        if (value != null)
        {
            return new File(value);
        }
        return file;
	}

	public Map<String, String> getProperties() {
		return properties;
	}

	@Override
	public void run() {
		LOG.info("Running new round");
		// We must wait for FileInstall to complete initialisation
        // to avoid race conditions observed in FELIX-2791
        try
        {
            bundleWatcher.lock.readLock().lockInterruptibly();
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            LOG.info("Watcher for " + watchedDirectory + " exiting because of interruption.", e);
            return;
        }
        try {
            LOG.info(
                    "{" + POLL + " (ms) = " + poll + ", "
                            + DIR + " = " + watchedDirectory.getAbsolutePath() + ", "
                            + TMPDIR + " = " + tmpDir + ", "
                            + FILTER + " = " + filter + "}"
            );

            try {
                // enforce a delay before the first directory scan
                Thread.sleep(poll);
            } catch (InterruptedException e) {
                LOG.info("Watcher for " + watchedDirectory + " was interrupted while waiting "
                        + poll + " milliseconds for initial directory scan.", e);
                return;
            }
        }
        finally
        {
        	bundleWatcher.lock.readLock().unlock();
        }

        while (!interrupted()) {
            try {
                // Don't access the disk when the framework is still in a startup phase.
                if (systemBundle.getState() == Bundle.ACTIVE) {
                    Set<File> files = scanner.scan(false);
                    // Check that there is a result.  If not, this means that the directory can not be listed,
                    // so it's presumably not a valid directory (it may have been deleted by someone).
                    // In such case, just sleep
                    if (files != null) {
                        process(files);
                    }
                }
                synchronized (this) {
                    wait(poll);
                }
            } catch (InterruptedException e) {
                interrupt();
                return;
            } catch (Throwable e) {
                try {
                    context.getBundle();
                } catch (IllegalStateException t) {
                    // FileInstall bundle has been uninstalled, exiting loop
                    return;
                }
                LOG.error("In main loop, we have serious trouble", e);
            }
        }
	}
	
	public void close() {
		interrupt();
        try
        {
            scanner.close();
        }
        catch (IOException e)
        {
            // Ignore
        }
        try
        {
            join(10000);
        }
        catch (InterruptedException ie)
        {
            // Ignore
        }
		
	}
	
	@Override
	public void start() {
		LOG.info("Starting initial scan");
        Set<File> files = scanner.scan(true);
        if (files != null)
        {
            try
            {
                process(files);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
		super.start();
	}

	private void process(Set<File> files) throws InterruptedException
    {
        bundleWatcher.lock.readLock().lockInterruptibly();
        try
        {
            doProcess(files);
        }
        finally
        {
        	bundleWatcher.lock.readLock().unlock();
        }
    }

	private void doProcess(Set<File> files) throws InterruptedException
    {
		if (!files.isEmpty()) {
			LOG.info("Something changes in your watched directory");
		}
        for(File file : files) {
        	// if file is a jar
        	if (file.isFile() && file.getName().endsWith(".jar")) {
        		LOG.info("This is a Java jar file: " + file.toURI().toString());
        		if (this.caveRepoService != null) {
        			LOG.info("Now we can upload to Cave");
        			CaveRepository caveRepo = this.caveRepoService.getRepositories()[0];
        			try {
						caveRepo.upload(file.toURI().toURL());
					} catch (Exception e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
        		}
        	}
        }
    }

}
