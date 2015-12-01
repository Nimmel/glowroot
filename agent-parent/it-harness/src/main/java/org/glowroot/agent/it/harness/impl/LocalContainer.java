/*
 * Copyright 2011-2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.it.harness.impl;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.reflect.Reflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.MainEntryPoint;
import org.glowroot.agent.impl.AdviceCache;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.GlowrootAgentInit;
import org.glowroot.agent.it.harness.AppUnderTest;
import org.glowroot.agent.it.harness.ConfigService;
import org.glowroot.agent.it.harness.Container;
import org.glowroot.agent.it.harness.TempDirs;
import org.glowroot.agent.weaving.Advice;
import org.glowroot.agent.weaving.IsolatedWeavingClassLoader;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

public class LocalContainer implements Container {

    private final File baseDir;
    private final boolean deleteBaseDirOnClose;
    private final boolean shared;

    private volatile @Nullable IsolatedWeavingClassLoader isolatedWeavingClassLoader;
    private final @Nullable GrpcServerWrapper server;
    private final @Nullable TraceCollector traceCollector;
    private final @Nullable ConfigService configService;
    private final GlowrootAgentInit glowrootAgentInit;

    private volatile @Nullable Thread executingAppThread;

    public static LocalContainer create(File baseDir) throws Exception {
        return new LocalContainer(baseDir, false, false, ImmutableMap.<String, String>of());
    }

    public LocalContainer(@Nullable File baseDir, boolean shared, boolean fat,
            Map<String, String> extraProperties) throws Exception {
        if (baseDir == null) {
            this.baseDir = TempDirs.createTempDir("glowroot-test-basedir");
            deleteBaseDirOnClose = true;
        } else {
            this.baseDir = baseDir;
            deleteBaseDirOnClose = false;
        }
        this.shared = shared;

        int collectorPort;
        if (fat) {
            collectorPort = 0;
            traceCollector = null;
            server = null;
        } else {
            collectorPort = getAvailablePort();
            traceCollector = new TraceCollector();
            server = new GrpcServerWrapper(traceCollector, collectorPort);
        }
        Map<String, String> properties = Maps.newHashMap();
        properties.put("glowroot.base.dir", this.baseDir.getAbsolutePath());
        if (collectorPort != 0) {
            properties.put("glowroot.collector.host", "localhost");
            properties.put("glowroot.collector.port", Integer.toString(collectorPort));
        }
        properties.putAll(extraProperties);
        List<Class<?>> bridgeClasses = ImmutableList.<Class<?>>of(AppUnderTest.class);
        IsolatedClassLoader midLoader = new IsolatedClassLoader(bridgeClasses);
        ClassLoader priorContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // need to initialize MainEntryPoint first to give SLF4J a chance to load outside
            // of the context class loader, otherwise often (but not always) end up with this:
            //
            // SLF4J: The following set of substitute loggers may have been accessed
            // SLF4J: during the initialization phase. Logging calls during this
            // SLF4J: phase were not honored. However, subsequent logging calls to these
            // SLF4J: loggers will work as normally expected.
            // SLF4J: See also http://www.slf4j.org/codes.html#substituteLogger
            // SLF4J: org.glowroot.agent.weaving.IsolatedWeavingClassLoader

            Reflection.initialize(MainEntryPoint.class);

            Thread.currentThread().setContextClassLoader(midLoader);
            MainEntryPoint.start(properties);
        } finally {
            Thread.currentThread().setContextClassLoader(priorContextClassLoader);
        }
        IsolatedWeavingClassLoader.Builder loader = IsolatedWeavingClassLoader.builder();
        loader.setParentClassLoader(midLoader);
        glowrootAgentInit = checkNotNull(MainEntryPoint.getGlowrootAgentInit());
        AgentModule agentModule = glowrootAgentInit.getAgentModule();
        AdviceCache adviceCache = agentModule.getAdviceCache();
        loader.setShimTypes(adviceCache.getShimTypes());
        loader.setMixinTypes(adviceCache.getMixinTypes());
        List<Advice> advisors = Lists.newArrayList();
        advisors.addAll(adviceCache.getAdvisors());
        loader.setAdvisors(advisors);
        loader.setWeavingTimerService(agentModule.getWeavingTimerService());
        loader.setTimerWrapperMethods(
                agentModule.getConfigService().getAdvancedConfig().timerWrapperMethods());
        loader.addBridgeClasses(bridgeClasses);
        isolatedWeavingClassLoader = loader.build();
        configService = new ConfigServiceImpl(server, false);
        // this is used to set slowThresholdMillis=0
        glowrootAgentInit.getAgentModule().getConfigService().resetAllConfig();
    }

    @Override
    public ConfigService getConfigService() {
        checkNotNull(configService);
        return configService;
    }

    @Override
    public void addExpectedLogMessage(String loggerName, String partialMessage) {
        checkNotNull(traceCollector);
        traceCollector.addExpectedLogMessage(loggerName, partialMessage);
    }

    @Override
    public Trace execute(Class<? extends AppUnderTest> appClass) throws Exception {
        checkNotNull(traceCollector);
        executeInternal(appClass);
        Trace trace = traceCollector.getCompletedTrace(10, SECONDS);
        traceCollector.clearTrace();
        return trace;
    }

    @Override
    public void executeNoExpectedTrace(Class<? extends AppUnderTest> appClass) throws Exception {
        executeInternal(appClass);
        Thread.sleep(10);
        if (traceCollector != null && traceCollector.hasTrace()) {
            throw new IllegalStateException("Trace was collected when none was expected");
        }
    }

    @Override
    public void interruptAppUnderTest() throws Exception {
        Thread thread = executingAppThread;
        if (thread == null) {
            throw new IllegalStateException("No app currently executing");
        }
        thread.interrupt();
    }

    @Override
    public Trace getCollectedPartialTrace() throws InterruptedException {
        checkNotNull(traceCollector);
        return traceCollector.getPartialTrace(10, SECONDS);
    }

    @Override
    public void checkAndReset() throws Exception {
        glowrootAgentInit.getAgentModule().getConfigService().resetAllConfig();
        if (traceCollector != null) {
            traceCollector.checkAndResetLogMessages();
        }
    }

    @Override
    public void close() throws Exception {
        close(false);
    }

    @Override
    public void close(boolean evenIfShared) throws Exception {
        if (shared && !evenIfShared) {
            // this is the shared container and will be closed at the end of the run
            ch.qos.logback.classic.Logger rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory
                    .getLogger(Logger.ROOT_LOGGER_NAME);
            // detaching existing GrpcLogbackAppender so that it won't continue to pick up and
            // report errors that are logged to this Container
            rootLogger.detachAppender("org.glowroot.agent.init.GrpcLogbackAppender");
            return;
        }
        glowrootAgentInit.close();
        if (server != null) {
            server.close();
        }
        if (deleteBaseDirOnClose) {
            TempDirs.deleteRecursively(baseDir);
        }
        // release class loader to prevent OOM Perm Gen
        isolatedWeavingClassLoader = null;
    }

    // this is used to re-open a shared container after a non-shared container was used
    public void reopen() throws Exception {
        glowrootAgentInit.reopen();
    }

    private void executeInternal(Class<? extends AppUnderTest> appClass) throws Exception {
        IsolatedWeavingClassLoader isolatedWeavingClassLoader = this.isolatedWeavingClassLoader;
        if (isolatedWeavingClassLoader == null) {
            throw new AssertionError("LocalContainer has already been stopped");
        }
        ClassLoader previousContextClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(isolatedWeavingClassLoader);
        executingAppThread = Thread.currentThread();
        try {
            AppUnderTest app = isolatedWeavingClassLoader.newInstance(appClass, AppUnderTest.class);
            app.executeApp();
        } finally {
            executingAppThread = null;
            Thread.currentThread().setContextClassLoader(previousContextClassLoader);
        }
    }

    static int getAvailablePort() throws IOException {
        ServerSocket serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverSocket.close();
        return port;
    }
}
