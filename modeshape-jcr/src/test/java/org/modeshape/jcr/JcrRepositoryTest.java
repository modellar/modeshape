/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jcr;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.collection.IsArrayContaining.hasItemInArray;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.jcr.NamespaceException;
import javax.jcr.NoSuchWorkspaceException;
import javax.jcr.Node;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFormatException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.observation.Event;
import javax.jcr.query.Query;
import javax.jcr.query.QueryResult;
import javax.transaction.TransactionManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.modeshape.common.FixFor;
import org.modeshape.common.collection.Problems;
import org.modeshape.common.statistic.Stopwatch;
import org.modeshape.common.util.FileUtil;
import org.modeshape.jcr.RepositoryStatistics.MetricHistory;
import org.modeshape.jcr.api.monitor.DurationActivity;
import org.modeshape.jcr.api.monitor.DurationMetric;
import org.modeshape.jcr.api.monitor.History;
import org.modeshape.jcr.api.monitor.Statistics;
import org.modeshape.jcr.api.monitor.ValueMetric;
import org.modeshape.jcr.api.monitor.Window;
import org.modeshape.jcr.cache.NodeKey;
import org.modeshape.jcr.journal.LocalJournal;
import org.modeshape.jcr.journal.JournalRecord;

public class JcrRepositoryTest extends AbstractTransactionalTest {

    private Environment environment;
    private RepositoryConfiguration config;
    private JcrRepository repository;
    private JcrSession session;

    @Before
    public void beforeEach() throws Exception {
        FileUtil.delete("target/persistent_repository");

        environment = new TestingEnvironment();
        config = new RepositoryConfiguration("repoName", environment);
        repository = new JcrRepository(config);
        repository.start();
    }

    @After
    public void afterEach() throws Exception {
        if (session != null) {
            try {
                session.logout();
            } finally {
                session = null;
            }
        }
        shutdownDefaultRepository();
        environment.shutdown();
    }

    private void shutdownDefaultRepository() {
        if (repository != null) {
            try {
                TestingUtil.killRepositories(repository);
            } finally {
                repository = null;
                config = null;
            }
        }
    }

    protected TransactionManager getTransactionManager() {
        return repository.transactionManager();
    }

    @Test
    public void shouldCreateRepositoryInstanceWithoutPassingInCacheManager() throws Exception {
        shutdownDefaultRepository();
        RepositoryConfiguration config = new RepositoryConfiguration("repoName");
        repository = new JcrRepository(config);
        repository.start();
        try {
            Session session = repository.login();
            assertThat(session, is(notNullValue()));
        } finally {
            repository.shutdown().get(3L, TimeUnit.SECONDS);
            JTATestUtil.clearJBossJTADefaultStoreLocation();
        }
    }

    @Test
    public void shouldAllowCreationOfSessionForDefaultWorkspaceWithoutUsingCredentials() throws Exception {
        JcrSession session1 = repository.login();
        assertThat(session1.isLive(), is(true));
    }

    @Test( expected = NoSuchWorkspaceException.class )
    public void shouldNotAllowCreatingSessionForNonExistantWorkspace() throws Exception {
        repository.login("non-existant-workspace");
    }

    @Test
    public void shouldAllowShuttingDownAndRestarting() throws Exception {
        JcrSession session1 = repository.login();
        JcrSession session2 = repository.login();
        assertThat(session1.isLive(), is(true));
        assertThat(session2.isLive(), is(true));
        session2.logout();
        assertThat(session1.isLive(), is(true));
        assertThat(session2.isLive(), is(false));

        repository.shutdown().get(3L, TimeUnit.SECONDS);
        assertThat(session1.isLive(), is(false));
        assertThat(session2.isLive(), is(false));

        repository.start();
        JcrSession session3 = repository.login();
        assertThat(session1.isLive(), is(false));
        assertThat(session2.isLive(), is(false));
        assertThat(session3.isLive(), is(true));
        session3.logout();
    }

    @Test
    public void shouldAllowCreatingNewWorkspacesByDefault() throws Exception {
        // Verify the workspace does not exist yet ...
        try {
            repository.login("new-workspace");
        } catch (NoSuchWorkspaceException e) {
            // expected
        }
        JcrSession session1 = repository.login();
        assertThat(session1.getRootNode(), is(notNullValue()));
        session1.getWorkspace().createWorkspace("new-workspace");

        // Now create a session to that workspace ...
        JcrSession session2 = repository.login("new-workspace");
        assertThat(session2.getRootNode(), is(notNullValue()));
    }

    @FixFor( {"MODE-1834", "MODE-2004"} )
    @Test
    public void shouldAllowCreatingNewWorkspacesByDefaultWhenUsingTransactionManagerWithOptimisticLocking() throws Exception {
        shutdownDefaultRepository();

        RepositoryConfiguration config = RepositoryConfiguration.read("config/repo-config-filesystem-jbosstxn-optimistic.json");
        repository = new JcrRepository(config);
        repository.start();

        // Verify the workspace does not exist yet ...
        try {
            repository.login("new-workspace");
        } catch (NoSuchWorkspaceException e) {
            // expected
        }
        JcrSession session1 = repository.login();
        assertThat(session1.getRootNode(), is(notNullValue()));
        session1.getWorkspace().createWorkspace("new-workspace");

        // Now create a session to that workspace ...
        JcrSession session2 = repository.login("new-workspace");
        assertThat(session2.getRootNode(), is(notNullValue()));

        // Shut down the repository ...
        assertThat(repository.shutdown().get(), is(true));

        // Start up the repository again, this time by reading the persisted data ...
        repository = new JcrRepository(config);
        repository.start();

        // And verify that the workspace existance was persisted properly ...
        repository.login("new-workspace");
    }

