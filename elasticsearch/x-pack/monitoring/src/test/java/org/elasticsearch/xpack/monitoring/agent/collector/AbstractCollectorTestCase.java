/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.agent.collector;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.SysGlobals;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.license.core.License;
import org.elasticsearch.license.plugin.Licensing;
import org.elasticsearch.license.plugin.core.LicenseService;
import org.elasticsearch.license.plugin.core.LicenseState;
import org.elasticsearch.license.plugin.core.Licensee;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.graph.GraphLicensee;
import org.elasticsearch.xpack.monitoring.MonitoringLicensee;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.monitoring.test.MonitoringIntegTestCase;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.security.SecurityLicenseState;
import org.elasticsearch.xpack.support.clock.Clock;
import org.elasticsearch.xpack.watcher.WatcherLicensee;
import org.junit.Before;

import static java.util.Collections.emptyList;
import static org.elasticsearch.common.unit.TimeValue.timeValueMinutes;

@ClusterScope(scope = ESIntegTestCase.Scope.SUITE, randomDynamicTemplates = false, transportClientRatio = 0.0)
public abstract class AbstractCollectorTestCase extends MonitoringIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(InternalXPackPlugin.class);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .build();
    }

    @Before
    public void ensureLicenseIsEnabled() {
        enableLicense();
    }

    public InternalClient securedClient() {
        return internalCluster().getInstance(InternalClient.class);
    }

    public InternalClient securedClient(String nodeId) {
        return internalCluster().getInstance(InternalClient.class, nodeId);
    }

    protected void assertCanCollect(AbstractCollector collector) {
        assertNotNull(collector);
        assertTrue("collector [" + collector.name() + "] should be able to collect data", collector.shouldCollect());
        Collection results = collector.collect();
        assertNotNull(results);
    }

    protected void assertCannotCollect(AbstractCollector collector) {
        assertNotNull(collector);
        assertFalse("collector [" + collector.name() + "] should not be able to collect data", collector.shouldCollect());
        Collection results = collector.collect();
        assertTrue(results == null || results.isEmpty());
    }

    private static License createTestingLicense(long issueDate, long expiryDate) {
        return License.builder()
                .expiryDate(expiryDate)
                .issueDate(issueDate)
                .issuedTo("AbstractCollectorTestCase")
                .issuer("test")
                .maxNodes(Integer.MAX_VALUE)
                .signature("_signature")
                .type("trial")
                .uid(String.valueOf(RandomizedTest.systemPropertyAsInt(SysGlobals.CHILDVM_SYSPROP_JVM_ID, 0)) +
                        System.identityHashCode(AbstractCollectorTestCase.class))
                .build();
    }

    protected static void enableLicense() {
        long issueDate = System.currentTimeMillis();
        long expiryDate = issueDate + randomDaysInMillis();

        final License license = createTestingLicense(issueDate, expiryDate);
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.onChange(license.operationMode(), LicenseState.ENABLED);
        }
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.update(license);
        }
    }

    protected static void beginGracefulPeriod() {
        long expiryDate = System.currentTimeMillis() + timeValueMinutes(10).millis();
        long issueDate = expiryDate - randomDaysInMillis();

        final License license = createTestingLicense(issueDate, expiryDate);
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.onChange(license.operationMode(), LicenseState.GRACE_PERIOD);
        }
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.update(license);
        }
    }

    protected static void endGracefulPeriod() {
        long expiryDate = System.currentTimeMillis() - MonitoringSettings.MAX_LICENSE_GRACE_PERIOD.millis() - timeValueMinutes(10).millis();
        long issueDate = expiryDate - randomDaysInMillis();

        final License license = createTestingLicense(issueDate, expiryDate);
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.onChange(license.operationMode(), LicenseState.DISABLED);
        }
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.update(license);
        }
    }

    protected static void disableLicense() {
        long expiryDate = System.currentTimeMillis() - MonitoringSettings.MAX_LICENSE_GRACE_PERIOD.millis() - randomDaysInMillis();
        long issueDate = expiryDate - randomDaysInMillis();

        final License license = createTestingLicense(issueDate, expiryDate);
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.onChange(license.operationMode(), LicenseState.DISABLED);
        }
        for (LicenseServiceForCollectors service : internalCluster().getInstances(LicenseServiceForCollectors.class)) {
            service.update(license);
        }
    }

    private static long randomDaysInMillis() {
        return TimeValue.timeValueHours(randomIntBetween(1, 30) * 24).millis();
    }

    public void waitForNoBlocksOnNodes() throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                for (String nodeId : internalCluster().getNodeNames()) {
                    try {
                        waitForNoBlocksOnNode(nodeId);
                    } catch (Exception e) {
                        fail("failed to wait for no blocks on node [" + nodeId + "]: " + e.getMessage());
                    }
                }
            }
        });
    }

    public void waitForNoBlocksOnNode(final String nodeId) throws Exception {
        assertBusy(() -> {
            ClusterBlocks clusterBlocks =
                    client(nodeId).admin().cluster().prepareState().setLocal(true).execute().actionGet().getState().blocks();
            assertTrue(clusterBlocks.global().isEmpty());
            assertTrue(clusterBlocks.indices().values().isEmpty());
        }, 30L, TimeUnit.SECONDS);
    }

    public static class InternalLicensing extends Licensing {

        public InternalLicensing() {
            super(Settings.EMPTY);
        }

        @Override
        public Collection<Module> nodeModules() {
            return Collections.singletonList(b -> b.bind(LicenseService.class).to(LicenseServiceForCollectors.class));
        }

        @Override
        public Collection<Object> createComponents(ClusterService clusterService, Clock clock, Environment environment,
                                                   ResourceWatcherService resourceWatcherService,
                                                   SecurityLicenseState securityLicenseState) {
            WatcherLicensee watcherLicensee = new WatcherLicensee(settings);
            MonitoringLicensee monitoringLicensee = new MonitoringLicensee(settings);
            GraphLicensee graphLicensee = new GraphLicensee(settings);
            LicenseService licenseService = new LicenseServiceForCollectors(settings, environment,
                    resourceWatcherService, Arrays.asList(watcherLicensee, monitoringLicensee, graphLicensee));
            return Arrays.asList(licenseService, watcherLicensee, monitoringLicensee, graphLicensee);
        }

        @Override
        public List<ActionHandler<? extends ActionRequest<?>, ? extends ActionResponse>> getActions() {
            return emptyList();
        }

        @Override
        public List<Class<? extends RestHandler>> getRestHandlers() {
            return emptyList();
        }
    }

    public static class InternalXPackPlugin extends XPackPlugin {

        public InternalXPackPlugin(Settings settings) throws IOException {
            super(settings);
            licensing = new InternalLicensing();
        }
    }

    public static class LicenseServiceForCollectors extends LicenseService {

        private final List<Licensee> licensees;
        private volatile License license;

        @Inject
        public LicenseServiceForCollectors(Settings settings, Environment env,
                                           ResourceWatcherService resourceWatcherService, List<Licensee> licensees) {
            super(settings, null, null, env, resourceWatcherService, licensees);
            this.licensees = licensees;
        }

        public void onChange(License.OperationMode operationMode, LicenseState state) {
            for (Licensee licensee : licensees) {
                licensee.onChange(new Licensee.Status(operationMode, state));
            }
        }

        @Override
        public Licensee.Status licenseeStatus() {
            return null;
        }

        @Override
        public License getLicense() {
            return license;
        }

        public synchronized void update(License license) {
            this.license = license;
        }

        @Override
        protected void doStart() {}

        @Override
        protected void doStop() {}
    }
}
