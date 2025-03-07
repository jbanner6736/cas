package org.apereo.cas;

import org.apereo.cas.impl.account.MongoDbPasswordlessUserAccountStoreTests;
import org.apereo.cas.impl.tokens.MongoDbPasswordlessTokenRepositoryTests;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * This is {@link AllTestsSuite}.
 *
 * @author Misagh Moayyed
 * @since 6.0.0-RC3
 */
@SelectClasses({
    MongoDbPasswordlessUserAccountStoreTests.class,
    MongoDbPasswordlessTokenRepositoryTests.class
})
@Suite
public class AllTestsSuite {
}
