package com.linkedin.davinci.consumer;

import static com.linkedin.venice.schema.rmd.RmdConstants.REPLICATION_CHECKPOINT_VECTOR_FIELD_POS;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.davinci.repository.ThinClientMetaStoreBasedRepository;
import com.linkedin.davinci.storage.chunking.AbstractAvroChunkingAdapter;
import com.linkedin.davinci.storage.chunking.GenericChunkingAdapter;
import com.linkedin.davinci.storage.chunking.SpecificRecordChunkingAdapter;
import com.linkedin.davinci.utils.ChunkAssembler;
import com.linkedin.venice.client.change.capture.protocol.RecordChangeEvent;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.compression.CompressorFactory;
import com.linkedin.venice.compression.NoopCompressor;
import com.linkedin.venice.compression.VeniceCompressor;
import com.linkedin.venice.controllerapi.D2ControllerClient;
import com.linkedin.venice.controllerapi.MultiSchemaResponse;
import com.linkedin.venice.controllerapi.StoreResponse;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.protocol.ControlMessage;
import com.linkedin.venice.kafka.protocol.Delete;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.VersionSwap;
import com.linkedin.venice.kafka.protocol.enums.ControlMessageType;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreInfo;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.pubsub.PubSubTopicPartitionImpl;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.adapter.kafka.ApacheKafkaOffsetPosition;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.api.PubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.schema.SchemaReader;
import com.linkedin.venice.schema.rmd.RmdUtils;
import com.linkedin.venice.serialization.AvroStoreDeserializerCache;
import com.linkedin.venice.serialization.StoreDeserializerCache;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serialization.avro.AvroSpecificStoreDeserializerCache;
import com.linkedin.venice.serializer.FastSerializerDeserializerFactory;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.serializer.SerializerDeserializerFactory;
import com.linkedin.venice.utils.DictionaryUtils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.utils.lazy.Lazy;
import com.linkedin.venice.views.ChangeCaptureView;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class VeniceChangelogConsumerImpl<K, V> implements VeniceChangelogConsumer<K, V> {
  private static final Logger LOGGER = LogManager.getLogger(VeniceChangelogConsumerImpl.class);
  protected final int partitionCount;

  protected static final VeniceCompressor NO_OP_COMPRESSOR = new NoopCompressor();

  protected final CompressorFactory compressorFactory = new CompressorFactory();

  protected final HashMap<Integer, VeniceCompressor> compressorMap = new HashMap<>();
  protected StoreDeserializerCache<V> storeDeserializerCache;

  protected ThinClientMetaStoreBasedRepository storeRepository;

  protected final AbstractAvroChunkingAdapter<RecordChangeEvent> recordChangeEventChunkingAdapter =
      new SpecificRecordChunkingAdapter<>();

  protected final AbstractAvroChunkingAdapter<V> userEventChunkingAdapter;

  protected final SchemaReader schemaReader;
  private final String viewClassName;
  protected final PubSubTopicRepository pubSubTopicRepository = new PubSubTopicRepository();

  protected final RecordDeserializer<K> keyDeserializer;
  private final D2ControllerClient d2ControllerClient;
  private final RecordDeserializer<RecordChangeEvent> recordChangeDeserializer =
      FastSerializerDeserializerFactory.getFastAvroSpecificDeserializer(
          AvroProtocolDefinition.RECORD_CHANGE_EVENT.getCurrentProtocolVersionSchema(),
          RecordChangeEvent.class);
  protected final ReplicationMetadataSchemaRepository replicationMetadataSchemaRepository;

  protected final String storeName;

  protected final PubSubConsumerAdapter pubSubConsumer;
  protected final Map<Integer, List<Long>> currentVersionHighWatermarks = new HashMap<>();
  protected final int[] currentValuePayloadSize;

  protected final ChangelogClientConfig changelogClientConfig;

  protected final ChunkAssembler chunkAssembler;

  public VeniceChangelogConsumerImpl(
      ChangelogClientConfig changelogClientConfig,
      PubSubConsumerAdapter pubSubConsumer) {
    this.pubSubConsumer = pubSubConsumer;
    this.storeName = changelogClientConfig.getStoreName();
    this.d2ControllerClient = changelogClientConfig.getD2ControllerClient();
    StoreResponse storeResponse = changelogClientConfig.getD2ControllerClient().getStore(storeName);
    if (storeResponse.isError()) {
      throw new VeniceException(
          "Failed to get store info for store: " + storeName + " with error: " + storeResponse.getError());
    }
    StoreInfo store = storeResponse.getStore();
    this.changelogClientConfig = ChangelogClientConfig.cloneConfig(changelogClientConfig);
    this.partitionCount = store.getPartitionCount();
    this.currentValuePayloadSize = new int[partitionCount];
    this.viewClassName = changelogClientConfig.getViewName();
    this.replicationMetadataSchemaRepository = new ReplicationMetadataSchemaRepository(d2ControllerClient);
    this.schemaReader = changelogClientConfig.getSchemaReader();
    Schema keySchema = schemaReader.getKeySchema();
    this.keyDeserializer = FastSerializerDeserializerFactory.getFastAvroGenericDeserializer(keySchema, keySchema);
    this.chunkAssembler = new ChunkAssembler(storeName);

    this.storeRepository = new ThinClientMetaStoreBasedRepository(
        changelogClientConfig.getInnerClientConfig(),
        VeniceProperties.empty(),
        null);

    if (changelogClientConfig.getInnerClientConfig().isSpecificClient()) {
      // If a value class is supplied, we'll use a Specific record adapter
      Class valueClass = changelogClientConfig.getInnerClientConfig().getSpecificValueClass();
      this.userEventChunkingAdapter = new SpecificRecordChunkingAdapter();
      this.storeDeserializerCache = new AvroSpecificStoreDeserializerCache<>(storeRepository, storeName, valueClass);
    } else {
      this.userEventChunkingAdapter = GenericChunkingAdapter.INSTANCE;
      this.storeDeserializerCache = new AvroStoreDeserializerCache<>(storeRepository, storeName, true);
    }

    LOGGER.info(
        "Start a change log consumer client for store: {}, with partition count: {} and view class: {} ",
        storeName,
        partitionCount,
        viewClassName);
  }

  @Override
  public int getPartitionCount() {
    return partitionCount;
  }

  @Override
  public CompletableFuture<Void> subscribe(Set<Integer> partitions) {
    return internalSubscribe(partitions, null);
  }

  public CompletableFuture<Void> internalSubscribe(Set<Integer> partitions, PubSubTopic topic) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        storeRepository.start();
        storeRepository.subscribe(storeName);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      storeRepository.refresh();

      PubSubTopic topicToSubscribe;
      if (topic == null) {
        topicToSubscribe = getCurrentServingVersionTopic();
      } else {
        topicToSubscribe = topic;
      }

      synchronized (pubSubConsumer) {
        Set<PubSubTopicPartition> topicPartitionSet = new HashSet<>(pubSubConsumer.getAssignment());
        for (PubSubTopicPartition topicPartition: pubSubConsumer.getAssignment()) {
          if (partitions.contains(topicPartition.getPartitionNumber())) {
            pubSubConsumer.unSubscribe(topicPartition);
          }
        }

        List<PubSubTopicPartition> topicPartitionList =
            getPartitionListToSubscribe(partitions, topicPartitionSet, topicToSubscribe);
        List<PubSubTopicPartition> topicPartitionListToSeek =
            getPartitionListToSubscribe(partitions, Collections.EMPTY_SET, topicToSubscribe);

        for (PubSubTopicPartition topicPartition: topicPartitionList) {
          // TODO: we do this because we don't populate the compressor into the change capture view topic, so we
          // take this opportunity to populate it. This could be worth revisiting by either populating the compressor
          // into view topics and consuming, or, expanding the interface to this function to have a compressor provider
          // (and thereby let other view implementations figure out what would be right).
          if (!topicPartition.getPubSubTopic().getName().endsWith(ChangeCaptureView.CHANGE_CAPTURE_TOPIC_SUFFIX)) {
            compressorMap.put(topicPartition.getPartitionNumber(), getVersionCompressor(topicPartition));
          }
        }
        for (PubSubTopicPartition topicPartition: topicPartitionListToSeek) {
          pubSubConsumer.subscribe(topicPartition, OffsetRecord.LOWEST_OFFSET);
        }
        return null;
      }
    });
  }

  protected VeniceCompressor getVersionCompressor(PubSubTopicPartition topicPartition) {
    Store store = storeRepository.getStore(storeName);
    String topicName = topicPartition.getPubSubTopic().getName();
    Version version = store.getVersion(Version.parseVersionFromVersionTopicName(topicName)).get();
    VeniceCompressor compressor;
    if (CompressionStrategy.ZSTD_WITH_DICT.equals(version.getCompressionStrategy())) {
      compressor = compressorFactory.getVersionSpecificCompressor(topicName);
      if (compressor == null) {
        // we need to retrieve the dictionary from the kafka topic
        ByteBuffer dictionary = DictionaryUtils
            .readDictionaryFromKafka(topicName, new VeniceProperties(changelogClientConfig.getConsumerProperties()));
        compressor = compressorFactory
            .createVersionSpecificCompressorIfNotExist(version.getCompressionStrategy(), topicName, dictionary.array());
      }
    } else {
      compressor = compressorFactory.getCompressor(version.getCompressionStrategy());
    }
    return compressor;
  }

  @Override
  public CompletableFuture<Void> seekToBeginningOfPush(Set<Integer> partitions) {
    // Get latest version topic
    PubSubTopic topic = getCurrentServingVersionTopic();
    return internalSeek(partitions, topic, p -> pubSubConsumer.subscribe(p, OffsetRecord.LOWEST_OFFSET));
  }

  @Override
  public CompletableFuture<Void> seekToBeginningOfPush() {
    return seekToBeginningOfPush(
        pubSubConsumer.getAssignment()
            .stream()
            .map(topicPartition -> topicPartition.getPartitionNumber())
            .collect(Collectors.toSet()));
  }

  @Override
  public CompletableFuture<Void> seekToEndOfPush(Set<Integer> partitions) {
    // Get the latest change capture topic
    storeRepository.refresh();
    Store store = storeRepository.getStore(storeName);
    int currentVersion = store.getCurrentVersion();
    PubSubTopic topic = pubSubTopicRepository
        .getTopic(Version.composeKafkaTopic(storeName, currentVersion) + ChangeCaptureView.CHANGE_CAPTURE_TOPIC_SUFFIX);
    return internalSeek(partitions, topic, p -> pubSubConsumer.subscribe(p, OffsetRecord.LOWEST_OFFSET));
  }

  @Override
  public void pause() {
    this.pause(
        pubSubConsumer.getAssignment()
            .stream()
            .map(PubSubTopicPartition::getPartitionNumber)
            .collect(Collectors.toSet()));
  }

  @Override
  public void resume(Set<Integer> partitions) {
    synchronized (pubSubConsumer) {
      Set<PubSubTopicPartition> currentSubscriptions = pubSubConsumer.getAssignment();
      for (PubSubTopicPartition partition: currentSubscriptions) {
        if (partitions.contains(partition.getPartitionNumber())) {
          pubSubConsumer.resume(partition);
        }
      }
    }
  }

  @Override
  public void resume() {
    this.resume(
        pubSubConsumer.getAssignment()
            .stream()
            .map(PubSubTopicPartition::getPartitionNumber)
            .collect(Collectors.toSet()));
  }

  @Override
  public void pause(Set<Integer> partitions) {
    synchronized (pubSubConsumer) {
      Set<PubSubTopicPartition> currentSubscriptions = pubSubConsumer.getAssignment();
      for (PubSubTopicPartition partition: currentSubscriptions) {
        if (partitions.contains(partition.getPartitionNumber())) {
          pubSubConsumer.pause(partition);
        }
      }
    }
  }

  @Override
  public CompletableFuture<Void> seekToEndOfPush() {
    return seekToEndOfPush(
        pubSubConsumer.getAssignment()
            .stream()
            .map(topicPartition -> topicPartition.getPartitionNumber())
            .collect(Collectors.toSet()));
  }

  @Override
  public CompletableFuture<Void> seekToTail(Set<Integer> partitions) {
    return internalSeekToTail(partitions, ChangeCaptureView.CHANGE_CAPTURE_TOPIC_SUFFIX);
  }

  public CompletableFuture<Void> internalSeekToTail(Set<Integer> partitions, String topicSuffix) {
    // Get the latest change capture topic
    PubSubTopic topic = pubSubTopicRepository.getTopic(getCurrentServingVersionTopic() + topicSuffix);
    return internalSeek(partitions, topic, p -> {
      Long partitionEndOffset = pubSubConsumer.endOffset(p);
      pubSubConsumerSeek(p, partitionEndOffset);
    });
  }

  private PubSubTopic getCurrentServingVersionTopic() {
    storeRepository.refresh();
    Store store = storeRepository.getStore(storeName);
    int currentVersion = store.getCurrentVersion();
    return pubSubTopicRepository.getTopic(Version.composeKafkaTopic(storeName, currentVersion));
  }

  @Override
  public CompletableFuture<Void> seekToTail() {
    return seekToTail(
        pubSubConsumer.getAssignment()
            .stream()
            .map(topicPartition -> topicPartition.getPartitionNumber())
            .collect(Collectors.toSet()));
  }

  @Override
  public CompletableFuture<Void> seekToCheckpoint(Set<VeniceChangeCoordinate> checkpoints) {
    return CompletableFuture.supplyAsync(() -> {
      for (VeniceChangeCoordinate coordinate: checkpoints) {
        PubSubTopic topic = pubSubTopicRepository.getTopic(coordinate.getTopic());
        PubSubTopicPartition pubSubTopicPartition = new PubSubTopicPartitionImpl(topic, coordinate.getPartition());
        internalSeek(Collections.singleton(coordinate.getPartition()), topic, foo -> {
          Long topicOffset = ((ApacheKafkaOffsetPosition) coordinate.getPosition()).getOffset();
          pubSubConsumerSeek(pubSubTopicPartition, topicOffset);
        }).join();
      }
      return null;
    });
  }

  private void pubSubConsumerSeek(PubSubTopicPartition topicPartition, Long offset) {
    // Offset the seek to next operation inside venice pub sub consumer adapter subscription logic.
    if (offset == OffsetRecord.LOWEST_OFFSET) {
      pubSubConsumer.subscribe(topicPartition, OffsetRecord.LOWEST_OFFSET);
    } else {
      pubSubConsumer.subscribe(topicPartition, offset - 1);
    }
  }

  @Override
  public CompletableFuture<Void> subscribeAll() {
    Set<Integer> allPartitions = new HashSet<>();
    for (int partition = 0; partition < partitionCount; partition++) {
      allPartitions.add(partition);
    }
    return this.subscribe(allPartitions);
  }

  @Override
  public CompletableFuture<Void> seekToTimestamps(Map<Integer, Long> timestamps) {
    return internalSeekToTimestamps(timestamps, ChangeCaptureView.CHANGE_CAPTURE_TOPIC_SUFFIX);
  }

  public CompletableFuture<Void> internalSeekToTimestamps(Map<Integer, Long> timestamps, String topicSuffix) {
    // Get the latest change capture topic
    storeRepository.refresh();
    Store store = storeRepository.getStore(storeName);
    int currentVersion = store.getCurrentVersion();
    String topicName = Version.composeKafkaTopic(storeName, currentVersion) + topicSuffix;
    PubSubTopic topic = pubSubTopicRepository.getTopic(topicName);
    Map<PubSubTopicPartition, Long> topicPartitionLongMap = new HashMap<>();
    for (Map.Entry<Integer, Long> timestampPair: timestamps.entrySet()) {
      PubSubTopicPartition topicPartition = new PubSubTopicPartitionImpl(topic, timestampPair.getKey());
      topicPartitionLongMap.put(topicPartition, timestampPair.getValue());
    }
    return internalSeek(timestamps.keySet(), topic, partition -> {
      Long offset = pubSubConsumer.offsetForTime(partition, topicPartitionLongMap.get(partition));
      // As the offset for this timestamp does not exist, we need to seek to the very end of the topic partition.
      if (offset == null) {
        offset = pubSubConsumer.endOffset(partition);
      }
      pubSubConsumerSeek(partition, offset);
    });
  }

  @Override
  public CompletableFuture<Void> seekToTimestamp(Long timestamp) {
    Set<PubSubTopicPartition> topicPartitionSet = new HashSet<>(pubSubConsumer.getAssignment());
    Map<Integer, Long> partitionsToSeek = new HashMap<>();
    for (PubSubTopicPartition partition: topicPartitionSet) {
      partitionsToSeek.put(partition.getPartitionNumber(), timestamp);
    }
    return this.seekToTimestamps(partitionsToSeek);
  }

  protected CompletableFuture<Void> internalSeek(
      Set<Integer> partitions,
      PubSubTopic targetTopic,
      SeekFunction seekAction) {
    return CompletableFuture.supplyAsync(() -> {
      synchronized (pubSubConsumer) {
        // Prune out current subscriptions
        for (PubSubTopicPartition topicPartition: pubSubConsumer.getAssignment()) {
          currentVersionHighWatermarks.remove(topicPartition.getPartitionNumber());
          if (partitions.contains(topicPartition.getPartitionNumber())) {
            pubSubConsumer.unSubscribe(topicPartition);
          }
        }

        List<PubSubTopicPartition> topicPartitionListToSeek =
            getPartitionListToSubscribe(partitions, Collections.EMPTY_SET, targetTopic);
        for (PubSubTopicPartition topicPartition: topicPartitionListToSeek) {
          if (!topicPartition.getPubSubTopic().getName().endsWith(ChangeCaptureView.CHANGE_CAPTURE_TOPIC_SUFFIX)) {
            compressorMap.put(topicPartition.getPartitionNumber(), getVersionCompressor(topicPartition));
          }
          seekAction.apply(topicPartition);
        }

      }
      return null;
    });
  }

  @FunctionalInterface
  interface SeekFunction {
    void apply(PubSubTopicPartition partitionToSeek);
  }

  private List<PubSubTopicPartition> getPartitionListToSubscribe(
      Set<Integer> partitions,
      Set<PubSubTopicPartition> topicPartitionSet,
      PubSubTopic topic) {
    List<PubSubTopicPartition> topicPartitionList = new ArrayList<>();
    for (Integer partition: partitions) {
      PubSubTopicPartition topicPartition = new PubSubTopicPartitionImpl(topic, partition);
      if (!topicPartitionSet.contains(topicPartition)) {
        topicPartitionList.add(topicPartition);
      }
    }
    topicPartitionList.addAll(topicPartitionSet);
    return topicPartitionList;
  }

  @Override
  public void unsubscribe(Set<Integer> partitions) {
    synchronized (pubSubConsumer) {
      Set<PubSubTopicPartition> topicPartitionSet = new HashSet<>(pubSubConsumer.getAssignment());
      Set<PubSubTopicPartition> topicPartitionsToUnsub = new HashSet<>();
      for (PubSubTopicPartition topicPartition: topicPartitionSet) {
        if (partitions.contains(topicPartition.getPartitionNumber())) {
          topicPartitionsToUnsub.add(topicPartition);
        }
      }
      pubSubConsumer.batchUnsubscribe(topicPartitionsToUnsub);
    }
  }

  @Override
  public void unsubscribeAll() {
    Set<Integer> allPartitions = new HashSet<>();
    for (int partition = 0; partition < partitionCount; partition++) {
      allPartitions.add(partition);
    }
    this.unsubscribe(allPartitions);
  }

  @Override
  public Collection<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> poll(long timeoutInMs) {
    return internalPoll(timeoutInMs, ChangeCaptureView.CHANGE_CAPTURE_TOPIC_SUFFIX);
  }

  protected Collection<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> internalPoll(
      long timeoutInMs,
      String topicSuffix) {
    List<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> pubSubMessages = new ArrayList<>();
    Map<PubSubTopicPartition, List<PubSubMessage<KafkaKey, KafkaMessageEnvelope, Long>>> messagesMap;
    synchronized (pubSubConsumer) {
      messagesMap = pubSubConsumer.poll(timeoutInMs);
    }
    for (Map.Entry<PubSubTopicPartition, List<PubSubMessage<KafkaKey, KafkaMessageEnvelope, Long>>> entry: messagesMap
        .entrySet()) {
      PubSubTopicPartition pubSubTopicPartition = entry.getKey();
      List<PubSubMessage<KafkaKey, KafkaMessageEnvelope, Long>> messageList = entry.getValue();
      for (PubSubMessage<KafkaKey, KafkaMessageEnvelope, Long> message: messageList) {
        if (message.getKey().isControlMessage()) {
          ControlMessage controlMessage = (ControlMessage) message.getValue().getPayloadUnion();
          if (handleControlMessage(controlMessage, pubSubTopicPartition, topicSuffix)) {
            break;
          }
        } else {
          Optional<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> pubSubMessage =
              convertPubSubMessageToPubSubChangeEventMessage(message, pubSubTopicPartition);
          pubSubMessage.ifPresent(pubSubMessages::add);
        }
      }
    }
    return pubSubMessages;
  }

  /**
   * Handle control message from the given topic. Returns true if a topic switch should occur and records should be returned
   *
   * @param controlMessage the encountered control message
   * @param pubSubTopicPartition the topic that this control message was concerned
   * @return true if a version switch happened, false if otherwise
   */
  protected boolean handleControlMessage(
      ControlMessage controlMessage,
      PubSubTopicPartition pubSubTopicPartition,
      String topicSuffix) {
    ControlMessageType controlMessageType = ControlMessageType.valueOf(controlMessage);
    // TODO: Find a better way to avoid data gap between version topic and change capture topic due to log compaction.
    if (controlMessageType.equals(ControlMessageType.END_OF_PUSH)) {
      LOGGER.info(
          "End of Push message received for version {} for store {}",
          Version.parseVersionFromKafkaTopicName(pubSubTopicPartition.getPubSubTopic().getName()),
          storeName);
      // Jump to next topic
      return switchToNewTopic(
          pubSubTopicPartition.getPubSubTopic(),
          topicSuffix,
          pubSubTopicPartition.getPartitionNumber());
    }
    if (controlMessageType.equals(ControlMessageType.VERSION_SWAP)) {
      return handleVersionSwapControlMessage(controlMessage, pubSubTopicPartition, topicSuffix);
    }
    return false;
  }

  // This function exists for wrappers of this class to be able to do any kind of preprocessing on the raw bytes of the
  // data consumed
  // in the change stream so as to avoid having to do any duplicate deserialization/serialization. Wrappers which depend
  // on solely
  // on the data post deserialization
  protected <T> T processRecordBytes(
      ByteBuffer decompressedBytes,
      T deserializedValue,
      byte[] key,
      ByteBuffer value,
      PubSubTopicPartition partition,
      int valueSchemaId,
      long recordOffset) throws IOException {
    return deserializedValue;
  }

  protected Optional<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> convertPubSubMessageToPubSubChangeEventMessage(
      PubSubMessage<KafkaKey, KafkaMessageEnvelope, Long> message,
      PubSubTopicPartition pubSubTopicPartition) {
    Optional<PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate>> pubSubChangeEventMessage = Optional.empty();
    byte[] keyBytes = message.getKey().getKey();
    MessageType messageType = MessageType.valueOf(message.getValue());
    RecordChangeEvent recordChangeEvent;
    Object assembledObject = null;
    List<Long> replicationCheckpoint = null;
    if (messageType.equals(MessageType.DELETE)) {
      Delete delete = (Delete) message.getValue().payloadUnion;

      // Deletes have a previous and current value of null. So just fill it in!
      ChangeEvent<V> changeEvent = new ChangeEvent<>(null, null);
      pubSubChangeEventMessage = Optional.of(
          new ImmutableChangeCapturePubSubMessage<>(
              keyDeserializer.deserialize(keyBytes),
              changeEvent,
              pubSubTopicPartition,
              message.getOffset(),
              message.getPubSubMessageTime(),
              message.getPayloadSize(),
              false));

      replicationCheckpoint = extractOffsetVectorFromMessage(
          delete.getReplicationMetadataVersionId(),
          delete.getReplicationMetadataPayload());
    }
    if (messageType.equals(MessageType.PUT)) {
      Put put = (Put) message.getValue().payloadUnion;
      // Select appropriate deserializers
      Lazy deserializerProvider;
      int readerSchemaId;
      if (pubSubTopicPartition.getPubSubTopic().isVersionTopic()) {
        Schema valueSchema = schemaReader.getValueSchema(put.schemaId);
        deserializerProvider =
            Lazy.of(() -> FastSerializerDeserializerFactory.getFastAvroGenericDeserializer(valueSchema, valueSchema));
        readerSchemaId = AvroProtocolDefinition.RECORD_CHANGE_EVENT.getCurrentProtocolVersion();
      } else {
        deserializerProvider = Lazy.of(() -> recordChangeDeserializer);
        readerSchemaId = this.schemaReader.getLatestValueSchemaId();
      }

      // Select compressor. We'll only construct compressors for version topics so this will return null for
      // events from change capture. This is fine as today they are not compressed.
      VeniceCompressor compressor;
      if (pubSubTopicPartition.getPubSubTopic().isVersionTopic()) {
        compressor = compressorMap.get(pubSubTopicPartition.getPartitionNumber());
      } else {
        compressor = NO_OP_COMPRESSOR;
      }

      assembledObject = chunkAssembler.bufferAndAssembleRecord(
          pubSubTopicPartition,
          put.getSchemaId(),
          keyBytes,
          put.getPutValue(),
          message.getOffset(),
          deserializerProvider,
          readerSchemaId,
          compressor);
      if (assembledObject == null) {
        // bufferAndAssembleRecord may have only buffered records and not returned anything yet because
        // it's waiting for more input. In this case, just return an empty optional for now.
        return Optional.empty();
      }

      try {
        assembledObject = processRecordBytes(
            compressor.decompress(put.getPutValue()),
            assembledObject,
            keyBytes,
            put.getPutValue(),
            pubSubTopicPartition,
            readerSchemaId,
            message.getOffset());
      } catch (Exception ex) {
        throw new VeniceException(ex);
      }

      if (assembledObject instanceof RecordChangeEvent) {
        recordChangeEvent = (RecordChangeEvent) assembledObject;
        replicationCheckpoint = recordChangeEvent.replicationCheckpointVector;
      } else {
        replicationCheckpoint =
            extractOffsetVectorFromMessage(put.getReplicationMetadataVersionId(), put.getReplicationMetadataPayload());
      }

      // Now that we've assembled the object, we need to extract the replication vector depending on if it's from VT
      // or from the record change event. Records from VT 'typically' don't have an offset vector, but they will in
      // repush scenarios (which we want to be opaque to the user and filter accordingly).
      int payloadSize = message.getPayloadSize();
      if (assembledObject instanceof RecordChangeEvent) {
        recordChangeEvent = (RecordChangeEvent) assembledObject;
        pubSubChangeEventMessage = Optional.of(
            convertChangeEventToPubSubMessage(
                recordChangeEvent,
                keyDeserializer.deserialize(keyBytes),
                pubSubTopicPartition,
                message.getOffset(),
                message.getPubSubMessageTime(),
                payloadSize));
      } else {
        ChangeEvent<V> changeEvent = new ChangeEvent<>(null, (V) assembledObject);
        pubSubChangeEventMessage = Optional.of(
            new ImmutableChangeCapturePubSubMessage<>(
                keyDeserializer.deserialize(keyBytes),
                changeEvent,
                pubSubTopicPartition,
                message.getOffset(),
                message.getPubSubMessageTime(),
                payloadSize,
                false));
      }
    }

    // Determine if the event should be filtered or not
    if (filterRecordByVersionSwapHighWatermarks(replicationCheckpoint, pubSubTopicPartition)) {
      return Optional.empty();
    }

    return pubSubChangeEventMessage;
  }

  protected List<Long> extractOffsetVectorFromMessage(
      int replicationMetadataVersionId,
      ByteBuffer replicationMetadataPayload) {
    if (replicationMetadataVersionId > 0) {
      MultiSchemaResponse.Schema replicationMetadataSchema =
          replicationMetadataSchemaRepository.getReplicationMetadataSchemaById(storeName, replicationMetadataVersionId);
      RecordDeserializer<GenericRecord> deserializer = SerializerDeserializerFactory
          .getAvroGenericDeserializer(Schema.parse(replicationMetadataSchema.getSchemaStr()));
      GenericRecord replicationMetadataRecord = deserializer.deserialize(replicationMetadataPayload);
      GenericData.Array replicationCheckpointVector =
          (GenericData.Array) replicationMetadataRecord.get(REPLICATION_CHECKPOINT_VECTOR_FIELD_POS);
      List<Long> offsetVector = new ArrayList<>();
      for (Object o: replicationCheckpointVector) {
        offsetVector.add((Long) o);
      }
      return offsetVector;
    }
    return new ArrayList<>();
  }

  protected boolean handleVersionSwapControlMessage(
      ControlMessage controlMessage,
      PubSubTopicPartition pubSubTopicPartition,
      String topicSuffix) {
    ControlMessageType controlMessageType = ControlMessageType.valueOf(controlMessage);
    if (controlMessageType.equals(ControlMessageType.VERSION_SWAP)) {
      VersionSwap versionSwap = (VersionSwap) controlMessage.controlMessageUnion;
      LOGGER.info(
          "Obtain version swap message: {} and versions swap high watermarks: {}",
          versionSwap,
          versionSwap.getLocalHighWatermarks());
      PubSubTopic newServingVersionTopic =
          pubSubTopicRepository.getTopic(versionSwap.newServingVersionTopic.toString());

      // TODO: There seems to exist a condition in the server where highwatermark offsets may regress when transmitting
      // the version swap message
      // it seems like this can potentially happen if a repush occurs and no data is consumed on that previous version.
      // To make the client
      // handle this gracefully, we instate the below condition that says the hwm in the client should never go
      // backwards.
      if (RmdUtils.hasOffsetAdvanced(
          currentVersionHighWatermarks.getOrDefault(pubSubTopicPartition.getPartitionNumber(), Collections.EMPTY_LIST),
          versionSwap.getLocalHighWatermarks())) {
        currentVersionHighWatermarks
            .put(pubSubTopicPartition.getPartitionNumber(), versionSwap.getLocalHighWatermarks());
      }
      switchToNewTopic(newServingVersionTopic, topicSuffix, pubSubTopicPartition.getPartitionNumber());
      chunkAssembler.clearInMemoryDB();
      return true;
    }
    return false;
  }

  private PubSubMessage<K, ChangeEvent<V>, VeniceChangeCoordinate> convertChangeEventToPubSubMessage(
      RecordChangeEvent recordChangeEvent,
      K currentKey,
      PubSubTopicPartition pubSubTopicPartition,
      Long offset,
      Long timestamp,
      int payloadSize) {
    V currentValue = null;
    if (recordChangeEvent.currentValue != null && recordChangeEvent.currentValue.getSchemaId() > 0) {
      currentValuePayloadSize[pubSubTopicPartition.getPartitionNumber()] =
          recordChangeEvent.currentValue.getValue().array().length;
      currentValue = deserializeValueFromBytes(
          recordChangeEvent.currentValue.getValue(),
          recordChangeEvent.currentValue.getSchemaId());
    }
    V previousValue = null;
    if (recordChangeEvent.previousValue != null && recordChangeEvent.previousValue.getSchemaId() > 0) {
      previousValue = deserializeValueFromBytes(
          recordChangeEvent.previousValue.getValue(),
          recordChangeEvent.previousValue.getSchemaId());
    }
    ChangeEvent<V> changeEvent = new ChangeEvent<>(previousValue, currentValue);
    return new ImmutableChangeCapturePubSubMessage<>(
        currentKey,
        changeEvent,
        pubSubTopicPartition,
        offset,
        timestamp,
        payloadSize,
        false);
  }

  private V deserializeValueFromBytes(ByteBuffer byteBuffer, int valueSchemaId) {
    RecordDeserializer<V> valueDeserializer = storeDeserializerCache.getDeserializer(valueSchemaId, valueSchemaId);
    if (byteBuffer != null) {
      return valueDeserializer.deserialize(byteBuffer);
    }
    return null;
  }

  private boolean filterRecordByVersionSwapHighWatermarks(
      List<Long> recordCheckpointVector,
      PubSubTopicPartition pubSubTopicPartition) {
    int partitionId = pubSubTopicPartition.getPartitionNumber();
    if (recordCheckpointVector != null && currentVersionHighWatermarks.containsKey(partitionId)) {
      List<Long> partitionCurrentVersionHighWatermarks =
          currentVersionHighWatermarks.getOrDefault(partitionId, Collections.EMPTY_LIST);
      return !RmdUtils.hasOffsetAdvanced(partitionCurrentVersionHighWatermarks, recordCheckpointVector);
    }
    // Has not met version swap message after client initialization.
    return false;
  }

  protected boolean switchToNewTopic(PubSubTopic newTopic, String topicSuffix, Integer partition) {
    PubSubTopic mergedTopicName = pubSubTopicRepository.getTopic(newTopic.getName() + topicSuffix);
    Set<Integer> partitions = Collections.singleton(partition);
    for (PubSubTopicPartition currentSubscribedPartition: pubSubConsumer.getAssignment()) {
      if (partition.equals(currentSubscribedPartition.getPartitionNumber())) {
        if (mergedTopicName.getName().equals(currentSubscribedPartition.getPubSubTopic().getName())) {
          // We're being asked to switch to a topic that we're already subscribed to, NoOp this
          return false;
        }
      }
    }
    unsubscribe(partitions);
    try {
      internalSubscribe(partitions, mergedTopicName).get();
    } catch (InterruptedException | ExecutionException e) {
      throw new VeniceException("Subscribe to new topic:" + mergedTopicName + " is not successful, error: " + e);
    }
    return true;
  }

  @Override
  public void close() {
    this.unsubscribeAll();
    pubSubConsumer.close();
  }

  @VisibleForTesting
  protected void setStoreRepository(ThinClientMetaStoreBasedRepository repository) {
    this.storeRepository = repository;
    if (changelogClientConfig.getInnerClientConfig().isSpecificClient()) {
      // If a value class is supplied, we'll use a Specific record adapter
      Class valueClass = changelogClientConfig.getInnerClientConfig().getSpecificValueClass();
      this.storeDeserializerCache = new AvroSpecificStoreDeserializerCache<>(storeRepository, storeName, valueClass);
    } else {
      this.storeDeserializerCache = new AvroStoreDeserializerCache<>(storeRepository, storeName, true);
    }
  }

  protected VeniceChangeCoordinate getLatestCoordinate(Integer partition) {
    Set<PubSubTopicPartition> topicPartitionSet = pubSubConsumer.getAssignment();
    Optional<PubSubTopicPartition> topicPartition =
        topicPartitionSet.stream().filter(tp -> tp.getPartitionNumber() == partition).findFirst();
    if (!topicPartition.isPresent()) {
      throw new VeniceException(
          "Cannot get latest coordinate position for partition " + partition + "! Consumer isn't subscribed!");
    }
    long offset = pubSubConsumer.endOffset(topicPartition.get()) - 1;
    return new VeniceChangeCoordinate(
        topicPartition.get().getPubSubTopic().getName(),
        new ApacheKafkaOffsetPosition(offset),
        partition);
  }

  protected PubSubTopicPartition getTopicPartition(Integer partition) {
    Set<PubSubTopicPartition> topicPartitionSet = pubSubConsumer.getAssignment();
    Optional<PubSubTopicPartition> topicPartition =
        topicPartitionSet.stream().filter(tp -> tp.getPartitionNumber() == partition).findFirst();
    if (!topicPartition.isPresent()) {
      throw new VeniceException(
          "Cannot get latest coordinate position for partition " + partition + "! Consumer isn't subscribed!");
    }
    return topicPartition.get();
  }

  protected PubSubConsumerAdapter getPubSubConsumer() {
    return pubSubConsumer;
  }

  protected ChangelogClientConfig getChangelogClientConfig() {
    return changelogClientConfig;
  }
}
