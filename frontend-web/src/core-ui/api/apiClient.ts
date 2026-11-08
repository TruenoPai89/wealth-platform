/**
 * apiClient.ts
 *
 * Cliente HTTP base para los endpoints REST del backend Java (Raúl).
 *
 * ESTADO ACTUAL: PENDIENTE — El contrato OpenAPI (/contracts/openapi-core.yaml)
 * aún no ha sido definido. Este archivo es un esqueleto preparado.
 *
 * CUANDO EL CONTRATO ESTÉ LISTO:
 *   1. Definir BASE_URL en .env.local: NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
 *   2. Implementar los métodos get/post/put/delete con fetch nativo de Next.js.
 *   3. Reemplazar los imports de mockPortfolioService por los de este cliente.
 *
 * @module core-ui/api/apiClient
 */

const BASE_URL = process.env["NEXT_PUBLIC_API_BASE_URL"] ?? "";

/**
 * Wrapper de fetch tipado. Lanza un error descriptivo si la respuesta no es 2xx.
 * Preparado para adjuntar JWT de autenticación cuando el backend lo requiera.
 */
async function request<T>(
  endpoint: string,
  options?: RequestInit,
): Promise<T> {
  if (!BASE_URL) {
    throw new Error(
      "[apiClient] NEXT_PUBLIC_API_BASE_URL no está definida. " +
        "Usa mockPortfolioService mientras el backend no esté disponible.",
    );
  }

  const url = `${BASE_URL}${endpoint}`;

  const response = await fetch(url, {
    headers: {
      "Content-Type": "application/json",
      // Authorization: `Bearer ${getAuthToken()}`, // TODO: activar con auth
      ...options?.headers,
    },
    ...options,
  });

  if (!response.ok) {
    throw new Error(
      `[apiClient] Error ${response.status} ${response.statusText} → ${url}`,
    );
  }

  return response.json() as Promise<T>;
}

// ─── Helpers públicos (descomentarlos cuando el contrato esté listo) ─────────

export const apiClient = {
  get: <T>(endpoint: string, options?: Omit<RequestInit, "method">) =>
    request<T>(endpoint, { ...options, method: "GET" }),

  post: <T>(
    endpoint: string,
    body: unknown,
    options?: Omit<RequestInit, "method" | "body">,
  ) =>
    request<T>(endpoint, {
      ...options,
      method: "POST",
      body: JSON.stringify(body),
    }),

  put: <T>(
    endpoint: string,
    body: unknown,
    options?: Omit<RequestInit, "method" | "body">,
  ) =>
    request<T>(endpoint, {
      ...options,
      method: "PUT",
      body: JSON.stringify(body),
    }),
};
