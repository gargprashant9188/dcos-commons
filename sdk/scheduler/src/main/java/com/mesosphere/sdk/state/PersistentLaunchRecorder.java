package com.mesosphere.sdk.state;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.TextFormat;
import com.mesosphere.sdk.offer.*;
import com.mesosphere.sdk.offer.taskdata.TaskLabelReader;
import com.mesosphere.sdk.offer.taskdata.TaskLabelWriter;
import com.mesosphere.sdk.scheduler.plan.DefaultPodInstance;
import com.mesosphere.sdk.scheduler.recovery.FailureUtils;
import com.mesosphere.sdk.specification.*;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Records the result of launched tasks to persistent storage.
 */
public class PersistentLaunchRecorder implements OperationRecorder {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final StateStore stateStore;
    private final ServiceSpec serviceSpec;

    public PersistentLaunchRecorder(StateStore stateStore, ServiceSpec serviceSpec) {
        this.stateStore = stateStore;
        this.serviceSpec = serviceSpec;
    }

    @Override
    public void record(OfferRecommendation offerRecommendation) throws Exception {
        if (!(offerRecommendation instanceof LaunchOfferRecommendation)) {
            return;
        }

        Protos.TaskInfo taskInfo = ((LaunchOfferRecommendation) offerRecommendation).getTaskInfo();

        String launchDetails = "";
        switch (FailureUtils.getLaunchType(stateStore.fetchTask(taskInfo.getName()))) {
        case INITIAL_LAUNCH:
            launchDetails = "initial launch";
            taskInfo = taskInfo.toBuilder()
                    .setLabels(new TaskLabelWriter(taskInfo).setInitialLaunch().toProto())
                    .build();
            break;
        case RELAUNCH:
            launchDetails = "relaunch";
            break;
        case UNKNOWN:
            throw new IllegalStateException("Unsupported launch type");
        }

        Optional<Protos.TaskStatus> taskStatus = Optional.empty();
        String taskStatusDetails = "";
        if (!taskInfo.getTaskId().getValue().equals("")) {
            taskStatusDetails = " with STAGING status";

            Protos.TaskStatus.Builder taskStatusBuilder = Protos.TaskStatus.newBuilder()
                    .setTaskId(taskInfo.getTaskId())
                    .setState(Protos.TaskState.TASK_STAGING);
            if (taskInfo.hasExecutor()) {
                taskStatusBuilder.setExecutorId(taskInfo.getExecutor().getExecutorId());
            }

            taskStatus = Optional.of(taskStatusBuilder.build());
        }

        logger.info("Persisting {} operation{}: {}",
                launchDetails, taskStatusDetails, TextFormat.shortDebugString(taskInfo));

        updateResourcesWithinResourceSet(taskInfo);
        stateStore.storeTasks(Collections.singletonList(taskInfo));
        if (taskStatus.isPresent()) {
            stateStore.storeStatus(taskStatus.get());
        }
    }

    /**
     * This method keeps the resources associated with tasks in the state store up to date, when a task which shares
     * their resource-set is launched.
     * @param taskInfo the task being launched
     * @throws TaskException is thrown on a failure to read meta-data from the TaskInfo
     */
    @VisibleForTesting
    void updateResourcesWithinResourceSet(Protos.TaskInfo taskInfo) throws TaskException {
        // Find the PodSpec + TaskSpec for this TaskInfo
        Optional<PodSpec> podSpecOptional = TaskUtils.getPodSpec(serviceSpec, taskInfo);
        if (!podSpecOptional.isPresent()) {
            return;
        }

        PodInstance podInstance =
                new DefaultPodInstance(podSpecOptional.get(), new TaskLabelReader(taskInfo).getIndex());

        Optional<TaskSpec> taskSpecOptional = TaskUtils.getTaskSpec(podInstance, taskInfo.getName());
        if (!taskSpecOptional.isPresent()) {
            return;
        }

        // Update any other TaskInfos in this resource set to have the same resources:
        Collection<Protos.TaskInfo> taskInfosWithSameResourceSet =
                getOtherTasksInResourceSet(podInstance, taskSpecOptional.get());
        stateStore.storeTasks(updateTasksWithResources(taskInfosWithSameResourceSet, taskInfo.getResourcesList()));
    }

    /**
     * Returns a list of other tasks whose pod and resource set match the provided task information.
     * The returned list will avoid including the same task that was provided.
     */
    private Collection<Protos.TaskInfo> getOtherTasksInResourceSet(PodInstance podInstance, TaskSpec sourceTaskSpec) {
        // Find the names of tasks sharing the resource set being used in this launch
        List<String> taskNamesToUpdate = podInstance.getPod().getTasks().stream()
                // Avoid returning sourceTask itself:
                .filter(taskSpec -> !taskSpec.getName().equals(sourceTaskSpec.getName()))
                .filter(taskSpec -> taskSpec.getResourceSet().equals(sourceTaskSpec.getResourceSet()))
                .map(taskSpec -> TaskSpec.getInstanceName(podInstance, taskSpec))
                .collect(Collectors.toList());
        logger.info("Updating resources for tasks: {}", taskNamesToUpdate);

        // Fetch any existing matching TaskInfos from the state store
        List<Protos.TaskInfo> taskInfosToUpdate = taskNamesToUpdate.stream()
                .map(taskName -> stateStore.fetchTask(taskName))
                .filter(taskInfoOptional -> taskInfoOptional.isPresent())
                .map(taskInfoOptional -> taskInfoOptional.get())
                .collect(Collectors.toList());
        List<String> taskIds = taskInfosToUpdate.stream()
                .map(taskInfoToUpdate -> taskInfoToUpdate.getTaskId().getValue())
                .collect(Collectors.toList());
        logger.info("Updating resources in TaskInfos: {}", taskIds);
        return taskInfosToUpdate;
    }

    /**
     * Returns {@link Protos.TaskInfo}s which have been updated to contain the provided resource list, overwriting any
     * previous resource information.
     */
    private static Collection<Protos.TaskInfo> updateTasksWithResources(
            Collection<Protos.TaskInfo> taskInfosToUpdate, Collection<Protos.Resource> resources) {
        List<Protos.TaskInfo> updatedTaskInfos = new ArrayList<>();
        for (Protos.TaskInfo taskInfoToUpdate : taskInfosToUpdate) {
            updatedTaskInfos.add(
                    Protos.TaskInfo.newBuilder(taskInfoToUpdate)
                            .clearResources()
                            .addAllResources(resources)
                            .build());
        }
        return updatedTaskInfos;
    }
}
