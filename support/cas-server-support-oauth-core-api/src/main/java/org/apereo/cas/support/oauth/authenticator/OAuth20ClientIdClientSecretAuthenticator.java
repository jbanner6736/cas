package org.apereo.cas.support.oauth.authenticator;

import org.apereo.cas.audit.AuditableContext;
import org.apereo.cas.audit.AuditableExecution;
import org.apereo.cas.authentication.principal.NullPrincipal;
import org.apereo.cas.authentication.principal.PrincipalResolver;
import org.apereo.cas.authentication.principal.ServiceFactory;
import org.apereo.cas.authentication.principal.WebApplicationService;
import org.apereo.cas.services.RegisteredServiceAttributeReleasePolicyContext;
import org.apereo.cas.services.RegisteredServiceUsernameProviderContext;
import org.apereo.cas.services.ServicesManager;
import org.apereo.cas.support.oauth.OAuth20Constants;
import org.apereo.cas.support.oauth.OAuth20GrantTypes;
import org.apereo.cas.support.oauth.services.OAuthRegisteredService;
import org.apereo.cas.support.oauth.util.OAuth20Utils;
import org.apereo.cas.support.oauth.validator.OAuth20ClientSecretValidator;
import org.apereo.cas.support.oauth.web.OAuth20RequestParameterResolver;
import org.apereo.cas.ticket.code.OAuth20Code;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.apereo.cas.util.function.FunctionUtils;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.credentials.UsernamePasswordCredentials;
import org.pac4j.core.credentials.authenticator.Authenticator;
import org.pac4j.core.exception.CredentialsException;
import org.pac4j.core.profile.CommonProfile;

import java.util.Map;
import java.util.Optional;

/**
 * Authenticator for client credentials authentication.
 *
 * @author Jerome Leleu
 * @since 5.0.0
 */
@Slf4j
@RequiredArgsConstructor
@Getter
public class OAuth20ClientIdClientSecretAuthenticator implements Authenticator {
    private final ServicesManager servicesManager;

    private final ServiceFactory<WebApplicationService> webApplicationServiceServiceFactory;

    private final AuditableExecution registeredServiceAccessStrategyEnforcer;

    private final TicketRegistry ticketRegistry;

    private final PrincipalResolver principalResolver;

    private final OAuth20RequestParameterResolver requestParameterResolver;

    private final OAuth20ClientSecretValidator clientSecretValidator;

    @Override
    public Optional<Credentials> validate(final CallContext callContext, final Credentials credentials) throws CredentialsException {
        LOGGER.debug("Authenticating credential [{}]", credentials);
        val upc = (UsernamePasswordCredentials) credentials;
        val id = upc.getUsername();
        val registeredService = OAuth20Utils.getRegisteredOAuthServiceByClientId(this.servicesManager, id);
        val audit = AuditableContext.builder()
            .registeredService(registeredService)
            .build();
        val accessResult = registeredServiceAccessStrategyEnforcer.execute(audit);

        if (!accessResult.isExecutionFailure() && canAuthenticate(callContext)) {
            val service = webApplicationServiceServiceFactory.createService(registeredService.getServiceId());
            validateCredentials(upc, registeredService, callContext);

            val credential = new OAuth20ClientIdClientSecretCredential(upc.getUsername(), upc.getPassword());
            val principal = principalResolver.resolve(credential);

            val context = RegisteredServiceAttributeReleasePolicyContext.builder()
                .registeredService(registeredService)
                .service(service)
                .principal(principal)
                .build();
            val attributes = registeredService.getAttributeReleasePolicy().getAttributes(context);

            val profile = new CommonProfile();
            if (principal instanceof NullPrincipal) {
                LOGGER.debug("No principal was resolved. Falling back to the username [{}] from the credentials.", id);
                profile.setId(id);
            } else {

                val usernameContext = RegisteredServiceUsernameProviderContext.builder()
                    .registeredService(registeredService)
                    .service(service)
                    .principal(principal)
                    .build();
                val username = registeredService.getUsernameAttributeProvider().resolveUsername(usernameContext);
                profile.setId(username);
            }
            profile.addAttribute(OAuth20Constants.CLIENT_ID, id);
            LOGGER.debug("Created profile id [{}]", profile.getId());
            profile.addAttributes((Map) attributes);
            LOGGER.debug("Authenticated user profile [{}]", profile);
            credentials.setUserProfile(profile);
            return Optional.of(credentials);
        }
        return Optional.empty();
    }

    protected void validateCredentials(final UsernamePasswordCredentials credentials,
                                       final OAuthRegisteredService registeredService,
                                       final CallContext callContext) {
        if (!clientSecretValidator.validate(registeredService, credentials.getPassword())) {
            throw new CredentialsException("Invalid client credentials provided registered service: " + registeredService.getName());
        }
    }

    protected boolean canAuthenticate(final CallContext callContext) {
        val context = callContext.webContext();
        val grantType = context.getRequestParameter(OAuth20Constants.GRANT_TYPE);

        if (grantType.isPresent() && OAuth20Utils.isGrantType(grantType.get(), OAuth20GrantTypes.PASSWORD)) {
            LOGGER.debug("Skipping Client credential authentication to use password authentication");
            return false;
        }

        if (grantType.isPresent()
            && OAuth20Utils.isGrantType(grantType.get(), OAuth20GrantTypes.REFRESH_TOKEN)
            && context.getRequestParameter(OAuth20Constants.CLIENT_ID).isPresent()
            && context.getRequestParameter(OAuth20Constants.CLIENT_SECRET).isEmpty()) {
            LOGGER.debug("Skipping client credential authentication to use refresh token authentication");
            return false;
        }

        val code = context.getRequestParameter(OAuth20Constants.CODE);

        if (code.isPresent()) {
            LOGGER.debug("Checking if the OAuth code issued contains code challenge");
            val token = FunctionUtils.doAndHandle(() -> {
                val state = ticketRegistry.getTicket(code.get(), OAuth20Code.class);
                return state == null || state.isExpired() ? null : state;
            });
            
            if (token != null && StringUtils.isNotEmpty(token.getCodeChallenge())) {
                LOGGER.debug("The OAuth code [{}] issued contains code challenge which requires PKCE Authentication", code.get());
                return false;
            }
        }
        return true;
    }
}
