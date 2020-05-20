package com.linkedin.venice.kafka.admin;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.kafka.TopicManager;
import com.linkedin.venice.utils.Time;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import kafka.admin.AdminUtils;
import kafka.admin.RackAwareMode;
import kafka.common.TopicAlreadyMarkedForDeletionException;
import kafka.server.ConfigType;
import kafka.utils.ZKStringSerializer$;
import kafka.utils.ZkUtils;
import org.I0Itec.zkclient.ZkClient;
import org.I0Itec.zkclient.ZkConnection;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.log4j.Logger;

import scala.collection.JavaConversions;


public class ScalaAdminUtils implements KafkaAdminWrapper {
  private static final Logger logger = Logger.getLogger(ScalaAdminUtils.class);
  public static final int DEFAULT_SESSION_TIMEOUT_MS = 10 * Time.MS_PER_SECOND;
  public static final int DEFAULT_CONNECTION_TIMEOUT_MS = 8 * Time.MS_PER_SECOND;

  private Properties properties;
  private String zkConnection;
  private ZkClient zkClient;
  private ZkUtils zkUtils;

  public ScalaAdminUtils() {}

  @Override
  public void initialize(Properties properties) {
    if (null == properties) {
      throw new IllegalArgumentException("properties cannot be null!");
    }
    this.properties = properties;
    this.zkConnection = properties.getProperty(ConfigKeys.KAFKA_ZK_ADDRESS);
    if (null == this.zkConnection) {
      throw new IllegalArgumentException("properties must contain: " + ConfigKeys.KAFKA_ZK_ADDRESS);
    }
  }

  @Override
  public void createTopic(String topicName, int numPartitions, int replication, Properties topicProperties) {
    AdminUtils.createTopic(getZkUtils(), topicName, numPartitions, replication, topicProperties, RackAwareMode.Safe$.MODULE$);
  }

  @Override
  public void deleteTopic(String topicName) {
    try {
      AdminUtils.deleteTopic(getZkUtils(), topicName);
    } catch (TopicAlreadyMarkedForDeletionException e) {
      logger.warn("Topic delete requested, but topic already marked for deletion");
    }
  }

  @Override
  public void setTopicConfig(String topicName, Properties topicProperties) {
    AdminUtils.changeTopicConfig(getZkUtils(), topicName, topicProperties);
  }

  @Override
  public Map<String, Long> getAllTopicRetentions() {
    Map<String, Long> topicRetentions = new HashMap<>();
    scala.collection.Map<String, Properties> allTopicConfigs = AdminUtils.fetchAllTopicConfigs(getZkUtils());
    Map<String, Properties> allTopicConfigsJavaMap = scala.collection.JavaConversions.mapAsJavaMap(allTopicConfigs);
    allTopicConfigsJavaMap.forEach( (topic, topicProperties) -> {
      if (topicProperties.containsKey(TopicConfig.RETENTION_MS_CONFIG)) {
        topicRetentions.put(topic, Long.valueOf(topicProperties.getProperty(TopicConfig.RETENTION_MS_CONFIG)));
      } else {
        topicRetentions.put(topic, TopicManager.UNKNOWN_TOPIC_RETENTION);
      }
    });
    return topicRetentions;
  }

  @Override
  public Properties getTopicConfig(String topicName) {
    return AdminUtils.fetchEntityConfig(getZkUtils(), ConfigType.Topic(), topicName);
  }

  @Override
  public boolean containsTopic(String topic) {
    return AdminUtils.topicExists(getZkUtils(), topic);
  }

  @Override
  public Map<String, Properties> getAllTopicConfig() {
    return JavaConversions.mapAsJavaMap(AdminUtils.fetchAllTopicConfigs(getZkUtils()));
  }

  @Override
  public boolean isTopicDeletionUnderway() {
    return getZkUtils().getChildrenParentMayNotExist(ZkUtils.DeleteTopicsPath()).size() > 0;
  }

  @Override
  public void close() throws IOException {
    if (null != this.zkClient) {
      try {
        this.zkClient.close();
      } catch (Exception e) {
        logger.warn("Exception (suppressed) during zkClient.close()", e);
      }
    }
    if (null != this.zkClient) {
      try {
        this.zkUtils.close();
      } catch (Exception e) {
        logger.warn("Exception (suppressed) during zkUtils.close()", e);
      }
    }
  }

  /**
   * The first time this is called, it lazily initializes {@link #zkClient}.
   *
   * @return The internal {@link ZkClient} instance.
   */
  private synchronized ZkClient getZkClient() {
    if (this.zkClient == null) {
      String zkConnection = properties.getProperty(ConfigKeys.KAFKA_ZK_ADDRESS);
      this.zkClient = new ZkClient(zkConnection, DEFAULT_SESSION_TIMEOUT_MS, DEFAULT_CONNECTION_TIMEOUT_MS, ZKStringSerializer$.MODULE$);
    }
    return this.zkClient;
  }

  /**
   * The first time this is called, it lazily initializes {@link #zkUtils}.
   *
   * @return The internal {@link ZkUtils} instance.
   */
  private synchronized ZkUtils getZkUtils() {
    if (this.zkUtils == null) {
      this.zkUtils = new ZkUtils(getZkClient(), new ZkConnection(zkConnection), false);
    }
    return this.zkUtils;
  }
}