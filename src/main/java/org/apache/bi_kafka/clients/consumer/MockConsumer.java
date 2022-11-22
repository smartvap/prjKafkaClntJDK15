/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.bi_kafka.clients.consumer;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.apache.bi_kafka.clients.consumer.internals.NoOpConsumerRebalanceListener;
import org.apache.bi_kafka.clients.consumer.internals.SubscriptionState;
import org.apache.bi_kafka.common.*;
import org.apache.bi_kafka.common.errors.WakeupException;


/**
 * A mock of the {@link Consumer} interface you can use for testing code that uses Kafka. This class is <i> not
 * threadsafe </i>. However, you can use the {@link #schedulePollTask(Runnable)} method to write multithreaded tests
 * where a driver thread waits for {@link #poll(long)} to be called by a background thread and then can safely perform
 * operations during a callback.
 */
public class MockConsumer<K, V> implements Consumer<K, V> {

    private final Map<String, List<PartitionInfo>> partitions;
    private final SubscriptionState subscriptions;
    private Map<TopicPartition, List<ConsumerRecord<K, V>>> records;
    private Set<TopicPartition> paused;
    private boolean closed;
    private final Map<TopicPartition, Long> beginningOffsets;
    private final Map<TopicPartition, Long> endOffsets;

    private Queue<Runnable> pollTasks;
    private KafkaException exception;

    private AtomicBoolean wakeup;

    public MockConsumer(OffsetResetStrategy offsetResetStrategy) {
        this.subscriptions = new SubscriptionState(offsetResetStrategy);
        this.partitions = new HashMap<String, List<PartitionInfo>>();
        this.records = new HashMap<TopicPartition, List<ConsumerRecord<K, V>>>();
        this.paused = new HashSet<TopicPartition>();
        this.closed = false;
        this.beginningOffsets = new HashMap<TopicPartition, Long>();
        this.endOffsets = new HashMap<TopicPartition, Long>();
        this.pollTasks = new LinkedList<Runnable>();
        this.exception = null;
        this.wakeup = new AtomicBoolean(false);
    }

    public Set<TopicPartition> assignment() {
        return this.subscriptions.assignedPartitions();
    }

    /** Simulate a rebalance event. */
    public void rebalance(Collection<TopicPartition> newAssignment) {
        // TODO: Rebalance callbacks
        this.records.clear();
        this.subscriptions.assignFromSubscribed(newAssignment);
    }

    public Set<String> subscription() {
        return this.subscriptions.subscription();
    }

    public void subscribe(Collection<String> topics) {
        subscribe(topics, new NoOpConsumerRebalanceListener());
    }

    public void subscribe(Pattern pattern, final ConsumerRebalanceListener listener) {
        ensureNotClosed();
        this.subscriptions.subscribe(pattern, listener);
        Set<String> topicsToSubscribe = new HashSet<String>();
        for (String topic: partitions.keySet()) {
            if (pattern.matcher(topic).matches() &&
                !subscriptions.subscription().contains(topic))
                topicsToSubscribe.add(topic);
        }
        ensureNotClosed();
        this.subscriptions.subscribeFromPattern(topicsToSubscribe);
    }

    public void subscribe(Collection<String> topics, final ConsumerRebalanceListener listener) {
        ensureNotClosed();
        this.subscriptions.subscribe(new HashSet<String>(topics), listener);
    }

    public void assign(Collection<TopicPartition> partitions) {
        ensureNotClosed();
        this.subscriptions.assignFromUser(new HashSet<TopicPartition>(partitions));
    }

    public void unsubscribe() {
        ensureNotClosed();
        subscriptions.unsubscribe();
    }

