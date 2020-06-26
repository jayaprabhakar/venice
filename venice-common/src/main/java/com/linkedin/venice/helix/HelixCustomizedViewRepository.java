package com.linkedin.venice.helix;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.Partition;
import com.linkedin.venice.meta.PartitionAssignment;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.pushmonitor.PartitionStatus;
import com.linkedin.venice.pushmonitor.ReadOnlyPartitionStatus;
import com.linkedin.venice.routerapi.ReplicaState;
import com.linkedin.venice.utils.HelixUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.validation.constraints.NotNull;
import org.apache.helix.PropertyKey;
import org.apache.helix.PropertyType;
import org.apache.helix.api.exceptions.HelixMetaDataAccessException;
import org.apache.helix.model.CustomizedView;
import org.apache.helix.model.IdealState;
import org.apache.helix.spectator.RoutingTableSnapshot;
import org.apache.log4j.Logger;


/**
 * Extend HelixBaseRoutingRepository to leverage customized view data.
 */
public class HelixCustomizedViewRepository extends HelixBaseRoutingRepository {
  private static final Logger logger = Logger.getLogger(HelixCustomizedViewRepository.class);

  private static final String LEADER_FOLLOWER_VENICE_STATE_FILLER = "N/A";

  public HelixCustomizedViewRepository(SafeHelixManager manager) {
    super(manager);
    dataSource.put(PropertyType.CUSTOMIZEDVIEW, Arrays.asList(HelixPartitionState.OFFLINE_PUSH.name()));
  }

  /**
   * Get ready to serve instances from local memory. All of instances are in {@link ExecutionStatus#COMPLETED}
   * state.
   */
  @Override
  public List<Instance> getReadyToServeInstances(PartitionAssignment partitionAssignment, int partitionId) {
    Partition partition = partitionAssignment.getPartition(partitionId);
    if (partition == null) {
      return Collections.emptyList();
    } else {
      return partition.getReadyToServeInstances();
    }
  }

  @Override
  public List<ReplicaState> getReplicaStates(String kafkaTopic, int partitionId) {
      Partition partition = resourceAssignment.getPartition(kafkaTopic, partitionId);
      if (partition == null) {
        return Collections.emptyList();
      }
      return partition.getAllInstances()
          .entrySet()
          .stream()
          .flatMap(e -> e.getValue()
              .stream()
              .map(instance -> new ReplicaState(partitionId, instance.getNodeId(), LEADER_FOLLOWER_VENICE_STATE_FILLER,
                  e.getKey(), e.getKey().equals(ExecutionStatus.COMPLETED))))
          .collect(Collectors.toList());
  }

  @Override
  protected void onExternalViewDataChange(RoutingTableSnapshot routingTableSnapshot) {
    throw new VeniceException("The function onExternalViewDataChange is not implemented");
  }

