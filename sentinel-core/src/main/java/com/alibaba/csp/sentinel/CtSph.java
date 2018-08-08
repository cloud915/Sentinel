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
package com.alibaba.csp.sentinel;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.alibaba.csp.sentinel.log.RecordLog;
import com.alibaba.csp.sentinel.context.Context;
import com.alibaba.csp.sentinel.context.ContextUtil;
import com.alibaba.csp.sentinel.context.NullContext;
import com.alibaba.csp.sentinel.node.Node;
import com.alibaba.csp.sentinel.slotchain.MethodResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlot;
import com.alibaba.csp.sentinel.slotchain.ProcessorSlotChain;
import com.alibaba.csp.sentinel.slotchain.ResourceWrapper;
import com.alibaba.csp.sentinel.slotchain.StringResourceWrapper;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.Rule;

/**
 * {@inheritDoc}
 *
 * @author jialiang.linjl
 * @author leyou(lihao)
 * @author Eric Zhao
 * @see Sph
 */
public class CtSph implements Sph {

    private static final Object[] OBJECTS0 = new Object[0];

    /**
     * Same resource({@link ResourceWrapper#equals(Object)}) will share the same
     * {@link ProcessorSlotChain}, no matter in which {@link Context}.
     */
    private static Map<ResourceWrapper, ProcessorSlotChain> chainMap
        = new HashMap<ResourceWrapper, ProcessorSlotChain>();

    private static final Object LOCK = new Object();

    /**
     * Do all {@link Rule}s checking about the resource.
     * 针对资源，执行所有规则检查
     *
     * <p>Each distinct resource will use a {@link ProcessorSlot} to do rules checking. Same resource will use
     * same {@link ProcessorSlot} globally. </p>
     * 针对不同资源，使用 资源插槽 来进行规则检查。 相同资源则使用全局相同的 资源插槽检查
     *
     * <p>Note that total {@link ProcessorSlot} count must not exceed {@link Constants#MAX_SLOT_CHAIN_SIZE},
     * otherwise no rules checking will do. In this condition, all requests will pass directly, with no checking
     * or exception.</p>
     * 注意，资源插槽数量不能超过 MAX_SLOT_CHAIN_SIZE的值，否则将没有规则可检查。检查过程中如果没有异常或不被检查，则请求允许通过。
     *
     * @param resourceWrapper resource name 资源名（接口名、方法名）
     * @param count           tokens needed 需要的令牌数量
     * @param args            arguments of user method call 请求方法的参数列表
     * @return {@link Entry} represents this call
     * @throws BlockException if any rule's threshold is exceeded
     */
    public Entry entry(ResourceWrapper resourceWrapper, int count, Object... args) throws BlockException {
        // 获取上下文信息
        Context context = ContextUtil.getContext();
        if (context instanceof NullContext) {
            // Init the entry only. No rule checking will occur.
            // 之前没有创建上下文，此时直接允许请求通过
            return new CtEntry(resourceWrapper, null, context);
        }

        if (context == null) {
            // 创建一个默认数据的上下文
            context = MyContextUtil.myEnter(Constants.CONTEXT_DEFAULT_NAME, "", resourceWrapper.getType());
        }

        // Global switch is close, no rule checking will do.
        // 全局配置关闭，则无规则校验
        if (!Constants.ON) {
            return new CtEntry(resourceWrapper, null, context);
        }

        ProcessorSlot<Object> chain = lookProcessChain(resourceWrapper);

        /*
         * Means processor size exceeds {@link Constants.MAX_ENTRY_SIZE}, no
         * rule checking will do.
         */
        if (chain == null) {
            return new CtEntry(resourceWrapper, null, context);
        }

        Entry e = new CtEntry(resourceWrapper, chain, context);
        try {
            // 获取令牌
            chain.entry(context, resourceWrapper, null, count, args);
        } catch (BlockException e1) {
            e.exit(count, args);
            throw e1;
        } catch (Throwable e1) {
            RecordLog.info("sentinel unexpected exception", e1);
        }
        return e;
    }

