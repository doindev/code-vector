package io.doindev.cvector.neo4j;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;

import java.util.List;
import java.util.Map;

public class Neo4jClient implements AutoCloseable {

    private final Driver driver;
    private final String uri;

    public Neo4jClient(String uri, String user, String password) {
        this.uri = uri;
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, password));
    }

    public String uri() { return uri; }

    public boolean ping() {
        try {
            driver.verifyConnectivity();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public List<Record> read(String cypher, Map<String, Object> params) {
        try (Session s = driver.session()) {
            return s.executeRead(tx -> tx.run(cypher, params).list());
        }
    }

    public void write(String cypher, Map<String, Object> params) {
        try (Session s = driver.session()) {
            s.executeWrite(tx -> {
                tx.run(cypher, params).consume();
                return null;
            });
        }
    }

    @Override
    public void close() {
        driver.close();
    }
}
