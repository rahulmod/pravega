/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.segmentstore.server.host;

import io.pravega.common.Exceptions;
import io.pravega.common.cluster.Host;
import io.pravega.segmentstore.contracts.StreamSegmentStore;
import io.pravega.segmentstore.server.host.delegationtoken.DelegationTokenVerifier;
import io.pravega.segmentstore.server.host.delegationtoken.TokenVerifierImpl;
import io.pravega.segmentstore.server.host.handler.PravegaConnectionListener;
import io.pravega.segmentstore.server.host.stat.AutoScalerConfig;
import io.pravega.segmentstore.server.host.stat.SegmentStatsFactory;
import io.pravega.segmentstore.server.host.stat.SegmentStatsRecorder;
import io.pravega.segmentstore.server.store.ServiceBuilder;
import io.pravega.segmentstore.server.store.ServiceBuilderConfig;
import io.pravega.segmentstore.server.store.ServiceConfig;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperConfig;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperLogFactory;
import io.pravega.segmentstore.storage.impl.extendeds3.ExtendedS3StorageConfig;
import io.pravega.segmentstore.storage.impl.extendeds3.ExtendedS3StorageFactory;
import io.pravega.segmentstore.storage.impl.filesystem.FileSystemStorageConfig;
import io.pravega.segmentstore.storage.impl.filesystem.FileSystemStorageFactory;
import io.pravega.segmentstore.storage.impl.hdfs.HDFSStorageConfig;
import io.pravega.segmentstore.storage.impl.hdfs.HDFSStorageFactory;
import io.pravega.segmentstore.storage.impl.rocksdb.RocksDBCacheFactory;
import io.pravega.segmentstore.storage.impl.rocksdb.RocksDBConfig;
import io.pravega.segmentstore.storage.mocks.InMemoryDurableDataLogFactory;
import io.pravega.segmentstore.storage.mocks.InMemoryStorageFactory;
import io.pravega.shared.metrics.MetricsConfig;
import io.pravega.shared.metrics.MetricsProvider;
import io.pravega.shared.metrics.StatsProvider;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

/**
 * Starts the Pravega Service.
 */
@Slf4j
public final class ServiceStarter {
    //region Members

    private final ServiceBuilderConfig builderConfig;
    private final ServiceConfig serviceConfig;
    private final ServiceBuilder serviceBuilder;
    private StatsProvider statsProvider;
    private PravegaConnectionListener listener;
    private SegmentStatsFactory segmentStatsFactory;
    private CuratorFramework zkClient;
    private boolean closed;
    private DelegationTokenVerifier tokenVerifier;

    //endregion

    //region Constructor

    public ServiceStarter(ServiceBuilderConfig config) {
        this.builderConfig = config;
        this.serviceConfig = this.builderConfig.getConfig(ServiceConfig::builder);
        this.serviceBuilder = createServiceBuilder();
    }

    private ServiceBuilder createServiceBuilder() {
        ServiceBuilder builder = ServiceBuilder.newInMemoryBuilder(this.builderConfig);
        attachDataLogFactory(builder);
        attachRocksDB(builder);
        attachStorage(builder);
        attachZKSegmentManager(builder);
        return builder;
    }

    //endregion

    //region Service Operation

    public void start() throws Exception {
        Exceptions.checkNotClosed(this.closed, this);

        log.info("Initializing metrics provider ...");
        MetricsProvider.initialize(builderConfig.getConfig(MetricsConfig::builder));
        statsProvider = MetricsProvider.getMetricsProvider();
        statsProvider.start();

        log.info("Initializing ZooKeeper Client ...");
        this.zkClient = createZKClient();

        log.info("Initializing Service Builder ...");
        this.serviceBuilder.initialize();

        log.info("Creating StreamSegmentService ...");
        StreamSegmentStore service = this.serviceBuilder.createStreamSegmentService();

        log.info("Creating Segment Stats recorder ...");
        segmentStatsFactory = new SegmentStatsFactory();
        SegmentStatsRecorder statsRecorder = segmentStatsFactory
                .createSegmentStatsRecorder(service, builderConfig.getConfig(AutoScalerConfig::builder));

        this.tokenVerifier = new TokenVerifierImpl(builderConfig.getConfig(AutoScalerConfig::builder));
        this.listener = new PravegaConnectionListener(false, this.serviceConfig.getListeningIPAddress(),
                this.serviceConfig.getListeningPort(), service, statsRecorder, tokenVerifier);
        this.listener.startListening();
        log.info("PravegaConnectionListener started successfully.");
        log.info("StreamSegmentService started.");
    }

