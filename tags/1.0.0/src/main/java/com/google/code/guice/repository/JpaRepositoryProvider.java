/**
 * Copyright (C) 2012 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.code.guice.repository;

import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.inject.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.support.RepositoryFactorySupport;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

/**
 * Creates an instance of JpaRepository. Bind all your repositories to this provider. Examples:
 * <pre>
 *  bind(UserRepository.class).toProvider(new JpaRepositoryProvider&lt;UserRepository&gt;());
 *  bind(AccountRepository.class).toProvider(new JpaRepositoryProvider&lt;AccountRepository&gt;());
 * </pre>
 * In case when custom implementation class is a SimpleXXXRepository subclass - it should be passed as a constructor
 * parameter.
 * <pre>
 *  bind(CustomerRepository.class).toProvider(new JpaRepositoryProvider&lt;CustomerRepository&gt;(CustomerRepositoryImpl.class));
 * </pre>
 * Note, that all Repositories is not thread-safe, because they works directly with EntityManager, which is also not
 * thread-safe. Here is a valid example for concurrent Repository usage:
 * <pre>
 *     &#64;Inject
 *     private Provider&lt;UserRepository&gt; userRepositoryProvider;
 *     ...
 *     // this method can be used concurrently
 *     public void threadSafeRepoUsageMethod(){
 *         UserRepository repo = userRepositoryProvider.get();
 *         repo.save(new User("login", "password"));
 *         repo.someOtherRepoMethod();
 *     }
 * </pre>
 *
 * @author Alexey Krylov AKA lexx
 */
public class JpaRepositoryProvider<R extends Repository> implements Provider<R> {

    /*===========================================[ STATIC VARIABLES ]=============*/

    private static final Logger logger = LoggerFactory.getLogger(JpaRepositoryProvider.class);

    /*===========================================[ INSTANCE VARIABLES ]=========*/

    private Class<R> repositoryClass;
    private Provider<EntityManagerFactory> entityManagerFactoryProvider;
    private ApplicationContext context;
    private Provider<EntityManager> entityManagerProvider;
    private Class domainClass;
    private Class customImplementationClass;

    /*===========================================[ CONSTRUCTORS ]===============*/

    /**
     * Constructor for auto-binding.
     *
     * @param repositoryClass           class of Repository.
     * @param customImplementationClass custom implementation class of Repository.
     *
     * @see ScanningJpaRepositoryModule
     */
    JpaRepositoryProvider(Class<R> repositoryClass, @Nullable Class customImplementationClass) {
        Assert.notNull(repositoryClass);
        this.repositoryClass = repositoryClass;
        this.customImplementationClass = customImplementationClass;
    }

    /**
     * @param customImplementationClass custom implementation class of Repository. Can't be null.
     */
    public JpaRepositoryProvider(Class customImplementationClass) {
        Assert.notNull(customImplementationClass);
        this.customImplementationClass = customImplementationClass;
    }

    /**
     * Default constructor.
     */
    public JpaRepositoryProvider() {
    }

    /*===========================================[ CLASS METHODS ]==============*/

    @Inject
    public void init(Injector injector,
                     Provider<EntityManagerFactory> entityManagerFactoryProvider,
                     Provider<EntityManager> entityManagerProvider,
                     CustomRepositoryImplementationResolver customRepositoryImplementationResolver,
                     DomainClassResolver domainClassResolver) {
        this.entityManagerFactoryProvider = entityManagerFactoryProvider;
        this.entityManagerProvider = entityManagerProvider;
        if (repositoryClass == null) {
            repositoryClass = extractRepositoryClass(injector);
        }
        domainClass = domainClassResolver.resolve(repositoryClass);
        context = createSpringContext();

        if (customImplementationClass == null) {
            customImplementationClass = customRepositoryImplementationResolver.resolve(repositoryClass);
        }

        if (customImplementationClass != null) {
            logger.info(String.format("Custom repository implementation class for [%s] set to [%s]", repositoryClass.getName(), customImplementationClass.getName()));
        }
    }

