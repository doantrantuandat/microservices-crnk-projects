package io.github.doantrantuandat.example.accounts;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

// ordering.service.url points at an address nothing listens on (port 1 is reserved) so OrderLinkerModule
// constructs normally but every remote lookup fails - exercising this service's own fail-open behavior
// without needing a live ordering-service, matching this task's definition of done.
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ordering.service.url=http://localhost:1")
public class AccountApplicationTest {

    private static final String JSONAPI = "application/vnd.api+json";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    public void adminCanCreateAccount() {
        String body = "{\"data\":{\"type\":\"account\",\"id\":\"101\",\"attributes\":{"
                + "\"name\":\"New Account\",\"email\":\"new@example.com\",\"plan\":\"free\"}}}";
        ResponseEntity<String> response = post(body, "admin");
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    public void viewerCannotCreateAccount() {
        String body = "{\"data\":{\"type\":\"account\",\"id\":\"102\",\"attributes\":{"
                + "\"name\":\"Blocked Account\",\"email\":\"blocked@example.com\",\"plan\":\"free\"}}}";
        ResponseEntity<String> response = post(body, "viewer");
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    public void blankNameFailsValidation() {
        String body = "{\"data\":{\"type\":\"account\",\"id\":\"103\",\"attributes\":{"
                + "\"name\":\"\",\"email\":\"blank-name@example.com\",\"plan\":\"free\"}}}";
        ResponseEntity<String> response = post(body, "admin");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    }

    @Test
    public void invalidEmailFailsValidation() {
        String body = "{\"data\":{\"type\":\"account\",\"id\":\"104\",\"attributes\":{"
                + "\"name\":\"Bad Email\",\"email\":\"not-an-email\",\"plan\":\"free\"}}}";
        ResponseEntity<String> response = post(body, "admin");
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.getStatusCode());
    }

    @Test
    public void getAccountByIdReturnsExpectedFields() {
        ResponseEntity<String> response = restTemplate.getForEntity("/account/1", String.class);
        assertEquals(200, response.getStatusCodeValue());
        String responseBody = response.getBody();
        assertTrue(responseBody.contains("\"type\":\"account\""));
        assertTrue(responseBody.contains("\"id\":\"1\""));
        assertTrue(responseBody.contains("Alice Nguyen"));
        assertTrue(responseBody.contains("alice@example.com"));
        assertTrue(responseBody.contains("\"plan\":\"pro\""));
    }

    private ResponseEntity<String> post(String body, String mockRole) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", JSONAPI);
        headers.set("X-Mock-Role", mockRole);
        return restTemplate.postForEntity("/account", new HttpEntity<>(body, headers), String.class);
    }
}
