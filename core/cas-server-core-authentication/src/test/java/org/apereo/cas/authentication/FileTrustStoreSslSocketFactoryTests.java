package org.apereo.cas.authentication;

import org.apereo.cas.configuration.model.core.authentication.HttpClientProperties;
import org.apereo.cas.util.http.SimpleHttpClient;
import org.apereo.cas.util.http.SimpleHttpClientFactoryBean;

import lombok.SneakyThrows;
import lombok.val;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@code FileTrustStoreSslSocketFactory} class, checking for self-signed
 * and missing certificates via a local truststore.
 *
 * @author Misagh Moayyed
 * @since 4.1.0
 */
@Tag("FileSystem")
public class FileTrustStoreSslSocketFactoryTests {

    @Nested
    @SuppressWarnings("ClassCanBeStatic")
    public class SslSocketFactoryTests {
        private static final ClassPathResource RESOURCE = new ClassPathResource("truststore.jks");

        private static final ClassPathResource RESOURCE_P12 = new ClassPathResource("truststore.p12");

        @SneakyThrows
        private static SSLConnectionSocketFactory sslFactory(final Resource resource,
                                                             final String password,
                                                             final String trustStoreType) {
            return new SSLConnectionSocketFactory(
                new DefaultCasSSLContext(resource, password, trustStoreType,
                    new HttpClientProperties(), NoopHostnameVerifier.INSTANCE).getSslContext());
        }

        private static SSLConnectionSocketFactory sslFactory() {
            return sslFactory(RESOURCE, "changeit", "JKS");
        }

        private static SimpleHttpClient getSimpleHttpClient(final SSLConnectionSocketFactory sslConnectionSocketFactory) {
            val clientFactory = new SimpleHttpClientFactoryBean();
            clientFactory.setSslSocketFactory(sslConnectionSocketFactory);
            val client = clientFactory.getObject();
            assertNotNull(client);
            return client;
        }

        @Test
        public void verifyTrustStoreLoadingSuccessfullyWithCertAvailable() {
            val client = getSimpleHttpClient(sslFactory());
            assertTrue(client.isValidEndPoint("https://self-signed.badssl.com"));
        }

        @Test
        public void verifyTrustStoreNotFound() {
            assertThrows(IOException.class, () -> sslFactory(new FileSystemResource("test.jks"), "changeit", "JKS"));
        }

        @Test
        public void verifyTrustStoreBadPassword() {
            assertThrows(IOException.class, () -> sslFactory(RESOURCE, "invalid", "JKS"));
        }

        @Test
        public void verifyTrustStoreType() {
            val client = getSimpleHttpClient(sslFactory(RESOURCE_P12, "changeit", "PKCS12"));
            assertTrue(client.isValidEndPoint("https://www.google.com"));
        }

        @Test
        public void verifyTrustStoreLoadingSuccessfullyForValidEndpointWithNoCert() {
            val client = getSimpleHttpClient(sslFactory());
            assertTrue(client.isValidEndPoint("https://www.google.com"));
        }

        @Test
        public void verifyTrustStoreLoadingSuccessfullyWihInsecureEndpoint() {
            val client = getSimpleHttpClient(sslFactory());
            assertTrue(client.isValidEndPoint("http://wikipedia.org"));
        }
    }

    @Nested
    @SpringBootTest(classes = DefaultCasSSLContextTests.SharedTestConfiguration.class,
        properties = "cas.http-client.trust-store.file=classpath:truststore.jks")
    @SuppressWarnings("ClassCanBeStatic")
    public class DefaultSslContext {
        @Autowired
        @Qualifier(CasSSLContext.BEAN_NAME)
        private CasSSLContext casSslContext;

        @Test
        public void verifyOperation() throws Exception {
            assertNotNull(casSslContext.getTrustManagerFactory());
            assertNotNull(DefaultCasSSLContextTests.SharedTestConfiguration.contactUrl("https://self-signed.badssl.com", casSslContext));
        }
    }
}