    public ConsumerRecords<K, V> poll(long timeout) {
        ensureNotClosed();

        // Synchronize around the entire execution so new tasks to be triggered on subsequent poll calls can be added in
        // the callback
        synchronized (pollTasks) {
            Runnable task = pollTasks.poll();
            if (task != null)
                task.run();
        }

        if (wakeup.get()) {
            wakeup.set(false);
            throw new WakeupException();
        }

        if (exception != null) {
            RuntimeException exception = this.exception;
            this.exception = null;
            throw exception;
        }

        // Handle seeks that need to wait for a poll() call to be processed
        for (TopicPartition tp : subscriptions.missingFetchPositions())
            updateFetchPosition(tp);

        // update the consumed offset
        for (Map.Entry<TopicPartition, List<ConsumerRecord<K, V>>> entry : this.records.entrySet()) {
            if (!subscriptions.isPaused(entry.getKey())) {
                List<ConsumerRecord<K, V>> recs = entry.getValue();
                if (!recs.isEmpty())
                    this.subscriptions.position(entry.getKey(), recs.get(recs.size() - 1).offset() + 1);
            }
        }

        ConsumerRecords<K, V> copy = new ConsumerRecords<K, V>(this.records);
        this.records = new HashMap<TopicPartition, List<ConsumerRecord<K, V>>>();
        return copy;
    }

    public void addRecord(ConsumerRecord<K, V> record) {
        ensureNotClosed();
        TopicPartition tp = new TopicPartition(record.topic(), record.partition());
        Set<TopicPartition> currentAssigned = new HashSet<TopicPartition>(this.subscriptions.assignedPartitions());
        if (!currentAssigned.contains(tp))
            throw new IllegalStateException("Cannot add records for a partition that is not assigned to the consumer");
        List<ConsumerRecord<K, V>> recs = this.records.get(tp);
        if (recs == null) {
            recs = new ArrayList<ConsumerRecord<K, V>>();
            this.records.put(tp, recs);
        }
        recs.add(record);
    }

    public void setException(KafkaException exception) {
        this.exception = exception;
    }

