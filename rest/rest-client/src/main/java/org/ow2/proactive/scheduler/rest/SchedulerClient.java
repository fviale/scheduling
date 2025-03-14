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
package org.ow2.proactive.scheduler.rest;

import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static org.ow2.proactive.scheduler.common.SchedulerConstants.SUBMISSION_MODE;
import static org.ow2.proactive.scheduler.common.SchedulerConstants.SUBMISSION_MODE_WORKFLOW_API;
import static org.ow2.proactive.scheduler.common.task.TaskStatus.statusesToString;
import static org.ow2.proactive.scheduler.rest.ExceptionUtility.*;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.jobId;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.taskState;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.toFilteredStatistics;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.toJobInfos;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.toJobResult;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.toJobState;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.toJobUsages;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.toSchedulerUserInfos;
import static org.ow2.proactive.scheduler.rest.data.DataUtility.toTaskResult;

import java.io.*;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;

import org.apache.http.client.HttpClient;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.log4j.Logger;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.objectweb.proactive.utils.StackTraceUtil;
import org.ow2.proactive.authentication.ConnectionInfo;
import org.ow2.proactive.authentication.UserData;
import org.ow2.proactive.db.SortParameter;
import org.ow2.proactive.http.HttpClientBuilder;
import org.ow2.proactive.scheduler.common.*;
import org.ow2.proactive.scheduler.common.exception.JobAlreadyFinishedException;
import org.ow2.proactive.scheduler.common.exception.JobCreationException;
import org.ow2.proactive.scheduler.common.exception.LabelConflictException;
import org.ow2.proactive.scheduler.common.exception.LabelNotFoundException;
import org.ow2.proactive.scheduler.common.exception.NotConnectedException;
import org.ow2.proactive.scheduler.common.exception.PermissionException;
import org.ow2.proactive.scheduler.common.exception.SchedulerException;
import org.ow2.proactive.scheduler.common.exception.SubmissionClosedException;
import org.ow2.proactive.scheduler.common.exception.UnknownJobException;
import org.ow2.proactive.scheduler.common.exception.UnknownTaskException;
import org.ow2.proactive.scheduler.common.job.*;
import org.ow2.proactive.scheduler.common.job.factories.Job2XMLTransformer;
import org.ow2.proactive.scheduler.common.task.Task;
import org.ow2.proactive.scheduler.common.task.TaskId;
import org.ow2.proactive.scheduler.common.task.TaskResult;
import org.ow2.proactive.scheduler.common.task.TaskState;
import org.ow2.proactive.scheduler.common.task.TaskStatesPage;
import org.ow2.proactive.scheduler.common.task.TaskStatus;
import org.ow2.proactive.scheduler.common.usage.JobUsage;
import org.ow2.proactive.scheduler.job.JobIdImpl;
import org.ow2.proactive.scheduler.job.SchedulerUserInfo;
import org.ow2.proactive.scheduler.rest.data.DataUtility;
import org.ow2.proactive.scheduler.rest.data.JobInfoImpl;
import org.ow2.proactive.scheduler.rest.data.TaskResultImpl;
import org.ow2.proactive.scheduler.rest.data.TaskStateImpl;
import org.ow2.proactive.scheduler.rest.readers.OctetStreamReader;
import org.ow2.proactive.scheduler.rest.readers.TaskResultReader;
import org.ow2.proactive.scheduler.rest.readers.WildCardTypeReader;
import org.ow2.proactive.scheduler.signal.SignalApiException;
import org.ow2.proactive.scheduler.task.TaskIdImpl;
import org.ow2.proactive_grid_cloud_portal.common.SchedulerRestInterface;
import org.ow2.proactive_grid_cloud_portal.common.dto.LoginForm;
import org.ow2.proactive_grid_cloud_portal.common.dto.PermissionForm;
import org.ow2.proactive_grid_cloud_portal.scheduler.client.SchedulerRestClient;
import org.ow2.proactive_grid_cloud_portal.scheduler.dto.*;
import org.ow2.proactive_grid_cloud_portal.scheduler.exception.*;

import com.google.common.base.Strings;
import com.google.common.io.Closer;
import com.google.common.net.UrlEscapers;


public class SchedulerClient extends ClientBase implements ISchedulerClient {

    private static final long RETRY_INTERVAL = TimeUnit.SECONDS.toMillis(1);

    private SchedulerRestClient schedulerRestClient;

    private String sid;

    private ConnectionInfo connectionInfo;

    private boolean initialized = false;

    private SchedulerEventReceiver schedulerEventReceiver;

    public static final String ASC_SUFFIX = "_a";

    public static final String DESC_SUFFIX = "_d";

    private static final Logger logger = Logger.getLogger(SchedulerClient.class);

    private SchedulerClient() {
    }

    /**
     * Creates an ISchedulerClient instance.
     *
     * @return an ISchedulerClient instance
     */
    public static ISchedulerClient createInstance() {
        SchedulerClient client = new SchedulerClient();
        return (ISchedulerClient) Proxy.newProxyInstance(ISchedulerClient.class.getClassLoader(),
                                                         new Class[] { ISchedulerClient.class },
                                                         new SessionHandler(client));
    }

    private String createJobSortParamsString(List<SortParameter<JobSortParameter>> jobSortParameters) {

        if (jobSortParameters == null) {
            return "";
        }
        return jobSortParameters.stream().map(jobSortParameter -> {
            String jobParameterName = jobSortParameter.getParameter().name();
            if (jobSortParameter.getSortOrder().isAscending()) {
                return jobParameterName + ASC_SUFFIX;
            } else {
                return jobParameterName + DESC_SUFFIX;
            }
        }).collect(Collectors.joining(","));
    }

    @Override
    public void init(ConnectionInfo connectionInfo) throws Exception {
        HttpClient client = new HttpClientBuilder().insecure(connectionInfo.isInsecure()).useSystemProperties().build();
        SchedulerRestClient restApiClient = new SchedulerRestClient(connectionInfo.getUrl(),
                                                                    new ApacheHttpClient4Engine(client));

        ResteasyProviderFactory factory = ResteasyProviderFactory.getInstance();
        factory.register(new WildCardTypeReader());
        factory.register(new OctetStreamReader());
        factory.register(new TaskResultReader());
        SchedulerRestClient.registerGzipEncoding(factory);

        setApiClient(restApiClient);

        this.connectionInfo = connectionInfo;
        this.initialized = true;

        renewSessionInternal();
    }

    @Override
    public ConnectionInfo getConnectionInfo() {
        return connectionInfo;
    }

    private void checkInitialized() {
        if (!initialized) {
            throw new IllegalStateException("SchedulerClient not initialized.");
        }
    }

