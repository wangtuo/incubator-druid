/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.druid.indexing.common.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Binder;
import com.google.inject.Module;
import io.druid.data.input.FirehoseFactory;
import io.druid.data.input.impl.DimensionSchema;
import io.druid.data.input.impl.DimensionsSpec;
import io.druid.data.input.impl.DoubleDimensionSchema;
import io.druid.data.input.impl.FloatDimensionSchema;
import io.druid.data.input.impl.InputRowParser;
import io.druid.data.input.impl.LongDimensionSchema;
import io.druid.data.input.impl.NoopInputRowParser;
import io.druid.data.input.impl.StringDimensionSchema;
import io.druid.data.input.impl.TimeAndDimsParseSpec;
import io.druid.guice.GuiceAnnotationIntrospector;
import io.druid.guice.GuiceInjectableValues;
import io.druid.guice.GuiceInjectors;
import io.druid.indexing.common.TaskToolbox;
import io.druid.indexing.common.TestUtils;
import io.druid.indexing.common.actions.SegmentListUsedAction;
import io.druid.indexing.common.actions.TaskAction;
import io.druid.indexing.common.actions.TaskActionClient;
import io.druid.indexing.common.stats.RowIngestionMetersFactory;
import io.druid.indexing.common.task.CompactionTask.SegmentProvider;
import io.druid.indexing.common.task.IndexTask.IndexIOConfig;
import io.druid.indexing.common.task.IndexTask.IndexIngestionSpec;
import io.druid.indexing.common.task.IndexTask.IndexTuningConfig;
import io.druid.indexing.firehose.IngestSegmentFirehoseFactory;
import io.druid.jackson.DefaultObjectMapper;
import io.druid.java.util.common.ISE;
import io.druid.java.util.common.Intervals;
import io.druid.java.util.common.StringUtils;
import io.druid.java.util.common.granularity.Granularities;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import io.druid.query.aggregation.LongMaxAggregatorFactory;
import io.druid.query.aggregation.LongSumAggregatorFactory;
import io.druid.query.aggregation.first.FloatFirstAggregatorFactory;
import io.druid.query.aggregation.last.DoubleLastAggregatorFactory;
import io.druid.segment.IndexIO;
import io.druid.segment.IndexMergerV9;
import io.druid.segment.IndexSpec;
import io.druid.segment.Metadata;
import io.druid.segment.QueryableIndex;
import io.druid.segment.SimpleQueryableIndex;
import io.druid.segment.column.Column;
import io.druid.segment.column.ColumnBuilder;
import io.druid.segment.column.ValueType;
import io.druid.segment.data.CompressionFactory.LongEncodingStrategy;
import io.druid.segment.data.CompressionStrategy;
import io.druid.segment.data.ListIndexed;
import io.druid.segment.data.RoaringBitmapSerdeFactory;
import io.druid.segment.incremental.IncrementalIndex;
import io.druid.segment.indexing.DataSchema;
import io.druid.segment.indexing.granularity.ArbitraryGranularitySpec;
import io.druid.segment.loading.SegmentLoadingException;
import io.druid.segment.realtime.firehose.ChatHandlerProvider;
import io.druid.segment.realtime.firehose.NoopChatHandlerProvider;
import io.druid.segment.transform.TransformingInputRowParser;
import io.druid.segment.writeout.OffHeapMemorySegmentWriteOutMediumFactory;
import io.druid.server.security.AuthTestUtils;
import io.druid.server.security.AuthorizerMapper;
import io.druid.timeline.DataSegment;
import io.druid.timeline.partition.NumberedShardSpec;
import org.hamcrest.CoreMatchers;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RunWith(Parameterized.class)
public class CompactionTaskTest
{
  private static final String DATA_SOURCE = "dataSource";
  private static final String TIMESTAMP_COLUMN = "timestamp";
  private static final String MIXED_TYPE_COLUMN = "string_to_double";
  private static final Interval COMPACTION_INTERVAL = Intervals.of("2017-01-01/2017-06-01");
  private static final List<Interval> SEGMENT_INTERVALS = ImmutableList.of(
      Intervals.of("2017-01-01/2017-02-01"),
      Intervals.of("2017-02-01/2017-03-01"),
      Intervals.of("2017-03-01/2017-04-01"),
      Intervals.of("2017-04-01/2017-05-01"),
      Intervals.of("2017-05-01/2017-06-01")
  );
  private static final Map<Interval, DimensionSchema> MIXED_TYPE_COLUMN_MAP = ImmutableMap.of(
      Intervals.of("2017-01-01/2017-02-01"),
      new StringDimensionSchema(MIXED_TYPE_COLUMN),
      Intervals.of("2017-02-01/2017-03-01"),
      new StringDimensionSchema(MIXED_TYPE_COLUMN),
      Intervals.of("2017-03-01/2017-04-01"),
      new StringDimensionSchema(MIXED_TYPE_COLUMN),
      Intervals.of("2017-04-01/2017-05-01"),
      new StringDimensionSchema(MIXED_TYPE_COLUMN),
      Intervals.of("2017-05-01/2017-06-01"),
      new DoubleDimensionSchema(MIXED_TYPE_COLUMN)
  );
  private static final IndexTuningConfig TUNING_CONFIG = createTuningConfig();

