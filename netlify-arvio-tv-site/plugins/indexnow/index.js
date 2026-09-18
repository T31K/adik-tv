const { submitIndexNow } = require("./submit-indexnow");

module.exports = {
  onSuccess: async ({ constants }) => {
    const isProduction = process.env.CONTEXT === "production";
    if (constants.IS_LOCAL || !isProduction) {
      console.log("IndexNow skipped outside a production deployment.");
      return;
    }

    try {
      const result = await submitIndexNow({ publishDir: constants.PUBLISH_DIR });
      console.log(`IndexNow accepted ${result.urlCount} URLs (HTTP ${result.status}).`);
    } catch (error) {
      // Search-engine availability must never block an otherwise healthy deployment.
      console.warn(`IndexNow notification failed: ${error.message}`);
    }
  },
};
