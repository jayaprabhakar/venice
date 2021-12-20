package com.linkedin.venice.controller;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.NodeReplicasReadinessResponse;
import com.linkedin.venice.controllerapi.NodeReplicasReadinessState;
import com.linkedin.venice.controllerapi.VersionCreationResponse;
import com.linkedin.venice.exceptions.VeniceNoHelixResourceException;
import com.linkedin.venice.helix.HelixCustomizedViewOfflinePushRepository;
import com.linkedin.venice.helix.ResourceAssignment;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceServerWrapper;
import com.linkedin.venice.meta.PartitionAssignment;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;

public class TestHolisticSeverHealthCheck {
  private VeniceClusterWrapper cluster;
  protected ControllerClient controllerClient;
  int replicaFactor = 2;
  int partitionSize = 1000;

  @BeforeClass
  public void setup() {
    int numOfController = 1;
    cluster = ServiceFactory.getVeniceCluster(numOfController, 2, 1, replicaFactor,
        partitionSize, false, false);

    Properties routerProperties = new Properties();
    routerProperties.put(ConfigKeys.HELIX_OFFLINE_PUSH_ENABLED, true);
    cluster.addVeniceRouter(routerProperties);

    controllerClient =
        ControllerClient.constructClusterControllerClient(cluster.getClusterName(), cluster.getAllControllersURLs());
  }

  @AfterClass
  public void cleanup() {
    cluster.close();
  }


  private boolean VerifyNodeReplicasState(String nodeId, NodeReplicasReadinessState state) {
    NodeReplicasReadinessResponse response = controllerClient.nodeReplicasReadiness(nodeId);
    return !response.isError() && response.getNodeState() == state;
  }

  private boolean VerifyNodeIsError(String nodeId) {
    NodeReplicasReadinessResponse response = controllerClient.nodeReplicasReadiness(nodeId);
    return response.isError();
  }

  private void verifyNodesAreReady() {
    String wrongNodeId = "incorrect_node_id";
    for (VeniceServerWrapper server : cluster.getVeniceServers()) {
      String nodeId = Utils.getHelixNodeIdentifier(server.getPort());
      Assert.assertTrue(VerifyNodeReplicasState(nodeId, NodeReplicasReadinessState.READY));
      Assert.assertTrue(VerifyNodeIsError(wrongNodeId));
    }
  }

  private void verifyNodesAreInExpectedState(NodeReplicasReadinessState state) {
    for (VeniceServerWrapper server : cluster.getVeniceServers()) {
      String nodeId = Utils.getHelixNodeIdentifier(server.getPort());
      Assert.assertTrue(VerifyNodeReplicasState(nodeId, state));
    }
  }

  private void verifyNodesAreInanimate() {
    verifyNodesAreInExpectedState(NodeReplicasReadinessState.INANIMATE);
  }

  private void verifyNodesAreUnready() {
    verifyNodesAreInExpectedState(NodeReplicasReadinessState.UNREADY);
  }

  /**
   * HealthServiceAfterServerRestart test does the following steps:
   *
   * 1.  Create a Venice cluster with 1 controller, 1 router, and 2 servers (customized view is enabled).
   * 2.  Verify both nodes are in the ready state.
   * 3.  Create a new store and push data.
   * 4.  Wait for the push job to complete.
   * 5.  Verify that both nodes are in the ready state.
   * 6.  Stop both servers and wait for them to fully stopped.
   * 7.  Verity that both nodes are in the inanimate state.
   * 8.  Restart both servers.
   * 9.  Verify that both nodes can come back in the ready state again.
   * 10. Mock CustomizedView so that getReadyToServeInstances returns an empty list.
   * 11. Verify that both servers are in the unready state
   */

  @Test(timeOut = 120 * Time.MS_PER_SECOND)
  public void testHealthServiceAfterServerRestart() throws Exception {
    String storeName = Utils.getUniqueString("testHealthServiceAfterServerRestart");
    int dataSize = 2000;

    // Assert both servers are in the ready state before the push.
    verifyNodesAreReady();

    cluster.getNewStore(storeName);
    VersionCreationResponse response = cluster.getNewVersion(storeName, dataSize);

    String topicName = response.getKafkaTopic();
    Assert.assertEquals(response.getReplicas(), replicaFactor);
    Assert.assertEquals(response.getPartitions(), dataSize / partitionSize);

    try (VeniceWriter<String, String, byte[]> veniceWriter = cluster.getVeniceWriter(topicName)) {
      veniceWriter.broadcastStartOfPush(new HashMap<>());
      veniceWriter.put("test", "test", 1).get();
      veniceWriter.broadcastEndOfPush(new HashMap<>());
    }

    // Wait until push is completed.
    TestUtils.waitForNonDeterministicCompletion(120, TimeUnit.SECONDS,
        () -> cluster.getMasterVeniceController()
            .getVeniceAdmin()
            .getOffLinePushStatus(cluster.getClusterName(), topicName)
            .getExecutionStatus()
            .equals(ExecutionStatus.COMPLETED));

    // Assert both servers are in ready state.
    verifyNodesAreReady();

    // Stop both servers.
    for (VeniceServerWrapper server : cluster.getVeniceServers()) {
      cluster.stopVeniceServer(server.getPort());
    }

    // Wait until the server is shutdown completely from the router's point of view.
    TestUtils.waitForNonDeterministicCompletion(120, TimeUnit.SECONDS, () -> {
      PartitionAssignment partitionAssignment;
      try {
        partitionAssignment =
            cluster.getRandomVeniceRouter().getRoutingDataRepository().getPartitionAssignments(topicName);
      } catch (VeniceNoHelixResourceException e) {
        // topic is not updated in the router.
        return false;
      }
      // Ensure all of server are shutdown, not partition assigned.
      return partitionAssignment.getAssignedNumberOfPartitions() == 0;
    });

    // Verify that both servers are in the inanimate state.
    verifyNodesAreInanimate();

    // Restart both servers.
    for (VeniceServerWrapper restartServer : cluster.getVeniceServers()) {
      cluster.restartVeniceServer(restartServer.getPort());
    }

    // Wait until the servers are in the ready state again.
    for (VeniceServerWrapper server : cluster.getVeniceServers()) {
      String nodeId = Utils.getHelixNodeIdentifier(server.getPort());
      TestUtils.waitForNonDeterministicCompletion(120, TimeUnit.SECONDS,
          () -> VerifyNodeReplicasState(nodeId, NodeReplicasReadinessState.READY));
    }

    // Mock CustomizedView so that getReadyToServeInstances returns an empty list.
    VeniceHelixAdmin admin = (VeniceHelixAdmin) cluster.getMasterVeniceController().getVeniceAdmin();
    ResourceAssignment resourceAssignment = admin.getHelixVeniceClusterResources(cluster.getClusterName())
        .getCustomizedViewRepository()
        .getResourceAssignment();

    HelixCustomizedViewOfflinePushRepository mockedCvRepo = mock(HelixCustomizedViewOfflinePushRepository.class);
    when(mockedCvRepo.getReadyToServeInstances((PartitionAssignment) any(), anyInt())).thenReturn(
        Collections.emptyList());
    when(mockedCvRepo.getResourceAssignment()).thenReturn(resourceAssignment);
    admin.getHelixVeniceClusterResources(cluster.getClusterName()).setCustomizedViewRepository(mockedCvRepo);

    // Verify that both servers are in the unready state.
    verifyNodesAreUnready();
  }
}