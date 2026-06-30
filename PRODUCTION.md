# Preparación para producción

## Arquitectura

La aplicación funciona completamente en el dispositivo y no necesita API key.

1. Android captura y corrige un JPEG.
2. El usuario marca la columna de matrículas.
3. La app amplía y divide esa zona en franjas superpuestas.
4. ML Kit y Tesseract ejecutan el OCR local con varios tratamientos de contraste.
5. El resultado se limita a `matriculas_modelos.json` y se presenta como lista editable.

## Despliegue

- No se necesita servidor ni saldo de terceros.
- Verifica que los modelos OCR queden empaquetados en el APK/App Bundle.
- Prueba el consumo de memoria en los móviles más antiguos admitidos.
- El directorio `server` es un prototipo anterior y no participa en la aplicación Android.

## Privacidad

Las hojas permanecen dentro del móvil. La versión actual no declara permiso de Internet y no transmite imágenes.

## Evaluación

Antes de publicar, usa al menos 100 fotografías representativas: luz variable, inclinación, formatos con/sin guion, varios móviles y matrículas parecidas.

Mide recuperación, precisión y exactitud completa por hoja. Mantén siempre la revisión editable.
