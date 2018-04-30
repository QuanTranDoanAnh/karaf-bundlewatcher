package vn.quantda.example.osgi.bundlewatcher;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationEvent;
import org.osgi.service.cm.ConfigurationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vn.quantda.example.osgi.bundlewatcher.collections.DictionaryAsMap;
import vn.quantda.example.osgi.bundlewatcher.properties.InterpolationHelper;
import vn.quantda.example.osgi.bundlewatcher.properties.TypedProperties;

public class ConfigInstaller implements ConfigurationListener {
	private final Logger LOG = LoggerFactory.getLogger(ConfigInstaller.class);
	private final BundleContext context;
    private final ConfigurationAdmin configAdmin;
    private final BundleWatcher bundleWatcher;
    private final Map<String, String> pidToFile = new HashMap<>();
    private ServiceRegistration<?> registration;

	public ConfigInstaller(BundleContext context, ConfigurationAdmin configAdmin, BundleWatcher bundleWatcher) {
		this.context = context;
        this.configAdmin = configAdmin;
        this.bundleWatcher = bundleWatcher;
	}

	public void init() {
		registration = this.context.registerService(
                new String[] {
                    ConfigurationListener.class.getName()
                },
                this, null);
        try
        {
            Configuration[] configs = configAdmin.listConfigurations(null);
            if (configs != null)
            {
                for (Configuration config : configs)
                {
                    Dictionary<String, ?> dict = config.getProperties();
                    String fileName = dict != null ? (String) dict.get( DirectoryWatcher.FILENAME ) : null;
                    if (fileName != null)
                    {
                        pidToFile.put(config.getPid(), fileName);
                    }
                }
            }
        }
        catch (Exception e)
        {
            LOG.info( "Unable to initialize configurations list", e );
        }
		
	}

	public void destroy() {
		registration.unregister();
	}

	@Override
	public void configurationEvent(final ConfigurationEvent configurationEvent)
    {
        if (System.getSecurityManager() != null)
        {
            AccessController.doPrivileged(
                    new PrivilegedAction<Void>()
                    {
                        public Void run()
                        {
                            doConfigurationEvent(configurationEvent);
                            return null;
                        }
                    }
            );
        }
        else
        {
            doConfigurationEvent(configurationEvent);
        }
    }

	
    public void doConfigurationEvent(ConfigurationEvent configurationEvent)
    {
        // Check if writing back configurations has been disabled.
        {
            if (!shouldSaveConfig())
            {
                return;
            }
        }

        if (configurationEvent.getType() == ConfigurationEvent.CM_UPDATED)
        {
            try
            {
                Configuration config = getConfigurationAdmin().getConfiguration(
                                            configurationEvent.getPid(),
                                            "?");
                Dictionary<String, ?> dict = config.getProperties();
                String fileName = dict != null ? (String) dict.get( DirectoryWatcher.FILENAME ) : null;
                File file = fileName != null ? fromConfigKey(fileName) : null;
                if( file != null && file.isFile() && canHandle( file ) ) {
                    pidToFile.put(config.getPid(), fileName);
                    TypedProperties props = new TypedProperties( bundleSubstitution() );
                    try (Reader r = new InputStreamReader(new FileInputStream(file), encoding()))
                    {
                        props.load(r);
                    }
                    // remove "removed" properties from the cfg file
                    List<String> propertiesToRemove = new ArrayList<>();
                    for( String key : props.keySet() )
                    {
                        if( dict.get(key) == null
                                && !Constants.SERVICE_PID.equals(key)
                                && !ConfigurationAdmin.SERVICE_FACTORYPID.equals(key)
                                && !DirectoryWatcher.FILENAME.equals(key) ) {
                            propertiesToRemove.add(key);
                        }
                    }
                    for( Enumeration<String> e  = dict.keys(); e.hasMoreElements(); )
                    {
                        String key = e.nextElement().toString();
                        if( !Constants.SERVICE_PID.equals(key)
                                && !ConfigurationAdmin.SERVICE_FACTORYPID.equals(key)
                                && !DirectoryWatcher.FILENAME.equals(key) )
                        {
                            Object val = dict.get( key );
                            props.put( key, val );
                        }
                    }
                    for( String key : propertiesToRemove )
                    {
                        props.remove(key);
                    }
                    try (Writer fw = new OutputStreamWriter(new FileOutputStream(file), encoding()))
                    {
                        props.save( fw );
                    }
                    // we're just writing out what's already loaded into ConfigAdmin, so
                    // update file checksum since lastModified gets updated when writing
                    bundleWatcher.updateChecksum(file);
                }
            }
            catch (Exception e)
            {
                LOG.info( "Unable to save configuration", e );
            }
        }

        if (configurationEvent.getType() == ConfigurationEvent.CM_DELETED)
        {
            try {
                String fileName = pidToFile.remove(configurationEvent.getPid());
                File file = fileName != null ? fromConfigKey(fileName) : null;
                if (file != null && file.isFile()) {
                    if (!file.delete()) {
                        throw new IOException("Unable to delete file: " + file);
                    }
                }
            }
            catch (Exception e)
            {
                LOG.info( "Unable to delete configuration file", e );
            }
        }
    }

    private boolean canHandle(File file) {
		return true;
	}

	private ConfigurationAdmin getConfigurationAdmin() {
    	return configAdmin;
	}

