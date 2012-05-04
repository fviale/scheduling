package functionaltests;

import java.io.Serializable;

import org.junit.Assert;
import org.junit.Test;
import org.ow2.proactive.scheduler.common.Scheduler;
import org.ow2.proactive.scheduler.common.SchedulerStatus;
import org.ow2.proactive.scheduler.common.job.JobId;
import org.ow2.proactive.scheduler.common.job.JobResult;
import org.ow2.proactive.scheduler.common.job.TaskFlowJob;
import org.ow2.proactive.scheduler.common.task.JavaTask;
import org.ow2.proactive.scheduler.common.task.TaskResult;
import org.ow2.proactive.scheduler.common.task.executable.JavaExecutable;
import org.ow2.proactive.scripting.SelectionScript;
import org.ow2.tests.FunctionalTest;


/**
 * Test checks that it is possible to kill tasks and jobs when scheduler
 * is in FROZEN, PAUSED or STOPPED state.
 * 
 * @author ProActive team
 *
 */
public class TestKillWhenInStoppedState extends FunctionalTest {

    static final long FINISH_JOB_TIMEOUT = 30000;

    static final String TASK_NAME1 = "Task1";

    static final String TASK_NAME2 = "Task2";

    public static class TestJavaTask extends JavaExecutable {

        @Override
        public Serializable execute(TaskResult... results) throws Throwable {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        }

    }

    @Test
    public void testTaskAndJobKilling() throws Exception {
        Scheduler scheduler = SchedulerTHelper.getSchedulerInterface();

        test(SchedulerStatus.FROZEN);

        if (!scheduler.resume()) {
            Assert.fail("Failed to resume scheduler");
        }

        test(SchedulerStatus.PAUSED);

        if (!scheduler.resume()) {
            Assert.fail("Failed to resume scheduler");
        }

        test(SchedulerStatus.STOPPED);
    }

    public void test(SchedulerStatus status) throws Exception {
        System.out.println("Testing status: " + status);

        Scheduler scheduler = SchedulerTHelper.getSchedulerInterface();

        Assert.assertEquals("Unexpected status", SchedulerStatus.STARTED, scheduler.getStatus());

        JobId runningJobId = scheduler.submit(createRunningJob());
        JobId pendingJobId = scheduler.submit(createPendingJob());

        System.out.println("Waiting when task is running");
        SchedulerTHelper.waitForEventTaskRunning(runningJobId, TASK_NAME1);

        switch (status) {
            case FROZEN:
                System.out.println("Freezing scheduler");
                if (!scheduler.freeze()) {
                    Assert.fail("Failed to freeze scheduler");
                }
                break;
            case PAUSED:
                System.out.println("Pausing scheduler");
                if (!scheduler.pause()) {
                    Assert.fail("Failed to pause scheduler");
                }
                break;
            case STOPPED:
                System.out.println("Stopping scheduler");
                if (!scheduler.stop()) {
                    Assert.fail("Failed to stop scheduler");
                }
                break;
            default:
                throw new IllegalArgumentException();
        }

        Assert.assertEquals("Unexpected status", status, scheduler.getStatus());

        System.out.println("Killing task");
        if (!scheduler.killTask(runningJobId, TASK_NAME1)) {
            Assert.fail("Failed to kill task");
        }
        SchedulerTHelper.waitForEventTaskFinished(runningJobId, TASK_NAME1);

        System.out.println("Killing running job");
        if (!scheduler.killJob(runningJobId)) {
            Assert.fail("Failed to kill running job");
        }

        System.out.println("Waiting for job finished event");
        SchedulerTHelper.waitForEventJobFinished(runningJobId, FINISH_JOB_TIMEOUT);

        System.out.println("Killing pending job");
        if (!scheduler.killJob(pendingJobId)) {
            Assert.fail("Failed to kill pending job");
        }

        System.out.println("Waiting for job finished event");
        SchedulerTHelper.waitForEventPendingJobFinished(pendingJobId, FINISH_JOB_TIMEOUT);

        printJobResult(scheduler, runningJobId);
        printJobResult(scheduler, pendingJobId);
    }

    private void printJobResult(Scheduler scheduler, JobId jobId) throws Exception {
        JobResult jobResult = scheduler.getJobResult(jobId);
        for (TaskResult taskResult : jobResult.getAllResults().values()) {
            System.out.println("Task " + taskResult.getTaskId());
            if (taskResult.getException() != null) {
                System.out.println("Exception: " + taskResult.getException());
            }
            System.out.println("Task output:");
            System.out.println(taskResult.getOutput().getAllLogs(false));
        }
    }

    private TaskFlowJob createRunningJob() throws Exception {
        TaskFlowJob job = new TaskFlowJob();
        job.setName("Test running job");
        job.setCancelJobOnError(false);

        JavaTask javaTask1 = new JavaTask();
        javaTask1.setExecutableClassName(TestJavaTask.class.getName());
        javaTask1.setName(TASK_NAME1);
        job.addTask(javaTask1);

        JavaTask javaTask2 = new JavaTask();
        javaTask2.setExecutableClassName(TestJavaTask.class.getName());
        javaTask2.setName(TASK_NAME2);
        job.addTask(javaTask2);

        return job;
    }

    private TaskFlowJob createPendingJob() throws Exception {
        TaskFlowJob job = new TaskFlowJob();
        job.setName("Test pending job");
        job.setCancelJobOnError(false);

        JavaTask javaTask = new JavaTask();
        javaTask.setExecutableClassName(TestJavaTask.class.getName());
        javaTask.setName(TASK_NAME2);
        javaTask.setSelectionScript(new SelectionScript("selected = false;", "JavaScript", false));
        job.addTask(javaTask);

        return job;
    }

}