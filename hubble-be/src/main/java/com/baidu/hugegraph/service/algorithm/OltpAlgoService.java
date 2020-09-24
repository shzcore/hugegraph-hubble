package com.baidu.hugegraph.service.algorithm;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.baidu.hugegraph.driver.HugeClient;
import com.baidu.hugegraph.driver.TraverserManager;
import com.baidu.hugegraph.entity.algorithm.ShortPath;
import com.baidu.hugegraph.entity.enums.AsyncTaskStatus;
import com.baidu.hugegraph.entity.enums.ExecuteStatus;
import com.baidu.hugegraph.entity.enums.ExecuteType;
import com.baidu.hugegraph.entity.query.ExecuteHistory;
import com.baidu.hugegraph.entity.query.GraphView;
import com.baidu.hugegraph.entity.query.GremlinResult;
import com.baidu.hugegraph.entity.query.JsonView;
import com.baidu.hugegraph.entity.query.TableView;
import com.baidu.hugegraph.exception.InternalException;
import com.baidu.hugegraph.service.HugeClientPoolService;
import com.baidu.hugegraph.service.query.ExecuteHistoryService;
import com.baidu.hugegraph.structure.graph.Edge;
import com.baidu.hugegraph.structure.graph.Path;
import com.baidu.hugegraph.structure.graph.Vertex;
import com.baidu.hugegraph.util.HubbleUtil;
import com.google.common.collect.ImmutableMap;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class OltpAlgoService {

    @Autowired
    private HugeClientPoolService poolService;
    @Autowired
    private ExecuteHistoryService historyService;

    private HugeClient getClient(int connId) {
        return this.poolService.getOrCreate(connId);
    }

    public GremlinResult shortPath(int connId, ShortPath body) {
        HugeClient client = this.getClient(connId);
        TraverserManager traverser = client.traverser();
        Path result = traverser.shortestPath(body.getSource(), body.getTarget(),
                                             body.getDirection(), body.getLabel(),
                                             body.getMaxDepth(), body.getMaxDegree(),
                                             body.getSkipDegree(), body.getCapacity());
        JsonView jsonView = new JsonView();
        jsonView.setData(result.objects());
        Date createTime = HubbleUtil.nowDate();
        TableView tableView = this.buildPathTableView(result);
        GraphView graphView = this.buildPathGraphView(result);
        // Insert execute history
        ExecuteStatus status = ExecuteStatus.SUCCESS;
        ExecuteHistory history;
        history = new ExecuteHistory(null, connId, 0L, ExecuteType.ALGORITHM,
                                     body.toString(), status, AsyncTaskStatus.UNKNOWN,
                                     -1L, createTime);
        int rows = this.historyService.save(history);
        if (rows != 1) {
            throw new InternalException("entity.insert.failed", history);
        }
        return GremlinResult.builder()
                            .type(GremlinResult.Type.PATH)
                            .jsonView(jsonView)
                            .tableView(tableView)
                            .graphView(graphView)
                            .build();
    }

    private TableView buildPathTableView(Path result) {
        List<Object> elements = result.objects();
        List<Object> paths = new ArrayList<>(elements.size());
        List<Object> ids = new ArrayList<>();
        elements.forEach(element -> {
            if (element instanceof Vertex) {
                ids.add(((Vertex) element).id());
            } else if (element instanceof Edge) {
                ids.add(((Edge) element).id());
            } else {
                ids.add(element);
            }
        });
        paths.add(ImmutableMap.of("path", ids));
        return new TableView(TableView.PATH_HEADER, paths);
    }

    private GraphView buildPathGraphView(Path result) {
        Map<Object, Vertex> vertices = new HashMap<>();
        Map<String, Edge> edges = new HashMap<>();

        List<Object> ids = new ArrayList<>();
        List<Object> elements = ((Path) result).objects();
        for (Object element : elements) {
            if (element instanceof Vertex) {
                Vertex vertex = (Vertex) element;
                vertices.put(vertex.id(), vertex);
            } else if (element instanceof Edge) {
                Edge edge = (Edge) element;
                edges.put(edge.id(), edge);
            } else {
                return GraphView.EMPTY;
            }
        }
        return new GraphView(vertices.values(), new ArrayList<>());
    }
}
