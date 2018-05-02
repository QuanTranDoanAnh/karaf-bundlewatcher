/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package vn.quantda.example.osgi.bundlewatcher.remoteupgrader;


import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import vn.quantda.example.osgi.bundlewatcher.remoteupgrader.exceptions.InvalidArgumentException;

public class RemoteBundleUpgrader implements BundleActivator, ServiceTrackerCustomizer<RepositoryAdmin, RepositoryAdmin> {

	private static final Logger LOG = LoggerFactory.getLogger(RemoteBundleUpgrader.class);
	
	RepositoryAdmin repoAdmin;
	BundleContext context;
	final Map<String, RemoteObrWatcher> watchers = new HashMap<String, RemoteObrWatcher>();
	final ReadWriteLock lock = new ReentrantReadWriteLock();
	volatile boolean stopped;
	ConfigAdminSupport cmSupport;
	ServiceTracker<RepositoryAdmin, RepositoryAdmin> repoAdminTracker;

	public void start(BundleContext context) {
		LOG.info("Starting the bundle " + context.getBundle(0).getSymbolicName());
		this.context = context;
		this.lock.writeLock().lock();
        try {
        	String flt = "(" + Constants.OBJECTCLASS + "=" + RepositoryAdmin.class.getName() + ")";
            this.repoAdminTracker = new ServiceTracker<>(context, FrameworkUtil.createFilter(flt), this);
            repoAdminTracker.open();
            repoAdmin = repoAdminTracker.getService();
            try
            {
                cmSupport = new ConfigAdminSupport(context, this);
            }
            catch (NoClassDefFoundError e)
            {
                LOG.info("ConfigAdmin is not available, some features will be disabled", e);
            }
        	
        	// Created the initial configuration
            Hashtable<String, String> ht = new Hashtable<String, String>();

            set(ht, RemoteObrWatcher.POLL);
            set(ht, RemoteObrWatcher.REMOTE_OBR_URL);

            // check if dir is an array of dirs
            String urlStrings = ht.get(RemoteObrWatcher.REMOTE_OBR_URL);
            if (urlStrings != null && urlStrings.indexOf(',') != -1)
            {
                StringTokenizer st = new StringTokenizer(urlStrings, ",");
                int index = 0;
                while (st.hasMoreTokens())
                {
                    final String urlString = st.nextToken().trim();
                    ht.put(RemoteObrWatcher.REMOTE_OBR_URL, urlString);

                    String name = "initial";
                    if (index > 0) name = name + index;
                    updated(name, new Hashtable<String, String>(ht));

                    index++;
                }
            }
            else
            {
                updated("initial", ht);
            }
        } catch (InvalidSyntaxException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
            // now notify all the directory watchers to proceed
            // We need this to avoid race conditions observed in FELIX-2791
        	this.lock.writeLock().unlock();
        }
    }
	
    public void stop(BundleContext context) {
        System.out.println("Stopping the bundle");
    }

	@Override
	public RepositoryAdmin addingService(ServiceReference<RepositoryAdmin> reference) {
		this.repoAdmin = this.context.getService(reference);
		updateRepoAdminToWatchers(this.repoAdmin);
		return this.repoAdmin;
	}
	
	public void setRepoAdmin(RepositoryAdmin repoAdmin) {
		this.repoAdmin = repoAdmin;
	}

	public void updated(String pid, Map<String, String> properties) {
		RemoteObrWatcher watcher;
		
		synchronized (watchers) {
			watcher = watchers.get(pid);
            if (watcher != null && watcher.getProperties().equals(properties))
            {
                return;
            }
            if (watcher != null && watcher.getRemoteObr() == null ) {
            	String remoteObrUrl = properties.get(RemoteObrWatcher.REMOTE_OBR_URL);
            	if (remoteObrUrl != null) {
            		try {
						watcher.setRemoteObr(remoteObrUrl);
					} catch (MalformedURLException e) {
						//ignore
					}
            	}
            }
		}
		try {
			if (watcher != null)
	        {
	            watcher.close();
	        }
			watcher = new RemoteObrWatcher(this, properties, context);
			RepositoryAdmin repoAdmin = checkRepoAdminService();
			watcher.setRepoAdmin(repoAdmin);
			
			watcher.setDaemon(true);
			synchronized (watchers)
	        {
	            watchers.put(pid, watcher);
	        }
	        watcher.start();
		} catch (InvalidArgumentException e) {
			LOG.error("Error happenned:", e.getInnerException());
		}
		
	}

