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
package org.ow2.proactive.scheduler.job;

import java.util.*;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import org.ow2.proactive.scheduler.common.SchedulerEvent;
import org.ow2.proactive.scheduler.common.job.JobId;
import org.ow2.proactive.scheduler.common.job.JobInfo;
import org.ow2.proactive.scheduler.common.job.JobPriority;
import org.ow2.proactive.scheduler.common.job.JobState;
import org.ow2.proactive.scheduler.common.job.JobStatus;
import org.ow2.proactive.scheduler.common.job.JobVariable;
import org.ow2.proactive.scheduler.common.task.TaskId;
import org.ow2.proactive.scheduler.task.ClientTaskState;


/**
 * JobInfo provides some information about the Job it is linked with.
 * <br>
 * These information and only them are able to change inside the job,
 * and that's what the scheduler will send to each listener.
 * <br>
 * To have a job up to date, you must use {@code org.ow2.proactive.scheduler.job.InternalJob#setJobInfo(JobInfo)}.
 * This will automatically put the job up to date.
 *
 * @author The ProActive Team
 * @since ProActive Scheduling 0.9
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class JobInfoImpl implements JobInfo {

    /** job id: must be initialized to a value in order to create temp taskId */
    private JobId jobId = JobIdImpl.makeJobId("0");

    private String owner;

    private String tenant;

    private String domain;

    private String projectName;

    private String description = null;

    private String bucketName = null;

    private String label = null;

    /** job submitted time */
    private long submittedTime = -1;

    /** job started time*/
    //DEFAULT MUST BE -1
    private long startTime = -1;

    /** job inError time*/
    //DEFAULT MUST BE -1
    private long inErrorTime = -1;

    /** job finished time*/
    //DEFAULT MUST BE -1
    private long finishedTime = -1;

    /** job removed time (it means the user got back the result of the job)*/
    //DEFAULT MUST BE -1
    private long removedTime = -1;

    /** job scheduled time for removal (for the housekeeping) */
    // DEFAULT MUST BE 0 IF NOT SCHEDULED FOR REMOVAL
    private long scheduledTimeForRemoval = 0;

    /** last updated time on the job*/
    private long lastUpdatedTime = -1;

    private long cumulatedCoreTime = 0L;

    private int numberOfNodes = 0;

    private int numberOfNodesInParallel = 0;

    /** total number of tasks */
    private int totalNumberOfTasks = 0;

    /** number of pending tasks */
    private int numberOfPendingTasks = 0;

    /** number of running tasks */
    private int numberOfRunningTasks = 0;

    /** number of finished tasks */
    private int numberOfFinishedTasks = 0;

    /** number of failed tasks */
    private int numberOfFailedTasks = 0;

    /** number of faulty tasks */
    private int numberOfFaultyTasks = 0;

    /** number of in-error tasks */
    private int numberOfInErrorTasks = 0;

    /** job priority */
    private JobPriority priority = JobPriority.NORMAL;

    /** status of the job */
    private JobStatus status = JobStatus.PENDING;

    /** to know if the job has to be removed after the fixed admin delay or not */
    private boolean toBeRemoved;

    /** Tasks skipped by a Control Flow Action */
    private Set<TaskId> tasksSkipped;

    private List<ClientTaskState> modifiedTasks;

    private Map<String, String> genericInformation;

    private Map<String, String> variables;

    private Map<String, JobVariable> detailedVariables;

    private Set<String> signals;

    private Map<String, Map<String, JobVariable>> detailedSignals;

    private Map<String, String> visualizationConnectionStrings = Collections.emptyMap();

    private Map<String, String> visualizationIcons = Collections.emptyMap();

    private Map<Integer, Boolean> attachedServices = new LinkedHashMap();

    private Map<String, ExternalEndpoint> externalEndpointUrls = new LinkedHashMap<>();

    private boolean resultMapPresent = false;

    private List<String> preciousTasks = new ArrayList<>();

    private Long parentId = null;

    private Long startAt = null;

    private int childrenCount = 0;

    private String submissionMode = null;

    public JobInfoImpl() {
    }

    public JobInfoImpl(JobId jobId, String owner) {
        this.jobId = jobId;
        this.owner = owner;
    }

    /*
     * Copy constructor is used to pass job information to the event listener
     * (SchedulerStateUpdate)
     */
    public JobInfoImpl(JobInfoImpl jobInfo) {
        this.jobId = jobInfo.getJobId();
        this.owner = jobInfo.owner;
        this.tenant = jobInfo.tenant;
        this.projectName = jobInfo.getProjectName();
        this.description = jobInfo.getDescription();
        this.bucketName = jobInfo.getBucketName();
        this.label = jobInfo.getLabel();
        this.submittedTime = jobInfo.getSubmittedTime();
        this.startTime = jobInfo.getStartTime();
        this.inErrorTime = jobInfo.getInErrorTime();
        this.finishedTime = jobInfo.getFinishedTime();
        this.removedTime = jobInfo.getRemovedTime();
        this.totalNumberOfTasks = jobInfo.getTotalNumberOfTasks();
        this.numberOfPendingTasks = jobInfo.getNumberOfPendingTasks();
        this.numberOfRunningTasks = jobInfo.getNumberOfRunningTasks();
        this.numberOfFinishedTasks = jobInfo.getNumberOfFinishedTasks();
        this.numberOfFailedTasks = jobInfo.getNumberOfFailedTasks();
        this.numberOfFaultyTasks = jobInfo.getNumberOfFaultyTasks();
        this.numberOfInErrorTasks = jobInfo.getNumberOfInErrorTasks();
        this.priority = jobInfo.getPriority();
        this.status = jobInfo.getStatus();
        this.toBeRemoved = jobInfo.toBeRemoved;
        this.submissionMode = jobInfo.getSubmissionMode();

        if (jobInfo.getTasksSkipped() != null) {
            this.tasksSkipped = new HashSet<>(jobInfo.getTasksSkipped());
        }
        if (jobInfo.getModifiedTasks() != null) {
            this.modifiedTasks = new ArrayList<>(jobInfo.getModifiedTasks());
        }
        this.genericInformation = jobInfo.getGenericInformation();
        this.variables = jobInfo.getVariables();
        this.detailedVariables = jobInfo.getDetailedVariables();

        this.signals = jobInfo.getSignals();
        this.detailedSignals = jobInfo.getDetailedSignals();
        this.visualizationConnectionStrings = jobInfo.getVisualizationConnectionStrings();
        this.visualizationIcons = jobInfo.getVisualizationIcons();
        this.attachedServices = jobInfo.getAttachedServices();
        this.externalEndpointUrls = jobInfo.getExternalEndpointUrls();
        this.resultMapPresent = jobInfo.isResultMapPresent();
        this.preciousTasks = jobInfo.getPreciousTasks();
        this.parentId = jobInfo.getParentId();
        this.childrenCount = jobInfo.getChildrenCount();
        this.startAt = jobInfo.getStartAt();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getJobOwner() {
        return owner;
    }

    public void setJobOwner(String owner) {
        this.owner = owner;
    }

    @Override
    public String getTenant() {
        return tenant;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    @Override
    public String getProjectName() {
        return projectName;
    }

    public void setProjectName(String projectName) {
        this.projectName = projectName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String getBucketName() {
        return bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    @Override
    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getSubmissionMode() {
        return submissionMode;
    }

    public void setSubmissionMode(String submissionMode) {
        this.submissionMode = submissionMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JobId getJobId() {
        return jobId;
    }

    public void setJobId(JobId jobId) {
        this.jobId = jobId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getFinishedTime() {
        return finishedTime;
    }

    public void setFinishedTime(long finishedTime) {
        this.finishedTime = finishedTime;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getRemovedTime() {
        return removedTime;
    }

    public void setRemovedTime(long removedTime) {
        this.removedTime = removedTime;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public boolean isStarted() {
        return startTime > 0;
    }

    public void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getInErrorTime() {
        return inErrorTime;
    }

    public void setInErrorTime(long inErrorTime) {
        this.inErrorTime = inErrorTime;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getSubmittedTime() {
        return submittedTime;
    }

    public void setSubmittedTime(long submittedTime) {
        this.submittedTime = submittedTime;
    }

    @Override
    public long getLastUpdatedTime() {
        return lastUpdatedTime;
    }

    public void setLastUpdatedTime(long lastUpdatedTime) {
        this.lastUpdatedTime = lastUpdatedTime;
    }

    @Override
    public long getCumulatedCoreTime() {
        return cumulatedCoreTime;
    }

    public void setCumulatedCoreTime(long cumulatedCoreTime) {
        this.cumulatedCoreTime = cumulatedCoreTime;
    }

    @Override
    public int getNumberOfNodes() {
        return numberOfNodes;
    }

    public void setNumberOfNodes(int numberOfNodes) {
        this.numberOfNodes = numberOfNodes;
    }

    @Override
    public int getNumberOfNodesInParallel() {
        return numberOfNodesInParallel;
    }

    public void setNumberOfNodesInParallel(int numberOfNodesInParallel) {
        this.numberOfNodesInParallel = numberOfNodesInParallel;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getTotalNumberOfTasks() {
        return totalNumberOfTasks;
    }

    public void setTotalNumberOfTasks(int totalNumberOfTasks) {
        this.totalNumberOfTasks = totalNumberOfTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfFinishedTasks() {
        return numberOfFinishedTasks;
    }

    public void setNumberOfFinishedTasks(int numberOfFinishedTasks) {
        this.numberOfFinishedTasks = numberOfFinishedTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfPendingTasks() {
        return numberOfPendingTasks;
    }

    public void setNumberOfPendingTasks(int numberOfPendingTasks) {
        this.numberOfPendingTasks = numberOfPendingTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfRunningTasks() {
        return numberOfRunningTasks;
    }

    public void setNumberOfRunningTasks(int numberOfRunningTasks) {
        this.numberOfRunningTasks = numberOfRunningTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfFailedTasks() {
        return numberOfFailedTasks;
    }

    public void setNumberOfFailedTasks(int numberOfFailedTasks) {
        this.numberOfFailedTasks = numberOfFailedTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfFaultyTasks() {
        return numberOfFaultyTasks;
    }

    public void setNumberOfFaultyTasks(int numberOfFaultyTasks) {
        this.numberOfFaultyTasks = numberOfFaultyTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getNumberOfInErrorTasks() {
        return numberOfInErrorTasks;
    }

    public void setNumberOfInErrorTasks(int numberOfInErrorTasks) {
        this.numberOfInErrorTasks = numberOfInErrorTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JobPriority getPriority() {
        return priority;
    }

    public void setPriority(JobPriority priority) {
        this.priority = priority;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isToBeRemoved() {
        return toBeRemoved;
    }

    public void setToBeRemoved() {
        this.toBeRemoved = true;
    }

    /**
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + jobId + "]";
    }

    public void setTasksChanges(ChangedTasksInfo changesInfo, JobState job) {
        this.modifiedTasks = new ArrayList<>(changesInfo.getNewTasks().size() + changesInfo.getUpdatedTasks().size());

        for (TaskId id : changesInfo.getNewTasks()) {
            modifiedTasks.add(new ClientTaskState(job.getHMTasks().get(id)));
        }

        for (TaskId id : changesInfo.getUpdatedTasks()) {
            modifiedTasks.add(new ClientTaskState(job.getHMTasks().get(id)));
        }

        this.tasksSkipped = new HashSet<>(changesInfo.getSkippedTasks());
    }

    public void clearTasksChanges() {
        modifiedTasks = null;
        tasksSkipped = null;
    }

    public List<ClientTaskState> getModifiedTasks() {
        return this.modifiedTasks;
    }

    /**
     * Used as an argument for {@link SchedulerEvent#TASK_SKIPPED} to
     * specify which tasks were skipped
     * 
     * @return a set of the skipped tasks
     */
    public Set<TaskId> getTasksSkipped() {
        return this.tasksSkipped;
    }

    public void setScheduledTimeForRemoval(long scheduledTimeForRemoval) {
        this.scheduledTimeForRemoval = scheduledTimeForRemoval;
    }

    @Override
    public long getScheduledTimeForRemoval() {
        return scheduledTimeForRemoval;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.ow2.proactive.scheduler.common.job.JobInfo#getGenericInformation()
     */
    @Override
    public Map<String, String> getGenericInformation() {
        return genericInformation;
    }

    public void setGenericInformation(Map<String, String> genericInformation) {
        this.genericInformation = genericInformation;
    }

    @Override
    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables;
    }

    @Override
    public Map<String, JobVariable> getDetailedVariables() {
        return detailedVariables;
    }

    public void setDetailedVariables(Map<String, JobVariable> detailedVariables) {
        this.detailedVariables = detailedVariables;
    }

    @Override
    public Set<String> getSignals() {
        return (signals == null) ? new HashSet<>() : signals;
    }

    @Override
    public void setSignals(Set<String> signals) {
        this.signals = signals;
    }

    @Override
    public Map<String, Map<String, JobVariable>> getDetailedSignals() {
        return (detailedSignals == null) ? new LinkedHashMap<>() : detailedSignals;
    }

    @Override
    public void setDetailedSignals(Map<String, Map<String, JobVariable>> detailedSignals) {
        this.detailedSignals = detailedSignals;
    }

    @Override
    public Map<String, String> getVisualizationConnectionStrings() {
        return visualizationConnectionStrings;
    }

    @Override
    public void setVisualizationConnectionStrings(Map<String, String> visualizationConnectionStrings) {
        this.visualizationConnectionStrings = visualizationConnectionStrings;
    }

    @Override
    public Map<String, String> getVisualizationIcons() {
        return visualizationIcons;
    }

    @Override
    public void setVisualizationIcons(Map<String, String> visualizationIcons) {
        this.visualizationIcons = visualizationIcons;
    }

    @Override
    public Map<Integer, Boolean> getAttachedServices() {
        return attachedServices;
    }

    public void setAttachedServices(Map<Integer, Boolean> attachedServices) {
        this.attachedServices = attachedServices;
    }

    @Override
    public Map<String, ExternalEndpoint> getExternalEndpointUrls() {
        return externalEndpointUrls;
    }

    public void setExternalEndpointUrls(Map<String, ExternalEndpoint> externalEndpointUrls) {
        this.externalEndpointUrls = externalEndpointUrls;
    }

    public boolean isResultMapPresent() {
        return resultMapPresent;
    }

    public void setResultMapPresent(boolean resultMapPresent) {
        this.resultMapPresent = resultMapPresent;
    }

    @Override
    public List<String> getPreciousTasks() {
        return preciousTasks;
    }

    public void setPreciousTasks(List<String> preciousTasks) {
        this.preciousTasks = preciousTasks;
    }

    @Override
    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    @Override
    public Long getStartAt() {
        return startAt;
    }

    public void setStartAt(Long startAt) {
        this.startAt = startAt;
    }

    @Override
    public int getChildrenCount() {
        return childrenCount;
    }

    public void setChildrenCount(int childrenCount) {
        this.childrenCount = childrenCount;
    }
}
