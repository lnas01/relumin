package com.behase.relumin.scheduler;

import com.behase.relumin.model.*;
import com.behase.relumin.service.ClusterService;
import com.behase.relumin.service.NodeService;
import com.behase.relumin.service.NotifyService;
import com.behase.relumin.webconfig.WebConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.fluentd.logger.FluentLogger;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.internal.util.reflection.Whitebox;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.boot.test.OutputCapture;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class NodeSchedulerTest {
    @InjectMocks
    @Spy
    private NodeScheduler nodeScheduler = new NodeScheduler();

    @Mock
    private ClusterService clusterService;

    @Mock
    private NodeService nodeService;

    @Mock
    private JedisPool datastoreJedisPool;

    @Mock
    private NotifyService notifyService;

    @Spy
    private ObjectMapper mapper = WebConfig.MAPPER;

    @Mock
    private FluentLogger fluentLogger;

    @Rule
    public OutputCapture capture = new OutputCapture();

    @Before
    public void init() {
        Whitebox.setInternalState(nodeScheduler, "collectStaticsInfoMaxCount", 100);
        Whitebox.setInternalState(nodeScheduler, "redisPrefixKey", "_relumin");
        Whitebox.setInternalState(nodeScheduler, "noticeMailHost", "localhost");
        Whitebox.setInternalState(nodeScheduler, "noticeMailPort", 25);
        Whitebox.setInternalState(nodeScheduler, "noticeMailFrom", "from@example.com");
        Whitebox.setInternalState(nodeScheduler, "outputMetricsFluentdNodeTag", "tag");
    }

    @Test
    public void collectStaticsInfo() throws Exception {
        doReturn(Sets.newHashSet("cluster1", "cluster2")).when(clusterService).getClusters();
        doReturn(new Notice()).when(clusterService).getClusterNotice("cluster1");
        doReturn(null).when(clusterService).getClusterNotice("cluster2");
        doReturn(
                Cluster.builder()
                        .clusterName("cluster1")
                        .info(Maps.newHashMap())
                        .nodes(Lists.newArrayList(
                                ClusterNode.builder().nodeId("nodeId").hostAndPort("localhost:10000").build()
                        ))
                        .build()
        ).when(clusterService).getCluster("cluster1");
        doReturn(
                Cluster.builder()
                        .clusterName("cluster2")
                        .info(Maps.newHashMap())
                        .nodes(Lists.newArrayList(
                                ClusterNode.builder().nodeId("nodeId").hostAndPort("localhost:10000").build()
                        ))
                        .build()
        ).when(clusterService).getCluster("cluster2");
        doReturn(Maps.newHashMap()).when(nodeService).getStaticsInfo(any());
        doReturn(mock(Jedis.class)).when(datastoreJedisPool).getResource();

        doReturn(Lists.newArrayList(new NoticeJob())).when(nodeScheduler).getNoticeJobs(any(), any(), any());

        nodeScheduler.collectStaticsInfo();

        String output = capture.toString();
        assertThat(output, containsString("NOTIFY"));
    }

    @Test
    public void saveSlowLogs() throws Exception {
        // given
        List<SlowLog> slowLogs = Lists.newArrayList(
                SlowLog.builder().id(5L).timeStamp(5L).build(),
                SlowLog.builder().id(4L).timeStamp(4L).build(),
                SlowLog.builder().id(3L).timeStamp(3L).build(),
                SlowLog.builder().id(2L).timeStamp(2L).build(),
                SlowLog.builder().id(1L).timeStamp(1L).build()
        );
        Jedis jedis = mock(Jedis.class);
        doReturn(new PagerData<SlowLog>(0, 5, 0, Lists.newArrayList())).when(clusterService).getClusterSlowLogHistory(anyString(), anyLong(), anyLong());
        doReturn(null).when(jedis).lpush(anyString(), any());
        doReturn(jedis).when(datastoreJedisPool).getResource();

        // when
        nodeScheduler.saveSlowLogs(slowLogs, "clusterName");

        // then
        String[] expected = {
                mapper.writeValueAsString(SlowLog.builder().id(1L).timeStamp(1L).build()),
                mapper.writeValueAsString(SlowLog.builder().id(2L).timeStamp(2L).build()),
                mapper.writeValueAsString(SlowLog.builder().id(3L).timeStamp(3L).build()),
                mapper.writeValueAsString(SlowLog.builder().id(4L).timeStamp(4L).build()),
                mapper.writeValueAsString(SlowLog.builder().id(5L).timeStamp(5L).build())
        };
        verify(jedis).lpush("_relumin.cluster.clusterName.slowLog", expected);
    }

    @Test
    public void outputMetrics() {
        Map<ClusterNode, Map<String, String>> staticsInfos = ImmutableMap.of(
                ClusterNode.builder().nodeId("nodeId1").hostAndPort("localhost:10000").build(),
                ImmutableMap.of(
                        "use_memory", "200000000",
                        "connected_clients", "1000",
                        "instantaneous_ops_per_sec", "1000"
                ),
                ClusterNode.builder().nodeId("nodeId2").hostAndPort("localhost:10001").build(),
                ImmutableMap.of(
                        "use_memory", "200000000",
                        "connected_clients", "1000",
                        "instantaneous_ops_per_sec", "1000"
                )
        );

        nodeScheduler.outputMetrics(new Cluster(), staticsInfos);
        verify(fluentLogger, times(2)).log(anyString(), any());
    }

    @Test
    public void getNoticeJobs_is_invalid_endtime() {
        Notice notice = Notice.builder().invalidEndTime(String.valueOf(System.currentTimeMillis() + 1000L))
                .build();
        List<NoticeJob> result = nodeScheduler.getNoticeJobs(notice, null, null);
        assertThat(result.size(), is(0));
    }

    @Test
    public void getNoticeJobs() {
        Notice notice = new Notice();
        notice.setItems(Lists.newArrayList(
                NoticeItem.builder()
                        .metricsName("cluster_state")
                        .metricsType(NoticeItem.NoticeType.CLUSTER_INFO.getValue())
                        .operator(NoticeItem.NoticeOperator.EQ.getValue())
                        .value("ok")
                        .valueType(NoticeItem.NoticeValueType.STRING.getValue())
                        .build(),
                NoticeItem.builder()
                        .metricsName("cluster_current_epoch")
                        .metricsType(NoticeItem.NoticeType.CLUSTER_INFO.getValue())
                        .operator(NoticeItem.NoticeOperator.EQ.getValue())
                        .value("1")
                        .valueType(NoticeItem.NoticeValueType.NUMBER.getValue())
                        .build(),
                NoticeItem.builder()
                        .metricsName("cluster_known_nodes")
                        .metricsType(NoticeItem.NoticeType.CLUSTER_INFO.getValue())
                        .operator(NoticeItem.NoticeOperator.EQ.getValue())
                        .value("2")
                        .valueType(NoticeItem.NoticeValueType.NUMBER.getValue())
                        .build(),
                NoticeItem.builder()
                        .metricsName("use_memory")
                        .metricsType(NoticeItem.NoticeType.NODE_INFO.getValue())
                        .operator(NoticeItem.NoticeOperator.EQ.getValue())
                        .value("200000000")
                        .valueType(NoticeItem.NoticeValueType.NUMBER.getValue())
                        .build(),
                NoticeItem.builder()
                        .metricsName("connected_clients")
                        .metricsType(NoticeItem.NoticeType.NODE_INFO.getValue())
                        .operator(NoticeItem.NoticeOperator.EQ.getValue())
                        .value("1000")
                        .valueType(NoticeItem.NoticeValueType.NUMBER.getValue())
                        .build(),
                NoticeItem.builder()
                        .metricsName("instantaneous_ops_per_sec")
                        .metricsType(NoticeItem.NoticeType.NODE_INFO.getValue())
                        .operator(NoticeItem.NoticeOperator.EQ.getValue())
                        .value("2000")
                        .valueType(NoticeItem.NoticeValueType.NUMBER.getValue())
                        .build()
        ));
        Map<ClusterNode, Map<String, String>> staticsInfos = ImmutableMap.of(
                ClusterNode.builder().nodeId("nodeId1").hostAndPort("localhost:10000").build(),
                ImmutableMap.of(
                        "use_memory", "200000000",
                        "connected_clients", "1000",
                        "instantaneous_ops_per_sec", "1000"
                ),
                ClusterNode.builder().nodeId("nodeId2").hostAndPort("localhost:10001").build(),
                ImmutableMap.of(
                        "use_memory", "200000000",
                        "connected_clients", "1000",
                        "instantaneous_ops_per_sec", "1000"
                )
        );
        Cluster cluster = new Cluster();
        cluster.setStatus("ok");
        cluster.setInfo(ImmutableMap.of(
                "cluster_state", "ok",
                "cluster_current_epoch", "1",
                "cluster_known_nodes", "1"
        ));

        List<NoticeJob> result = nodeScheduler.getNoticeJobs(notice, cluster, staticsInfos);
        log.info("result={}", result);
        assertThat(result.size(), is(4));
        assertThat(result.get(0).getResultValues().size(), is(1));
        assertThat(result.get(1).getResultValues().size(), is(1));
        assertThat(result.get(2).getResultValues().size(), is(2));
        assertThat(result.get(3).getResultValues().size(), is(2));
    }

    @Test
    public void isInInvalidEndTime() {
        Notice notice;

        notice = Notice.builder().invalidEndTime(String.valueOf(System.currentTimeMillis() + 1000L)).build();
        assertThat(nodeScheduler.isInInvalidEndTime(notice), is(true));

        notice = Notice.builder().invalidEndTime(String.valueOf(System.currentTimeMillis() - 1000L)).build();
        assertThat(nodeScheduler.isInInvalidEndTime(notice), is(false));

        notice = Notice.builder().invalidEndTime("").build();
        assertThat(nodeScheduler.isInInvalidEndTime(notice), is(false));
    }

    @Test
    public void shouldNotify() {
        assertThat(nodeScheduler.shouldNotify("string", "eq", "hoge", "hoge"), is(true));
        assertThat(nodeScheduler.shouldNotify("string", "ne", "hogo", "hoge"), is(true));
        assertThat(nodeScheduler.shouldNotify("string", "lt", "hoge", "hoge"), is(false));

        assertThat(nodeScheduler.shouldNotify("number", "eq", "10", "10"), is(true));
        assertThat(nodeScheduler.shouldNotify("number", "eq", "1", "1.00000"), is(true));
        assertThat(nodeScheduler.shouldNotify("number", "eq", "0.000000000001", "0.000000000001"), is(true));

        assertThat(nodeScheduler.shouldNotify("number", "ne", "1", "1.1"), is(true));
        assertThat(nodeScheduler.shouldNotify("number", "ne", "0.000000000001", "0.00000000001"), is(true));

        assertThat(nodeScheduler.shouldNotify("number", "gt", "1.0000000000000000001", "1"), is(true));
        assertThat(nodeScheduler.shouldNotify("number", "gt", "1", "1"), is(false));
        assertThat(nodeScheduler.shouldNotify("number", "gt", "0", "1"), is(false));

        assertThat(nodeScheduler.shouldNotify("number", "ge", "1.0000000000000000001", "1"), is(true));
        assertThat(nodeScheduler.shouldNotify("number", "ge", "1", "1"), is(true));
        assertThat(nodeScheduler.shouldNotify("number", "ge", "0", "1"), is(false));

        assertThat(nodeScheduler.shouldNotify("number", "lt", "1.0000000000000000001", "1"), is(false));
        assertThat(nodeScheduler.shouldNotify("number", "lt", "1", "1"), is(false));
        assertThat(nodeScheduler.shouldNotify("number", "lt", "0", "1"), is(true));

        assertThat(nodeScheduler.shouldNotify("number", "le", "1.0000000000000000001", "1"), is(false));
        assertThat(nodeScheduler.shouldNotify("number", "le", "1", "1"), is(true));
        assertThat(nodeScheduler.shouldNotify("number", "le", "0", "1"), is(true));
    }
}