    @FixFor( {"MODE-1834", "MODE-2004"} )
    @Test
    public void shouldAllowCreatingNewWorkspacesByDefaultWhenUsingTransactionManagerWithPessimisticLocking() throws Exception {
        shutdownDefaultRepository();

        RepositoryConfiguration config = RepositoryConfiguration.read("config/repo-config-filesystem-jbosstxn-pessimistic.json");
        repository = new JcrRepository(config);
        repository.start();

        // Verify the workspace does not exist yet ...
        try {
            repository.login("new-workspace");
        } catch (NoSuchWorkspaceException e) {
            // expected
        }
        JcrSession session1 = repository.login();
        assertThat(session1.getRootNode(), is(notNullValue()));
        session1.getWorkspace().createWorkspace("new-workspace");

        // Now create a session to that workspace ...
        JcrSession session2 = repository.login("new-workspace");
        assertThat(session2.getRootNode(), is(notNullValue()));

        // Shut down the repository ...
        assertThat(repository.shutdown().get(), is(true));

        // Start up the repository again, this time by reading the persisted data ...
        repository = new JcrRepository(config);
        repository.start();

        // And verify that the workspace existance was persisted properly ...
        repository.login("new-workspace");
    }

    @Test
    public void shouldAllowDestroyingWorkspacesByDefault() throws Exception {
        // Verify the workspace does not exist yet ...
        try {
            repository.login("new-workspace");
        } catch (NoSuchWorkspaceException e) {
            // expected
        }
        JcrSession session1 = repository.login();
        assertThat(session1.getRootNode(), is(notNullValue()));
        session1.getWorkspace().createWorkspace("new-workspace");

        // Now create a session to that workspace ...
        JcrSession session2 = repository.login("new-workspace");
        assertThat(session2.getRootNode(), is(notNullValue()));
    }

    @Test
    public void shouldReturnNullForNullDescriptorKey() {
        assertThat(repository.getDescriptor(null), is(nullValue()));
    }

    @Test
    public void shouldReturnNullForEmptyDescriptorKey() {
        assertThat(repository.getDescriptor(""), is(nullValue()));
    }

    @Test
    public void shouldProvideBuiltInDescriptorKeys() {
        testDescriptorKeys(repository);
    }

    @Test
    public void shouldProvideDescriptorValues() {
        testDescriptorValues(repository);
    }

    @Test
    public void shouldProvideBuiltInDescriptorsWhenNotSuppliedDescriptors() throws Exception {
        testDescriptorKeys(repository);
        testDescriptorValues(repository);
    }

    @Test
    public void shouldProvideRepositoryWorkspaceNamesDescriptor() throws ValueFormatException {
        Set<String> workspaceNames = repository.repositoryCache().getWorkspaceNames();
        Set<String> descriptorValues = new HashSet<String>();
        for (JcrValue value : repository.getDescriptorValues(org.modeshape.jcr.api.Repository.REPOSITORY_WORKSPACES)) {
            descriptorValues.add(value.getString());
        }
        assertThat(descriptorValues, is(workspaceNames));
    }

    @Test
    public void shouldProvideStatisticsImmediatelyAfterStartup() throws Exception {
        History history = repository.getRepositoryStatistics()
                                    .getHistory(ValueMetric.WORKSPACE_COUNT, Window.PREVIOUS_60_SECONDS);
        Statistics[] stats = history.getStats();
        assertThat(stats.length, is(not(0)));
        assertThat(history.getTotalDuration(TimeUnit.SECONDS), is(60L));
        System.out.println(history);
    }