  private static Map<String, DimensionSchema> DIMENSIONS;
  private static Map<String, AggregatorFactory> AGGREGATORS;
  private static List<DataSegment> SEGMENTS;
  private static RowIngestionMetersFactory rowIngestionMetersFactory = new TestUtils().getRowIngestionMetersFactory();
  private static ObjectMapper objectMapper = setupInjectablesInObjectMapper(new DefaultObjectMapper());
  private static Map<DataSegment, File> segmentMap;

  private final boolean keepSegmentGranularity;

  private TaskToolbox toolbox;

  @BeforeClass
  public static void setupClass()
  {
    DIMENSIONS = new HashMap<>();
    AGGREGATORS = new HashMap<>();

    DIMENSIONS.put(Column.TIME_COLUMN_NAME, new LongDimensionSchema(Column.TIME_COLUMN_NAME));
    DIMENSIONS.put(TIMESTAMP_COLUMN, new LongDimensionSchema(TIMESTAMP_COLUMN));
    for (int i = 0; i < 5; i++) {
      final StringDimensionSchema schema = new StringDimensionSchema(
          "string_dim_" + i,
          null,
          null
      );
      DIMENSIONS.put(schema.getName(), schema);
    }
    for (int i = 0; i < 5; i++) {
      final LongDimensionSchema schema = new LongDimensionSchema("long_dim_" + i);
      DIMENSIONS.put(schema.getName(), schema);
    }
    for (int i = 0; i < 5; i++) {
      final FloatDimensionSchema schema = new FloatDimensionSchema("float_dim_" + i);
      DIMENSIONS.put(schema.getName(), schema);
    }
    for (int i = 0; i < 5; i++) {
      final DoubleDimensionSchema schema = new DoubleDimensionSchema("double_dim_" + i);
      DIMENSIONS.put(schema.getName(), schema);
    }

    AGGREGATORS.put("agg_0", new CountAggregatorFactory("agg_0"));
    AGGREGATORS.put("agg_1", new LongSumAggregatorFactory("agg_1", "long_dim_1"));
    AGGREGATORS.put("agg_2", new LongMaxAggregatorFactory("agg_2", "long_dim_2"));
    AGGREGATORS.put("agg_3", new FloatFirstAggregatorFactory("agg_3", "float_dim_3"));
    AGGREGATORS.put("agg_4", new DoubleLastAggregatorFactory("agg_4", "double_dim_4"));

    segmentMap = new HashMap<>(5);
    for (int i = 0; i < 5; i++) {
      final Interval segmentInterval = Intervals.of(StringUtils.format("2017-0%d-01/2017-0%d-01", (i + 1), (i + 2)));
      segmentMap.put(
          new DataSegment(
              DATA_SOURCE,
              segmentInterval,
              "version",
              ImmutableMap.of(),
              findDimensions(i, segmentInterval),
              new ArrayList<>(AGGREGATORS.keySet()),
              new NumberedShardSpec(0, 1),
              0,
              1
          ),
          new File("file_" + i)
      );
    }
    SEGMENTS = new ArrayList<>(segmentMap.keySet());
  }

