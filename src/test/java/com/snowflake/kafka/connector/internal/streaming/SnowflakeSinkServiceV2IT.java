package com.snowflake.kafka.connector.internal.streaming;

import static com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig.SNOWPIPE_STREAMING_MAX_CLIENT_LAG;
import static com.snowflake.kafka.connector.internal.streaming.SnowflakeSinkServiceV2.partitionChannelKey;
import static com.snowflake.kafka.connector.internal.streaming.TopicPartitionChannel.NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE;

import com.codahale.metrics.Gauge;
import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.Utils;
import com.snowflake.kafka.connector.dlq.InMemoryKafkaRecordErrorReporter;
import com.snowflake.kafka.connector.internal.SchematizationTestUtils;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionService;
import com.snowflake.kafka.connector.internal.SnowflakeConnectionServiceFactory;
import com.snowflake.kafka.connector.internal.SnowflakeErrors;
import com.snowflake.kafka.connector.internal.SnowflakeSinkService;
import com.snowflake.kafka.connector.internal.SnowflakeSinkServiceFactory;
import com.snowflake.kafka.connector.internal.TestUtils;
import com.snowflake.kafka.connector.internal.metrics.MetricsUtil;
import com.snowflake.kafka.connector.internal.streaming.telemetry.SnowflakeTelemetryChannelCreation;
import com.snowflake.kafka.connector.internal.streaming.telemetry.SnowflakeTelemetryChannelStatus;
import com.snowflake.kafka.connector.internal.streaming.telemetry.SnowflakeTelemetryServiceV2;
import com.snowflake.kafka.connector.records.SnowflakeConverter;
import com.snowflake.kafka.connector.records.SnowflakeJsonConverter;
import io.confluent.connect.avro.AvroConverter;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;

@RunWith(Parameterized.class)
public class SnowflakeSinkServiceV2IT {

  private SnowflakeConnectionService conn;
  private String table = TestUtils.randomTableName();
  private int partition = 0;
  private int partition2 = 1;

  // Topic name should be same as table name. (Only for testing, not necessarily in real deployment)
  private String topic = table;
  private TopicPartition topicPartition = new TopicPartition(topic, partition);

  // use OAuth as authenticator or not
  private boolean useOAuth;

  @Parameterized.Parameters(name = "useOAuth: {0}")
  public static Collection<Object[]> input() {
    // TODO: Added {true} after SNOW-352846 is released
    return Arrays.asList(new Object[][] {{false}});
  }

  public SnowflakeSinkServiceV2IT(boolean useOAuth) {
    this.useOAuth = useOAuth;
    if (!useOAuth) {
      conn = TestUtils.getConnectionServiceForStreaming();
    } else {
      conn = TestUtils.getOAuthConnectionServiceForStreaming();
    }
  }

  @After
  public void afterEach() {
    TestUtils.dropTable(table);
  }

  @Test
  public void testSinkServiceV2Builder() {
    Map<String, String> config = getConfig();

    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .build();

    assert service instanceof SnowflakeSinkServiceV2;

    // connection test
    Map<String, String> finalConfig = config;
    assert TestUtils.assertError(
        SnowflakeErrors.ERROR_5010,
        () ->
            SnowflakeSinkServiceFactory.builder(
                    null, IngestionMethodConfig.SNOWPIPE_STREAMING, finalConfig)
                .build());
    assert TestUtils.assertError(
        SnowflakeErrors.ERROR_5010,
        () -> {
          SnowflakeConnectionService conn = TestUtils.getConnectionService();
          if (useOAuth) {
            conn = TestUtils.getOAuthConnectionService();
          }
          conn.close();
          SnowflakeSinkServiceFactory.builder(
                  conn, IngestionMethodConfig.SNOWPIPE_STREAMING, finalConfig)
              .build();
        });
  }

  @Test
  public void testChannelCloseIngestion() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
    long offset = 0;

