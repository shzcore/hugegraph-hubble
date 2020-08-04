/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.service.query;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.baidu.hugegraph.config.HugeConfig;
import com.baidu.hugegraph.driver.HugeClient;
import com.baidu.hugegraph.entity.enums.AsyncTaskStatus;
import com.baidu.hugegraph.entity.enums.ExecuteType;
import com.baidu.hugegraph.entity.query.ExecuteHistory;
import com.baidu.hugegraph.mapper.query.ExecuteHistoryMapper;
import com.baidu.hugegraph.options.HubbleOptions;
import com.baidu.hugegraph.service.HugeClientPoolService;
import com.baidu.hugegraph.structure.Task;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
public class ExecuteHistoryService {

    @Autowired
    private HugeConfig config;
    @Autowired
    private ExecuteHistoryMapper mapper;
    @Autowired
    private HugeClientPoolService poolService;

    private HugeClient getClient(int connId) {
        return this.poolService.getOrCreate(connId);
    }

    public IPage<ExecuteHistory> list(int connId, long current, long pageSize) {
        HugeClient client = this.getClient(connId);
        QueryWrapper<ExecuteHistory> query = Wrappers.query();
        query.eq("conn_id", connId).orderByDesc("create_time");
        Page<ExecuteHistory> page = new Page<>(current, pageSize);
        IPage<ExecuteHistory> results = this.mapper.selectPage(page, query);

        int limit = this.config.get(HubbleOptions.EXECUTE_HISTORY_SHOW_LIMIT);
        if (results.getTotal() > limit) {
            log.debug("Execute history total records: {}", results.getTotal());
            results.setTotal(limit);
        }
        // Get the status of successful execution of asynchronous tasks
        results.getRecords().forEach((p) -> {
            if (p.getType().equals(ExecuteType.GREMLIN_ASYNC)) {
                try {
                    Task task = client.task().get(p.getAsyncId());
                    long endDate = task.updateTime() > 0 ? task.updateTime() : new Date().getTime();
                    p.setDuration(endDate - task.createTime());
                    p.setAsyncStatus(AsyncTaskStatus.valueOf(task.status().toUpperCase()));
                } catch (Exception e) {
                    p.setDuration(0L);
                    p.setAsyncStatus(AsyncTaskStatus.UNKNOWN);
                }

            }
        });
        return results;
    }


    public ExecuteHistory get(int connId, int id) {
        HugeClient client = this.getClient(connId);
        ExecuteHistory history = this.mapper.selectById(id);
        if (history.getType().equals(ExecuteType.GREMLIN_ASYNC)) {
            try {
                Task task = client.task().get(history.getAsyncId());
                history.setDuration(task.updateTime() - task.createTime());
                history.setAsyncStatus(AsyncTaskStatus.valueOf(task.status().toUpperCase()));
            } catch (Exception e) {
                history.setDuration(0L);
                history.setAsyncStatus(AsyncTaskStatus.UNKNOWN);
            }

        }
        return history;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int save(ExecuteHistory history) {
        return this.mapper.insert(history);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int update(ExecuteHistory history) {
        return this.mapper.updateById(history);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int remove(int connId, int id) {
        ExecuteHistory history = this.mapper.selectById(id);
        HugeClient client = this.getClient(connId);
        if (history.getType().equals(ExecuteType.GREMLIN_ASYNC)) {
            client.task().delete(history.getAsyncId());
        }
        return this.mapper.deleteById(id);
    }

    @Async
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000)
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void removeExceedLimit() {
        int limit = this.config.get(HubbleOptions.EXECUTE_HISTORY_SHOW_LIMIT);
        this.mapper.deleteExceedLimit(limit);
    }
}
