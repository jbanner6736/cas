package org.apereo.cas.acct.webflow;

import org.apereo.cas.config.CasAccountManagementWebflowConfiguration;
import org.apereo.cas.web.flow.BaseWebflowConfigurerTests;
import org.apereo.cas.web.flow.CasWebflowConstants;

import lombok.val;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.webflow.context.ExternalContextHolder;
import org.springframework.webflow.context.servlet.ServletExternalContext;
import org.springframework.webflow.execution.Action;
import org.springframework.webflow.execution.RequestContextHolder;
import org.springframework.webflow.test.MockRequestContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This is {@link LoadAccountRegistrationPropertiesActionTests}.
 *
 * @author Misagh Moayyed
 * @since 6.5.0
 */
@Tag("WebflowAccountActions")
@Import(CasAccountManagementWebflowConfiguration.class)
public class LoadAccountRegistrationPropertiesActionTests extends BaseWebflowConfigurerTests {

    @Autowired
    @Qualifier(CasWebflowConstants.ACTION_ID_LOAD_ACCOUNT_REGISTRATION_PROPERTIES)
    private Action loadAccountRegistrationPropertiesAction;

    @Test
    public void verifyOperation() throws Exception {
        val context = new MockRequestContext();
        val request = new MockHttpServletRequest();
        val response = new MockHttpServletResponse();
        context.setExternalContext(new ServletExternalContext(new MockServletContext(), request, response));
        RequestContextHolder.setRequestContext(context);
        ExternalContextHolder.setExternalContext(context.getExternalContext());
        assertNull(loadAccountRegistrationPropertiesAction.execute(context));
        assertTrue(context.getFlowScope().contains("registrationProperties"));
    }
}
