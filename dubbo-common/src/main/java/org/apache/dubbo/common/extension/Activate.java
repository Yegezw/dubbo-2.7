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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/*
 * 可以使用 @Activate 来控制 SPI 扩展实现在什么场景下加载生效, 相比于任何场景下都生效, 能精确的控制扩展实现的生效条件会让实现变得更灵活
 * 1. group: 修饰的实现类是在 Provider 端被激活还是在 Consumer 端被激活
 * 2. value: 修饰的实现类只在 URL 参数中出现指定的 key 时才会被激活
 * 3. order: 用来确定扩展实现类的排序
 *
 * 不加 @Activate 注解, getActivateExtension() 时不会加载, 其它 getExtension() 方法仍可正常加载
 *
 * @Activate
 * 不加任何参数, 表示在 getActivateExtension() 时无条件自动返回
 *
 * @Activate(group="provider")
 * group 支持 consumer、provider 两个固定值, getActivateExtension() 调用加载扩展点时自动过滤
 * provider 表示在提供者端会被加载、consumer 表示在消费者端会被加载
 *
 * @Activate(value="cache")
 * URL 参数中有 cache 这个 key 时, 调用 getActivateExtension() 才会加载
 *
 * @Activate(value="cache:test")
 * URL 参数中有 cache 这个 key 并且值为 test 时, 调用 getActivateExtension() 才会加载
 *
 * 排序同样使用 @Activate 注解设置, order 值越小加载优先级越高, @Activate(order=100) > @Activate(order=200) 
 */

/**
 * Activate. This annotation is useful for automatically activate certain extensions with the given criteria,
 * for examples: <code>@Activate</code> can be used to load certain <code>Filter</code> extension when there are
 * multiple implementations.
 * <ol>
 * <li>{@link Activate#group()} specifies group criteria. Framework SPI defines the valid group values.
 * <li>{@link Activate#value()} specifies parameter key in {@link URL} criteria.
 * </ol>
 * SPI provider can call {@link ExtensionLoader#getActivateExtension(URL, String, String)} to find out all activated
 * extensions with the given criteria.
 *
 * @see SPI
 * @see URL
 * @see ExtensionLoader
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Activate {
    /**
     * Activate the current extension when one of the groups matches. The group passed into
     * {@link ExtensionLoader#getActivateExtension(URL, String, String)} will be used for matching.
     *
     * @return group names to match
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     */
    String[] group() default {};

    /**
     * Activate the current extension when the specified keys appear in the URL's parameters.
     * <p>
     * For example, given <code>@Activate("cache, validation")</code>, the current extension will be return only when
     * there's either <code>cache</code> or <code>validation</code> key appeared in the URL's parameters.
     * </p>
     *
     * @return URL parameter keys
     * @see ExtensionLoader#getActivateExtension(URL, String)
     * @see ExtensionLoader#getActivateExtension(URL, String, String)
     */
    String[] value() default {};

    /**
     * Relative ordering info, optional
     * Deprecated since 2.7.0
     *
     * @return extension list which should be put before the current one
     */
    @Deprecated
    String[] before() default {};

    /**
     * Relative ordering info, optional
     * Deprecated since 2.7.0
     *
     * @return extension list which should be put after the current one
     */
    @Deprecated
    String[] after() default {};

    /**
     * Absolute ordering info, optional
     *
     * @return absolute ordering info
     */
    int order() default 0;
}