  private static ObjectMapper setupInjectablesInObjectMapper(ObjectMapper objectMapper)
  {
    final GuiceAnnotationIntrospector guiceIntrospector = new GuiceAnnotationIntrospector();
    objectMapper.setAnnotationIntrospectors(
        new AnnotationIntrospectorPair(
            guiceIntrospector, objectMapper.getSerializationConfig().getAnnotationIntrospector()
        ),
        new AnnotationIntrospectorPair(
            guiceIntrospector, objectMapper.getDeserializationConfig().getAnnotationIntrospector()
        )
    );
    GuiceInjectableValues injectableValues = new GuiceInjectableValues(
        GuiceInjectors.makeStartupInjectorWithModules(
            ImmutableList.<Module>of(
                new Module()
                {
                  @Override
                  public void configure(Binder binder)
                  {
                    binder.bind(AuthorizerMapper.class).toInstance(AuthTestUtils.TEST_AUTHORIZER_MAPPER);
                    binder.bind(ChatHandlerProvider.class).toInstance(new NoopChatHandlerProvider());
                    binder.bind(RowIngestionMetersFactory.class).toInstance(rowIngestionMetersFactory);
                  }
                }
            )
        )
    );
    objectMapper.setInjectableValues(injectableValues);
    objectMapper.registerModule(
        new SimpleModule().registerSubtypes(new NamedType(NumberedShardSpec.class, "NumberedShardSpec"))
    );
    return objectMapper;
  }

  private static List<String> findDimensions(int startIndex, Interval segmentInterval)
  {
    final List<String> dimensions = new ArrayList<>();
    dimensions.add(TIMESTAMP_COLUMN);
    for (int i = 0; i < 5; i++) {
      int postfix = i + startIndex;
      postfix = postfix >= 5 ? postfix - 5 : postfix;
      dimensions.add("string_dim_" + postfix);
      dimensions.add("long_dim_" + postfix);
      dimensions.add("float_dim_" + postfix);
      dimensions.add("double_dim_" + postfix);
    }
    dimensions.add(MIXED_TYPE_COLUMN_MAP.get(segmentInterval).getName());
    return dimensions;
  }

  private static IndexTuningConfig createTuningConfig()
  {
    return new IndexTuningConfig(
        5000000,
        500000,
        1000000L,
        null,
        null,
        null,
        new IndexSpec(
            new RoaringBitmapSerdeFactory(true),
            CompressionStrategy.LZ4,
            CompressionStrategy.LZF,
            LongEncodingStrategy.LONGS
        ),
        5000,
        true,
        false,
        true,
        false,
        null,
        100L,
        null,
        null,
        null,
        null
    );
  }

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Before
  public void setup()
  {
    toolbox = new TestTaskToolbox(
        new TestTaskActionClient(new ArrayList<>(segmentMap.keySet())),
        new TestIndexIO(objectMapper, segmentMap),
        segmentMap
    );
  }

  @Parameters(name = "keepSegmentGranularity={0}")
  public static Collection<Object[]> parameters()
  {
    return ImmutableList.of(
        new Object[] {false},
        new Object[] {true}
    );
  }

  public CompactionTaskTest(boolean keepSegmentGranularity)
  {
    this.keepSegmentGranularity = keepSegmentGranularity;
  }

