# Lector de hoja de vuelos

Aplicación Android nativa escrita en Java. Captura y corrige la hoja con ML Kit Document Scanner y reconoce las matrículas completamente en el móvil, sin API, saldo ni conexión a Internet.

Clasificación:

- **800** para `787-8`.
- **900** para `787-9`.
- **Premium** para `EC-NXA`, `EC-NVZ` y `EC-NZG`.

## Desarrollo local

Requisitos: Android Studio y Android SDK 36. Abre el proyecto, conecta el móvil y ejecuta la configuración `app`.

## Flujo de uso

1. Pulsa **Escanear hoja**.
2. Captura los cuatro bordes y revisa el recorte.
3. Ajusta el rectángulo azul para incluir únicamente la columna de matrículas.
4. La app amplía la zona y la analiza en franjas superpuestas con ML Kit y Tesseract.
5. Revisa, corrige, elimina o añade matrículas antes de confirmar.

Se admiten `EC-NFM`, `ECNFM` y otros formatos equivalentes; todos se normalizan a `EC-NFM`.

## Verificación

- Android: `gradlew testDebugUnitTest assembleDebug lintDebug`.
- OCR instrumental: `gradlew connectedDebugAndroidTest`.

Consulta [PRODUCTION.md](PRODUCTION.md) antes de publicar.
