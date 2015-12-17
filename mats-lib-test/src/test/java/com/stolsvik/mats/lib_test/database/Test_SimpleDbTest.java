package com.stolsvik.mats.lib_test.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.stolsvik.mats.lib_test.MatsDbTest;
import com.stolsvik.mats.lib_test.basics.Test_SimplestServiceRequest;
import com.stolsvik.mats.test.MatsTestLatch.Result;
import com.stolsvik.mats.util.MatsTxSqlConnection;

/**
 * Dumb test that looks quite a bit like {@link Test_SimplestServiceRequest}, only the Initiator now populate a table
 * with some data, which the Mats service retrieves and replies with.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]  (inserts into database)
 *     [Service]  (fetches from database)
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SimpleDbTest extends MatsDbTest {
    @SuppressWarnings("resource")
    @Before
    public void setupService() {
        matsRule.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    // :: Get the data from the SQL table
                    try {
                        Connection matsTxDbCon = MatsTxSqlConnection.getConnection();
                        Statement stmt = matsTxDbCon.createStatement();
                        ResultSet rs = stmt.executeQuery("SELECT * FROM datatable");
                        rs.next();
                        String data = rs.getString(1);

                        return new DataTO(dto.number * 2, dto.string + ":FromService:" + data);
                    }
                    catch (SQLException e) {
                        throw new RuntimeException("Got problems with the SQL", e);
                    }
                });
    }

    @Before
    public void setupTerminator() {
        matsRule.getMatsFactory().terminator(TERMINATOR, DataTO.class, StateTO.class,
                (context, dto, sto) -> {
                    log.debug("TERMINATOR MatsTrace:\n" + context.getTrace());
                    matsTestLatch.resolve(dto, sto);
                });

    }

    @SuppressWarnings("resource")
    @Test
    public void doTest() throws InterruptedException, SQLException {
        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);

        String randomData = UUID.randomUUID().toString();

        // :: Create a table - outside of the MATS transaction.
        try (Connection dbCon = matsRule.getDataSource().getConnection();
                Statement stmt = dbCon.createStatement();) {
            stmt.execute("CREATE TABLE datatable ( data VARCHAR )");
        }

        matsRule.getMatsFactory().getInitiator(INITIATOR).initiate(
                (msg) -> {
                    // :: Populate the SQL table with a piece of data
                    Connection matsTxDbCon = MatsTxSqlConnection.getConnection();
                    try {
                        PreparedStatement pStmt = matsTxDbCon.prepareStatement("INSERT INTO datatable VALUES (?)");
                        pStmt.setString(1, randomData);
                        pStmt.execute();
                    }
                    catch (SQLException e) {
                        throw new RuntimeException("Got problems with the SQL", e);
                    }

                    // :: Send the request
                    msg.traceId(randomId())
                            .from(INITIATOR)
                            .to(SERVICE)
                            .replyTo(TERMINATOR)
                            .request(dto, sto);
                });

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = matsTestLatch.waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService:" + randomData), result.getData());
    }
}