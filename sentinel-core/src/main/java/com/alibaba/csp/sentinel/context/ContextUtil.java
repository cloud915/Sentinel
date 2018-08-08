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
package com.alibaba.csp.sentinel.context;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

import com.alibaba.csp.sentinel.Constants;
import com.alibaba.csp.sentinel.EntryType;
import com.alibaba.csp.sentinel.SphO;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.node.DefaultNode;
import com.alibaba.csp.sentinel.node.EntranceNode;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.slots.nodeselector.NodeSelectorSlot;

/**
 * Utility class to get or create {@link Context} in current thread.
 *
 * <p>
 * Each {@link SphU}#entry() or {@link SphO}#entry() should be in a {@link Context}.
 * If we don't invoke {@link ContextUtil}#enter() explicitly, DEFAULT context will be used.
 * </p>
 *
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 */
public class ContextUtil {

    /**
     * Store the context in ThreadLocal for easy access.
     */
    private static ThreadLocal<Context> contextHolder = new ThreadLocal<Context>();

    /**
     * Holds all {@link EntranceNode}
     */
    private static volatile Map<String, DefaultNode> contextNameNodeMap = new HashMap<String, DefaultNode>();

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Context NULL_CONTEXT = new NullContext();

    /**
     * <p>
     * Enter the invocation context. The context is ThreadLocal, meaning that
     * each thread has it's own {@link Context}. New context will be created if
     * current thread doesn't have one.
     * 进入调用上下文。这个上下文是线程本地变量，也就是每个线程独有的。如果当前线程不存在上下文，则创建一个新的上下文
     * </p>
     * <p>
     * A context will be related to a {@link EntranceNode}, which represents the entrance
     * of the invocation tree. New {@link EntranceNode} will be created if
     * current context does't have one. Note that same context name will share
     * same {@link EntranceNode} globally.
     * 一个上下问会和一个入口节点有关，它代表了调用树的入口。如果上下问不存在则创建一个入口节点。注意，相同上下文明村将在全局共享入口节点
     * </p>
     * <p>
     * Note that each distinct {@code origin} of {@code name} will lead to creating a new
     * {@link Node}, meaning that total {@link Node} created will be of:<br/>
     * {@code distinct context name count * distinct origin count} <br/>
     * So when origin is too many, memory efficiency should be carefully considered.
     * 请注意，每个名称不同的源，将创建一个新节点。也就是说，所创建的节点总和= 不同名称上下文数量*不同源的数量
     * 因此，当源点太多时，应该仔细考虑内存效率
     * </p>
     * <p>
     * Same resource in different context will count separately, see {@link NodeSelectorSlot}.
     * 在不同的上下文中，相同的资源将当度计算，参见NodeSelectorSlot
     * </p>
     *
     * @param name   the context name.
     * @param origin the origin of this invocation, usually the origin could be the Service
     *               Consumer's app name. The origin is useful when we want to control different
     *               invoker/consumer separately.
     * @return The invocation context of the current thread.
     */
    static public Context enter(String name, String origin) {
        if (Constants.CONTEXT_DEFAULT_NAME.equals(name)) {
            throw new ContextNameDefineException(
                "The " + Constants.CONTEXT_DEFAULT_NAME + " can't be permit to defined!");
        }
        return trueEnter(name, origin);
    }

    protected static Context trueEnter(String name, String origin) {
        Context context = contextHolder.get();
        if (context == null) {
            // 获取本地缓存map
            Map<String, DefaultNode> localCacheNameMap = contextNameNodeMap;
            // 根据名称，获取默认节点信息（也就是注释中提到的node）
            DefaultNode node = localCacheNameMap.get(name);
            if (node == null) {
                if (localCacheNameMap.size() > Constants.MAX_CONTEXT_NAME_SIZE) {
                    return NULL_CONTEXT;
                } else {
                    try {
                        // 加锁创建node
                        LOCK.lock();
                        node = contextNameNodeMap.get(name);
                        if (node == null) {
                            if (contextNameNodeMap.size() > Constants.MAX_CONTEXT_NAME_SIZE) {
                                return NULL_CONTEXT;
                            } else {
                                // 新建一个入口节点，其中资源信息包含 应用名称、入口类型(IN/OUT)
                                node = new EntranceNode(new StringResourceWrapper(name, EntryType.IN), null);
                                // Add entrance node. //
                                Constants.ROOT.addChild(node);

                                // 新创建的节点，加入本地缓存map
                                Map<String, DefaultNode> newMap = new HashMap<String, DefaultNode>(
                                    contextNameNodeMap.size() + 1);
                                newMap.putAll(contextNameNodeMap);
                                newMap.put(name, node);
                                contextNameNodeMap = newMap;
                            }
                        }
                    } finally {
                        LOCK.unlock();
                    }
                }
            }
            context = new Context(node, name);
            context.setOrigin(origin);
            contextHolder.set(context);
        }

        return context;
    }

    /**
     * <p>
     * Enter the invocation context. The context is ThreadLocal, meaning that
     * each thread has it's own {@link Context}. New context will be created if
     * current thread doesn't have one.
     * </p>
     * <p>
     * A context will related to A {@link EntranceNode}, which is the entrance
     * of the invocation tree. New {@link EntranceNode} will be created if
     * current context does't have one. Note that same resource name will share
     * same {@link EntranceNode} globally.
     * </p>
     * <p>
     * Same resource in different context will count separately, see {@link NodeSelectorSlot}.
     * </p>
     *
     * @param name the context name.
     * @return The invocation context of the current thread.
     */
    public static Context enter(String name) {
        return enter(name, "");
    }

    /**
     * Exit context of current thread, that is removing {@link Context} in the
     * ThreadLocal.
     */
    public static void exit() {
        Context context = contextHolder.get();
        if (context != null && context.getCurEntry() == null) {
            contextHolder.set(null);
        }
    }

    /**
     * Get {@link Context} of current thread.
     *
     * @return context of current thread. Null value will be return if current
     * thread does't have context.
     */
    public static Context getContext() {
        return contextHolder.get();
    }
}
