# Llamada de voz con el Asesor de IA (ElevenLabs + Twilio)

Rama: `ElevenLabs` · Estado: **implementado y probado contra un ElevenLabs FALSO; nunca se ha hecho una llamada real.**

## 1. Qué es

El cliente pulsa **«Llamar a mi Asesor de IA»** en el Dashboard, escribe su teléfono y el backend Java pide a
ElevenLabs Conversational AI (que usa Twilio) que le llame. Un agente de voz conversa con él y, durante la llamada,
consulta nuestro backend para conocer la cartera del cliente y las fichas DFI de los fondos.

```
Navegador (Next.js)                Backend Java (8080)                     ElevenLabs / Twilio
 VoiceAgentModal ──POST trigger-call──▶ TelephonyService ──POST outbound-call──▶ llama al teléfono
        ▲  └─GET calls/{id}/status──▶ (consulta estado real)  ◀──GET conversations/{id}
        │                                                                          │
        │                          ◀── GET agent-context/{userId}  (herramienta del agente, cabecera secreta)
        │                          ◀── GET fund-dfi?isin_or_name=  (herramienta del agente, cabecera secreta)
```

## 2. Qué hay hecho

### Backend (`backend-core-java/src/main/java/com/wealth/mod5_telephony/`)

| Fichero | Función |
|---|---|
| `TelephonyController` | Los 4 endpoints (ver 3). Errores como `{ "error": "..." }`, igual que el resto de controllers. |
| `TelephonyService` | Valida y normaliza el teléfono a E.164, lanza la llamada, consulta su estado y construye el contexto del agente. |
| `ElevenLabsClient` | Cliente HTTP (RestClient, mismo patrón que `OpenJevClient`). La clave viaja en `xi-api-key` y nunca se loguea. |
| `AgentContext` | Respuesta ultracompacta del webhook (~736 bytes). |
| `FundDfiService` | Fichas DFI por ISIN o nombre hablado. **Nunca inventa datos.** |
| `CallThrottle` | Freno anti-abuso: 60 s entre llamadas al mismo número y 20 llamadas/hora en total. |
| `TelephonyProperties` / `TelephonyException` | Configuración (`wealth.telephony.*`) y errores con su código HTTP. |

### Frontend (`frontend-web/src/`)

| Fichero | Función |
|---|---|
| `mod5-voice-ui/VoiceAgentModal.tsx` | Modal: teléfono → «Recibir llamada ahora» → «Marcando...» → «En llamada con tu Cartera...» → «Llamada finalizada». Consulta el estado real cada 2,5 s. |
| `mod5-voice-ui/VoiceOrb.tsx` | Indicador animado (anillos de timbre / barras de voz / check / cruz). |
| `core-ui/services/telephonyService.ts` | Llamadas al backend, normalización y enmascarado del teléfono, mensajes de error legibles. |
| `core-ui/types/telephony.ts` | Tipos espejo de los records Java. |
| `mod1-rebalancer-ui/RebalanceDashboard.tsx` | Botón destacado y montaje del modal (oculto en modo mock). |
| `app/globals.css` | Animaciones de la llamada (respetan `prefers-reduced-motion`). |

### Datos y configuración
- `bank-simulator-mocks/api-json-responses/fund_dfi.json`: fichas DFI **ILUSTRATIVAS** (TER y SRRI aproximados).
- `backend-core-java/src/main/resources/application.yml`: bloque `wealth.telephony` (solo lee variables de entorno).

## 3. Endpoints (base `/api/v1/telephony`)

| Método y ruta | Quién lo llama | Qué hace |
|---|---|---|
| `POST /trigger-call` body `{phoneNumber, userId}` | La UI | Lanza la llamada. **202** `{conversationId, status:"dialing"}`. |
| `GET /calls/{conversationId}/status` | La UI | `dialing` · `in_call` · `ended` · `failed` (normalizado desde `initiated/in-progress/processing/done/failed`). |
| `GET /agent-context/{userId}` | El agente de voz | Cartera, liquidez, riesgo, posiciones y agrupaciones. Cabecera `X-Telephony-Secret`. |
| `GET /fund-dfi?isin_or_name=` | El agente de voz | Ficha DFI (TER, SRRI, política). Cabecera `X-Telephony-Secret`. |

