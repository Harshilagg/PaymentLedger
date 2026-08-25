import path from "node:path";
import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  turbopack: {
    root: path.join(__dirname),
  },
  // wallet-service has no CORS config (see SecurityConfig.java) and the brief rules out backend
  // changes, so requests are proxied same-origin through Next.js instead of calling it directly
  // from the browser.
  async rewrites() {
    const walletServiceUrl = process.env.WALLET_SERVICE_URL ?? "http://localhost:8081";
    return [
      {
        source: "/api/:path*",
        destination: `${walletServiceUrl}/:path*`,
      },
    ];
  },
};

export default nextConfig;
