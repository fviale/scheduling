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
package org.ow2.proactive.scheduler.common.util;

import java.io.Serializable;
import java.security.KeyException;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;

import org.apache.log4j.Logger;
import org.objectweb.proactive.ActiveObjectCreationException;
import org.objectweb.proactive.annotation.ImmediateService;
import org.objectweb.proactive.api.PAActiveObject;
import org.objectweb.proactive.core.node.NodeException;
import org.objectweb.proactive.extensions.annotation.ActiveObject;
import org.ow2.proactive.authentication.UserData;
import org.ow2.proactive.authentication.crypto.CredData;
import org.ow2.proactive.authentication.crypto.Credentials;
import org.ow2.proactive.db.SortParameter;
import org.ow2.proactive.scheduler.common.*;
import org.ow2.proactive.scheduler.common.exception.*;
import org.ow2.proactive.scheduler.common.job.*;
import org.ow2.proactive.scheduler.common.task.*;
import org.ow2.proactive.scheduler.common.usage.JobUsage;
import org.ow2.proactive.scheduler.common.util.logforwarder.AppenderProvider;
import org.ow2.proactive.scheduler.job.SchedulerUserInfo;
import org.ow2.proactive.scheduler.signal.SignalApiException;
import org.ow2.proactive.utils.console.MBeanInfoViewer;
import org.thavam.util.concurrent.blockingMap.BlockingHashMap;
import org.thavam.util.concurrent.blockingMap.BlockingMap;


/**
 * This class implements an active object managing a connection to the Scheduler (a proxy to the Scheduler)
 * You must init the proxy by calling the {@link #init(String, String, String)} method after having created it
 */
@ActiveObject
public class SchedulerProxyUserInterface implements Scheduler, Serializable, SchedulerEventListener {

    protected transient Scheduler uischeduler;

    protected transient MBeanInfoViewer mbeaninfoviewer;

    public static final Logger logger = Logger.getLogger(SchedulerProxyUserInterface.class);

    /*
     * a reference to a stub on this active object
     */
    private static transient SchedulerProxyUserInterface activeInstance;

    private volatile boolean sessionListenerAdded = false;

    private transient BlockingMap<JobId, Boolean> finishedJobs = new BlockingHashMap<>();

    private transient Set<JobId> waitedJobs = Collections.synchronizedSet(new HashSet<>());

    /**
     * Default constructor demanded by ProActive.
     * WARNING: Singleton pattern: Use getActiveInstance() instead of this constructor
     * @deprecated
     */
    public SchedulerProxyUserInterface() {
        //Default constructor demanded by ProActive.
    }

    /**
     * Singleton active object constructor.
     * Creates the singleton
     *
     * Creates an active object on this and returns a reference to its stub.
     * If the active object is already created, returns the reference
     * @return
     * @throws NodeException
     * @throws ActiveObjectCreationException
     */

    public static SchedulerProxyUserInterface getActiveInstance() throws ActiveObjectCreationException, NodeException {
        if (activeInstance != null)
            return activeInstance;

        activeInstance = PAActiveObject.newActive(SchedulerProxyUserInterface.class, new Object[] {});
        return activeInstance;
    }

    /**
     * initialize the connection the scheduler. 
     * Must be called only once
     * @param url the scheduler's url 
     * @param credentials the credential to be passed to the scheduler
     * @throws SchedulerException thrown if the scheduler is not available
     * @throws LoginException thrown if the credential is invalid
     */
    public void init(String url, Credentials credentials) throws SchedulerException, LoginException {
        SchedulerAuthenticationInterface auth = SchedulerConnection.join(url);
        this.uischeduler = auth.login(credentials);
        mbeaninfoviewer = new MBeanInfoViewer(auth, null, credentials);
    }

    /**
     * initialize the connection the scheduler. 
     * Must be called only once.
     * Create the corresponding credential object before sending it
     * to the scheduler.
     * @param url the scheduler's url 
     * @param user the username to use
     * @param pwd the password to use
     * @throws SchedulerException thrown if the scheduler is not available
     * @throws LoginException if the couple username/password is invalid
     */
    public void init(String url, String user, String pwd) throws SchedulerException, LoginException {
        CredData cred = new CredData(CredData.parseLogin(user), CredData.parseDomain(user), pwd);
        init(url, cred);
    }

