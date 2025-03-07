const puppeteer = require('puppeteer');
const assert = require('assert');
const cas = require('../../cas.js');

(async () => {
    const browser = await puppeteer.launch(cas.browserOptions());
    const page = await cas.newPage(browser);

    await cas.goto(page, "https://localhost:8443/cas/auth");
    await page.waitForTimeout(2000);
    let url = await page.url();
    console.log(`Page url: ${url}`);
    assert(url === "https://localhost:8443/cas/auth");
    await cas.loginWith(page, "casuser", "Mellon");
    await cas.assertCookie(page);
    await page.waitForTimeout(2000);
    await cas.goto(page, "https://localhost:8443/cas/off");
    await page.waitForTimeout(2000);
    await cas.assertCookie(page, false);

    await browser.close();
})();
