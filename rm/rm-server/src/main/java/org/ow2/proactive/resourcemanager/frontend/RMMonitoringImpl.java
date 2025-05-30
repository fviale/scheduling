/*
 * ProActive Parallel Suite(TM):
 * The Open Source library for parallel and distributed
 * Workflows & Scheduling, Orchestration, Cloud Automation
 * and Big Data Analysis on Enterprise Grids & Clouds.
 *
 * Copyright (c) 2007 - 2017 ActiveEon
 * Contact: contact@activeeon.com
 *
 * This library is free software: you can redistribute it and/or
 * modify it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation: version 3 of
 * the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * If needed, contact us to obtain a release under GPL Version 2 or 3
 * or a different license than the AGPL.
 */
package org.ow2.proactive.resourcemanager.frontend;

import static org.ow2.proactive.resourcemanager.core.properties.PAResourceManagerProperties.RM_JMX_TENANT_NAMES;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.objectweb.proactive.Body;
import org.objectweb.proactive.InitActive;
import org.objectweb.proactive.RunActive;
import org.objectweb.proactive.Service;
import org.objectweb.proactive.api.PAActiveObject;
import org.objectweb.proactive.core.ProActiveException;
import org.objectweb.proactive.core.UniqueID;
import org.objectweb.proactive.core.body.request.Request;
import org.objectweb.proactive.core.util.wrapper.BooleanWrapper;
import org.objectweb.proactive.extensions.annotation.ActiveObject;
import org.ow2.proactive.resourcemanager.authentication.Client;
import org.ow2.proactive.resourcemanager.common.RMConstants;
import org.ow2.proactive.resourcemanager.common.event.*;
import org.ow2.proactive.resourcemanager.core.RMCore;
import org.ow2.proactive.resourcemanager.core.history.NodeHistory;
import org.ow2.proactive.resourcemanager.core.jmx.RMJMXHelper;
import org.ow2.proactive.resourcemanager.core.properties.PAResourceManagerProperties;
import org.ow2.proactive.resourcemanager.db.RMDBManager;
import org.ow2.proactive.resourcemanager.exception.RMException;
import org.ow2.proactive.resourcemanager.utils.RMStatistics;


/**
 * Active object designed for the Monitoring of the Resource Manager.
 * This class provides a way for a monitor to ask at
 * Resource Manager to throw events
 * generated by nodes and nodes sources management. RMMonitoring dispatch
 * events thrown by {@link RMCore} to all its monitors.
 *
 *
 * @see org.ow2.proactive.resourcemanager.frontend.RMEventListener
 *
 * @author The ProActive Team
 * @since ProActive Scheduling 0.9
 */
@ActiveObject
public class RMMonitoringImpl implements RMMonitoring, RMEventListener, InitActive, RunActive {
    private static final Logger logger = Logger.getLogger(RMMonitoringImpl.class);

    // Attributes
    private RMCore rmcore;

    private Map<UniqueID, EventDispatcher> dispatchers;

    private transient ExecutorService eventDispatcherThreadPool;

    /** Resource Manager's statistics */
    public static final RMStatistics rmStatistics = new RMStatistics();

    private static final Map<String, RMStatistics> rmStatisticsByTenant = new HashMap<>();

    public static final String NO_TENANT = "NO_TENANT";

    public static final String ALL_TENANTS = "ALL_TENANTS";

    static {
        if (RM_JMX_TENANT_NAMES.isSet()) {
            rmStatisticsByTenant.put(NO_TENANT, new RMStatistics());
            RM_JMX_TENANT_NAMES.getValueAsList(",")
                               .stream()
                               .forEach(tenant -> rmStatisticsByTenant.put(tenant, new RMStatistics()));
        }
    }

    public static RMStatistics getRmStatistics(String tenant) {
        return rmStatisticsByTenant.get(tenant);
    }

    // ----------------------------------------------------------------------//
    // CONSTRUTORS

    /** ProActive empty constructor */
    public RMMonitoringImpl() {
    }

    /**
     * Creates the RMMonitoring active object.
     * @param rmcore Stub of the RMCore active object.
     */
    public RMMonitoringImpl(RMCore rmcore) {
        this.dispatchers = new HashMap<>();
        this.rmcore = rmcore;
    }