  @Test
  public void testSerdeWithInterval() throws IOException
  {
    final CompactionTask task = new CompactionTask(
        null,
        null,
        DATA_SOURCE,
        COMPACTION_INTERVAL,
        null,
        null,
        null,
        createTuningConfig(),
        ImmutableMap.of("testKey", "testContext"),
        objectMapper,
        AuthTestUtils.TEST_AUTHORIZER_MAPPER,
        null,
        rowIngestionMetersFactory
    );
    final byte[] bytes = objectMapper.writeValueAsBytes(task);
    final CompactionTask fromJson = objectMapper.readValue(bytes, CompactionTask.class);
    Assert.assertEquals(task.getType(), fromJson.getType());
    Assert.assertEquals(task.getDataSource(), fromJson.getDataSource());
    Assert.assertEquals(task.getInterval(), fromJson.getInterval());
    Assert.assertEquals(task.getSegments(), fromJson.getSegments());
    Assert.assertEquals(task.getDimensionsSpec(), fromJson.getDimensionsSpec());
    Assert.assertEquals(task.getTuningConfig(), fromJson.getTuningConfig());
    Assert.assertEquals(task.getContext(), fromJson.getContext());
    Assert.assertNull(fromJson.getSegmentProvider().getSegments());
  }

  @Test
  public void testSerdeWithSegments() throws IOException
  {
    final CompactionTask task = new CompactionTask(
        null,
        null,
        DATA_SOURCE,
        null,
        SEGMENTS,
        null,
        null,
        createTuningConfig(),
        ImmutableMap.of("testKey", "testContext"),
        objectMapper,
        AuthTestUtils.TEST_AUTHORIZER_MAPPER,
        null,
        rowIngestionMetersFactory
    );
    final byte[] bytes = objectMapper.writeValueAsBytes(task);
    final CompactionTask fromJson = objectMapper.readValue(bytes, CompactionTask.class);
    Assert.assertEquals(task.getType(), fromJson.getType());
    Assert.assertEquals(task.getDataSource(), fromJson.getDataSource());
    Assert.assertEquals(task.getInterval(), fromJson.getInterval());
    Assert.assertEquals(task.getSegments(), fromJson.getSegments());
    Assert.assertEquals(task.getDimensionsSpec(), fromJson.getDimensionsSpec());
    Assert.assertEquals(task.getTuningConfig(), fromJson.getTuningConfig());
    Assert.assertEquals(task.getContext(), fromJson.getContext());
  }

  @Test
  public void testCreateIngestionSchemaWithKeepSegmentGranularity() throws IOException, SegmentLoadingException
  {
    final List<IndexIngestionSpec> ingestionSpecs = CompactionTask.createIngestionSchema(
        toolbox,
        new SegmentProvider(DATA_SOURCE, COMPACTION_INTERVAL),
        null,
        keepSegmentGranularity,
        TUNING_CONFIG,
        objectMapper
    );
    final List<DimensionsSpec> expectedDimensionsSpec = getExpectedDimensionsSpecForAutoGeneration(
        keepSegmentGranularity
    );

    if (keepSegmentGranularity) {
      Assert.assertEquals(5, ingestionSpecs.size());
      assertIngestionSchema(ingestionSpecs, expectedDimensionsSpec, SEGMENT_INTERVALS);
    } else {
      Assert.assertEquals(1, ingestionSpecs.size());
      assertIngestionSchema(ingestionSpecs, expectedDimensionsSpec, Collections.singletonList(COMPACTION_INTERVAL));
    }
  }

  @Test
  public void testCreateIngestionSchemaWithIgnoreSegmentGranularity() throws IOException, SegmentLoadingException
  {
    final List<IndexIngestionSpec> ingestionSpecs = CompactionTask.createIngestionSchema(
        toolbox,
        new SegmentProvider(DATA_SOURCE, COMPACTION_INTERVAL),
        null,
        keepSegmentGranularity,
        TUNING_CONFIG,
        objectMapper
    );
    final List<DimensionsSpec> expectedDimensionsSpec = getExpectedDimensionsSpecForAutoGeneration(
        keepSegmentGranularity
    );

    if (keepSegmentGranularity) {
      Assert.assertEquals(5, ingestionSpecs.size());
      assertIngestionSchema(ingestionSpecs, expectedDimensionsSpec, SEGMENT_INTERVALS);
    } else {
      Assert.assertEquals(1, ingestionSpecs.size());
      assertIngestionSchema(ingestionSpecs, expectedDimensionsSpec, Collections.singletonList(COMPACTION_INTERVAL));
    }
  }

