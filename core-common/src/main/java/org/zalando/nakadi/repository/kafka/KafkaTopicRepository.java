package org.zalando.nakadi.repository.kafka;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotLeaderForPartitionException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownServerException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.echocat.jomon.runtime.concurrent.RetryForSpecifiedCountStrategy;
import org.echocat.jomon.runtime.concurrent.RetryForSpecifiedTimeStrategy;
import org.echocat.jomon.runtime.concurrent.Retryer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zalando.nakadi.config.NakadiSettings;
import org.zalando.nakadi.domain.BatchItem;
import org.zalando.nakadi.domain.CleanupPolicy;
import org.zalando.nakadi.domain.EventPublishingStatus;
import org.zalando.nakadi.domain.EventPublishingStep;
import org.zalando.nakadi.domain.NakadiCursor;
import org.zalando.nakadi.domain.NakadiRecord;
import org.zalando.nakadi.domain.NakadiRecordResult;
import org.zalando.nakadi.domain.PartitionEndStatistics;
import org.zalando.nakadi.domain.PartitionStatistics;
import org.zalando.nakadi.domain.Timeline;
import org.zalando.nakadi.exceptions.runtime.CannotAddPartitionToTopicException;
import org.zalando.nakadi.exceptions.runtime.EventPublishingException;
import org.zalando.nakadi.exceptions.runtime.InvalidCursorException;
import org.zalando.nakadi.exceptions.runtime.ServiceTemporarilyUnavailableException;
import org.zalando.nakadi.exceptions.runtime.TopicConfigException;
import org.zalando.nakadi.exceptions.runtime.TopicCreationException;
import org.zalando.nakadi.exceptions.runtime.TopicDeletionException;
import org.zalando.nakadi.exceptions.runtime.TopicRepositoryException;
import org.zalando.nakadi.mapper.NakadiRecordMapper;
import org.zalando.nakadi.repository.EventConsumer;
import org.zalando.nakadi.repository.NakadiTopicConfig;
import org.zalando.nakadi.repository.TopicRepository;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Function;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static java.util.stream.Collectors.toList;
import static org.apache.kafka.common.config.ConfigResource.Type.TOPIC;
import static org.zalando.nakadi.domain.CursorError.NULL_OFFSET;
import static org.zalando.nakadi.domain.CursorError.NULL_PARTITION;
import static org.zalando.nakadi.domain.CursorError.PARTITION_NOT_FOUND;
import static org.zalando.nakadi.domain.CursorError.UNAVAILABLE;