    /**
     * @see org.objectweb.proactive.InitActive#initActivity(org.objectweb.proactive.Body)
     */
    public void initActivity(Body body) {
        try {
            PAActiveObject.registerByName(PAActiveObject.getStubOnThis(), RMConstants.NAME_ACTIVE_OBJECT_RMMONITORING);
            eventDispatcherThreadPool = Executors.newFixedThreadPool(PAResourceManagerProperties.RM_MONITORING_MAX_THREAD_NUMBER.getValueAsInt());
        } catch (ProActiveException e) {
            logger.debug("Cannot register RMMonitoring. Aborting...", e);
            PAActiveObject.terminateActiveObject(true);
        }
    }

    /**
     * Method controls the execution of every request.
     * Tries to keep this active object alive in case of any exception.
     */
    public void runActivity(Body body) {
        Service service = new Service(body);
        while (body.isActive()) {
            try {
                Request request = service.blockingRemoveOldest();
                if (request != null) {
                    try {
                        service.serve(request);
                    } catch (Throwable e) {
                        logger.error("Cannot serve request: " + request, e);
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("runActivity interrupted", e);
            }
        }
    }

    private class EventDispatcher implements Runnable {

        protected Client client;

        protected RMEventListener listener;

        protected LinkedList<RMEvent> events;

        protected List<RMEventType> eventTypes = null;

        protected AtomicBoolean inProcess = new AtomicBoolean(false);

        protected long counter = 0;

        public EventDispatcher(Client client, RMEventListener listener, RMEventType[] eventTypes) {
            this.client = client;
            this.listener = listener;
            if (eventTypes != null && eventTypes.length > 0) {
                this.eventTypes = Arrays.asList(eventTypes);
            }

            this.events = new LinkedList<>();
        }

        public void run() {

            int numberOfEventDelivered = 0;
            long timeStamp = System.currentTimeMillis();
            if (logger.isDebugEnabled()) {
                logger.debug("Initializing " + Thread.currentThread() + " for events delivery to client '" + client +
                             "'");
            }

            while (true) {
                RMEvent event = null;
                synchronized (events) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(events.size() + " pending events for the client '" + client + "'");
                    }
                    if (events.size() > 0) {
                        event = events.removeFirst();
                    }
                }

                if (event != null) {
                    deliverEvent(event);
                    numberOfEventDelivered++;
                } else {
                    break;
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Finishing delivery in " + Thread.currentThread() + " to client '" + client + "'. " +
                             numberOfEventDelivered + " events were delivered in " +
                             (System.currentTimeMillis() - timeStamp) + " ms");
            }
            inProcess.set(false);
        }

        private void deliverEvent(RMEvent event) {

            //dispatch event
            long timeStamp = System.currentTimeMillis();

            if (logger.isDebugEnabled()) {
                logger.debug("Dispatching event '" + event.toString() + "' to client " + client);
            }
            try {
                if (event instanceof RMNodeEvent) {
                    RMNodeEvent nodeEvent = (RMNodeEvent) event;
                    listener.nodeEvent(nodeEvent);
                } else if (event instanceof RMNodeSourceEvent) {
                    RMNodeSourceEvent sourceEvent = (RMNodeSourceEvent) event;
                    listener.nodeSourceEvent(sourceEvent);
                } else {
                    listener.rmEvent(event);
                }

                long time = System.currentTimeMillis() - timeStamp;
                if (logger.isDebugEnabled()) {
                    logger.debug("Event '" + event.toString() + "' has been delivered to client " + client + " in " +
                                 time + " ms");
                }

            } catch (Exception e) {
                // probably listener was removed or disconnected
                logger.warn("Cannot send events to " + client, e);
                synchronized (dispatchers) {
                    dispatchers.remove(client.getId());
                    logger.warn(client + " was removed from listeners");
                }
            }
        }

        public void queueEvent(RMEvent event) {
            synchronized (events) {
                if (eventTypes == null || eventTypes.contains(event.getEventType())) {
                    try {
                        // clone event object to set a different counter for each client
                        RMEvent cloneEvent = (RMEvent) event.clone();
                        cloneEvent.setCounter(++counter);
                        events.add(cloneEvent);

                        if (inProcess.get()) {
                            if (logger.isDebugEnabled()) {
                                logger.debug("Communication to the client " + client +
                                             " is in progress in one thread of the thread pool.");
                            }
                        } else {
                            inProcess.set(true);
                            eventDispatcherThreadPool.submit(this);
                        }
                    } catch (CloneNotSupportedException ex) {
                        logger.error(ex.getMessage(), ex);
                    }
                }
            }
        }

        public void setCounter(long counter) {
            this.counter = counter;
        }
    }

