package com.stolsvik.mats.impl.jms;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

import javax.jms.JMSException;
import javax.jms.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.stolsvik.mats.exceptions.MatsBackendException;
import com.stolsvik.mats.exceptions.MatsRefuseMessageException;
import com.stolsvik.mats.exceptions.MatsRuntimeException;
import com.stolsvik.mats.util.MatsTxSqlConnection;
import com.stolsvik.mats.util.MatsTxSqlConnection.MatsSqlConnectionCreationException;

/**
 * Implementation of {@link JmsMatsTransactionManager} that in addition to the JMS transaction also handles a JDBC SQL
 * {@link Connection} (using only pure java, i.e. no Spring) for which it keeps transaction demarcation along with the
 * JMS transaction, by means of <a href=
 * "http://www.javaworld.com/article/2077963/open-source-tools/distributed-transactions-in-spring--with-and-without-xa.html?page=2">
 * <i>"Best Effort 1 Phase Commit"</i></a>:
 * <ol>
 * <li><b>JMS transaction is entered</b> (a transactional JMS Connection is always within a transaction)
 * <li>JMS Message is retrieved.
 * <li><b>SQL transaction is entered</b>
 * <li>Code is executed, including SQL statements.
 * <li><b>SQL transaction is committed - <font color="red">Any errors also rollbacks the JMS Transaction, so that none
 * of them have happened.</font></b>
 * <li><b>JMS transaction is committed.</b>
 * </ol>
 * Out of that order, one can see that if SQL transaction becomes committed, and then the JMS transaction fails, this
 * will be a pretty bad situation. However, of those two transactions, the SQL transaction is absolutely most likely to
 * fail, as this is where you can have business logic failures, concurrency problems (e.g. MS SQL's "Deadlock Victim"),
 * integrity constraints failing etc - that is, failures in both logic and timing. On the other hand, the JMS
 * transaction (which effectively boils down to <i>"yes, I received this message"</i>) is much harder to fail, where the
 * only situation where it can fail is due to infrastructure/hardware failures (exploding server / full disk on Message
 * Broker). This is called "Best Effort 1PC", and is nicely explained in <a href=
 * "http://www.javaworld.com/article/2077963/open-source-tools/distributed-transactions-in-spring--with-and-without-xa.html?page=2">
 * this article</a>. If this failure occurs, it will be caught and logged on ERROR level (by
 * {@link JmsMatsTransactionManager_JmsOnly}) - and then the Message Broker will probably try to redeliver the message.
 * Wise tips: Code idempotent! Handle double-deliveries!
 * <p>
 * The transactionally demarcated SQL Connection can be retrieved from the {@link MatsTxSqlConnection} utility class.
 * <p>
 * It requires a {@link JdbcConnectionSupplier} upon construction, in addition to the
 * {@link JmsMatsTransactionManager.JmsConnectionSupplier JmsConnectionSupplier}.
 * <p>
 * The {@link JdbcConnectionSupplier} will be asked for a SQL Connection in any MatsStage's StageProcessor that requires
 * it: The creation of the SQL Connection is lazy in that it won't be retrieved (nor entered into transaction with),
 * until it is actually requested by the user code by means of {@link MatsTxSqlConnection#getConnection()}.
 * <p>
 * The SQL Connection will be {@link Connection#close() closed} after each stage processing (after each transaction,
 * either committed or rollbacked) - if it was requested during the user code.
 * <p>
 * This implementation will not attempt at any Connection reuse (caching/pooling). It is up to the supplier to implement
 * any pooling, or make use of a pooled DataSource, if so desired. (Which definitely should be desired, due to the heavy
 * use of <i> "get new - use - commit/rollback - close"</i>.)
 *
 * @author Endre Stølsvik - 2015-12-06 - http://endre.stolsvik.com
 */
public class JmsMatsTransactionManager_JmsAndJdbc extends JmsMatsTransactionManager_JmsOnly {
    private static final Logger log = LoggerFactory.getLogger(JmsMatsTransactionManager_JmsAndJdbc.class);

    /**
     * Abstracts away JDBC Connection generation - useful if you need to provide any Connection parameters, or set some
     * Connection properties, for example set the {@link java.sql.Connection#setTransactionIsolation(int) Transaction
     * Isolation Level}.
     * <p>
     * Otherwise, the lambda can be as simple as <code>(stage) -> dataSource.getConnection()</code>.
     */
    @FunctionalInterface
    public interface JdbcConnectionSupplier {
        Connection createJdbcConnection(JmsMatsStage<?, ?, ?> stage) throws SQLException;
    }