public class KafkaTopicRepository implements TopicRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaTopicRepository.class);

    private final KafkaZookeeper kafkaZookeeper;
    private final KafkaFactory kafkaFactory;
    private final NakadiSettings nakadiSettings;
    private final KafkaSettings kafkaSettings;
    private final KafkaTopicConfigFactory kafkaTopicConfigFactory;
    private final KafkaLocationManager kafkaLocationManager;
    private final NakadiRecordMapper nakadiRecordMapper;

    public KafkaTopicRepository(final Builder builder) {
        this.kafkaZookeeper = builder.kafkaZookeeper;
        this.kafkaFactory = builder.kafkaFactory;
        this.nakadiSettings = builder.nakadiSettings;
        this.kafkaSettings = builder.kafkaSettings;
        this.kafkaLocationManager = builder.kafkaLocationManager;
        this.kafkaTopicConfigFactory = builder.kafkaTopicConfigFactory;
        this.nakadiRecordMapper = builder.nakadiRecordMapper;
    }

    public static class Builder {
        private KafkaZookeeper kafkaZookeeper;
        private KafkaFactory kafkaFactory;
        private NakadiSettings nakadiSettings;
        private KafkaSettings kafkaSettings;
        private KafkaTopicConfigFactory kafkaTopicConfigFactory;
        private KafkaLocationManager kafkaLocationManager;
        private NakadiRecordMapper nakadiRecordMapper;

        public Builder setKafkaZookeeper(final KafkaZookeeper kafkaZookeeper) {
            this.kafkaZookeeper = kafkaZookeeper;
            return this;
        }

        public Builder setKafkaFactory(final KafkaFactory kafkaFactory) {
            this.kafkaFactory = kafkaFactory;
            return this;
        }

        public Builder setNakadiSettings(final NakadiSettings nakadiSettings) {
            this.nakadiSettings = nakadiSettings;
            return this;
        }

        public Builder setKafkaSettings(final KafkaSettings kafkaSettings) {
            this.kafkaSettings = kafkaSettings;
            return this;
        }

        public Builder setKafkaTopicConfigFactory(final KafkaTopicConfigFactory kafkaTopicConfigFactory) {
            this.kafkaTopicConfigFactory = kafkaTopicConfigFactory;
            return this;
        }

        public Builder setKafkaLocationManager(final KafkaLocationManager kafkaLocationManager) {
            this.kafkaLocationManager = kafkaLocationManager;
            return this;
        }

        public Builder setNakadiRecordMapper(final NakadiRecordMapper nakadiRecordMapper) {
            this.nakadiRecordMapper = nakadiRecordMapper;
            return this;
        }

        public KafkaTopicRepository build() {
            return new KafkaTopicRepository(this);
        }
    }

    private CompletableFuture<Exception> publishItem(
            final Producer<byte[], byte[]> producer,
            final String topicId,
            final String eventType,
            final BatchItem item,
            final boolean delete) throws EventPublishingException {
        try {
            final CompletableFuture<Exception> result = new CompletableFuture<>();
            final ProducerRecord<byte[], byte[]> kafkaRecord = new ProducerRecord<>(
                    topicId,
                    KafkaCursor.toKafkaPartition(item.getPartition()),
                    item.getEventKeyBytes(),
                    delete ? null : item.dumpEventToBytes());
            if (null != item.getOwner()) {
                item.getOwner().serialize(kafkaRecord);
            }

            item.setStep(EventPublishingStep.PUBLISHING);

            producer.send(kafkaRecord, ((metadata, exception) -> {
                if (null != exception) {
                    LOG.warn("Failed to publish to kafka topic {}", topicId, exception);
                    item.updateStatusAndDetail(EventPublishingStatus.FAILED, "internal error");
                    result.complete(exception);
                } else {
                    item.updateStatusAndDetail(EventPublishingStatus.SUBMITTED, "");
                    result.complete(null);
                }
            }));
            return result;
        } catch (final InterruptException e) {
            Thread.currentThread().interrupt();
            item.updateStatusAndDetail(EventPublishingStatus.FAILED, "internal error");
            throw new EventPublishingException("Error publishing message to kafka", e, topicId, eventType);
        } catch (final RuntimeException e) {
            kafkaFactory.terminateProducer(producer);
            item.updateStatusAndDetail(EventPublishingStatus.FAILED, "internal error");
            throw new EventPublishingException("Error publishing message to kafka", e, topicId, eventType);
        }
    }

    private static boolean isExceptionShouldLeadToReset(@Nullable final Exception exception) {
        if (null == exception) {
            return false;
        }
        return Stream.of(NotLeaderForPartitionException.class, UnknownTopicOrPartitionException.class,
                org.apache.kafka.common.errors.TimeoutException.class, NetworkException.class,
                UnknownServerException.class)
                .anyMatch(clazz -> clazz.isAssignableFrom(exception.getClass()));
    }

    public List<String> listTopics() throws TopicRepositoryException {
        try {
            return kafkaZookeeper.listTopics();
        } catch (final Exception e) {
            throw new TopicRepositoryException("Failed to list topics", e);
        }
    }

    @Override
    public void repartition(final String topic, final int partitionsNumber) throws CannotAddPartitionToTopicException,
            TopicConfigException {
        try (AdminClient adminClient = AdminClient.create(kafkaLocationManager.getProperties())) {
            adminClient.createPartitions(ImmutableMap.of(topic, NewPartitions.increaseTo(partitionsNumber)));
            final long timeoutMillis = TimeUnit.SECONDS.toMillis(5);
            final Boolean areNewPartitionsAdded = Retryer.executeWithRetry(() -> {
                        try (Consumer<byte[], byte[]> consumer = kafkaFactory.getConsumer()) {
                            return consumer.partitionsFor(topic).size() == partitionsNumber;
                        }
                    },
                    new RetryForSpecifiedTimeStrategy<Boolean>(timeoutMillis)
                            .withWaitBetweenEachTry(100L)
                            .withResultsThatForceRetry(Boolean.FALSE));
            if (!Boolean.TRUE.equals(areNewPartitionsAdded)) {
                throw new TopicConfigException(String.format("Failed to repartition topic to %s", partitionsNumber));
            }
            final Producer<byte[], byte[]> producer = kafkaFactory.takeProducer();
            kafkaFactory.terminateProducer(producer);
            kafkaFactory.releaseProducer(producer);
        } catch (Exception e) {
            throw new CannotAddPartitionToTopicException(String
                    .format("Failed to increase the number of partition for %s topic to %s", topic,
                            partitionsNumber), e);
        }
    }

    @Override
    public String createTopic(final NakadiTopicConfig nakadiTopicConfig) throws TopicCreationException {

        final KafkaTopicConfig kafkaTopicConfig = kafkaTopicConfigFactory.createKafkaTopicConfig(nakadiTopicConfig);
        try (AdminClient adminClient = AdminClient.create(kafkaLocationManager.getProperties())) {
            final NewTopic newTopic = new NewTopic(
                    kafkaTopicConfig.getTopicName(),
                    Optional.of(kafkaTopicConfig.getPartitionCount()),
                    Optional.of((short) kafkaTopicConfig.getReplicaFactor()));
            newTopic.configs(kafkaTopicConfigFactory.createKafkaTopicLevelProperties(kafkaTopicConfig));

            adminClient.createTopics(Lists.newArrayList(newTopic)).all().get(30, TimeUnit.SECONDS);
        } catch (final TopicExistsException e) {
            throw new TopicCreationException("Topic with name " + kafkaTopicConfig.getTopicName() +
                    " already exists (or wasn't completely removed yet)", e);
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TopicCreationException("Unable to create topic " + kafkaTopicConfig.getTopicName(), e);
        }
        // Next step is to wait for topic initialization. On can not skip this task, cause kafka instances may not
        // receive information about topic creation, which in turn will block publishing.
        // This kind of behavior was observed during tests, but may also present on highly loaded event types.
        final long timeoutMillis = TimeUnit.SECONDS.toMillis(5);
        final Boolean allowsConsumption = Retryer.executeWithRetry(() -> {
                    try (Consumer<byte[], byte[]> consumer = kafkaFactory.getConsumer()) {
                        return null != consumer.partitionsFor(kafkaTopicConfig.getTopicName());
                    }
                },
                new RetryForSpecifiedTimeStrategy<Boolean>(timeoutMillis)
                        .withWaitBetweenEachTry(100L)
                        .withResultsThatForceRetry(Boolean.FALSE));
        if (!Boolean.TRUE.equals(allowsConsumption)) {
            throw new TopicCreationException("Failed to confirm topic creation within " + timeoutMillis + " millis");
        }

        return kafkaTopicConfig.getTopicName();
    }

    @Override
    public void deleteTopic(final String topic) throws TopicDeletionException {
        try (AdminClient adminClient = AdminClient.create(kafkaLocationManager.getProperties())) {
            // this will only trigger topic deletion, but the actual deletion is asynchronous
            adminClient.deleteTopics(Lists.newArrayList(topic)).all().get(30, TimeUnit.SECONDS);
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TopicDeletionException("Unable to delete topic " + topic, e);
        }
    }

    @Override
    public boolean topicExists(final String topic) throws TopicRepositoryException {
        return listTopics()
                .stream()
                .anyMatch(t -> t.equals(topic));
    }

    @Override
    public void syncPostBatch(
            final String topicId, final List<BatchItem> batch, final String eventType, final boolean delete)
            throws EventPublishingException {

        final Producer<byte[], byte[]> producer = kafkaFactory.takeProducer();
        try {
            batch.forEach(item -> {
                Preconditions.checkNotNull(
                        item.getPartition(), "BatchItem partition can't be null at the moment of publishing!");
            });

            final Function<BatchItem, CompletableFuture<Exception>> sendItem =
                    item -> publishItem(producer, topicId, eventType, item, delete);

            final List<CompletableFuture<Exception>> futures = new LinkedList<>();
            final Map<String, CompletableFuture<Exception>> futuresByKey = new HashMap<>();

            for (final BatchItem item : batch) {
                final String itemKey = item.getEventKey();
                if (itemKey == null) {
                    // items without a key go out of order
                    futures.add(sendItem.apply(item));
                } else {
                    // items for a single key are composed together for sequential publishing
                    CompletableFuture<Exception> fut = futuresByKey.get(itemKey);
                    if (fut == null) {
                        fut = sendItem.apply(item);
                    } else {
                        fut = fut.thenCompose(e -> {
                                    if (e != null) {
                                        item.updateStatusAndDetail(EventPublishingStatus.ABORTED,
                                                "earlier item failed for key: " + itemKey);
                                        return CompletableFuture.completedStage(e);
                                    } else {
                                        return sendItem.apply(item);
                                    }
                                });
                    }
                    futuresByKey.put(itemKey, fut);
                }
            }
            // we will wait for all futures together
            futures.addAll(futuresByKey.values());

            final CompletableFuture<Void> multiFuture = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture<?>[futures.size()]));
            multiFuture.get(createSendTimeout(), TimeUnit.MILLISECONDS);

            // Now lets check for errors
            final Optional<Exception> needReset = futures.stream()
                    .map(fut -> fut.getNow(null))
                    .filter(KafkaTopicRepository::isExceptionShouldLeadToReset)
                    .findAny();
            if (needReset.isPresent()) {
                LOG.info("Terminating producer while publishing to topic {} because of unrecoverable exception",
                        topicId, needReset.get());
                kafkaFactory.terminateProducer(producer);
            }
        } catch (final TimeoutException ex) {
            kafkaFactory.terminateProducer(producer);
            failUnpublished(topicId, eventType, batch, "timed out");
            throw new EventPublishingException("Timeout publishing message to kafka", ex, topicId, eventType);
        } catch (final ExecutionException ex) {
            failUnpublished(topicId, eventType, batch, "internal error");
            throw new EventPublishingException("Internal error publishing message to kafka", ex, topicId, eventType);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            failUnpublished(topicId, eventType, batch, "interrupted");
            throw new EventPublishingException("Interrupted publishing message to kafka", ex, topicId, eventType);
        } finally {
            kafkaFactory.releaseProducer(producer);
        }
        final boolean atLeastOneFailed = batch.stream()
                .anyMatch(item -> item.getResponse().getPublishingStatus() == EventPublishingStatus.FAILED);
        if (atLeastOneFailed) {
            failUnpublished(topicId, eventType, batch, "internal error");
            throw new EventPublishingException("Internal error when publishing to kafka", topicId, eventType);
        }
    }

    private long createSendTimeout() {
        return nakadiSettings.getKafkaSendTimeoutMs() + kafkaSettings.getRequestTimeoutMs();
    }

    private void failUnpublished(final String topicId, final String eventType, final List<BatchItem> batch,
                                 final String reason) {
        logFailedEvents(topicId, eventType, batch);
        batch.stream()
                .filter(item -> item.getResponse().getPublishingStatus() != EventPublishingStatus.SUBMITTED)
                .filter(item -> item.getResponse().getDetail().isEmpty())
                .forEach(item -> item.updateStatusAndDetail(EventPublishingStatus.FAILED, reason));
    }

    private void logFailedEvents(final String topicId, final String eventType, final List<BatchItem> batch) {
        final Map<String, List<BatchItem>> itemsPerPartition = new HashMap<>();
        for (final BatchItem item : batch) {
            itemsPerPartition.computeIfAbsent(item.getPartition(), (k) -> new LinkedList<>()).add(item);
        }

        final StringBuilder sb = new StringBuilder();
        for (final Map.Entry<String, List<BatchItem>> entry : itemsPerPartition.entrySet()) {
            final String publishingResult = entry.getValue().stream()
                    .map(i -> i.getResponse().getPublishingStatus() == EventPublishingStatus.SUBMITTED ? "1" : "0")
                    .collect(Collectors.joining(", "));
            sb.append(entry.getKey())
                    .append(":[")
                    .append(publishingResult)
                    .append("] ");
        }
        LOG.info("Failed events in batch for topic {} / {}: {}", topicId, eventType, sb.toString());
    }

    /**
     * The method sends list of events to Kafka and waiting for the result of each event.
     *
     * @param nakadiRecords list of the events to publish
     * @return empty list if no errors otherwise list with the errored events
     */
    public List<NakadiRecordResult> sendEvents(final String topic,
                                               final List<NakadiRecord> nakadiRecords) {
        final Producer<byte[], byte[]> producer = kafkaFactory.takeProducer();
        final Map<NakadiRecord, NakadiRecordResult> responses = new ConcurrentHashMap<>();

        final Function<NakadiRecord, CompletableFuture<NakadiRecordResult>> sendItem = nakadiRecord ->
                sendRecord(producer, topic, nakadiRecord)
                .thenApply(result -> {
                            responses.put(nakadiRecord, result);
                            return result;
                        });

        final List<CompletableFuture<NakadiRecordResult>> futures = new LinkedList<>();
        final Map<String, CompletableFuture<NakadiRecordResult>> futuresByKey = new HashMap<>();
        try {
            for (final NakadiRecord nakadiRecord : nakadiRecords) {
                final String eventKey = nakadiRecord.getEventKey();
                if (eventKey == null) {
                    futures.add(sendItem.apply(nakadiRecord));
                } else {
                    CompletableFuture<NakadiRecordResult> fut = futuresByKey.get(eventKey);
                    if (fut == null) {
                        fut = sendItem.apply(nakadiRecord);
                    } else {
                        fut = fut.thenCompose(r -> {
                                    if (r.getStatus() != NakadiRecordResult.Status.SUCCEEDED) {
                                        return CompletableFuture.completedStage(
                                                new NakadiRecordResult(
                                                        nakadiRecord.getMetadata(),
                                                        NakadiRecordResult.Status.ABORTED,
                                                        NakadiRecordResult.Step.PUBLISHING,
                                                        r.getException()));
                                    } else {
                                        return sendItem.apply(nakadiRecord);
                                    }
                                });
                    }
                    futuresByKey.put(eventKey, fut);
                }
            }

            futures.addAll(futuresByKey.values());

            final CompletableFuture<Void> multiFuture = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture<?>[futures.size()]));
            multiFuture.get(createSendTimeout(), TimeUnit.MILLISECONDS);

            final boolean shouldResetProducer = responses.values().stream()
                    .filter(r -> r.getStatus() == NakadiRecordResult.Status.FAILED)
                    .map(NakadiRecordResult::getException)
                    .anyMatch(KafkaTopicRepository::isExceptionShouldLeadToReset);
            if (shouldResetProducer) {
                kafkaFactory.terminateProducer(producer);
            }

            return prepareResponse(nakadiRecords, responses, null);

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return prepareResponse(nakadiRecords, responses, e);
        } catch (final TimeoutException te) {
            kafkaFactory.terminateProducer(producer);
            return prepareResponse(nakadiRecords, responses, te);
        } catch (final ExecutionException ee) {
            return prepareResponse(nakadiRecords, responses, ee);
        } catch (final RuntimeException re) {
            return prepareResponse(nakadiRecords, responses, re);
        } finally {
            kafkaFactory.releaseProducer(producer);
        }
    }

    private CompletableFuture<NakadiRecordResult> sendRecord(
            final Producer<byte[], byte[]> producer, final String topic, final NakadiRecord nakadiRecord) {

        final ProducerRecord<byte[], byte[]> producerRecord;
        try {
            producerRecord = nakadiRecordMapper.mapToProducerRecord(nakadiRecord, topic);
        } catch (final IOException ioe) {
            throw new RuntimeException("Failed to serialize a record for publishing to Kafka", ioe);
        }
        if (null != nakadiRecord.getOwner()) {
            nakadiRecord.getOwner().serialize(producerRecord);
        }

        final CompletableFuture<NakadiRecordResult> future = new CompletableFuture<>();
        producer.send(producerRecord, (metadata, exception) -> {
                    final NakadiRecordResult result;
                    if (exception != null) {
                        result = new NakadiRecordResult(
                                nakadiRecord.getMetadata(),
                                NakadiRecordResult.Status.FAILED,
                                NakadiRecordResult.Step.PUBLISHING,
                                exception);
                    } else {
                        result = new NakadiRecordResult(
                                nakadiRecord.getMetadata(),
                                NakadiRecordResult.Status.SUCCEEDED,
                                NakadiRecordResult.Step.PUBLISHING);
                    }
                    future.complete(result);
                });
        return future;
    }

    private List<NakadiRecordResult> prepareResponse(
            final List<NakadiRecord> nakadiRecords,
            final Map<NakadiRecord, NakadiRecordResult> recordStatuses,
            final Exception exception) {
        final List<NakadiRecordResult> resps = new LinkedList<>();
        for (final NakadiRecord record : nakadiRecords) {
            final NakadiRecordResult nakadiRecordResult = recordStatuses.get(record);
            if (nakadiRecordResult == null) {
                // in case kafka producer send method threw exception, the record was not attempted for publishing
                resps.add(new NakadiRecordResult(
                        record.getMetadata(),
                        NakadiRecordResult.Status.ABORTED,
                        NakadiRecordResult.Step.PUBLISHING,
                        exception));
            } else {
                resps.add(nakadiRecordResult);
            }
        }

        return resps;
    }

    @Override
    public Optional<PartitionStatistics> loadPartitionStatistics(final Timeline timeline, final String partition)
            throws ServiceTemporarilyUnavailableException {
        return loadPartitionStatistics(Collections.singletonList(new TimelinePartition(timeline, partition))).get(0);
    }

    @Override
    public List<Optional<PartitionStatistics>> loadPartitionStatistics(
            final Collection<TimelinePartition> partitions) throws ServiceTemporarilyUnavailableException {
        try {
            return Retryer.executeWithRetry(() -> {
                        return loadPartitionStatisticsInternal(partitions);
                    },
                    new RetryForSpecifiedCountStrategy(3)
                            .withWaitBetweenEachTry(5000)
                            .withExceptionsThatForceRetry(org.apache.kafka.common.errors.TimeoutException.class));
        } catch (final RuntimeException e) {
            throw new ServiceTemporarilyUnavailableException("Error occurred when fetching partitions offsets", e);
        }
    }

    public List<Optional<PartitionStatistics>> loadPartitionStatisticsInternal(
            final Collection<TimelinePartition> partitions) {
        final Map<String, Set<String>> topicToPartitions = partitions.stream().collect(
                Collectors.groupingBy(
                        tp -> tp.getTimeline().getTopic(),
                        Collectors.mapping(TimelinePartition::getPartition, Collectors.toSet())
                ));
        try (Consumer<byte[], byte[]> consumer = kafkaFactory.getConsumer()) {
            final List<PartitionInfo> allKafkaPartitions = topicToPartitions.keySet().stream()
                    .map(consumer::partitionsFor)
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList());
            final List<TopicPartition> partitionsToQuery = allKafkaPartitions.stream()
                    .filter(pi -> topicToPartitions.get(pi.topic())
                            .contains(KafkaCursor.toNakadiPartition(pi.partition())))
                    .map(pi -> new TopicPartition(pi.topic(), pi.partition()))
                    .collect(Collectors.toList());

            consumer.assign(partitionsToQuery);
            consumer.seekToBeginning(partitionsToQuery);
            final List<Long> begins = partitionsToQuery.stream().map(consumer::position).collect(toList());
            consumer.seekToEnd(partitionsToQuery);
            final List<Long> ends = partitionsToQuery.stream().map(consumer::position).collect(toList());

            final List<Optional<PartitionStatistics>> result = new ArrayList<>(partitions.size());
            for (final TimelinePartition tap : partitions) {
                // Now search for an index.
                final Optional<PartitionStatistics> itemResult = IntStream.range(0, partitionsToQuery.size())
                        .filter(i -> {
                            final TopicPartition info = partitionsToQuery.get(i);
                            return info.topic().equals(tap.getTimeline().getTopic()) &&
                                    info.partition() == KafkaCursor.toKafkaPartition(tap.getPartition());
                        }).mapToObj(indexFound -> (PartitionStatistics) new KafkaPartitionStatistics(
                                tap.getTimeline(),
                                partitionsToQuery.get(indexFound).partition(),
                                begins.get(indexFound),
                                ends.get(indexFound) - 1L))
                        .findAny();
                result.add(itemResult);
            }
            return result;
        }
    }

    @Override
    public List<PartitionStatistics> loadTopicStatistics(final Collection<Timeline> timelines)
            throws ServiceTemporarilyUnavailableException {
        try {
            return Retryer.executeWithRetry(() -> {
                        return loadTopicStatisticsInternal(timelines);
                    },
                    new RetryForSpecifiedCountStrategy(3)
                            .withWaitBetweenEachTry(5000)
                            .withExceptionsThatForceRetry(org.apache.kafka.common.errors.TimeoutException.class));
        } catch (final RuntimeException e) {
            throw new ServiceTemporarilyUnavailableException("Error occurred when fetching partitions offsets", e);
        }
    }

    public List<PartitionStatistics> loadTopicStatisticsInternal(final Collection<Timeline> timelines) {
        try (Consumer<byte[], byte[]> consumer = kafkaFactory.getConsumer()) {
            final Map<TopicPartition, Timeline> backMap = new HashMap<>();
            for (final Timeline timeline : timelines) {
                consumer.partitionsFor(timeline.getTopic())
                        .stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition()))
                        .forEach(tp -> backMap.put(tp, timeline));
            }
            final List<TopicPartition> kafkaTPs = new ArrayList<>(backMap.keySet());
            consumer.assign(kafkaTPs);
            consumer.seekToBeginning(kafkaTPs);
            final long[] begins = kafkaTPs.stream().mapToLong(consumer::position).toArray();

            consumer.seekToEnd(kafkaTPs);
            final long[] ends = kafkaTPs.stream().mapToLong(consumer::position).toArray();

            return IntStream.range(0, kafkaTPs.size())
                    .mapToObj(i -> new KafkaPartitionStatistics(
                            backMap.get(kafkaTPs.get(i)),
                            kafkaTPs.get(i).partition(),
                            begins[i],
                            ends[i] - 1))
                    .collect(toList());
        }
    }

    @Override
    public List<PartitionEndStatistics> loadTopicEndStatistics(final Collection<Timeline> timelines)
            throws ServiceTemporarilyUnavailableException {
        try {
            return Retryer.executeWithRetry(() -> {
                        return loadTopicEndStatisticsInternal(timelines);
                    },
                    new RetryForSpecifiedCountStrategy(3)
                            .withWaitBetweenEachTry(5000)
                            .withExceptionsThatForceRetry(org.apache.kafka.common.errors.TimeoutException.class));
        } catch (final RuntimeException e) {
            throw new ServiceTemporarilyUnavailableException("Error occurred when fetching partitions offsets", e);
        }
    }

    private List<PartitionEndStatistics> loadTopicEndStatisticsInternal(final Collection<Timeline> timelines) {
        try (Consumer<byte[], byte[]> consumer = kafkaFactory.getConsumer()) {
            final Map<TopicPartition, Timeline> backMap = new HashMap<>();
            for (final Timeline timeline : timelines) {
                consumer.partitionsFor(timeline.getTopic())
                        .stream()
                        .map(p -> new TopicPartition(p.topic(), p.partition()))
                        .forEach(tp -> backMap.put(tp, timeline));
            }
            final List<TopicPartition> kafkaTPs = Lists.newArrayList(backMap.keySet());
            consumer.assign(kafkaTPs);
            consumer.seekToEnd(kafkaTPs);
            return backMap.entrySet().stream()
                    .map(e -> {
                        final TopicPartition tp = e.getKey();
                        final Timeline timeline = e.getValue();
                        return new KafkaPartitionEndStatistics(timeline, tp.partition(), consumer.position(tp) - 1);
                    })
                    .collect(toList());
        }
    }

    @Override
    public List<String> listPartitionNames(final String topicId) {
        return Retryer.executeWithRetry(() -> {
                    return listPartitionNamesInternal(topicId);
                },
                new RetryForSpecifiedCountStrategy(3)
                        .withWaitBetweenEachTry(5000)
                        .withExceptionsThatForceRetry(org.apache.kafka.common.errors.TimeoutException.class));
    }

    @Override
    public Map<org.zalando.nakadi.domain.TopicPartition, Long> getSizeStats() {
        final Map<org.zalando.nakadi.domain.TopicPartition, Long> result = new HashMap<>();

        try {
            final List<String> brokers = kafkaZookeeper.getBrokerIdsForSizeStats();

            for (final String brokerId : brokers) {
                final BubukuSizeStats stats = kafkaZookeeper.getSizeStatsForBroker(brokerId);
                stats.getPerPartitionStats().forEach((topic, partitionSizes) -> {
                    partitionSizes.forEach((partition, size) -> {
                        final org.zalando.nakadi.domain.TopicPartition tp =
                                new org.zalando.nakadi.domain.TopicPartition(topic, partition);

                        result.compute(tp, (ignore, oldSize) ->
                                Optional.ofNullable(oldSize).map(v -> Math.max(oldSize, size)).orElse(size));
                    });
                });
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to acquire size statistics", e);
        }
    }

    public List<String> listPartitionNamesInternal(final String topicId) {
        final Producer<byte[], byte[]> producer = kafkaFactory.takeProducer();
        try {
            return unmodifiableList(producer.partitionsFor(topicId)
                    .stream()
                    .map(partitionInfo -> KafkaCursor.toNakadiPartition(partitionInfo.partition()))
                    .collect(toList()));
        } finally {
            kafkaFactory.releaseProducer(producer);
        }
    }

    @Override
    public EventConsumer.LowLevelConsumer createEventConsumer(
            @Nullable final String clientId,
            final List<NakadiCursor> cursors)
            throws ServiceTemporarilyUnavailableException, InvalidCursorException {

        final Map<NakadiCursor, KafkaCursor> cursorMapping = convertToKafkaCursors(cursors);
        final Map<TopicPartition, Timeline> timelineMap = cursorMapping.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> new TopicPartition(entry.getValue().getTopic(), entry.getValue().getPartition()),
                        entry -> entry.getKey().getTimeline(),
                        (v1, v2) -> v2));
        final List<KafkaCursor> kafkaCursors = cursorMapping.values().stream()
                .map(kafkaCursor -> kafkaCursor.addOffset(1))
                .collect(toList());

        return new NakadiKafkaConsumer(
                kafkaFactory.getConsumer(clientId),
                kafkaCursors,
                timelineMap,
                nakadiSettings.getKafkaPollTimeoutMs());

    }

    @Override
    public void validateReadCursors(final List<NakadiCursor> cursors)
            throws InvalidCursorException, ServiceTemporarilyUnavailableException {
        convertToKafkaCursors(cursors);
    }

    private Map<NakadiCursor, KafkaCursor> convertToKafkaCursors(final List<NakadiCursor> cursors)
            throws ServiceTemporarilyUnavailableException, InvalidCursorException {
        final List<Timeline> timelines = cursors.stream().map(NakadiCursor::getTimeline).distinct().collect(toList());
        final List<PartitionStatistics> statistics = loadTopicStatistics(timelines);

        final Map<NakadiCursor, KafkaCursor> result = new HashMap<>();
        for (final NakadiCursor position : cursors) {
            validateCursorForNulls(position);
            final Optional<PartitionStatistics> partition =
                    statistics.stream().filter(t -> Objects.equals(t.getPartition(), position.getPartition()))
                            .filter(t -> Objects.equals(t.getTimeline().getTopic(), position.getTopic()))
                            .findAny();
            if (!partition.isPresent()) {
                throw new InvalidCursorException(PARTITION_NOT_FOUND, position);
            }
            final KafkaCursor toCheck = position.asKafkaCursor();

            // Checking oldest position
            final KafkaCursor oldestCursor = KafkaCursor.fromNakadiCursor(partition.get().getBeforeFirst());
            if (toCheck.compareTo(oldestCursor) < 0) {
                throw new InvalidCursorException(UNAVAILABLE, position);
            }
            // checking newest position
            final KafkaCursor newestPosition = KafkaCursor.fromNakadiCursor(partition.get().getLast());
            if (toCheck.compareTo(newestPosition) > 0) {
                throw new InvalidCursorException(UNAVAILABLE, position);
            } else {
                result.put(position, toCheck);
            }
        }
        return result;
    }

    @Override
    public void updateTopicConfig(final String topic, final Long retentionMs, final CleanupPolicy cleanupPolicy)
            throws TopicConfigException {
        try (AdminClient adminClient = AdminClient.create(kafkaLocationManager.getProperties())) {
            final KafkaTopicConfigBuilder builder = new KafkaTopicConfigBuilder();
            kafkaTopicConfigFactory.configureCleanupPolicy(builder, cleanupPolicy);
            builder.withRetentionMs(retentionMs);
            final KafkaTopicConfig config = builder.build();
            final Map<String, String> configMap = kafkaTopicConfigFactory.createKafkaTopicLevelProperties(config);
            final ConfigResource configResource = new ConfigResource(TOPIC, topic);
            final Map<ConfigResource, Collection<AlterConfigOp>> configs = new HashMap<>();
            final List<AlterConfigOp> alterConfigOps = configMap
                    .entrySet()
                    .stream()
                    .map(entry -> new AlterConfigOp(
                            new ConfigEntry(entry.getKey(), entry.getValue()),
                            AlterConfigOp.OpType.SET))
                    .collect(Collectors.toList());

            configs.put(configResource, alterConfigOps);
            adminClient.incrementalAlterConfigs(configs).all().get(30, TimeUnit.SECONDS);
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new TopicConfigException("Unable to update retention time for topic " + topic, e);
        }
    }

    private void validateCursorForNulls(final NakadiCursor cursor) throws InvalidCursorException {
        if (cursor.getPartition() == null) {
            throw new InvalidCursorException(NULL_PARTITION, cursor);
        }
        if (cursor.getOffset() == null) {
            throw new InvalidCursorException(NULL_OFFSET, cursor);
        }
    }
}
