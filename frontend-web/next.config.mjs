/** @type {import('next').NextConfig} */
const nextConfig = {
  // Cuando el backend esté listo, aquí configuraremos los rewrites
  // para redirigir /api/* al servidor Java de Raúl:
  // async rewrites() {
  //   return [{ source: "/api/:path*", destination: "http://localhost:8080/api/:path*" }];
  // },
};

export default nextConfig;