  @Test
  public void testCreateIngestionSchemaWithCustomDimensionsSpec() throws IOException, SegmentLoadingException
  {
    final DimensionsSpec customSpec = new DimensionsSpec(
        Lists.newArrayList(
            new LongDimensionSchema("timestamp"),
            new StringDimensionSchema("string_dim_0"),
            new StringDimensionSchema("string_dim_1"),
            new StringDimensionSchema("string_dim_2"),
            new StringDimensionSchema("string_dim_3"),
            new StringDimensionSchema("string_dim_4"),
            new LongDimensionSchema("long_dim_0"),
            new LongDimensionSchema("long_dim_1"),
            new LongDimensionSchema("long_dim_2"),
            new LongDimensionSchema("long_dim_3"),
            new LongDimensionSchema("long_dim_4"),
            new FloatDimensionSchema("float_dim_0"),
            new FloatDimensionSchema("float_dim_1"),
            new FloatDimensionSchema("float_dim_2"),
            new FloatDimensionSchema("float_dim_3"),
            new FloatDimensionSchema("float_dim_4"),
            new DoubleDimensionSchema("double_dim_0"),
            new DoubleDimensionSchema("double_dim_1"),
            new DoubleDimensionSchema("double_dim_2"),
            new DoubleDimensionSchema("double_dim_3"),
            new DoubleDimensionSchema("double_dim_4"),
            new StringDimensionSchema(MIXED_TYPE_COLUMN)
        )
    );

    final List<IndexIngestionSpec> ingestionSpecs = CompactionTask.createIngestionSchema(
        toolbox,
        new SegmentProvider(DATA_SOURCE, COMPACTION_INTERVAL),
        customSpec,
        keepSegmentGranularity,
        TUNING_CONFIG,
        objectMapper
    );

    if (keepSegmentGranularity) {
      Assert.assertEquals(5, ingestionSpecs.size());
      final List<DimensionsSpec> dimensionsSpecs = new ArrayList<>(5);
      IntStream.range(0, 5).forEach(i -> dimensionsSpecs.add(customSpec));
      assertIngestionSchema(
          ingestionSpecs,
          dimensionsSpecs,
          SEGMENT_INTERVALS
      );
    } else {
      Assert.assertEquals(1, ingestionSpecs.size());
      assertIngestionSchema(
          ingestionSpecs,
          Collections.singletonList(customSpec),
          Collections.singletonList(COMPACTION_INTERVAL)
      );
    }
  }

  @Test
  public void testCreateIngestionSchemaWithCustomSegments() throws IOException, SegmentLoadingException
  {
    final List<IndexIngestionSpec> ingestionSpecs = CompactionTask.createIngestionSchema(
        toolbox,
        new SegmentProvider(SEGMENTS),
        null,
        keepSegmentGranularity,
        TUNING_CONFIG,
        objectMapper
    );
    final List<DimensionsSpec> expectedDimensionsSpec = getExpectedDimensionsSpecForAutoGeneration(
        keepSegmentGranularity
    );

    if (keepSegmentGranularity) {
      Assert.assertEquals(5, ingestionSpecs.size());
      assertIngestionSchema(ingestionSpecs, expectedDimensionsSpec, SEGMENT_INTERVALS);
    } else {
      Assert.assertEquals(1, ingestionSpecs.size());
      assertIngestionSchema(ingestionSpecs, expectedDimensionsSpec, Collections.singletonList(COMPACTION_INTERVAL));
    }
  }

  @Test
  public void testCreateIngestionSchemaWithDifferentSegmentSet() throws IOException, SegmentLoadingException
  {
    expectedException.expect(CoreMatchers.instanceOf(IllegalStateException.class));
    expectedException.expectMessage(CoreMatchers.containsString("are different from the current used segments"));

    final List<DataSegment> segments = new ArrayList<>(SEGMENTS);
    segments.remove(0);
    CompactionTask.createIngestionSchema(
        toolbox,
        new SegmentProvider(segments),
        null,
        keepSegmentGranularity,
        TUNING_CONFIG,
        objectMapper
    );
  }