    /**
     * Skipping this test because it purposefully runs over 60 minutes (!!!), mostly just waiting for the statistics thread to
     * wake up once every 5 seconds.
     * 
     * @throws Exception
     */
    @Ignore
    @Test
    public void shouldProvideStatisticsForAVeryLongTime() throws Exception {
        final AtomicBoolean stop = new AtomicBoolean(false);
        final JcrRepository repository = this.repository;
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                JcrSession[] openSessions = new JcrSession[100 * 6];
                int index = 0;
                while (!stop.get()) {
                    try {
                        for (int i = 0; i != 6; ++i) {
                            JcrSession session1 = repository.login();
                            assertThat(session1.getRootNode(), is(notNullValue()));
                            openSessions[index++] = session1;
                        }
                        if (index >= openSessions.length) {
                            for (int i = 0; i != openSessions.length; ++i) {
                                openSessions[i].logout();
                                openSessions[i] = null;
                            }
                            index = 0;
                        }
                        Thread.sleep(MILLISECONDS.convert(3, SECONDS));
                    } catch (Throwable t) {
                        t.printStackTrace();
                        stop.set(true);
                        break;
                    }
                }
            }
        });
        worker.start();
        // Status thread ...
        final Stopwatch sw = new Stopwatch();
        Thread status = new Thread(new Runnable() {
            @Override
            public void run() {
                System.out.println("Starting ...");
                sw.start();
                int counter = 0;
                while (!stop.get()) {
                    try {
                        Thread.sleep(MILLISECONDS.convert(10, SECONDS));
                        if (!stop.get()) {
                            ++counter;
                            sw.lap();
                            System.out.println("   continuing after " + sw.getTotalDuration().toSimpleString());
                        }
                        if (counter % 24 == 0) {
                            History history = repository.getRepositoryStatistics().getHistory(ValueMetric.SESSION_COUNT,
                                                                                              Window.PREVIOUS_60_SECONDS);
                            System.out.println(history);
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                        stop.set(true);
                        break;
                    }
                }
            }
        });
        status.start();

        // wait for 65 minutes, so that the statistics have a value ...
        Thread.sleep(MILLISECONDS.convert(65, MINUTES));
        stop.set(true);
        System.out.println();
        Thread.sleep(MILLISECONDS.convert(5, SECONDS));

        History history = repository.getRepositoryStatistics().getHistory(ValueMetric.SESSION_COUNT, Window.PREVIOUS_60_MINUTES);
        Statistics[] stats = history.getStats();
        System.out.println(history);
        assertThat(stats.length, is(MetricHistory.MAX_MINUTES));
        assertThat(stats[0], is(notNullValue()));
        assertThat(stats[11], is(notNullValue()));
        assertThat(stats[59], is(notNullValue()));
        assertThat(history.getTotalDuration(TimeUnit.MINUTES), is(60L));

        history = repository.getRepositoryStatistics().getHistory(ValueMetric.SESSION_COUNT, Window.PREVIOUS_60_SECONDS);
        stats = history.getStats();
        System.out.println(history);
        assertThat(stats.length, is(MetricHistory.MAX_SECONDS));
        assertThat(stats[0], is(notNullValue()));
        assertThat(stats[11], is(notNullValue()));
        assertThat(history.getTotalDuration(TimeUnit.SECONDS), is(60L));

        history = repository.getRepositoryStatistics().getHistory(ValueMetric.SESSION_COUNT, Window.PREVIOUS_24_HOURS);
        stats = history.getStats();
        System.out.println(history);
        assertThat(stats.length, is(not(0)));
        assertThat(stats[0], is(nullValue()));
        assertThat(stats[23], is(notNullValue()));
        assertThat(history.getTotalDuration(TimeUnit.HOURS), is(24L));
    }

    /**
     * Skipping this test because it purposefully runs over 6 seconds, mostly just waiting for the statistics thread to wake up
     * once every 5 seconds.
     * 
     * @throws Exception
     */
    @Ignore
    @Test
    public void shouldProvideStatistics() throws Exception {
        for (int i = 0; i != 3; ++i) {
            JcrSession session1 = repository.login();
            assertThat(session1.getRootNode(), is(notNullValue()));
        }
        // wait for 6 seconds, so that the statistics have a value ...
        Thread.sleep(6000L);
        History history = repository.getRepositoryStatistics().getHistory(ValueMetric.SESSION_COUNT, Window.PREVIOUS_60_SECONDS);
        Statistics[] stats = history.getStats();
        assertThat(stats.length, is(12));
        assertThat(stats[0], is(nullValue()));
        assertThat(stats[11], is(notNullValue()));
        assertThat(stats[11].getMaximum(), is(3L));
        assertThat(stats[11].getMinimum(), is(3L));
        assertThat(history.getTotalDuration(TimeUnit.SECONDS), is(60L));
        System.out.println(history);
    }

    /**
     * Skipping this test because it purposefully runs over 18 seconds, mostly just waiting for the statistics thread to wake up
     * once every 5 seconds.
     * 
     * @throws Exception
     */
    @Ignore
    @Test
    public void shouldProvideStatisticsForMultipleSeconds() throws Exception {
        LinkedList<JcrSession> sessions = new LinkedList<JcrSession>();
        for (int i = 0; i != 5; ++i) {
            JcrSession session1 = repository.login();
            assertThat(session1.getRootNode(), is(notNullValue()));
            sessions.addFirst(session1);
            Thread.sleep(1000L);
        }
        Thread.sleep(6000L);
        while (sessions.peek() != null) {
            JcrSession session = sessions.poll();
            session.logout();
            Thread.sleep(1000L);
        }
        History history = repository.getRepositoryStatistics().getHistory(ValueMetric.SESSION_COUNT, Window.PREVIOUS_60_SECONDS);
        Statistics[] stats = history.getStats();
        assertThat(stats.length, is(12));
        assertThat(history.getTotalDuration(TimeUnit.SECONDS), is(60L));
        System.out.println(history);

        DurationActivity[] lifetimes = repository.getRepositoryStatistics().getLongestRunning(DurationMetric.SESSION_LIFETIME);
        System.out.println("Session lifetimes: ");
        for (DurationActivity activity : lifetimes) {
            System.out.println("  " + activity);
        }
    }

    @SuppressWarnings( "deprecation" )
    private void testDescriptorKeys( Repository repository ) {
        String[] keys = repository.getDescriptorKeys();
        assertThat(keys, notNullValue());
        assertThat(keys.length >= 15, is(true));
        assertThat(keys, hasItemInArray(Repository.LEVEL_1_SUPPORTED));
        assertThat(keys, hasItemInArray(Repository.LEVEL_2_SUPPORTED));
        assertThat(keys, hasItemInArray(Repository.OPTION_LOCKING_SUPPORTED));
        assertThat(keys, hasItemInArray(Repository.OPTION_OBSERVATION_SUPPORTED));
        assertThat(keys, hasItemInArray(Repository.OPTION_QUERY_SQL_SUPPORTED));
        assertThat(keys, hasItemInArray(Repository.OPTION_TRANSACTIONS_SUPPORTED));
        assertThat(keys, hasItemInArray(Repository.OPTION_VERSIONING_SUPPORTED));
        assertThat(keys, hasItemInArray(Repository.QUERY_XPATH_DOC_ORDER));
        assertThat(keys, hasItemInArray(Repository.QUERY_XPATH_POS_INDEX));
        assertThat(keys, hasItemInArray(Repository.REP_NAME_DESC));
        assertThat(keys, hasItemInArray(Repository.REP_VENDOR_DESC));
        assertThat(keys, hasItemInArray(Repository.REP_VENDOR_URL_DESC));
        assertThat(keys, hasItemInArray(Repository.REP_VERSION_DESC));
        assertThat(keys, hasItemInArray(Repository.SPEC_NAME_DESC));
        assertThat(keys, hasItemInArray(Repository.SPEC_VERSION_DESC));
    }

    @SuppressWarnings( "deprecation" )
    private void testDescriptorValues( Repository repository ) {
        assertThat(repository.getDescriptor(Repository.LEVEL_1_SUPPORTED), is("true"));
        assertThat(repository.getDescriptor(Repository.LEVEL_2_SUPPORTED), is("true"));
        assertThat(repository.getDescriptor(Repository.OPTION_LOCKING_SUPPORTED), is("true"));
        assertThat(repository.getDescriptor(Repository.OPTION_OBSERVATION_SUPPORTED), is("true"));
        assertThat(repository.getDescriptor(Repository.OPTION_QUERY_SQL_SUPPORTED), is("true"));
        assertThat(repository.getDescriptor(Repository.OPTION_TRANSACTIONS_SUPPORTED), is("true"));
        assertThat(repository.getDescriptor(Repository.OPTION_VERSIONING_SUPPORTED), is("true"));
        assertThat(repository.getDescriptor(Repository.QUERY_XPATH_DOC_ORDER), is("false"));
        assertThat(repository.getDescriptor(Repository.QUERY_XPATH_POS_INDEX), is("false"));
        assertThat(repository.getDescriptor(Repository.REP_NAME_DESC), is("ModeShape"));
        assertThat(repository.getDescriptor(Repository.REP_VENDOR_DESC), is("JBoss, a division of Red Hat"));
        assertThat(repository.getDescriptor(Repository.REP_VENDOR_URL_DESC), is("http://www.modeshape.org"));
        assertThat(repository.getDescriptor(Repository.REP_VERSION_DESC), is(notNullValue()));
        assertThat(repository.getDescriptor(Repository.REP_VERSION_DESC).startsWith("4."), is(true));
        assertThat(repository.getDescriptor(Repository.SPEC_NAME_DESC), is(JcrI18n.SPEC_NAME_DESC.text()));
        assertThat(repository.getDescriptor(Repository.SPEC_VERSION_DESC), is("2.0"));
    }

    @Test
    public void shouldReturnNullWhenDescriptorKeyIsNull() {
        assertThat(repository.getDescriptor(null), is(nullValue()));
    }

    @Test
    public void shouldNotAllowEmptyDescriptorKey() {
        assertThat(repository.getDescriptor(""), is(nullValue()));
    }

    @Test
    public void shouldNotProvideRepositoryWorkspaceNamesDescriptorIfOptionSetToFalse() throws Exception {
        assertThat(repository.getDescriptor(org.modeshape.jcr.api.Repository.REPOSITORY_WORKSPACES), is(nullValue()));
    }

    @SuppressWarnings( "deprecation" )
    @Test
    public void shouldHaveRootNode() throws Exception {
        session = createSession();
        javax.jcr.Node root = session.getRootNode();
        String uuid = root.getIdentifier();

        // Should be referenceable ...
        assertThat(root.isNodeType("mix:referenceable"), is(true));

        // Should have a UUID ...
        assertThat(root.getUUID(), is(uuid));

        // Should have an identifier ...
        assertThat(root.getIdentifier(), is(uuid));

        // Get the children of the root node ...
        javax.jcr.NodeIterator iter = root.getNodes();
        javax.jcr.Node system = iter.nextNode();
        assertThat(system.getName(), is("jcr:system"));

        // Add a child node ...
        javax.jcr.Node childA = root.addNode("childA", "nt:unstructured");
        assertThat(childA, is(notNullValue()));
        iter = root.getNodes();
        javax.jcr.Node system2 = iter.nextNode();
        javax.jcr.Node childA2 = iter.nextNode();
        assertThat(system2.getName(), is("jcr:system"));
        assertThat(childA2.getName(), is("childA"));
    }

    @Test
    public void shouldHaveSystemBranch() throws Exception {
        session = createSession();
        javax.jcr.Node root = session.getRootNode();
        AbstractJcrNode system = (AbstractJcrNode)root.getNode("jcr:system");
        assertThat(system, is(notNullValue()));
    }

    @Test
    public void shouldHaveRegisteredModeShapeSpecificNamespacesNamespaces() throws Exception {
        session = createSession();
        // Don't use the constants, since this needs to check that the actual values are correct
        assertThat(session.getNamespaceURI("mode"), is("http://www.modeshape.org/1.0"));
    }

    @Test( expected = NamespaceException.class )
    public void shouldNotHaveModeShapeInternalNamespaceFromVersion2() throws Exception {
        session = createSession();
        // Don't use the constants, since this needs to check that the actual values are correct
        session.getNamespaceURI("modeint");
    }

    @Test
    public void shouldHaveRegisteredThoseNamespacesDefinedByTheJcrSpecification() throws Exception {
        session = createSession();
        // Don't use the constants, since this needs to check that the actual values are correct
        assertThat(session.getNamespaceURI("mode"), is("http://www.modeshape.org/1.0"));
        assertThat(session.getNamespaceURI("jcr"), is("http://www.jcp.org/jcr/1.0"));
        assertThat(session.getNamespaceURI("mix"), is("http://www.jcp.org/jcr/mix/1.0"));
        assertThat(session.getNamespaceURI("nt"), is("http://www.jcp.org/jcr/nt/1.0"));
        assertThat(session.getNamespaceURI(""), is(""));
    }

    @Test
    public void shouldHaveRegisteredThoseNamespacesDefinedByTheJcrApiJavaDoc() throws Exception {
        session = createSession();
        // Don't use the constants, since this needs to check that the actual values are correct
        assertThat(session.getNamespaceURI("sv"), is("http://www.jcp.org/jcr/sv/1.0"));
        assertThat(session.getNamespaceURI("xmlns"), is("http://www.w3.org/2000/xmlns/"));
    }

    protected JcrSession createSession() throws Exception {
        return repository.login();
    }

    protected JcrSession createSession( final String workspace ) throws Exception {
        return repository.login(workspace);
    }

    @Ignore( "GC behavior is non-deterministic from the application's POV - this test _will_ occasionally fail" )
    @Test
    public void shouldAllowManySessionLoginsAndLogouts() throws Exception {
        Session session = null;
        for (int i = 0; i < 10000; i++) {
            session = repository.login();
            session.logout();
        }

        session = repository.login();
        session = null;

        // Give the gc a chance to run
        System.gc();
        Thread.sleep(100);

        assertThat(repository.runningState().activeSessionCount(), is(0));
    }

    /**
     * This test takes about 10 minutes to run, and is therefore @Ignore'd.
     * 
     * @throws Exception
     */
    @Ignore
    @Test
    public void shouldCleanUpLocksFromDeadSessions() throws Exception {
        String lockedNodeName = "lockedNode";
        JcrSession locker = repository.login();

        // Create a node to lock
        javax.jcr.Node lockedNode = locker.getRootNode().addNode(lockedNodeName);
        lockedNode.addMixin("mix:lockable");
        locker.save();

        // Create a session-scoped lock (not deep)
        locker.getWorkspace().getLockManager().lock(lockedNode.getPath(), false, true, 1L, "me");
        assertThat(lockedNode.isLocked(), is(true));

        Session reader = repository.login();
        javax.jcr.Node readerNode = (javax.jcr.Node)reader.getItem("/" + lockedNodeName);
        assertThat(readerNode.isLocked(), is(true));

        // No locks should have changed yet.
        repository.runningState().cleanUpLocks();
        assertThat(lockedNode.isLocked(), is(true));
        assertThat(readerNode.isLocked(), is(true));

        /*       
         * Simulate the GC cleaning up the session and it being purged from the activeSessions() map.
         * This can't really be tested in a consistent way due to a lack of specificity around when
         * the garbage collector runs. The @Ignored test above does cause a GC sweep on by computer and
         * confirms that the code works in principle. A different chicken dance may be required to
         * fully test this on a different computer.
         */
        repository.runningState().removeSession(locker);
        Thread.sleep(RepositoryConfiguration.LOCK_EXTENSION_INTERVAL_IN_MILLIS + 100);

        // The locker thread should be inactive and the lock cleaned up
        repository.runningState().cleanUpLocks();
        assertThat(readerNode.isLocked(), is(false));
    }

    @Test
    public void shouldAllowCreatingWorkspaces() throws Exception {
        shutdownDefaultRepository();

        RepositoryConfiguration config = null;
        config = RepositoryConfiguration.read("{ \"name\" : \"repoName\", \"workspaces\" : { \"allowCreation\" : true } }");
        config = new RepositoryConfiguration(config.getDocument(), "repoName", environment);
        repository = new JcrRepository(config);
        repository.start();

        // Create several sessions ...
        Session session2 = null;
        Session session3 = null;
        try {
            session = createSession();
            session2 = createSession();

            // Create a new workspace ...
            String newWorkspaceName = "MyCarWorkspace";
            session.getWorkspace().createWorkspace(newWorkspaceName);
            assertAccessibleWorkspace(session, newWorkspaceName);
            assertAccessibleWorkspace(session2, newWorkspaceName);
            session.logout();

            session3 = createSession();
            assertAccessibleWorkspace(session2, newWorkspaceName);
            assertAccessibleWorkspace(session3, newWorkspaceName);

            // Create a session for this new workspace ...
            session = createSession(newWorkspaceName);
        } finally {
            try {
                if (session2 != null) session2.logout();
            } finally {
                if (session3 != null) session3.logout();
            }
        }

    }

    protected void assertAccessibleWorkspace( Session session,
                                              String workspaceName ) throws Exception {
        assertContains(session.getWorkspace().getAccessibleWorkspaceNames(), workspaceName);
    }

    protected void assertContains( String[] actuals,
                                   String... expected ) {
        // Each expected must appear in the actuals ...
        for (String expect : expected) {
            if (expect == null) continue;
            boolean found = false;
            for (String actual : actuals) {
                if (expect.equals(actual)) {
                    found = true;
                    break;
                }
            }
            assertThat("Did not find '" + expect + "' in the actuals: " + actuals, found, is(true));
        }
    }

    @Test
    @FixFor( "MODE-1269" )
    public void shouldAllowReindexingEntireWorkspace() throws Exception {
        session = createSession();
        session.getWorkspace().reindex();
    }

    @Test
    @FixFor( "MODE-1269" )
    public void shouldAllowReindexingSubsetOfWorkspace() throws Exception {
        session = createSession();
        session.getWorkspace().reindex("/");
    }

    @Test
    @FixFor( "MODE-1269" )
    public void shouldAllowAsynchronousReindexingEntireWorkspace() throws Exception {
        session = createSession();
        Future<Boolean> future = session.getWorkspace().reindexAsync();
        assertThat(future, is(notNullValue()));
        assertThat(future.get(), is(true)); // get() blocks until done
    }

    @Test
    @FixFor( "MODE-1269" )
    public void shouldAllowAsynchronousReindexingSubsetOfWorkspace() throws Exception {
        session = createSession();
        Future<Boolean> future = session.getWorkspace().reindexAsync("/");
        assertThat(future, is(notNullValue()));
        assertThat(future.get(), is(true)); // get() blocks until done
    }

    @FixFor( "MODE-1498" )
    @Test
    public void shouldWorkWithUserDefinedTransactions() throws Exception {
        session = createSession();

        Session session2 = createSession();
        try {

            // Create a listener to count the changes ...
            SimpleListener listener = addListener(3); // we'll create 3 nodes ...
            assertThat(listener.getActualEventCount(), is(0));

            // Check that the node is not visible to the other session ...
            session.refresh(false);
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");

            // Start a transaction ...
            final TransactionManager txnMgr = getTransactionManager();
            txnMgr.begin();
            assertThat(listener.getActualEventCount(), is(0));
            Node txnNode1 = session.getRootNode().addNode("txnNode1");
            Node txnNode1a = txnNode1.addNode("txnNodeA");
            assertThat(txnNode1a, is(notNullValue()));
            session.save();
            assertThat(listener.getActualEventCount(), is(0));

            // Check that the node is not visible to the other session ...
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");
            session2.refresh(false);
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");

            // sleep a bit to let any incorrect events propagate through the system ...
            Thread.sleep(100L);
            assertThat(listener.getActualEventCount(), is(0));

            Node txnNode2 = session.getRootNode().addNode("txnNode2");
            assertThat(txnNode2, is(notNullValue()));
            session.save();
            assertThat(listener.getActualEventCount(), is(0));
            assertThat(session.getRootNode().hasNode("txnNode1"), is(true));
            assertThat(session.getRootNode().hasNode("txnNode2"), is(true));

            // Check that the node is not visible to the other session ...
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");
            session2.refresh(false);
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");

            // sleep a bit to let any incorrect events propagate through the system ...
            Thread.sleep(100L);
            assertThat(listener.getActualEventCount(), is(0));

            assertThat(session.getRootNode().hasNode("txnNode1"), is(true));
            assertThat(session.getRootNode().hasNode("txnNode2"), is(true));

            // Now commit the transaction ...
            txnMgr.commit();
            listener.waitForEvents();

            nodeExists(session, "/", "txnNode1");
            nodeExists(session, "/", "txnNode2");

            // Check that the node IS visible to the other session ...
            nodeExists(session2, "/", "txnNode1");
            nodeExists(session2, "/", "txnNode2");
            session2.refresh(false);
            nodeExists(session2, "/", "txnNode1");
            nodeExists(session2, "/", "txnNode2");

        } finally {
            session2.logout();
        }
    }

    @FixFor( "MODE-1498" )
    @Test
    public void shouldWorkWithUserDefinedTransactionsThatUseRollback() throws Exception {
        session = createSession();

        Session session2 = createSession();
        try {

            // Create a listener to count the changes ...
            SimpleListener listener = addListener(3); // we'll create 3 nodes ...
            assertThat(listener.getActualEventCount(), is(0));

            // Check that the node is not visible to the other session ...
            session.refresh(false);
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");

            // Start a transaction ...
            final TransactionManager txnMgr = getTransactionManager();
            txnMgr.begin();
            assertThat(listener.getActualEventCount(), is(0));
            Node txnNode1 = session.getRootNode().addNode("txnNode1");
            Node txnNode1a = txnNode1.addNode("txnNodeA");
            assertThat(txnNode1a, is(notNullValue()));
            session.save();
            assertThat(listener.getActualEventCount(), is(0));

            // Check that the node is not visible to the other session ...
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");
            session2.refresh(false);
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");

            // sleep a bit to let any incorrect events propagate through the system ...
            Thread.sleep(100L);
            assertThat(listener.getActualEventCount(), is(0));

            Node txnNode2 = session.getRootNode().addNode("txnNode2");
            assertThat(txnNode2, is(notNullValue()));
            session.save();
            assertThat(listener.getActualEventCount(), is(0));
            assertThat(session.getRootNode().hasNode("txnNode1"), is(true));
            assertThat(session.getRootNode().hasNode("txnNode2"), is(true));

            // Check that the node is not visible to the other session ...
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");
            session2.refresh(false);
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");

            // sleep a bit to let any incorrect events propagate through the system ...
            Thread.sleep(100L);
            assertThat(listener.getActualEventCount(), is(0));

            assertThat(session.getRootNode().hasNode("txnNode1"), is(true));
            assertThat(session.getRootNode().hasNode("txnNode2"), is(true));

            // Now commit the transaction ...
            txnMgr.rollback();

            // There should have been no events ...
            Thread.sleep(100L);
            assertThat(listener.getActualEventCount(), is(0));

            // The nodes does not exist in the session because the session was saved and those changes were rolled back ...
            assertThat(session.getRootNode().hasNode("txnNode1"), is(false));
            assertThat(session.getRootNode().hasNode("txnNode2"), is(false));
            session.refresh(false);
            assertThat(session.getRootNode().hasNode("txnNode1"), is(false));
            assertThat(session.getRootNode().hasNode("txnNode2"), is(false));

            // Check that the node IS NOT visible to the other session ...
            session2.refresh(false);
            nodeDoesNotExist(session2, "/", "txnNode1");
            nodeDoesNotExist(session2, "/", "txnNode2");

        } finally {
            session2.logout();
        }
    }

    @FixFor( "MODE-1828" )
    @Test
    public void shouldAllowNodeTypeChangeAfterWrite() throws Exception {
        session = createSession();
        session.workspace()
               .getNodeTypeManager()
               .registerNodeTypes(getClass().getResourceAsStream("/cnd/nodeTypeChange-initial.cnd"), true);

        Node testRoot = session.getRootNode().addNode("/testRoot", "test:nodeTypeA");
        testRoot.setProperty("fieldA", "foo");
        session.save();

        session.workspace()
               .getNodeTypeManager()
               .registerNodeTypes(getClass().getResourceAsStream("/cnd/nodeTypeChange-next.cnd"), true);

        testRoot = session.getNode("/testRoot");
        assertEquals("foo", testRoot.getProperty("fieldA").getString());
        testRoot.setProperty("fieldB", "bar");
        session.save();

        testRoot = session.getNode("/testRoot");
        assertEquals("foo", testRoot.getProperty("fieldA").getString());
        assertEquals("bar", testRoot.getProperty("fieldB").getString());
    }

    @FixFor( "MODE-1525" )
    @Test
    public void shouldDiscoverCorrectChildNodeType() throws Exception {
        session = createSession();

        InputStream cndStream = getClass().getResourceAsStream("/cnd/medical.cnd");
        assertThat(cndStream, is(notNullValue()));
        session.getWorkspace().getNodeTypeManager().registerNodeTypes(cndStream, true);

        // Now create a person ...
        Node root = session.getRootNode();
        Node person = root.addNode("jsmith", "inf:person");
        person.setProperty("inf:firstName", "John");
        person.setProperty("inf:lastName", "Smith");
        session.save();

        Node doctor = root.addNode("drBarnes", "inf:doctor");
        doctor.setProperty("inf:firstName", "Sally");
        doctor.setProperty("inf:lastName", "Barnes");
        doctor.setProperty("inf:doctorProviderNumber", "12345678-AB");
        session.save();

        Node referral = root.addNode("referral", "nt:unstructured");
        referral.addMixin("er:eReferral");
        assertThat(referral.getMixinNodeTypes()[0].getName(), is("er:eReferral"));
        Node group = referral.addNode("er:gp");
        assertThat(group.getPrimaryNodeType().getName(), is("inf:doctor"));
        // Check that group doesn't specify the first name and last name ...
        assertThat(group.hasProperty("inf:firstName"), is(false));
        assertThat(group.hasProperty("inf:lastName"), is(false));
        session.save();
        // Check that group has a default first name and last name ...
        assertThat(group.getProperty("inf:firstName").getString(), is("defaultFirstName"));
        assertThat(group.getProperty("inf:lastName").getString(), is("defaultLastName"));

        Node docGroup = root.addNode("documentGroup", "inf:documentGroup");
        assertThat(docGroup.getPrimaryNodeType().getName(), is("inf:documentGroup"));
        docGroup.addMixin("er:eReferral");
        Node ergp = docGroup.addNode("er:gp");
        assertThat(ergp.getPrimaryNodeType().getName(), is("inf:doctor"));
        // Check that group doesn't specify the first name and last name ...
        assertThat(ergp.hasProperty("inf:firstName"), is(false));
        assertThat(ergp.hasProperty("inf:lastName"), is(false));
        session.save();
        // Check that group has a default first name and last name ...
        assertThat(ergp.getProperty("inf:firstName").getString(), is("defaultFirstName"));
        assertThat(ergp.getProperty("inf:lastName").getString(), is("defaultLastName"));
    }

    @FixFor( "MODE-1525" )
    @Test
    public void shouldDiscoverCorrectChildNodeTypeButFailOnMandatoryPropertiesWithNoDefaultValues() throws Exception {
        session = createSession();

        InputStream cndStream = getClass().getResourceAsStream("/cnd/medical-invalid-mandatories.cnd");
        assertThat(cndStream, is(notNullValue()));
        session.getWorkspace().getNodeTypeManager().registerNodeTypes(cndStream, true);

        // Now create a person ...
        Node root = session.getRootNode();
        Node person = root.addNode("jsmith", "inf:person");
        person.setProperty("inf:firstName", "John");
        person.setProperty("inf:lastName", "Smith");
        session.save();

        Node doctor = root.addNode("drBarnes", "inf:doctor");
        doctor.setProperty("inf:firstName", "Sally");
        doctor.setProperty("inf:lastName", "Barnes");
        doctor.setProperty("inf:doctorProviderNumber", "12345678-AB");
        session.save();

        Node referral = root.addNode("referral", "nt:unstructured");
        referral.addMixin("er:eReferral");
        assertThat(referral.getMixinNodeTypes()[0].getName(), is("er:eReferral"));
        Node group = referral.addNode("er:gp");
        assertThat(group.getPrimaryNodeType().getName(), is("inf:doctor"));
        try {
            session.save();
            fail("Expected a constraint violation exception");
        } catch (ConstraintViolationException e) {
            // expected, since "inf:firstName" is mandatory but doesn't have a default value
        }

        // Set the missing mandatory properties on the node ...
        group.setProperty("inf:firstName", "Sally");
        group.setProperty("inf:lastName", "Barnes");

        // and now Session.save() will work ...
        session.save();
    }

    @Test
    @FixFor( "MODE-1807" )
    public void shouldRegisterCNDFileWithResidualChildDefinition() throws Exception {
        session = createSession();

        InputStream cndStream = getClass().getResourceAsStream("/cnd/orc.cnd");
        assertThat(cndStream, is(notNullValue()));
        session.getWorkspace().getNodeTypeManager().registerNodeTypes(cndStream, true);

        session.getRootNode().addNode("patient", "orc:patient").addNode("patientcase", "orc:patientcase");
        session.save();

        assertNotNull(session.getNode("/patient/patientcase"));
    }

    @Test
    @FixFor( "MODE-1360" )
    public void shouldHandleMultipleConcurrentReadWriteSessions() throws Exception {
        int numThreads = 100;
        final AtomicBoolean passed = new AtomicBoolean(true);
        final AtomicInteger counter = new AtomicInteger(0);
        final Repository repository = this.repository;
        ExecutorService executor = Executors.newFixedThreadPool(50);
        try {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        Session session = repository.login();
                        session.getRootNode().addNode("n" + counter.getAndIncrement()); // unique name
                        session.save();
                        session.logout();
                    } catch (Throwable e) {
                        e.printStackTrace();
                        passed.set(false);
                    }
                }

            };
            for (int i = 0; i < numThreads; i++) {
                executor.execute(runnable);
            }
            executor.shutdown(); // Disable new tasks from being submitted
        } finally {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                fail("timeout");
            }
            if (!passed.get()) {
                fail("one or more threads got an exception");
            }
        }
    }

    @FixFor( "MODE-1805" )
    @Test
    public void shouldCreateRepositoryInstanceWithQueriesDisabled() throws Exception {
        shutdownDefaultRepository();

        RepositoryConfiguration config = RepositoryConfiguration.read("{ 'name' : 'noQueries', 'query' : { 'enabled' : false } }");
        repository = new JcrRepository(config);
        repository.start();
        try {
            Session session = repository.login();
            assertThat(session, is(notNullValue()));

            // Add some content ...
            Node testNode = session.getRootNode().addNode("repos");
            session.save();
            session.logout();

            session = repository.login();
            Node testNode2 = session.getNode("/repos");
            assertTrue(testNode.isSame(testNode2));
            session.logout();

            // Queries return nothing ...
            session = repository.login();
            Query query = session.getWorkspace().getQueryManager().createQuery("SELECT * FROM [nt:base]", Query.JCR_SQL2);
            QueryResult results = query.execute();
            assertTrue(results.getNodes().getSize() == 0);
            session.logout();
        } finally {
            repository.shutdown().get(3L, TimeUnit.SECONDS);
            JTATestUtil.clearJBossJTADefaultStoreLocation();
        }
    }

    @FixFor( "MODE-1902" )
    @Test( expected = RepositoryException.class )
    public void shouldFailToStartWhenNoIndexesExistAndRebuildOptionFailIfMissing() throws Exception {
        shutdownDefaultRepository();

        RepositoryConfiguration config = RepositoryConfiguration.read(getClass().getClassLoader()
                                                                                .getResourceAsStream("config/repo-config-fail-if-missing-indexes.json"),
                                                                      "Fail if missing indexes");
        repository = new JcrRepository(config);
        repository.start();
    }

    @Test
    @FixFor( "MODE-2056")
    public void shouldReturnActiveSessions() throws Exception {
        shutdownDefaultRepository();

        config = new RepositoryConfiguration("repoName", environment);
        repository = new JcrRepository(config);
        assertEquals(0, repository.getActiveSessionsCount());

        repository.start();

        JcrSession session1 = repository.login();
        JcrSession session2 = repository.login();
        assertEquals(2, repository.getActiveSessionsCount());
        session2.logout();
        assertEquals(1, repository.getActiveSessionsCount());
        session1.logout();
        assertEquals(0, repository.getActiveSessionsCount());
        repository.login();
        repository.shutdown().get();
        assertEquals(0, repository.getActiveSessionsCount());
    }

    @FixFor( "MODE-2033" )
    @Test
    public void shouldStartAndReturnStartupProblems() throws Exception {
        shutdownDefaultRepository();
        RepositoryConfiguration config = RepositoryConfiguration.read(
                getClass().getClassLoader().getResourceAsStream("config/repo-config-with-startup-problems.json"), "Deprecated config");
        repository = new JcrRepository(config);
        Problems problems = repository.getStartupProblems();
        assertEquals("Expected 2 startup warnings:" + problems.toString(), 2, problems.warningCount());
        assertEquals("Expected 2 startup errors: " + problems.toString(), 2, problems.errorCount());
    }

    @FixFor( "MODE-2033" )
    @Test
    public void shouldClearStartupProblemsOnRestart() throws Exception {
        shutdownDefaultRepository();
        RepositoryConfiguration config = RepositoryConfiguration.read(
                getClass().getClassLoader().getResourceAsStream("config/repo-config-with-startup-problems.json"), "Deprecated config");
        repository = new JcrRepository(config);
        Problems problems = repository.getStartupProblems();
        assertEquals("Invalid startup problems:" + problems.toString(), 4, problems.size());
        repository.shutdown().get();
        problems = repository.getStartupProblems();
        assertEquals("Invalid startup problems:" + problems.toString(), 4, problems.size());
    }

    @FixFor( "MODE-2033" )
    @Test
    public void shouldReturnStartupProblemsAfterStarting() throws Exception {
        shutdownDefaultRepository();
        RepositoryConfiguration config = RepositoryConfiguration.read(
                getClass().getClassLoader().getResourceAsStream("config/repo-config-with-startup-problems.json"), "Deprecated config");
        repository = new JcrRepository(config);
        repository.start();
        Problems problems = repository.getStartupProblems();
        assertEquals("Expected 2 startup warnings:" + problems.toString(), 2, problems.warningCount());
        assertEquals("Expected 2 startup errors: " + problems.toString(), 2, problems.errorCount());
    }

    @FixFor( "MODE-1863" )
    @Test
    public void shouldStartupWithJournalingEnabled() throws Exception {
        FileUtil.delete("target/journal");
        shutdownDefaultRepository();
        RepositoryConfiguration config = RepositoryConfiguration.read(
                getClass().getClassLoader().getResourceAsStream("config/repo-config-journaling.json"), "Deprecated config");
        repository = new JcrRepository(config);
        repository.start();

        //add some nodes
        JcrSession session1 = repository.login();
        int nodeCount = 10;
        for (int i = 0; i < nodeCount; i++) {
            Node node = session1.getRootNode().addNode("testNode_" + i);
            node.setProperty("int_prop", i);
        }
        session1.save();

        //give the events a change to reach the journal
        Thread.sleep(300);

        //edit some nodes
        for (int i = 0; i < nodeCount / 2; i++) {
            session1.getNode("/testNode_" + i).setProperty("int_prop2", 2 * i);
        }
        session1.save();

        //give the events a change to reach the journal
        Thread.sleep(300);

        //remove the nodes
        Set<NodeKey> expectedJournalKeys = new TreeSet<NodeKey>();
        for (int i = 0; i < nodeCount; i++) {
            AbstractJcrNode node = session1.getNode("/testNode_" + i);
            expectedJournalKeys.add(node.key());
            node.remove();
        }
        expectedJournalKeys.add(session1.getRootNode().key());
        session1.save();

        //give the events a change to reach the journal
        Thread.sleep(300);

        //check the journal has entries
        LocalJournal.Records journalRecordsReversed = repository.runningState().journal().allRecords(true);

        assertTrue(journalRecordsReversed.size() > 0);
        JournalRecord lastRecord = journalRecordsReversed.iterator().next();
        assertEquals(expectedJournalKeys, new TreeSet<NodeKey>(lastRecord.changedNodes()));

        repository.shutdown();
    }

    protected void nodeExists( Session session,
                               String parentPath,
                               String childName,
                               boolean exists ) throws Exception {
        Node parent = session.getNode(parentPath);
        assertThat(parent.hasNode(childName), is(exists));
        String path = parent.getPath();
        if (parent.getDepth() != 0) path = path + "/";
        path = path + childName;

        String sql = "SELECT * FROM [nt:base] WHERE PATH() = '" + path + "'";
        Query query = session.getWorkspace().getQueryManager().createQuery(sql, Query.JCR_SQL2);
        QueryResult result = query.execute();
        assertThat(result.getNodes().getSize(), is(exists ? 1L : 0L));
    }

    protected void nodeExists( Session session,
                               String parentPath,
                               String childName ) throws Exception {
        nodeExists(session, parentPath, childName, true);
    }

    protected void nodeDoesNotExist( Session session,
                                     String parentPath,
                                     String childName ) throws Exception {
        nodeExists(session, parentPath, childName, false);
    }

    private static final int ALL_EVENTS = Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED
                                          | Event.PROPERTY_REMOVED;

    SimpleListener addListener( int expectedEventsCount ) throws Exception {
        return addListener(expectedEventsCount, ALL_EVENTS, null, false, null, null, false);
    }

    SimpleListener addListener( int expectedEventsCount,
                                int eventTypes,
                                String absPath,
                                boolean isDeep,
                                String[] uuids,
                                String[] nodeTypeNames,
                                boolean noLocal ) throws Exception {
        return addListener(expectedEventsCount, 1, eventTypes, absPath, isDeep, uuids, nodeTypeNames, noLocal);
    }

    SimpleListener addListener( int expectedEventsCount,
                                int numIterators,
                                int eventTypes,
                                String absPath,
                                boolean isDeep,
                                String[] uuids,
                                String[] nodeTypeNames,
                                boolean noLocal ) throws Exception {
        return addListener(this.session,
                           expectedEventsCount,
                           numIterators,
                           eventTypes,
                           absPath,
                           isDeep,
                           uuids,
                           nodeTypeNames,
                           noLocal);
    }

    SimpleListener addListener( Session session,
                                int expectedEventsCount,
                                int numIterators,
                                int eventTypes,
                                String absPath,
                                boolean isDeep,
                                String[] uuids,
                                String[] nodeTypeNames,
                                boolean noLocal ) throws Exception {
        SimpleListener listener = new SimpleListener(expectedEventsCount, numIterators, eventTypes);
        session.getWorkspace()
               .getObservationManager()
               .addEventListener(listener, eventTypes, absPath, isDeep, uuids, nodeTypeNames, noLocal);
        return listener;
    }

}