    SinkRecord record1 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);

    // Lets close the service
    // Closing a partition == closing a channel
    service.close(Collections.singletonList(new TopicPartition(topic, partition)));

    // Lets insert a record when partition was closed.
    // It should auto create the channel
    service.insert(record1);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 1, 20, 5);

    service.closeAll();
  }

  // Two partitions, insert Record, one partition gets rebalanced (closed).
  // just before rebalance, there is data in buffer for other partition,
  // Send data again for both partitions.
  // Successfully able to ingest all records
  @Test
  public void testStreamingIngest_multipleChannelPartitions_closeSubsetOfPartitionsAssigned()
      throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);
    TopicPartition tp1 = new TopicPartition(table, partition);
    TopicPartition tp2 = new TopicPartition(table, partition2);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(5)
            .setFlushTime(5)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, tp1) // Internally calls startTask
            .addTask(table, tp2) // Internally calls startTask
            .build();

    final int recordsInPartition1 = 2;
    final int recordsInPartition2 = 2;
    List<SinkRecord> recordsPartition1 =
        TestUtils.createJsonStringSinkRecords(0, recordsInPartition1, table, partition);

    List<SinkRecord> recordsPartition2 =
        TestUtils.createJsonStringSinkRecords(0, recordsInPartition2, table, partition2);

    List<SinkRecord> records = new ArrayList<>(recordsPartition1);
    records.addAll(recordsPartition2);

    service.insert(records);

    TestUtils.assertWithRetry(
        () -> {
          // This is how we will trigger flush. (Mimicking poll API)
          service.insert(new ArrayList<>()); // trigger time based flush
          return TestUtils.tableSize(table) == recordsInPartition1 + recordsInPartition2;
        },
        10,
        20);

    TestUtils.assertWithRetry(() -> service.getOffset(tp1) == recordsInPartition1, 20, 5);
    TestUtils.assertWithRetry(() -> service.getOffset(tp2) == recordsInPartition2, 20, 5);
    // before you close partition 1, there should be some data in partition 2
    List<SinkRecord> newRecordsPartition2 =
        TestUtils.createJsonStringSinkRecords(2, recordsInPartition1, table, partition2);
    service.insert(newRecordsPartition2);
    // partitions to close = 1 out of 2
    List<TopicPartition> partitionsToClose = Collections.singletonList(tp1);
    service.close(partitionsToClose);

    // remaining partition should be present in the map
    SnowflakeSinkServiceV2 snowflakeSinkServiceV2 = (SnowflakeSinkServiceV2) service;

    Assert.assertTrue(
        snowflakeSinkServiceV2
            .getTopicPartitionChannelFromCacheKey(partitionChannelKey(tp2.topic(), tp2.partition()))
            .isPresent());

    List<SinkRecord> newRecordsPartition1 =
        TestUtils.createJsonStringSinkRecords(2, recordsInPartition1, table, partition);

    List<SinkRecord> newRecords2Partition2 =
        TestUtils.createJsonStringSinkRecords(4, recordsInPartition1, table, partition2);
    List<SinkRecord> newrecords = new ArrayList<>(newRecordsPartition1);
    newrecords.addAll(newRecords2Partition2);

    service.insert(newrecords);
    TestUtils.assertWithRetry(
        () -> {
          // This is how we will trigger flush. (Mimicking poll API)
          service.insert(new ArrayList<>()); // trigger time based flush
          return TestUtils.tableSize(table) == recordsInPartition1 * 5;
        },
        10,
        20);
  }

  @Test
  public void testRebalanceOpenCloseIngestion() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
    long offset = 0;

    SinkRecord record1 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);

    service.insert(record1);

    // Lets close the service
    // Closing a partition == closing a channel
    service.close(Collections.singletonList(new TopicPartition(topic, partition)));

    // it should skip this record1 since it will fetch offset token 0 from Snowflake
    service.insert(record1);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 1, 20, 5);

    service.closeAll();
  }

  @Test
  public void testStreamingIngestion() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
    long offset = 0;

    SinkRecord record1 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);

    service.insert(record1);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 1, 20, 5);

    // insert another offset and check what we committed
    offset += 1;
    SinkRecord record2 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);
    offset += 1;
    SinkRecord record3 =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test_key" + offset,
            input.schema(),
            input.value(),
            offset);

    service.insert(Arrays.asList(record2, record3));
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 3, 20, 5);

    service.closeAll();
  }

  @Test
  public void testStreamingIngest_multipleChannelPartitions_withMetrics() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    // set up telemetry service spy
    SnowflakeConnectionService connectionService = Mockito.spy(this.conn);
    connectionService.createTable(table);
    SnowflakeTelemetryServiceV2 telemetryService =
        Mockito.spy((SnowflakeTelemetryServiceV2) this.conn.getTelemetryClient());
    Mockito.when(connectionService.getTelemetryClient()).thenReturn(telemetryService);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(
                connectionService, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(5)
            .setFlushTime(5)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
            .addTask(table, new TopicPartition(topic, partition2)) // Internally calls startTask
            .setCustomJMXMetrics(true)
            .build();

    final int recordsInPartition1 = 2;
    final int recordsInPartition2 = 5;
    List<SinkRecord> recordsPartition1 =
        TestUtils.createJsonStringSinkRecords(0, recordsInPartition1, topic, partition);

    List<SinkRecord> recordsPartition2 =
        TestUtils.createJsonStringSinkRecords(0, recordsInPartition2, topic, partition2);

    List<SinkRecord> records = new ArrayList<>(recordsPartition1);
    records.addAll(recordsPartition2);

    service.insert(records);

    TestUtils.assertWithRetry(
        () -> {
          // This is how we will trigger flush. (Mimicking poll API)
          service.insert(new ArrayList<>()); // trigger time based flush
          return TestUtils.tableSize(table) == recordsInPartition1 + recordsInPartition2;
        },
        10,
        20);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == recordsInPartition1,
        20,
        5);
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition2)) == recordsInPartition2,
        20,
        5);

    // verify all metrics
    Map<String, Gauge> metricRegistry =
        service
            .getMetricRegistry(SnowflakeSinkServiceV2.partitionChannelKey(topic, partition))
            .get()
            .getGauges();
    assert metricRegistry.size()
        == SnowflakeTelemetryChannelStatus.NUM_METRICS * 2; // two partitions

    // partition 1
    this.verifyPartitionMetrics(
        metricRegistry,
        partitionChannelKey(topic, partition),
        NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE,
        recordsInPartition1 - 1,
        recordsInPartition1,
        1,
        this.conn.getConnectorName());
    this.verifyPartitionMetrics(
        metricRegistry,
        partitionChannelKey(topic, partition2),
        NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE,
        recordsInPartition2 - 1,
        recordsInPartition2,
        1,
        this.conn.getConnectorName());

    // verify telemetry
    Mockito.verify(telemetryService, Mockito.times(2))
        .reportKafkaPartitionStart(Mockito.any(SnowflakeTelemetryChannelCreation.class));

    service.closeAll();

    // verify metrics closed
    assert !service
        .getMetricRegistry(SnowflakeSinkServiceV2.partitionChannelKey(topic, partition))
        .isPresent();

    Mockito.verify(telemetryService, Mockito.times(2))
        .reportKafkaPartitionUsage(
            Mockito.any(SnowflakeTelemetryChannelStatus.class), Mockito.eq(true));
  }

  private void verifyPartitionMetrics(
      Map<String, Gauge> metricRegistry,
      String partitionChannelKey,
      long offsetPersistedInSnowflake,
      long processedOffset,
      long latestConsumerOffset,
      long currentTpChannelOpenCount,
      String connectorName) {
    // offsets
    assert (long)
            metricRegistry
                .get(
                    MetricsUtil.constructMetricName(
                        partitionChannelKey,
                        MetricsUtil.OFFSET_SUB_DOMAIN,
                        MetricsUtil.OFFSET_PERSISTED_IN_SNOWFLAKE))
                .getValue()
        == offsetPersistedInSnowflake;
    assert (long)
            metricRegistry
                .get(
                    MetricsUtil.constructMetricName(
                        partitionChannelKey,
                        MetricsUtil.OFFSET_SUB_DOMAIN,
                        MetricsUtil.PROCESSED_OFFSET))
                .getValue()
        == processedOffset;
    assert (long)
            metricRegistry
                .get(
                    MetricsUtil.constructMetricName(
                        partitionChannelKey,
                        MetricsUtil.OFFSET_SUB_DOMAIN,
                        MetricsUtil.LATEST_CONSUMER_OFFSET))
                .getValue()
        == latestConsumerOffset;
  }

  @Test
  public void testStreamingIngest_multipleChannelPartitionsWithTopic2Table() throws Exception {
    final int partitionCount = 3;
    final int recordsInEachPartition = 2;
    final int topicCount = 3;

    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    ArrayList<String> topics = new ArrayList<>();
    for (int topic = 0; topic < topicCount; topic++) {
      topics.add(TestUtils.randomTableName());
    }

    // only insert fist topic to topicTable
    Map<String, String> topic2Table = new HashMap<>();
    topic2Table.put(topics.get(0), table);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(5)
            .setFlushTime(5)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setTopic2TableMap(topic2Table)
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .build();

    for (int topic = 0; topic < topicCount; topic++) {
      for (int partition = 0; partition < partitionCount; partition++) {
        service.startPartition(topics.get(topic), new TopicPartition(topics.get(topic), partition));
      }

      List<SinkRecord> records = new ArrayList<>();
      for (int partition = 0; partition < partitionCount; partition++) {
        records.addAll(
            TestUtils.createJsonStringSinkRecords(
                0, recordsInEachPartition, topics.get(topic), partition));
      }

      service.insert(records);
    }

    for (int topic = 0; topic < topicCount; topic++) {
      int finalTopic = topic;
      TestUtils.assertWithRetry(
          () -> {
            service.insert(new ArrayList<>()); // trigger time based flush
            return TestUtils.tableSize(topics.get(finalTopic))
                == recordsInEachPartition * partitionCount;
          },
          10,
          20);

      for (int partition = 0; partition < partitionCount; partition++) {
        int finalPartition = partition;
        TestUtils.assertWithRetry(
            () ->
                service.getOffset(new TopicPartition(topics.get(finalTopic), finalPartition))
                    == recordsInEachPartition,
            20,
            5);
      }
    }

    service.closeAll();
  }

  @Test
  public void testStreamingIngest_startPartitionsWithMultipleChannelPartitions() throws Exception {
    final int partitionCount = 5;
    final int recordsInEachPartition = 2;

    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(5)
            .setFlushTime(5)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .build();

    ArrayList<TopicPartition> topicPartitions = new ArrayList<>();
    for (int partition = 0; partition < partitionCount; partition++) {
      topicPartitions.add(new TopicPartition(topic, partition));
    }
    Map<String, String> topic2Table = new HashMap<>();
    topic2Table.put(topic, table);
    service.startPartitions(topicPartitions, topic2Table);

    List<SinkRecord> records = new ArrayList<>();
    for (int partition = 0; partition < partitionCount; partition++) {
      records.addAll(
          TestUtils.createJsonStringSinkRecords(0, recordsInEachPartition, topic, partition));
    }

    service.insert(records);

    TestUtils.assertWithRetry(
        () -> {
          service.insert(new ArrayList<>()); // trigger time based flush
          return TestUtils.tableSize(table) == recordsInEachPartition * partitionCount;
        },
        10,
        20);

    for (int partition = 0; partition < partitionCount; partition++) {
      int finalPartition = partition;
      TestUtils.assertWithRetry(
          () ->
              service.getOffset(new TopicPartition(topic, finalPartition))
                  == recordsInEachPartition,
          20,
          5);
    }

    service.closeAll();
  }

  @Test
  public void testStreamingIngestion_timeBased() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(100)
            .setFlushTime(11) // 11 seconds
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
            .build();

    final long noOfRecords = 123;
    List<SinkRecord> sinkRecords =
        TestUtils.createJsonStringSinkRecords(0, noOfRecords, topic, partition);

    service.insert(sinkRecords);

    TestUtils.assertWithRetry(
        () -> {
          // This is how we will trigger flush. (Mimicking poll API)
          service.insert(new ArrayList<>()); // trigger time based flush
          return TestUtils.tableSize(table) == noOfRecords;
        },
        10,
        20);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == noOfRecords, 20, 5);

    service.closeAll();
  }

  @Test
  public void testNativeJsonInputIngestion() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // json without schema
    JsonConverter converter = new JsonConverter();
    HashMap<String, String> converterConfig = new HashMap<String, String>();
    converterConfig.put("schemas.enable", "false");
    converter.configure(converterConfig, false);
    SchemaAndValue noSchemaInputValue =
        converter.toConnectData(
            topic, TestUtils.JSON_WITHOUT_SCHEMA.getBytes(StandardCharsets.UTF_8));

    converter = new JsonConverter();
    converterConfig = new HashMap<>();
    converterConfig.put("schemas.enable", "false");
    converter.configure(converterConfig, true);
    SchemaAndValue noSchemaInputKey =
        converter.toConnectData(
            topic, TestUtils.JSON_WITHOUT_SCHEMA.getBytes(StandardCharsets.UTF_8));

    // json with schema
    converter = new JsonConverter();
    converterConfig = new HashMap<>();
    converterConfig.put("schemas.enable", "true");
    converter.configure(converterConfig, false);
    SchemaAndValue schemaInputValue =
        converter.toConnectData(topic, TestUtils.JSON_WITH_SCHEMA.getBytes(StandardCharsets.UTF_8));

    converter = new JsonConverter();
    converterConfig = new HashMap<>();
    converterConfig.put("schemas.enable", "true");
    converter.configure(converterConfig, true);
    SchemaAndValue schemaInputKey =
        converter.toConnectData(topic, TestUtils.JSON_WITH_SCHEMA.getBytes(StandardCharsets.UTF_8));

    long startOffset = 0;
    long endOffset = 3;

    SinkRecord noSchemaRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            noSchemaInputValue.schema(),
            noSchemaInputValue.value(),
            startOffset);
    SinkRecord schemaRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            schemaInputValue.schema(),
            schemaInputValue.value(),
            startOffset + 1);

    SinkRecord noSchemaRecordKey =
        new SinkRecord(
            topic,
            partition,
            noSchemaInputKey.schema(),
            noSchemaInputKey.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 2);
    SinkRecord schemaRecordKey =
        new SinkRecord(
            topic,
            partition,
            schemaInputKey.schema(),
            schemaInputKey.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 3);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
            .build();

    service.insert(noSchemaRecordValue);
    service.insert(schemaRecordValue);

    service.insert(noSchemaRecordKey);
    service.insert(schemaRecordKey);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == endOffset + 1, 20, 5);

    service.closeAll();
  }

  @Test
  public void testNativeAvroInputIngestion() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    // avro
    SchemaBuilder schemaBuilder =
        SchemaBuilder.struct()
            .field("int8", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field("int16", Schema.INT16_SCHEMA)
            .field("int32", Schema.INT32_SCHEMA)
            .field("int64", Schema.INT64_SCHEMA)
            .field("float32", Schema.FLOAT32_SCHEMA)
            .field("float64", Schema.FLOAT64_SCHEMA)
            .field("int8Min", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field("int16Min", Schema.INT16_SCHEMA)
            .field("int32Min", Schema.INT32_SCHEMA)
            .field("int64Min", Schema.INT64_SCHEMA)
            .field("float32Min", Schema.FLOAT32_SCHEMA)
            .field("float64Min", Schema.FLOAT64_SCHEMA)
            .field("int8Max", SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field("int16Max", Schema.INT16_SCHEMA)
            .field("int32Max", Schema.INT32_SCHEMA)
            .field("int64Max", Schema.INT64_SCHEMA)
            .field("float32Max", Schema.FLOAT32_SCHEMA)
            .field("float64Max", Schema.FLOAT64_SCHEMA)
            .field("float64HighPrecision", Schema.FLOAT64_SCHEMA)
            .field("float64TenDigits", Schema.FLOAT64_SCHEMA)
            .field("float64BigDigits", Schema.FLOAT64_SCHEMA)
            .field("boolean", Schema.BOOLEAN_SCHEMA)
            .field("string", Schema.STRING_SCHEMA)
            .field("bytes", Schema.BYTES_SCHEMA)
            .field("bytesReadOnly", Schema.BYTES_SCHEMA)
            .field("int16Optional", Schema.OPTIONAL_INT16_SCHEMA)
            .field("int32Optional", Schema.OPTIONAL_INT32_SCHEMA)
            .field("int64Optional", Schema.OPTIONAL_INT64_SCHEMA)
            .field("float32Optional", Schema.OPTIONAL_FLOAT32_SCHEMA)
            .field("float64Optional", Schema.OPTIONAL_FLOAT64_SCHEMA)
            .field("booleanOptional", Schema.OPTIONAL_BOOLEAN_SCHEMA)
            .field("stringOptional", Schema.OPTIONAL_STRING_SCHEMA)
            .field("bytesOptional", Schema.OPTIONAL_BYTES_SCHEMA)
            .field("array", SchemaBuilder.array(Schema.STRING_SCHEMA).build())
            .field("map", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build())
            .field(
                "int8Optional",
                SchemaBuilder.int8().defaultValue((byte) 2).doc("int8 field").build())
            .field(
                "mapNonStringKeys",
                SchemaBuilder.map(Schema.INT32_SCHEMA, Schema.INT32_SCHEMA).build())
            .field(
                "mapArrayMapInt",
                SchemaBuilder.map(
                        Schema.STRING_SCHEMA,
                        SchemaBuilder.array(
                                SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA)
                                    .build())
                            .build())
                    .build());
    Struct original =
        new Struct(schemaBuilder.build())
            .put("int8", (byte) 12)
            .put("int16", (short) 12)
            .put("int32", 12)
            .put("int64", 12L)
            .put("float32", 12.2f)
            .put("float64", 12.2)
            .put("int8Min", Byte.MIN_VALUE)
            .put("int16Min", Short.MIN_VALUE)
            .put("int32Min", Integer.MIN_VALUE)
            .put("int64Min", Long.MIN_VALUE)
            .put("float32Min", Float.MIN_VALUE)
            .put("float64Min", Double.MIN_VALUE)
            .put("int8Max", Byte.MAX_VALUE)
            .put("int16Max", Short.MAX_VALUE)
            .put("int32Max", Integer.MAX_VALUE)
            .put("int64Max", Long.MAX_VALUE)
            .put("float32Max", Float.MAX_VALUE)
            .put("float64Max", Double.MAX_VALUE)
            .put("float64HighPrecision", 2312.4200000000001d)
            .put("float64TenDigits", 1.0d / 3.0d)
            .put("float64BigDigits", 2312.42321432655123456d)
            .put("boolean", true)
            .put("string", "foo")
            .put("bytes", ByteBuffer.wrap("foo".getBytes()))
            .put("bytesReadOnly", ByteBuffer.wrap("foo".getBytes()).asReadOnlyBuffer())
            .put("array", Arrays.asList("a", "b", "c"))
            .put("map", Collections.singletonMap("field", 1))
            .put("mapNonStringKeys", Collections.singletonMap(1, 1))
            .put(
                "mapArrayMapInt",
                Collections.singletonMap(
                    "field",
                    Arrays.asList(
                        Collections.singletonMap("field", 1),
                        Collections.singletonMap("field", 1))));

    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData(topic, original.schema(), original);
    SchemaAndValue avroInputValue = avroConverter.toConnectData(topic, converted);

    avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), true);
    converted = avroConverter.fromConnectData(topic, original.schema(), original);
    SchemaAndValue avroInputKey = avroConverter.toConnectData(topic, converted);

    long startOffset = 0;
    long endOffset = 2;

    SinkRecord avroRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            avroInputValue.schema(),
            avroInputValue.value(),
            startOffset);

    SinkRecord avroRecordKey =
        new SinkRecord(
            topic,
            partition,
            avroInputKey.schema(),
            avroInputKey.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 1);

    SinkRecord avroRecordKeyValue =
        new SinkRecord(
            topic,
            partition,
            avroInputKey.schema(),
            avroInputKey.value(),
            avroInputKey.schema(),
            avroInputKey.value(),
            startOffset + 2);

    conn.createTable(table);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    service.insert(avroRecordValue);
    service.insert(avroRecordKey);
    service.insert(avroRecordKeyValue);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == endOffset + 1, 20, 5);

    service.closeAll();
  }

  @Test
  public void testBrokenIngestion() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // Mismatched schema and value
    SchemaAndValue brokenInputValue = new SchemaAndValue(Schema.INT32_SCHEMA, "error");

    long startOffset = 0;

    SinkRecord brokenValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            brokenInputValue.schema(),
            brokenInputValue.value(),
            startOffset);

    SinkRecord brokenKey =
        new SinkRecord(
            topic,
            partition,
            brokenInputValue.schema(),
            brokenInputValue.value(),
            Schema.STRING_SCHEMA,
            "test",
            startOffset + 1);

    SinkRecord brokenKeyValue =
        new SinkRecord(
            topic,
            partition,
            brokenInputValue.schema(),
            brokenInputValue.value(),
            brokenInputValue.schema(),
            brokenInputValue.value(),
            startOffset + 2);
    InMemoryKafkaRecordErrorReporter errorReporter = new InMemoryKafkaRecordErrorReporter();

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(errorReporter)
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    service.insert(brokenValue);
    service.insert(brokenKey);
    service.insert(brokenKeyValue);

    TestUtils.assertWithRetry(
        () ->
            service.getOffset(new TopicPartition(topic, partition))
                == NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE,
        20,
        5);

    List<InMemoryKafkaRecordErrorReporter.ReportedRecord> reportedData =
        errorReporter.getReportedRecords();

    assert reportedData.size() == 3;
    assert TestUtils.tableSize(table) == 0
        : "expected: " + 0 + " actual: " + TestUtils.tableSize(table);
  }

  @Test
  public void testBrokenRecordIngestionFollowedUpByValidRecord() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // Mismatched schema and value
    SchemaAndValue brokenInputValue = new SchemaAndValue(Schema.INT32_SCHEMA, "error");
    SchemaAndValue correctInputValue = new SchemaAndValue(Schema.STRING_SCHEMA, "correct");

    long recordCount = 1;

    SinkRecord brokenValue =
        new SinkRecord(
            topic, partition, null, null, brokenInputValue.schema(), brokenInputValue.value(), 0);

    SinkRecord brokenKey =
        new SinkRecord(
            topic, partition, brokenInputValue.schema(), brokenInputValue.value(), null, null, 1);

    SinkRecord correctValue =
        new SinkRecord(
            topic, partition, null, null, correctInputValue.schema(), correctInputValue.value(), 2);

    InMemoryKafkaRecordErrorReporter errorReporter = new InMemoryKafkaRecordErrorReporter();

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setErrorReporter(errorReporter)
            .setRecordNumber(recordCount)
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    service.insert(brokenValue);
    service.insert(brokenKey);
    service.insert(correctValue);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 3, 20, 5);

    List<InMemoryKafkaRecordErrorReporter.ReportedRecord> reportedData =
        errorReporter.getReportedRecords();

    assert reportedData.size() == 2;
    assert TestUtils.tableSize(table) == 1
        : "expected: " + 1 + " actual: " + TestUtils.tableSize(table);

    service.closeAll();
  }

  /**
   * A bit different from above test where we first insert a valid json record, followed by two
   * broken records (Non valid JSON) followed by another good record with max buffer record size
   * being 2
   */
  @Test
  public void testBrokenRecordIngestionAfterValidRecord() throws Exception {
    Map<String, String> config = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // Mismatched schema and value
    SchemaAndValue brokenInputValue = new SchemaAndValue(Schema.INT32_SCHEMA, "error");
    SchemaAndValue correctInputValue = new SchemaAndValue(Schema.STRING_SCHEMA, "correct");

    long recordCount = 2;

    SinkRecord correctValue =
        new SinkRecord(
            topic, partition, null, null, correctInputValue.schema(), correctInputValue.value(), 0);

    SinkRecord brokenValue =
        new SinkRecord(
            topic, partition, null, null, brokenInputValue.schema(), brokenInputValue.value(), 1);

    SinkRecord brokenKey =
        new SinkRecord(
            topic, partition, brokenInputValue.schema(), brokenInputValue.value(), null, null, 2);

    SinkRecord anotherCorrectValue =
        new SinkRecord(
            topic, partition, null, null, correctInputValue.schema(), correctInputValue.value(), 3);

    InMemoryKafkaRecordErrorReporter errorReporter = new InMemoryKafkaRecordErrorReporter();

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setErrorReporter(errorReporter)
            .setRecordNumber(recordCount)
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    service.insert(correctValue);
    service.insert(brokenValue);
    service.insert(brokenKey);
    service.insert(anotherCorrectValue);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 4, 20, 5);

    List<InMemoryKafkaRecordErrorReporter.ReportedRecord> reportedData =
        errorReporter.getReportedRecords();

    assert reportedData.size() == 2;

    service.closeAll();
  }

  /* Service start -> Insert -> Close. service start -> fetch the offsetToken, compare and ingest check data */

  @Test
  public void testStreamingIngestionWithExactlyOnceSemanticsNoOverlappingOffsets()
      throws Exception {
    conn.createTable(table);
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));

    long offset = 0;
    // Create sink record
    SinkRecord record1 =
        new SinkRecord(
            topic, partition, Schema.STRING_SCHEMA, "test", input.schema(), input.value(), offset);

    service.insert(record1);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == 1, 20, 5);
    // wait for ingest
    TestUtils.assertWithRetry(() -> TestUtils.tableSize(table) == 1, 30, 20);

    service.closeAll();

    // initialize a new sink service
    SnowflakeSinkService service2 =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();
    offset = 1;
    // Create sink record
    SinkRecord record2 =
        new SinkRecord(
            topic, partition, Schema.STRING_SCHEMA, "test", input.schema(), input.value(), offset);

    service2.insert(record2);

    // wait for ingest
    TestUtils.assertWithRetry(() -> TestUtils.tableSize(table) == 2, 30, 20);

    assert service2.getOffset(new TopicPartition(topic, partition)) == offset + 1;

    service2.closeAll();
  }

  /* Service start -> Insert -> Close. service start -> fetch the offsetToken, compare and ingest check data */

  @Test
  public void testStreamingIngestionWithExactlyOnceSemanticsOverlappingOffsets() throws Exception {
    conn.createTable(table);
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    SnowflakeConverter converter = new SnowflakeJsonConverter();
    SchemaAndValue input =
        converter.toConnectData(topic, "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));

    long offset = 0;
    final long noOfRecords = 10;
    // send regular data
    List<SinkRecord> records =
        TestUtils.createJsonStringSinkRecords(0, noOfRecords, topic, partition);

    service.insert(records);

    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == noOfRecords, 20, 5);

    // wait for ingest
    TestUtils.assertWithRetry(() -> TestUtils.tableSize(table) == 10, 30, 20);

    service.closeAll();

    // initialize a new sink service
    SnowflakeSinkService service2 =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    final long startOffsetAlreadyInserted = 5;
    records =
        TestUtils.createJsonStringSinkRecords(
            startOffsetAlreadyInserted, noOfRecords, topic, partition);

    service2.insert(records);

    final long totalRecordsExpected = noOfRecords + (noOfRecords - startOffsetAlreadyInserted);

    // wait for ingest
    TestUtils.assertWithRetry(() -> TestUtils.tableSize(table) == totalRecordsExpected, 30, 20);

    assert service2.getOffset(new TopicPartition(topic, partition)) == totalRecordsExpected;

    service2.closeAll();
  }

  @Test
  public void testSchematizationWithTableCreationAndAvroInput() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    config.put(SnowflakeSinkConnectorConfig.ENABLE_SCHEMATIZATION_CONFIG, "true");
    config.put(
        SnowflakeSinkConnectorConfig.VALUE_CONVERTER_CONFIG_FIELD,
        "io.confluent.connect.avro.AvroConverter");
    config.put(SnowflakeSinkConnectorConfig.VALUE_SCHEMA_REGISTRY_CONFIG_FIELD, "http://fake-url");
    // get rid of these at the end
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    // avro
    SchemaBuilder schemaBuilder =
        SchemaBuilder.struct()
            .field("id_int8", Schema.INT8_SCHEMA)
            .field("id_int8_optional", Schema.OPTIONAL_INT8_SCHEMA)
            .field("id_int16", Schema.INT16_SCHEMA)
            .field("ID_INT32", Schema.INT32_SCHEMA)
            .field("id_int64", Schema.INT64_SCHEMA)
            .field("first_name", Schema.STRING_SCHEMA)
            .field("rating_float32", Schema.FLOAT32_SCHEMA)
            .field("rating_float64", Schema.FLOAT64_SCHEMA)
            .field("approval", Schema.BOOLEAN_SCHEMA)
            .field("info_array_string", SchemaBuilder.array(Schema.STRING_SCHEMA).build())
            .field("info_array_int", SchemaBuilder.array(Schema.INT32_SCHEMA).build())
            .field("info_array_json", SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).build())
            .field(
                "info_map", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build());

    Struct original =
        new Struct(schemaBuilder.build())
            .put("id_int8", (byte) 0)
            .put("id_int16", (short) 42)
            .put("ID_INT32", 42)
            .put("id_int64", 42L)
            .put("first_name", "zekai")
            .put("rating_float32", 0.99f)
            .put("rating_float64", 0.99d)
            .put("approval", true)
            .put("info_array_string", Arrays.asList("a", "b"))
            .put("info_array_int", Arrays.asList(1, 2))
            .put(
                "info_array_json",
                Arrays.asList(null, "{\"a\": 1, \"b\": null, \"c\": null, \"d\": \"89asda9s0a\"}"))
            .put("info_map", Collections.singletonMap("field", 3));

    SchemaRegistryClient schemaRegistry = new MockSchemaRegistryClient();
    AvroConverter avroConverter = new AvroConverter(schemaRegistry);
    avroConverter.configure(
        Collections.singletonMap("schema.registry.url", "http://fake-url"), false);
    byte[] converted = avroConverter.fromConnectData(topic, original.schema(), original);
    conn.createTableWithOnlyMetadataColumn(table);

    SchemaAndValue avroInputValue = avroConverter.toConnectData(topic, converted);

    long startOffset = 0;

    SinkRecord avroRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            avroInputValue.schema(),
            avroInputValue.value(),
            startOffset);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    // The first insert should fail and schema evolution will kick in to update the schema
    service.insert(Collections.singletonList(avroRecordValue));
    TestUtils.assertWithRetry(
        () ->
            service.getOffset(new TopicPartition(topic, partition))
                == NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE,
        20,
        5);

    TestUtils.checkTableSchema(table, SchematizationTestUtils.SF_AVRO_SCHEMA_FOR_TABLE_CREATION);

    // Retry the insert should succeed now with the updated schema
    service.insert(Collections.singletonList(avroRecordValue));
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == startOffset + 1, 20, 5);

    TestUtils.checkTableContentOneRow(
        table, SchematizationTestUtils.CONTENT_FOR_AVRO_TABLE_CREATION);

    service.closeAll();
  }

  @Test
  public void testSchematizationWithTableCreationAndJsonInput() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    config.put(SnowflakeSinkConnectorConfig.ENABLE_SCHEMATIZATION_CONFIG, "true");
    config.put(
        SnowflakeSinkConnectorConfig.VALUE_CONVERTER_CONFIG_FIELD,
        "org.apache.kafka.connect.json.JsonConverter");
    config.put(SnowflakeSinkConnectorConfig.VALUE_SCHEMA_REGISTRY_CONFIG_FIELD, "http://fake-url");
    config.put("schemas.enable", "false");
    // get rid of these at the end
    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    SchemaBuilder schemaBuilder =
        SchemaBuilder.struct()
            .field("id_int8", Schema.INT8_SCHEMA)
            .field("id_int8_optional", Schema.OPTIONAL_INT8_SCHEMA)
            .field("id_int16", Schema.INT16_SCHEMA)
            .field("\"id_int32_double_quotes\"", Schema.INT32_SCHEMA)
            .field("id_int64", Schema.INT64_SCHEMA)
            .field("first_name", Schema.STRING_SCHEMA)
            .field("rating_float32", Schema.FLOAT32_SCHEMA)
            .field("rating_float64", Schema.FLOAT64_SCHEMA)
            .field("approval", Schema.BOOLEAN_SCHEMA)
            .field("info_array", SchemaBuilder.array(Schema.STRING_SCHEMA).build())
            .field(
                "info_map", SchemaBuilder.map(Schema.STRING_SCHEMA, Schema.INT32_SCHEMA).build());

    Struct original =
        new Struct(schemaBuilder.build())
            .put("id_int8", (byte) 0)
            .put("id_int16", (short) 42)
            .put("\"id_int32_double_quotes\"", 42)
            .put("id_int64", 42L)
            .put("first_name", "zekai")
            .put("rating_float32", 0.99f)
            .put("rating_float64", 0.99d)
            .put("approval", true)
            .put("info_array", Arrays.asList("a", "b"))
            .put("info_map", Collections.singletonMap("field", 3));

    JsonConverter jsonConverter = new JsonConverter();
    jsonConverter.configure(config, false);
    byte[] converted = jsonConverter.fromConnectData(topic, original.schema(), original);
    conn.createTableWithOnlyMetadataColumn(table);

    SchemaAndValue jsonInputValue = jsonConverter.toConnectData(topic, converted);

    long startOffset = 0;

    SinkRecord jsonRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            jsonInputValue.schema(),
            jsonInputValue.value(),
            startOffset);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    // The first insert should fail and schema evolution will kick in to update the schema
    service.insert(Collections.singletonList(jsonRecordValue));
    TestUtils.assertWithRetry(
        () ->
            service.getOffset(new TopicPartition(topic, partition))
                == NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE,
        20,
        5);
    TestUtils.checkTableSchema(table, SchematizationTestUtils.SF_JSON_SCHEMA_FOR_TABLE_CREATION);

    // Retry the insert should succeed now with the updated schema
    service.insert(Collections.singletonList(jsonRecordValue));
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == startOffset + 1, 20, 5);

    TestUtils.checkTableContentOneRow(
        table, SchematizationTestUtils.CONTENT_FOR_JSON_TABLE_CREATION);

    service.closeAll();
  }

  @Test
  public void testSchematizationSchemaEvolutionWithNonNullableColumn() throws Exception {
    Map<String, String> config = TestUtils.getConfForStreaming();
    config.put(SnowflakeSinkConnectorConfig.ENABLE_SCHEMATIZATION_CONFIG, "true");
    config.put(
        SnowflakeSinkConnectorConfig.VALUE_CONVERTER_CONFIG_FIELD,
        "org.apache.kafka.connect.json.JsonConverter");
    config.put(SnowflakeSinkConnectorConfig.VALUE_SCHEMA_REGISTRY_CONFIG_FIELD, "http://fake-url");
    config.put("schemas.enable", "false");
    // get rid of these at the end
    SnowflakeSinkConnectorConfig.setDefaultValues(config);

    SchemaBuilder schemaBuilder = SchemaBuilder.struct().field("id_int8", Schema.INT8_SCHEMA);
    Struct original = new Struct(schemaBuilder.build()).put("id_int8", (byte) 0);

    JsonConverter jsonConverter = new JsonConverter();
    jsonConverter.configure(config, false);
    byte[] converted = jsonConverter.fromConnectData(topic, original.schema(), original);
    conn.createTableWithOnlyMetadataColumn(table);
    createNonNullableColumn(table, "id_int8_non_nullable");

    SchemaAndValue jsonInputValue = jsonConverter.toConnectData(topic, converted);

    long startOffset = 0;

    SinkRecord jsonRecordValue =
        new SinkRecord(
            topic,
            partition,
            Schema.STRING_SCHEMA,
            "test",
            jsonInputValue.schema(),
            jsonInputValue.value(),
            startOffset);

    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition))
            .build();

    // The first insert should fail and schema evolution will kick in to add the column
    service.insert(Collections.singletonList(jsonRecordValue));
    TestUtils.assertWithRetry(
        () ->
            service.getOffset(new TopicPartition(topic, partition))
                == NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE,
        20,
        5);

    // The second insert should fail again and schema evolution will kick in to update the
    // nullability
    service.insert(Collections.singletonList(jsonRecordValue));
    TestUtils.assertWithRetry(
        () ->
            service.getOffset(new TopicPartition(topic, partition))
                == NO_OFFSET_TOKEN_REGISTERED_IN_SNOWFLAKE,
        20,
        5);

    // Retry the insert should succeed now with the updated schema
    service.insert(Collections.singletonList(jsonRecordValue));
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == startOffset + 1, 20, 5);

    service.closeAll();
  }

  @Test
  public void testStreamingIngestionValidClientLag() throws Exception {
    Map<String, String> config = getConfig();
    config.put(SNOWPIPE_STREAMING_MAX_CLIENT_LAG, "30");
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    conn.createTable(table);

    // opens a channel for partition 0, table and topic
    SnowflakeSinkService service =
        SnowflakeSinkServiceFactory.builder(conn, IngestionMethodConfig.SNOWPIPE_STREAMING, config)
            .setRecordNumber(100)
            .setFlushTime(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
            .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
            .build();

    final long noOfRecords = 50;
    List<SinkRecord> sinkRecords =
        TestUtils.createJsonStringSinkRecords(0, noOfRecords, topic, partition);

    Thread.sleep(1000); // to ensure we flush buffer on time threshold
    service.insert(sinkRecords);

    try {
      // Wait 20 seconds here and no flush should happen since the max client lag is 30 seconds
      TestUtils.assertWithRetry(
          () -> service.getOffset(new TopicPartition(topic, partition)) == noOfRecords, 5, 4);
      Assert.fail("The rows should not be flushed");
    } catch (Exception e) {
      // do nothing
    }

    // Wait for enough time, the rows should be flushed
    TestUtils.assertWithRetry(
        () -> service.getOffset(new TopicPartition(topic, partition)) == noOfRecords, 30, 30);

    service.closeAll();
  }

  @Test
  public void testStreamingIngestionInvalidClientLag() {
    Map<String, String> config = TestUtils.getConfForStreaming();
    SnowflakeSinkConnectorConfig.setDefaultValues(config);
    Map<String, String> overriddenConfig = new HashMap<>(config);
    overriddenConfig.put(
        SnowflakeSinkConnectorConfig.SNOWPIPE_STREAMING_MAX_CLIENT_LAG, "TWOO_HUNDRED");

    conn.createTable(table);

    try {
      // This will fail in creation of client
      SnowflakeSinkServiceFactory.builder(
              conn, IngestionMethodConfig.SNOWPIPE_STREAMING, overriddenConfig)
          .setRecordNumber(1)
          .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
          .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(topicPartition)))
          .addTask(table, new TopicPartition(topic, partition)) // Internally calls startTask
          .build();
    } catch (IllegalArgumentException ex) {
      Assert.assertEquals(NumberFormatException.class, ex.getCause().getClass());
    }
  }

  private void createNonNullableColumn(String tableName, String colName) {
    String createTableQuery = "alter table identifier(?) add " + colName + " int not null";

    try {
      PreparedStatement stmt = conn.getConnection().prepareStatement(createTableQuery);
      stmt.setString(1, tableName);
      stmt.setString(2, colName);
      stmt.execute();
      stmt.close();
    } catch (SQLException e) {
      throw SnowflakeErrors.ERROR_2007.getException(e);
    }
  }

  private Map<String, String> getConfig() {
    if (!useOAuth) {
      return TestUtils.getConfForStreaming();
    } else {
      return TestUtils.getConfForStreamingWithOAuth();
    }
  }

  // note this test relies on testrole_kafka and testrole_kafka_1 roles being granted to test_kafka
  // user
  @Test
  public void testStreamingIngest_multipleChannel_distinctClients() throws Exception {
    // create cat and dog configs and partitions
    // one client is enabled but two clients should be created because different roles in config
    String catTopic = "catTopic_" + TestUtils.randomTableName();
    Map<String, String> catConfig = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(catConfig);
    catConfig.put(SnowflakeSinkConnectorConfig.ENABLE_STREAMING_CLIENT_OPTIMIZATION_CONFIG, "true");
    catConfig.put(Utils.SF_OAUTH_CLIENT_ID, "1");
    catConfig.put(Utils.NAME, catTopic);

    String dogTopic = "dogTopic_" + TestUtils.randomTableName();
    Map<String, String> dogConfig = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(dogConfig);
    dogConfig.put(SnowflakeSinkConnectorConfig.ENABLE_STREAMING_CLIENT_OPTIMIZATION_CONFIG, "true");
    dogConfig.put(Utils.SF_OAUTH_CLIENT_ID, "2");
    dogConfig.put(Utils.NAME, dogTopic);

    String fishTopic = "fishTopic_" + TestUtils.randomTableName();
    Map<String, String> fishConfig = getConfig();
    SnowflakeSinkConnectorConfig.setDefaultValues(fishConfig);
    fishConfig.put(
        SnowflakeSinkConnectorConfig.ENABLE_STREAMING_CLIENT_OPTIMIZATION_CONFIG, "true");
    fishConfig.put(Utils.SF_OAUTH_CLIENT_ID, "2");
    fishConfig.put(Utils.NAME, fishTopic);
    fishConfig.put(SNOWPIPE_STREAMING_MAX_CLIENT_LAG, "1");

    // setup connection and create tables
    TopicPartition catTp = new TopicPartition(catTopic, 0);
    SnowflakeConnectionService catConn =
        SnowflakeConnectionServiceFactory.builder().setProperties(catConfig).build();
    catConn.createTable(catTopic);

    TopicPartition dogTp = new TopicPartition(dogTopic, 1);
    SnowflakeConnectionService dogConn =
        SnowflakeConnectionServiceFactory.builder().setProperties(dogConfig).build();
    dogConn.createTable(dogTopic);

    TopicPartition fishTp = new TopicPartition(fishTopic, 1);
    SnowflakeConnectionService fishConn =
        SnowflakeConnectionServiceFactory.builder().setProperties(fishConfig).build();
    fishConn.createTable(fishTopic);

    // create the sink services
    SnowflakeSinkService catService =
        SnowflakeSinkServiceFactory.builder(
                catConn, IngestionMethodConfig.SNOWPIPE_STREAMING, catConfig)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(catTp)))
            .addTask(catTopic, catTp) // Internally calls startTask
            .build();

    SnowflakeSinkService dogService =
        SnowflakeSinkServiceFactory.builder(
                dogConn, IngestionMethodConfig.SNOWPIPE_STREAMING, dogConfig)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(dogTp)))
            .addTask(dogTopic, dogTp) // Internally calls startTask
            .build();

    SnowflakeSinkService fishService =
        SnowflakeSinkServiceFactory.builder(
                dogConn, IngestionMethodConfig.SNOWPIPE_STREAMING, fishConfig)
            .setRecordNumber(1)
            .setErrorReporter(new InMemoryKafkaRecordErrorReporter())
            .setSinkTaskContext(new InMemorySinkTaskContext(Collections.singleton(fishTp)))
            .addTask(fishTopic, fishTp) // Internally calls startTask
            .build();

    // create records
    final int catRecordCount = 9;
    final int dogRecordCount = 3;
    final int fishRecordCount = 1;

    List<SinkRecord> catRecords =
        TestUtils.createJsonStringSinkRecords(0, catRecordCount, catTp.topic(), catTp.partition());
    List<SinkRecord> dogRecords =
        TestUtils.createJsonStringSinkRecords(0, dogRecordCount, dogTp.topic(), dogTp.partition());
    List<SinkRecord> fishRecords =
        TestUtils.createJsonStringSinkRecords(
            0, fishRecordCount, fishTp.topic(), fishTp.partition());

    // insert records
    catService.insert(catRecords);
    dogService.insert(dogRecords);
    fishService.insert(fishRecords);

    // check data was ingested
    TestUtils.assertWithRetry(() -> catService.getOffset(catTp) == catRecordCount, 20, 20);
    TestUtils.assertWithRetry(() -> dogService.getOffset(dogTp) == dogRecordCount, 20, 20);
    TestUtils.assertWithRetry(() -> fishService.getOffset(fishTp) == fishRecordCount, 20, 20);

    // verify three clients were created
    assert StreamingClientProvider.getStreamingClientProviderInstance()
        .getRegisteredClients()
        .containsKey(new StreamingClientProperties(catConfig));
    assert StreamingClientProvider.getStreamingClientProviderInstance()
        .getRegisteredClients()
        .containsKey(new StreamingClientProperties(dogConfig));
    assert StreamingClientProvider.getStreamingClientProviderInstance()
        .getRegisteredClients()
        .containsKey(new StreamingClientProperties(fishConfig));

    // close services
    catService.closeAll();
    dogService.closeAll();
    fishService.closeAll();

    // verify three clients were closed
    assert !StreamingClientProvider.getStreamingClientProviderInstance()
        .getRegisteredClients()
        .containsKey(new StreamingClientProperties(catConfig));
    assert !StreamingClientProvider.getStreamingClientProviderInstance()
        .getRegisteredClients()
        .containsKey(new StreamingClientProperties(dogConfig));
    assert !StreamingClientProvider.getStreamingClientProviderInstance()
        .getRegisteredClients()
        .containsKey(new StreamingClientProperties(fishConfig));
  }
}