    private final JdbcConnectionSupplier _jdbcConnectionSupplier;

    public static JmsMatsTransactionManager_JmsAndJdbc create(JmsConnectionSupplier jmsConnectionSupplier,
            JdbcConnectionSupplier jdbcConnectionSupplier) {
        return new JmsMatsTransactionManager_JmsAndJdbc(jmsConnectionSupplier, jdbcConnectionSupplier);
    }

    protected JmsMatsTransactionManager_JmsAndJdbc(JmsConnectionSupplier jmsConnectionSupplier,
            JdbcConnectionSupplier jdbcConnectionSupplier) {
        super(jmsConnectionSupplier);
        _jdbcConnectionSupplier = jdbcConnectionSupplier;
    }

    @Override
    public TransactionContext getTransactionContext(JmsMatsStage<?, ?, ?> stage) {
        return new TransactionalContext_JmsAndJdbc(createJmsConnection(stage), _jdbcConnectionSupplier, stage);
    }

    /**
     * The {@link JmsMatsTransactionManager.TransactionContext} implementation for
     * {@link JmsMatsTransactionManager_JmsOnly}.
     */
    public static class TransactionalContext_JmsAndJdbc extends TransactionalContext_JmsOnly {
        private final JdbcConnectionSupplier _jdbcConnectionSupplier;
        private final JmsMatsStage<?, ?, ?> _stage;

        public TransactionalContext_JmsAndJdbc(javax.jms.Connection jmsConnection,
                JdbcConnectionSupplier jdbcConnectionSupplier, JmsMatsStage<?, ?, ?> stage) {
            super(jmsConnection, stage);
            _jdbcConnectionSupplier = jdbcConnectionSupplier;
            _stage = stage;
        }

        @Override
        public void performWithinTransaction(Session jmsSession, ProcessingLambda lambda) throws JMSException {
            // :: First make the potential Connection available
            LazyJdbcConnectionSupplier lazyConnectionSupplier = new LazyJdbcConnectionSupplier();
            MatsTxSqlConnection.setThreadLocalConnectionSupplier(lazyConnectionSupplier);

            // :: We invoke the "outer" transaction, which is the JMS transaction.
            super.performWithinTransaction(jmsSession, () -> {
                // ----- We're *within* the JMS Transaction demarcation.

                /*
                 * NOTICE: We will not get the SQL Connection and set AutoCommit to false /here/ (i.e. start the
                 * transaction), as that will be done implicitly by the user code IFF it actually fetches the SQL
                 * Connection.
                 *
                 * ----- Therefore, we're now IMPLICITLY *within* the SQL Transaction demarcation.
                 */

                // Flag to check that we've handled all paths we know of.
                boolean allPathsHandled = false;
                try {
                    log.debug(LOG_PREFIX + "About to run ProcessingLambda for " + stageOrInit(_stage)
                            + ", within JDBC SQL Transactional demarcation.");
                    /*
                     * Invoking the provided ProcessingLambda, which typically will be the actual user code (albeit
                     * wrapped with some minor code from the JmsMatsStage to parse the MapMessage, deserialize the
                     * MatsTrace, and fetch the state etc.), which will now be inside both the inner (implicit) SQL
                     * Transaction demarcation, and the outer JMS Transaction demarcation.
                     */
                    lambda.performWithinTransaction();

                    // ProcessingLambda went OK: "Good path" is handled.
                    allPathsHandled = true;
                }
                // Catch EVERYTHING that can come out of the try-block:
                catch (MatsRefuseMessageException | RuntimeException | Error e) {
                    // ----- The user code had some error occur, or want to reject this message.

                    // The ProcessngLambda raised some Exception, and this is the handling: "Bad path" is handled.
                    allPathsHandled = true;

                    // !!NOTE!!: The full Exception will be logged by super class when it does its rollback handling.
                    log.error(LOG_PREFIX + "ROLLBACK SQL: " + e.getClass().getSimpleName() + " while processing "
                            + stageOrInit(_stage) + " (most probably from user code)."
                            + " Rolling back the SQL Connection.");
                    /*
                     * IFF the SQL Connection was fetched, we will now rollback (and close) it.
                     */
                    commitOrRollbackThenCloseConnection(false, lazyConnectionSupplier.getAnyGottenConnection());

                    // ----- We're *outside* the SQL Transaction demarcation (rolled back).

                    // We will now throw on the Exception, which will rollback the JMS Transaction.
                    throw e;
                }
                // :: Sanity check that we have handled all paths
                finally {
                    // ?: Are all paths handled?
                    if (!allPathsHandled) {
                        // -> No: This is a MATS coding error, the catch block above should have caught the Exception.
                        String msg = "The " + stageOrInit(_stage)
                                + " raised some Exception that should have been caught! This should never happen!";
                        log.error(msg);
                        commitOrRollbackThenCloseConnection(false, lazyConnectionSupplier.getAnyGottenConnection());
                        throw new MatsBackendException(msg);
                    }
                }

                // ----- The ProcessingLambda went OK, no Exception was raised.

                log.debug(LOG_PREFIX + "COMMIT SQL: ProcessingLambda finished, committing SQL Connection.");
                /*
                 * IFF the SQL Connection was fetched, we will now commit (and close) it.
                 */
                commitOrRollbackThenCloseConnection(true, lazyConnectionSupplier.getAnyGottenConnection());

                // ----- We're now *outside* the SQL Transaction demarcation (committed).

                // Return nicely, as the SQL Connection.commit() and .close() went OK.
                return;
            });
        }

