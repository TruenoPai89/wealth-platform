# Wealth Platform: Hack Finance

Plataforma patrimonial proactiva para clientes retail. Un motor determinista hace todos los cálculos; Claude no calcula: recibe el JSON del motor y lo explica. Un verificador comprueba que cada cifra del texto sale de ese JSON y, si la verificación falla, se muestra una plantilla fija.

## Demo en 2 minutos

Con los tres servicios arrancados (ver [Ejecución](#ejecución)):

1. Abre `http://localhost:3000/irpf` y pulsa **Calcular resumen**. Con las 5 ventas de ejemplo de 2026 sale una cuota del ahorro de **4.722,60 €** (ganancias 27.400 €, pérdidas 2.840 €, 1.500 € compensados de 2023, base 23.060 €). La venta de UK Gilts aparece tachada: la regla de los 2 meses difiere esa pérdida de 350 €.
2. Cambia el precio de una venta y vuelve a calcular: el motor recalcula y Claude explica el nuevo resultado.
3. Abre `/pignoracion`, pulsa **Cargar contrato de ejemplo** y extrae los términos. Claude devuelve LTV máximo 70 %, aviso de margen al 80 % y 5 días hábiles, cada valor con su cita literal del contrato.

## Módulos

| Módulo | Ruta | Qué hace |
| --- | --- | --- |
| 1. Rebalanceador | `/` y `/transferencias` | Reparte aportaciones y rebalancea hacia los pesos objetivo |
| 2. Optimizador fiscal | `/fiscal` | FIFO estricto (art. 37.2 LIRPF) y regla de los 2 meses (art. 33.5 LIRPF) |
| 3. Simulador IRPF | `/irpf` | Simula una venta y genera el resumen fiscal anual de fondos y ETFs: base y cuota del ahorro, compensación de pérdidas de 4 años, descarga en CSV |
| 4. Gestor de pignoración | `/pignoracion` | LTV, haircuts, test de estrés y extracción de términos de un contrato con Claude |

## Cómo usamos la IA

Toda llamada a Claude está en el servicio Python (`ai-and-ingest-python`). Hay dos usos:

- **Explicar resultados** (módulos 3 y 4). Claude recibe solo el JSON del motor y escribe la explicación. `verificador_cifras.py` extrae cada cifra del texto, en dígitos o en letra, y la compara con el JSON. Si alguna no está, se le pide a Claude que reescriba. Si vuelve a fallar o la API no responde, se devuelve una plantilla determinista: la pantalla nunca se queda sin respuesta.
- **Extraer términos de un contrato** (módulo 4). Con *tool use* y un esquema JSON obligatorio, Claude devuelve LTV, umbral de aviso, plazo y haircuts, cada uno con la cita literal que lo respalda. El extractor comprueba que la cita existe en el contrato y que el valor coincide con el número de esa cita. Los resultados se cachean para no repetir llamadas.

Modelo por defecto: `claude-haiku-5-5` (cambiable con la variable `CLAUDE_MODEL`).

## Requisitos

- Java 21 y Maven (backend, Spring Boot 3.5.16).
- Node.js 20 y npm (frontend, Next.js 14.2.5).
- Python 3.12 con `venv` (servicio de IA).
- Una clave de API de Anthropic, solo para las explicaciones y la extracción. Sin ella, la app funciona y muestra las plantillas.

## Instalación

Desde la raíz del repo:

```
cd frontend-web
npm ci

cd ../ai-and-ingest-python
python -m venv .venv
.venv\Scripts\activate          # Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
```

El backend descarga sus dependencias la primera vez que se ejecuta Maven.

## Configuración

Copia los ficheros de ejemplo. Ninguno se sube a Git.

**Servicio de IA:** copia `ai-and-ingest-python/.env.example` a `ai-and-ingest-python/.env` y pon tu clave:

```
ANTHROPIC_API_KEY=tu-clave
```

**Frontend:** copia `frontend-web/.env.local.example` a `frontend-web/.env.local`. Los valores por defecto sirven para local:

```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api/v1
NEXT_PUBLIC_AI_BASE_URL=http://localhost:8000
NEXT_PUBLIC_USE_MOCK=false
```

## Ejecución

Tres terminales:

1. **Backend:** `cd backend-core-java` y `mvn spring-boot:run` (puerto 8080). Arráncalo desde esa carpeta: lee los datos de prueba de `../bank-simulator-mocks`.
2. **Servicio de IA:** `cd ai-and-ingest-python`, activa el venv y ejecuta `uvicorn app.main:app --port 8000`. Arráncalo desde esa carpeta para que lea el `.env`. Comprobación: `http://localhost:8000/health`.
3. **Frontend:** `cd frontend-web` y `npm run dev`. Abre `http://localhost:3000`.

## Pruebas

| Qué | Comando | Resultado esperado |
| --- | --- | --- |
| Backend | `cd backend-core-java` y `mvn test` | 216 tests, `BUILD SUCCESS` |
| Servicio de IA | `cd ai-and-ingest-python`, venv activo, `python -m pytest tests` | 38 passed |
| Frontend | `cd frontend-web` y `npm run build` | Compilación sin errores |

Ninguna prueba automática llama a Claude, así que no consumen crédito. Entre los tests de IA hay casos adversariales: respuestas simuladas que inventan una cifra o una cita deben acabar rechazadas o en plantilla.

Usa `python -m pytest tests` y no `pytest` a secas: en `app/advisor_llm/` hay dos scripts `test_pledge_*_manual.py` que llaman a la API real y no forman parte de la suite.

## Estructura

```
backend-core-java/        Motor determinista (Spring Boot): módulos 1 a 4
ai-and-ingest-python/     Servicio de IA (FastAPI + SDK de Anthropic)
frontend-web/             Interfaz (Next.js + React + Tailwind)
bank-simulator-mocks/     Datos sintéticos: lotes fiscales, carteras, contrato de ejemplo
contracts/                Contratos compartidos entre servicios (OpenAPI, JSON)
```

## Limitaciones conocidas

- El verificador garantiza que toda cifra del texto existe en el JSON del motor, pero no que se atribuya a la magnitud correcta.
- El resumen fiscal es un borrador orientativo de la parte de inversiones, no la declaración completa ni asesoramiento fiscal.
- Los datos de la demo son sintéticos.
- La aplicación se ejecuta en local.
