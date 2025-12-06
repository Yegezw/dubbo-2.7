/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.common.extension;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.factory.AdaptiveExtensionFactory;
import org.apache.dubbo.common.extension.factory.SpiExtensionFactory;
import org.apache.dubbo.common.extension.support.ActivateComparator;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import static org.apache.dubbo.common.constants.CommonConstants.*;

/**
 * Load dubbo extensions
 * <ul>
 * <li>auto inject dependency extension </li>
 * <li>auto wrap extension in wrapper </li>
 * <li>default extension is an adaptive instance</li>
 * </ul>
 *
 * @see <a href="http://java.sun.com/j2se/1.5.0/docs/guide/jar/jar.html#Service%20Provider">Service Provider in Java 5</a>
 * @see org.apache.dubbo.common.extension.SPI
 * @see org.apache.dubbo.common.extension.Adaptive
 * @see org.apache.dubbo.common.extension.Activate
 */
public class ExtensionLoader<T> {

    private static final Logger logger = LoggerFactory.getLogger(ExtensionLoader.class);

    /**
     * Java SPI
     */
    private static final String SERVICES_DIRECTORY = "META-INF/services/";
    /**
     * Dubbo SPI
     */
    private static final String DUBBO_DIRECTORY = "META-INF/dubbo/";
    /**
     * Dubbo 内部扩展点加载目录
     */
    private static final String DUBBO_INTERNAL_DIRECTORY = DUBBO_DIRECTORY + "internal/";

    private static final Pattern NAME_SEPARATOR = Pattern.compile("\\s*[,]+\\s*");

    /*
     *     org.apache.dubbo.rpc.Protocol                          接口 - 扩展点的接口
     *     org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol      实现 - 扩展点的 Class
     * new org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol()    对象 - 扩展点实例化的对象
     */

    /**
     * 保存所有的 ExtensionLoader 对象, key = 扩展点的接口, value = ExtensionLoader 对象
     */
    private static final ConcurrentMap<Class<?>, ExtensionLoader<?>> EXTENSION_LOADERS = new ConcurrentHashMap<>();
    /**
     * 扩展点实例容器, 保存了所有实例化后的扩展点对象, key = 扩展点的 Class, value = 扩展点实例化的对象
     */
    private static final ConcurrentMap<Class<?>, Object> EXTENSION_INSTANCES = new ConcurrentHashMap<>();

    /**
     * Dubbo SPI 要求扩展点类型必须是接口, 且标注 @SPI 注解
     */
    private final Class<?> type;
    /**
     * 扩展点对象工厂, 用于依赖注入时, 获取依赖的扩展点对象
     * <p>
     * {@code ExtensionLoader<ExtensionFactory>} 的 objectFactory 为 null<br>
     * 其它所有 {@code ExtensionLoader<T>} 的 objectFactory 都是 {@link AdaptiveExtensionFactory}
     * <p>
     * {@link AdaptiveExtensionFactory} 里面是对 ExtensionFactory 的包装<br>
     * 包含了 {@link SpiExtensionFactory} 和 {@link org.apache.dubbo.config.spring.extension.SpringExtensionFactory}
     * <p>
     * {@link AdaptiveExtensionFactory} 也可以说是获取扩展点的一层代理<br>
     * 底层都是从 {@link SpiExtensionFactory} 和 {@link org.apache.dubbo.config.spring.extension.SpringExtensionFactory} 获取扩展点
     */
    private final ExtensionFactory objectFactory;

    /**
     * key = 扩展点的接口, value = 扩展点定义 key, 可以通过扩展点 Class 获取 key<br>
     * "class org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol" -> dubbo
     */
    private final ConcurrentMap<Class<?>, String> cachedNames = new ConcurrentHashMap<>();
    /**
     * key = 扩展点定义 key, value = 扩展点的接口, 可以通过 key 获取扩展点 Class<br>
     * dubbo -> "class org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol"
     */
    private final Holder<Map<String, Class<?>>> cachedClasses = new Holder<>();
    /**
     * 激活的扩展点容器, key = 扩展点定义 key, value = @Activate 对象<br>
     * token -> {$Proxy7@2886} "@org.apache.dubbo.common.extension.Activate(after=[], value=[token], before=[], group=[provider], order=0)"
     */
    private final Map<String, Object> cachedActivates = new ConcurrentHashMap<>();