    /**
     * Get {@link ProcessorSlotChain} of the resource. new {@link ProcessorSlotChain} will
     * be created if the resource doesn't relate one.
     * 从资源中 获取 “处理器插槽链” ProcessorSlotChain。如果不存在则创建新的ProcessorSlotChain
     *
     * <p>Same resource({@link ResourceWrapper#equals(Object)}) will share the same
     * {@link ProcessorSlotChain} globally, no matter in witch {@link Context}.<p/>
     * 相同资源将共享相同的处理器插槽链，无论在哪个上下文中。判断相同以ResourceWrapper#equals(Object)方法为准。
     *
     * <p>
     * Note that total {@link ProcessorSlot} count must not exceed {@link Constants#MAX_SLOT_CHAIN_SIZE},
     * otherwise null will return.
     * 请注意，“处理器插槽”ProcessorSlot 的总和 不能超过 MAX_SLOT_CHAIN_SIZE，否则返回null
     * </p>
     *
     * @param resourceWrapper target resource
     * @return {@link ProcessorSlotChain} of the resource
     */
    private ProcessorSlot<Object> lookProcessChain(ResourceWrapper resourceWrapper) {
        // 从map中获取 ProcessorSlotChain，如果没有则创建要给新的并加入map
        ProcessorSlotChain chain = chainMap.get(resourceWrapper);
        if (chain == null) {
            synchronized (LOCK) {
                chain = chainMap.get(resourceWrapper);
                if (chain == null) {
                    // Entry size limit.
                    if (chainMap.size() >= Constants.MAX_SLOT_CHAIN_SIZE) {
                        return null;
                    }

                    chain = Env.slotsChainbuilder.build();
                    HashMap<ResourceWrapper, ProcessorSlotChain> newMap
                        = new HashMap<ResourceWrapper, ProcessorSlotChain>(
                        chainMap.size() + 1);
                    newMap.putAll(chainMap);
                    newMap.put(resourceWrapper, chain);
                    chainMap = newMap;
                }
            }
        }
        return chain;
    }

    private static class CtEntry extends Entry {

        protected Entry parent = null;
        protected Entry child = null;
        private ProcessorSlot<Object> chain;
        private Context context;

        CtEntry(ResourceWrapper resourceWrapper, ProcessorSlot<Object> chain, Context context) {
            super(resourceWrapper);
            this.chain = chain;
            this.context = context;
            parent = context.getCurEntry();
            if (parent != null) {
                ((CtEntry)parent).child = this;
            }
            context.setCurEntry(this);
        }

        @Override
        public void exit(int count, Object... args) throws ErrorEntryFreeException {
            trueExit(count, args);
        }

        @Override
        protected Entry trueExit(int count, Object... args) throws ErrorEntryFreeException {
            if (context != null) {
                if (context.getCurEntry() != this) {
                    // Clean previous call stack.
                    CtEntry e = (CtEntry)context.getCurEntry();
                    while (e != null) {
                        e.exit(count, args);
                        e = (CtEntry)e.parent;
                    }
                    throw new ErrorEntryFreeException(
                        "The order of entry free is can't be paired with the order of entry");
                } else {
                    if (chain != null) {
                        chain.exit(context, resourceWrapper, count, args);
                    }
                    // Modify the call stack.
                    context.setCurEntry(parent);
                    if (parent != null) {
                        ((CtEntry)parent).child = null;
                    }
                    if (parent == null) {
                        // Auto-created entry indicates immediate exit.
                        ContextUtil.exit();
                    }
                    // Clean the reference of context in current entry to avoid duplicate exit.
                    context = null;
                }
            }
            return parent;

        }

        @Override
        public Node getLastNode() {
            return parent == null ? null : parent.getCurNode();
        }
    }

    /**
     * This class is used for skip context name checking.
     */
    private final static class MyContextUtil extends ContextUtil {
        static Context myEnter(String name, String origin, EntryType type) {
            return trueEnter(name, origin);
        }
    }

    @Override
    public Entry entry(String name) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(String name, EntryType type) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, 1, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type, int count) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(String name, EntryType type, int count) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, int count) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, EntryType.OUT);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(String name, int count) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, EntryType.OUT);
        return entry(resource, count, OBJECTS0);
    }

    @Override
    public Entry entry(Method method, EntryType type, int count, Object... args) throws BlockException {
        MethodResourceWrapper resource = new MethodResourceWrapper(method, type);
        return entry(resource, count, args);
    }


    @Override
    public Entry entry(String name, EntryType type, int count, Object... args) throws BlockException {
        StringResourceWrapper resource = new StringResourceWrapper(name, type);
        return entry(resource, count, args);
    }
}