    /**
     * Extracts repository class from Guice-bindings (it's a type paramater).
     *
     * @param injector Google Guice injector.
     *
     * @return repository class.
     */
    protected Class<R> extractRepositoryClass(Injector injector) {
        Class repositoryClass;
        Key<R> key = null;
        BiMap<Key<?>, Binding<?>> biMap = ImmutableBiMap.copyOf(injector.getBindings());
        String currentProvider = toString();
        for (Binding<?> value : biMap.values()) {
            if (value.getProvider().toString().equals(currentProvider)) {
                key = (Key<R>) biMap.inverse().get(value);
                break;
            }
        }

        if (key == null) {
            throw new IllegalStateException(String.format("Unable to find provider binding for [%s]", toString()));
        }

        repositoryClass = key.getTypeLiteral().getRawType();
        return repositoryClass;
    }

    /**
     * Creates a Spring-context for spring-data-jpa.
     *
     * @return initialized Spring-context.
     */
    protected ApplicationContext createSpringContext() {
        GenericApplicationContext context = new GenericApplicationContext();

        context.registerBeanDefinition("entityManagerFactory",
                BeanDefinitionBuilder.genericBeanDefinition(GuiceLocalEntityManagerFactoryBean.class).
                        addConstructorArgValue(entityManagerFactoryProvider).getBeanDefinition());

        context.registerBeanDefinition("transactionManager",
                BeanDefinitionBuilder.genericBeanDefinition(JpaTransactionManager.class).getBeanDefinition());


        context.registerBeanDefinition("jpaRepositoryFactory",
                BeanDefinitionBuilder.genericBeanDefinition(JpaRepositoryFactoryBean.class).getBeanDefinition());

        return context;
    }

    public R get() {
        EntityManager entityManager = entityManagerProvider.get();
        GuiceLocalEntityManagerFactoryBean entityManagerFactoryBean = context.getBean(GuiceLocalEntityManagerFactoryBean.class);

        // Transaction support specifics
        EntityManagerFactory proxiedEmf = entityManagerFactoryBean.getObject();
        if (TransactionSynchronizationManager.hasResource(proxiedEmf)) {
            TransactionSynchronizationManager.unbindResource(proxiedEmf);
        }

        TransactionSynchronizationManager.bindResource(proxiedEmf, new EntityManagerHolder(entityManagerProvider.get()));

        JpaRepositoryFactoryBean jpaRepositoryFactoryBean = createJpaRepositoryFactoryBean();

        jpaRepositoryFactoryBean.setBeanFactory(context);
        jpaRepositoryFactoryBean.setEntityManager(entityManager);
        jpaRepositoryFactoryBean.setRepositoryInterface(repositoryClass);

        if (customImplementationClass != null) {
            Object customRepositoryImplementation = instantiateCustomRepository();

            if (customRepositoryImplementation != null) {
                jpaRepositoryFactoryBean.setCustomImplementation(customRepositoryImplementation);
            }
        }

        jpaRepositoryFactoryBean.afterPropertiesSet();

        return (R) jpaRepositoryFactoryBean.getObject();
    }

    /**
     * Some instantiation example is <a href="https://jira.springsource.org/browse/DATAJPA-69">here</a>.
     */
    protected JpaRepositoryFactoryBean createJpaRepositoryFactoryBean() {
        return new JpaRepositoryFactoryBean() {
            protected RepositoryFactorySupport createRepositoryFactory(EntityManager entityManager) {
                return new CustomJpaRepositoryFactory(entityManager);
            }
        };
    }

    protected Object instantiateCustomRepository() {
        Object customRepositoryImplementation = null;
        /**
         * Watching a case when Repository implementation is a SimpleJpaRepository subclass -
         * we need to call a constructor with some parameters.
         */
        try {
            if (SimpleJpaRepository.class.isAssignableFrom(customImplementationClass)) {
                customRepositoryImplementation = customImplementationClass.getConstructor(Class.class, EntityManager.class).newInstance(domainClass, entityManagerProvider.get());
            } else {
                customRepositoryImplementation = customImplementationClass.newInstance();
            }
        } catch (Throwable e) {
            logger.error(String.format("Unable to instantiate custom repository implementation. Repository class is [%s]", customImplementationClass.getName()), e);
        }
        return customRepositoryImplementation;
    }
}