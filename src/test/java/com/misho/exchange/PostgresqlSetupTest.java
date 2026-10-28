package com.misho.exchange;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostgresqlSetupTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void connectsToPostgresqlAndAppliesTheSchemaMigration() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertEquals("PostgreSQL", connection.getMetaData().getDatabaseProductName());
            assertTrue(connection.getCatalog().endsWith("_test"), "Use a dedicated test database.");
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'exchange'", Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM public.flyway_schema_history WHERE version = '1' AND success", Integer.class));
    }

    @Test
    void hibernateExecutesAQueryAgainstPostgresql() {
        Number result = (Number) entityManager.createNativeQuery("SELECT 1").getSingleResult();
        assertEquals(1, result.intValue());
    }
}