Códigos: 400 entrada inválida · 401 secreto incorrecto · 404 usuario/fondo no existe · 409 fondo ambiguo ·
429 demasiadas llamadas · 502 el proveedor rechazó la petición · 503 faltan credenciales de ElevenLabs.

Ejemplo de `agent-context` (CLI-001 → PF-001, total 130.000 €):
```json
{"userId":"CLI-001","portfolioId":"PF-001","currency":"EUR","total":130000,"cash":9500.00,"cashPct":7.3,
 "riskLevel":"agresivo","riskAssetsPct":70.0,
 "positions":[{"name":"iShares Core MSCI World","pct":29.2,"eur":38000}, "..."],
 "baskets":[{"id":"agrupacion-1","name":"Agrupación 1","mix":"iShares Core MSCI World 33.3%, ..."}]}
```

## 4. Variables de entorno (NUNCA en el repo)

| Variable | Para qué |
|---|---|
| `ELEVENLABS_API_KEY` | Clave de la API de ElevenLabs. |
| `ELEVENLABS_AGENT_ID` | Id del agente (`agent_...`). |
| `ELEVENLABS_PHONE_NUMBER_ID` | Id del número de Twilio importado en ElevenLabs (`phnum_...`). |
| `TELEPHONY_WEBHOOK_SECRET` | Secreto compartido para `X-Telephony-Secret`. Vacío = sin comprobación (**solo desarrollo local**). |

Sin las tres primeras, la app arranca igual y `trigger-call` responde 503 con un mensaje claro.
Genera el secreto con `openssl rand -hex 24`.

## 5. Cómo conectarlo a ElevenLabs (pendiente de hacer)

ElevenLabs no puede llamar a `localhost`: el backend necesita una URL pública HTTPS.

1. **En ElevenLabs:** crear el agente, y comprar o importar un número de Twilio (da el `phnum_...`).
2. **Arrancar el backend** con las 4 variables del punto 4 (`cd backend-core-java && mvn spring-boot:run`).
3. **Exponerlo:** `ngrok http 8080` (requiere `ngrok config add-authtoken ...` la primera vez). La URL cambia en cada reinicio.
4. **Crear dos herramientas Webhook en el agente** (sección Tools; los nombres exactos de los campos del panel pueden variar):

   | | `get_portfolio_context` | `get_fund_dfi` |
   |---|---|---|
   | URL | `https://<ngrok>/api/v1/telephony/agent-context/{user_id}` | `https://<ngrok>/api/v1/telephony/fund-dfi` |
   | Método | GET | GET |
   | Cabecera | `X-Telephony-Secret` = el secreto, como **Secret** | igual |
   | Parámetro | path `user_id` | query `isin_or_name` |

5. **Prompt del agente** (incluir `{{user_id}}` registra la variable dinámica, que el backend ya envía al lanzar la llamada):
   ```
   Al empezar la llamada, llama SIEMPRE a get_portfolio_context con user_id={{user_id}}.
   Usa solo los datos que devuelva; nunca inventes importes.
   El campo riskLevel es un indicador derivado de la cartera, NO el perfil MiFID del cliente.
   Si te preguntan por comisiones o riesgo de un fondo, usa get_fund_dfi. Si responde que no hay ficha,
   dilo tal cual. Eres un asistente informativo: no das asesoramiento personalizado.
   ```
6. **Probar** con el botón del Dashboard.

> **Por qué herramientas y no «webhook de personalización»:** el webhook de personalización de ElevenLabs que hemos
> leído en su documentación es para llamadas **entrantes**. En las salientes el agente tiene que pedir el contexto él
> mismo (de ahí la instrucción del prompt).

## 6. Cómo probarlo sin gastar nada

- **Tests automáticos:** `cd backend-core-java && mvn test` → 301 tests, entre ellos los de telefonía y voz (`mod5_telephony`). Usan un
  servidor HTTP local que hace de ElevenLabs falso.
- **UI con ElevenLabs falso:** se probó el recorrido Marcando → En llamada → Finalizada arrancando el backend con
  `--wealth.telephony.elevenlabs.base-url=http://127.0.0.1:<puerto>` apuntando a un servidor de mentira que no llama a
  nadie. Sin credenciales, el modal muestra el 503 del backend.