    /**
     * 扩展点对象 Holder 的容器, 用于保存已经实例化的扩展点对象 Holder<br>
     * dubbo -> {Holder[Object]}, Object = org.apache.dubbo.rpc.protocol.dubbo.DubboProtocol 对象
     */
    private final ConcurrentMap<String, Holder<Object>> cachedInstances = new ConcurrentHashMap<>();
    /**
     * 自适应扩展点对象持有者, 持有 Dubbo 生成的自适应扩展点对象
     */
    private final Holder<Object> cachedAdaptiveInstance = new Holder<>();
    /**
     * 自适应扩展点 Class 持有者, 持有 Dubbo 生成的自适应扩展点类
     */
    private volatile Class<?> cachedAdaptiveClass = null;
    /**
     * 默认扩展点 key, @SPI 注解里的 value
     */
    private String cachedDefaultName;
    private volatile Throwable createAdaptiveInstanceError;

    private Set<Class<?>> cachedWrapperClasses;

    private Map<String, IllegalStateException> exceptions = new ConcurrentHashMap<>();

    private ExtensionLoader(Class<?> type) {
        this.type = type; // 初始化扩展点类型
        objectFactory = (type == ExtensionFactory.class ? null : ExtensionLoader.getExtensionLoader(ExtensionFactory.class).getAdaptiveExtension());
    }

    private static <T> boolean withExtensionAnnotation(Class<T> type) {
        return type.isAnnotationPresent(SPI.class);
    }

    @SuppressWarnings("unchecked")
    public static <T> ExtensionLoader<T> getExtensionLoader(Class<T> type) {
        // 验证扩展点类型不能为空
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        // 验证扩展点类型必须是接口
        if (!type.isInterface()) {
            throw new IllegalArgumentException("Extension type (" + type + ") is not an interface!");
        }
        // 验证扩展点接口必须添加 @SPI 注解
        if (!withExtensionAnnotation(type)) {
            throw new IllegalArgumentException("Extension type (" + type +
                    ") is not an extension, because it is NOT annotated with @" + SPI.class.getSimpleName() + "!");
        }

        ExtensionLoader<T> loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        if (loader == null) {
            // 实例化 ExtensionLoader 并保存到 EXTENSION_LOADERS
            EXTENSION_LOADERS.putIfAbsent(type, new ExtensionLoader<T>(type));
            loader = (ExtensionLoader<T>) EXTENSION_LOADERS.get(type);
        }
        return loader; // 返回 ExtensionLoader 对象
    }

    // For testing purposes only
    public static void resetExtensionLoader(Class type) {
        ExtensionLoader loader = EXTENSION_LOADERS.get(type);
        if (loader != null) {
            // Remove all instances associated with this loader as well
            Map<String, Class<?>> classes = loader.getExtensionClasses();
            for (Map.Entry<String, Class<?>> entry : classes.entrySet()) {
                EXTENSION_INSTANCES.remove(entry.getValue());
            }
            classes.clear();
            EXTENSION_LOADERS.remove(type);
        }
    }

    private static ClassLoader findClassLoader() {
        return ClassUtils.getClassLoader(ExtensionLoader.class);
    }

    public String getExtensionName(T extensionInstance) {
        return getExtensionName(extensionInstance.getClass());
    }

