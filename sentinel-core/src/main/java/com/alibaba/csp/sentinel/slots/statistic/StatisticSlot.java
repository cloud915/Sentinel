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
package com.alibaba.csp.sentinel.slots.statistic;

import com.alibaba.csp.sentinel.util.TimeUtil;
import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.node.ClusterNode;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.slotchain.AbstractLinkedProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;

/**
 * <p>
 * A processor slot that dedicates to real time statistics.
 * 一个用于实时统计的处理器槽。
 * When entering this slot, we need to separately count the following
 * information:
 * 当进入这个槽时，我们需要分别计算以下信息：
 * <ul>
 * <li>{@link ClusterNode}: total statistics of a cluster node of the resource id  </li>
 * 资源id起源节点的集群节点的总统计信息
 * <li> origin node: statistics of a cluster node from different callers/origins.</li>
 * 来自不同的调用/起源的集群节点的统计信息
 * <li> {@link DefaultNode}: statistics for specific resource name in the specific context.
 * 特定上下文中的特定资源名称的统计信息。
 * <li> Finally, the sum statistics of all entrances.</li>
 * 最后，所有入口的总和统计。
 * </ul>
 * </p>
 *
 * @author jialiang.linjl
 */
public class StatisticSlot extends AbstractLinkedProcessorSlot<DefaultNode> {

    @Override
    public void entry(Context context, ResourceWrapper resourceWrapper, DefaultNode node, int count, Object... args)
        throws Throwable {

        try {
            fireEntry(context, resourceWrapper, node, count, args);
            node.increaseThreadNum();// 增加线程数
            node.addPassRequest();// 增加通过请求数量

            // 递增 源节点数据
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseThreadNum();
                context.getCurEntry().getOriginNode().addPassRequest();
            }

            // 如果是IN类型的资源，递增ENTRY_NODE统计数据
            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseThreadNum();
                Constants.ENTRY_NODE.addPassRequest();
            }

        } catch (BlockException e) {
            context.getCurEntry().setError(e);

            // Add block count.
            node.increaseBlockedQps();
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseBlockedQps();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseBlockedQps();
            }

            throw e;
        } catch (Throwable e) {
            context.getCurEntry().setError(e);

            // Should not happen
            node.increaseExceptionQps();
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().increaseExceptionQps();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.increaseExceptionQps();
            }
            throw e;
        }
    }

    @Override
    public void exit(Context context, ResourceWrapper resourceWrapper, int count, Object... args) {
        DefaultNode node = (DefaultNode)context.getCurNode();

        if (context.getCurEntry().getError() == null) {
            long rt = TimeUtil.currentTimeMillis() - context.getCurEntry().getCreateTime();
            if (rt > Constants.TIME_DROP_VALVE) {
                rt = Constants.TIME_DROP_VALVE;
            }

            node.rt(rt);
            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().rt(rt);
            }

            node.decreaseThreadNum();

            if (context.getCurEntry().getOriginNode() != null) {
                context.getCurEntry().getOriginNode().decreaseThreadNum();
            }

            if (resourceWrapper.getType() == EntryType.IN) {
                Constants.ENTRY_NODE.rt(rt);
                Constants.ENTRY_NODE.decreaseThreadNum();
            }
        } else {
            // error may happen
            // node.rt(-2);
        }

        fireExit(context, resourceWrapper, count);
    }

}
