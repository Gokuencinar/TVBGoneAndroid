# Changelog — TVBGoneAndroid

Historial de cambios de **TVBGoneAndroid**.

**Compatibilidad:** Android 11 o posterior (`minSdk 30`). El soporte de emisión depende del hardware disponible: blaster IR integrado compatible con `ConsumerIrManager` o adaptador IR estéreo por audio.

## Próxima versión

### Nuevo
- Apariencia cyberpunk/gamer con interfaz HUD, superficies oscuras y acentos neón cian, magenta, violeta y verde.
- Estado `IR // READY` / `IR // CHECK` visible en la cabecera de cada pantalla.
- Feedback háptico para navegación, transmisión, éxito y error.
- La app recuerda la última pestaña, categoría, región y ritmo de barrido usados.
- La pantalla permanece activa mientras se ejecuta un barrido IR.
- Las actualizaciones muestran un resumen de las notas de la release antes de descargar la APK.
- Nueva pestaña **Mando** con interfaz de mando universal: Power, volumen, canales, mute, entradas, navegación/OK, media, teclado numérico y controles adicionales según las señales disponibles.
- Las señales compatibles de los mandos descargados desde la biblioteca online pueden guardarse juntas como perfiles reutilizables en la pestaña Mando.
- Compartir un mando genera ahora un archivo `.ir` real y temporal con permiso de lectura para la aplicación receptora.

### Mejorado
- Barra inferior, tarjetas, botones, campos, métricas, controles segmentados y estados vacíos rediseñados para mayor contraste y respuesta visual.
- El estado del accesorio en Control abre Ajustes / Info con un toque.
- El botón/gesto Atrás de Android respeta la navegación interna al entrar en IR Online.
- Se descartan respuestas antiguas de la carga de marcas online al cambiar rápidamente de fuente.
- Targets táctiles y estados seleccionados ampliados en los principales controles.
- Tocar un mando guardado en Equipos abre directamente el nuevo modo de mando completo.
- La antigua pestaña Diagnóstico pasa a llamarse **Ajustes / Info** y conserva todas las comprobaciones técnicas, copia de seguridad, actualizaciones y créditos.

### Corregido
- Al terminar un barrido universal, la tarjeta permanece visible para poder pulsar `FUNCIONÓ` y elegir entre los últimos candidatos.
- Ajustes / Info se refresca correctamente al conceder el permiso de micrófono.
- Ajustes / Info deja de mostrar como hechos ajustes de audio que la app no puede verificar y los presenta como recomendaciones.

## 0.1.0-android — 2026-09-23

### Nuevo
- Primera versión Android independiente del proyecto.
- Port de la base universal prioritaria y de la base TV-B-Gone original.
- 137 códigos Norteamérica/Asia y 138 códigos Europa.
- Soporte de protocolos NEC, NEC Extended, Samsung32, Sony SIRC 12/15/20, RC5, RC6, JVC, Kaseikyo, RCA y Pioneer.
- Emisión mediante IR integrado de Android con `ConsumerIrManager`.
- Emisión mediante adaptador IR de audio estéreo con salida diferencial izquierda/derecha.
- Barrido rápido y modo Identificar.
- Importación Flipper `.ir` RAW y parsed.
- Exportación Flipper RAW desde el núcleo.
- Biblioteca IR online.
- Captura IR inicial mediante `AudioRecord`.
- Detección heurística básica de protocolo.
- Gestión local de equipos mediante SharedPreferences/JSON.
- Interfaz OLED con Control, Códigos, Online, Aprender, Equipos y Diagnóstico.
- Compilación automática del APK mediante GitHub Actions.

### Cambiado
- El port pasa a mantenerse como repositorio Android independiente en lugar de una rama secundaria del proyecto iOS.
- El nombre visible de la aplicación se unifica como **TVBGoneAndroid**.
- Se incorpora un icono de launcher propio y se actualizan sus recursos.

### Corregido
- Corregidos cierres inesperados observados en Android 11.
- Estabilizado el ciclo de barrido IR y la gestión de estados durante la transmisión.
- Ajustada la implementación para LineageOS 18.1 / Android 11 y dispositivos equivalentes.