    @Override
    public List<JobUsage> getAccountUsage(String user, Date start, Date end)
            throws NotConnectedException, PermissionException {
        List<JobUsage> jobUsages = null;
        try {
            List<JobUsageData> jobUsageDataList = restApi().getUsageOnAccount(sid, user, start, end);
            jobUsages = toJobUsages(jobUsageDataList);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return jobUsages;
    }

    @Override
    public List<JobUsage> getMyAccountUsage(Date startDate, Date endDate)
            throws NotConnectedException, PermissionException {
        List<JobUsage> jobUsages = null;
        try {
            List<JobUsageData> jobUsageDataList = restApi().getUsageOnMyAccount(sid, startDate, endDate);
            jobUsages = toJobUsages(jobUsageDataList);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return jobUsages;
    }

    @Override
    public void changeJobPriority(JobId jobId, JobPriority priority)
            throws NotConnectedException, UnknownJobException, PermissionException, JobAlreadyFinishedException {
        changeJobPriority(jobId.value(), priority);
    }

    @Override
    public void changeJobPriority(String jobId, JobPriority priority)
            throws NotConnectedException, UnknownJobException, PermissionException, JobAlreadyFinishedException {
        try {
            restApi().schedulerChangeJobPriorityByName(sid, jobId, priority.name());
        } catch (Exception e) {
            throwJAFEOrUJEOrNCEOrPE(e);
        }
    }

    @Override
    public void disconnect() throws NotConnectedException, PermissionException {
        try {
            restApi().disconnect(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        initialized = false;
    }

    @Override
    public boolean freeze() throws NotConnectedException, PermissionException {
        boolean success = false;
        try {
            success = restApi().freezeScheduler(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return success;
    }

    @Override
    public JobResult getJobResult(JobId jobId) throws NotConnectedException, PermissionException, UnknownJobException {
        return getJobResult(jobId.value());
    }

    @Override
    public JobResult getJobResult(String jobId) throws NotConnectedException, PermissionException, UnknownJobException {
        JobResult jobResult = null;
        try {
            JobResultData jobResultData = restApi().jobResult(sid, jobId);
            if (jobResultData != null) {
                jobResult = toJobResult(jobResultData);
            }
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return jobResult;
    }

    @Override
    public JobState getJobState(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        JobState jobState = null;
        try {
            JobStateData jobStateData = restApi().listJobs(sid, jobId);
            jobState = toJobState(jobStateData);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return jobState;
    }

    @Override
    public JobState getJobState(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return getJobState(jobId.value());
    }

    @Override
    public TaskState getTaskState(JobId jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        TaskState taskState = null;
        try {
            TaskStateData taskStateData = restApi().jobTask(sid, jobId.toString(), taskName);
            taskState = new TaskStateImpl(taskStateData);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return taskState;
    }

    @Override
    public Page<JobInfo> getJobs(int index, int range, JobFilterCriteria criteria,
            List<SortParameter<JobSortParameter>> jobSortParameters) throws NotConnectedException, PermissionException {
        Page<JobInfo> jobInfos = null;
        try {
            String sortParams = createJobSortParamsString(jobSortParameters);
            RestMapPage<Long, ArrayList<UserJobData>> userJobsAllRevisions = restApi().revisionAndJobsInfo(sid,
                                                                                                           criteria.getJobId(),
                                                                                                           index,
                                                                                                           range,
                                                                                                           criteria.isMyJobsOnly(),
                                                                                                           criteria.isPending(),
                                                                                                           criteria.isRunning(),
                                                                                                           criteria.isFinished(),
                                                                                                           criteria.isWithIssuesOnly(),
                                                                                                           criteria.isChildJobs(),
                                                                                                           criteria.getJobName(),
                                                                                                           criteria.getProjectName(),
                                                                                                           criteria.getBucketName(),
                                                                                                           criteria.getSubmissionMode(),
                                                                                                           criteria.getLabel(),
                                                                                                           criteria.getUserName(),
                                                                                                           criteria.getTenant(),
                                                                                                           criteria.getParentId(),
                                                                                                           sortParams,
                                                                                                           criteria.getStatus() != null ? criteria.getStatus()
                                                                                                                                                  .toString()
                                                                                                                                        : "",
                                                                                                           criteria.getSubmittedTimeLessThan(),
                                                                                                           criteria.getSubmittedTimeGreater(),
                                                                                                           criteria.getStartAtTimeGreater(),
                                                                                                           criteria.getStartAtTimeLessThan());
            List<UserJobData> userJobs = userJobsAllRevisions.getMap().values().iterator().next();
            jobInfos = new Page<JobInfo>(toJobInfos(userJobs), userJobs.size());

        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return jobInfos;
    }

    @Override
    public List<JobInfo> getJobsInfoList(List<String> jobsId) throws PermissionException, NotConnectedException {
        List<JobInfo> jobsInfoList = null;
        try {
            List<UserJobData> userJobDataList = restApi().jobsInfoList(sid, jobsId);
            jobsInfoList = new ArrayList<>(toJobInfos(userJobDataList));
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return jobsInfoList;
    }

    @Override
    public String getJobServerLogs(String jobId)
            throws UnknownJobException, NotConnectedException, PermissionException {
        String jobServerLog = "";
        try {
            jobServerLog = restApi().jobServerLog(sid, jobId);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return jobServerLog;
    }

    @Override
    public SchedulerStatus getStatus() throws NotConnectedException, PermissionException {
        SchedulerStatus status = null;
        try {
            SchedulerStatusData schedulerStatus = restApi().getSchedulerStatus(sid);
            status = SchedulerStatus.valueOf(schedulerStatus.name());
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return status;
    }

    @Override
    public TaskResult getTaskResult(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        TaskResultImpl taskResult = null;
        try {
            TaskResultData taskResultData = restApi().taskResult(sid, jobId, taskName);
            taskResult = (TaskResultImpl) toTaskResult(JobIdImpl.makeJobId(jobId), taskResultData);
            if (taskResult.value() == null) {
                Serializable value = restApi().valueOfTaskResult(sid, jobId, taskName);
                if (value != null) {
                    taskResult.setValue(value);
                }
            }

        } catch (Throwable t) {
            throwUJEOrNCEOrPEOrUTE(exception(t));
        }
        return taskResult;
    }

    @Override
    public TaskResult getTaskResult(JobId jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return getTaskResult(jobId.value(), taskName);
    }

    @Override
    public List<TaskResult> getTaskResultsByTag(JobId jobId, String taskTag)
            throws NotConnectedException, UnknownJobException, PermissionException {
        List<TaskState> taskStates = getJobState(jobId).getTasksByTag(taskTag);
        ArrayList<TaskResult> results = new ArrayList<TaskResult>(taskStates.size());
        for (TaskState currentState : taskStates) {
            String taskName = currentState.getTaskInfo().getName();
            try {
                TaskResult currentResult = getTaskResult(jobId, taskName);
                results.add(currentResult);
            } catch (UnknownTaskException ex) {
                // never occurs because tasks are filtered by tag so they cannot
                // be unknown.
                logger.warn("Unknown task.", ex);
            }
        }
        return results;
    }

    @Override
    public List<TaskResult> getTaskResultsByTag(String jobId, String taskTag)
            throws NotConnectedException, UnknownJobException, PermissionException {
        return this.getTaskResultsByTag(JobIdImpl.makeJobId(jobId), taskTag);
    }

    @Override
    public String getTaskServerLogs(String jobId, String taskName)
            throws UnknownJobException, UnknownTaskException, NotConnectedException, PermissionException {
        String taskLogs = "";
        try {
            taskLogs = restApi().taskLog(sid, jobId, taskName);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return taskLogs;
    }

    @Override
    public String getTaskServerLogsByTag(String jobId, String tag)
            throws UnknownJobException, NotConnectedException, PermissionException {
        String taskLogs = "";
        try {
            taskLogs = restApi().taskLogByTag(sid, jobId, tag);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return taskLogs;
    }

    @Override
    public List<SchedulerUserInfo> getUsers() throws NotConnectedException, PermissionException {
        List<SchedulerUserInfo> schedulerUserInfos = null;
        try {
            List<SchedulerUserData> users = restApi().getUsers(sid);
            schedulerUserInfos = toSchedulerUserInfos(users);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return schedulerUserInfos;
    }

    @Override
    public List<SchedulerUserInfo> getUsersWithJobs() throws NotConnectedException, PermissionException {
        List<SchedulerUserInfo> schedulerUserInfos = null;
        try {
            List<SchedulerUserData> usersWithJobs = restApi().getUsersWithJobs(sid);
            schedulerUserInfos = toSchedulerUserInfos(usersWithJobs);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return schedulerUserInfos;
    }

    @Override
    public FilteredStatistics getFilteredStatistics(String workflowName, String bucketName, Boolean myJobs,
            long startDate, long endDate) throws NotConnectedException, PermissionException {

        try {
            FilteredStatisticsData usersWithJobs = restApi().getFilteredStatistics(sid,
                                                                                   startDate,
                                                                                   endDate,
                                                                                   myJobs,
                                                                                   workflowName,
                                                                                   bucketName);
            return toFilteredStatistics(usersWithJobs);
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public List<FilteredTopWorkflow> getTopWorkflowsWithIssues(int numberOfWorkflows, String workflowName,
            String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {

        try {
            List<FilteredTopWorkflowData> filteredWorkflows = restApi().getTopWorkflowsWithIssues(sid,
                                                                                                  numberOfWorkflows,
                                                                                                  startDate,
                                                                                                  endDate,
                                                                                                  myJobs,
                                                                                                  workflowName,
                                                                                                  bucketName);
            return new ArrayList<>(DataUtility.toFilteredTopWorkflowsWithIssues(filteredWorkflows));
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public List<FilteredTopWorkflowsCumulatedCoreTime> getTopWorkflowsCumulatedCoreTime(int numberOfWorkflows,
            String workflowName, String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {

        try {
            List<FilteredTopWorkflowsCumulatedCoreTimeData> filteredWorkflows = restApi().getTopWorkflowsCumulatedCoreTime(sid,
                                                                                                                           numberOfWorkflows,
                                                                                                                           startDate,
                                                                                                                           endDate,
                                                                                                                           myJobs,
                                                                                                                           workflowName,
                                                                                                                           bucketName);
            return new ArrayList<>(DataUtility.toFilteredTopCumulatedCoreTime(filteredWorkflows));
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public List<FilteredTopWorkflowsNumberOfNodes> getTopWorkflowsNumberOfNodes(int numberOfWorkflows,
            String workflowName, String bucketName, boolean myJobs, long startDate, long endDate, boolean inParallel)
            throws NotConnectedException, PermissionException {

        try {
            List<FilteredTopWorkflowsNumberOfNodesData> filteredWorkflows = restApi().getTopWorkflowsNumberOfNodes(sid,
                                                                                                                   numberOfWorkflows,
                                                                                                                   startDate,
                                                                                                                   endDate,
                                                                                                                   myJobs,
                                                                                                                   workflowName,
                                                                                                                   bucketName,
                                                                                                                   inParallel);
            return new ArrayList<>(DataUtility.tFilteredTopWorkflowsNumberOfNodes(filteredWorkflows));
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public List<WorkflowDuration> getTopExecutionTimeWorkflows(int numberOfWorkflows, String workflowName,
            String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {

        try {
            List<WorkflowDurationData> filteredWorkflows = restApi().getTopExecutionTimeWorkflows(sid,
                                                                                                  numberOfWorkflows,
                                                                                                  startDate,
                                                                                                  endDate,
                                                                                                  myJobs,
                                                                                                  workflowName,
                                                                                                  bucketName);
            return new ArrayList<>(DataUtility.toWorkflowsDuration(filteredWorkflows));
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public List<WorkflowDuration> getTopPendingTimeWorkflows(int numberOfWorkflows, String workflowName,
            String bucketName, Boolean myJobs, long startDate, long endDate)
            throws NotConnectedException, PermissionException {

        try {
            List<WorkflowDurationData> filteredWorkflows = restApi().getTopPendingTimeWorkflows(sid,
                                                                                                numberOfWorkflows,
                                                                                                startDate,
                                                                                                endDate,
                                                                                                myJobs,
                                                                                                workflowName,
                                                                                                bucketName);
            return new ArrayList<>(DataUtility.toWorkflowsDuration(filteredWorkflows));
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public Map<String, Integer> getSubmissionModeCount(String workflowName, String bucketName, Boolean myJobs,
            long startDate, long endDate) throws NotConnectedException, PermissionException {

        try {
            return restApi().getSubmissionModeCount(sid, startDate, endDate, myJobs, workflowName, bucketName);
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public CompletedJobsCount getCompletedJobs(Boolean myJobs, String workflowName, String bucketName, long startDate,
            long endDate, int numberOfIntervals) throws NotConnectedException, PermissionException {

        try {
            CompletedJobsCountData completedJob = restApi().getCompletedJobs(sid,
                                                                             myJobs,
                                                                             workflowName,
                                                                             bucketName,
                                                                             startDate,
                                                                             endDate,
                                                                             numberOfIntervals);
            return DataUtility.toCompletedJobsCount(completedJob);
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public CompletedTasksCount getCompletedTasks(Boolean myTasks, String taskName, long startDate, long endDate,
            int numberOfIntervals) throws NotConnectedException, PermissionException {

        try {
            CompletedTasksCountData completedTask = restApi().getCompletedTasks(sid,
                                                                                myTasks,
                                                                                taskName,
                                                                                startDate,
                                                                                endDate,
                                                                                numberOfIntervals);
            return DataUtility.toCompletedTasksCount(completedTask);
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public Set<String> getSubmissionModeValues() throws NotConnectedException, PermissionException {

        try {
            return restApi().getSubmissionModeValues(sid);
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public boolean isConnected() {
        boolean isConnected = false;
        if (initialized) {
            try {
                isConnected = restApi().isConnected(sid);
            } catch (Throwable e) {
                // ignore
            }
        }
        return isConnected;
    }

    @Override
    public String getCurrentPolicy() throws NotConnectedException, PermissionException {
        return getCurrentPolicy();
    }

    @Override
    public Map getJobsToSchedule() throws NotConnectedException, PermissionException {
        return getJobsToSchedule();
    }

    @Override
    public List<TaskDescriptor> getTasksToSchedule() throws NotConnectedException, PermissionException {
        return getTasksToSchedule();
    }

    @Override
    public boolean kill() throws NotConnectedException, PermissionException {
        boolean isKilled = false;
        try {
            isKilled = restApi().killScheduler(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isKilled;
    }

    @Override
    public boolean killJob(JobId jobId) throws NotConnectedException, PermissionException {
        return killJob(jobId.value());
    }

    @Override
    public boolean killJob(String jobId) throws NotConnectedException, PermissionException {
        boolean isJobKilled = false;
        try {
            isJobKilled = restApi().killJob(sid, jobId);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isJobKilled;
    }

    @Override
    public boolean killJobs(List<String> jobsId) throws NotConnectedException, PermissionException {
        boolean isJobKilled = false;
        try {
            isJobKilled = restApi().killJobs(sid, jobsId);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isJobKilled;
    }

    @Override
    public boolean killTask(JobId jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return killTask(jobId.value(), taskName);
    }

    @Override
    public boolean killTask(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        boolean isTaskKilled = false;
        try {
            isTaskKilled = restApi().killTask(sid, jobId, taskName);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return isTaskKilled;
    }

    @Override
    public boolean linkResourceManager(String rmUrl) throws NotConnectedException, PermissionException {
        boolean isLinked = false;
        try {
            isLinked = restApi().linkRm(sid, rmUrl);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isLinked;
    }

    @Override
    public boolean pause() throws NotConnectedException, PermissionException {
        boolean isSchedulerPaused = false;
        try {
            isSchedulerPaused = restApi().pauseScheduler(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isSchedulerPaused;
    }

    @Override
    public boolean pauseJob(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return pauseJob(jobId.value());
    }

    @Override
    public boolean pauseJob(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        boolean isJobPaused = false;
        try {
            isJobPaused = restApi().pauseJob(sid, jobId);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return isJobPaused;
    }

    @Override
    public boolean restartAllInErrorTasks(String jobId)
            throws NotConnectedException, UnknownJobException, PermissionException {
        boolean isAllInErrorTasksRestarted = false;
        try {
            isAllInErrorTasksRestarted = restApi().restartAllInErrorTasks(sid, jobId);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return isAllInErrorTasksRestarted;
    }

    @Override
    public boolean preemptTask(JobId jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return preemptTask(jobId.value(), taskName, restartDelay);
    }

    @Override
    public boolean preemptTask(String jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        boolean isTaskPreempted = false;
        try {
            isTaskPreempted = restApi().preemptTask(sid, jobId, taskName);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return isTaskPreempted;
    }

    @Override
    public boolean removeJob(JobId jobId) throws NotConnectedException, PermissionException {
        return removeJob(jobId.value());
    }

    @Override
    public boolean removeJobs(List<JobId> jobIds) throws NotConnectedException, PermissionException {
        boolean isAllJobsRemoved = false;
        try {
            isAllJobsRemoved = restApi().removeJobs(sid,
                                                    jobIds.stream().map(JobId::value).collect(Collectors.toList()),
                                                    0);
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return isAllJobsRemoved;
    }

    @Override
    public boolean removeJobs(long olderThan) throws NotConnectedException, PermissionException {
        boolean isAllJobsRemoved = false;
        try {
            isAllJobsRemoved = restApi().removeJobs(sid, null, olderThan);
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return isAllJobsRemoved;
    }

    @Override
    public boolean removeJob(String jobId) throws NotConnectedException, PermissionException {
        boolean isJobRemoved = false;
        try {
            isJobRemoved = restApi().removeJob(sid, jobId);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isJobRemoved;
    }

    @Override
    public boolean restartTask(JobId jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        return restartTask(jobId.value(), taskName, restartDelay);
    }

    @Override
    public boolean restartTask(String jobId, String taskName, int restartDelay)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        boolean isTaskRestarted = false;
        try {
            isTaskRestarted = restApi().restartTask(sid, jobId, taskName);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return isTaskRestarted;
    }

    @Override
    public boolean finishInErrorTask(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        boolean result = false;
        try {
            result = restApi().finishInErrorTask(sid, jobId, taskName);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return result;
    }

    @Override
    public boolean restartInErrorTask(String jobId, String taskName)
            throws NotConnectedException, UnknownJobException, UnknownTaskException, PermissionException {
        boolean result = false;
        try {
            result = restApi().restartInErrorTask(sid, jobId, taskName);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return result;
    }

    @Override
    public boolean resume() throws NotConnectedException, PermissionException {
        boolean isResumed = false;
        try {
            isResumed = restApi().resumeScheduler(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isResumed;
    }

    @Override
    public boolean resumeJob(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return resumeJob(jobId.value());
    }

    @Override
    public boolean resumeJob(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        boolean isJobResumed = false;
        try {
            isJobResumed = restApi().resumeJob(sid, jobId);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return isJobResumed;
    }

    @Override
    public boolean shutdown() throws NotConnectedException, PermissionException {
        boolean isShutdown = false;
        try {
            isShutdown = restApi().shutdownScheduler(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isShutdown;
    }

    @Override
    public boolean start() throws NotConnectedException, PermissionException {
        boolean success = false;
        try {
            success = restApi().startScheduler(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return success;
    }

    @Override
    public boolean stop() throws NotConnectedException, PermissionException {
        boolean isStopped = false;
        try {
            isStopped = restApi().stopScheduler(sid);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return isStopped;
    }

    @Override
    public JobId submit(Job job)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        JobIdData jobIdData = null;
        try {
            Map<String, String> genericInformation = setSubmissionModeToGenericInfo(job.getGenericInformation());
            InputStream is = (new Job2XMLTransformer()).jobToxml((TaskFlowJob) job);
            jobIdData = restApiClient().submitXml(sid, is, null, genericInformation);
        } catch (Exception e) {
            throwNCEOrPEOrSCEOrJCE(e);
        }
        return jobId(jobIdData);
    }

    @Override
    public List<JobIdDataAndError> submit(List<Job> jobs) throws NotConnectedException {
        List<JobIdDataAndError> jobIdDataAndErrors = new ArrayList<>(jobs.size());
        for (Job job : jobs) {
            try {
                job.setGenericInformation(new LinkedHashMap<>(setSubmissionModeToGenericInfo(job.getGenericInformation())));
                InputStream is = (new Job2XMLTransformer()).jobToxml((TaskFlowJob) job);
                JobIdData jobIdData = restApiClient().submitXml(sid, is);
                jobIdDataAndErrors.add(new JobIdDataAndError(jobIdData.getId(), jobIdData.getReadableName()));
            } catch (Exception e) {
                jobIdDataAndErrors.add(new JobIdDataAndError(e.getMessage(), StackTraceUtil.getStackTrace(e)));
            }
        }
        return jobIdDataAndErrors;
    }

    @Override
    public JobId submit(File job)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        JobIdData jobIdData = null;
        Map<String, String> genericInfos = setSubmissionModeToGenericInfo(null);
        try (InputStream is = new FileInputStream(job)) {
            jobIdData = restApiClient().submitXml(sid, is, null, genericInfos);
        } catch (Exception e) {
            throwNCEOrPEOrSCEOrJCE(e);
        }
        return jobId(jobIdData);
    }

    @Override
    public JobId reSubmit(JobId currentJobId, Map<String, String> jobVariables, Map<String, String> jobGenericInfos,
            String sessionId) throws NotConnectedException {
        final JobIdData jobIdData;
        try {
            jobGenericInfos = new LinkedHashMap<>(setSubmissionModeToGenericInfo(jobGenericInfos));
            jobIdData = restApiClient().reSubmit(sid, currentJobId.value(), jobVariables, jobGenericInfos);
        } catch (NotConnectedRestException e) {
            throw new NotConnectedException(e);
        }
        return jobId(jobIdData);
    }

    @Override
    public JobId submit(File job, Map<String, String> variables)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        return submit(job, variables, null);
    }

    @Override
    public JobId submit(File job, Map<String, String> variables, Map<String, String> genericInfos)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        JobIdData jobIdData = null;
        genericInfos = new LinkedHashMap<>(setSubmissionModeToGenericInfo(genericInfos));
        try (InputStream is = new FileInputStream(job)) {
            jobIdData = restApiClient().submitXml(sid, is, variables, genericInfos);
        } catch (Exception e) {
            throwNCEOrPEOrSCEOrJCE(e);
        }
        return jobId(jobIdData);
    }

    @Override
    public JobId submit(URL job)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        return this.submit(job, null, null);
    }

    @Override
    public JobId submit(URL job, Map<String, String> variables)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        return this.submit(job, variables, null);
    }

    @Override
    public JobId submit(Map<String, String> genericInfos, URL job, Map<String, String> variables)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        return this.submit(job, variables, genericInfos, null);
    }

    @Override
    public JobId submit(URL job, Map<String, String> variables, Map<String, String> requestHeaderParams)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        return submit(job, variables, null, requestHeaderParams);
    }

    @Override
    public JobId submit(URL job, Map<String, String> variables, Map<String, String> genericInfos,
            Map<String, String> requestHeaderParams)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        JobIdData jobIdData = null;
        try {
            URLConnection urlConnection = job.openConnection();

            if (requestHeaderParams != null) {
                for (Map.Entry<String, String> requestHeaderEntry : requestHeaderParams.entrySet()) {
                    urlConnection.addRequestProperty(requestHeaderEntry.getKey(), requestHeaderEntry.getValue());
                }
            }
            InputStream is = urlConnection.getInputStream();
            genericInfos = new LinkedHashMap<>(setSubmissionModeToGenericInfo(genericInfos));
            jobIdData = restApiClient().submitXml(sid, is, variables, genericInfos);
        } catch (Exception e) {
            throwNCEOrPEOrSCEOrJCE(e);
        }
        return jobId(jobIdData);
    }

    @Override
    public JobId submitFromCatalog(String catalogRestURL, String bucketName, String workflowName)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {
        return this.submitFromCatalog(catalogRestURL, bucketName, workflowName, null);

    }

    @Override
    public JobId submitFromCatalog(String catalogRestURL, String bucketName, String workflowName,
            Map<String, String> variables)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {

        return submitFromCatalog(catalogRestURL, bucketName, workflowName, variables, null);

    }

    @Override
    public JobId submitFromCatalog(String catalogRestURL, String bucketName, String workflowName,
            Map<String, String> variables, Map<String, String> genericInfo)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {

        String objectUrl = catalogRestURL + "/buckets/" + UrlEscapers.urlPathSegmentEscaper().escape(bucketName) +
                           "/resources/" + UrlEscapers.urlPathSegmentEscaper().escape(workflowName) + "/raw";
        return submitFromCatalog(objectUrl, variables, genericInfo);

    }

    @Override
    public JobId submitFromCatalog(String catalogRestURL, String calledWorkflow)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {

        return submitFromCatalog(catalogRestURL, calledWorkflow, new HashMap<>());

    }

    @Override
    public JobId submitFromCatalog(String catalogRestURL, String calledWorkflow, Map<String, String> variables)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {

        return submitFromCatalog(catalogRestURL,
                                 getBucketFromCalledWorkflow(calledWorkflow),
                                 getNameFromCalledWorkflow(calledWorkflow),
                                 variables,
                                 null);
    }

    @Override
    public JobId submitFromCatalog(String catalogRestURL, String calledWorkflow, Map<String, String> variables,
            Map<String, String> genericInfo)
            throws NotConnectedException, PermissionException, SubmissionClosedException, JobCreationException {

        Optional<String> revision = getRevisionFromCalledWorkflow(calledWorkflow);

        String objectUrl = catalogRestURL + "/buckets/" +
                           UrlEscapers.urlPathSegmentEscaper().escape(getBucketFromCalledWorkflow(calledWorkflow)) +
                           "/resources/" +
                           UrlEscapers.urlPathSegmentEscaper().escape(getNameFromCalledWorkflow(calledWorkflow)) +
                           revision.map(r -> "/revisions/" + UrlEscapers.urlPathSegmentEscaper().escape(r)).orElse("") +
                           "/raw";

        return this.submitFromCatalog(objectUrl, variables, genericInfo);
    }

    @Override
    public List<JobIdDataAndError> multipleSubmitFromUrls(List<WorkflowUrlData> workflowUrlDataList)
            throws NotConnectedException, PermissionException {
        List<JobIdDataAndError> answer = null;
        try {
            workflowUrlDataList.forEach(workflowUrlData -> workflowUrlData.setGenericInformation(new LinkedHashMap<>(setSubmissionModeToGenericInfo(workflowUrlData.getGenericInformation()))));
            answer = restApiClient().submitMultipleUrl(sid, workflowUrlDataList);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return answer;
    }

    @Override
    public boolean isJobFinished(JobId jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        return isJobFinished(jobId.toString());
    }

    @Override
    public boolean isJobFinished(String jobId) throws NotConnectedException, UnknownJobException, PermissionException {
        boolean answer = false;
        try {
            answer = !getJobInfo(jobId).getStatus().isJobAlive();
        } catch (SchedulerException e) {
            throwUJEOrNCEOrPE(e);
        }
        return answer;
    }

    @Override
    public JobResult waitForJob(JobId jobId, long timeout)
            throws NotConnectedException, UnknownJobException, PermissionException, TimeoutException {
        return waitForJob(jobId.value(), timeout);
    }

    @Override
    public JobResult waitForJob(String jobId, long timeout)
            throws NotConnectedException, UnknownJobException, PermissionException, TimeoutException {
        timeout += currentTimeMillis();
        while (currentTimeMillis() < timeout) {
            if (isJobFinished(jobId)) {
                JobResult result = getJobResult(jobId);
                if (result == null) {
                    throw new IllegalStateException("Result for job " + jobId + " not found");
                }
                return result;
            }
            if (currentTimeMillis() + RETRY_INTERVAL < timeout) {
                sleep(RETRY_INTERVAL);
            } else {
                break;
            }
        }
        throw new TimeoutException(format("Timeout waiting for the job: job-id=%s", jobId));
    }

    @Override
    public boolean isTaskFinished(String jobId, String taskName)
            throws UnknownJobException, NotConnectedException, PermissionException, UnknownTaskException {
        boolean finished = false;
        try {
            TaskStateData taskStateData = restApi().jobTask(sid, jobId, taskName);
            TaskState taskState = taskState(taskStateData);
            finished = !taskState.getStatus().isTaskAlive();
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
        return finished;
    }

    @Override
    public TaskResult waitForTask(String jobId, String taskName, long timeout) throws UnknownJobException,
            NotConnectedException, PermissionException, UnknownTaskException, TimeoutException {
        timeout += currentTimeMillis();
        while (currentTimeMillis() < timeout) {
            if (isTaskFinished(jobId, taskName)) {
                return getTaskResult(jobId, taskName);
            }
            if (currentTimeMillis() + RETRY_INTERVAL < timeout) {
                sleep(RETRY_INTERVAL);
            } else {
                break;
            }
        }
        throw new TimeoutException(format("Timeout waiting for the task: job-id=%s, task-id=%s", jobId, taskName));
    }

    @Override
    public List<JobResult> waitForAllJobs(List<String> jobIds, long timeout)
            throws NotConnectedException, UnknownJobException, PermissionException, TimeoutException {
        long timestamp = 0;
        List<JobResult> results = new ArrayList<>(jobIds.size());
        for (String jobId : jobIds) {
            timestamp = currentTimeMillis();
            results.add(waitForJob(jobId, timeout));
            timeout = timeout - (currentTimeMillis() - timestamp);
        }
        return results;
    }

    @Override
    public Map.Entry<String, JobResult> waitForAnyJob(List<String> jobIds, long timeout)
            throws NotConnectedException, UnknownJobException, PermissionException, TimeoutException {
        timeout += currentTimeMillis();
        while (currentTimeMillis() < timeout) {
            for (String jobId : jobIds) {
                if (isJobFinished(jobId)) {
                    return toEntry(jobId, getJobResult(jobId));
                }
            }
            if (currentTimeMillis() + RETRY_INTERVAL < timeout) {
                sleep(RETRY_INTERVAL);
            } else {
                break;
            }
        }
        throw new TimeoutException(format("Timeout waiting for any job: jobIds=%s.", String.valueOf(jobIds)));
    }

    @Override
    public Entry<String, TaskResult> waitForAnyTask(String jobId, List<String> taskNames, long timeout)
            throws UnknownJobException, NotConnectedException, PermissionException, UnknownTaskException,
            TimeoutException {
        timeout += currentTimeMillis();
        while (currentTimeMillis() < timeout) {
            for (String taskName : taskNames) {
                if (isTaskFinished(jobId, taskName)) {
                    return toEntry(taskName, getTaskResult(jobId, taskName));
                }
            }
            if (currentTimeMillis() + RETRY_INTERVAL < timeout) {
                sleep(RETRY_INTERVAL);
            } else {
                break;
            }
        }
        throw new TimeoutException(format("Timeout waiting for any task: job-id=%s, task-ids=%s.",
                                          jobId,
                                          String.valueOf(taskNames)));
    }

    @Override
    public List<Entry<String, TaskResult>> waitForAllTasks(String jobId, List<String> taskNames, long timeout)
            throws UnknownJobException, NotConnectedException, PermissionException, UnknownTaskException,
            TimeoutException {
        long timestamp = 0;
        List<Map.Entry<String, TaskResult>> taskResults = new ArrayList<>(taskNames.size());
        for (String taskName : taskNames) {
            timestamp = currentTimeMillis();
            Entry<String, TaskResult> taskResultEntry = toEntry(taskName, waitForTask(jobId, taskName, timeout));
            taskResults.add(taskResultEntry);
            timeout = timeout - (currentTimeMillis() - timestamp);
        }
        return taskResults;
    }

    @Override
    public boolean pushFile(String spacename, String pathname, String filename, String file)
            throws NotConnectedException, PermissionException {
        boolean uploaded = false;
        try (FileInputStream inputStream = new FileInputStream(file)) {
            uploaded = restApiClient().pushFile(sid, spacename, pathname, filename, inputStream);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return uploaded;
    }

    @Override
    public void pullFile(String space, String pathname, String outputFile)
            throws NotConnectedException, PermissionException {
        try {
            restApiClient().pullFile(sid, space, pathname, outputFile);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
    }

    @Override
    public boolean deleteFile(String space, String pathname) throws NotConnectedException, PermissionException {
        boolean deleted = false;
        try {
            deleted = restApi().deleteFile(sid, space, pathname);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return deleted;
    }

    @Override
    public List<String> getUserSpaceURIs() throws NotConnectedException, PermissionException {
        try {
            return restApi().userspaceURIs(sid);
        } catch (Exception error) {
            throw throwNCEOrPE(error);
        }
    }

    @Override
    public List<String> getGlobalSpaceURIs() throws NotConnectedException, PermissionException {
        try {
            return restApi().globalspaceURIs(sid);
        } catch (Exception error) {
            throw throwNCEOrPE(error);
        }
    }

    public void renewSessionInternal() throws SchedulerException, LoginException, KeyException {
        Closer closer = Closer.create();
        try {
            LoginForm loginForm = new LoginForm();
            loginForm.setUsername(Strings.isNullOrEmpty(connectionInfo.getDomain()) ? connectionInfo.getLogin()
                                                                                    : connectionInfo.getDomain() +
                                                                                      "\\" + connectionInfo.getLogin());
            loginForm.setPassword(connectionInfo.getPassword());
            if (connectionInfo.getCredentialFile() != null) {
                FileInputStream inputStream = new FileInputStream(connectionInfo.getCredentialFile());
                closer.register(inputStream);
                loginForm.setCredential(inputStream);
            }
            sid = restApi().loginOrRenewSession(sid, loginForm);

        } catch (Exception e) {
            throwNCEOrKEOrLEOrSE(e);
        } finally {
            try {
                closer.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    @Override
    public void renewSession() throws NotConnectedException {
        try {
            renewSessionInternal();
        } catch (LoginException | SchedulerException | KeyException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setSession(String sid) {
        this.sid = sid;
    }

    @Override
    public String getSession() {
        return sid;
    }

    @Override
    public void addEventListener(SchedulerEventListener listener, boolean myEventsOnly, SchedulerEvent... events)
            throws NotConnectedException, PermissionException {
        try {
            removeEventListener();
            schedulerEventReceiver = (new SchedulerEventReceiver.Builder()).restServerUrl(connectionInfo.getUrl())
                                                                           .insecure(connectionInfo.isInsecure())
                                                                           .sessionId(sid)
                                                                           .schedulerEventListener(listener)
                                                                           .myEventsOnly(myEventsOnly)
                                                                           .selectedEvents(events)
                                                                           .build();
            schedulerEventReceiver.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeEventListener() throws NotConnectedException, PermissionException {
        if (schedulerEventReceiver != null) {
            schedulerEventReceiver.stop();
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            // ignore
        }
    }

    private SchedulerRestInterface restApi() {
        checkInitialized();
        return schedulerRestClient.getScheduler();
    }

    private void setApiClient(SchedulerRestClient schedulerRestClient) {
        this.schedulerRestClient = schedulerRestClient;
    }

    private SchedulerRestClient restApiClient() {
        return schedulerRestClient;
    }

    private <K, V> Map.Entry<K, V> toEntry(final K k, final V v) {
        return new AbstractMap.SimpleEntry<>(k, v);

    }

    @Override
    public void putThirdPartyCredential(String key, String value) throws SchedulerException {
        try {
            restApi().putThirdPartyCredential(sid, key, value);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public Set<String> thirdPartyCredentialsKeySet() throws SchedulerException {
        try {
            return restApi().thirdPartyCredentialsKeySet(sid);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public void removeThirdPartyCredential(String key) throws SchedulerException {
        try {
            restApi().removeThirdPartyCredential(sid, key);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public Page<TaskId> getTaskIds(String taskTag, long from, long to, boolean mytasks, Set<TaskStatus> taskStatuses,
            int offset, int limit) throws SchedulerException {
        RestPage<TaskStateData> page = null;
        try {
            page = restApi().getTaskStates(sid, from, to, mytasks, statusesToString(taskStatuses), offset, limit, null);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
        List<TaskId> lTaskIds = new ArrayList<TaskId>(page.getList().size());
        for (TaskStateData taskStateData : page.getList()) {
            TaskInfoData taskInfo = taskStateData.getTaskInfo();
            TaskIdData taskIdData = taskInfo.getTaskId();
            JobId jobId = new JobIdImpl(taskInfo.getJobId().getId(), taskInfo.getJobId().getReadableName());
            TaskId taskId = TaskIdImpl.createTaskId(jobId, taskIdData.getReadableName(), taskIdData.getId());
            lTaskIds.add(taskId);
        }
        return new Page<TaskId>(lTaskIds, page.getSize());
    }

    @Override
    public Page<TaskState> getTaskStates(String taskTag, long from, long to, boolean mytasks,
            Set<TaskStatus> statusFilter, int offset, int limit, SortSpecifierContainer sortParams)
            throws SchedulerException {
        RestPage<TaskStateData> page = null;
        SortSpecifierContainer sortContainer = new SortSpecifierContainer(sortParams.toString());
        try {
            page = restApi().getTaskStates(sid,
                                           from,
                                           to,
                                           mytasks,
                                           TaskStatus.statusFilterString(statusFilter),
                                           offset,
                                           limit,
                                           sortContainer);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
        List<TaskState> lTaskStates = new ArrayList<TaskState>(page.getList().size());
        for (TaskStateData taskStateData : page.getList()) {
            lTaskStates.add(new TaskStateImpl(taskStateData));
        }
        return new Page<TaskState>(lTaskStates, page.getSize());
    }

    @Override
    public JobInfo getJobInfo(String jobId) throws SchedulerException {
        JobInfoData jobInfoData = null;
        try {
            jobInfoData = restApi().jobInfo(sid, jobId);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
        JobInfoImpl jobInfoImpl = new JobInfoImpl();
        JobId newJobId = JobIdImpl.makeJobId(jobId);
        jobInfoImpl.setJobId(newJobId);
        jobInfoImpl.setJobOwner(jobInfoData.getJobOwner());
        jobInfoImpl.setTenant(jobInfoData.getTenant());
        jobInfoImpl.setDomain(jobInfoData.getDomain());
        jobInfoImpl.setProjectName(jobInfoData.getProjectName());
        jobInfoImpl.setBucketName(jobInfoData.getBucketName());
        jobInfoImpl.setDescription(jobInfoData.getDescription());
        jobInfoImpl.setNumberOfNodes(jobInfoData.getNumberOfNodes());
        jobInfoImpl.setNumberOfNodesInParallel(jobInfoData.getNumberOfNodesInParallel());
        jobInfoImpl.setRemovedTime(jobInfoData.getRemovedTime());
        jobInfoImpl.setStartTime(jobInfoData.getStartTime());
        jobInfoImpl.setFinishedTime(jobInfoData.getFinishedTime());
        jobInfoImpl.setInErrorTime(jobInfoData.getInErrorTime());
        jobInfoImpl.setSubmittedTime(jobInfoData.getSubmittedTime());
        jobInfoImpl.setCumulatedCoreTime(jobInfoData.getCumulatedCoreTime());
        jobInfoImpl.setNumberOfFinishedTasks(jobInfoData.getNumberOfFinishedTasks());
        jobInfoImpl.setNumberOfPendingTasks(jobInfoData.getNumberOfPendingTasks());
        jobInfoImpl.setNumberOfRunningTasks(jobInfoData.getNumberOfRunningTasks());
        jobInfoImpl.setNumberOfInErrorTasks(jobInfoData.getNumberOfInErrorTasks());
        jobInfoImpl.setNumberOfFaultyTasks(jobInfoData.getNumberOfFaultyTasks());
        jobInfoImpl.setNumberOfFailedTasks(jobInfoData.getNumberOfFailedTasks());
        jobInfoImpl.setTotalNumberOfTasks(jobInfoData.getTotalNumberOfTasks());
        jobInfoImpl.setJobPriority(JobPriority.findPriority(jobInfoData.getPriority().toString()));
        jobInfoImpl.setJobStatus(JobStatus.findStatus(jobInfoData.getStatus().toString()));
        if (jobInfoData.isToBeRemoved()) {
            jobInfoImpl.setToBeRemoved();
        }
        jobInfoImpl.setPreciousTasks(jobInfoData.getPreciousTasks());
        jobInfoImpl.setGenericInformation(jobInfoData.getGenericInformation());
        jobInfoImpl.setVariables(jobInfoData.getVariables());
        jobInfoImpl.setDetailedVariables(jobInfoData.getDetailedVariables());
        jobInfoImpl.setSignals(jobInfoData.getSignals());
        jobInfoImpl.setDetailedSignals(jobInfoData.getDetailedSignals());
        jobInfoImpl.setVisualizationConnectionStrings(jobInfoData.getVisualizationConnectionStrings());
        jobInfoImpl.setVisualizationIcons(jobInfoData.getVisualizationIcons());
        jobInfoImpl.setAttachedServices(jobInfoData.getAttachedServices());
        jobInfoImpl.setExternalEndpointUrls(jobInfoData.getExternalEndpointUrls());
        jobInfoImpl.setParentId(jobInfoData.getParentId());
        jobInfoImpl.setChildrenCount(jobInfoData.getChildrenCount());
        jobInfoImpl.setResultMapPresent(jobInfoData.isResultMapPresent());
        jobInfoImpl.setLabel(jobInfoData.getLabel());
        jobInfoImpl.setSubmissionMode(jobInfoData.getSubmissionMode());
        jobInfoImpl.setStartAt(jobInfoData.getStartAt());
        return jobInfoImpl;
    }

    @Override
    public boolean changeStartAt(JobId jobId, String startAt)
            throws NotConnectedException, UnknownJobException, PermissionException {
        boolean isJobStartAtChanged = false;
        try {
            isJobStartAtChanged = restApi().changeStartAt(sid, jobId.value(), startAt);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return isJobStartAtChanged;
    }

    @Override
    public boolean changeStartAt(List<JobId> jobIdList, String startAt)
            throws NotConnectedException, UnknownJobException, PermissionException {
        boolean isJobStartAtChanged = false;
        try {
            List<String> jobIds = jobIdList.stream().map(jobId -> jobId.value()).collect(Collectors.toList());
            isJobStartAtChanged = restApi().changeStartAtMultiple(sid, startAt, jobIds);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return isJobStartAtChanged;
    }

    @Override
    public String getJobContent(JobId jobId) throws SchedulerException {
        try {
            return restApi().getJobContent(sid, jobId.value());
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public void enableRemoteVisualization(String jobId, String taskName, String connectionString)
            throws NotConnectedException, PermissionException, UnknownJobException, UnknownTaskException {
        try {
            restApi().enableRemoteVisualization(sid, jobId, taskName, connectionString);
        } catch (Exception e) {
            throwUJEOrNCEOrPEOrUTE(e);
        }
    }

    @Override
    public void registerService(String jobId, int serviceInstanceid, boolean enableActions)
            throws NotConnectedException, PermissionException, UnknownJobException {
        try {
            restApi().registerService(sid, jobId, serviceInstanceid, enableActions);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
    }

    @Override
    public void detachService(String jobId, int serviceInstanceid)
            throws NotConnectedException, PermissionException, UnknownJobException {
        try {
            restApi().detachService(sid, jobId, serviceInstanceid);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
    }

    @Override
    public Map<Object, Object> getPortalConfiguration() throws SchedulerException {
        try {
            return restApi().getPortalConfiguration(sid);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public String getCurrentUser() throws NotConnectedException {

        String connectedUser = restApi().getLoginFromSessionId(sid);
        if ("".equals(connectedUser)) {
            throw new NotConnectedException("Session " + sid + " is not connected");
        }
        return connectedUser;
    }

    @Override
    public UserData getCurrentUserData() throws NotConnectedException {

        UserData connectedUserData = restApi().getUserDataFromSessionId(sid);
        if (connectedUserData == null) {
            throw new NotConnectedException("Session " + sid + " is not connected");
        }
        return connectedUserData;
    }

    @Override
    public Subject getSubject() throws NotConnectedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> getSchedulerProperties() throws SchedulerException {

        try {
            return restApi().getSchedulerPropertiesFromSessionId(sid);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public TaskStatesPage getTaskPaginated(String jobId, int offset, int limit)
            throws NotConnectedException, UnknownJobException, PermissionException {
        TaskStatesPage taskStatesPage = null;
        try {
            final int size = restApi().getJobTaskStates(sid, jobId).getList().size();
            List<TaskState> taskStates = restApi().getJobTaskStatesPaginated(sid, jobId, offset, limit)
                                                  .getList()
                                                  .stream()
                                                  .map(DataUtility::taskState)
                                                  .collect(Collectors.toList());
            taskStatesPage = new TaskStatesPage();
            taskStatesPage.setSize(size);
            taskStatesPage.setTaskStates(taskStates);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return taskStatesPage;
    }

    @Override
    public TaskStatesPage getTaskPaginated(String jobId, String statusFilter, int offset, int limit)
            throws NotConnectedException, UnknownJobException, PermissionException {
        TaskStatesPage taskStatesPage = null;
        try {
            final int size = restApi().getJobTaskStates(sid, jobId).getList().size();
            List<TaskState> taskStates = restApi().getJobTaskStatesFilteredPaginated(sid,
                                                                                     jobId,
                                                                                     offset,
                                                                                     limit,
                                                                                     statusFilter)
                                                  .getList()
                                                  .stream()
                                                  .map(DataUtility::taskState)
                                                  .collect(Collectors.toList());
            taskStatesPage = new TaskStatesPage();
            taskStatesPage.setSize(size);
            taskStatesPage.setTaskStates(taskStates);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return taskStatesPage;
    }

    @Override
    public List<TaskResult> getPreciousTaskResults(String jobId)
            throws NotConnectedException, PermissionException, UnknownJobException {
        List<TaskState> taskStates = getJobState(jobId).getTasks()
                                                       .stream()
                                                       .filter(Task::isPreciousResult)
                                                       .collect(Collectors.toList());
        ArrayList<TaskResult> results = new ArrayList<>(taskStates.size());
        for (TaskState currentState : taskStates) {
            String taskName = currentState.getTaskInfo().getName();
            try {
                TaskResult currentResult = getTaskResult(jobId, taskName);
                results.add(currentResult);
            } catch (UnknownTaskException ex) {
                // never occurs because tasks are filtered by tag so they cannot
                // be unknown.
                logger.warn("Unknown task.", ex);
            }
        }
        return results;
    }

    @Override
    public Map<Long, Map<String, Serializable>> getJobResultMaps(List<String> jobsId)
            throws NotConnectedException, UnknownJobException, PermissionException {
        Map<Long, Map<String, Serializable>> result = new HashMap<>();
        try {
            Map<Long, Map<String, String>> map = restApi().jobResultMaps(sid, jobsId);
            for (Entry<Long, Map<String, String>> entry : map.entrySet()) {
                Map<String, Serializable> resultMap = new HashMap<>();
                for (Entry<String, String> entryInEntry : entry.getValue().entrySet()) {
                    resultMap.put(entryInEntry.getKey(), entryInEntry.getValue());
                }

                result.put(entry.getKey(), resultMap);
            }
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
        return result;
    }

    @Override
    public Map<Long, List<String>> getPreciousTaskNames(List<String> jobsId) throws SchedulerException {
        try {
            return restApi().getPreciousTaskNames(sid, jobsId);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public boolean checkPermission(String method) throws SecurityException {
        try {
            return restApi().checkPermissionMethod(sid, method);
        } catch (Exception e) {
            throw new SecurityException(e.getMessage(), e);
        }
    }

    @Override
    public boolean checkJobPermissionMethod(String jobId, String method) throws SchedulerException {
        try {
            return restApi().checkJobPermissionMethod(sid, method, jobId);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public List<String> checkJobsPermissionMethod(List<String> jobIds, String method) throws SchedulerException {
        try {
            return restApi().checkJobsPermissionMethod(sid, method, jobIds);
        } catch (RestException e) {
            throw RestException.unwrapRestException(e);
        }
    }

    @Override
    public Set<String> addJobSignal(String jobId, String signal, Map<String, String> updatedVariables)
            throws NotConnectedException, UnknownJobException, PermissionException, SignalApiException {
        Set<String> result = new HashSet<>();
        try {
            result = restApi().addJobSignalWithVariables(sid, signal, jobId, updatedVariables);
        } catch (Exception e) {
            throwSAEorUJEOrNCEOrPE(e);
        }
        return result;
    }

    @Override
    public List<JobVariable> validateJobSignal(String jobId, String signal, Map<String, String> updatedVariables)
            throws NotConnectedException, UnknownJobException, PermissionException, SignalApiException {
        List<JobVariable> jobVariables = new LinkedList<>();
        try {
            JobValidationData data = restApi().validateJobSignal(sid, signal, jobId, updatedVariables);
            data.getUpdatedVariables().forEach((k, v) -> jobVariables.add(new JobVariable(k, v)));
            data.getUpdatedAdvanced()
                .forEach((k, v) -> jobVariables.stream()
                                               .filter(jobVariable -> jobVariable.getName().equals(k))
                                               .findFirst()
                                               .get()
                                               .setAdvanced(v));
            data.getUpdatedHidden()
                .forEach((k, v) -> jobVariables.stream()
                                               .filter(jobVariable -> jobVariable.getName().equals(k))
                                               .findFirst()
                                               .get()
                                               .setHidden(v));
            data.getUpdatedDescriptions()
                .forEach((k, v) -> jobVariables.stream()
                                               .filter(jobVariable -> jobVariable.getName().equals(k))
                                               .findFirst()
                                               .get()
                                               .setDescription(v));
            data.getUpdatedGroups()
                .forEach((k, v) -> jobVariables.stream()
                                               .filter(jobVariable -> jobVariable.getName().equals(k))
                                               .findFirst()
                                               .get()
                                               .setGroup(v));
            data.getUpdatedModels()
                .forEach((k, v) -> jobVariables.stream()
                                               .filter(jobVariable -> jobVariable.getName().equals(k))
                                               .findFirst()
                                               .get()
                                               .setModel(v));
        } catch (Exception e) {
            throwSAEorUJEOrNCEOrPE(e);
        }
        return jobVariables;
    }

    private org.apache.http.impl.client.HttpClientBuilder getHttpClientBuilder()
            throws KeyStoreException, NoSuchAlgorithmException, KeyManagementException {
        SSLContextBuilder builder = new SSLContextBuilder();
        builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(builder.build(),
                                                                          SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
        return HttpClients.custom().setSSLSocketFactory(sslsf);
    }

    private JobId submitFromCatalog(String objectUrl, Map<String, String> variables, Map<String, String> genericInfos)
            throws SubmissionClosedException, JobCreationException, NotConnectedException, PermissionException {
        JobIdData jobIdData = null;

        try {
            genericInfos = new LinkedHashMap<>(setSubmissionModeToGenericInfo(genericInfos));
            jobIdData = restApiClient().submitUrl(sid, objectUrl, variables, genericInfos);
        } catch (Exception e) {
            throwNCEOrPEOrSCEOrJCE(e);
        }
        return jobId(jobIdData);
    }

    private String getBucketFromCalledWorkflow(String calledWorkflow) {
        try {
            return calledWorkflow.split("/")[0];
        } catch (Exception e) {
            throw new RuntimeException(String.format("Impossible to parse the PA:CATALOG_OBJECT: %s, parsing error when getting the workflow bucket",
                                                     calledWorkflow),
                                       e);
        }
    }

    private String getNameFromCalledWorkflow(String calledWorkflow) {
        try {
            return calledWorkflow.split("/")[1];
        } catch (Exception e) {
            throw new RuntimeException(String.format("Impossible to parse the PA:CATALOG_OBJECT: %s, parsing error when getting the workflow name",
                                                     calledWorkflow),
                                       e);
        }
    }

    private Optional<String> getRevisionFromCalledWorkflow(String calledWorkflow) {
        try {
            return Optional.of(calledWorkflow.split("/")[2]);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static Map<String, String> setSubmissionModeToGenericInfo(Map<String, String> genericInformation) {
        Map<String, String> newGenericInformation = genericInformation == null ? new LinkedHashMap<>()
                                                                               : new LinkedHashMap<>(genericInformation);
        if (!newGenericInformation.containsKey(SUBMISSION_MODE)) {
            newGenericInformation.put(SUBMISSION_MODE, SUBMISSION_MODE_WORKFLOW_API);
        }
        return newGenericInformation;
    }

    @Override
    public Map<String, Map<String, Boolean>> checkJobsPermissionMethods(List<String> jobIds, List<String> methods)
            throws UnknownJobException, NotConnectedException {
        Map<String, Map<String, Boolean>> answer = new HashMap<>(jobIds.size());
        try {
            PermissionForm permissionForm = new PermissionForm();
            permissionForm.setJobids(jobIds);
            permissionForm.setMethods(methods);
            answer = restApi().checkJobsPermissionMethods(sid, permissionForm);
        } catch (Exception e) {
            throwUJEOrNCE(e);
        }
        return answer;
    }

    @Override
    public void addExternalEndpointUrl(String jobId, String endpointName, String externalEndpointUrl,
            String endpointIconUri) throws NotConnectedException, PermissionException, UnknownJobException {
        try {
            if (endpointName == null) {
                throw new IllegalArgumentException("endpointName cannot be null");
            }
            if (externalEndpointUrl == null) {
                throw new IllegalArgumentException("externalEndpointUrl cannot be null");
            }
            restApi().addExternalEndpointUrl(sid, jobId, endpointName, externalEndpointUrl, endpointIconUri);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
    }

    @Override
    public void removeExternalEndpointUrl(String jobId, String endpointName)
            throws NotConnectedException, PermissionException, UnknownJobException {
        try {
            restApi().removeExternalEndpointUrl(sid, jobId, endpointName);
        } catch (Exception e) {
            throwUJEOrNCEOrPE(e);
        }
    }

    @Override
    public List<JobLabelInfo> getLabels() throws PermissionException, NotConnectedException {
        try {
            return DataUtility.toJobLabelsInfo(restApi().getLabels(sid));
        } catch (RestException e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public List<JobLabelInfo> createLabels(List<String> labels) throws NotConnectedException, PermissionException {
        try {
            return DataUtility.toJobLabelsInfo(restApi().createLabels(sid, labels));
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public List<JobLabelInfo> setLabels(List<String> labels) throws NotConnectedException, PermissionException {
        try {
            return DataUtility.toJobLabelsInfo(restApi().setLabels(sid, labels));
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public JobLabelInfo updateLabel(String labelId, String newLabel) throws NotConnectedException, PermissionException {
        try {
            return DataUtility.toJobLabelInfo(restApi().updateLabel(sid, labelId, newLabel));
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
        return null;
    }

    @Override
    public void deleteLabel(String labelId) throws NotConnectedException, PermissionException {
        try {
            restApi().deleteLabel(sid, labelId);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
    }

    @Override
    public void setLabelOnJobs(String labelId, List<String> jobIds) throws NotConnectedException, PermissionException {
        try {
            restApi().setLabelOnJobs(sid, labelId, jobIds);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
    }

    @Override
    public void removeJobLabels(List<String> jobIds) throws NotConnectedException, PermissionException {
        try {
            restApi().removeJobLabels(sid, jobIds);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
    }

    @Override
    public void updateLogo(byte[] image) throws NotConnectedException, PermissionException {
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(image)) {
            restApiClient().updateLogo(sid, byteArrayInputStream);
        } catch (Exception e) {
            throwNCEOrPE(e);
        }
    }
}
