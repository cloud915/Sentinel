/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.csp.sentinel.slots.block.flow.controller;

import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.Controller;

/**
 * @author jialiang.linjl
 */
public class DefaultController implements Controller {

    double count = 0;
    int grade = 0;

    public DefaultController(double count, int grade) {
        super();
        this.count = count;
        this.grade = grade;
    }

    @Override
    public boolean canPass(Node node, int acquireCount) {
        int curCount = avgUsedTokens(node);
        // 剩余令牌数量是否满足 申请的令牌数量
        if (curCount + acquireCount > count) {
            return false;
        }

        return true;
    }

    private int avgUsedTokens(Node node) {
        if (node == null) {
            return -1;
        }
        // 根据当前级别，使用 当前并发线程数 或 当前QPS 来作为平均令牌数量
        return grade == RuleConstant.FLOW_GRADE_THREAD ? node.curThreadNum() : (int)node.passQps();
    }

}
