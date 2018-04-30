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
package vn.quantda.example.osgi.bundlewatcher;

import java.io.File;
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

import org.apache.karaf.cave.server.api.CaveRepositoryService;
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


public class BundleWatcher implements BundleActivator, ServiceTrackerCustomizer<CaveRepositoryService,CaveRepositoryService> {
	private final Logger LOG = LoggerFactory.getLogger(BundleWatcher.class);
	Runnable cmSupport;
	BundleContext context;
	final Map<String, DirectoryWatcher> watchers = new HashMap<String, DirectoryWatcher>();
	final ReadWriteLock lock = new ReentrantReadWriteLock();
	volatile boolean stopped;
	
	private ServiceTracker<CaveRepositoryService, CaveRepositoryService> caveRepoServiceTracker;
	
    public void start(BundleContext context) {
        this.context = context;
        LOG.info("Starting the bundle " + this.context.getBundle(0).getSymbolicName());
        this.lock.writeLock().lock();
        
        try {
        	Hashtable<String, Object> props = new Hashtable<>();
        	props.put("url.handler.protocol", "jardir");
        	
        	String flt = "(" + Constants.OBJECTCLASS + "=" + CaveRepositoryService.class.getName() + ")";
        	caveRepoServiceTracker = new ServiceTracker<CaveRepositoryService, CaveRepositoryService>(context, FrameworkUtil.createFilter(flt), this);
        	caveRepoServiceTracker.open();
        	
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

            set(ht, DirectoryWatcher.POLL);
            set(ht, DirectoryWatcher.DIR);
            set(ht, DirectoryWatcher.LOG_LEVEL);
            set(ht, DirectoryWatcher.LOG_DEFAULT);
            set(ht, DirectoryWatcher.FILTER);
            set(ht, DirectoryWatcher.TMPDIR);
            set(ht, DirectoryWatcher.DISABLE_CONFIG_SAVE);
            set(ht, DirectoryWatcher.ENABLE_CONFIG_SAVE);
            set(ht, DirectoryWatcher.CONFIG_ENCODING);
            set(ht, DirectoryWatcher.DISABLE_NIO2);
            set(ht, DirectoryWatcher.SUBDIR_MODE);

            // check if dir is an array of dirs
            String dirs = ht.get(DirectoryWatcher.DIR);
            if (dirs != null && dirs.indexOf(',') != -1)
            {
                StringTokenizer st = new StringTokenizer(dirs, ",");
                int index = 0;
                while (st.hasMoreTokens())
                {
                    final String dir = st.nextToken().trim();
                    ht.put(DirectoryWatcher.DIR, dir);

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
    	LOG.info("Stopping the bundle  " + this.context.getBundle().getSymbolicName());
        lock.writeLock().lock();
        try
        {
            List<DirectoryWatcher> toClose = new ArrayList<DirectoryWatcher>();
            synchronized (watchers)
            {
                toClose.addAll(watchers.values());
                watchers.clear();
            }
            for (DirectoryWatcher aToClose : toClose)
            {
                try
                {
                    aToClose.close();
                }
                catch (Exception e)
                {
                    // Ignore
                }
            }
            if (cmSupport != null)
            {
                cmSupport.run();
            }
        }
        finally
        {
            stopped = true;
            lock.writeLock().unlock();
        }
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
    
    public void updated(String pid, Map<String, String> properties) {
    	DirectoryWatcher watcher;
        synchronized (watchers)
        {
            watcher = watchers.get(pid);
            if (watcher != null && watcher.getProperties().equals(properties))
            {
                return;
            }
        }
        if (watcher != null)
        {
            watcher.close();
        }
        watcher = new DirectoryWatcher(this, properties, context);
        CaveRepositoryService caveRepositoryService = checkCaveRepoService();
        watcher.setCaveRepoService(caveRepositoryService);
        watcher.setDaemon(true);
        synchronized (watchers)
        {
            watchers.put(pid, watcher);
        }
        watcher.start();
		
	}

	public void deleted(String pid) {
		DirectoryWatcher watcher;
        synchronized (watchers)
        {
            watcher = watchers.remove(pid);
        }
        if (watcher != null)
        {
            watcher.close();
        }
		
	}
	
	public void updateChecksum(File file) {
		List<DirectoryWatcher> toUpdate = new ArrayList<DirectoryWatcher>();
        synchronized (watchers)
        {
            toUpdate.addAll(watchers.values());
        }
        for (DirectoryWatcher watcher : toUpdate)
        {
            watcher.scanner.updateChecksum(file);
        }
		
	}
	
	private CaveRepositoryService checkCaveRepoService() {
		CaveRepositoryService caveRepoService = this.caveRepoServiceTracker.getService();
		LOG.info("Can get CaveRepositoryService:" + (caveRepoService != null));
		return caveRepoService;
	}

	@Override
	public CaveRepositoryService addingService(ServiceReference<CaveRepositoryService> serviceReference) {
		CaveRepositoryService caveRepoService = this.context.getService(serviceReference);
		setCaveRepoService(caveRepoService);
		return caveRepoService;
	}

	@Override
	public void modifiedService(ServiceReference<CaveRepositoryService> serviceReference, CaveRepositoryService service) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void removedService(ServiceReference<CaveRepositoryService> serviceReference, CaveRepositoryService service) {
		// TODO Auto-generated method stub
		
	}
	
	private void setCaveRepoService(CaveRepositoryService caveRepoService) {
        List<DirectoryWatcher> toNotify = new ArrayList<DirectoryWatcher>();
        synchronized (watchers)
        {
            toNotify.addAll(watchers.values());
        }
        for (DirectoryWatcher dir : toNotify)
        {
            dir.setCaveRepoService(caveRepoService);
        }
		
	}
    
    private class ConfigAdminSupport implements Runnable {

    	private Tracker tracker;
        private ServiceRegistration<?> registration;
        
		public ConfigAdminSupport(BundleContext context, BundleWatcher bundleWatcher) {
			tracker = new Tracker(context, bundleWatcher);
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

			private final BundleWatcher bundleWatcher;
            private final Set<String> configs = Collections.synchronizedSet(new HashSet<String>());
            private final Map<Long, ConfigInstaller> configInstallers = new HashMap<Long, ConfigInstaller>();
            
            private Tracker(BundleContext bundleContext, BundleWatcher bundleWatcher)
            {
                super(bundleContext, ConfigurationAdmin.class.getName(), null);
                this.bundleWatcher = bundleWatcher;
            }
            
			@Override
			public String getName() {
				return "vn.quantda.osgi.bundlewatcher";
			}

			@Override
			public void updated(String pid, Dictionary<String, ?> properties) throws ConfigurationException {
				configs.add(pid);
				Map<String, String> props = new HashMap<String, String>();
                for (Enumeration<String> e = properties.keys(); e.hasMoreElements();) {
                    String k = e.nextElement();
                    props.put(k, properties.get(k).toString());
                }
                bundleWatcher.updated(pid, props);
				
			}

			@Override
			public void deleted(String pid) {
				configs.remove(pid);
				bundleWatcher.deleted(pid);
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
                    long id = (Long) serviceReference.getProperty(Constants.SERVICE_ID);
                    ConfigInstaller configInstaller = new ConfigInstaller(this.context, cm, bundleWatcher);
                    configInstaller.init();
                    configInstallers.put(id, configInstaller);
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
                        bundleWatcher.deleted(s);
                        iterator.remove();
                    }
                    long id = (Long) serviceReference.getProperty(Constants.SERVICE_ID);
                    ConfigInstaller configInstaller = configInstallers.remove(id);
                    if (configInstaller != null)
                    {
                        configInstaller.destroy();
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