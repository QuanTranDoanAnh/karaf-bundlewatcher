package vn.quantda.example.osgi.bundlewatcher.remoteupgrader;

import java.net.URL;
import java.util.Map;

import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RemoteObrWatcher extends Thread {

	public final static String POLL = "quantda.bundlewatcher.remoteobrwatcher.poll";
	
	private final Logger LOG = LoggerFactory.getLogger(RemoteObrWatcher.class);
	
	BundleContext context;
	RepositoryAdmin repoAdmin;
	URL remoteObrUrl;
	long poll = 10000;
	
	public RemoteObrWatcher(String remoteObrUrlString, BundleContext context) {
		this.context = context;
		try {
			remoteObrUrl = new URL(remoteObrUrlString);
			this.repoAdmin.addRepository(remoteObrUrl);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			LOG.error("Error happened", e);
		}
	}

	public void setRepoAdmin(RepositoryAdmin repoAdmin) {
		this.repoAdmin = repoAdmin;
	}


	@Override
	public void run() {
		// TODO Auto-generated method stub
		super.run();
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
}
