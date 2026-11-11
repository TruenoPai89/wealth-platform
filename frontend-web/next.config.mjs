/** @type {import('next').NextConfig} */
const nextConfig = {
  // ─── Monorepo: directorio raíz del proyecto Next.js ──────────────────────
  // Vercel lo detecta automáticamente cuando "Root Directory" se configura en
  // el panel a "frontend-web". No es necesario declararlo aquí, pero lo
  // dejamos como documentación del layout del monorepo.

  // ─── Imágenes externas ────────────────────────────────────────────────────
  // Añade aquí los dominios de los que se sirven imágenes con <Image> de Next.
  // Actualmente no hay ninguno (todos los assets son SVG inline o CSS).
  // Descomenta y añade si se integran logos externos, avatares, etc.
  images: {
    remotePatterns: [
      // Ejemplo: CDN de avatares o logos de fondos de terceros.
      // { protocol: "https", hostname: "cdn.example.com" },
    ],
  },

  // ─── Headers de seguridad (Vercel los sirve en cada respuesta) ───────────
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: [
          // Evita que la app sea embebida en iframes de terceros (clickjacking)
          { key: "X-Frame-Options", value: "DENY" },
          // Fuerza el tipo MIME declarado (evita sniffing en MSIE/Edge antiguo)
          { key: "X-Content-Type-Options", value: "nosniff" },
          // No envía el origen completo al navegar a sitios externos
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          // Desactiva funcionalidades del navegador no usadas por la plataforma
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=()",
          },
        ],
      },
    ];
  },

  // ─── Rewrites opcionales ──────────────────────────────────────────────────
  // Si el backend Java y el servicio Python están desplegados en URLs fijas,
  // puedes proxiar sus rutas aquí para evitar CORS desde el browser.
  // Descomenta y ajusta las URLs de destino cuando estén disponibles en producción.
  //
  // async rewrites() {
  //   return [
  //     {
  //       // Backend Java (Módulos 1, 2, 3 y 4)
  //       source: "/api/v1/:path*",
  //       destination: `${process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080/api/v1"}/:path*`,
  //     },
  //     {
  //       // Servicio Python de IA (Módulos 3 y 4 — explicaciones Claude)
  //       source: "/ai/:path*",
  //       destination: `${process.env.NEXT_PUBLIC_AI_BASE_URL ?? "http://localhost:8000"}/:path*`,
  //     },
  //   ];
  // },
};

export default nextConfig;
