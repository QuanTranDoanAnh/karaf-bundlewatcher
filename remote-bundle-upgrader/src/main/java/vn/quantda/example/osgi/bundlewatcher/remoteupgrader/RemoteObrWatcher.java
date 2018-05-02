package vn.quantda.example.osgi.bundlewatcher.remoteupgrader;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.apache.felix.bundlerepository.Reason;
import org.apache.felix.bundlerepository.Repository;
import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.apache.felix.bundlerepository.Resolver;
import org.apache.felix.bundlerepository.Resource;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vn.quantda.example.osgi.bundlewatcher.remoteupgrader.exceptions.InvalidArgumentException;


public class RemoteObrWatcher extends Thread {

	public final static String POLL = "quantda.bundlewatcher.remoteobrwatcher.poll";
	public final static String REMOTE_OBR_URL = "quantda.bundlewatcher.remoteobrwatcher.url";
	
	private final Logger LOG = LoggerFactory.getLogger(RemoteObrWatcher.class);
	
	RemoteBundleUpgrader upgrader;
	BundleContext context;
	Map<String, String> properties;
	RepositoryAdmin repoAdmin;
	URL remoteObr;
	long poll;
	
	public RemoteObrWatcher(RemoteBundleUpgrader upgrader, Map<String, String> properties, BundleContext context) throws InvalidArgumentException {
		this.upgrader = upgrader;
		this.context = context;
		this.properties = properties;
		try {
			poll = getLong(properties, POLL, 10000);
			LOG.info("Poll time: "+poll);
			String urlString = properties.get(REMOTE_OBR_URL);
			LOG.info("Remote OBR URL: " + urlString);
			if (urlString != null) {
				remoteObr = new URL(urlString);
			}
		} catch(MalformedURLException e) {
			throw new InvalidArgumentException(e);
		}
		
	}

	public void setRepoAdmin(RepositoryAdmin repoAdmin) {
		this.repoAdmin = repoAdmin;
	}
	
	public void setRemoteObr(String url) throws MalformedURLException {
		this.remoteObr = new URL(url);
	}


	@Override
	public void run() {
		if (this.repoAdmin != null && this.remoteObr != null) {
        	LOG.info("Already reached RepositoryAdmin");
        	Resource[] resourcesToUpgrade = null;
			try {
				// Refresh Url
				this.repoAdmin.addRepository(remoteObr);
				resourcesToUpgrade = getListResourcesToUpgrade();
				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				LOG.error("Error happened", e);
			}
			if (resourcesToUpgrade != null && resourcesToUpgrade.length > 0) {
				doUpgradeResources(resourcesToUpgrade);
			}
			
        	
        } else {
        	LOG.error("Cannot reach Repository Admin");
        }
	}

	private void doUpgradeResources(Resource[] resourcesToUpgrade) {
		LOG.info("Doing upgrading bundles");
		Resolver resolver = this.repoAdmin.resolver();
		for(Resource resource : resourcesToUpgrade) {
			if (resource != null) {
				resolver.add(resource);
			}
		}
		if ((resolver.getAddedResources() != null) &&
                (resolver.getAddedResources().length > 0)) {
			if (resolver.resolve(Resolver.NO_OPTIONAL_RESOURCES)) {
				LOG.info("Deploying Target resource(s):");
				Resource[] resources = resolver.getAddedResources();
				resources = resolver.getRequiredResources();
				for (int resIdx = 0; (resources != null) && (resIdx < resources.length); resIdx++) {
					LOG.info("   " + getResourceId(resources[resIdx])
                            + " (" + resources[resIdx].getVersion() + ")");
                }
				try {
                    resolver.deploy(Resolver.START);
                } catch (IllegalStateException ex) {
                    LOG.error("Error happened:", ex);
                }
			} else {
                Reason[] reqs = resolver.getUnsatisfiedRequirements();
                if ((reqs != null) && (reqs.length > 0)) {
                	LOG.info("Unsatisfied requirement(s):");
                    for (Reason req : reqs) {
                    	LOG.info("   " + req.getRequirement().getFilter());
                    	LOG.info("      " + getResourceId(req.getResource()));
                    }
                } else {
                	LOG.info("Could not resolve targets.");
                }
            }
			
		}
		
	}

	private String getResourceId(Resource resource) {
		return resource.getPresentationName() != null ? resource.getPresentationName() : resource.getSymbolicName();
	}

	private Resource[] getListResourcesToUpgrade() {
		LOG.info("Getting list of Resources to upgrade");
		Resource[] resourcesToUpgrade = new Resource[] {};
		
		Map<String, Resource> resourcesFromRemoteObrs = new HashMap<>();
		//fetch remote OBR for resources
		Repository[] repos = repoAdmin.listRepositories();
		for(Repository repo : repos) {
			LOG.info("Repository: " + repo.getName() + " - URL: " + repo.getURI());
			Resource[] repoResources = repo.getResources();
			for(Resource resource : repoResources) {
				LOG.info("Resource: " + resource.getId() + " - Presentation Name: " + resource.getPresentationName() + " - Symbolic Name: " + resource.getSymbolicName() + " - Version: " + resource.getVersion().toString());
				if (! resourcesFromRemoteObrs.containsKey(resource.getSymbolicName())) {
					LOG.info("This resource does not exists in resourcesFromRemoteObrs.");
					resourcesFromRemoteObrs.put(resource.getSymbolicName(), resource);
				} else {
					// only put latest version
					Resource currentResource = resourcesFromRemoteObrs.get(resource.getSymbolicName());
					if (currentResource == null) {
						LOG.info("This resource does not exists in resourcesFromRemoteObrs.");
						resourcesFromRemoteObrs.put(resource.getSymbolicName(), resource);
					} else {
						// compare version
						if (resource.getVersion().compareTo(currentResource.getVersion()) > 0) {
							LOG.info("This resource has an existing but lower version copy in resourcesFromRemoteObrs.");
							resourcesFromRemoteObrs.put(currentResource.getSymbolicName(), resource);
						} else {
							LOG.info("This resource has an existing copy in resourcesFromRemoteObrs. Won't update");
						}
					}
				}
			}
		}
		
		// Get list of current installed bundles
		Bundle[] installedBundles = this.context.getBundles();
		Map<String, Resource> tmpToBeUpgradedResource = new HashMap<>();
		for (Bundle bundle : installedBundles) {
			String bundleSymName = bundle.getSymbolicName();
			
			if (resourcesFromRemoteObrs.containsKey(bundleSymName)) {
				LOG.info("Installed bundle to be checked:" + bundle.getSymbolicName() + " - Version: " + bundle.getVersion().toString());
				Resource checkedResource = resourcesFromRemoteObrs.get(bundleSymName);
				if (checkedResource.getVersion().compareTo(bundle.getVersion()) > 0) {
					LOG.info("This bundle has existing higher versions from remote Obr.");
					tmpToBeUpgradedResource.put(bundleSymName, checkedResource);
				} else {
					LOG.info("This bundle has existing but lower versions from remote Obr.");
				}
			} 
			
		}
		if (tmpToBeUpgradedResource.size() > 0) {
			resourcesToUpgrade = tmpToBeUpgradedResource.values().toArray(new Resource[0]);
		}
		LOG.info("List of Resources to upgrade has " + resourcesToUpgrade.length + "items.");
		return resourcesToUpgrade;
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

	public Object getProperties() {
		return this.properties;
	}
	
	public void close() {
		interrupt();
        try
        {
            //scanner.close();
        	
        }
        catch (Exception e)
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

	public Object getRemoteObr() {
		return this.remoteObr;
	}
}
