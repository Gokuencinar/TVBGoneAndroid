# Changelog — TVBGoneAndroid

Historial de cambios de **TVBGoneAndroid**.

**Compatibilidad:** Android 11 o posterior (`minSdk 30`). El soporte de emisión depende del hardware disponible: blaster IR integrado compatible con `ConsumerIrManager` o adaptador IR estéreo por audio.

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
