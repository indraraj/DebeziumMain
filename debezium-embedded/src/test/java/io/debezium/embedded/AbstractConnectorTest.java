/*
 * Copyright Debezium Authors.
 * 
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.embedded;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.json.JsonDeserializer;
import org.apache.kafka.connect.source.SourceConnector;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.storage.FileOffsetBackingStore;
import org.junit.After;
import org.junit.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.fest.assertions.Assertions.assertThat;

import io.debezium.config.Configuration;
import io.debezium.data.VerifyRecord;
import io.debezium.embedded.EmbeddedEngine.CompletionCallback;
import io.debezium.function.BooleanConsumer;
import io.debezium.relational.history.HistoryRecord;
import io.debezium.util.Testing;

/**
 * An abstract base class for unit testing {@link SourceConnector} implementations using the Debezium {@link EmbeddedEngine}
 * with local file storage.
 * <p>
 * To use this abstract class, simply create a test class that extends it, and add one or more test methods that
 * {@link #start(Class, Configuration) starts the connector} using your connector's custom configuration.
 * Then, your test methods can call {@link #consumeRecords(int, Consumer)} to consume the specified number
 * of records (the supplied function gives you a chance to do something with the record).
 * 
 * @author Randall Hauch
 */
public abstract class AbstractConnectorTest implements Testing {

    protected static final Path OFFSET_STORE_PATH = Testing.Files.createTestingPath("file-connector-offsets.txt").toAbsolutePath();

    private ExecutorService executor;
    private EmbeddedEngine engine;
    private BlockingQueue<SourceRecord> consumedLines;
    protected long pollTimeoutInMs = TimeUnit.SECONDS.toMillis(5);
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private CountDownLatch latch;
    private JsonConverter keyJsonConverter = new JsonConverter();
    private JsonConverter valueJsonConverter = new JsonConverter();
    private JsonDeserializer keyJsonDeserializer = new JsonDeserializer();
    private JsonDeserializer valueJsonDeserializer = new JsonDeserializer();

    @Before
    public final void initializeConnectorTestFramework() {
        keyJsonConverter = new JsonConverter();
        valueJsonConverter = new JsonConverter();
        keyJsonDeserializer = new JsonDeserializer();
        valueJsonDeserializer = new JsonDeserializer();
        Configuration converterConfig = Configuration.create().build();
        Configuration deserializerConfig = Configuration.create().build();
        keyJsonConverter.configure(converterConfig.asMap(), true);
        valueJsonConverter.configure(converterConfig.asMap(), false);
        keyJsonDeserializer.configure(deserializerConfig.asMap(), true);
        valueJsonDeserializer.configure(deserializerConfig.asMap(), false);

        resetBeforeEachTest();
        consumedLines = new ArrayBlockingQueue<>(getMaximumEnqueuedRecordCount());
        Testing.Files.delete(OFFSET_STORE_PATH);
    }

    /**
     * Stop the connector and block until the connector has completely stopped.
     */
    @After
    public final void stopConnector() {
        stopConnector(null);
    }

