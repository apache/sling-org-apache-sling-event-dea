/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.event.dea.impl;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.observation.ExternalResourceChangeListener;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.event.dea.DEAConstants;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.service.event.EventConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This event handler distributes events from other application nodes in
 * the cluster on the current instance.
 * <p>
 * It's listening for resource added events in the resource tree storing the
 * distributed events. If a new resource is added, the resource is read,
 * converted to an event and then send using the local event admin.
 * <p>
 */
public class DistributedEventSender
    implements ResourceChangeListener, ExternalResourceChangeListener {

    /** Default logger. */
    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /** Is the background task still running? */
    private volatile boolean running;

    /** A local queue for serializing the event processing. */
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();

    private final ResourceResolverFactory resourceResolverFactory;

    private final EventAdmin eventAdmin;

    private final String ownRootPathWithSlash;

    private volatile ServiceRegistration<ResourceChangeListener> serviceRegistration;
    
    private AtomicInteger postedEventCounter = new AtomicInteger();

    public DistributedEventSender(final BundleContext bundleContext,
            final String rootPath,
            final String ownRootPath,
            final ResourceResolverFactory rrFactory,
            final EventAdmin eventAdmin) {
        this.eventAdmin = eventAdmin;
        this.resourceResolverFactory = rrFactory;
        this.ownRootPathWithSlash = ownRootPath + "/";

        this.running = true;
        final Thread backgroundThread = new Thread(new Runnable() {
            @Override
            public void run() {
                // create service registration properties
                final Dictionary<String, Object> props = new Hashtable<String, Object>();
                props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");

                // listen for all resource added OSGi events in the DEA area
                props.put(ResourceChangeListener.CHANGES, ResourceChange.ChangeType.ADDED.name());
                props.put(ResourceChangeListener.PATHS, rootPath);

                final ServiceRegistration<ResourceChangeListener> reg =
                        bundleContext.registerService(ResourceChangeListener.class,
                                       DistributedEventSender.this, props);

                DistributedEventSender.this.serviceRegistration = reg;

                try {
                    runInBackground();
                } catch (Throwable t) { //NOSONAR
                    logger.error("Background thread stopped with exception: " + t.getMessage(), t);
                    running = false;
                }
            }
        });
        backgroundThread.start();
    }

    /**
     * Deactivate this component.
     */
    public void stop() {
        if ( this.serviceRegistration != null ) {
            this.serviceRegistration.unregister();
            this.serviceRegistration = null;
        }
        // stop background threads by putting empty objects into the queue
        this.running = false;
        try {
            this.queue.put("");
        } catch (final InterruptedException e) {
            this.ignoreException(e);
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Read an event from the resource
     * @return The event object or <code>null</code>
     */
    private Event readEvent(final Resource eventResource) {
        try {
            final ValueMap vm = ResourceHelper.getValueMap(eventResource);
            final String topic = vm.get(EventConstants.EVENT_TOPIC, String.class);
            if ( topic == null ) {
                // no topic should never happen as we check the resource type before
                logger.error("Unable to read distributed event from " + eventResource.getPath() + " : no topic property available.");
            } else {
                final Map<String, Object> properties = ResourceHelper.cloneValueMap(vm);
                // only send event if there are no read errors, otherwise discard it
                @SuppressWarnings("unchecked")
                final List<Exception> readErrorList = (List<Exception>) properties.remove(ResourceHelper.PROPERTY_MARKER_READ_ERROR_LIST);
                if ( readErrorList == null ) {
                    properties.remove(EventConstants.EVENT_TOPIC);
                    properties.remove(DEAConstants.PROPERTY_DISTRIBUTE);
                    final Object oldRT = properties.remove("event.dea." + ResourceResolver.PROPERTY_RESOURCE_TYPE);
                    if ( oldRT != null ) {
                        properties.put(ResourceResolver.PROPERTY_RESOURCE_TYPE, oldRT);
                    } else {
                        properties.remove(ResourceResolver.PROPERTY_RESOURCE_TYPE);
                    }
                    try {
                        final Event event = new Event(topic, properties);
                        return event;
                    } catch (final IllegalArgumentException iae) {
                        // this exception occurs if the topic is not correct (it should never happen,
                        // but you never know)
                        logger.error("Unable to read event: " + iae.getMessage(), iae);
                    }
                } else {
                    for(final Exception e : readErrorList) {
                        logger.warn("Unable to read distributed event from " + eventResource.getPath(), e);
                    }
                }
            }
        } catch (final InstantiationException ie) {
            // something happened with the resource in the meantime
            this.ignoreException(ie);
        }
        return null;
    }

    /**
     * Background thread
     */
    private void runInBackground() {
        while ( this.running ) {
            // so let's wait/get the next event from the queue
            String path = null;
            try {
                path = this.queue.take();
            } catch (final InterruptedException e) {
                this.ignoreException(e);
                Thread.currentThread().interrupt();
                this.running = false;
            }
            if ( path != null && path.length() > 0 && this.running ) {
                ResourceResolver resolver = null;
                try {
                    resolver = this.resourceResolverFactory.getServiceResourceResolver(null);
                    final Resource eventResource = resolver.getResource(path);
                    if (eventResource == null) {
                        this.logger.warn("runInBackground : resource not found at "+path);
                    } else if ( DistributedEventAdminImpl.RESOURCE_TYPE_EVENT.equals(eventResource.getResourceType())) {
                        final Event e = this.readEvent(eventResource);
                        if ( e != null ) {
                            // we check event admin as processing is async
                            final EventAdmin localEA = this.eventAdmin;
                            if ( localEA != null ) {
                                localEA.postEvent(e);
                                postedEventCounter.incrementAndGet();
                            } else {
                                this.logger.error("Unable to post event as no event admin is available.");
                            }
                        }
                    }
                } catch (final LoginException ex) {
                    this.logger.error("Exception during creation of resource resolver.", ex);
                } finally {
                    if ( resolver != null ) {
                        resolver.close();
                    }
                }
            }
        }
    }

    /**
     * @see org.apache.sling.api.resource.observation.ResourceChangeListener#onChange(java.util.List)
     */
    @Override
    public void onChange(final List<ResourceChange> changes) {
        for(final ResourceChange c : changes) {
            if ( !c.getPath().startsWith(this.ownRootPathWithSlash) ) {
                try {
                    this.queue.put(c.getPath());
                } catch (final InterruptedException ex) {
                    this.ignoreException(ex);
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Helper method which just logs the exception in debug mode.
     * @param e
     */
    private void ignoreException(final Exception e) {
        if ( this.logger.isDebugEnabled() ) {
            this.logger.debug("Ignored exception " + e.getMessage(), e);
        }
    }
    
    /**
     * Return the number of events which have been posted to the local eventAdmin
     * @return number of events
     */
    public int getPostedEventCounter() {
        return postedEventCounter.get();
    }
    
}
