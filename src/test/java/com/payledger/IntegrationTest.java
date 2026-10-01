package com.payledger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

/**
 * Base for every test: one real Postgres container and one Spring context, shared through the
 * context cache. Tests never clean up; each one creates its own users and asserts on its own data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected TestRestTemplate http;
}
