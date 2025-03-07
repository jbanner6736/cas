const puppeteer = require('puppeteer');
const cas = require('../../cas.js');
const assert = require('assert');

(async () => {
    let secret = process.env.DUO_REGISTRATION_SIGNING_KEY;
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);
    await cas.goto(page, "https://localhost:8443/cas/login?authn_method=mfa-duo");
    await cas.loginWith(page, "unknown", "Mellon");
    await page.waitForTimeout(3000);
    await cas.screenshot(page);
    let url = await page.url();
    console.log(url);
    assert(url.startsWith("https://httpbin.org/anything/1"));

    let content = await cas.textContent(page, "body pre");
    let payload = JSON.parse(content);
    console.log(payload);
    // remove the last character encoded as a "?"
    let principal = payload.args.principal.slice(0, -1);
    console.log(`Using principal ${principal}`);
    let decoded = await cas.verifyJwt(principal, secret, {
        algorithms: ["HS512"],
        complete: false
    });
    assert(decoded.sub === "unknown");
    assert(decoded.aud === "httpbin.org");
    assert(decoded.iss === "https://localhost:8443");
    await browser.close();

})();
