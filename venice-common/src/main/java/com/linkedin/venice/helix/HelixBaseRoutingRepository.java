package com.linkedin.venice.helix;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.listener.ListenerManager;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.PartitionAssignment;
import com.linkedin.venice.meta.RoutingDataRepository;
import com.linkedin.venice.routerapi.ReplicaState;
import com.linkedin.venice.utils.Utils;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.validation.constraints.NotNull;
import org.apache.helix.PropertyType;
import org.apache.helix.api.listeners.BatchMode;
import org.apache.helix.api.listeners.ControllerChangeListener;
import org.apache.helix.api.listeners.IdealStateChangeListener;
import org.apache.helix.NotificationContext;
import org.apache.helix.PropertyKey;
import org.apache.helix.api.listeners.RoutingTableChangeListener;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.LiveInstance;
import org.apache.helix.spectator.RoutingTableProvider;
import org.apache.helix.spectator.RoutingTableSnapshot;
import org.apache.log4j.Logger;


/**
 * Get routing data from Helix and convert it to our Venice partition and replica objects.
 * <p>
 * Although Helix RoutingTableProvider already cached routing data in local memory. But it only gets data from
 * /$cluster/EXTERNALVIEW, /$cluster/CONFIGS/PARTICIPANTS, /$cluster/CUSTOMIZEDVIEW.
 * Two parts of data are missed: Additional data in /$cluster/LIVEINSTANCES and
 * partition number in /$cluster/IDEALSTATES. So we cached Venice partitions and instances
 * here to include all of them and also convert them from Helix data structure to Venice data structure.
 * <p>
 * As this repository is used by Router, so here only cached the online instance at first. If Venice needs some more
 * instances in other state, could add them in the further.
 */