    /**
     * initialize the connection the scheduler. 
     * Must be called only once.
     * Create the corresponding credential object before sending it
     * to the scheduler.
     * @param url the scheduler's url 
     * @param credData the credential object that contains user-related data
     * @throws SchedulerException thrown if the scheduler is not available
     * @throws LoginException if the couple username/password is invalid
     * @since Scheduling 3.1.0
     */
    public void init(String url, CredData credData) throws SchedulerException, LoginException {
        SchedulerAuthenticationInterface auth = SchedulerConnection.join(url);
        PublicKey pubKey = auth.getPublicKey();

        try {

            Credentials cred = Credentials.createCredentials(credData, pubKey);
            this.uischeduler = auth.login(cred);
            mbeaninfoviewer = new MBeanInfoViewer(auth, credData.getLogin(), cred);
        } catch (KeyException e) {
            throw new InternalSchedulerException(e);
        }
    }

    /**
     * Subscribes a listener to the Scheduler
     */
    @Override
    @ImmediateService
    public SchedulerState addEventListener(SchedulerEventListener sel, boolean myEventsOnly, boolean getCurrentState,
            SchedulerEvent... events) throws NotConnectedException, PermissionException {
        checkSchedulerConnection();

        return uischeduler.addEventListener(sel, myEventsOnly, true, events);

    }

    private void addSessionJobEventListener() throws NotConnectedException, PermissionException {
        if (!sessionListenerAdded) {
            checkSchedulerConnection();

            uischeduler.addEventListener((SchedulerEventListener) PAActiveObject.getStubOnThis(),
                                         true,
                                         true,
                                         new SchedulerEvent[] { SchedulerEvent.JOB_RUNNING_TO_FINISHED });
            sessionListenerAdded = true;
        }
    }

    @Override
    @ImmediateService
    public void disconnect() throws NotConnectedException, PermissionException {
        if (uischeduler == null)
            throw new NotConnectedException("Not connected to the scheduler.");

        uischeduler.disconnect();

    }

    @Override
    @ImmediateService
    public boolean isConnected() {
        if (uischeduler == null) {
            return false;
        } else
            try {
                return uischeduler.isConnected();
            } catch (Exception e) {
                logger.error("Error when callling " + this.getClass().getCanonicalName() +
                             " -> isConnected() method: " + e.getMessage() + ". The connection is considered lost. ",
                             e);
                return false;
            }
    }

