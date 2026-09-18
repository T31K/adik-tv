const fs = require("node:fs");
const path = require("node:path");

const BASE_URL = "https://arvio.tv";
const INDEXNOW_ENDPOINT = "https://api.indexnow.org/indexnow";
const INDEXNOW_KEY = "3bd98309ff4fc815859c2ad382610b879cdf68526b2750e333a15a6b0e4289ee";
const KEY_LOCATION = `${BASE_URL}/${INDEXNOW_KEY}.txt`;

function readSitemapUrls(publishDir) {
  const sitemapPath = path.join(publishDir, "sitemap.xml");
  const sitemap = fs.readFileSync(sitemapPath, "utf8");
  const urls = [...sitemap.matchAll(/<loc>([^<]+)<\/loc>/gu)]
    .map((match) => match[1].trim())
    .filter((url) => {
      try {
        return new URL(url).origin === BASE_URL;
      } catch {
        return false;
      }
    });

  return [...new Set(urls)];
}

async function submitIndexNow({ publishDir, fetchImpl = fetch, dryRun = false }) {
  const keyFile = path.join(publishDir, `${INDEXNOW_KEY}.txt`);
  const hostedKey = fs.readFileSync(keyFile, "utf8").trim();
  if (hostedKey !== INDEXNOW_KEY) {
    throw new Error("The IndexNow verification file does not contain the configured key.");
  }

  const urlList = readSitemapUrls(publishDir);
  if (urlList.length === 0 || urlList.length > 10_000) {
    throw new Error(`Expected 1-10,000 sitemap URLs, found ${urlList.length}.`);
  }

  const payload = {
    host: new URL(BASE_URL).host,
    key: INDEXNOW_KEY,
    keyLocation: KEY_LOCATION,
    urlList,
  };

  if (dryRun) return payload;

  const response = await fetchImpl(INDEXNOW_ENDPOINT, {
    method: "POST",
    headers: { "content-type": "application/json; charset=utf-8" },
    body: JSON.stringify(payload),
  });

  if (response.status !== 200 && response.status !== 202) {
    const responseBody = (await response.text()).slice(0, 500);
    throw new Error(`IndexNow returned HTTP ${response.status}: ${responseBody}`);
  }

  return { status: response.status, urlCount: urlList.length };
}

module.exports = { readSitemapUrls, submitIndexNow };