    public void commitAsync(Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
        ensureNotClosed();
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet())
            subscriptions.committed(entry.getKey(), entry.getValue());
        if (callback != null) {
            callback.onComplete(offsets, null);
        }
    }

    public void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        commitAsync(offsets, null);
    }

    public void commitAsync() {
        commitAsync(null);
    }

    public void commitAsync(OffsetCommitCallback callback) {
        ensureNotClosed();
        commitAsync(this.subscriptions.allConsumed(), callback);
    }

    public void commitSync() {
        commitSync(this.subscriptions.allConsumed());
    }

    public void seek(TopicPartition partition, long offset) {
        ensureNotClosed();
        subscriptions.seek(partition, offset);
    }

    public OffsetAndMetadata committed(TopicPartition partition) {
        ensureNotClosed();
        if (subscriptions.isAssigned(partition)) {
            return subscriptions.committed(partition);
        }
        return new OffsetAndMetadata(0);
    }

    public long position(TopicPartition partition) {
        ensureNotClosed();
        if (!this.subscriptions.isAssigned(partition))
            throw new IllegalArgumentException("You can only check the position for partitions assigned to this consumer.");
        Long offset = this.subscriptions.position(partition);
        if (offset == null) {
            updateFetchPosition(partition);
            offset = this.subscriptions.position(partition);
        }
        return offset;
    }

    public void seekToBeginning(Collection<TopicPartition> partitions) {
        ensureNotClosed();
        for (TopicPartition tp : partitions)
            subscriptions.needOffsetReset(tp, OffsetResetStrategy.EARLIEST);
    }

    public void updateBeginningOffsets(Map<TopicPartition, Long> newOffsets) {
        beginningOffsets.putAll(newOffsets);
    }

    public void seekToEnd(Collection<TopicPartition> partitions) {
        ensureNotClosed();
        for (TopicPartition tp : partitions)
            subscriptions.needOffsetReset(tp, OffsetResetStrategy.LATEST);
    }

    public void updateEndOffsets(Map<TopicPartition, Long> newOffsets) {
        endOffsets.putAll(newOffsets);
    }

    public Map<MetricName, ? extends Metric> metrics() {
        ensureNotClosed();
        return Collections.emptyMap();
    }

    public List<PartitionInfo> partitionsFor(String topic) {
        ensureNotClosed();
        return this.partitions.get(topic);
    }

    public Map<String, List<PartitionInfo>> listTopics() {
        ensureNotClosed();
        return partitions;
    }

    public void updatePartitions(String topic, List<PartitionInfo> partitions) {
        ensureNotClosed();
        this.partitions.put(topic, partitions);
    }

    public void pause(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            subscriptions.pause(partition);
            paused.add(partition);
        }
    }

    public void resume(Collection<TopicPartition> partitions) {
        for (TopicPartition partition : partitions) {
            subscriptions.resume(partition);
            paused.remove(partition);
        }
    }

    public Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes(Map<TopicPartition, Long> timestampsToSearch) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    public Map<TopicPartition, Long> beginningOffsets(Collection<TopicPartition> partitions) {
        Map<TopicPartition, Long> result = new HashMap<TopicPartition, Long>();
        for (TopicPartition tp : partitions) {
            Long beginningOffset = beginningOffsets.get(tp);
            if (beginningOffset == null)
                throw new IllegalStateException("The partition " + tp + " does not have a beginning offset.");
            result.put(tp, beginningOffset);
        }
        return result;
    }

    public Map<TopicPartition, Long> endOffsets(Collection<TopicPartition> partitions) {
        Map<TopicPartition, Long> result = new HashMap<TopicPartition, Long>();
        for (TopicPartition tp : partitions) {
            Long endOffset = endOffsets.get(tp);
            if (endOffset == null)
                throw new IllegalStateException("The partition " + tp + " does not have an end offset.");
            result.put(tp, endOffset);
        }
        return result;
    }

    public void close() {
        close(KafkaConsumer.DEFAULT_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public void close(long timeout, TimeUnit unit) {
        ensureNotClosed();
        this.closed = true;
    }

    public boolean closed() {
        return this.closed;
    }

    public void wakeup() {
        wakeup.set(true);
    }

    /**
     * Schedule a task to be executed during a poll(). One enqueued task will be executed per {@link #poll(long)}
     * invocation. You can use this repeatedly to mock out multiple responses to poll invocations.
     * @param task the task to be executed
     */
    public void schedulePollTask(Runnable task) {
        synchronized (pollTasks) {
            pollTasks.add(task);
        }
    }

    public void scheduleNopPollTask() {
        schedulePollTask(new Runnable() {
            public void run() {
                // noop
            }
        });
    }

    public Set<TopicPartition> paused() {
        return Collections.unmodifiableSet(new HashSet<TopicPartition>(paused));
    }

    private void ensureNotClosed() {
        if (this.closed)
            throw new IllegalStateException("This consumer has already been closed.");
    }

    private void updateFetchPosition(TopicPartition tp) {
        if (subscriptions.isOffsetResetNeeded(tp)) {
            resetOffsetPosition(tp);
        } else if (subscriptions.committed(tp) == null) {
            subscriptions.needOffsetReset(tp);
            resetOffsetPosition(tp);
        } else {
            subscriptions.seek(tp, subscriptions.committed(tp).offset());
        }
    }

    private void resetOffsetPosition(TopicPartition tp) {
        OffsetResetStrategy strategy = subscriptions.resetStrategy(tp);
        Long offset;
        if (strategy == OffsetResetStrategy.EARLIEST) {
            offset = beginningOffsets.get(tp);
            if (offset == null)
                throw new IllegalStateException("MockConsumer didn't have beginning offset specified, but tried to seek to beginning");
        } else if (strategy == OffsetResetStrategy.LATEST) {
            offset = endOffsets.get(tp);
            if (offset == null)
                throw new IllegalStateException("MockConsumer didn't have end offset specified, but tried to seek to end");
        } else {
            throw new NoOffsetForPartitionException(tp);
        }
        seek(tp, offset);
    }
}