@BatchMode
public abstract class HelixBaseRoutingRepository
    implements RoutingDataRepository, ControllerChangeListener, IdealStateChangeListener, RoutingTableChangeListener {
  private static final Logger logger = Logger.getLogger(HelixBaseRoutingRepository.class);

  /**
   * Manager used to communicate with Helix.
   */
  protected final SafeHelixManager manager;
  /**
   * Builder used to build the data path to access Helix internal data.
   */
  protected final PropertyKey.Builder keyBuilder;

  protected ResourceAssignment resourceAssignment = new ResourceAssignment();
  /**
   * Master controller of cluster.
   */
  private volatile Instance masterController = null;

  protected final ListenerManager<RoutingDataChangedListener> listenerManager;

  protected volatile Map<String, Instance> liveInstancesMap = new HashMap<>();

  protected volatile Map<String, Integer> resourceToIdealPartitionCountMap;

  private long masterControllerChangeTime = -1;

  private RoutingTableProvider routingTableProvider;

  protected final Map<PropertyType, List<String>> dataSource;

  public HelixBaseRoutingRepository(SafeHelixManager manager) {
    this.manager = manager;
    listenerManager = new ListenerManager<>(); //TODO make thread count configurable
    keyBuilder = new PropertyKey.Builder(manager.getClusterName());
    dataSource = new HashMap<>();
  }

  /**
   * This method is used to add listener after HelixManager being connected. Otherwise, it will met error because adding
   * listener before connecting.
   */
  public void refresh() {
    try {
      logger.info("Refresh started for cluster " + manager.getClusterName() + "'s" + getClass().getSimpleName());
      // After adding the listener, helix will initialize the callback which will get the entire external view
      // and trigger the external view change event. In other words, venice will read the newest external view immediately.
      manager.addIdealStateChangeListener(this);
      manager.addControllerListener(this);
      // Use routing table provider to get the notification of the external view change, customized view change,
      // and live instances change.
      routingTableProvider = new RoutingTableProvider(manager.getOriginalManager(), dataSource);
      routingTableProvider.addRoutingTableChangeListener(this, null);
      // Get the current external view and customized views, process at first. As the new helix API will not init a event after you add the listener.
      for (PropertyType propertyType : dataSource.keySet()) {
        if (dataSource.get(propertyType).isEmpty()) {
          onRoutingTableChange(routingTableProvider.getRoutingTableSnapshot(propertyType), null);
        } else {
          for (String customizedStateType : dataSource.get(propertyType)) {
            onRoutingTableChange(routingTableProvider.getRoutingTableSnapshot(propertyType, customizedStateType), null);
          }
        }
      }
      // TODO subscribe zk state change event after we can get zk client from HelixManager
      // (Should be fixed by Helix team soon)
      logger.info("Refresh finished for cluster" + manager.getClusterName() + "'s" + getClass().getSimpleName());
    } catch (Exception e) {
      String errorMessage = "Cannot refresh routing table from Helix for cluster " + manager.getClusterName();
      logger.error(errorMessage, e);
      throw new VeniceException(errorMessage, e);
    }
  }

  public void clear() {
    // removeListener method is a thread safe method, we don't need to lock here again.
    manager.removeListener(keyBuilder.controller(), this);
    manager.removeListener(keyBuilder.idealStates(), this);
    if (routingTableProvider != null) {
      routingTableProvider.removeRoutingTableChangeListener(this);
    }
  }

  /**
   * Get instances from local memory. All of instances are in {@link HelixState#ONLINE} state.
   */
  public List<Instance> getReadyToServeInstances(String kafkaTopic, int partitionId) {
    return getReadyToServeInstances(resourceAssignment.getPartitionAssignment(kafkaTopic), partitionId);
  }

  public abstract List<Instance> getReadyToServeInstances(PartitionAssignment partitionAssignment, int partitionId);

  public Map<String, List<Instance>> getAllInstances(String kafkaTopic, int partitionId) {
    return getPartitionAssignments(kafkaTopic).getPartition(partitionId).getAllInstances();
  }

  @Override
  public abstract List<ReplicaState> getReplicaStates(String kafkaTopic, int partitionId);

  /**
   * Get Partitions from local memory.
   *
   * @param resourceName
   *
   * @return
   */

  public PartitionAssignment getPartitionAssignments(@NotNull String resourceName) {
    return resourceAssignment.getPartitionAssignment(resourceName);
  }

  /**
   * Get number of partition from local memory cache.
   *
   * @param resourceName
   *
   * @return
   */
  public int getNumberOfPartitions(@NotNull String resourceName) {
    return resourceAssignment.getPartitionAssignment(resourceName).getExpectedNumberOfPartitions();
  }

  @Override
  public boolean containsKafkaTopic(String kafkaTopic) {
    return resourceAssignment.containsResource(kafkaTopic);
  }

  @Override
  public Instance getMasterController() {
    if (masterController == null) {
      throw new VeniceException(
          "There is no master controller for this controller or we have not received master changed event from helix.");
    }
    return masterController;
  }

  @Override
  public void subscribeRoutingDataChange(String kafkaTopic, RoutingDataChangedListener listener) {
    listenerManager.subscribe(kafkaTopic, listener);
  }

  @Override
  public void unSubscribeRoutingDataChange(String kafkaTopic, RoutingDataChangedListener listener) {
    listenerManager.unsubscribe(kafkaTopic, listener);
  }

  @Override
  public Map<String, Instance> getLiveInstancesMap() {
    return liveInstancesMap;
  }

  @Override
  public long getMasterControllerChangeTime() {
    return this.masterControllerChangeTime;
  }

  @Override
  public void onControllerChange(NotificationContext changeContext) {
    if (changeContext.getType().equals(NotificationContext.Type.FINALIZE)) {
      //Finalized notification, listener will be removed.
      return;
    }
    logger.info("Got notification type:" + changeContext.getType() + ". Master controller is changed.");
    LiveInstance leader = manager.getHelixDataAccessor().getProperty(keyBuilder.controllerLeader());
    this.masterControllerChangeTime = System.currentTimeMillis();
    if (leader == null) {
      this.masterController = null;
      logger.error("Cluster do not have master controller now!");
    } else {
      this.masterController = createInstanceFromLiveInstance(leader);
      logger.info("New master controller is:" + masterController.getHost() + ":" + masterController.getPort());
    }
  }

  public ResourceAssignment getResourceAssignment() {
    return resourceAssignment;
  }

  @Override
  public boolean doseResourcesExistInIdealState(String resource) {
    PropertyKey key = keyBuilder.idealStates(resource);
    // Try to get the helix property for the given resource, if result is null means the resource does not exist in
    // ideal states.
    if (manager.getHelixDataAccessor().getProperty(key) == null) {
      return false;
    } else {
      return true;
    }
  }

  protected Map<String, Instance> convertLiveInstances(Collection<LiveInstance> helixLiveInstances) {
    HashMap<String, Instance> instancesMap = new HashMap<>();
    for (LiveInstance helixLiveInstance : helixLiveInstances) {
      Instance instance = createInstanceFromLiveInstance(helixLiveInstance);
      instancesMap.put(instance.getNodeId(), instance);
    }
    return instancesMap;
  }

  private static Instance createInstanceFromLiveInstance(LiveInstance liveInstance) {
    return new Instance(liveInstance.getId(), Utils.parseHostFromHelixNodeIdentifier(liveInstance.getId()),
        Utils.parsePortFromHelixNodeIdentifier(liveInstance.getId()));
  }

  @Override
  public void onIdealStateChange(List<IdealState> idealStates, NotificationContext changeContext) {
    refreshResourceToIdealPartitionCountMap(idealStates);
  }

  protected void refreshResourceToIdealPartitionCountMap(List<IdealState> idealStates) {
    HashMap<String, Integer> partitionCountMap = new HashMap<>();
    for (IdealState idealState : idealStates) {
      // Ideal state could be null, if a resource has already been deleted.
      if (idealState != null) {
        partitionCountMap.put(idealState.getResourceName(), idealState.getNumPartitions());
      }
    }
    this.resourceToIdealPartitionCountMap = Collections.unmodifiableMap(partitionCountMap);
  }

  @Override
  public void onRoutingTableChange(RoutingTableSnapshot routingTableSnapshot, Object context) {
    if (routingTableSnapshot == null) {
      logger.warn("Routing table snapshot should not be null");
      return;
    }
    PropertyType helixPropertyType = routingTableSnapshot.getPropertyType();
    switch (helixPropertyType) {
      case EXTERNALVIEW:
        logger.debug("Received Helix routing table change on External View");
        onExternalViewDataChange(routingTableSnapshot);
        break;
      case CUSTOMIZEDVIEW:
        logger.debug("Received Helix routing table change on Customized View");
        onCustomizedViewDataChange(routingTableSnapshot);
        break;
      default:
        logger.warn("Received Helix routing table change on invalid type " + helixPropertyType);
    }
  }

  protected abstract void onExternalViewDataChange(RoutingTableSnapshot routingTableSnapshot);

  protected abstract void onCustomizedViewDataChange(RoutingTableSnapshot routingTableSnapshot);

  @Override
  public Instance getLeaderInstance(String resourceName, int partition) {
    List<Instance> instances = resourceAssignment.getPartition(resourceName, partition).getLeaderInstance();
    if (instances.isEmpty()) {
      return null;
    }

    if (instances.size() > 1) {
      logger.error(String.format("Detect multiple leaders. Kafka topic: %s, partition: %d", resourceName, partition));
    }

    return instances.get(0);
  }
}