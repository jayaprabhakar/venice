package com.linkedin.venice.pushstatushelper;

import com.linkedin.venice.common.PushStatusStoreUtils;
import com.linkedin.venice.pushstatus.PushStatusKey;
import com.linkedin.venice.utils.LatencyUtils;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.VeniceWriterFactory;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * PushStatusRecordDeleter is a class help controller purge push status of outdated versions.
 */
public class PushStatusStoreRecordDeleter implements AutoCloseable {
  private static final Logger logger = LogManager.getLogger(PushStatusStoreRecordDeleter.class);
  private final PushStatusStoreVeniceWriterCache veniceWriterCache;

  public PushStatusStoreRecordDeleter(VeniceWriterFactory veniceWriterFactory) {
    this.veniceWriterCache = new PushStatusStoreVeniceWriterCache(veniceWriterFactory);
  }

  public void deletePushStatus(String storeName, int version, Optional<String> incrementalPushVersion, int partitionCount) {
    VeniceWriter writer = veniceWriterCache.prepareVeniceWriter(storeName);
    logger.info("Deleting pushStatus of storeName: " + storeName + " , version: " + version);
    for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
      PushStatusKey pushStatusKey = PushStatusStoreUtils.getPushKey(version, partitionId, incrementalPushVersion);
      writer.delete(pushStatusKey, null);
    }
  }

  public void deleteServerIncrementalPushStatus(String storeName, int version, String incrementalPushVersion, int partitionCount) {
    logger.info("Deleting pushStatus of storeName: " + storeName + " , version: " + version);
    for (int partitionId = 0; partitionId < partitionCount; partitionId++) {
      deletePartitionIncrementalPushStatus(storeName, version, incrementalPushVersion, partitionId);
    }
  }

  public void deletePartitionIncrementalPushStatus(String storeName, int version, String incrementalPushVersion, int partitionId) {
    PushStatusKey pushStatusKey = PushStatusStoreUtils.getPushKey(version, partitionId,
        Optional.ofNullable(incrementalPushVersion), Optional.of(PushStatusStoreUtils.SERVER_INCREMENTAL_PUSH_PREFIX));
    logger.info("Deleting incremental push status belonging to a partition:{}. pushStatusKey:{}", partitionId, pushStatusKey);
    veniceWriterCache.prepareVeniceWriter(storeName).delete(pushStatusKey, null);
  }

  public void removePushStatusStoreVeniceWriter(String storeName) {
    logger.info("Removing push status store writer for store " + storeName);
    long veniceWriterRemovingStartTimeInNs = System.nanoTime();
    veniceWriterCache.removeVeniceWriter(storeName);
    logger.info("Removed push status store writer for store " + storeName + " in "
        + LatencyUtils.getLatencyInMS(veniceWriterRemovingStartTimeInNs) + "ms.");
  }

  public VeniceWriter getPushStatusStoreVeniceWriter(String storeName) {
    return veniceWriterCache.getVeniceWriterFromMap(storeName);
  }

  @Override
  public void close() {
    logger.info("Closing VeniceWriter cache");
    long cacheClosingStartTimeInNs = System.nanoTime();
    veniceWriterCache.close();
    logger.info("Closed VeniceWriter cache in " + LatencyUtils.getLatencyInMS(cacheClosingStartTimeInNs) + "ms.");
  }
}
