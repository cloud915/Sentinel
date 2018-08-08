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
package com.alibaba.csp.sentinel.slots;

import com.alibaba.csp.sentinel.slotchain.DefaultProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slots.block.authority.AuthoritySlot;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeSlot;
import com.alibaba.csp.sentinel.slots.block.flow.FlowSlot;
import com.alibaba.csp.sentinel.slots.clusterbuilder.ClusterBuilderSlot;
import com.alibaba.csp.sentinel.slots.logger.LogSlot;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;
import com.alibaba.csp.sentinel.slots.statistic.StatisticSlot;
import com.alibaba.csp.sentinel.slots.system.SystemSlot;

/**
 * Helper class to create {@link ProcessorSlotChain}.
 *
 * @author qinan.qn
 * @author leyou
 */
public class DefaultSlotsChainBuilder implements SlotsChainBuilder {

    @Override
    public ProcessorSlotChain build() {
        ProcessorSlotChain chain = new DefaultProcessorSlotChain();
        // buildTreeNode(),初始化
        chain.addLast(new NodeSelectorSlot());
        // buildClusterNode(),初始化
        chain.addLast(new ClusterBuilderSlot());
        // 直接调用其他链上节点，如果发生异常则记录日志
        chain.addLast(new LogSlot());
        // 统计指标数据等静态信息
        chain.addLast(new StatisticSlot());
        // 系统指标数据校验，只针对流入的流量，qps、并发线程数、RT、load
        chain.addLast(new SystemSlot());
        // 白名单、黑名单 规则，内部entry方法就是校验这些
        chain.addLast(new AuthoritySlot());
        // 限流规则，内部entry有三种实现，不同限流模式
        chain.addLast(new FlowSlot());
        // 降级规则，当前request是否达到降级标准（熔断？）
        chain.addLast(new DegradeSlot());

        return chain;
    }

}
