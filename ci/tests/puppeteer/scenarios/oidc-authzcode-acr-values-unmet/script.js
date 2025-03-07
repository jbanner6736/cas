const puppeteer = require('puppeteer');
const cas = require('../../cas.js');
const assert = require('assert');

(async () => {
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);

    const redirectUrl = "https://apereo.github.io";
    let url = `https://localhost:8443/cas/oidc/authorize?response_type=code&`;
    url += `client_id=client&scope=openid%20email%20profile%20address%20phone&redirect_uri=${redirectUrl}`;
    url += `&nonce=3d3a7457f9ad3&state=1735fd6c43c14&acr_values=mfa-gauth`;
    console.log(`Navigating to ${url}`);
    await cas.goto(page, url);
    await page.waitForTimeout(1000);
    await cas.loginWith(page, "casuser", "Mellon");
    await page.waitForTimeout(2000);
    console.log(`Page URL: ${await page.url()}`);
    assert(await page.url() === "https://apereo.github.io/?error=unmet_authentication_requirements");
    await browser.close();
})();
