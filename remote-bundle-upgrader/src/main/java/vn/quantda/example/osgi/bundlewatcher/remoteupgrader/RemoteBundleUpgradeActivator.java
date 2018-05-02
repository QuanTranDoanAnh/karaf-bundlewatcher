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


import java.net.URL;

import org.apache.felix.bundlerepository.Repository;
import org.apache.felix.bundlerepository.RepositoryAdmin;
import org.apache.felix.bundlerepository.Resource;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RemoteBundleUpgradeActivator implements BundleActivator, ServiceTrackerCustomizer<RepositoryAdmin, RepositoryAdmin> {

	private static final Logger LOG = LoggerFactory.getLogger(RemoteBundleUpgradeActivator.class);
	
	RepositoryAdmin repoAdmin;
	
	BundleContext context;

	public void setRepoAdmin(RepositoryAdmin repoAdmin) {
		this.repoAdmin = repoAdmin;
	}

	public void start(BundleContext context) {
		this.context = context;
        System.out.println("Starting the bundle");
        try {
        	String flt = "(" + Constants.OBJECTCLASS + "=" + RepositoryAdmin.class.getName() + ")";
            ServiceTracker<RepositoryAdmin, RepositoryAdmin> repoAdminTracker = new ServiceTracker<>(context, FrameworkUtil.createFilter(flt), this);
            repoAdminTracker.open();
            repoAdmin = repoAdminTracker.getService();
	        if (repoAdmin != null) {
	        	LOG.info("Already reached RepositoryAdmin");
	        	// Refresh Url
	        	URL remoteRepoUrl;
				try {
					remoteRepoUrl = new URL("http://localhost:9090/cave/http/test-repo-repository.xml");
					repoAdmin.addRepository(remoteRepoUrl);
					// fetch current list of repositories
					Repository[] repos = repoAdmin.listRepositories();
					for(Repository repo : repos) {
						LOG.info("Repository: " + repo.getName() + " - URL: " + repo.getURI());
						Resource[] repoResources = repo.getResources();
						for(Resource resource : repoResources) {
							LOG.info("Resource: " + resource.getId() + " - Presentation Name: " + resource.getPresentationName() + " - Symbolic Name: " + resource.getSymbolicName() + " - Version: " + resource.getVersion().toString());
						}
					}
				} catch (Exception e) {
					// TODO Auto-generated catch block
					LOG.error("Error happened", e);
				}
	        	
	        } else {
	        	LOG.error("Cannot reach Repository Admin");
	        }
        } catch (Exception e) {
        	
        }
    }

    public void stop(BundleContext context) {
        System.out.println("Stopping the bundle");
    }

	@Override
	public RepositoryAdmin addingService(ServiceReference<RepositoryAdmin> reference) {
		this.repoAdmin = this.context.getService(reference);
		return this.repoAdmin;
	}

	@Override
	public void modifiedService(ServiceReference<RepositoryAdmin> reference, RepositoryAdmin service) {
		this.repoAdmin = this.context.getService(reference);
		
	}

	@Override
	public void removedService(ServiceReference<RepositoryAdmin> reference, RepositoryAdmin service) {
		this.repoAdmin = null;
		
	}

}