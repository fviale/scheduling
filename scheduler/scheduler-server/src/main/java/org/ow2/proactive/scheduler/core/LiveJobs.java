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
package org.ow2.proactive.scheduler.core;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.ow2.proactive.scheduler.common.JobDescriptor;
import org.ow2.proactive.scheduler.common.NotificationData;
import org.ow2.proactive.scheduler.common.SchedulerEvent;
import org.ow2.proactive.scheduler.common.exception.TaskAbortedException;
import org.ow2.proactive.scheduler.common.exception.TaskPreemptedException;
import org.ow2.proactive.scheduler.common.exception.TaskRestartedException;
import org.ow2.proactive.scheduler.common.exception.UnknownJobException;
import org.ow2.proactive.scheduler.common.exception.UnknownTaskException;
import org.ow2.proactive.scheduler.common.job.JobId;
import org.ow2.proactive.scheduler.common.job.JobInfo;
import org.ow2.proactive.scheduler.common.job.JobPriority;
import org.ow2.proactive.scheduler.common.job.JobStatus;
import org.ow2.proactive.scheduler.common.task.RestartMode;
import org.ow2.proactive.scheduler.common.task.SimpleTaskLogs;
import org.ow2.proactive.scheduler.common.task.TaskId;
import org.ow2.proactive.scheduler.common.task.TaskInfo;
import org.ow2.proactive.scheduler.common.task.TaskState;
import org.ow2.proactive.scheduler.common.task.TaskStatus;
import org.ow2.proactive.scheduler.common.task.flow.FlowAction;
import org.ow2.proactive.scheduler.core.db.SchedulerDBManager;
import org.ow2.proactive.scheduler.core.helpers.StartAtUpdater;
import org.ow2.proactive.scheduler.core.helpers.TaskResultCreator;
import org.ow2.proactive.scheduler.core.properties.PASchedulerProperties;
import org.ow2.proactive.scheduler.descriptor.EligibleTaskDescriptor;
import org.ow2.proactive.scheduler.descriptor.EligibleTaskDescriptorImpl;
import org.ow2.proactive.scheduler.job.ChangedTasksInfo;
import org.ow2.proactive.scheduler.job.ClientJobState;
import org.ow2.proactive.scheduler.job.ExternalEndpoint;
import org.ow2.proactive.scheduler.job.InternalJob;
import org.ow2.proactive.scheduler.job.JobIdImpl;
import org.ow2.proactive.scheduler.job.JobInfoImpl;
import org.ow2.proactive.scheduler.synchronization.SynchronizationInternal;
import org.ow2.proactive.scheduler.task.TaskIdImpl;
import org.ow2.proactive.scheduler.task.TaskInfoImpl;
import org.ow2.proactive.scheduler.task.TaskLauncher;
import org.ow2.proactive.scheduler.task.TaskResultImpl;
import org.ow2.proactive.scheduler.task.internal.InternalTask;
import org.ow2.proactive.scheduler.util.JobLogger;
import org.ow2.proactive.scheduler.util.MultipleTimingLogger;
import org.ow2.proactive.scheduler.util.TaskLogger;
import org.ow2.proactive.utils.TaskIdWrapper;


class LiveJobs {

    private static final Logger logger = Logger.getLogger(SchedulingService.class);

    private static final JobLogger jlogger = JobLogger.getInstance();

    private static final TaskLogger tlogger = TaskLogger.getInstance();

    private static final TaskResultCreator taskResultCreator = TaskResultCreator.getInstance();

    public static final String FAKE_TASK_NAME = "notask";

    public static final int FAKE_TASK_ID = -1;

    public static final String TASK_ICON = "task.icon";

    public static String KILL_TASK_DEFAULT_MESSAGE = "The task has been manually killed.";

    public static class JobData {

        final InternalJob job;

        final ReentrantLock jobLock = new ReentrantLock();

        private JobData(InternalJob job) {
            this.job = job;
        }

        void unlock() {
            jobLock.unlock();
        }
    }

    private final SchedulerDBManager dbManager;

    private final SchedulerStateUpdate listener;

    private final Map<JobId, JobData> jobs = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<TaskIdWrapper, RunningTaskData> runningTasksData = new ConcurrentHashMap<>();

    private final OnErrorPolicyInterpreter onErrorPolicyInterpreter = new OnErrorPolicyInterpreter();

    private final StartAtUpdater startAtUpdater;

    private final SynchronizationInternal synchronizationInternal;

    private SchedulingService service;

    private static final String SIGNAL_ORIGINATOR = "scheduler";

    private static final String SIGNAL_TASK = "0t0";

    private static final TaskId SIGNAL_TASK_ID = TaskIdImpl.makeTaskId(SIGNAL_TASK);

    LiveJobs(SchedulerDBManager dbManager, SchedulerStateUpdate listener, SynchronizationInternal synchronizationAPI,
            SchedulingService service) {
        this.dbManager = dbManager;
        this.listener = listener;
        this.synchronizationInternal = synchronizationAPI;
        this.service = service;
        this.startAtUpdater = new StartAtUpdater(service);
    }

    Collection<RunningTaskData> getRunningTasks() {
        return runningTasksData.values();
    }

    boolean canPingTask(RunningTaskData taskData) {
        return runningTasksData.get(TaskIdWrapper.wrap(taskData.getTask().getId())) == taskData;
    }

    void jobRecovered(InternalJob job) {
        jobs.put(job.getId(), new JobData(job));
        for (InternalTask task : job.getITasks()) {
            if (task.getStatus() == TaskStatus.RUNNING) {
                logger.info("Recover task " + task.getId() + " (" + task.getName() + ") of job " + job.getId() + " (" +
                            job.getName() + ")");
                runningTasksData.put(TaskIdWrapper.wrap(task.getId()), new RunningTaskData(task,
                                                                                           job.getOwner(),
                                                                                           job.getCredentials(),
                                                                                           task.getExecuterInformation()
                                                                                               .getLauncher()));
            }
        }

    }

    void unpauseAll() {
        for (JobId jobId : jobs.keySet()) {
            JobData jobData = lockJob(jobId);
            if (jobData != null) {
                try {
                    InternalJob job = jobData.job;
                    if (job.getStatus() == JobStatus.PAUSED) {
                        job.setUnPause();
                        dbManager.unpauseJobAndTasks(job);
                        updateJobInSchedulerState(job, SchedulerEvent.JOB_RESUMED);
                    }
                } finally {
                    jobData.unlock();
                }
            }
        }
    }

    List<RunningTaskData> getRunningTasks(JobId jobId) {
        List<RunningTaskData> result = new ArrayList<>();
        for (RunningTaskData taskData : runningTasksData.values()) {
            if (taskData.getTask().getJobId().equals(jobId)) {
                result.add(taskData);
            }
        }
        return result;
    }

    RunningTaskData getRunningTask(TaskId taskId) {
        return runningTasksData.get(TaskIdWrapper.wrap(taskId));
    }

    boolean hasJobOwnedByUser(String user) {
        for (JobData jobData : jobs.values()) {
            if (jobData.job.getOwner().equals(user)) {
                return true;
            }
        }
        return false;
    }