    public void shutdown() {
        if (!this.closed) {
            this.serviceBuilder.close();
            log.info("StreamSegmentService shut down.");

            if (this.listener != null) {
                this.listener.close();
                log.info("PravegaConnectionListener closed.");
            }

            if (this.statsProvider != null) {
                statsProvider.close();
                statsProvider = null;
                log.info("Metrics statsProvider is now closed.");
            }

            if (this.zkClient != null) {
                this.zkClient.close();
                this.zkClient = null;
                log.info("ZooKeeper Client shut down.");
            }

            if (this.segmentStatsFactory != null) {
                segmentStatsFactory.close();
            }

            this.closed = true;
        }
    }

    private void attachDataLogFactory(ServiceBuilder builder) {
        builder.withDataLogFactory(setup -> {
            switch (this.serviceConfig.getDataLogTypeImplementation()) {
                case BOOKKEEPER:
                    return new BookKeeperLogFactory(setup.getConfig(BookKeeperConfig::builder), this.zkClient, setup.getCoreExecutor());
                case INMEMORY:
                    return new InMemoryDurableDataLogFactory(setup.getCoreExecutor());
                default:
                    throw new IllegalStateException("Unsupported storage implementation: " + this.serviceConfig.getDataLogTypeImplementation());
            }
        });
    }

    private void attachRocksDB(ServiceBuilder builder) {
        builder.withCacheFactory(setup -> new RocksDBCacheFactory(setup.getConfig(RocksDBConfig::builder)));
    }

    private void attachStorage(ServiceBuilder builder) {
        builder.withStorageFactory(setup -> {
            switch (this.serviceConfig.getStorageImplementation()) {
                case HDFS:
                    HDFSStorageConfig hdfsConfig = setup.getConfig(HDFSStorageConfig::builder);
                    return new HDFSStorageFactory(hdfsConfig, setup.getStorageExecutor());
                case FILESYSTEM:
                    FileSystemStorageConfig fsConfig = setup.getConfig(FileSystemStorageConfig::builder);
                    return new FileSystemStorageFactory(fsConfig, setup.getStorageExecutor());
                case EXTENDEDS3:
                    ExtendedS3StorageConfig extendedS3Config = setup.getConfig(ExtendedS3StorageConfig::builder);
                    return new ExtendedS3StorageFactory(extendedS3Config, setup.getStorageExecutor());
                case INMEMORY:
                    return new InMemoryStorageFactory(setup.getStorageExecutor());
                default:
                    throw new IllegalStateException("Unsupported storage implementation: " + this.serviceConfig.getStorageImplementation());
            }
        });
    }

    private void attachZKSegmentManager(ServiceBuilder builder) {
        builder.withContainerManager(setup ->
                new ZKSegmentContainerManager(setup.getContainerRegistry(),
                        this.zkClient,
                        new Host(this.serviceConfig.getPublishedIPAddress(),
                                this.serviceConfig.getPublishedPort(), null),
                        setup.getCoreExecutor()));
    }

    private CuratorFramework createZKClient() {
        CuratorFramework zkClient = CuratorFrameworkFactory
                .builder()
                .connectString(this.serviceConfig.getZkURL())
                .namespace("pravega/" + this.serviceConfig.getClusterName())
                .retryPolicy(new ExponentialBackoffRetry(this.serviceConfig.getZkRetrySleepMs(), this.serviceConfig.getZkRetryCount()))
                .sessionTimeoutMs(this.serviceConfig.getZkSessionTimeoutMs())
                .build();
        zkClient.start();
        return zkClient;
    }

    //endregion

    //region main()

    public static void main(String[] args) throws Exception {
        AtomicReference<ServiceStarter> serviceStarter = new AtomicReference<>();
        try {
            System.err.println(System.getProperty(ServiceBuilderConfig.CONFIG_FILE_PROPERTY_NAME, "config.properties"));
            // Load up the ServiceBuilderConfig, using this priority order (lowest to highest):
            // 1. Configuration file (either default or specified via SystemProperties)
            // 2. System Properties overrides (these will be passed in via the command line or inherited from the JVM)
            ServiceBuilderConfig config = ServiceBuilderConfig
                    .builder()
                    .include(System.getProperty(ServiceBuilderConfig.CONFIG_FILE_PROPERTY_NAME, "config.properties"))
                    .include(System.getProperties())
                    .build();

            // For debugging purposes, it may be useful to know the non-default values for configurations being used.
            // This will unfortunately include all System Properties as well, but knowing those can be useful too sometimes.
            log.info("Segment store configuration:");
            config.forEach((key, value) -> log.info("{} = {}", key, value));
            serviceStarter.set(new ServiceStarter(config));
        } catch (Throwable e) {
            log.error("Could not create a Service with default config, Aborting.", e);
            System.exit(1);
        }

        try {
            serviceStarter.get().start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("Caught interrupt signal...");
                serviceStarter.get().shutdown();
            }));

            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ex) {
            log.info("Caught interrupt signal...");
        } finally {
            serviceStarter.get().shutdown();
        }
    }

    //endregion
}