  @Override
  protected void onCustomizedViewDataChange(RoutingTableSnapshot routingTableSnapshot) {
    Collection<CustomizedView> customizedViewCollection = routingTableSnapshot.getCustomizeViews();
    if (customizedViewCollection == null) {
      logger.warn("There is no existing customized view");
      return;
    }
    /**
     * onDataChange logic for offline push status
     */
    if (routingTableSnapshot.getCustomizedStateType().equals(HelixPartitionState.OFFLINE_PUSH.name())) {
      // Create a snapshot to prevent live instances map being changed during this method execution.
      Map<String, Instance> liveInstanceSnapshot = convertLiveInstances(routingTableSnapshot.getLiveInstances());
      // Get number of partitions from Ideal state category in ZK.
      Map<String, Integer> resourceToPartitionCountMapSnapshot = resourceToIdealPartitionCountMap;
      ResourceAssignment newResourceAssignment = new ResourceAssignment();
      Set<String> resourcesInCustomizedView =
          customizedViewCollection.stream().map(CustomizedView::getResourceName).collect(Collectors.toSet());

      if (!resourceToPartitionCountMapSnapshot.keySet().containsAll(resourcesInCustomizedView)) {
        logger.info("Found the inconsistent data between customized view and ideal state of cluster: "
            + manager.getClusterName() + ". Reading the latest ideal state from zk.");

        List<PropertyKey> keys = customizedViewCollection.stream()
            .map(cv -> keyBuilder.idealStates(cv.getResourceName()))
            .collect(Collectors.toList());
        try {
          List<IdealState> idealStates = manager.getHelixDataAccessor().getProperty(keys);
          refreshResourceToIdealPartitionCountMap(idealStates);
          resourceToPartitionCountMapSnapshot = resourceToIdealPartitionCountMap;
          logger.info("Ideal state of cluster: " + manager.getClusterName() + " is updated from zk");
        } catch (HelixMetaDataAccessException e) {
          logger.error("Failed to update the ideal state of cluster: " + manager.getClusterName()
              + " because we could not access to zk.", e);
          return;
        }
      }

      for (CustomizedView customizedView : customizedViewCollection) {
        String resourceName = customizedView.getResourceName();
        if (!resourceToPartitionCountMapSnapshot.containsKey(resourceName)) {
          logger.warn("Could not find resource: " + resourceName + " in ideal state. Ideal state is up to date,"
              + " so the resource has been deleted from ideal state or could not read " + "from "
              + "zk. Ignore its customized view update.");
          continue;
        }
        PartitionAssignment partitionAssignment =
            new PartitionAssignment(resourceName, resourceToPartitionCountMapSnapshot.get(resourceName));
        for (String partitionName : customizedView.getPartitionSet()) {
          //Get instance to customized state map for this partition from local memory.
          Map<String, String> instanceStateMap = customizedView.getStateMap(partitionName);
          Map<String, List<Instance>> stateToInstanceMap = new HashMap<>();
          //Populate customized state to instance set map
          for (String instanceName : instanceStateMap.keySet()) {
            Instance instance = liveInstanceSnapshot.get(instanceName);
            if (null != instance) {
              ExecutionStatus status;
              try {
                status = ExecutionStatus.valueOf(instanceStateMap.get(instanceName));
              } catch (Exception e) {
                logger.warn("Instance:" + instanceName + " unrecognized status:" + instanceStateMap.get(instanceName));
                continue;
              }
              stateToInstanceMap.computeIfAbsent(status.toString(), s-> new ArrayList<>()).add(instance);
            } else {
              logger.warn("Cannot find instance '" + instanceName + "' in /LIVEINSTANCES");
            }
          }
          // Update partitionAssignment of customized state
          int partitionId = HelixUtils.getPartitionId(partitionName);
          partitionAssignment.addPartition(new Partition(partitionId, stateToInstanceMap));

          // Update partition status to trigger callback
          // Note we do not change the callback function which listens on PartitionStatus change, instead, we populate
          // partition status with partition assignment data of customized view
          PartitionStatus partitionStatus = new PartitionStatus(partitionId);
          stateToInstanceMap.forEach((key, value) -> value.forEach(
              instance -> partitionStatus.updateReplicaStatus(instance.getNodeId(), ExecutionStatus.valueOf(key))));
          listenerManager.trigger(resourceName, listener -> listener.onPartitionStatusChange(resourceName,
              ReadOnlyPartitionStatus.fromPartitionStatus(partitionStatus)));
        }
        newResourceAssignment.setPartitionAssignment(resourceName, partitionAssignment);
      }
      Set<String> deletedResourceNames;
      synchronized (resourceAssignment) {
        // Update the live instances as well. Helix updates live instances in this routing data
        // changed event.
        this.liveInstancesMap = Collections.unmodifiableMap(liveInstanceSnapshot);
        deletedResourceNames = resourceAssignment.compareAndGetDeletedResources(newResourceAssignment);
        resourceAssignment.refreshAssignment(newResourceAssignment);
        logger.info("Updated resource assignment and live instances.");
      }
      logger.info("Customized view is changed. The number of active resources is " + resourcesInCustomizedView.size()
          + ", and the number of deleted resource is " + deletedResourceNames.size());
      // Notify listeners that listen on customized view data change
      for (String kafkaTopic : resourceAssignment.getAssignedResources()) {
        PartitionAssignment partitionAssignment = resourceAssignment.getPartitionAssignment(kafkaTopic);
        listenerManager.trigger(kafkaTopic, listener -> listener.onCustomizedViewChange(partitionAssignment));
      }
      // Notify events to the listeners which listen on deleted resources.
      for (String kafkaTopic : deletedResourceNames) {
        listenerManager.trigger(kafkaTopic, listener -> listener.onRoutingDataDeleted(kafkaTopic));
      }
    }
  }

  @Override
  public void refreshRoutingDataForResource(String kafkaTopic) {
    throw new VeniceException("The function of refreshRoutingDataForResource is not implemented");
  }
}