  @Test
  public void testMissingMetadata() throws IOException, SegmentLoadingException
  {
    expectedException.expect(RuntimeException.class);
    expectedException.expectMessage(CoreMatchers.startsWith("Index metadata doesn't exist for segment"));

    final TestIndexIO indexIO = (TestIndexIO) toolbox.getIndexIO();
    indexIO.removeMetadata(Iterables.getFirst(indexIO.getQueryableIndexMap().keySet(), null));
    final List<DataSegment> segments = new ArrayList<>(SEGMENTS);
    CompactionTask.createIngestionSchema(
        toolbox,
        new SegmentProvider(segments),
        null,
        keepSegmentGranularity,
        TUNING_CONFIG,
        objectMapper
    );
  }

  @Test
  public void testEmptyInterval()
  {
    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(CoreMatchers.containsString("must specify a nonempty interval"));

    final CompactionTask task = new CompactionTask(
        null,
        null,
        "foo",
        Intervals.of("2000-01-01/2000-01-01"),
        null,
        null,
        null,
        null,
        null,
        objectMapper,
        AuthTestUtils.TEST_AUTHORIZER_MAPPER,
        new NoopChatHandlerProvider(),
        null
    );
  }

  private static List<DimensionsSpec> getExpectedDimensionsSpecForAutoGeneration(boolean keepSegmentGranularity)
  {
    if (keepSegmentGranularity) {
      return ImmutableList.of(
          new DimensionsSpec(getDimensionSchema(new StringDimensionSchema("string_to_double"))),
          new DimensionsSpec(getDimensionSchema(new StringDimensionSchema("string_to_double"))),
          new DimensionsSpec(getDimensionSchema(new StringDimensionSchema("string_to_double"))),
          new DimensionsSpec(getDimensionSchema(new StringDimensionSchema("string_to_double"))),
          new DimensionsSpec(getDimensionSchema(new DoubleDimensionSchema("string_to_double")))
      );
    } else {
      return Collections.singletonList(
          new DimensionsSpec(getDimensionSchema(new DoubleDimensionSchema("string_to_double")))
      );
    }
  }

  private static List<DimensionSchema> getDimensionSchema(DimensionSchema mixedTypeColumn)
  {
    return Lists.newArrayList(
        new LongDimensionSchema("timestamp"),
        new StringDimensionSchema("string_dim_4"),
        new LongDimensionSchema("long_dim_4"),
        new FloatDimensionSchema("float_dim_4"),
        new DoubleDimensionSchema("double_dim_4"),
        new StringDimensionSchema("string_dim_0"),
        new LongDimensionSchema("long_dim_0"),
        new FloatDimensionSchema("float_dim_0"),
        new DoubleDimensionSchema("double_dim_0"),
        new StringDimensionSchema("string_dim_1"),
        new LongDimensionSchema("long_dim_1"),
        new FloatDimensionSchema("float_dim_1"),
        new DoubleDimensionSchema("double_dim_1"),
        new StringDimensionSchema("string_dim_2"),
        new LongDimensionSchema("long_dim_2"),
        new FloatDimensionSchema("float_dim_2"),
        new DoubleDimensionSchema("double_dim_2"),
        new StringDimensionSchema("string_dim_3"),
        new LongDimensionSchema("long_dim_3"),
        new FloatDimensionSchema("float_dim_3"),
        new DoubleDimensionSchema("double_dim_3"),
        mixedTypeColumn
    );
  }