    boolean isJobAlive(JobId jobId) {
        if (!jobs.containsKey(jobId)) {
            return false;
        }
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return false;
        }
        try {
            return jobData.job.getStatus().isJobAlive();
        } finally {
            jobData.unlock();
        }
    }

    JobStatus getJobStatus(JobId jobId) {
        if (!jobs.containsKey(jobId)) {
            return null;
        }
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return null;
        }
        try {
            return jobData.job.getStatus();
        } finally {
            jobData.unlock();
        }
    }

    boolean isTaskAlive(TaskId taskId) {
        JobData jobData = lockJob(taskId.getJobId());
        if (jobData == null) {
            return false;
        }
        try {
            return jobData.job.getHMTasks().get(taskId).isTaskAlive();
        } finally {
            jobData.unlock();
        }
    }

    TaskStatus getTaskStatus(TaskId taskId) {
        JobData jobData = lockJob(taskId.getJobId());
        if (jobData == null) {
            return null;
        }
        try {
            return jobData.job.getHMTasks().get(taskId).getStatus();
        } finally {
            jobData.unlock();
        }
    }

    void changeJobPriority(JobId jobId, JobPriority priority) {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return;
        }
        try {
            jobData.job.setPriority(priority);

            dbManager.changeJobPriority(jobId, priority);

            listener.jobStateUpdated(jobData.job.getOwner(),
                                     new NotificationData<JobInfo>(SchedulerEvent.JOB_CHANGE_PRIORITY,
                                                                   new JobInfoImpl((JobInfoImpl) jobData.job.getJobInfo())));

            listener.jobUpdatedFullData(jobData.job);
        } finally {
            jobData.unlock();
        }
    }

    public Boolean restartAllInErrorTasks(JobId jobId) {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return false;
        }
        try {
            InternalJob job = jobData.job;
            for (TaskState taskState : job.getTasks()) {
                try {
                    InternalTask task = jobData.job.getTask(taskState.getName());
                    if (task.getStatus() == TaskStatus.IN_ERROR) {
                        tlogger.info(task.getId(), "restarting in-error task " + task.getId());
                        jobData.job.restartInErrorTask(task);
                    }
                } catch (UnknownTaskException e) {
                    logger.error("", e);
                    jlogger.error(jobId, "", e);
                    tlogger.error(taskState.getId(), "", e);
                }
            }
            setJobStatusAfterInErrorTaskRestart(jobData.job);

            dbManager.updateJobAndRestartAllInErrorTasks(job);
            updateJobInSchedulerState(job, SchedulerEvent.JOB_RESTARTED_FROM_ERROR);

            return Boolean.TRUE;
        } finally {
            jobData.unlock();
        }
    }

    boolean resumeJob(JobId jobId) {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return false;
        }
        try {
            InternalJob job = jobData.job;
            Set<TaskId> updatedTasks = job.setUnPause();

            if (!updatedTasks.isEmpty()) {
                jlogger.info(jobId, "has just been resumed.");
                dbManager.unpauseJobAndTasks(job);
                updateTasksInSchedulerState(job, updatedTasks);
            }

            // update tasks events list and send it to front-end
            updateJobInSchedulerState(job, SchedulerEvent.JOB_RESUMED);

            return !updatedTasks.isEmpty();
        } finally {
            jobData.unlock();
        }
    }

    boolean pauseJob(JobId jobId) {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return false;
        }
        try {
            InternalJob job = jobData.job;

            Set<TaskId> updatedTasks = job.setPaused();

            if (!updatedTasks.isEmpty()) {
                jlogger.info(jobId, "has just been paused.");
                dbManager.pauseJobAndTasks(job);
                updateTasksInSchedulerState(job, updatedTasks);
            }

            // update tasks events list and send it to front-end
            updateJobInSchedulerState(job, SchedulerEvent.JOB_PAUSED);

            return !updatedTasks.isEmpty();
        } finally {
            jobData.unlock();
        }
    }

    boolean updateStartAt(JobId jobId, String startAt) {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return false;
        }
        try {
            boolean answer = startAtUpdater.updateStartAt(jobData.job, startAt, dbManager);
            if (answer) {
                listener.jobStateUpdated(jobData.job.getOwner(),
                                         new NotificationData<JobInfo>(SchedulerEvent.JOB_UPDATED,
                                                                       new JobInfoImpl((JobInfoImpl) jobData.job.getJobInfo())));
            }
            return answer;
        } finally {
            jobData.unlock();
        }
    }

    void jobSubmitted(InternalJob job) {
        jobSubmitted(job, new MultipleTimingLogger("TestTiming", logger));
    }

    void increaseJobDataChildrenCount(Long jobId, int increaseAmount) {
        dbManager.increaseJobDataChildrenCount(jobId, increaseAmount);
        // If a job has a parent, it means that the parent job children count has been increased.
        // accordingly, we need to send a JOB_UPDATED notification of the parent
        JobId parentJobId = JobIdImpl.makeJobId(jobId.toString());
        JobData parentJobData = jobs.get(parentJobId);
        if (parentJobData != null) {
            // the parent job is alive, we load it from memory
            InternalJob parentJob = parentJobData.job;
            ((JobInfoImpl) parentJob.getJobInfo()).setChildrenCount(parentJob.getJobInfo().getChildrenCount() + 1);
            listener.jobStateUpdated(parentJob.getOwner(),
                                     new NotificationData<JobInfo>(SchedulerEvent.JOB_UPDATED,
                                                                   new JobInfoImpl((JobInfoImpl) parentJob.getJobInfo())));
        } else {
            // the parent job is terminated, we load it from the db
            List<InternalJob> internalJobs = dbManager.loadInternalJob(jobId);
            if (!internalJobs.isEmpty()) {
                InternalJob parentJob = internalJobs.get(0);
                listener.jobStateUpdated(parentJob.getOwner(),
                                         new NotificationData<JobInfo>(SchedulerEvent.JOB_UPDATED,
                                                                       new JobInfoImpl((JobInfoImpl) parentJob.getJobInfo())));
            }
        }
    }

    void jobSubmitted(InternalJob job, MultipleTimingLogger timingLogger) {
        timingLogger.start("prepareTasks");
        job.prepareTasks();
        job.submitAction();
        timingLogger.end("prepareTasks");
        timingLogger.start("dbNewJobSubmitted");
        dbManager.newJobSubmitted(job, timingLogger);
        timingLogger.end("dbNewJobSubmitted");
        timingLogger.start("listenerJobSubmitted");
        ClientJobState clientJobState = new ClientJobState(job);
        jobs.put(job.getId(), new JobData(job));
        listener.jobSubmitted(clientJobState);
        timingLogger.end("listenerJobSubmitted");
        if (!timingLogger.isHierarchical()) {
            timingLogger.printTimings(Level.DEBUG);
        }
    }

    Map<JobId, JobDescriptor> lockJobsToSchedule(boolean isSchedulerPausedOrStopped) {

        TreeSet<JobPriority> prioritiesScheduled = new TreeSet<>();
        TreeSet<JobPriority> prioritiesNotScheduled = new TreeSet<>();

        Map<JobId, JobDescriptor> result = new HashMap<>();
        for (Map.Entry<JobId, JobData> entry : jobs.entrySet()) {
            JobData value = entry.getValue();

            // If the scheduler is paused, schedule only running or stalled jobs
            if (isSchedulerPausedOrStopped &&
                (value.job.getStatus() != JobStatus.RUNNING && value.job.getStatus() != JobStatus.STALLED)) {
                continue;
            }

            if (value.jobLock.tryLock()) {
                InternalJob job = entry.getValue().job;
                result.put(job.getId(), job.getJobDescriptor());
                prioritiesScheduled.add(job.getPriority());
            } else {
                prioritiesNotScheduled.add(value.job.getPriority());
            }
            if (unlockIfConflict(prioritiesScheduled, prioritiesNotScheduled, result))
                return new HashMap<>(0);
        }
        return result;
    }

    private boolean unlockIfConflict(TreeSet<JobPriority> prioritiesScheduled,
            TreeSet<JobPriority> prioritiesNotScheduled, Map<JobId, JobDescriptor> result) {
        if (priorityConflict(prioritiesScheduled, prioritiesNotScheduled)) {
            unlockJobsToSchedule(result.values());
            return true;
        }
        return false;
    }

    /**
     * This method checks if there is a conflict priority between the jobs
     * selected to be scheduled and those not selected. There is a conflict if
     * any job scheduled has a strictly lower priority than any unscheduled job
     *
     * @param prioritiesScheduled
     * @param prioritiesNotScheduled
     * @return
     */
    public boolean priorityConflict(TreeSet<JobPriority> prioritiesScheduled,
            TreeSet<JobPriority> prioritiesNotScheduled) {

        for (JobPriority jp : prioritiesNotScheduled) {
            if (!prioritiesScheduled.headSet(jp).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    void unlockJobsToSchedule(Collection<JobDescriptor> jobDescriptors) {
        for (JobDescriptor desc : jobDescriptors) {
            JobData jobData = checkJobAccess(desc.getJobId());
            if (jobData != null) {
                jobData.unlock();
            }
        }
    }

    void restartWaitingTask(TaskId taskId) {
        JobData jobData = lockJob(taskId.getJobId());
        if (jobData == null) {
            return;
        }
        try {
            InternalTask task = jobData.job.getTask(taskId);
            if (!task.getStatus().isTaskAlive()) {
                tlogger.warn(taskId, "task to be restarted isn't alive " + task.getStatus());
                return;
            }
            jobData.job.reStartTask(task);
        } catch (UnknownTaskException e) {
            logger.error("Unexpected exception", e);
        } finally {
            jobData.unlock();
        }
    }

    private void restartTaskOnNodeFailure(InternalTask task, JobData jobData, TerminationData terminationData) {
        final String errorMsg = "An error has occurred due to a node failure and the maximum amount of retries property has been reached.";

        task.setProgress(0);
        task.decreaseNumberOfExecutionOnFailureLeft();
        tlogger.info(task.getId(), "number of retry on failure left " + task.getNumberOfExecutionOnFailureLeft());

        InternalJob job = jobData.job;

        if (task.getNumberOfExecutionOnFailureLeft() > 0) {
            task.setStatus(TaskStatus.WAITING_ON_FAILURE);

            job.newWaitingTask();
            listener.taskStateUpdated(job.getOwner(),
                                      new NotificationData<TaskInfo>(SchedulerEvent.TASK_WAITING_FOR_RESTART,
                                                                     new TaskInfoImpl((TaskInfoImpl) task.getTaskInfo())));
            job.reStartTask(task);
            dbManager.taskRestarted(job, task, null);
            tlogger.info(task.getId(), " is waiting for restart");
        } else {
            job.incrementNumberOfFailedTasksBy(1);
            endJob(jobData, terminationData, task, null, errorMsg, JobStatus.FAILED);
        }
    }

    TerminationData restartTaskOnNodeFailure(InternalTask task) {
        JobData jobData = lockJob(task.getJobId());
        if (jobData == null) {
            return emptyResult(task.getId());
        }
        try {
            TaskId taskId = task.getId();

            if (task.getStatus() != TaskStatus.RUNNING) {
                return emptyResult(taskId);
            }

            RunningTaskData taskData = runningTasksData.remove(TaskIdWrapper.wrap(taskId));
            if (taskData == null) {
                throw new IllegalStateException("Task " + task.getId() + " is not running.");
            }

            TerminationData result = TerminationData.newTerminationData();
            result.addTaskData(jobData.job, taskData, TerminationData.TerminationStatus.NODEFAILED, null);

            restartTaskOnNodeFailure(task, jobData, result);

            return result;
        } finally {
            jobData.unlock();
        }
    }

    private void restartTaskOnError(JobData jobData, InternalTask task, TaskStatus status, TaskResultImpl result,
            long waitTime, TerminationData terminationData) {
        InternalJob job = jobData.job;

        tlogger.info(task.getId(), "node Exclusion : restart mode is '" + task.getRestartTaskOnError() + "'");
        if (task.getRestartTaskOnError().equals(RestartMode.ELSEWHERE)) {
            task.setNodeExclusion(task.getExecuterInformation().getNodes());
        }
        task.setStatus(status);
        job.newWaitingTask();
        ((JobInfoImpl) job.getJobInfo()).setPreciousTasks(job.getPreciousTasksFinished());
        dbManager.updateAfterTaskFinished(job, task, result);
        listener.taskStateUpdated(job.getOwner(),
                                  new NotificationData<TaskInfo>(SchedulerEvent.TASK_WAITING_FOR_RESTART,
                                                                 new TaskInfoImpl((TaskInfoImpl) task.getTaskInfo())));

        tlogger.info(task.getId(), String.format("wait [%d] milliseconds before restart task on error.", waitTime));
        terminationData.addRestartData(task.getId(), waitTime);
        job.decreaseNumberOfNodesInParallel(task.getExecuterInformation().getNodes().getTotalNumberOfNodes());

        logger.info("END restartTaskOnError");
    }

    TerminationData simulateJobStart(List<EligibleTaskDescriptor> tasksToSchedule, String errorMsg) {
        TerminationData terminationData = TerminationData.newTerminationData();
        for (EligibleTaskDescriptor eltd : tasksToSchedule) {
            JobId jobId = eltd.getJobId();
            if (!terminationData.jobTerminated(jobId)) {
                JobData jobData = lockJob(jobId);
                if (jobData != null) {
                    try {
                        if (jobData.job.getStartTime() < 0) {
                            jobData.job.start();
                            updateJobInSchedulerState(jobData.job, SchedulerEvent.JOB_PENDING_TO_RUNNING);
                            jlogger.info(jobId, "started");
                        }
                        endJob(jobData,
                               terminationData,
                               ((EligibleTaskDescriptorImpl) eltd).getInternal(),
                               null,
                               errorMsg,
                               JobStatus.CANCELED);
                    } finally {
                        jobData.unlock();
                    }
                }
            }
        }
        return terminationData;
    }

    void taskStarted(InternalJob job, InternalTask task, TaskLauncher launcher, String taskLauncherNodeUrl) {
        JobData jobData = checkJobAccess(job.getId());
        if (jobData == null) {
            throw new IllegalStateException("Job " + job.getId() + " does not exist");
        }
        if (runningTasksData.containsKey(TaskIdWrapper.wrap(task.getId()))) {
            throw new IllegalStateException("Task is already started");
        }

        tlogger.info(task.getId(), "task started " + task.getId());

        runningTasksData.put(TaskIdWrapper.wrap(task.getId()),
                             new RunningTaskData(task, job.getOwner(), job.getCredentials(), launcher));

        boolean firstTaskStarted;

        if (job.getStartTime() < 0) {
            // if it is the first task of this job
            job.start();
            updateJobInSchedulerState(job, SchedulerEvent.JOB_PENDING_TO_RUNNING);
            jlogger.info(job.getId(), "started");
            firstTaskStarted = true;
        } else {
            firstTaskStarted = false;
        }

        // set the different informations on task
        job.startTask(task);
        jobData.job.increaseNumberOfNodes(task.getExecuterInformation().getNodes().getTotalNumberOfNodes());
        job.increaseNumberOfNodesInParallel(task.getExecuterInformation().getNodes().getTotalNumberOfNodes());
        dbManager.jobTaskStarted(job, task, firstTaskStarted, taskLauncherNodeUrl);

        listener.taskStateUpdated(job.getOwner(),
                                  new NotificationData<TaskInfo>(SchedulerEvent.TASK_PENDING_TO_RUNNING,
                                                                 new TaskInfoImpl((TaskInfoImpl) task.getTaskInfo())));

        // fill previous task progress with 0, means task has started
        task.setProgress(0);
    }

    private TerminationData emptyResult(TaskId taskId) {
        RunningTaskData taskData = runningTasksData.remove(TaskIdWrapper.wrap(taskId));
        if (taskData != null) {
            throw new IllegalStateException("Task is marked as running: " + taskId);
        }
        return TerminationData.EMPTY;
    }

    private TerminationData emptyData(JobId jobId) {
        for (TaskIdWrapper taskId : runningTasksData.keySet()) {
            if (taskId.getTaskId().getJobId().equals(jobId)) {
                throw new IllegalStateException("Unexpected task data: " + taskId);
            }
        }
        return TerminationData.EMPTY;
    }

    public TerminationData taskTerminatedWithResult(TaskId taskId, TaskResultImpl result) {
        JobData jobData = lockJob(taskId.getJobId());
        if (jobData == null) {
            return emptyResult(taskId);
        }
        try {
            InternalTask task;
            try {
                task = jobData.job.getTask(taskId);
            } catch (UnknownTaskException e) {
                logger.error("Unexpected exception", e);
                return emptyResult(taskId);
            }
            if (task.getStatus() != TaskStatus.RUNNING) {
                tlogger.info(taskId, "task isn't running anymore");
                return emptyResult(taskId);
            }

            TaskIdWrapper taskIdWrapper = TaskIdWrapper.wrap(taskId);
            RunningTaskData taskData = runningTasksData.remove(taskIdWrapper);
            if (taskData == null) {
                tlogger.info(taskId, "Task " + taskId + " terminates after a recovery of the scheduler");
                taskData = new RunningTaskData(task,
                                               jobData.job.getOwner(),
                                               jobData.job.getCredentials(),
                                               task.getExecuterInformation().getLauncher());
            }

            TerminationData terminationData = createAndFillTerminationData(result,
                                                                           taskData,
                                                                           jobData.job,
                                                                           TerminationData.TerminationStatus.NORMAL);

            InternalJob.FinishTaskStatus finishTaskStatus = InternalJob.FinishTaskStatus.NORMAL;
            if (result.hadException()) {
                finishTaskStatus = InternalJob.FinishTaskStatus.ERROR;
            }

            tlogger.info(taskId,
                         "finished with" + (finishTaskStatus == InternalJob.FinishTaskStatus.ERROR ? "" : "out") +
                                 " errors");

            if (finishTaskStatus == InternalJob.FinishTaskStatus.ERROR) {
                tlogger.error(taskId, "task has terminated with an error", result.getException());
                task.decreaseNumberOfExecutionLeft();

                boolean requiresPauseJobOnError = onErrorPolicyInterpreter.requiresPauseJobOnError(task);

                int numberOfExecutionLeft = task.getNumberOfExecutionLeft();

                if (numberOfExecutionLeft <= 0 && onErrorPolicyInterpreter.requiresCancelJobOnError(task)) {
                    tlogger.info(taskId, "no retry left and task is tagged with cancel job on error");

                    jobData.job.increaseNumberOfFaultyTasks(taskId);
                    jobData.job.getResultMap().putAll(result.getResultMap());
                    endJob(jobData,
                           terminationData,
                           task,
                           result,
                           "An error occurred in your task and the maximum number of executions has been reached. " +
                                   "You also ask to cancel the job in such a situation!",
                           JobStatus.CANCELED);

                    jlogger.info(taskId.getJobId(), "job has been canceled");

                    return terminationData;
                } else if (numberOfExecutionLeft > 0) {
                    tlogger.info(taskId, "number of execution left is " + numberOfExecutionLeft);

                    long waitTime = 0l;
                    if (task.getTaskRetryDelayProperty().isSet()) {
                        waitTime = task.getTaskRetryDelay();
                    } else {
                        waitTime = jobData.job.getNextWaitingTime(task.getMaxNumberOfExecution() -
                                                                  numberOfExecutionLeft);
                    }

                    if (onErrorPolicyInterpreter.requiresPauseTaskOnError(task) || requiresPauseJobOnError) {

                        restartTaskOnError(jobData,
                                           task,
                                           TaskStatus.WAITING_ON_ERROR,
                                           result,
                                           waitTime,
                                           terminationData);

                        tlogger.info(taskId, "new restart is scheduled");
                        jobData.job.increaseCumulatedCoreTime(System.currentTimeMillis() -
                                                              task.getTaskInfo().getStartTime());

                        return terminationData;
                    } else {
                        jobData.job.increaseNumberOfFaultyTasks(taskId);
                        jobData.job.increaseCumulatedCoreTime(System.currentTimeMillis() -
                                                              task.getTaskInfo().getStartTime());

                        restartTaskOnError(jobData,
                                           task,
                                           TaskStatus.WAITING_ON_ERROR,
                                           result,
                                           waitTime,
                                           terminationData);

                        tlogger.info(taskId, "new restart is scheduled");

                        return terminationData;
                    }
                } else if (numberOfExecutionLeft <= 0) {
                    if (!onErrorPolicyInterpreter.requiresPauseTaskOnError(task) &&
                        !onErrorPolicyInterpreter.requiresPauseJobOnError(task) &&
                        !onErrorPolicyInterpreter.requiresCancelJobOnError(task)) {
                        jobData.job.increaseNumberOfFaultyTasks(taskId);
                        // remove the parent tasks results if task fails and job is canceled
                        task.removeParentTasksResults();
                    } else if (onErrorPolicyInterpreter.requiresPauseTaskOnError(task)) {
                        suspendTaskOnError(jobData, task, result.getTaskDuration(), result);
                        tlogger.info(taskId, "Task still contains errors after restart, so it stays in InError state");
                        jobData.job.increaseCumulatedCoreTime(System.currentTimeMillis() -
                                                              task.getTaskInfo().getStartTime());

                        return terminationData;
                    } else if (requiresPauseJobOnError) {
                        suspendTaskOnError(jobData, task, result.getTaskDuration(), result);
                        pauseJob(task.getJobId());
                        tlogger.info(taskId,
                                     "Task still contains errors after automatic restart, the Job is configured to pause on error");
                        return terminationData;
                    }

                    if (requiresPauseJobOnError) {
                        pauseJob(task.getJobId());
                    }
                }
            } else {
                //remove the parent tasks results if task finished with no error
                task.removeParentTasksResults();
                task.setInErrorTime(-1);
            }

            terminateTask(jobData, task, finishTaskStatus, result, terminationData);

            return terminationData;
        } finally {
            jobData.unlock();
        }
    }

    private TerminationData createAndFillTerminationData(TaskResultImpl result, RunningTaskData taskData,
            InternalJob job, TerminationData.TerminationStatus status) {
        TerminationData terminationData = TerminationData.newTerminationData();
        terminationData.addTaskData(job, taskData, status, result);
        return terminationData;
    }

    private void suspendTaskOnError(JobData jobData, InternalTask task, long taskDuration, TaskResultImpl result) {
        InternalJob job = jobData.job;
        job.setInErrorTime(System.currentTimeMillis());
        job.setTaskPausedOnError(task);
        job.incrementNumberOfInErrorTasksBy(1);
        setJobStatusToInErrorIfNotPaused(job);

        task.setInErrorTime(task.getStartTime() + taskDuration);
        dbManager.updateAfterTaskFinished(job, task, result);

        updateTaskPausedOnerrorState(job, task.getId());
        updateJobInSchedulerState(job, SchedulerEvent.JOB_IN_ERROR);
    }

    private void setJobStatusToInErrorIfNotPaused(InternalJob job) {
        // maybe set the job to IN_ERROR state if it's not already in paused state (error policy : OnTaskError.PAUSE_JOB)
        if (!job.getStatus().equals(JobStatus.PAUSED) && job.getNumberOfInErrorTasks() > 0) {
            job.setStatus(JobStatus.IN_ERROR);
        }
    }

    private void setJobStatusAfterInErrorTaskRestart(InternalJob job) {
        // Resume the job to RUNNING state, unless some InError tasks remain or the job is in paused state
        if (!job.getStatus().equals(JobStatus.PAUSED) && job.getNumberOfInErrorTasks() > 0) {
            job.setStatus(JobStatus.IN_ERROR);
        } else if (job.getStatus().equals(JobStatus.IN_ERROR) && job.getNumberOfInErrorTasks() == 0) {
            job.setStatus(JobStatus.RUNNING);
            job.setInErrorTime(-1);
        } else if (job.getNumberOfInErrorTasks() == 0) {
            job.setInErrorTime(-1);
        }
    }

    public static boolean isJobWithErrors(InternalJob job) {
        switch (job.getStatus()) {
            case FAILED:
            case CANCELED:
                return true;
            default:
                return job.getJobInfo().getNumberOfFailedTasks() > 0 || job.getJobInfo().getNumberOfFaultyTasks() > 0;
        }
    }

    TerminationData finishInErrorTask(JobId jobId, String taskName) throws UnknownTaskException, UnknownJobException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        InternalJob job = jobData.job;
        try {
            InternalTask task = job.getTask(taskName);
            if (task == null) {
                throw new UnknownTaskException(taskName);
            }

            TaskId taskId = task.getId();
            if (task.getStatus() != TaskStatus.IN_ERROR) {
                tlogger.info(task.getId(), "Task must be in state IN_ERROR: " + task.getStatus());
                return emptyResult(task.getId());
            }

            TaskResultImpl taskResult = taskResultCreator.getTaskResult(dbManager, job, task);

            RunningTaskData data = new RunningTaskData(task, job.getOwner(), job.getCredentials(), null);

            TerminationData terminationData = TerminationData.newTerminationData();
            terminationData.addTaskData(job, data, TerminationData.TerminationStatus.ABORTED, taskResult);

            tlogger.debug(taskId, "result added to job " + job.getId());
            // to be done before terminating the task, once terminated it is not
            // running anymore..
            ChangedTasksInfo changesInfo = job.finishInErrorTask(taskId, taskResult, listener);
            ((JobInfoImpl) job.getJobInfo()).setPreciousTasks(job.getPreciousTasksFinished());

            boolean jobFinished = job.isFinished();

            // update job info if it is terminated
            if (jobFinished) {
                // terminating job
                job.terminate();
                jlogger.debug(job.getId(), "terminated");
                jobs.remove(job.getId());
                terminationData.addJobToTerminate(job.getId(),
                                                  job.getGenericInformation(),
                                                  job.getCredentials(),
                                                  isJobWithErrors(job));
            }

            task.setInErrorTime(-1);
            boolean jobUpdated = false;
            if (job.getNumberOfInErrorTasks() == 0) {
                job.setInErrorTime(-1);
                jobUpdated = true;
            }

            // Update database
            if (taskResult.getAction() != null) {
                dbManager.updateAfterWorkflowTaskFinished(job, changesInfo, taskResult);
            } else {
                dbManager.updateAfterTaskFinished(job, task, taskResult);
            }

            // send event
            listener.taskStateUpdated(job.getOwner(),
                                      new NotificationData<TaskInfo>(SchedulerEvent.TASK_IN_ERROR_TO_FINISHED,
                                                                     new TaskInfoImpl((TaskInfoImpl) task.getTaskInfo())));
            // if this job is finished (every task have finished)
            jlogger.info(job.getId(),
                         "finished tasks " + job.getNumberOfFinishedTasks() + ", total tasks " +
                                      job.getTotalNumberOfTasks() + ", finished " + jobFinished);
            if (jobFinished) {
                // send event to client
                listener.jobStateUpdated(job.getOwner(),
                                         new NotificationData<JobInfo>(SchedulerEvent.JOB_RUNNING_TO_FINISHED,
                                                                       new JobInfoImpl((JobInfoImpl) job.getJobInfo())));

                listener.jobUpdatedFullData(job);
            } else if (jobUpdated) {
                listener.jobStateUpdated(job.getOwner(),
                                         new NotificationData<JobInfo>(SchedulerEvent.JOB_UPDATED,
                                                                       new JobInfoImpl((JobInfoImpl) job.getJobInfo())));
                listener.jobUpdatedFullData(job);
            }

            return terminationData;
        } finally {
            jobData.unlock();
        }
    }

    void restartInErrorTask(JobId jobId, String taskName) throws UnknownTaskException {
        JobData jobData = lockJob(jobId);
        try {
            InternalTask task = jobData.job.getTask(taskName);
            if (task.getStatus() == TaskStatus.IN_ERROR) {
                tlogger.info(task.getId(), "restarting in-error task " + task.getId());
                jobData.job.restartInErrorTask(task);
                setJobStatusAfterInErrorTaskRestart(jobData.job);

                dbManager.updateJobAndTaskState(jobData.job, task);
                updateJobInSchedulerState(jobData.job, SchedulerEvent.JOB_RESTARTED_FROM_ERROR);
            }
        } finally {
            jobData.unlock();
        }
    }

    TerminationData restartTask(JobId jobId, String taskName, int restartDelay)
            throws UnknownJobException, UnknownTaskException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalTask task = jobData.job.getTask(taskName);
            tlogger.info(task.getId(), "restarting task " + task.getId());
            if (!task.getStatus().isTaskAlive()) {
                tlogger.warn(task.getId(), "task isn't alive: " + task.getStatus());
                return emptyResult(task.getId());
            }

            TaskIdWrapper taskIdWrapper = TaskIdWrapper.wrap(task.getId());
            RunningTaskData taskData = runningTasksData.remove(taskIdWrapper);
            if (taskData == null) {
                throw new IllegalStateException("Task " + task.getId() + " is not running.");
            }

            TaskResultImpl taskResult = taskResultCreator.getTaskResult(dbManager,
                                                                        jobData.job,
                                                                        task,
                                                                        new TaskRestartedException("Aborted by user"),
                                                                        new SimpleTaskLogs("", "Aborted by user"));
            TerminationData terminationData = createAndFillTerminationData(taskResult,
                                                                           taskData,
                                                                           jobData.job,
                                                                           TerminationData.TerminationStatus.ABORTED);

            task.decreaseNumberOfExecutionLeft();

            if (task.getNumberOfExecutionLeft() <= 0 && onErrorPolicyInterpreter.requiresCancelJobOnError(task)) {
                endJob(jobData,
                       terminationData,
                       task,
                       taskResult,
                       "An error occurred in your task and the maximum number of executions has been reached. " +
                                   "You also ask to cancel the job in such a situation !",
                       JobStatus.CANCELED);
                return terminationData;
            } else if (task.getNumberOfExecutionLeft() > 0) {
                long waitTime = restartDelay * 1000l;
                restartTaskOnError(jobData, task, TaskStatus.WAITING_ON_ERROR, taskResult, waitTime, terminationData);
                return terminationData;
            }

            terminateTask(jobData, task, InternalJob.FinishTaskStatus.ABORTED, taskResult, terminationData);

            return terminationData;
        } finally {
            jobData.unlock();
        }
    }

    boolean enableRemoteVisualization(JobId jobId, String taskName, String connectionString)
            throws UnknownJobException, UnknownTaskException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalTask task = jobData.job.getTask(taskName);
            tlogger.info(task.getId(),
                         "Enabling Remote visualization for task " + task.getId() + " with connection string " +
                                       connectionString);
            if (task.getStatus() != TaskStatus.RUNNING) {
                tlogger.warn(task.getId(),
                             "Cannot enable visualization as the task is not currently running. Current status: " +
                                           task.getStatus());
                return false;
            }
            task.setVisualizationConnectionString(connectionString);
            task.setVisualizationActivated(true);

            Map<String, String> connectionStringMap = new HashMap<>();
            Map<String, String> visualizationIconsMap = new HashMap<>();
            jobData.job.getIHMTasks()
                       .entrySet()
                       .stream()
                       .filter(entry -> entry.getValue().isVisualizationActivated() &&
                                        entry.getValue().getFinishedTime() <= 0)
                       .forEach(entry -> {
                           connectionStringMap.put(entry.getKey().getReadableName(),
                                                   entry.getValue().getVisualizationConnectionString());
                           visualizationIconsMap.put(entry.getKey().getReadableName(),
                                                     entry.getValue().getGenericInformation().get(TASK_ICON));
                       });
            jobData.job.getJobInfo().setVisualizationConnectionStrings(connectionStringMap);
            jobData.job.getJobInfo().setVisualizationIcons(visualizationIconsMap);

            TaskInfo ti = new TaskInfoImpl((TaskInfoImpl) task.getTaskInfo());
            listener.taskStateUpdated(jobData.job.getOwner(),
                                      new NotificationData<>(SchedulerEvent.TASK_VISU_ACTIVATED, ti));
            listener.jobStateUpdated(jobData.job.getOwner(),
                                     new NotificationData<>(SchedulerEvent.JOB_UPDATED, jobData.job.getJobInfo()));
            return true;

        } finally {
            jobData.unlock();
        }
    }

    boolean registerService(JobId jobId, int serviceId, boolean enableActions) throws UnknownJobException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalJob job = jobData.job;
            job.getAttachedServices().put(serviceId, enableActions);
            dbManager.updateAttachedServices(job);
            listener.jobStateUpdated(jobData.job.getOwner(),
                                     new NotificationData<>(SchedulerEvent.JOB_UPDATED, jobData.job.getJobInfo()));
            return true;
        } finally {
            jobData.unlock();
        }
    }

    boolean detachService(JobId jobId, int serviceId) throws UnknownJobException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalJob job = jobData.job;
            job.getAttachedServices().remove(serviceId);
            dbManager.updateAttachedServices(job);
            listener.jobStateUpdated(jobData.job.getOwner(),
                                     new NotificationData<>(SchedulerEvent.JOB_UPDATED, jobData.job.getJobInfo()));
            return true;
        } finally {
            jobData.unlock();
        }
    }

    boolean addExternalEndpointUrl(JobId jobId, String endpointName, String externalEndpointUrl, String endpointIconUri)
            throws UnknownJobException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalJob job = jobData.job;
            job.getExternalEndpointUrls().put(endpointName,
                                              new ExternalEndpoint(endpointName, externalEndpointUrl, endpointIconUri));
            dbManager.updateExternalEnpointUrls(job);
            listener.jobStateUpdated(jobData.job.getOwner(),
                                     new NotificationData<>(SchedulerEvent.JOB_UPDATED, jobData.job.getJobInfo()));
            return true;
        } finally {
            jobData.unlock();
        }
    }

    boolean removeExternalEndpointUrl(JobId jobId, String endpointName) throws UnknownJobException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalJob job = jobData.job;
            job.getExternalEndpointUrls().remove(endpointName);
            dbManager.updateExternalEnpointUrls(job);
            listener.jobStateUpdated(jobData.job.getOwner(),
                                     new NotificationData<>(SchedulerEvent.JOB_UPDATED, jobData.job.getJobInfo()));
            return true;
        } finally {
            jobData.unlock();
        }
    }

    TerminationData preemptTask(JobId jobId, String taskName, int restartDelay)
            throws UnknownJobException, UnknownTaskException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalTask task = jobData.job.getTask(taskName);
            tlogger.info(task.getId(), "preempting task " + task.getId());
            if (!task.getStatus().isTaskAlive()) {
                tlogger.info(task.getId(), "task isn't alive: " + task.getStatus());
                return emptyResult(task.getId());
            }
            RunningTaskData taskData = runningTasksData.remove(TaskIdWrapper.wrap(task.getId()));
            if (taskData == null) {
                throw new IllegalStateException("Task " + task.getId() + " is not running.");
            }
            TaskResultImpl taskResult = taskResultCreator.getTaskResult(dbManager,
                                                                        jobData.job,
                                                                        task,
                                                                        new TaskPreemptedException("Preempted by admin"),
                                                                        new SimpleTaskLogs("", "Preempted by admin"));

            TerminationData terminationData = createAndFillTerminationData(taskResult,
                                                                           taskData,
                                                                           jobData.job,
                                                                           TerminationData.TerminationStatus.ABORTED);

            long waitTime = restartDelay * 1000L;
            jobData.job.increaseCumulatedCoreTime(System.currentTimeMillis() - task.getTaskInfo().getStartTime());

            restartTaskOnError(jobData, task, TaskStatus.WAITING_ON_ERROR, taskResult, waitTime, terminationData);

            return terminationData;
        } finally {
            jobData.unlock();
        }
    }

    TerminationData killTask(JobId jobId, String taskName) throws UnknownJobException, UnknownTaskException {
        return this.killTask(jobId, taskName, KILL_TASK_DEFAULT_MESSAGE);
    }

    TerminationData killTask(JobId jobId, String taskName, String message)
            throws UnknownJobException, UnknownTaskException {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            throw new UnknownJobException(jobId);
        }
        try {
            InternalTask task = jobData.job.getTask(taskName);
            tlogger.info(task.getId(), "killing task " + task.getId());
            if (!task.getStatus().isTaskAlive()) {
                tlogger.warn(task.getId(), "task isn't alive: " + task.getStatus());
                return emptyResult(task.getId());
            }
            RunningTaskData taskData = runningTasksData.remove(TaskIdWrapper.wrap(task.getId()));
            if (taskData == null) {
                // the task is not in running state
                taskData = new RunningTaskData(task, jobData.job.getOwner(), jobData.job.getCredentials(), null);
            }

            TaskResultImpl taskResult = taskResultCreator.getTaskResult(dbManager,
                                                                        jobData.job,
                                                                        task,
                                                                        new TaskAbortedException(message),
                                                                        new SimpleTaskLogs("", message));

            TerminationData terminationData = createAndFillTerminationData(taskResult,
                                                                           taskData,
                                                                           jobData.job,
                                                                           TerminationData.TerminationStatus.ABORTED);

            if (onErrorPolicyInterpreter.requiresCancelJobOnError(task)) {
                endJob(jobData,
                       terminationData,
                       task,
                       taskResult,
                       message + " The job was configured to be cancelled automatically in this situation.",
                       JobStatus.CANCELED);
            } else {
                terminateTask(jobData, task, InternalJob.FinishTaskStatus.ABORTED, taskResult, terminationData);
            }

            return terminationData;
        } finally {
            jobData.unlock();
        }
    }

    private void terminateTask(JobData jobData, InternalTask task, InternalJob.FinishTaskStatus status,
            TaskResultImpl result, TerminationData terminationData) {
        InternalJob job = jobData.job;
        TaskId taskId = task.getId();

        tlogger.debug(taskId, "result added to job " + job.getId());
        // to be done before terminating the task, once terminated it is not
        // running anymore..
        job.getRunningTaskDescriptor(taskId);
        //merge task map result to job map result
        job.getResultMap().putAll(result.getResultMap());
        if (task.getFlowScript() != null && status == InternalJob.FinishTaskStatus.ABORTED ||
            status == InternalJob.FinishTaskStatus.SKIPPED) {
            result.setAction(FlowAction.getDefaultAction(task.getFlowScript()));
        }
        ChangedTasksInfo changesInfo = job.terminateTask(status, taskId, listener, result.getAction(), result);
        ((JobInfoImpl) job.getJobInfo()).setPreciousTasks(job.getPreciousTasksFinished());

        boolean jobFinished = job.isFinished();

        // update job info if it is terminated
        if (jobFinished) {
            // terminating job
            job.terminate();

            // Cleaning job signals
            cleanJobSignals(job.getId());

            jlogger.debug(job.getId(), "terminated");
            terminationData.addJobToTerminate(job.getId(),
                                              job.getGenericInformation(),
                                              job.getCredentials(),
                                              isJobWithErrors(job));
            jobs.remove(job.getId());
        }

        if (task.getTaskInfo().getStartTime() > 0) {
            jobData.job.increaseCumulatedCoreTime(System.currentTimeMillis() - task.getTaskInfo().getStartTime());
        }
        if (task.getExecuterInformation() != null && task.getExecuterInformation().getNodes() != null) {
            job.decreaseNumberOfNodesInParallel(task.getExecuterInformation().getNodes().getTotalNumberOfNodes());
        }
        task.setTaskResult(result);

        // Update database
        if (result.getAction() != null) {
            dbManager.updateAfterWorkflowTaskFinished(job, changesInfo, result);
        } else {
            dbManager.updateAfterTaskFinished(job, task, result);
        }

        // send event
        listener.taskStateUpdated(job.getOwner(),
                                  new NotificationData<TaskInfo>(SchedulerEvent.TASK_RUNNING_TO_FINISHED,
                                                                 new TaskInfoImpl((TaskInfoImpl) task.getTaskInfo())));
        // if this job is finished (every task have finished)
        jlogger.info(job.getId(),
                     "finished tasks " + job.getNumberOfFinishedTasks() + ", total tasks " +
                                  job.getTotalNumberOfTasks() + ", finished " + jobFinished);
        if (jobFinished) {
            // send event to client
            listener.jobStateUpdated(job.getOwner(),
                                     new NotificationData<JobInfo>(SchedulerEvent.JOB_RUNNING_TO_FINISHED,
                                                                   new JobInfoImpl((JobInfoImpl) job.getJobInfo())));

            listener.jobUpdatedFullData(job);
        }
    }

    private TerminationData terminateJob(JobId jobId, JobStatus jobStatus) {
        JobData jobData = lockJob(jobId);
        if (jobData == null) {
            return emptyData(jobId);
        }
        try {
            TerminationData terminationData = TerminationData.newTerminationData();
            endJob(jobData, terminationData, null, null, "", jobStatus);
            return terminationData;
        } finally {
            jobData.unlock();
        }
    }

    public TerminationData killJob(JobId jobId) {
        jlogger.info(jobId, "killing job");
        return terminateJob(jobId, JobStatus.KILLED);
    }

    /**
     * @param jobIds terminated jobs with given id
     * @return return TerminationData which is than used to kill all running tasks
     */
    public TerminationData killJobs(List<JobId> jobIds) {
        jobIds.forEach(jobId -> jlogger.info(jobId, "killing job"));
        List<JobData> jobDatas = lockJobs(jobIds);
        if (jobDatas == null || jobDatas.isEmpty()) {
            return TerminationData.EMPTY;
        }
        try {
            TaskResultImpl taskResult = null;
            JobStatus jobStatus = JobStatus.KILLED;

            Set<TaskId> tasksToUpdate = new HashSet<>();

            TerminationData terminationData = TerminationData.newTerminationData();

            for (JobData jobData : jobDatas) {

                JobId jobId = jobData.job.getId();

                jobs.remove(jobId);
                InternalJob job = jobData.job;
                terminationData.addJobToTerminate(jobId,
                                                  job.getGenericInformation(),
                                                  job.getCredentials(),
                                                  isJobWithErrors(job));

                jlogger.info(job.getId(), "ending request");

                long additionalCoreTime = 0;

                for (Iterator<RunningTaskData> i = runningTasksData.values().iterator(); i.hasNext();) {
                    RunningTaskData taskData = i.next();
                    if (taskData.getTask().getJobId().equals(jobId)) {
                        i.remove();
                        // remove previous read progress
                        taskData.getTask().setProgress(0);
                        additionalCoreTime += System.currentTimeMillis() -
                                              taskData.getTask().getTaskInfo().getStartTime();
                        terminationData.addTaskData(job,
                                                    taskData,
                                                    TerminationData.TerminationStatus.ABORTED,
                                                    taskResult);
                    }
                }

                jobData.job.increaseCumulatedCoreTime(additionalCoreTime);

                // if job has been killed
                tasksToUpdate.addAll(job.failed(null, jobStatus));
            }

            dbManager.killJobs(jobDatas.stream().map(jobData -> jobData.job).collect(Collectors.toList()));

            for (JobData jobData : jobDatas) {
                InternalJob job = jobData.job;

                updateTasksInSchedulerState(job, tasksToUpdate);

                SchedulerEvent event;
                if (job.getStatus() == JobStatus.PENDING) {
                    event = SchedulerEvent.JOB_PENDING_TO_FINISHED;
                } else {
                    event = SchedulerEvent.JOB_RUNNING_TO_FINISHED;
                }

                // Cleaning job signals
                cleanJobSignals(jobData.job.getId());

                // update job and tasks events list and send it to front-end
                updateJobInSchedulerState(job, event);

                jlogger.info(job.getId(), "finished (" + jobStatus + ")");
                jlogger.close(job.getId());

            }

            return terminationData;
        } finally {
            jobDatas.forEach(JobData::unlock);
        }
    }

    public TerminationData removeJob(JobId jobId) {
        return terminateJob(jobId, JobStatus.FINISHED);
    }

    private void endJob(JobData jobData, TerminationData terminationData, InternalTask task, TaskResultImpl taskResult,
            String errorMsg, JobStatus jobStatus) {
        JobId jobId = jobData.job.getId();

        jobs.remove(jobId);

        InternalJob job = jobData.job;

        terminationData.addJobToTerminate(jobId,
                                          job.getGenericInformation(),
                                          job.getCredentials(),
                                          isJobWithErrors(job));

        SchedulerEvent event;
        if (job.getStatus() == JobStatus.PENDING) {
            event = SchedulerEvent.JOB_PENDING_TO_FINISHED;
        } else {
            event = SchedulerEvent.JOB_RUNNING_TO_FINISHED;
        }

        if (task != null) {
            jlogger.info(job.getId(), "ending request caused by task " + task.getId());
        } else {
            jlogger.info(job.getId(), "ending request");
        }
        long additionalCoreTime = 0;

        for (Iterator<RunningTaskData> i = runningTasksData.values().iterator(); i.hasNext();) {
            RunningTaskData taskData = i.next();
            if (taskData.getTask().getJobId().equals(jobId)) {
                i.remove();
                // remove previous read progress
                taskData.getTask().setProgress(0);
                additionalCoreTime += System.currentTimeMillis() - taskData.getTask().getTaskInfo().getStartTime();
                terminationData.addTaskData(job, taskData, TerminationData.TerminationStatus.ABORTED, taskResult);
            }
        }
        jobData.job.increaseCumulatedCoreTime(additionalCoreTime);

        // if job has been killed
        if (jobStatus == JobStatus.KILLED) {
            Set<TaskId> tasksToUpdate = job.failed(null, jobStatus);
            dbManager.killJob(job);
            updateTasksInSchedulerState(job, tasksToUpdate);
        } else {
            // don't tamper the original job status if it's already in a
            // finished state (failed/canceled)
            if (jobStatus != JobStatus.FINISHED) {
                Set<TaskId> tasksToUpdate = job.failed(task.getId(), jobStatus);

                // store the exception into jobResult / To prevent from empty
                // task result (when job canceled), create one
                boolean noResult = (jobStatus == JobStatus.CANCELED && taskResult == null);
                if (jobStatus == JobStatus.FAILED || noResult) {
                    taskResult = new TaskResultImpl(task.getId(),
                                                    new Exception(errorMsg),
                                                    new SimpleTaskLogs("", errorMsg),
                                                    -1);
                }
                dbManager.updateAfterJobFailed(job, task, taskResult, tasksToUpdate);
                updateTasksInSchedulerState(job, tasksToUpdate);
            }
        }

        // Cleaning job signals
        cleanJobSignals(job.getId());

        // update job and tasks events list and send it to front-end
        updateJobInSchedulerState(job, event);

        jlogger.info(job.getId(), "finished (" + jobStatus + ")");
        jlogger.close(job.getId());
    }

    private void cleanJobSignals(JobId jobId) {

        try {
            TaskId fakeTaskId = createFakeTaskId(jobId.value());
            if (synchronizationInternal != null && synchronizationInternal.channelExists(SIGNAL_ORIGINATOR,
                                                                                         fakeTaskId,
                                                                                         PASchedulerProperties.SCHEDULER_SIGNALS_CHANNEL.getValueAsString() +
                                                                                                     jobId.value())) {
                synchronizationInternal.deleteChannel(SIGNAL_ORIGINATOR,
                                                      fakeTaskId,
                                                      PASchedulerProperties.SCHEDULER_SIGNALS_CHANNEL.getValueAsString() +
                                                                  jobId.value());
            }
        } catch (IOException e) {
            jlogger.warn(jobId, "Could not clear the job " + jobId + " from the signals channel");
        }
    }

    private TaskId createFakeTaskId(String jobId) {
        JobId jobIdObj = JobIdImpl.makeJobId(jobId);
        return TaskIdImpl.createTaskId(jobIdObj, FAKE_TASK_NAME, FAKE_TASK_ID);
    }

    private void updateTasksInSchedulerState(InternalJob job, Set<TaskId> tasksToUpdate) {
        for (TaskId tid : tasksToUpdate) {
            try {
                InternalTask t = job.getTask(tid);
                TaskInfo ti = new TaskInfoImpl((TaskInfoImpl) t.getTaskInfo());
                listener.taskStateUpdated(job.getOwner(),
                                          new NotificationData<>(SchedulerEvent.TASK_RUNNING_TO_FINISHED, ti));
            } catch (UnknownTaskException e) {
                logger.error(e);
            }
        }
    }

    public JobData lockJob(JobId jobId) {
        JobData jobData = jobs.get(jobId);
        if (jobData == null) {
            logger.info("Job " + jobId + " is terminated");
            return null;
        }
        jobData.jobLock.lock();
        if (jobs.containsKey(jobId)) {
            return jobData;
        } else {
            jobData.unlock();
            return null;
        }
    }

    /**
     * @param jobIds job ids to lock and return its data
     * @return list of locked jobs data for each job id provided
     */
    public List<JobData> lockJobs(List<JobId> jobIds) {
        List<JobData> lockedJobs = new ArrayList<>(jobIds.size());

        for (JobId jobId : jobIds) {
            JobData jobData = lockJob(jobId);
            if (jobData != null) {
                lockedJobs.add(jobData);
            }
        }

        return lockedJobs;
    }

    private JobData checkJobAccess(JobId jobId) {
        JobData jobData = jobs.get(jobId);
        if (jobData == null) {
            logger.warn("Job " + jobId + " is terminated or has been removed.");
            return null;
        }
        if (!jobData.jobLock.isHeldByCurrentThread()) {
            throw new IllegalThreadStateException("Thread doesn't hold lock for job " + jobId);
        } else {
            return jobData;
        }
    }

    private void updateJobInSchedulerState(InternalJob currentJob, SchedulerEvent eventType) {
        try {
            listener.jobStateUpdated(currentJob.getOwner(),
                                     new NotificationData<JobInfo>(eventType,
                                                                   new JobInfoImpl((JobInfoImpl) currentJob.getJobInfo())));
            listener.jobUpdatedFullData(currentJob);
        } catch (Throwable t) {
            // Just to prevent update method error
        }
    }

    private void updateTaskPausedOnerrorState(InternalJob job, TaskId taskToUpdate) {
        try {
            InternalTask t = job.getTask(taskToUpdate);
            TaskInfo ti = new TaskInfoImpl((TaskInfoImpl) t.getTaskInfo());
            listener.taskStateUpdated(job.getOwner(), new NotificationData<>(SchedulerEvent.TASK_IN_ERROR, ti));
        } catch (UnknownTaskException e) {
            logger.error(e);
        }
    }

}
