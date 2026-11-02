/** @type {import('next').NextConfig} */
const nextConfig = {
  typedRoutes: true,
  async headers() {
    return [
      {
        // iOS reads this file to open invite links in the app. It has no extension, so it
        // needs its JSON type set explicitly.
        source: "/.well-known/apple-app-site-association",
        headers: [{ key: "Content-Type", value: "application/json" }]
      }
    ]
  }
}

export default nextConfig
