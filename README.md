# Wealth Platform: Hack Finance

Plataforma patrimonial proactiva para clientes retail. Un motor determinista hace todos los cálculos; Claude no calcula: recibe el JSON del motor y lo explica. Un verificador comprueba que cada cifra del texto sale de ese JSON y, si la verificación falla, se muestra una plantilla fija. Además, el cliente puede hablar por voz con un asesor de IA (Alex, sobre ElevenLabs) que consulta su cartera en tiempo real.

## Demo en 2 minutos

Con los servicios arrancados (ver [Ejecución](#ejecución)):

1. Abre `http://localhost:3000/irpf` y pulsa **Calcular resumen**. Con las 5 ventas de ejemplo de 2026 sale una cuota del ahorro de **4.722,60 €** (ganancias 27.400 €, pérdidas 2.840 €, 1.500 € compensados de 2023, base 23.060 €). La venta de UK Gilts aparece tachada: la regla de los 2 meses difiere esa pérdida de 350 €.
2. Cambia el precio de una venta y vuelve a calcular: el motor recalcula y Claude explica el nuevo resultado.
3. Abre `/pignoracion`, pulsa **Cargar contrato de ejemplo** y extrae los términos. Claude devuelve LTV máximo 70 %, aviso de margen al 80 % y 5 días hábiles, cada valor con su cita literal del contrato.
4. Vuelve a `/`, pulsa el botón de voz y luego **Empezar a hablar**. Pregunta "¿cómo está mi cartera?": Alex consulta la cartera PF-001 en el backend y responde por voz, con la transcripción en pantalla.

## Módulos

| Módulo | Ruta | Qué hace |
| --- | --- | --- |
| 1. Rebalanceador | `/` y `/transferencias` | Reparte aportaciones y rebalancea hacia los pesos objetivo |
| 2. Optimizador fiscal | `/fiscal` | FIFO estricto (art. 37.2 LIRPF) y regla de los 2 meses (art. 33.5 LIRPF) |
| 3. Simulador IRPF | `/irpf` | Simula una venta y genera el resumen fiscal anual de fondos y ETFs: base y cuota del ahorro, compensación de pérdidas de 4 años, descarga en CSV |
| 4. Gestor de pignoración | `/pignoracion` | LTV, haircuts, test de estrés y extracción de términos de un contrato con Claude |
| 5. Asesor de voz | Botón de voz en `/` | Conversación de voz en el navegador con un agente de ElevenLabs que lee la cartera y las fichas de fondos del backend |

## Cómo usamos la IA

Toda llamada a Claude está en el servicio Python (`ai-and-ingest-python`). Hay dos usos:

- **Explicar resultados** (módulos 3 y 4). Claude recibe solo el JSON del motor y escribe la explicación. `verificador_cifras.py` extrae cada cifra del texto, en dígitos o en letra, y la compara con el JSON. Si alguna no está, se le pide a Claude que reescriba. Si vuelve a fallar o la API no responde, se devuelve una plantilla determinista: la pantalla nunca se queda sin respuesta.
- **Extraer términos de un contrato** (módulo 4). Con *tool use* y un esquema JSON obligatorio, Claude devuelve LTV, umbral de aviso, plazo y haircuts, cada uno con la cita literal que lo respalda. El extractor comprueba que la cita existe en el contrato y que el valor coincide con el número de esa cita. Los resultados se cachean para no repetir llamadas.

Modelo por defecto: `claude-haiku-5-5` (cambiable con la variable `CLAUDE_MODEL`).

### Asesor de voz (módulo 5)

- El navegador abre una sesión de voz WebRTC con el SDK `@elevenlabs/react`. El agente es **privado**: el frontend pide al backend un token de conversación de un solo uso (`GET /api/v1/voice/web-session?userId=CLI-001`) y la API key de ElevenLabs nunca sale del servidor. El endpoint limita a 30 sesiones por hora y devuelve 404 si el cliente no existe.
- El agente no calcula ni inventa cifras: usa dos herramientas que llaman al backend, `get_portfolio_context` (`GET /api/v1/telephony/agent-context/{userId}`) y `get_fund_dfi` (`GET /api/v1/telephony/fund-dfi`). Ambas exigen la cabecera secreta `X-Telephony-Secret`; sin secreto configurado el backend las cierra (503).
- La configuración del agente (prompt, voz y herramientas) vive en el panel de ElevenLabs. Detalle en [`docs/ELEVENLABS_VOICE_AGENT.md`](docs/ELEVENLABS_VOICE_AGENT.md).

## Requisitos

- Java 21 y Maven (backend, Spring Boot 3.5.16).
- Node.js 20 y npm (frontend, Next.js 14.2.5). `npm` avisa de que una dependencia del SDK de voz prefiere Node 22: es solo un aviso y funciona con Node 20.
- Python 3.12 con `venv` (servicio de IA).
- Una clave de API de Anthropic, solo para las explicaciones y la extracción. Sin ella, la app funciona y muestra las plantillas.
- Solo para el asesor de voz: una cuenta de ElevenLabs con un agente configurado y [ngrok](https://ngrok.com/) para que el agente alcance el backend local. Sin ellos, el resto de la app funciona y el botón de voz muestra un error claro.

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

**Asesor de voz (opcional):** variables de entorno en la terminal del backend, nunca en el repositorio:

| Variable | Descripción |
| --- | --- |
| `ELEVENLABS_API_KEY` | API key de ElevenLabs con permisos ElevenAgents (Escribir), De texto a voz (Acceso) y Voces (Leído) |
| `ELEVENLABS_AGENT_ID` | ID del agente (`agent_...`) |
| `TELEPHONY_WEBHOOK_SECRET` | Secreto compartido con las herramientas del agente (cabecera `X-Telephony-Secret`) |

En PowerShell, sin dejar las claves en el historial:

```
$env:ELEVENLABS_API_KEY = Read-Host "API key de ElevenLabs"
$env:ELEVENLABS_AGENT_ID = Read-Host "ID del agente"
$env:TELEPHONY_WEBHOOK_SECRET = Read-Host "Secreto del webhook"
```

## Ejecución

Tres terminales para la app; una cuarta para la voz:

1. **Backend:** `cd backend-core-java` y `mvn spring-boot:run` (puerto 8080). Arráncalo desde esa carpeta: lee los datos de prueba de `../bank-simulator-mocks`. Si vas a usar la voz, define antes las variables de la sección anterior en esta misma terminal.
2. **Servicio de IA:** `cd ai-and-ingest-python`, activa el venv y ejecuta `uvicorn app.main:app --port 8000`. Arráncalo desde esa carpeta para que lea el `.env`. Comprobación: `http://localhost:8000/health`.
3. **Frontend:** `cd frontend-web` y `npm run dev`. Abre `http://localhost:3000`.
4. **Túnel para la voz:** `ngrok http 8080 --url=<tu-dominio-ngrok>`. Las herramientas del agente en ElevenLabs deben apuntar a ese dominio.

Comprobación de la voz: `GET http://localhost:8080/api/v1/voice/web-session?userId=CLI-001` debe responder `200` con un `conversationToken`.

## Pruebas

| Qué | Comando | Resultado esperado |
| --- | --- | --- |
| Backend | `cd backend-core-java` y `mvn test` | 251 tests, `BUILD SUCCESS` |
| Servicio de IA | `cd ai-and-ingest-python`, venv activo, `python -m pytest tests` | 38 passed |
| Frontend | `cd frontend-web` y `npm run build` | Compilación sin errores |

Ninguna prueba automática llama a Claude ni a ElevenLabs, así que no consumen crédito: los tests de voz usan un servidor de ElevenLabs falso en local. Entre los tests de IA hay casos adversariales: respuestas simuladas que inventan una cifra o una cita deben acabar rechazadas o en plantilla.

Usa `python -m pytest tests` y no `pytest` a secas: en `app/advisor_llm/` hay dos scripts `test_pledge_*_manual.py` que llaman a la API real y no forman parte de la suite.

## Estructura

```
backend-core-java/        Motor determinista (Spring Boot): módulos 1 a 4 y endpoints del asesor de voz
ai-and-ingest-python/     Servicio de IA (FastAPI + SDK de Anthropic)
frontend-web/             Interfaz (Next.js + React + Tailwind)
bank-simulator-mocks/     Datos sintéticos: lotes fiscales, carteras, contrato de ejemplo
contracts/                Contratos compartidos entre servicios (OpenAPI, JSON)
docs/                     Documentación del agente de voz
```

## Limitaciones conocidas

- El verificador garantiza que toda cifra del texto existe en el JSON del motor, pero no que se atribuya a la magnitud correcta.
- Las respuestas del asesor de voz no pasan por el verificador de cifras: el agente solo recibe datos de las herramientas del backend y su prompt le prohíbe dar cifras que no vengan de ellas, pero no hay comprobación automática de lo que dice.
- Las fichas de fondos (TER, SRRI) que consulta el asesor de voz son ilustrativas.
- La llamada telefónica saliente (Twilio) está implementada en el backend pero no se usa en la demo: requiere un número de Twilio verificado.
- El resumen fiscal es un borrador orientativo de la parte de inversiones, no la declaración completa ni asesoramiento fiscal.
- Los datos de la demo son sintéticos.
- La aplicación se ejecuta en local.