	boolean shouldSaveConfig()
    {
        String str = this.context.getProperty( DirectoryWatcher.ENABLE_CONFIG_SAVE );
        if (str == null)
        {
            str = this.context.getProperty( DirectoryWatcher.DISABLE_CONFIG_SAVE );
        }
        if (str != null)
        {
            return Boolean.valueOf(str);
        }
        return true;
    }

    String encoding()
    {
        String str = this.context.getProperty( DirectoryWatcher.CONFIG_ENCODING );
        return str != null ? str : "ISO-8859-1";
    }
    
    /**
     * Set the configuration based on the config file.
     *
     * @param f
     *            Configuration file
     * @return <code>true</code> if the configuration has been updated
     * @throws Exception
     */
    boolean setConfig(final File f) throws Exception
    {
        final Hashtable<String, Object> ht = new Hashtable<>();
        final InputStream in = new BufferedInputStream(new FileInputStream(f));
        try
        {
            in.mark(1);
            boolean isXml = in.read() == '<';
            in.reset();
            if (isXml) {
                final Properties p = new Properties();
                p.loadFromXML(in);
                Map<String, String> strMap = new HashMap<>();
                for (Object k : p.keySet()) {
                    strMap.put(k.toString(), p.getProperty(k.toString()));
                }
                InterpolationHelper.performSubstitution(strMap, context);
                ht.putAll(strMap);
            } else {
                TypedProperties p = new TypedProperties( bundleSubstitution() );
                try (Reader r = new InputStreamReader(in, encoding()))
                {
                    p.load(r);
                }
                for (String k : p.keySet()) {
                    ht.put(k, p.get(k));
                }
            }
        }
        finally
        {
            in.close();
        }

        String pid[] = parsePid(f.getName());
        Configuration config = getConfiguration(toConfigKey(f), pid[0], pid[1]);

        Dictionary<String, Object> props = config.getProperties();
        Hashtable<String, Object> old = props != null ? new Hashtable<String, Object>(new DictionaryAsMap<>(props)) : null;
        if (old != null) {
        	old.remove( DirectoryWatcher.FILENAME );
        	old.remove( Constants.SERVICE_PID );
        	old.remove( ConfigurationAdmin.SERVICE_FACTORYPID );
        }

        if( !ht.equals( old ) )
        {
            ht.put(DirectoryWatcher.FILENAME, toConfigKey(f));
            if (old == null) {
                LOG.info("Creating configuration from " + pid[0]
                        + (pid[1] == null ? "" : "-" + pid[1]) + ".cfg");
            } else {
            	LOG.info("Updating configuration from " + pid[0]
                        + (pid[1] == null ? "" : "-" + pid[1]) + ".cfg");
            }
            config.update(ht);
            return true;
        }
        else
        {
            return false;
        }
    }

    /**
     * Remove the configuration.
     *
     * @param f
     *            File where the configuration in was defined.
     * @return <code>true</code>
     * @throws Exception
     */
    boolean deleteConfig(File f) throws Exception
    {
        String pid[] = parsePid(f.getName());
        LOG.info("Deleting configuration from " + pid[0]
                + (pid[1] == null ? "" : "-" + pid[1]) + ".cfg");
        Configuration config = getConfiguration(toConfigKey(f), pid[0], pid[1]);
        config.delete();
        return true;
    }

    String toConfigKey(File f) {
        return f.getAbsoluteFile().toURI().toString();
    }

    File fromConfigKey(String key) {
        return new File(URI.create(key));
    }

    String[] parsePid(String path)
    {
        String pid = path.substring(0, path.lastIndexOf('.'));
        int n = pid.indexOf('-');
        if (n > 0)
        {
            String factoryPid = pid.substring(n + 1);
            pid = pid.substring(0, n);
            return new String[]
                {
                    pid, factoryPid
                };
        }
        else
        {
            return new String[]
                {
                    pid, null
                };
        }
    }

    Configuration getConfiguration(String fileName, String pid, String factoryPid)
        throws Exception
    {
        Configuration oldConfiguration = findExistingConfiguration(fileName);
        if (oldConfiguration != null)
        {
            return oldConfiguration;
        }
        else
        {
            Configuration newConfiguration;
            if (factoryPid != null)
            {
                newConfiguration = getConfigurationAdmin().createFactoryConfiguration(pid, "?");
            }
            else
            {
                newConfiguration = getConfigurationAdmin().getConfiguration(pid, "?");
            }
            return newConfiguration;
        }
    }

    Configuration findExistingConfiguration(String fileName) throws Exception
    {
        String filter = "(" + DirectoryWatcher.FILENAME + "=" + escapeFilterValue(fileName) + ")";
        Configuration[] configurations = getConfigurationAdmin().listConfigurations(filter);
        if (configurations != null && configurations.length > 0)
        {
            return configurations[0];
        }
        else
        {
            return null;
        }
    }

    TypedProperties.SubstitutionCallback bundleSubstitution() {
        final InterpolationHelper.SubstitutionCallback cb = new InterpolationHelper.BundleContextSubstitutionCallback(context);
        return new TypedProperties.SubstitutionCallback() {
            @Override
            public String getValue(String name, String key, String value) {
                return cb.getValue(value);
            }
        };
    }

    private String escapeFilterValue(String s) {
        return s.replaceAll("[(]", "\\\\(").
                replaceAll("[)]", "\\\\)").
                replaceAll("[=]", "\\\\=").
                replaceAll("[\\*]", "\\\\*");
    }

}