    public String getExtensionName(Class<?> extensionClass) {
        getExtensionClasses();// load class
        return cachedNames.get(extensionClass);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, key, null)}
     *
     * @param url url
     * @param key url parameter key which used to get extension point names
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String, String)
     */
    public List<T> getActivateExtension(URL url, String key) {
        return getActivateExtension(url, key, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, values, null)}
     *
     * @param url    url
     * @param values extension point names
     * @return extension list which are activated
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String[] values) {
        return getActivateExtension(url, values, null);
    }

    /**
     * This is equivalent to {@code getActivateExtension(url, url.getParameter(key).split(","), null)}
     *
     * @param url   url
     * @param key   url parameter key which used to get extension point names
     * @param group group
     * @return extension list which are activated.
     * @see #getActivateExtension(org.apache.dubbo.common.URL, String[], String)
     */
    public List<T> getActivateExtension(URL url, String key, String group) {
        String value = url.getParameter(key); // 从 URL 参数里获取指定的扩展点
        // 获取激活扩展点的核心方法
        return getActivateExtension(url, StringUtils.isEmpty(value) ? null : COMMA_SPLIT_PATTERN.split(value), group);
    }

    /**
     * Get activate extensions.
     *
     * @param url    url
     * @param values extension point names
     * @param group  group
     * @return extension list which are activated
     * @see org.apache.dubbo.common.extension.Activate
     */
    public List<T> getActivateExtension(URL url, String[] values, String group) {
        List<T> exts = new ArrayList<>(); // 最终返回的结果
        // 运行期间 URL 参数中指定的扩展点
        List<String> names = values == null ? new ArrayList<>(0) : Arrays.asList(values);
        // 处理默认的激活扩展点
        if (!names.contains(REMOVE_VALUE_PREFIX + DEFAULT_KEY)) {
            getExtensionClasses();
            // 遍历所有激活扩展点, 筛选符合条件的激活扩展点
            for (Map.Entry<String, Object> entry : cachedActivates.entrySet()) {
                String name = entry.getKey();
                Object activate = entry.getValue();

                String[] activateGroup, activateValue;

                // 只有标记了 @Activate 才符合基本筛选条件
                if (activate instanceof Activate) {
                    activateGroup = ((Activate) activate).group();
                    activateValue = ((Activate) activate).value();
                } else if (activate instanceof com.alibaba.dubbo.common.extension.Activate) {
                    activateGroup = ((com.alibaba.dubbo.common.extension.Activate) activate).group();
                    activateValue = ((com.alibaba.dubbo.common.extension.Activate) activate).value();
                } else {
                    continue;
                }
                // 继续筛选符合条件的激活扩展点
                // 1. 匹配组 group
                // 2. URL 参数中不包含
                // 3. 不包含移除的配置
                // 4. @Activate 没有配置 value, 或者 URL 中的参数包含 @Activate 配置的 value
                if (isMatchGroup(group, activateGroup)
                        && !names.contains(name)
                        && !names.contains(REMOVE_VALUE_PREFIX + name)
                        && isActive(activateValue, url)) {
                    exts.add(getExtension(name));
                }
            }
            exts.sort(ActivateComparator.COMPARATOR);
        }
        // 将 URL 中指定的扩展点也加入到返回结果里
        List<T> usrs = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            // 不包含移除配置
            if (!name.startsWith(REMOVE_VALUE_PREFIX)
                    && !names.contains(REMOVE_VALUE_PREFIX + name)) {
                if (DEFAULT_KEY.equals(name)) {
                    if (!usrs.isEmpty()) {
                        exts.addAll(0, usrs);
                        usrs.clear();
                    }
                } else {
                    usrs.add(getExtension(name));
                }
            }
        }
        if (!usrs.isEmpty()) {
            exts.addAll(usrs);
        }
        return exts;
    }

    private boolean isMatchGroup(String group, String[] groups) {
        if (StringUtils.isEmpty(group)) {
            return true;
        }
        if (groups != null && groups.length > 0) {
            for (String g : groups) {
                if (group.equals(g)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isActive(String[] keys, URL url) {
        if (keys.length == 0) {
            return true;
        }
        for (String key : keys) {
            for (Map.Entry<String, String> entry : url.getParameters().entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if ((k.equals(key) || k.endsWith("." + key))
                        && ConfigUtils.isNotEmpty(v)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Get extension's instance. Return <code>null</code> if extension is not found or is not initialized. Pls. note
     * that this method will not trigger extension load.
     * <p>
     * In order to trigger extension load, call {@link #getExtension(String)} instead.
     *
     * @see #getExtension(String)
     */
    @SuppressWarnings("unchecked")
    public T getLoadedExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Holder<Object> holder = getOrCreateHolder(name);
        return (T) holder.get();
    }

    private Holder<Object> getOrCreateHolder(String name) {
        Holder<Object> holder = cachedInstances.get(name);
        if (holder == null) {
            cachedInstances.putIfAbsent(name, new Holder<>());
            holder = cachedInstances.get(name);
        }
        return holder;
    }

    /**
     * Return the list of extensions which are already loaded.
     * <p>
     * Usually {@link #getSupportedExtensions()} should be called in order to get all extensions.
     *
     * @see #getSupportedExtensions()
     */
    public Set<String> getLoadedExtensions() {
        return Collections.unmodifiableSet(new TreeSet<>(cachedInstances.keySet()));
    }

    public Object getLoadedAdaptiveExtensionInstances() {
        return cachedAdaptiveInstance.get();
    }

    /**
     * Find the extension with the given name. If the specified name is not found, then {@link IllegalStateException}
     * will be thrown.
     */
    @SuppressWarnings("unchecked")
    public T getExtension(String name) {
        // 扩展点名不能为空
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        // 获取默认扩展点
        if ("true".equals(name)) {
            return getDefaultExtension();
        }
        // 从 cachedInstances 中获取扩展点 Holder, 如果没有就创建一个新的 Holder
        final Holder<Object> holder = getOrCreateHolder(name);
        Object instance = holder.get();
        // 单例 (双重检查)
        if (instance == null) {
            synchronized (holder) {
                instance = holder.get();
                if (instance == null) {
                    instance = createExtension(name); // 创建扩展点
                    holder.set(instance);
                }
            }
        }
        return (T) instance;
    }

    /**
     * Return default extension, return <code>null</code> if it's not configured.
     */
    public T getDefaultExtension() {
        getExtensionClasses();
        if (StringUtils.isBlank(cachedDefaultName) || "true".equals(cachedDefaultName)) {
            return null;
        }
        return getExtension(cachedDefaultName);
    }

    public boolean hasExtension(String name) {
        if (StringUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Extension name == null");
        }
        Class<?> c = this.getExtensionClass(name);
        return c != null;
    }

    public Set<String> getSupportedExtensions() {
        Map<String, Class<?>> clazzes = getExtensionClasses();
        return Collections.unmodifiableSet(new TreeSet<>(clazzes.keySet()));
    }

    /**
     * Return default extension name, return <code>null</code> if not configured.
     */
    public String getDefaultExtensionName() {
        getExtensionClasses();
        return cachedDefaultName;
    }

    /**
     * Register new extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension with the same name has already been registered.
     */
    public void addExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement the Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " already exists (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
        } else {
            if (cachedAdaptiveClass != null) {
                throw new IllegalStateException("Adaptive Extension already exists (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
        }
    }

    /**
     * Replace the existing extension via API
     *
     * @param name  extension name
     * @param clazz extension class
     * @throws IllegalStateException when extension to be placed doesn't exist
     * @deprecated not recommended any longer, and use only when test
     */
    @Deprecated
    public void replaceExtension(String name, Class<?> clazz) {
        getExtensionClasses(); // load classes

        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Input type " +
                    clazz + " doesn't implement Extension " + type);
        }
        if (clazz.isInterface()) {
            throw new IllegalStateException("Input type " +
                    clazz + " can't be interface!");
        }

        if (!clazz.isAnnotationPresent(Adaptive.class)) {
            if (StringUtils.isBlank(name)) {
                throw new IllegalStateException("Extension name is blank (Extension " + type + ")!");
            }
            if (!cachedClasses.get().containsKey(name)) {
                throw new IllegalStateException("Extension name " +
                        name + " doesn't exist (Extension " + type + ")!");
            }

            cachedNames.put(clazz, name);
            cachedClasses.get().put(name, clazz);
            cachedInstances.remove(name);
        } else {
            if (cachedAdaptiveClass == null) {
                throw new IllegalStateException("Adaptive Extension doesn't exist (Extension " + type + ")!");
            }

            cachedAdaptiveClass = clazz;
            cachedAdaptiveInstance.set(null);
        }
    }

    @SuppressWarnings("unchecked")
    public T getAdaptiveExtension() {
        Object instance = cachedAdaptiveInstance.get();
        if (instance == null) {
            if (createAdaptiveInstanceError != null) {
                throw new IllegalStateException("Failed to create adaptive instance: " +
                        createAdaptiveInstanceError.toString(),
                        createAdaptiveInstanceError);
            }

            synchronized (cachedAdaptiveInstance) {
                instance = cachedAdaptiveInstance.get();
                if (instance == null) {
                    try {
                        // 创建自适应扩展点对象
                        // 这里分为两种情况: 一种是存在 Adaptive 类、另一个是需要生成 Adaptive 类
                        instance = createAdaptiveExtension();
                        cachedAdaptiveInstance.set(instance);
                    } catch (Throwable t) {
                        createAdaptiveInstanceError = t;
                        throw new IllegalStateException("Failed to create adaptive instance: " + t.toString(), t);
                    }
                }
            }
        }

        return (T) instance;
    }

    private IllegalStateException findException(String name) {
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (entry.getKey().toLowerCase().contains(name.toLowerCase())) {
                return entry.getValue();
            }
        }
        StringBuilder buf = new StringBuilder("No such extension " + type.getName() + " by name " + name);


        int i = 1;
        for (Map.Entry<String, IllegalStateException> entry : exceptions.entrySet()) {
            if (i == 1) {
                buf.append(", possible causes: ");
            }

            buf.append("\r\n(");
            buf.append(i++);
            buf.append(") ");
            buf.append(entry.getKey());
            buf.append(":\r\n");
            buf.append(StringUtils.toString(entry.getValue()));
        }
        return new IllegalStateException(buf.toString());
    }

    @SuppressWarnings("unchecked")
    private T createExtension(String name) {
        // 获取扩展点 Class
        Class<?> clazz = getExtensionClasses().get(name);
        if (clazz == null) {
            throw findException(name);
        }
        try {
            T instance = (T) EXTENSION_INSTANCES.get(clazz);
            if (instance == null) {
                EXTENSION_INSTANCES.putIfAbsent(clazz, clazz.newInstance());
                instance = (T) EXTENSION_INSTANCES.get(clazz);
            }
            injectExtension(instance); // 依赖注入 (Dubbo IOC)
            // 如果包含包装类, 则返回包装类 (Dubbo AOP)
            // 类名为 XXXWrapper、且有一个 XXXWrapper(XXX) 的构造函数、实现 SPI XXX 接口, 如 ProtocolFilterWrapper
            Set<Class<?>> wrapperClasses = cachedWrapperClasses;
            if (CollectionUtils.isNotEmpty(wrapperClasses)) {
                for (Class<?> wrapperClass : wrapperClasses) {
                    instance = injectExtension((T) wrapperClass.getConstructor(type).newInstance(instance)); // 对包装类进行依赖注入
                }
            }
            return instance; // 返回扩展点
        } catch (Throwable t) {
            throw new IllegalStateException("Extension instance (name: " + name + ", class: " +
                    type + ") couldn't be instantiated: " + t.getMessage(), t);
        }
    }

    private T injectExtension(T instance) {
        // 如果 objectFactory 为空, 则不执行依赖注入
        // 除了 ExtensionFactory 外, 所有扩展点 ExtensionLoader 的 objectFactory 都为 AdaptiveExtensionFactory
        if (objectFactory == null) {
            return instance;
        }

        try {
            for (Method method : instance.getClass().getMethods()) {
                // 遍历扩展点所有方法, 只筛选 setter 方法, 且方法仅有一个参数 + 访问级别为 public
                if (!isSetter(method)) {
                    continue;
                }
                /**
                 * Check {@link DisableInject} to see if we need auto injection for this property
                 * 检测是否有 @DisableInject 注解修饰
                 */
                if (method.getAnnotation(DisableInject.class) != null) {
                    continue;
                }
                // 基本类型不注入
                Class<?> pt = method.getParameterTypes()[0];
                if (ReflectUtils.isPrimitives(pt)) {
                    continue;
                }

                try {
                    // 1. 通过 objectFactory 获取依赖的扩展点
                    // 2. 通过反射, 调用 setter 方法, 将依赖的扩展点注入进去
                    String property = getSetterProperty(method);              // 获取属性名
                    Object object = objectFactory.getExtension(pt, property); // 从 ObjectFactory 中获取依赖对象
                    if (object != null) {
                        method.invoke(instance, object); // 注入
                    }
                } catch (Exception e) {
                    logger.error("Failed to inject via method " + method.getName()
                            + " of interface " + type.getName() + ": " + e.getMessage(), e);
                }

            }
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
        return instance;
    }

    /**
     * get properties name for setter, for instance: setVersion, return "version"
     * <p>
     * return "", if setter name with length less than 3
     */
    private String getSetterProperty(Method method) {
        return method.getName().length() > 3 ? method.getName().substring(3, 4).toLowerCase() + method.getName().substring(4) : "";
    }

    /**
     * return true if and only if:
     * <p>
     * 1, public
     * <p>
     * 2, name starts with "set"
     * <p>
     * 3, only has one parameter
     */
    private boolean isSetter(Method method) {
        return method.getName().startsWith("set")
                && method.getParameterTypes().length == 1
                && Modifier.isPublic(method.getModifiers());
    }

    private Class<?> getExtensionClass(String name) {
        if (type == null) {
            throw new IllegalArgumentException("Extension type == null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Extension name == null");
        }
        return getExtensionClasses().get(name);
    }

    private Map<String, Class<?>> getExtensionClasses() {
        Map<String, Class<?>> classes = cachedClasses.get();
        if (classes == null) {
            synchronized (cachedClasses) {
                classes = cachedClasses.get();
                if (classes == null) {
                    classes = loadExtensionClasses(); // 加载扩展点 Class
                    cachedClasses.set(classes);
                }
            }
        }
        return classes;
    }

    /**
     * synchronized in getExtensionClasses
     * */
    private Map<String, Class<?>> loadExtensionClasses() {
        // 获取默认扩展点名, 通过 @SPI 注解获取的
        cacheDefaultExtensionName();

        // 以下是从配置目录里加载扩展点配置, 并解析为 Map<扩展点名, 扩展点 Class>
        // 这里需要注意的是, 以下三个目录中的配置文件都会加载进去
        // 1. META-INF/services/
        // 2. META-INF/dubbo/
        // 3. META-INF/dubbo/internal/
        Map<String, Class<?>> extensionClasses = new HashMap<>();
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_INTERNAL_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, DUBBO_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName());
        loadDirectory(extensionClasses, SERVICES_DIRECTORY, type.getName().replace("org.apache", "com.alibaba"));
        return extensionClasses;
    }

    /**
     * extract and cache default extension name if exists
     */
    private void cacheDefaultExtensionName() {
        final SPI defaultAnnotation = type.getAnnotation(SPI.class);
        if (defaultAnnotation == null) {
            return;
        }

        String value = defaultAnnotation.value();
        if ((value = value.trim()).length() > 0) {
            String[] names = NAME_SEPARATOR.split(value);
            if (names.length > 1) {
                throw new IllegalStateException("More than 1 default extension name on extension " + type.getName()
                        + ": " + Arrays.toString(names));
            }
            if (names.length == 1) {
                cachedDefaultName = names[0];
            }
        }
    }

    private void loadDirectory(Map<String, Class<?>> extensionClasses, String dir, String type) {
        // 拼接文件名, 如 META-INF/dubbo/internal/org.apache.dubbo.rpc.Protocol
        String fileName = dir + type;
        try {
            // 初始化 ClassLoader
            // 获取所有配置文件路径, 这里注意可能存在多个 Jar 包, 所以可能存在多个 URL
            Enumeration<java.net.URL> urls;
            ClassLoader classLoader = findClassLoader();
            if (classLoader != null) {
                urls = classLoader.getResources(fileName);
            } else {
                urls = ClassLoader.getSystemResources(fileName);
            }
            // 遍历每个文件, 逐个解析
            if (urls != null) {
                while (urls.hasMoreElements()) {
                    java.net.URL resourceURL = urls.nextElement();
                    // 解析文件, 并加载扩展点 Class, 解析过程简单来说就是
                    // 1. 逐行读取文件
                    // 2. 解析出 key 和 Class
                    // 3. 加载 Class, 并保存到各个缓存容器里, 如 cachedAdaptiveClass、cachedWrapperClasses、cacheNames
                    loadResource(extensionClasses, classLoader, resourceURL);
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", description file: " + fileName + ").", t);
        }
    }

    private void loadResource(Map<String, Class<?>> extensionClasses, ClassLoader classLoader, java.net.URL resourceURL) {
        try {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(resourceURL.openStream(), StandardCharsets.UTF_8))) {
                // 按行读取配置内容
                String line;
                while ((line = reader.readLine()) != null) {
                    final int ci = line.indexOf('#');
                    if (ci >= 0) {
                        line = line.substring(0, ci); // 截取 # 之前的字符串, # 之后的内容为注释, 需要忽略
                    }
                    line = line.trim();
                    if (line.length() > 0) {
                        try {
                            String name = null;
                            int i = line.indexOf('='); // 以等于号 = 为界, 截取键与值
                            if (i > 0) {
                                name = line.substring(0, i).trim();
                                line = line.substring(i + 1).trim();
                            }
                            if (line.length() > 0) {
                                // 加载类并缓存
                                loadClass(extensionClasses, resourceURL, Class.forName(line, true, classLoader), name);
                            }
                        } catch (Throwable t) {
                            IllegalStateException e = new IllegalStateException("Failed to load extension class (interface: " + type + ", class line: " + line + ") in " + resourceURL + ", cause: " + t.getMessage(), t);
                            exceptions.put(line, e);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logger.error("Exception occurred when loading extension class (interface: " +
                    type + ", class file: " + resourceURL + ") in " + resourceURL, t);
        }
    }

    private void loadClass(Map<String, Class<?>> extensionClasses, java.net.URL resourceURL, Class<?> clazz, String name) throws NoSuchMethodException {
        if (!type.isAssignableFrom(clazz)) {
            throw new IllegalStateException("Error occurred when loading extension class (interface: " +
                    type + ", class line: " + clazz.getName() + "), class "
                    + clazz.getName() + " is not subtype of interface.");
        }
        // 检测目标类上是否有 @Adaptive 注解
        if (clazz.isAnnotationPresent(Adaptive.class)) {
            cacheAdaptiveClass(clazz);
        } else if (isWrapperClass(clazz)) {
            cacheWrapperClass(clazz); // 缓存包装类
        } else {
            // 进入到这里, 表明只是该类只是一个普通的扩展类
            // 检测 clazz 是否有默认的构造方法, 如果没有则抛出异常
            clazz.getConstructor();
            if (StringUtils.isEmpty(name)) {
                // 如果 name 为空, 则尝试从 Extension 注解中获取 name, 或使用小写的类名作为 name
                name = findAnnotationName(clazz);
                if (name.length() == 0) {
                    throw new IllegalStateException("No such extension name for the class " + clazz.getName() + " in the config " + resourceURL);
                }
            }

            String[] names = NAME_SEPARATOR.split(name);
            if (ArrayUtils.isNotEmpty(names)) {
                // 如果类上有 @Activate 注解, 则使用 names 数组的第一个元素作为键, 缓存 name 到 @Activate 注解对象的映射关系
                cacheActivateClass(clazz, names[0]);
                for (String n : names) {
                    // 缓存 Class 到 name 的映射关系
                    cacheName(clazz, n);
                    // 缓存 name 到 Class 的映射关系
                    // 如果存在同一个 name 对应多个实现类则抛出异常
                    saveInExtensionClass(extensionClasses, clazz, n);
                }
            }
        }
    }

    /**
     * cache name
     */
    private void cacheName(Class<?> clazz, String name) {
        if (!cachedNames.containsKey(clazz)) {
            cachedNames.put(clazz, name);
        }
    }

    /**
     * put clazz in extensionClasses
     */
    private void saveInExtensionClass(Map<String, Class<?>> extensionClasses, Class<?> clazz, String name) {
        Class<?> c = extensionClasses.get(name);
        if (c == null) {
            extensionClasses.put(name, clazz);
        } else if (c != clazz) {
            String duplicateMsg = "Duplicate extension " + type.getName() + " name " + name + " on " + c.getName() + " and " + clazz.getName();
            logger.error(duplicateMsg);
            throw new IllegalStateException(duplicateMsg);
        }
    }

    /**
     * cache Activate class which is annotated with <code>Activate</code>
     * <p>
     * for compatibility, also cache class with old alibaba Activate annotation
     */
    private void cacheActivateClass(Class<?> clazz, String name) {
        Activate activate = clazz.getAnnotation(Activate.class);
        if (activate != null) {
            cachedActivates.put(name, activate);
        } else {
            // support com.alibaba.dubbo.common.extension.Activate
            com.alibaba.dubbo.common.extension.Activate oldActivate = clazz.getAnnotation(com.alibaba.dubbo.common.extension.Activate.class);
            if (oldActivate != null) {
                cachedActivates.put(name, oldActivate);
            }
        }
    }

    /**
     * cache Adaptive class which is annotated with <code>Adaptive</code>
     */
    private void cacheAdaptiveClass(Class<?> clazz) {
        if (cachedAdaptiveClass == null) {
            cachedAdaptiveClass = clazz;
        } else if (!cachedAdaptiveClass.equals(clazz)) {
            throw new IllegalStateException("More than 1 adaptive class found: "
                    + cachedAdaptiveClass.getName()
                    + ", " + clazz.getName());
        }
    }

    /**
     * cache wrapper class
     * <p>
     * like: ProtocolFilterWrapper, ProtocolListenerWrapper
     */
    private void cacheWrapperClass(Class<?> clazz) {
        if (cachedWrapperClasses == null) {
            cachedWrapperClasses = new ConcurrentHashSet<>();
        }
        cachedWrapperClasses.add(clazz);
    }

    /**
     * test if clazz is a wrapper class
     * <p>
     * which has Constructor with given class type as its only argument
     */
    private boolean isWrapperClass(Class<?> clazz) {
        try {
            clazz.getConstructor(type);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private String findAnnotationName(Class<?> clazz) {
        org.apache.dubbo.common.Extension extension = clazz.getAnnotation(org.apache.dubbo.common.Extension.class);
        if (extension != null) {
            return extension.value();
        }

        String name = clazz.getSimpleName();
        if (name.endsWith(type.getSimpleName())) {
            name = name.substring(0, name.length() - type.getSimpleName().length());
        }
        return name.toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private T createAdaptiveExtension() {
        try {
            // Dubbo 中有两种类型的自适应拓展: 一种是手工编码的、一种是自动生成的
            // 手工编码的自适应拓展中可能存在着一些依赖, 而自动生成的 Adaptive 拓展则不会依赖其它类
            return injectExtension((T) getAdaptiveExtensionClass().newInstance()); // 依赖注入
        } catch (Exception e) {
            throw new IllegalStateException("Can't create adaptive extension " + type + ", cause: " + e.getMessage(), e);
        }
    }

    private Class<?> getAdaptiveExtensionClass() {
        /*
         * getExtensionClasses() 用于获取某个接口的所有实现类, 比如该方法可以获取 Protocol 接口的 DubboProtocol、HttpProtocol、InjvmProtocol 等实现类
         * 在获取实现类的过程中, 如果某个实现类被 @Adaptive 注解修饰了, 那么该类就会被赋值给 cachedAdaptiveClass 变量
         * 此时下面步骤中的第二步条件成立 (缓存不为空), 直接返回 cachedAdaptiveClass 即可
         * 如果所有的实现类均未被 Adaptive 注解修饰, 那么执行第三步逻辑, 创建自适应拓展类
         */
        getExtensionClasses();
        if (cachedAdaptiveClass != null) {
            return cachedAdaptiveClass;
        }
        return cachedAdaptiveClass = createAdaptiveExtensionClass(); // 创建自适应扩展点 Class
    }

    private Class<?> createAdaptiveExtensionClass() {
        // 构建自适应拓展代码, 里面通过拼接字符串实现
        String code = new AdaptiveClassCodeGenerator(type, cachedDefaultName).generate();
        ClassLoader classLoader = findClassLoader();
        // 获取编译器实现类
        org.apache.dubbo.common.compiler.Compiler compiler = ExtensionLoader.getExtensionLoader(org.apache.dubbo.common.compiler.Compiler.class).getAdaptiveExtension();
        // 将 code 代码编译为 Class 并返回
        return compiler.compile(code, classLoader);
    }

    @Override
    public String toString() {
        return this.getClass().getName() + "[" + type.getName() + "]";
    }

}