  private static void assertIngestionSchema(
      List<IndexIngestionSpec> ingestionSchemas,
      List<DimensionsSpec> expectedDimensionsSpecs,
      List<Interval> expectedSegmentIntervals
  )
  {
    Preconditions.checkArgument(
        ingestionSchemas.size() == expectedDimensionsSpecs.size(),
        "ingesionSchemas.size()[%s] should be same with expectedDimensionsSpecs.size()[%s]",
        ingestionSchemas.size(),
        expectedDimensionsSpecs.size()
    );

    for (int i = 0; i < ingestionSchemas.size(); i++) {
      final IndexIngestionSpec ingestionSchema = ingestionSchemas.get(i);
      final DimensionsSpec expectedDimensionsSpec = expectedDimensionsSpecs.get(i);

      // assert dataSchema
      final DataSchema dataSchema = ingestionSchema.getDataSchema();
      Assert.assertEquals(DATA_SOURCE, dataSchema.getDataSource());

      final InputRowParser parser = objectMapper.convertValue(dataSchema.getParser(), InputRowParser.class);
      Assert.assertTrue(parser instanceof TransformingInputRowParser);
      Assert.assertTrue(((TransformingInputRowParser) parser).getParser() instanceof NoopInputRowParser);
      Assert.assertTrue(parser.getParseSpec() instanceof TimeAndDimsParseSpec);
      Assert.assertEquals(
          new HashSet<>(expectedDimensionsSpec.getDimensions()),
          new HashSet<>(parser.getParseSpec().getDimensionsSpec().getDimensions())
      );
      final Set<AggregatorFactory> expectedAggregators = AGGREGATORS.values()
                                                                    .stream()
                                                                    .map(AggregatorFactory::getCombiningFactory)
                                                                    .collect(Collectors.toSet());
      Assert.assertEquals(expectedAggregators, new HashSet<>(Arrays.asList(dataSchema.getAggregators())));
      Assert.assertEquals(
          new ArbitraryGranularitySpec(
              Granularities.NONE,
              false,
              Collections.singletonList(expectedSegmentIntervals.get(i))
          ),
          dataSchema.getGranularitySpec()
      );

      // assert ioConfig
      final IndexIOConfig ioConfig = ingestionSchema.getIOConfig();
      Assert.assertFalse(ioConfig.isAppendToExisting());
      final FirehoseFactory firehoseFactory = ioConfig.getFirehoseFactory();
      Assert.assertTrue(firehoseFactory instanceof IngestSegmentFirehoseFactory);
      final IngestSegmentFirehoseFactory ingestSegmentFirehoseFactory = (IngestSegmentFirehoseFactory) firehoseFactory;
      Assert.assertEquals(DATA_SOURCE, ingestSegmentFirehoseFactory.getDataSource());
      Assert.assertEquals(expectedSegmentIntervals.get(i), ingestSegmentFirehoseFactory.getInterval());
      Assert.assertNull(ingestSegmentFirehoseFactory.getDimensionsFilter());

      // check the order of dimensions
      Assert.assertEquals(
          new HashSet<>(expectedDimensionsSpec.getDimensionNames()),
          new HashSet<>(ingestSegmentFirehoseFactory.getDimensions())
      );
      // check the order of metrics
      Assert.assertEquals(
          Lists.newArrayList("agg_4", "agg_3", "agg_2", "agg_1", "agg_0"),
          ingestSegmentFirehoseFactory.getMetrics()
      );

      // assert tuningConfig
      Assert.assertEquals(createTuningConfig(), ingestionSchema.getTuningConfig());
    }
  }

  private static class TestTaskToolbox extends TaskToolbox
  {
    private final Map<DataSegment, File> segmentFileMap;

    TestTaskToolbox(
        TaskActionClient taskActionClient,
        IndexIO indexIO,
        Map<DataSegment, File> segmentFileMap
    )
    {
      super(
          null,
          taskActionClient,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          indexIO,
          null,
          null,
          null,
          new IndexMergerV9(objectMapper, indexIO, OffHeapMemorySegmentWriteOutMediumFactory.instance()),
          null,
          null,
          null,
          null,
          new NoopTestTaskFileWriter()
      );
      this.segmentFileMap = segmentFileMap;
    }

    @Override
    public Map<DataSegment, File> fetchSegments(List<DataSegment> segments)
    {
      final Map<DataSegment, File> submap = new HashMap<>(segments.size());
      for (DataSegment segment : segments) {
        final File file = Preconditions.checkNotNull(segmentFileMap.get(segment));
        submap.put(segment, file);
      }
      return submap;
    }
  }