    /**
     * Stop the connector, and return whether the connector was successfully stopped.
     * 
     * @param callback the function that should be called with whether the connector was successfully stopped; may be null
     */
    public void stopConnector(BooleanConsumer callback) {
        try {
            // Try to stop the connector ...
            if (engine != null && engine.isRunning()) {
                engine.stop();
                try {
                    engine.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            if (executor != null) {
                List<Runnable> neverRunTasks = executor.shutdownNow();
                assertThat(neverRunTasks).isEmpty();
                try {
                    while (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                        // wait for completion ...
                    }
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            if (engine != null && engine.isRunning()) {
                try {
                    while (!engine.await(5, TimeUnit.SECONDS)) {
                        // Wait for connector to stop completely ...
                    }
                } catch (InterruptedException e) {
                    Thread.interrupted();
                }
            }
            if (callback != null) callback.accept(engine != null ? engine.isRunning() : false);
        } finally {
            engine = null;
            executor = null;
        }
    }

    /**
     * Get the maximum number of messages that can be obtained from the connector and held in-memory before they are
     * consumed by test methods using {@link #consumeRecord()}, {@link #consumeRecords(int)}, or
     * {@link #consumeRecords(int, Consumer)}.
     * 
     * <p>
     * By default this method return {@code 100}.
     * 
     * @return the maximum number of records that can be enqueued
     */
    protected int getMaximumEnqueuedRecordCount() {
        return 100;
    }

    /**
     * Start the connector using the supplied connector configuration, where upon completion the status of the connector is
     * logged.
     * 
     * @param connectorClass the connector class; may not be null
     * @param connectorConfig the configuration for the connector; may not be null
     */
    protected void start(Class<? extends SourceConnector> connectorClass, Configuration connectorConfig) {
        start(connectorClass, connectorConfig, (success, msg, error) -> {
            if (success) {
                logger.info(msg);
            } else {
                logger.error(msg, error);
            }
        });
    }

    /**
     * Start the connector using the supplied connector configuration.
     * 
     * @param connectorClass the connector class; may not be null
     * @param connectorConfig the configuration for the connector; may not be null
     * @param callback the function that will be called when the engine fails to start the connector or when the connector
     *            stops running after completing successfully or due to an error; may be null
     */
    protected void start(Class<? extends SourceConnector> connectorClass, Configuration connectorConfig, CompletionCallback callback) {
        Configuration config = Configuration.copy(connectorConfig)
                                            .with(EmbeddedEngine.ENGINE_NAME, "testing-connector")
                                            .with(EmbeddedEngine.CONNECTOR_CLASS, connectorClass.getName())
                                            .with(FileOffsetBackingStore.OFFSET_STORAGE_FILE_FILENAME_CONFIG, OFFSET_STORE_PATH)
                                            .with(EmbeddedEngine.OFFSET_FLUSH_INTERVAL_MS, 0)
                                            .build();
        latch = new CountDownLatch(1);
        CompletionCallback wrapperCallback = (success, msg, error) -> {
            try {
                if (callback != null) callback.handle(success, msg, error);
            } finally {
                latch.countDown();
            }
        };

        // Create the connector ...
        engine = EmbeddedEngine.create()
                               .using(config)
                               .notifying((record) -> {
                                   try {
                                       consumedLines.put(record);
                                   } catch (InterruptedException e) {
                                       Thread.interrupted();
                                   }
                               })
                               .using(this.getClass().getClassLoader())
                               .using(wrapperCallback)
                               .build();

        // Submit the connector for asynchronous execution ...
        assertThat(executor).isNull();
        executor = Executors.newFixedThreadPool(1);
        executor.execute(engine);
    }

    /**
     * Set the maximum amount of time that the {@link #consumeRecord()}, {@link #consumeRecords(int)}, and
     * {@link #consumeRecords(int, Consumer)} methods block while waiting for each record before returning <code>null</code>.
     * 
     * @param timeout the timeout; must be positive
     * @param unit the time unit; may not be null
     */
    protected void setConsumeTimeout(long timeout, TimeUnit unit) {
        if (timeout < 0) throw new IllegalArgumentException("The timeout may not be negative");
        pollTimeoutInMs = unit.toMillis(timeout);
    }

    /**
     * Consume a single record from the connector.
     * 
     * @return the next record that was returned from the connector, or null if no such record has been produced by the connector
     * @throws InterruptedException if the thread was interrupted while waiting for a record to be returned
     */
    protected SourceRecord consumeRecord() throws InterruptedException {
        return consumedLines.poll(pollTimeoutInMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Try to consume the specified number of records from the connector, and return the actual number of records that were
     * consumed. Use this method when your test does not care what the records might contain.
     * 
     * @param numberOfRecords the number of records that should be consumed
     * @return the actual number of records that were consumed
     * @throws InterruptedException if the thread was interrupted while waiting for a record to be returned
     */
    protected int consumeRecords(int numberOfRecords) throws InterruptedException {
        return consumeRecords(numberOfRecords, null);
    }

    /**
     * Try to consume the specified number of records from the connector, calling the given function for each, and return the
     * actual number of records that were consumed.
     * 
     * @param numberOfRecords the number of records that should be consumed
     * @param recordConsumer the function that should be called with each consumed record
     * @return the actual number of records that were consumed
     * @throws InterruptedException if the thread was interrupted while waiting for a record to be returned
     */
    protected int consumeRecords(int numberOfRecords, Consumer<SourceRecord> recordConsumer) throws InterruptedException {
        int recordsConsumed = 0;
        while (recordsConsumed < numberOfRecords) {
            SourceRecord record = consumedLines.poll(pollTimeoutInMs, TimeUnit.MILLISECONDS);
            if (record != null) {
                ++recordsConsumed;
                if (recordConsumer != null) {
                    recordConsumer.accept(record);
                }
                if (Testing.Debug.isEnabled()) {
                    Testing.debug("Consumed record " + recordsConsumed + " / " + numberOfRecords + " ("
                            + (numberOfRecords - recordsConsumed) + " more)");
                    debug(record);
                } else if (Testing.Print.isEnabled()) {
                    Testing.print("Consumed record " + recordsConsumed + " / " + numberOfRecords + " ("
                            + (numberOfRecords - recordsConsumed) + " more)");
                    print(record);
                }
            }
        }
        return recordsConsumed;
    }

    protected SourceRecords consumeRecordsByTopic(int numRecords) throws InterruptedException {
        return consumeRecordsByTopic(numRecords, new SourceRecords());
    }

    protected SourceRecords consumeRecordsByTopic(int numRecords, SourceRecords records) throws InterruptedException {
        consumeRecords(numRecords, records::add);
        return records;
    }
    

    protected class SourceRecords {
        private final List<SourceRecord> records = new ArrayList<>();
        private final Map<String, List<SourceRecord>> recordsByTopic = new HashMap<>();
        private final Map<String, List<SourceRecord>> ddlRecordsByDbName = new HashMap<>();

        public void add(SourceRecord record) {
            records.add(record);
            recordsByTopic.computeIfAbsent(record.topic(), (topicName) -> new ArrayList<SourceRecord>()).add(record);
            String dbName = getAffectedDatabase(record);
            if (dbName != null) ddlRecordsByDbName.computeIfAbsent(dbName, key -> new ArrayList<>()).add(record);
        }

        protected String getAffectedDatabase(SourceRecord record) {
            Struct value = (Struct) record.value();
            if (value != null) {
                Field dbField = value.schema().field(HistoryRecord.Fields.DATABASE_NAME);
                if (dbField != null) {
                    return value.getString(dbField.name());
                }
            }
            return null;
        }

        public List<SourceRecord> ddlRecordsForDatabase(String dbName) {
            return ddlRecordsByDbName.get(dbName);
        }

        public Set<String> databaseNames() {
            return ddlRecordsByDbName.keySet();
        }

        public List<SourceRecord> recordsForTopic(String topicName) {
            return recordsByTopic.get(topicName);
        }

        public Set<String> topics() {
            return recordsByTopic.keySet();
        }

        public void forEachInTopic(String topic, Consumer<SourceRecord> consumer) {
            recordsForTopic(topic).forEach(consumer);
        }

        public void forEach(Consumer<SourceRecord> consumer) {
            records.forEach(consumer);
        }
        
        public List<SourceRecord> allRecordsInOrder() {
            return Collections.unmodifiableList(records);
        }

        public void print() {
            Testing.print("" + topics().size() + " topics: " + topics());
            recordsByTopic.forEach((k, v) -> {
                Testing.print(" - topic:'" + k + "'; # of events = " + v.size());
            });
            Testing.print("Records:");
            records.forEach(record -> AbstractConnectorTest.this.print(record));
        }
    }

    /**
     * Try to consume all of the messages that have already been returned by the connector.
     * 
     * @param recordConsumer the function that should be called with each consumed record
     * @return the number of records that were consumed
     */
    protected int consumeAvailableRecords(Consumer<SourceRecord> recordConsumer) {
        List<SourceRecord> records = new LinkedList<>();
        consumedLines.drainTo(records);
        if (recordConsumer != null) {
            records.forEach(recordConsumer);
        }
        return records.size();
    }

    /**
     * Wait for a maximum amount of time until the first record is available.
     * 
     * @param timeout the maximum amount of time to wait; must not be negative
     * @param unit the time unit for {@code timeout}
     * @return {@code true} if records are available, or {@code false} if the timeout occurred and no records are available
     */
    protected boolean waitForAvailableRecords(long timeout, TimeUnit unit) {
        assertThat(timeout).isGreaterThanOrEqualTo(0);
        long now = System.currentTimeMillis();
        long stop = now + unit.toMillis(timeout);
        while (System.currentTimeMillis() < stop) {
            if (!consumedLines.isEmpty()) break;
        }
        return consumedLines.isEmpty() ? false : true;
    }

    /**
     * Assert that the connector is currently running.
     */
    protected void assertConnectorIsRunning() {
        assertThat(engine.isRunning()).isTrue();
    }

    /**
     * Assert that the connector is NOT currently running.
     */
    protected void assertConnectorNotRunning() {
        assertThat(engine.isRunning()).isFalse();
    }

    /**
     * Assert that there are no records to consume.
     */
    protected void assertNoRecordsToConsume() {
        assertThat(consumedLines.isEmpty()).isTrue();
    }

    protected void assertKey(SourceRecord record, String pkField, int pk) {
        VerifyRecord.hasValidKey(record, pkField, pk);
    }

    protected void assertInsert(SourceRecord record, String pkField, int pk) {
        VerifyRecord.isValidInsert(record, pkField, pk);
    }

    protected void assertUpdate(SourceRecord record, String pkField, int pk) {
        VerifyRecord.isValidUpdate(record, pkField, pk);
    }

    protected void assertDelete(SourceRecord record, String pkField, int pk) {
        VerifyRecord.isValidDelete(record, pkField, pk);
    }

    protected void assertTombstone(SourceRecord record, String pkField, int pk) {
        VerifyRecord.isValidTombstone(record, pkField, pk);
    }

    protected void assertTombstone(SourceRecord record) {
        VerifyRecord.isValidTombstone(record);
    }

    /**
     * Assert that the supplied {@link Struct} is {@link Struct#validate() valid} and its {@link Struct#schema() schema}
     * matches that of the supplied {@code schema}.
     * 
     * @param value the value with a schema; may not be null
     */
    protected void assertSchemaMatchesStruct(SchemaAndValue value) {
        VerifyRecord.schemaMatchesStruct(value);
    }

    /**
     * Assert that the supplied {@link Struct} is {@link Struct#validate() valid} and its {@link Struct#schema() schema}
     * matches that of the supplied {@code schema}.
     * 
     * @param struct the {@link Struct} to validate; may not be null
     * @param schema the expected schema of the {@link Struct}; may not be null
     */
    protected void assertSchemaMatchesStruct(Struct struct, Schema schema) {
        VerifyRecord.schemaMatchesStruct(struct, schema);
    }

    /**
     * Validate that a {@link SourceRecord}'s key and value can each be converted to a byte[] and then back to an equivalent
     * {@link SourceRecord}.
     * 
     * @param record the record to validate; may not be null
     */
    protected void validate(SourceRecord record) {
        VerifyRecord.isValid(record);
    }

    protected void print(SourceRecord record) {
        VerifyRecord.print(record);
    }

    protected void debug(SourceRecord record) {
        VerifyRecord.debug(record);
    }
}
