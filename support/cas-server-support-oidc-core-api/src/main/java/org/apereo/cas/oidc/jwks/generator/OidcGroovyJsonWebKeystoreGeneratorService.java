package org.apereo.cas.oidc.jwks.generator;

import org.apereo.cas.util.scripting.WatchableGroovyScriptResource;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.jose4j.jwk.JsonWebKeySet;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * This is {@link OidcGroovyJsonWebKeystoreGeneratorService}.
 *
 * @author Misagh Moayyed
 * @since 6.5.0
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OidcGroovyJsonWebKeystoreGeneratorService implements OidcJsonWebKeystoreGeneratorService {
    private final WatchableGroovyScriptResource watchableScript;

    public OidcGroovyJsonWebKeystoreGeneratorService(final Resource watchableScript) {
        this.watchableScript = new WatchableGroovyScriptResource(watchableScript);
    }

    @Override
    public Optional<Resource> find() {
        val args = new Object[]{LOGGER};
        val result = watchableScript.execute("find", JsonWebKeySet.class, args);
        LOGGER.debug("Received JWKS resource from [{}] as [{}]", watchableScript, result);
        return Optional.ofNullable(result).map(OidcJsonWebKeystoreGeneratorService::toResource);
    }

    @Override
    public Resource generate() {
        val args = new Object[]{LOGGER};
        val result = watchableScript.execute(args, String.class);
        LOGGER.debug("Received payload result from [{}] as [{}]", watchableScript, result);
        return new ByteArrayResource(result.getBytes(StandardCharsets.UTF_8), "OIDC JWKS");
    }

    @Override
    public JsonWebKeySet store(final JsonWebKeySet jsonWebKeySet) throws Exception {
        val args = new Object[]{jsonWebKeySet, LOGGER};
        val result = watchableScript.execute("store", JsonWebKeySet.class, args);
        LOGGER.debug("Received payload result from [{}] as [{}]", watchableScript, result);
        return result;
    }
}