    private class GroupEventDispatcher extends EventDispatcher {

        public GroupEventDispatcher(Client client, RMEventListener stub, RMEventType[] events) {
            super(client, stub, events);
        }

        public void run() {

            long timeStamp = System.currentTimeMillis();
            if (logger.isDebugEnabled()) {
                logger.debug("Initializing " + Thread.currentThread() + " for events delivery to client '" + client +
                             "'");
            }

            while (true) {
                LinkedList<RMEvent> toDeliver = new LinkedList<>();
                synchronized (events) {
                    if (logger.isDebugEnabled()) {
                        logger.debug(events.size() + " pending events for the client '" + client + "'");
                    }

                    if (events.size() > 0) {
                        toDeliver.clear();
                        toDeliver.addAll(events);
                        events.clear();
                    }
                }

                if (toDeliver.size() > 0) {
                    if (deliverEvents(toDeliver)) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Finishing delivery in " + Thread.currentThread() + " to client '" + client +
                                         "'. " + toDeliver.size() + " events were delivered in " +
                                         (System.currentTimeMillis() - timeStamp) + " ms");
                        }
                    }

                } else {
                    break;
                }
            }
            inProcess.set(false);
        }

        private boolean deliverEvents(Collection<RMEvent> events) {
            //dispatch event
            long timeStamp = System.currentTimeMillis();

            if (logger.isDebugEnabled()) {
                for (RMEvent event : events) {
                    logger.debug("Dispatching events '" + event.toString() + "' to client " + client);
                }
            }
            try {
                ((RMGroupEventListener) listener).notify(events);

                long time = System.currentTimeMillis() - timeStamp;
                if (logger.isDebugEnabled()) {
                    logger.debug("Events has been delivered to client " + client + " in " + time + " ms");
                }

            } catch (Exception e) {
                // probably listener was removed or disconnected
                logger.warn("Cannot send events to " + client, e);
                synchronized (dispatchers) {
                    dispatchers.remove(client.getId());
                    logger.warn(client + " was removed from listeners");
                }
                return false;
            }
            return true;
        }
    }

    /** Register a new Resource manager listener.
     * Way to a monitor object to ask at RMMonitoring to throw
     * RM events to it.
     * @param stub a listener object which implements {@link RMEventListener}
     * interface.
     * @param events list of wanted events that must be received.
     * @return RMInitialState snapshot of RM's current state : nodes and node sources.
     *  */
    public RMInitialState addRMEventListener(RMEventListener stub, RMEventType... events) {
        UniqueID id = PAActiveObject.getContext().getCurrentRequest().getSourceBodyID();

        logger.debug("Adding the RM listener for " + id.shortString());
        synchronized (dispatchers) {
            Client client = null;
            synchronized (RMCore.clients) {
                client = RMCore.clients.get(id);
            }
            if (client == null) {
                throw new IllegalArgumentException("Unknown client " + id.shortString());
            }

            EventDispatcher eventDispatcher = null;
            if (stub instanceof RMGroupEventListener) {
                eventDispatcher = new GroupEventDispatcher(client, stub, events);
            } else {
                eventDispatcher = new EventDispatcher(client, stub, events);
            }
            this.dispatchers.put(id, eventDispatcher);

            RMInitialState rmInitialState = rmcore.getRMInitialState();

            eventDispatcher.setCounter(rmInitialState.getLatestCounter());
            return rmInitialState;
        }
    }

    /**
     * Removes a listener from RMMonitoring. Only listener itself must call this method
     */
    public void removeRMEventListener() {
        UniqueID id = PAActiveObject.getContext().getCurrentRequest().getSourceBodyID();

        String shortId = id.shortString();
        if (removeRMEventListener(id)) {
            logger.debug("Removing the RM listener for " + shortId);
        } else {
            logger.warn("Unknown listener found: " + shortId);
        }
    }

    public boolean removeRMEventListener(UniqueID id) {
        if (dispatchers == null || dispatchers.isEmpty()) {
            return false;
        }

        synchronized (dispatchers) {
            return dispatchers.remove(id) != null;
        }
    }

    @Deprecated
    public boolean isAlive() {
        return true;
    }

    public void queueEvent(RMEvent event) {
        //dispatch event
        if (logger.isDebugEnabled()) {
            logger.debug(event.toString() + " event");
        }

        synchronized (dispatchers) {
            for (EventDispatcher dispatcher : dispatchers.values()) {
                dispatcher.queueEvent(event);
            }
        }
    }

    public void nodeEventOnlyForStatistic(RMNodeEvent event) {
        rmStatistics.nodeEvent(event);
    }

    /**
     * @see org.ow2.proactive.resourcemanager.frontend.RMEventListener#nodeEvent(org.ow2.proactive.resourcemanager.common.event.RMNodeEvent)
     */
    public void nodeEvent(RMNodeEvent event) {
        RMMonitoringImpl.rmStatistics.nodeEvent(event);
        if (!rmStatisticsByTenant.isEmpty()) {
            String tenant = event.getTenant();
            if (tenant == null) {
                // a node event with no tenant must be registered in all statistics
                rmStatisticsByTenant.values().forEach(rmstats -> rmstats.nodeEvent(event));
            } else if (!rmStatisticsByTenant.containsKey(tenant)) {
                logger.error("Event received with tenant " + tenant +
                             " cannot be handled by the current configuration. Ensure it is defined in " +
                             RM_JMX_TENANT_NAMES.getKey() + ".");
            } else {
                rmStatisticsByTenant.get(tenant).nodeEvent(event);
            }
        }
        RMDBManager.getInstance().saveNodeHistory(new NodeHistory(event));
        queueEvent(event);
    }

    public void setNeededNodes(Map<String, Integer> neededNodes) {
        RMMonitoringImpl.rmStatistics.setNeededNodes(neededNodes.get(ALL_TENANTS));
        if (!rmStatisticsByTenant.isEmpty()) {
            rmStatisticsByTenant.entrySet()
                                .forEach(entry -> entry.getValue().setNeededNodes(neededNodes.get(entry.getKey())));
        }
    }

    public int getNeededNodes() {
        return RMMonitoringImpl.rmStatistics.getNeededNodes();
    }

    /**
     * @see org.ow2.proactive.resourcemanager.frontend.RMEventListener#nodeSourceEvent(org.ow2.proactive.resourcemanager.common.event.RMNodeSourceEvent)
     */
    public void nodeSourceEvent(RMNodeSourceEvent event) {
        queueEvent(event);
    }

    /**
     * @see org.ow2.proactive.resourcemanager.frontend.RMEventListener#rmEvent(org.ow2.proactive.resourcemanager.common.event.RMEvent)
     */
    public void rmEvent(RMEvent event) {
        RMMonitoringImpl.rmStatistics.rmEvent(event);
        if (!rmStatisticsByTenant.isEmpty()) {
            rmStatisticsByTenant.entrySet().forEach(entry -> entry.getValue().rmEvent(event));
        }
        queueEvent(event);
    }

    /** 
     * Stop and remove monitoring active object
     */
    public BooleanWrapper shutdown() {
        //throwing shutdown event
        rmEvent(new RMEvent(RMEventType.SHUTDOWN));
        PAActiveObject.terminateActiveObject(false);

        RMJMXHelper.getInstance().shutdown();
        // initiating shutdown
        eventDispatcherThreadPool.shutdown();
        try {
            // waiting until all clients will be notified
            eventDispatcherThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            logger.warn("", e);
        }

        return new BooleanWrapper(true);
    }

    public Logger getLogger() {
        return logger;
    }

    /**
     * Gets the current snapshot of the resource manager state providing
     * detailed nodes and node source information.
     *
     * To obtain summary of the resource manager state use {@link ResourceManager}.getState()
     *
     * @return the current state of the resource manager
     */
    public RMInitialState getState() {
        return rmcore.getRMInitialState();
    }
}