    @Override
    @ImmediateService
    public String getCurrentPolicy() throws NotConnectedException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getCurrentPolicy();
    }

    @Override
    @ImmediateService
    public Map getJobsToSchedule() throws NotConnectedException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getJobsToSchedule();
    }

    @Override
    @ImmediateService
    public List<TaskDescriptor> getTasksToSchedule() throws NotConnectedException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getTasksToSchedule();
    }

    @Override
    @ImmediateService
    public void renewSession() throws NotConnectedException {
        checkSchedulerConnection();
        uischeduler.renewSession();
    }

    @Override
    @ImmediateService
    public void removeEventListener() throws NotConnectedException, PermissionException {
        checkSchedulerConnection();
        uischeduler.removeEventListener();

    }

    @Override
    @ImmediateService
    public JobId submit(Job job)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        checkSchedulerConnection();
        return uischeduler.submit(job);
    }

    @Override
    public List<JobIdDataAndError> submit(List<Job> jobs) throws NotConnectedException {
        checkSchedulerConnection();
        return uischeduler.submit(jobs);
    }

    @Override
    @ImmediateService
    public JobId reSubmit(JobId currentJobId, Map<String, String> jobVariables, Map<String, String> jobGenericInfos,
            String sessionId) throws NotConnectedException, UnknownJobException, PermissionException,
            JobCreationException, SubmissionClosedException {
        checkSchedulerConnection();
        return uischeduler.reSubmit(currentJobId, jobVariables, jobGenericInfos, sessionId);
    }

    @Override
    @ImmediateService
    public void changeJobPriority(JobId jobId, JobPriority priority)
            throws NotConnectedException, UnknownJobException, PermissionException, JobAlreadyFinishedException {
        checkSchedulerConnection();
        uischeduler.changeJobPriority(jobId, priority);

    }

    @Override
    @ImmediateService
    public JobResult getJobResult(String jobId) throws NotConnectedException, PermissionException, UnknownJobException {
        checkSchedulerConnection();
        return uischeduler.getJobResult(jobId);
    }

    @Override
    @ImmediateService
    public List<String> getUserSpaceURIs() throws NotConnectedException, PermissionException {
        return uischeduler.getUserSpaceURIs();
    }

    @Override
    @ImmediateService
    public List<String> getGlobalSpaceURIs() throws NotConnectedException, PermissionException {
        return uischeduler.getGlobalSpaceURIs();
    }

    @Override
    @ImmediateService
    public JobResult getJobResult(JobId jobId) throws NotConnectedException, PermissionException, UnknownJobException {
        checkSchedulerConnection();
        return uischeduler.getJobResult(jobId);
    }

    @Override
    @ImmediateService
    public TaskResult getTaskResult(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getTaskResult(jobId, taskName);
    }

    @Override
    @ImmediateService
    public TaskResult getTaskResult(JobId jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getTaskResult(jobId, taskName);
    }

    @Override
    @ImmediateService
    public List<TaskResult> getTaskResultsByTag(JobId jobId, String taskTag)
            throws NotConnectedException, UnknownJobException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getTaskResultsByTag(jobId, taskTag);
    }

    @Override
    @ImmediateService
    public List<TaskResult> getTaskResultsByTag(String jobId, String taskTag)
            throws NotConnectedException, UnknownJobException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getTaskResultsByTag(jobId, taskTag);
    }

    @Override
    @ImmediateService
    public TaskResult getTaskResultFromIncarnation(JobId jobId, String taskName, int inc)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return uischeduler.getTaskResultFromIncarnation(jobId, taskName, inc);
    }

    @Override
    @ImmediateService
    public List<TaskResult> getTaskResultAllIncarnations(JobId jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return uischeduler.getTaskResultAllIncarnations(jobId, taskName);
    }

    @Override
    @ImmediateService
    public List<TaskResult> getTaskResultAllIncarnations(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return uischeduler.getTaskResultAllIncarnations(jobId, taskName);
    }

    @Override
    @ImmediateService
    public TaskResult getTaskResultFromIncarnation(String jobId, String taskName, int inc)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return uischeduler.getTaskResultFromIncarnation(jobId, taskName, inc);
    }

    @Override
    @ImmediateService
    public boolean killTask(JobId jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.killTask(jobId, taskName);
    }

    @Override
    @ImmediateService
    public boolean restartTask(JobId jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.restartTask(jobId, taskName, restartDelay);
    }

    @Override
    @ImmediateService
    public boolean preemptTask(JobId jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.preemptTask(jobId, taskName, restartDelay);
    }

    @Override
    @ImmediateService
    public boolean killTask(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.killTask(jobId, taskName);
    }

    @Override
    @ImmediateService
    public boolean restartTask(String jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.restartTask(jobId, taskName, restartDelay);
    }

    @Override
    @ImmediateService
    public boolean finishInErrorTask(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.finishInErrorTask(jobId, taskName);
    }

    @Override
    @ImmediateService
    public boolean restartInErrorTask(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.restartInErrorTask(jobId, taskName);
    }

    @Override
    @ImmediateService
    public boolean preemptTask(String jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.preemptTask(jobId, taskName, restartDelay);
    }

    @Override
    @ImmediateService
    public void enableRemoteVisualization(String jobId, String taskName, String connectionString)
            throws NotConnectedException, PermissionException, UnknownJobException, UnknownTaskException {
        checkSchedulerConnection();
        uischeduler.enableRemoteVisualization(jobId, taskName, connectionString);
    }

    @Override
    @ImmediateService
    public void registerService(String jobId, int serviceInstanceid, boolean enableActions)
            throws NotConnectedException, PermissionException, UnknownJobException {
        checkSchedulerConnection();
        uischeduler.registerService(jobId, serviceInstanceid, enableActions);
    }

    @Override
    @ImmediateService
    public void detachService(String jobId, int serviceInstanceid)
            throws NotConnectedException, PermissionException, UnknownJobException {
        checkSchedulerConnection();
        uischeduler.detachService(jobId, serviceInstanceid);
    }

    @Override
    @ImmediateService
    public boolean killJob(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.killJob(jobId);
    }

    @Override
    @ImmediateService
    public void listenJobLogs(JobId jobId, AppenderProvider appenderProvider)
            throws NotConnectedException, UnknownJobException, PermissionException {
        checkSchedulerConnection();
        uischeduler.listenJobLogs(jobId, appenderProvider);

    }

    @Override
    @ImmediateService
    public boolean pauseJob(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.pauseJob(jobId);

    }

    @Override
    @ImmediateService
    public boolean removeJob(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.removeJob(jobId);
    }

    @Override
    @ImmediateService
    public boolean removeJobs(List<JobId> jobIds) throws NotConnectedException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.removeJobs(jobIds);
    }

    @Override
    @ImmediateService
    public boolean removeJobs(long olderThen) throws NotConnectedException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.removeJobs(olderThen);
    }

    @Override
    @ImmediateService
    public boolean resumeJob(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.resumeJob(jobId);
    }

    private void checkSchedulerConnection() throws NotConnectedException {
        if (uischeduler == null) {
            throw new NotConnectedException("Not connected to the scheduler.");
        }
    }

    @Override
    @ImmediateService
    public void changeJobPriority(String jobId, JobPriority priority)
            throws NotConnectedException, UnknownJobException, PermissionException, JobAlreadyFinishedException {
        uischeduler.changeJobPriority(jobId, priority);

    }

    @Override
    @ImmediateService
    public SchedulerStatus getStatus() throws NotConnectedException, PermissionException {
        return uischeduler.getStatus();
    }

    @Override
    @ImmediateService
    public boolean killJob(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.killJob(jobId);
    }

    @Override
    @ImmediateService
    public boolean killJobs(List<String> jobsId) throws NotConnectedException, PermissionException {
        return uischeduler.killJobs(jobsId);
    }

    @Override
    @ImmediateService
    public boolean pauseJob(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.pauseJob(jobId);
    }

    @Override
    @ImmediateService
    public boolean restartAllInErrorTasks(String jobId)
            throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.restartAllInErrorTasks(jobId);
    }

    @Override
    @ImmediateService
    public boolean removeJob(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.removeJob(jobId);
    }

    @Override
    @ImmediateService
    public boolean resumeJob(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.resumeJob(jobId);
    }

    @Override
    @ImmediateService
    public void addEventListener(SchedulerEventListener sel, boolean myEventsOnly, SchedulerEvent... events)
            throws NotConnectedException, PermissionException {
        uischeduler.addEventListener(sel, myEventsOnly, events);
    }

    @Override
    @ImmediateService
    public JobState getJobState(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.getJobState(jobId);
    }

    @Override
    @ImmediateService
    public void listenJobLogs(String jobId, AppenderProvider appenderProvider)
            throws NotConnectedException, UnknownJobException, PermissionException {
        uischeduler.listenJobLogs(jobId, appenderProvider);
    }

    @Override
    @ImmediateService
    public boolean changePolicy(String newPolicyClassName) throws NotConnectedException, PermissionException {
        return uischeduler.changePolicy(newPolicyClassName);
    }

    @Override
    @ImmediateService
    public boolean reloadPolicyConfiguration() throws NotConnectedException, PermissionException {
        return uischeduler.reloadPolicyConfiguration();
    }

    @Override
    @ImmediateService
    public boolean freeze() throws NotConnectedException, PermissionException {
        return uischeduler.freeze();
    }

    @Override
    @ImmediateService
    public JobState getJobState(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.getJobState(jobId);
    }

    @Override
    @ImmediateService
    public TaskState getTaskState(JobId jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return uischeduler.getTaskState(jobId, taskName);
    }

    @Override
    @ImmediateService
    public SchedulerState getState() throws NotConnectedException, PermissionException {
        return uischeduler.getState();
    }

    @Override
    @ImmediateService
    public boolean kill() throws NotConnectedException, PermissionException {
        return uischeduler.kill();
    }

    @Override
    @ImmediateService
    public boolean linkResourceManager(String rmURL) throws NotConnectedException, PermissionException {
        return uischeduler.linkResourceManager(rmURL);
    }

    @Override
    @ImmediateService
    public boolean pause() throws NotConnectedException, PermissionException {
        return uischeduler.pause();
    }

    @Override
    @ImmediateService
    public boolean resume() throws NotConnectedException, PermissionException {
        return uischeduler.resume();
    }

    @Override
    @ImmediateService
    public boolean shutdown() throws NotConnectedException, PermissionException {
        return uischeduler.shutdown();
    }

    @Override
    @ImmediateService
    public boolean start() throws NotConnectedException, PermissionException {
        return uischeduler.start();
    }

    @Override
    @ImmediateService
    public boolean stop() throws NotConnectedException, PermissionException {
        return uischeduler.stop();
    }

    @Override
    @ImmediateService
    public SchedulerState getState(boolean myJobsOnly) throws NotConnectedException, PermissionException {
        return uischeduler.getState(myJobsOnly);
    }

    /**
     *
     * Return the informations about the Scheduler MBean as a formatted string.
     * The first time this method is called it connects to the JMX connector server.
     * The default behavior will try to establish a connection using RMI protocol, if it fails
     * the RO (Remote Object) protocol is used.
     *
     * @param mbeanName the object name of the MBean
     * @return the informations about the MBean as a formatted string
     *
     * @see MBeanInfoViewer#getInfo(String)
     */
    @Deprecated
    public String getInfo(String mbeanName) {
        try {
            return mbeaninfoviewer.getInfo(mbeanName);
        } catch (RuntimeException e) {
            return e.getMessage() + ", you are probably not authorized to access to this information.";
        }
    }

    /**
     * Return the informations about the Scheduler MBean as a Map.
     * The first time this method is called it connects to the JMX connector server.
     * The default behavior will try to establish a connection using RMI protocol, if it fails 
     * the RO (Remote Object) protocol is used.
     *
     * @param mbeanNameAsString the object name of the MBean
     * @return the informations about the MBean as a formatted string
     * 
     * @throws RuntimeException if mbean cannot access or connect the service
     */
    @ImmediateService
    public Map<String, String> getMappedInfo(final String mbeanNameAsString) throws RuntimeException {
        return mbeaninfoviewer.getMappedInfo(mbeanNameAsString);
    }

    @Override
    @ImmediateService
    public String getJobServerLogs(String id) throws UnknownJobException, NotConnectedException, PermissionException {
        return uischeduler.getJobServerLogs(id);
    }

    @Override
    @ImmediateService
    public String getTaskServerLogs(String id, String taskName)
            throws UnknownJobException, UnknownTaskException, NotConnectedException, PermissionException {
        return uischeduler.getTaskServerLogs(id, taskName);
    }

    @Override
    @ImmediateService
    public String getTaskServerLogsByTag(String id, String taskTag)
            throws UnknownJobException, NotConnectedException, PermissionException {
        return uischeduler.getTaskServerLogsByTag(id, taskTag);
    }

    @Override
    @ImmediateService
    public Page<JobInfo> getJobs(int index, int range, JobFilterCriteria filterCriteria,
            List<SortParameter<JobSortParameter>> sortParameters) throws NotConnectedException, PermissionException {
        return uischeduler.getJobs(index, range, filterCriteria, sortParameters);
    }

    @Override
    @ImmediateService
    public List<JobInfo> getJobsInfoList(List<String> jobsId) throws PermissionException, NotConnectedException {
        return uischeduler.getJobsInfoList(jobsId);
    }

    @Override
    @ImmediateService
    public List<SchedulerUserInfo> getUsers() throws NotConnectedException, PermissionException {
        return uischeduler.getUsers();
    }

    @Override
    @ImmediateService
    public List<SchedulerUserInfo> getUsersWithJobs() throws NotConnectedException, PermissionException {
        return uischeduler.getUsersWithJobs();
    }

    @Override
    @ImmediateService
    public FilteredStatistics getFilteredStatistics(String workflowName, String bucketName, Boolean myJobs,
            long startDate, long endDate) throws NotConnectedException, PermissionException {
        return uischeduler.getFilteredStatistics(workflowName, bucketName, myJobs, startDate, endDate);
    }

    @Override
    @ImmediateService
    public List<FilteredTopWorkflow> getTopWorkflowsWithIssues(int numberOfWorkflows, String workflowName,
            String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {
        return uischeduler.getTopWorkflowsWithIssues(numberOfWorkflows,
                                                     workflowName,
                                                     bucketName,
                                                     myJobs,
                                                     startDate,
                                                     endDate);
    }

    @Override
    @ImmediateService
    public List<FilteredTopWorkflowsCumulatedCoreTime> getTopWorkflowsCumulatedCoreTime(int numberOfWorkflows,
            String workflowName, String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {
        return uischeduler.getTopWorkflowsCumulatedCoreTime(numberOfWorkflows,
                                                            workflowName,
                                                            bucketName,
                                                            myJobs,
                                                            startDate,
                                                            endDate);
    }

    @Override
    @ImmediateService
    public List<FilteredTopWorkflowsNumberOfNodes> getTopWorkflowsNumberOfNodes(int numberOfWorkflows,
            String workflowName, String bucketName, boolean myJobs, long startDate, long endDate, boolean inParallel)
            throws NotConnectedException, PermissionException {
        return uischeduler.getTopWorkflowsNumberOfNodes(numberOfWorkflows,
                                                        workflowName,
                                                        bucketName,
                                                        myJobs,
                                                        startDate,
                                                        endDate,
                                                        inParallel);
    }

    @Override
    @ImmediateService
    public List<WorkflowDuration> getTopExecutionTimeWorkflows(int numberOfWorkflows, String workflowName,
            String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {
        return uischeduler.getTopExecutionTimeWorkflows(numberOfWorkflows,
                                                        workflowName,
                                                        bucketName,
                                                        myJobs,
                                                        startDate,
                                                        endDate);
    }

    @Override
    @ImmediateService
    public List<WorkflowDuration> getTopPendingTimeWorkflows(int numberOfWorkflows, String workflowName,
            String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {
        return uischeduler.getTopPendingTimeWorkflows(numberOfWorkflows,
                                                      workflowName,
                                                      bucketName,
                                                      myJobs,
                                                      startDate,
                                                      endDate);
    }

    @Override
    @ImmediateService
    public Map<String, Integer> getSubmissionModeCount(String workflowName, String bucketName, Boolean myJobs,
            long startDate, long endDate) throws NotConnectedException, PermissionException {
        return uischeduler.getSubmissionModeCount(workflowName, bucketName, myJobs, startDate, endDate);
    }

    @Override
    @ImmediateService
    public CompletedJobsCount getCompletedJobs(Boolean myJobs, String workflowName, String bucketName, long startDate,
            long endDate, int numberOfIntervals) throws NotConnectedException, PermissionException {
        return uischeduler.getCompletedJobs(myJobs, workflowName, bucketName, startDate, endDate, numberOfIntervals);
    }

    @Override
    @ImmediateService
    public CompletedTasksCount getCompletedTasks(Boolean myTasks, String taskName, long startDate, long endDate,
            int numberOfIntervals) throws NotConnectedException, PermissionException {
        return uischeduler.getCompletedTasks(myTasks, taskName, startDate, endDate, numberOfIntervals);
    }

    @Override
    @ImmediateService
    public Set<String> getSubmissionModeValues() throws NotConnectedException, PermissionException {
        return uischeduler.getSubmissionModeValues();
    }

    @Override
    @ImmediateService
    public List<JobUsage> getMyAccountUsage(Date startDate, Date endDate)
            throws NotConnectedException, PermissionException {
        return uischeduler.getMyAccountUsage(startDate, endDate);
    }

    @Override
    @ImmediateService
    public List<JobUsage> getAccountUsage(String user, Date startDate, Date endDate)
            throws NotConnectedException, PermissionException {
        return uischeduler.getAccountUsage(user, startDate, endDate);
    }

    @Override
    @ImmediateService
    public void putThirdPartyCredential(String key, String value) throws SchedulerException {
        uischeduler.putThirdPartyCredential(key, value);
    }

    @Override
    @ImmediateService
    public Set<String> thirdPartyCredentialsKeySet() throws SchedulerException {
        return uischeduler.thirdPartyCredentialsKeySet();
    }

    @Override
    @ImmediateService
    public void removeThirdPartyCredential(String key) throws SchedulerException {
        uischeduler.removeThirdPartyCredential(key);
    }

    @Override
    @ImmediateService
    public Page<TaskId> getTaskIds(String taskTag, long from, long to, boolean mytasks, Set<TaskStatus> taskStatuses,
            int offset, int limit) throws SchedulerException {
        return uischeduler.getTaskIds(taskTag, from, to, mytasks, taskStatuses, offset, limit);
    }

    @Override
    @ImmediateService
    public Page<TaskState> getTaskStates(String taskTag, long from, long to, boolean mytasks,
            Set<TaskStatus> statusFilter, int offset, int limit, SortSpecifierContainer sortParams)
            throws SchedulerException {
        return uischeduler.getTaskStates(taskTag, from, to, mytasks, statusFilter, offset, limit, sortParams);
    }

    @Override
    @ImmediateService
    public JobInfo getJobInfo(String jobId) throws SchedulerException {
        return uischeduler.getJobInfo(jobId);
    }

    @Override
    @ImmediateService
    public boolean changeStartAt(JobId jobId, String startAt)
            throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.changeStartAt(jobId, startAt);
    }

    @Override
    @ImmediateService
    public boolean changeStartAt(List<JobId> jobIdList, String startAt)
            throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.changeStartAt(jobIdList, startAt);
    }

    @Override
    @ImmediateService
    public String getJobContent(JobId jobId) throws SchedulerException {
        return uischeduler.getJobContent(jobId);
    }

    @Override
    @ImmediateService
    public Map<Object, Object> getPortalConfiguration() throws SchedulerException {
        return uischeduler.getPortalConfiguration();
    }

    @Override
    @ImmediateService
    public String getCurrentUser() throws NotConnectedException {
        return uischeduler.getCurrentUser();
    }

    @Override
    @ImmediateService
    public UserData getCurrentUserData() throws NotConnectedException {
        return uischeduler.getCurrentUserData();
    }

    @Override
    @ImmediateService
    public Subject getSubject() throws NotConnectedException {
        return uischeduler.getSubject();
    }

    @Override
    @ImmediateService
    public Map getSchedulerProperties() throws SchedulerException {
        return uischeduler.getSchedulerProperties();
    }

    @Override
    @ImmediateService
    public TaskStatesPage getTaskPaginated(String jobId, int offset, int limit)
            throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.getTaskPaginated(jobId, offset, limit);
    }

    @Override
    @ImmediateService
    public TaskStatesPage getTaskPaginated(String jobId, String statusFilter, int offset, int limit)
            throws NotConnectedException, UnknownJobException, PermissionException {
        return uischeduler.getTaskPaginated(jobId, statusFilter, offset, limit);
    }

    @Override
    @ImmediateService
    public List<TaskResult> getPreciousTaskResults(String jobId)
            throws NotConnectedException, PermissionException, UnknownJobException {
        return uischeduler.getPreciousTaskResults(jobId);
    }

    @Override
    @ImmediateService
    public Map<Long, Map<String, Serializable>> getJobResultMaps(List<String> jobsId)
            throws UnknownJobException, NotConnectedException, PermissionException {
        return uischeduler.getJobResultMaps(jobsId);
    }

    @Override
    @ImmediateService
    public Map<Long, List<String>> getPreciousTaskNames(List<String> jobsId) throws SchedulerException {
        return uischeduler.getPreciousTaskNames(jobsId);
    }

    @Override
    public boolean checkPermission(String method) throws SecurityException {
        return uischeduler.checkPermission(method);
    }

    @Override
    @ImmediateService
    public boolean checkJobPermissionMethod(String jobId, String method) throws SchedulerException {
        return uischeduler.checkJobPermissionMethod(jobId, method);
    }

    @Override
    @ImmediateService
    public List<String> checkJobsPermissionMethod(List<String> jobIds, String method) throws SchedulerException {
        return uischeduler.checkJobsPermissionMethod(jobIds, method);
    }

    @Override
    @ImmediateService
    public Set<String> addJobSignal(String jobId, String signal, Map<String, String> updatedVariables)
            throws UnknownJobException, NotConnectedException, PermissionException, SignalApiException,
            JobValidationException {
        return uischeduler.addJobSignal(jobId, signal, updatedVariables);
    }

    @Override
    @ImmediateService
    public List<JobVariable> validateJobSignal(String jobId, String signal, Map<String, String> updatedVariables)
            throws UnknownJobException, NotConnectedException, PermissionException, SignalApiException,
            JobValidationException {
        return uischeduler.validateJobSignal(jobId, signal, updatedVariables);
    }

    public String getStatHistory(String mbeanName, String range, String[] dataSources, String function) {
        return mbeaninfoviewer.retrieveStats(mbeanName, range, dataSources, function);
    }

    @Override
    @ImmediateService
    public Map<String, Map<String, Boolean>> checkJobsPermissionMethods(List<String> jobIds, List<String> methods)
            throws NotConnectedException, UnknownJobException {
        checkSchedulerConnection();
        return uischeduler.checkJobsPermissionMethods(jobIds, methods);
    }

    @Override
    @ImmediateService
    public void addExternalEndpointUrl(String jobId, String endpointName, String externalEndpointUrl,
            String endpointIconUri) throws NotConnectedException, PermissionException, UnknownJobException {
        checkSchedulerConnection();
        uischeduler.addExternalEndpointUrl(jobId, endpointName, externalEndpointUrl, endpointIconUri);
    }

    @Override
    @ImmediateService
    public void removeExternalEndpointUrl(String jobId, String endpointName)
            throws NotConnectedException, PermissionException, UnknownJobException {
        checkSchedulerConnection();
        uischeduler.removeExternalEndpointUrl(jobId, endpointName);
    }

    @Override
    @ImmediateService
    public List<JobLabelInfo> getLabels() throws NotConnectedException, PermissionException {
        checkSchedulerConnection();
        return uischeduler.getLabels();
    }

    @Override
    @ImmediateService
    public List<JobLabelInfo> createLabels(List<String> labels)
            throws NotConnectedException, PermissionException, LabelConflictException, LabelValidationException {
        checkSchedulerConnection();
        return uischeduler.createLabels(labels);
    }

    @Override
    @ImmediateService
    public List<JobLabelInfo> setLabels(List<String> labels)
            throws NotConnectedException, PermissionException, LabelValidationException {
        checkSchedulerConnection();
        return uischeduler.setLabels(labels);
    }

    @Override
    @ImmediateService
    public JobLabelInfo updateLabel(String labelId, String newLabel) throws NotConnectedException, PermissionException,
            LabelConflictException, LabelNotFoundException, LabelValidationException {
        checkSchedulerConnection();
        return uischeduler.updateLabel(labelId, newLabel);
    }

    @Override
    @ImmediateService
    public void deleteLabel(String labelId) throws NotConnectedException, PermissionException, LabelNotFoundException {
        checkSchedulerConnection();
        uischeduler.deleteLabel(labelId);
    }

    @Override
    @ImmediateService
    public void setLabelOnJobs(String labelId, List<String> jobIds)
            throws NotConnectedException, PermissionException, LabelNotFoundException, UnknownJobException {
        checkSchedulerConnection();
        uischeduler.setLabelOnJobs(labelId, jobIds);
    }

    @Override
    @ImmediateService
    public void removeJobLabels(List<String> jobIds)
            throws NotConnectedException, PermissionException, UnknownJobException {
        checkSchedulerConnection();
        uischeduler.removeJobLabels(jobIds);
    }

    @Override
    @ImmediateService
    public void updateLogo(byte[] image) throws NotConnectedException, PermissionException, ImageValidationException {
        checkSchedulerConnection();
        uischeduler.updateLogo(image);
    }

    @ImmediateService
    public void waitForJobFinished(JobId jobId, Long timeout)
            throws PermissionException, NotConnectedException, InterruptedException {
        addSessionJobEventListener();
        waitedJobs.add(jobId);
        // see https://github.com/sarveswaran-m/blockingMap4j/
        Boolean status;
        if (timeout != null && timeout > 0) {
            status = finishedJobs.take(jobId, timeout, TimeUnit.MILLISECONDS);
        } else {
            status = finishedJobs.take(jobId);
        }
        waitedJobs.remove(jobId);
        finishedJobs.remove(jobId);
        if (status == null) {
            throw new InterruptedException("Job " + jobId + " is not finished after " + timeout + " milliseconds");
        }
    }

    @Override
    public void schedulerStateUpdatedEvent(SchedulerEvent eventType) {

    }

    @Override
    public void jobSubmittedEvent(JobState job) {

    }

    @Override
    public void jobStateUpdatedEvent(NotificationData<JobInfo> notification) {
        JobId finishedJobId = notification.getData().getJobId();
        if (waitedJobs.contains(notification.getData().getJobId())) {
            finishedJobs.put(finishedJobId, true);
        }
    }

    @Override
    public void jobUpdatedFullDataEvent(JobState job) {

    }

    @Override
    public void taskStateUpdatedEvent(NotificationData<TaskInfo> notification) {

    }

    @Override
    public void usersUpdatedEvent(NotificationData<UserIdentification> notification) {

    }
}