- **Frontend:** `cd frontend-web && npm run type-check`.

## 7. Decisiones de diseño (conviene que las valide el equipo)

- **«Nivel de riesgo» es derivado, NO el perfil MiFID.** Se calcula con el peso de renta variable + materias primas
  (<20 % conservador · <45 % moderado · <70 % dinámico · ≥70 % agresivo). PF-001 sale 70,0 % = «agresivo». Existe
  `bank-simulator-mocks/api-json-responses/client_mifid_profile.json` pero **está vacío**; si hay perfil real, usarlo.
- **Contexto ultracompacto a propósito:** ~736 bytes, sin ISIN ni costes base, posiciones redondeadas y agrupaciones
  como una sola cadena. Menos tokens = menor latencia de voz.
- **`fund-dfi` nunca inventa:** fondo desconocido → 404 con la lista de fondos reales; ambiguo → 409 con candidatos.
- **El proveedor no filtra datos:** los errores de ElevenLabs llegan a la UI como «HTTP 401» genérico, sin clave ni
  cuerpo. El teléfono se enmascara en logs (`+34******456`) y en pantalla.
- **`userId` ↔ cartera:** `wealth.telephony.portfolio-by-user` mapea `CLI-001 → PF-001`; sin entrada, el `userId` se
  interpreta como `portfolioId`.

## 8. Pendiente y riesgos conocidos

**Bloqueantes para producción**
- [ ] **Sin autenticación en `trigger-call`:** cualquiera podría hacer sonar teléfonos y gastar saldo. `CallThrottle`
      (en memoria, se pierde al reiniciar) es solo un parche. Falta auth y exigir que el teléfono sea el del cliente.
- [ ] **Secreto del webhook obligatorio en producción:** vacío = sin comprobación (pensado para desarrollo).
- [ ] **Datos DFI ficticios** (`fund_dfi.json`): sustituir por el KID oficial de cada fondo. Cada respuesta ya declara
      en `notice` que son datos de demostración.
- [ ] **Cumplimiento (RGPD / grabación / MiFID):** la llamada puede quedar registrada por ElevenLabs/Twilio y se
      envían datos de cartera a un tercero. Revisar con el responsable legal antes de usar con clientes reales.

**Funcional**
- [ ] **Nunca se ha hecho una llamada real.** Pendiente de la primera prueba end-to-end con ElevenLabs.
- [ ] `userId="CLI-001"` y `clientName` están fijos en `RebalanceDashboard.tsx`.
- [ ] Cerrar el modal **no cuelga** la llamada (el modal lo avisa). No hay endpoint para colgar.
- [ ] El estado «sin respuesta» se infiere (la llamada acaba sin pasar por «en llamada», o 90 s en «marcando»).
- [ ] El nombre exacto de los campos de la herramienta Webhook en el panel de ElevenLabs no se pudo verificar (solo su doc).

## 9. Seguridad: lo que NO se debe hacer

- **No poner secretos en el código ni en git** (clave de ElevenLabs, secreto del webhook). Solo variables de entorno.
- **Incidente:** una clave de ElevenLabs se pegó en un chat durante el desarrollo. **Debe estar revocada y
  regenerada.** No está en el repo (verificado con grep).
- **No reutilizar el controlador de ejemplo de otro chat:** duplicaba la ruta `agent-context` (Spring no arrancaría),
  dejaba el secreto en el código y devolvía una cartera inventada (1.250.000 €) en vez de la real (130.000 €).

## 10. Otros cambios incluidos en esta rama (no son de telefonía)

Dos arreglos del frontend hechos antes en la misma sesión:
- `RebalanceDashboard.tsx`: al pulsar «Rebalancear» los gráficos y la tabla no se actualizaban porque los datos
  fiscales sustituían las posiciones de la vista proyectada. Ahora solo se les superpone el `taxSummary`.
- `TaxModuleBootstrap.tsx`: el análisis fiscal no se recalculaba al cambiar la meta (la clave de caché solo usaba
  `asOf`) y el panel lateral leía posiciones sin datos fiscales.

El servicio Python (`ai-and-ingest-python`) no se ha tocado; necesita `ANTHROPIC_API_KEY` para las explicaciones de IA
(módulos 3 y 4), que es independiente de esta función.