	public void deleted(String pid) {
		RemoteObrWatcher watcher;
        synchronized (watchers)
        {
            watcher = watchers.remove(pid);
        }
        if (watcher != null)
        {
            watcher.close();
        }
		
	}

	@Override
	public void modifiedService(ServiceReference<RepositoryAdmin> reference, RepositoryAdmin service) {
		this.repoAdmin = this.context.getService(reference);
		updateRepoAdminToWatchers(this.repoAdmin);
	}

	@Override
	public void removedService(ServiceReference<RepositoryAdmin> reference, RepositoryAdmin service) {
		
		this.repoAdmin = null;
		
	}
	
	// Adapted for FELIX-524
    private void set(Hashtable<String, String> ht, String key)
    {
        String o = context.getProperty(key);
        if (o == null)
        {
           o = System.getProperty(key.toUpperCase().replace('.', '_'));
            if (o == null)
            {
                return;
            }
        }
        ht.put(key, o);
    }
    
    private void updateRepoAdminToWatchers(RepositoryAdmin repoAdmin) {
    	List<RemoteObrWatcher> toUpdate = new ArrayList<RemoteObrWatcher>();
        synchronized (watchers)
        {
            toUpdate.addAll(watchers.values());
        }
        for (RemoteObrWatcher watcher : toUpdate)
        {
            watcher.setRepoAdmin(repoAdmin);
        }
		
	}
    
    private RepositoryAdmin checkRepoAdminService() {
    	if (this.repoAdmin == null) {
    		this.repoAdmin = this.repoAdminTracker.getService();
    	}
    	return this.repoAdmin;
    }
	
	private class ConfigAdminSupport implements Runnable {

    	private Tracker tracker;
        private ServiceRegistration<?> registration;
        
		public ConfigAdminSupport(BundleContext context, RemoteBundleUpgrader remoteBundleUpgrader) {
			tracker = new Tracker(context, remoteBundleUpgrader);
            Hashtable<String, Object> props = new Hashtable<String, Object>();
            props.put(Constants.SERVICE_PID, tracker.getName());
            registration = context.registerService(ManagedServiceFactory.class.getName(), tracker, props);
            tracker.open();
		}

		@Override
		public void run() {
			tracker.close();
            registration.unregister();
		}
		
		private class Tracker extends ServiceTracker<ConfigurationAdmin, ConfigurationAdmin> implements ManagedServiceFactory {

			private final RemoteBundleUpgrader remoteBundleUpgrader;
            private final Set<String> configs = Collections.synchronizedSet(new HashSet<String>());
            
            private Tracker(BundleContext bundleContext, RemoteBundleUpgrader remoteBundleUpgrader)
            {
                super(bundleContext, ConfigurationAdmin.class.getName(), null);
                this.remoteBundleUpgrader = remoteBundleUpgrader;
            }
            
			@Override
			public String getName() {
				return "vn.quantda.osgi.bundlewatcher.remoteupgrader";
			}

			@Override
			public void updated(String pid, Dictionary<String, ?> properties) throws ConfigurationException {
				configs.add(pid);
				Map<String, String> props = new HashMap<String, String>();
                for (Enumeration<String> e = properties.keys(); e.hasMoreElements();) {
                    String k = e.nextElement();
                    props.put(k, properties.get(k).toString());
                }
                remoteBundleUpgrader.updated(pid, props);
				
			}

			@Override
			public void deleted(String pid) {
				configs.remove(pid);
				remoteBundleUpgrader.deleted(pid);
			}
			
			@Override
			public ConfigurationAdmin addingService(ServiceReference<ConfigurationAdmin> serviceReference) {
				lock.writeLock().lock();
                try
                {
                    if (stopped) {
                        return null;
                    }
                    ConfigurationAdmin cm = super.addingService(serviceReference);
                    return cm;
                }
                finally
                {
                    lock.writeLock().unlock();
                }
			}
			
			@Override
			public void removedService(ServiceReference<ConfigurationAdmin> serviceReference, ConfigurationAdmin service) {
				lock.writeLock().lock();
                try
                {
                    if (stopped) {
                        return;
                    }
                    Iterator<String> iterator = configs.iterator();
                    while (iterator.hasNext())
                    {
                        String s = (String) iterator.next();
                        remoteBundleUpgrader.deleted(s);
                        iterator.remove();
                    }
                    super.removedService(serviceReference, service);
                }
                finally
                {
                    lock.writeLock().unlock();
                }
			}
			
		}
    	
    }

}