  private static class TestTaskActionClient implements TaskActionClient
  {
    private final List<DataSegment> segments;

    TestTaskActionClient(List<DataSegment> segments)
    {
      this.segments = segments;
    }

    @Override
    public <RetType> RetType submit(TaskAction<RetType> taskAction)
    {
      if (!(taskAction instanceof SegmentListUsedAction)) {
        throw new ISE("action[%s] is not supported", taskAction);
      }
      return (RetType) segments;
    }
  }

  private static class TestIndexIO extends IndexIO
  {
    private final Map<File, QueryableIndex> queryableIndexMap;

    TestIndexIO(
        ObjectMapper mapper,
        Map<DataSegment, File> segmentFileMap
    )
    {
      super(mapper, OffHeapMemorySegmentWriteOutMediumFactory.instance(), () -> 0);

      queryableIndexMap = new HashMap<>(segmentFileMap.size());
      for (Entry<DataSegment, File> entry : segmentFileMap.entrySet()) {
        final DataSegment segment = entry.getKey();
        final List<String> columnNames = new ArrayList<>(segment.getDimensions().size() + segment.getMetrics().size());
        columnNames.add(Column.TIME_COLUMN_NAME);
        columnNames.addAll(segment.getDimensions());
        columnNames.addAll(segment.getMetrics());
        final Map<String, Column> columnMap = new HashMap<>(columnNames.size());
        final List<AggregatorFactory> aggregatorFactories = new ArrayList<>(segment.getMetrics().size());

        for (String columnName : columnNames) {
          if (MIXED_TYPE_COLUMN.equals(columnName)) {
            columnMap.put(columnName, createColumn(MIXED_TYPE_COLUMN_MAP.get(segment.getInterval())));
          } else if (DIMENSIONS.containsKey(columnName)) {
            columnMap.put(columnName, createColumn(DIMENSIONS.get(columnName)));
          } else if (AGGREGATORS.containsKey(columnName)) {
            columnMap.put(columnName, createColumn(AGGREGATORS.get(columnName)));
            aggregatorFactories.add(AGGREGATORS.get(columnName));
          }
        }

        final Metadata metadata = new Metadata(
            null,
            aggregatorFactories.toArray(new AggregatorFactory[0]),
            null,
            null,
            null
        );

        queryableIndexMap.put(
            entry.getValue(),
            new SimpleQueryableIndex(
                segment.getInterval(),
                new ListIndexed<>(segment.getDimensions(), String.class),
                null,
                columnMap,
                null,
                metadata
            )
        );
      }
    }

    @Override
    public QueryableIndex loadIndex(File file)
    {
      return queryableIndexMap.get(file);
    }

    void removeMetadata(File file)
    {
      final SimpleQueryableIndex index = (SimpleQueryableIndex) queryableIndexMap.get(file);
      if (index != null) {
        queryableIndexMap.put(
            file,
            new SimpleQueryableIndex(
                index.getDataInterval(),
                index.getColumnNames(),
                index.getAvailableDimensions(),
                index.getBitmapFactoryForDimensions(),
                index.getColumns(),
                index.getFileMapper(),
                null,
                index.getDimensionHandlers()
            )
        );
      }
    }

    Map<File, QueryableIndex> getQueryableIndexMap()
    {
      return queryableIndexMap;
    }
  }

  private static Column createColumn(DimensionSchema dimensionSchema)
  {
    return new ColumnBuilder()
        .setType(IncrementalIndex.TYPE_MAP.get(dimensionSchema.getValueType()))
        .setDictionaryEncodedColumn(() -> null)
        .setBitmapIndex(() -> null)
        .build();
  }

  private static Column createColumn(AggregatorFactory aggregatorFactory)
  {
    return new ColumnBuilder()
        .setType(ValueType.fromString(aggregatorFactory.getTypeName()))
        .build();
  }
}