        private void commitOrRollbackThenCloseConnection(boolean commit, Connection con) {
            // ?: Was connection gotten by code in ProcessingLambda (user code)
            if (con == null) {
                // -> No, Connection was not gotten
                log.debug(LOG_PREFIX
                        + "SQL Connection was not requested by stage processing lambda (user code), nothing"
                        + " to perform " + (commit ? "commit" : "rollback") + " on!");
                return;
            }
            // E-> Yes, Connection was gotten by ProcessingLambda (user code)
            // :: Commit or Rollback
            try {
                if (commit) {
                    con.commit();
                    log.debug(LOG_PREFIX + "SQL Connection committed.");
                }
                else {
                    con.rollback();
                    log.debug(LOG_PREFIX + "SQL Connection rolled back.");
                }
            }
            catch (SQLException e) {
                throw new MatsSqlCommitOrRollbackFailedException("Could not " + (commit ? "commit" : "rollback")
                        + " SQL Connection [" + con + "] - for stage [" + _stage + "].", e);
            }
            // :: Close
            try {
                con.close();
                log.debug(LOG_PREFIX + "SQL Connection closed.");
            }
            catch (SQLException e) {
                throw new MatsSqlConnectionCloseFailedException("After performing " + (commit ? "commit"
                        : "rollback") + " on SQL Connection [" + con
                        + "], we tried to close it but that threw - for stage [" + _stage + "].", e);
            }
        }

        /**
         * Raised if commit or rollback of the SQL Connection failed.
         */
        public static final class MatsSqlCommitOrRollbackFailedException extends MatsRuntimeException {
            public MatsSqlCommitOrRollbackFailedException(String message, Throwable cause) {
                super(message, cause);
            }
        }

        /**
         * Raised if connection.close() throws upon finishing with the connection.
         */
        public static final class MatsSqlConnectionCloseFailedException extends MatsRuntimeException {
            public MatsSqlConnectionCloseFailedException(String message, Throwable cause) {
                super(message, cause);
            }
        }

        /**
         * Performs Lazy-getting (and setting AutoCommit false) of SQL Connection for the StageProcessor thread, by
         * means of being set as the ThreadLocal supplier using
         * {@link MatsTxSqlConnection#setThreadLocalConnectionSupplier(Supplier)}.
         */
        private class LazyJdbcConnectionSupplier implements Supplier<Connection> {
            private Connection _gottenConnection;

            @Override
            public Connection get() {
                // This shall only be done on one thread: The whole point is its ThreadLocal-ness - No concurrency.
                if (_gottenConnection == null) {
                    try {
                        _gottenConnection = _jdbcConnectionSupplier.createJdbcConnection(_stage);
                    }
                    catch (SQLException e) {
                        throw new MatsSqlConnectionCreationException("Could not get SQL Connection from ["
                                + _jdbcConnectionSupplier + "] for [" + _stage + "].", e);
                    }
                    try {
                        _gottenConnection.setAutoCommit(false);
                    }
                    catch (SQLException e) {
                        try {
                            _gottenConnection.close();
                        }
                        catch (SQLException closeE) {
                            log.warn("When trying to set AutoCommit to false, we got an SQLException. When trying"
                                    + " to close that Connection, we got a new SQLException. Ignoring.", closeE);
                        }
                        throw new MatsSqlConnectionCreationException("Could not set AutoCommit to false on the"
                                + " SQL Connection [" + _gottenConnection + "] for [" + _stage + "].", e);
                    }
                }
                return _gottenConnection;
            }

            private Connection getAnyGottenConnection() {
                return _gottenConnection;
            }
        }
    }
}
